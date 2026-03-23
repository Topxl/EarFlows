package com.earflows.app.translation

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * OpenAI Realtime API — WebSocket bidirectional audio streaming.
 *
 * Protocol:
 * - Connect to wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview
 * - Send: input_audio_buffer.append (base64 PCM 16kHz 16-bit LE)
 * - Receive: response.audio.delta (base64 PCM 24kHz 16-bit LE)
 *
 * True streaming: audio in → audio out, no text intermediate.
 * Latency: ~500-800ms (depends on network).
 *
 * Session is configured for simultaneous interpretation:
 * - Input: Thai speech
 * - Output: French speech (translated)
 * - Server-side VAD for turn detection
 */
class OpenAIRealtimeWS(
    private val apiKey: String,
    private val sourceLang: String,
    private val targetLang: String
) {
    companion object {
        private const val TAG = "OpenAIRealtime"
        private const val WS_URL = "wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview"
        private const val OUTPUT_SAMPLE_RATE = 24000  // OpenAI outputs 24kHz
    }

    private var webSocket: WebSocket? = null
    private var isConnected = false

    // Queue for received audio chunks (producer: WS callback, consumer: processChunk)
    private val outputQueue = ConcurrentLinkedQueue<ShortArray>()

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun connect() {
        val srcName = langName(sourceLang)
        val tgtName = langName(targetLang)

        val request = Request.Builder()
            .url(WS_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("OpenAI-Beta", "realtime=v1")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "Connected to OpenAI Realtime")
                isConnected = true

                // Configure session for S2S translation
                val config = JSONObject().apply {
                    put("type", "session.update")
                    put("session", JSONObject().apply {
                        put("modalities", JSONArray(listOf("audio")))  // Audio only — no text
                        put("instructions", buildString {
                            append("You are a simultaneous interpreter. ")
                            append("Listen to $srcName speech and speak the $tgtName translation. ")
                            append("Translate naturally, preserving tone and meaning. ")
                            append("Start translating immediately — do not wait for complete sentences. ")
                            append("Speak only in $tgtName. Never repeat the $srcName.")
                        })
                        put("voice", "alloy")  // Output voice
                        put("input_audio_format", "pcm16")
                        put("output_audio_format", "pcm16")
                        put("turn_detection", JSONObject().apply {
                            put("type", "server_vad")
                            put("threshold", 0.4)
                            put("prefix_padding_ms", 200)
                            put("silence_duration_ms", 600)
                        })
                    })
                }
                ws.send(config.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "response.audio.delta" -> {
                            val b64 = json.optString("delta", "")
                            if (b64.isNotEmpty()) {
                                val bytes = Base64.decode(b64, Base64.DEFAULT)
                                val shorts = bytesToShorts(bytes)
                                // Resample 24kHz → 16kHz (simple decimation)
                                val resampled = resample24to16(shorts)
                                outputQueue.add(resampled)
                            }
                        }
                        "error" -> {
                            val err = json.optJSONObject("error")?.optString("message", "unknown")
                            Log.e(TAG, "API error: $err")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Message parse error: ${e.message}")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                isConnected = false
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code")
                isConnected = false
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }
        })
    }

    /**
     * Send audio chunk and return any available translated audio.
     * Non-blocking: returns whatever is in the output queue.
     */
    fun processChunk(inputPcm: ShortArray): ShortArray? {
        if (!isConnected) return null

        // Send input audio
        val bytes = shortsToBytes(inputPcm)
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        val msg = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", b64)
        }
        webSocket?.send(msg.toString())

        // Return any accumulated output
        if (outputQueue.isEmpty()) return null

        val combined = mutableListOf<Short>()
        while (outputQueue.isNotEmpty()) {
            val chunk = outputQueue.poll() ?: break
            for (s in chunk) combined.add(s)
        }

        return if (combined.isNotEmpty()) combined.toShortArray() else null
    }

    fun disconnect() {
        webSocket?.close(1000, "Release")
        webSocket = null
        isConnected = false
        outputQueue.clear()
        Log.i(TAG, "Disconnected")
    }

    // ========================================================================
    // UTILS
    // ========================================================================

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in shorts) buf.putShort(s)
        return buf.array()
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.size / 2) { buf.short }
    }

    /** Simple 24kHz → 16kHz resampling (take every 2nd out of 3 samples) */
    private fun resample24to16(input: ShortArray): ShortArray {
        val outputSize = input.size * 2 / 3
        val output = ShortArray(outputSize)
        for (i in output.indices) {
            val srcIdx = (i * 3L / 2).toInt().coerceAtMost(input.size - 1)
            output[i] = input[srcIdx]
        }
        return output
    }

    private fun langName(code: String) = when (code) {
        "tha" -> "Thai"; "fra" -> "French"; "eng" -> "English"
        "cmn" -> "Chinese"; "spa" -> "Spanish"; "deu" -> "German"
        "jpn" -> "Japanese"; "kor" -> "Korean"
        else -> code
    }
}
