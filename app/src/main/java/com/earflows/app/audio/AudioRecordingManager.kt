package com.earflows.app.audio

import android.content.Context
import android.os.Environment
import android.util.Log
import com.earflows.app.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records raw audio to WAV files in segments.
 * Each segment = 5-10 minutes of audio.
 * Writes a JSON metadata file per session.
 *
 * Note: We write WAV (PCM) for simplicity and lossless quality.
 * Post-processing to Opus/OGG can be done later or via MediaCodec.
 */
class AudioRecordingManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecording"
        private val json = Json { prettyPrint = true }
    }

    private var outputStream: FileOutputStream? = null
    private var currentFile: File? = null
    private var sessionDir: File? = null
    private var segmentIndex = 0
    private var segmentBytesWritten = 0L
    private var sessionStartTime: Long = 0
    private var isRecording = false

    // Track all segments for metadata
    private val segments = mutableListOf<SegmentInfo>()

    private val maxSegmentBytes: Long
        get() = Constants.RECORDING_SEGMENT_DURATION_MS * Constants.SAMPLE_RATE * 2 / 1000  // 16-bit mono

    /**
     * Start a new recording session.
     * Creates a session directory under /Download/EarFlowsRecords/
     */
    fun startSession(sourceLang: String, targetLang: String): Boolean {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val recordsDir = File(downloadsDir, Constants.RECORDING_DIR)
        if (!recordsDir.exists() && !recordsDir.mkdirs()) {
            Log.e(TAG, "Failed to create records directory: ${recordsDir.absolutePath}")
            return false
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        sessionDir = File(recordsDir, "session_$timestamp")
        if (!sessionDir!!.mkdirs()) {
            Log.e(TAG, "Failed to create session directory")
            return false
        }

        sessionStartTime = System.currentTimeMillis()
        segmentIndex = 0
        segments.clear()
        isRecording = true

        return startNewSegment()
    }

    /**
     * Write raw PCM bytes to the current segment.
     * Automatically rolls over to a new segment when size limit reached.
     */
    fun writeChunk(pcmBytes: ByteArray) {
        if (!isRecording) return

        try {
            outputStream?.write(pcmBytes)
            segmentBytesWritten += pcmBytes.size

            // Check if we need to roll to a new segment
            if (segmentBytesWritten >= maxSegmentBytes) {
                finalizeCurrentSegment()
                startNewSegment()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio chunk: ${e.message}")
        }
    }

    /**
     * Stop recording and finalize all files.
     */
    fun stopSession(sourceLang: String, targetLang: String) {
        if (!isRecording) return
        isRecording = false

        finalizeCurrentSegment()
        writeSessionMetadata(sourceLang, targetLang)

        outputStream = null
        currentFile = null
        Log.i(TAG, "Recording session stopped. ${segments.size} segments saved.")
    }

    private fun startNewSegment(): Boolean {
        val dir = sessionDir ?: return false
        segmentIndex++
        segmentBytesWritten = 0

        val fileName = "segment_${String.format("%03d", segmentIndex)}.wav"
        currentFile = File(dir, fileName)

        return try {
            outputStream = FileOutputStream(currentFile!!)
            // Write WAV header placeholder (44 bytes), will update on finalize
            writeWavHeader(outputStream!!, 0)
            Log.i(TAG, "Started segment $segmentIndex: ${currentFile!!.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start segment: ${e.message}")
            false
        }
    }

    private fun finalizeCurrentSegment() {
        try {
            outputStream?.flush()
            outputStream?.close()

            // Update WAV header with actual data size
            currentFile?.let { file ->
                if (file.exists() && segmentBytesWritten > 0) {
                    updateWavHeader(file, segmentBytesWritten)
                    segments.add(
                        SegmentInfo(
                            filename = file.name,
                            durationMs = segmentBytesWritten * 1000 / (Constants.SAMPLE_RATE * 2),
                            sizeBytes = file.length()
                        )
                    )
                    Log.i(TAG, "Finalized segment: ${file.name}, ${segmentBytesWritten} bytes")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing segment: ${e.message}")
        }
    }

    private fun writeSessionMetadata(sourceLang: String, targetLang: String) {
        val dir = sessionDir ?: return
        val metadata = SessionMetadata(
            sessionStart = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(sessionStartTime)),
            sessionEnd = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
            durationMs = System.currentTimeMillis() - sessionStartTime,
            sourceLang = sourceLang,
            targetLang = targetLang,
            sampleRate = Constants.SAMPLE_RATE,
            segments = segments.toList()
        )

        try {
            val metaFile = File(dir, "session_metadata.json")
            metaFile.writeText(json.encodeToString(SessionMetadata.serializer(), metadata))
            Log.i(TAG, "Session metadata written: ${metaFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing metadata: ${e.message}")
        }
    }

    // --- WAV header utilities ---

    private fun writeWavHeader(out: FileOutputStream, dataSize: Long) {
        val totalSize = dataSize + 36
        val header = ByteArray(44)
        // RIFF chunk
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        writeIntLE(header, 4, totalSize.toInt())
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        // fmt sub-chunk
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeIntLE(header, 16, 16) // Sub-chunk size
        writeShortLE(header, 20, 1) // PCM format
        writeShortLE(header, 22, 1) // Mono
        writeIntLE(header, 24, Constants.SAMPLE_RATE)
        writeIntLE(header, 28, Constants.SAMPLE_RATE * 2) // Byte rate
        writeShortLE(header, 32, 2) // Block align
        writeShortLE(header, 34, 16) // Bits per sample
        // data sub-chunk
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        writeIntLE(header, 40, dataSize.toInt())

        out.write(header)
    }

    private fun updateWavHeader(file: File, dataSize: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            val totalSize = dataSize + 36
            raf.seek(4)
            raf.write(intToLE(totalSize.toInt()))
            raf.seek(40)
            raf.write(intToLE(dataSize.toInt()))
        }
    }

    private fun writeIntLE(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = (value shr 8 and 0xFF).toByte()
        arr[offset + 2] = (value shr 16 and 0xFF).toByte()
        arr[offset + 3] = (value shr 24 and 0xFF).toByte()
    }

    private fun writeShortLE(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = (value shr 8 and 0xFF).toByte()
    }

    private fun intToLE(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 24 and 0xFF).toByte()
        )
    }
}

@Serializable
data class SessionMetadata(
    val sessionStart: String,
    val sessionEnd: String,
    val durationMs: Long,
    val sourceLang: String,
    val targetLang: String,
    val sampleRate: Int,
    val segments: List<SegmentInfo>
)

@Serializable
data class SegmentInfo(
    val filename: String,
    val durationMs: Long,
    val sizeBytes: Long
)
