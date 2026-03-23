package com.earflows.app.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and manages sherpa-onnx models for offline S2S.
 *
 * Required models (~150MB total):
 * 1. Thai ASR (zipformer streaming): ~30MB
 *    - encoder, decoder, joiner ONNX + tokens.txt
 * 2. Silero VAD: ~2MB (already in assets)
 * 3. French TTS (Piper siwis-medium): ~50MB
 *    - model ONNX + espeak-ng data
 *
 * All from: https://github.com/k2-fsa/sherpa-onnx/releases
 */
class SherpaModelManager(private val context: Context) {

    companion object {
        private const val TAG = "SherpaModels"
        // Temporary: local HTTP server on dev PC (change to GitHub/HF in production)
        private const val MODEL_SERVER = "http://192.168.100.24:8765"     // Thai models
        private const val WHISPER_SERVER = "http://192.168.100.24:8766"   // Whisper models
        private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

        // Thai ASR — zipformer offline transducer (int8 quantized)
        private const val ASR_DIR = "sherpa-onnx-zipformer-thai-2024-06-20"
        private val ASR_FILES = listOf(
            "encoder-epoch-12-avg-5.int8.onnx",   // 155MB
            "decoder-epoch-12-avg-5.int8.onnx",   // 1.3MB
            "joiner-epoch-12-avg-5.int8.onnx",    // 1MB
            "tokens.txt"                            // 39KB
        )

        // Whisper tiny multilingual — supports French + 98 other languages
        private const val WHISPER_DIR = "sherpa-onnx-whisper-tiny"
        private val WHISPER_FILES = listOf(
            "tiny-encoder.int8.onnx",
            "tiny-decoder.int8.onnx",
            "tiny-tokens.txt"
        )

        // French TTS — Piper siwis-medium
        private const val TTS_TAG = "tts-models"
        private const val TTS_DIR = "vits-piper-fr_FR-siwis-medium"
        private val TTS_FILES = listOf(
            "fr_FR-siwis-medium.onnx",
            "fr_FR-siwis-medium.onnx.json"
        )
        // espeak-ng-data is needed for Piper phonemizer
        private const val ESPEAK_TAR = "espeak-ng-data.tar.bz2"
    }

    private val modelsDir: File
        get() = File(context.filesDir, "sherpa")

    val asrDir: File get() = File(modelsDir, ASR_DIR)
    val ttsDir: File get() = File(modelsDir, TTS_DIR)
    val espeakDir: File get() = File(modelsDir, "espeak-ng-data")

    private val _progress = MutableStateFlow(0f)
    val progress = _progress.asStateFlow()

    private val _status = MutableStateFlow("idle")
    val status = _status.asStateFlow()

    val whisperDir: File get() = File(modelsDir, WHISPER_DIR)

    fun isWhisperReady(): Boolean = WHISPER_FILES.all {
        val f = File(whisperDir, it)
        f.exists() && f.length() > 1000
    }

    // Whisper model paths
    fun whisperEncoderPath() = File(whisperDir, WHISPER_FILES[0]).absolutePath
    fun whisperDecoderPath() = File(whisperDir, WHISPER_FILES[1]).absolutePath
    fun whisperTokensPath() = File(whisperDir, WHISPER_FILES[2]).absolutePath

