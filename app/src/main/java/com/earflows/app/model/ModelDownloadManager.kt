package com.earflows.app.model

import android.content.Context
import android.util.Log
import com.earflows.app.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages downloading and caching of ML model files.
 *
 * Model storage: app internal storage /files/models/
 * Downloads from Hugging Face Hub (public ONNX models).
 *
 * Required models:
 * - Silero VAD: ~2MB (bundled in assets, no download needed)
 * - Whisper tiny ONNX: ~40MB (fallback ASR)
 * - NLLB-200 distilled ONNX: ~300MB (fallback translation)
 * - SeamlessStreaming ONNX: ~1-3GB (full pipeline, optional)
 */
class ModelDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownload"

        // Hugging Face ONNX model URLs
        private const val HF_BASE = "https://huggingface.co"

        // Whisper tiny encoder+decoder ONNX (quantized)
        private const val WHISPER_ENCODER_URL =
            "$HF_BASE/onnx-community/whisper-tiny/resolve/main/onnx/encoder_model_quantized.onnx"
        private const val WHISPER_DECODER_URL =
            "$HF_BASE/onnx-community/whisper-tiny/resolve/main/onnx/decoder_model_quantized.onnx"

        // NLLB distilled ONNX (quantized)
        private const val NLLB_ENCODER_URL =
            "$HF_BASE/onnx-community/nllb-200-distilled-600M/resolve/main/onnx/encoder_model_quantized.onnx"
        private const val NLLB_DECODER_URL =
            "$HF_BASE/onnx-community/nllb-200-distilled-600M/resolve/main/onnx/decoder_model_quantized.onnx"
    }

    data class ModelFile(
        val name: String,
        val url: String,
        val relativePath: String,
        val expectedSizeMb: Int,
        val required: Boolean = true
    )

    // All downloadable models
    val requiredModels = listOf(
        ModelFile("Whisper Tiny Encoder", WHISPER_ENCODER_URL, "whisper/encoder_model_quantized.onnx", 40),
        ModelFile("Whisper Tiny Decoder", WHISPER_DECODER_URL, "whisper/decoder_model_quantized.onnx", 40),
        ModelFile("NLLB Encoder", NLLB_ENCODER_URL, "nllb/encoder_model_quantized.onnx", 300),
        ModelFile("NLLB Decoder", NLLB_DECODER_URL, "nllb/decoder_model_quantized.onnx", 300),
    )

    // State
    private val _downloadState = MutableStateFlow(DownloadState.IDLE)
    val downloadState = _downloadState.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f) // 0.0 - 1.0
    val downloadProgress = _downloadProgress.asStateFlow()

    private val _currentModelName = MutableStateFlow("")
    val currentModelName = _currentModelName.asStateFlow()

    private val modelsDir: File
        get() = File(context.filesDir, Constants.MODEL_DIR)

    /**
     * Check if all required models are downloaded.
     */
    fun areModelsReady(): Boolean {
        return requiredModels.filter { it.required }.all { model ->
            File(modelsDir, model.relativePath).exists()
        }
    }

    /**
     * Get list of missing models.
     */
    fun getMissingModels(): List<ModelFile> {
        return requiredModels.filter { model ->
            !File(modelsDir, model.relativePath).exists()
        }
    }

    /**
     * Get total download size of missing models (MB).
     */
    fun getMissingDownloadSizeMb(): Int {
        return getMissingModels().sumOf { it.expectedSizeMb }
    }

    /**
     * Download all missing required models.
     */
    suspend fun downloadMissingModels(): Boolean = withContext(Dispatchers.IO) {
        val missing = getMissingModels()
        if (missing.isEmpty()) {
            _downloadState.value = DownloadState.COMPLETE
            return@withContext true
        }

        _downloadState.value = DownloadState.DOWNLOADING

        var completedModels = 0
        val totalModels = missing.size

        for (model in missing) {
            _currentModelName.value = model.name
            _downloadProgress.value = completedModels.toFloat() / totalModels

            val success = downloadFile(model)
            if (!success && model.required) {
                _downloadState.value = DownloadState.ERROR
                Log.e(TAG, "Failed to download required model: ${model.name}")
                return@withContext false
            }

            completedModels++
        }

        _downloadProgress.value = 1f
        _downloadState.value = DownloadState.COMPLETE
        Log.i(TAG, "All models downloaded successfully")
        true
    }

    private suspend fun downloadFile(model: ModelFile): Boolean = withContext(Dispatchers.IO) {
        val outputFile = File(modelsDir, model.relativePath)
        outputFile.parentFile?.mkdirs()

        // If file exists and is reasonable size, skip
        if (outputFile.exists() && outputFile.length() > 1000) {
            Log.i(TAG, "Model already exists: ${model.name}")
            return@withContext true
        }

        val tempFile = File(outputFile.absolutePath + ".tmp")

        try {
            Log.i(TAG, "Downloading: ${model.name} from ${model.url}")

            val connection = URL(model.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "EarFlows/1.0")

            // Support resume
            if (tempFile.exists()) {
                connection.setRequestProperty("Range", "bytes=${tempFile.length()}-")
            }

            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != 200 && responseCode != 206) {
                Log.e(TAG, "HTTP $responseCode for ${model.url}")
                connection.disconnect()
                return@withContext false
            }

            val totalBytes = connection.contentLengthLong
            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(tempFile, responseCode == 206) // Append if resuming

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead = if (responseCode == 206) tempFile.length() else 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead

                if (totalBytes > 0) {
                    val modelProgress = totalRead.toFloat() / totalBytes
                    // Combine model-level and file-level progress
                    val overallProgress = _downloadProgress.value
                    // Keep overall progress but show file progress in logs
                    if (totalRead % (1024 * 1024) == 0L) {
                        Log.d(TAG, "${model.name}: ${totalRead / (1024 * 1024)}MB / ${totalBytes / (1024 * 1024)}MB")
                    }
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()
            connection.disconnect()

            // Rename temp to final
            tempFile.renameTo(outputFile)
            Log.i(TAG, "Downloaded: ${model.name} (${outputFile.length() / (1024 * 1024)}MB)")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Download error for ${model.name}: ${e.message}", e)
            false
        }
    }

    /**
     * Get path to a specific model file.
     */
    fun getModelPath(relativePath: String): File {
        return File(modelsDir, relativePath)
    }

    fun getWhisperDir(): File = File(modelsDir, "whisper")
    fun getNllbDir(): File = File(modelsDir, "nllb")
    fun getSeamlessDir(): File = modelsDir
}

enum class DownloadState {
    IDLE,
    DOWNLOADING,
    COMPLETE,
    ERROR
}