    fun isAsrReady(): Boolean {
        // First check internal storage
        if (ASR_FILES.all { File(asrDir, it).exists() && File(asrDir, it).length() > 1000 }) return true

        // Try to copy from Download/sherpa_models if available
        val dlDir = File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS), "sherpa_models")
        if (dlDir.exists() && ASR_FILES.all { File(dlDir, it).exists() }) {
            asrDir.mkdirs()
            for (f in ASR_FILES) {
                val src = File(dlDir, f)
                val dst = File(asrDir, f)
                if (!dst.exists() || dst.length() < 1000) {
                    try { src.copyTo(dst, overwrite = true); Log.i(TAG, "Copied $f from Downloads") }
                    catch (e: Exception) { Log.e(TAG, "Copy $f failed: ${e.message}") }
                }
            }
            return ASR_FILES.all { File(asrDir, it).exists() && File(asrDir, it).length() > 1000 }
        }

        return false
    }
    fun isTtsReady(): Boolean = TTS_FILES.all { File(ttsDir, it).exists() }
    fun isReady(): Boolean = isAsrReady() && isTtsReady()

    fun totalDownloadSizeMb(): Int {
        var size = 0
        if (!isAsrReady()) size += 30
        if (!isTtsReady()) size += 55
        return size
    }

    // ASR model file paths (for sherpa-onnx config)
    fun encoderPath() = File(asrDir, ASR_FILES[0]).absolutePath
    fun decoderPath() = File(asrDir, ASR_FILES[1]).absolutePath
    fun joinerPath() = File(asrDir, ASR_FILES[2]).absolutePath
    fun tokensPath() = File(asrDir, ASR_FILES[3]).absolutePath

    // TTS model file paths
    fun ttsModelPath() = File(ttsDir, TTS_FILES[0]).absolutePath
    fun ttsConfigPath() = File(ttsDir, TTS_FILES[1]).absolutePath
    fun espeakDataPath() = espeakDir.absolutePath

    /**
     * Download all missing models.
     */
    suspend fun downloadAll(): Boolean = withContext(Dispatchers.IO) {
        val totalFiles = mutableListOf<Pair<String, File>>()

        // ASR files
        if (!isAsrReady()) {
            asrDir.mkdirs()
            for (f in ASR_FILES) {
                val url = "$MODEL_SERVER/$f"  // Direct file from local server
                totalFiles.add(url to File(asrDir, f))
            }
        }

        // Whisper multilingual (for French ASR in reply mode)
        if (!isWhisperReady()) {
            whisperDir.mkdirs()
            for (f in WHISPER_FILES) {
                val url = "$WHISPER_SERVER/$f"
                totalFiles.add(url to File(whisperDir, f))
            }
        }

        // TTS files
        if (!isTtsReady()) {
            ttsDir.mkdirs()
            for (f in TTS_FILES) {
                val url = "$GH/$TTS_TAG/$TTS_DIR/$f"
                totalFiles.add(url to File(ttsDir, f))
            }
        }

        // espeak-ng-data (needed for Piper)
        if (!espeakDir.exists()) {
            // espeak-ng-data is complex (tar.bz2) — extract from assets if bundled,
            // or download individual files. For now, try to copy from sherpa-onnx AAR
            // which bundles it, or create a minimal set.
            extractEspeakData()
        }

        if (totalFiles.isEmpty()) {
            _status.value = "ready"
            _progress.value = 1f
            return@withContext true
        }

        _status.value = "downloading"
        var done = 0

        for ((url, file) in totalFiles) {
            if (file.exists() && file.length() > 1000) {
                done++
                continue
            }

            _status.value = "Downloading ${file.name}..."
            Log.i(TAG, "Downloading: ${file.name}")

            val ok = downloadFile(url, file)
            if (!ok) {
                _status.value = "Error: ${file.name}"
                Log.e(TAG, "Failed: ${file.name}")
                return@withContext false
            }

            done++
            _progress.value = done.toFloat() / totalFiles.size
        }

        _status.value = "ready"
        _progress.value = 1f
        Log.i(TAG, "All sherpa models ready")
        true
    }

    private fun downloadFile(urlStr: String, output: File): Boolean {
        val temp = File(output.absolutePath + ".tmp")
        try {
            var url = urlStr
            for (hop in 0..10) {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 30_000
                    readTimeout = 300_000
                    setRequestProperty("User-Agent", "EarFlows/1.0")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    url = if (loc.startsWith("http")) loc else URL(URL(url), loc).toString()
                    continue
                }
                if (code != 200) {
                    Log.e(TAG, "HTTP $code for ${output.name}")
                    conn.disconnect()
                    return false
                }

                conn.inputStream.use { inp ->
                    FileOutputStream(temp).use { out ->
                        val buf = ByteArray(65536)
                        var n: Int
                        while (inp.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                        }
                    }
                }
                conn.disconnect()

                if (output.exists()) output.delete()
                temp.renameTo(output)
                Log.i(TAG, "OK: ${output.name} (${output.length() / 1024}KB)")
                return true
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}")
            temp.delete()
            return false
        }
    }

    /**
     * Extract espeak-ng-data from assets or create minimal phonemizer config.
     * Piper TTS needs this for French phoneme generation.
     */
    private fun extractEspeakData() {
        espeakDir.mkdirs()
        // Check if sherpa-onnx AAR bundles espeak-ng-data in its assets
        try {
            val assetFiles = context.assets.list("espeak-ng-data") ?: emptyArray()
            if (assetFiles.isNotEmpty()) {
                Log.i(TAG, "Extracting espeak-ng-data from assets (${assetFiles.size} files)")
                copyAssetsDir("espeak-ng-data", espeakDir)
                return
            }
        } catch (_: Exception) {}

        Log.w(TAG, "espeak-ng-data not found in assets — TTS may use fallback phonemizer")
    }

    private fun copyAssetsDir(assetPath: String, targetDir: File) {
        targetDir.mkdirs()
        val files = context.assets.list(assetPath) ?: return
        for (f in files) {
            val subPath = "$assetPath/$f"
            val subFiles = context.assets.list(subPath)
            if (subFiles != null && subFiles.isNotEmpty()) {
                copyAssetsDir(subPath, File(targetDir, f))
            } else {
                context.assets.open(subPath).use { inp ->
                    FileOutputStream(File(targetDir, f)).use { out ->
                        inp.copyTo(out)
                    }
                }
            }
        }
    }
}
