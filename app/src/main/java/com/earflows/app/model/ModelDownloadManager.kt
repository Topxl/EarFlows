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
 * Downloads ML models from Hugging Face Hub.
 * Uses HttpURLConnection with manual redirect following
 * (HuggingFace uses 302/307 redirects to their CDN).
 */
class ModelDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownload"
        private const val HF = "https://huggingface.co"

        private const val WHISPER_ENC =
            "$HF/onnx-community/whisper-tiny/resolve/main/onnx/encoder_model_quantized.onnx"
        private const val WHISPER_DEC =
            "$HF/onnx-community/whisper-tiny/resolve/main/onnx/decoder_model_merged.onnx"
        private const val NLLB_ENC =
            "$HF/Xenova/nllb-200-distilled-600M/resolve/main/onnx/encoder_model_quantized.onnx"
        private const val NLLB_DEC =
            "$HF/Xenova/nllb-200-distilled-600M/resolve/main/onnx/decoder_model_quantized.onnx"
    }

    data class ModelFile(
        val name: String,
        val url: String,
        val relativePath: String,
        val expectedSizeMb: Int,
        val required: Boolean = true
    )

    val requiredModels = listOf(
        ModelFile("Whisper Encoder", WHISPER_ENC, "whisper/encoder_model_quantized.onnx", 10),
        ModelFile("Whisper Decoder", WHISPER_DEC, "whisper/decoder_model_merged.onnx", 49),
        ModelFile("NLLB Encoder", NLLB_ENC, "nllb/encoder_model_quantized.onnx", 310),
        ModelFile("NLLB Decoder", NLLB_DEC, "nllb/decoder_model_quantized.onnx", 310),
    )

    private val _downloadState = MutableStateFlow(DownloadState.IDLE)
    val downloadState = _downloadState.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress = _downloadProgress.asStateFlow()

    private val _currentModelName = MutableStateFlow("")
    val currentModelName = _currentModelName.asStateFlow()

    private val _errorMessage = MutableStateFlow("")
    val errorMessage = _errorMessage.asStateFlow()

    // Full log for debugging — shown in UI on error
    private val _downloadLog = MutableStateFlow("")
    val downloadLog = _downloadLog.asStateFlow()

    private fun log(msg: String) {
        Log.i(TAG, msg)
        _downloadLog.value += "$msg\n"
    }

    private fun logErr(msg: String, e: Exception? = null) {
        Log.e(TAG, msg, e)
        _downloadLog.value += "ERROR: $msg\n"
        if (e != null) _downloadLog.value += "  ${e.javaClass.simpleName}: ${e.message}\n"
    }

    private val modelsDir: File
        get() = File(context.filesDir, Constants.MODEL_DIR)

    fun areModelsReady(): Boolean = requiredModels.filter { it.required }.all {
        val f = File(modelsDir, it.relativePath)
        f.exists() && f.length() > 10_000
    }

    fun getMissingModels(): List<ModelFile> = requiredModels.filter {
        val f = File(modelsDir, it.relativePath)
        !f.exists() || f.length() < 10_000
    }

    fun getMissingDownloadSizeMb(): Int = getMissingModels().sumOf { it.expectedSizeMb }

    suspend fun downloadMissingModels(): Boolean = withContext(Dispatchers.IO) {
        val missing = getMissingModels()
        if (missing.isEmpty()) {
            _downloadState.value = DownloadState.COMPLETE
            return@withContext true
        }

        _downloadState.value = DownloadState.DOWNLOADING
        _downloadProgress.value = 0f
        _errorMessage.value = ""
        _downloadLog.value = ""

        log("modelsDir: ${modelsDir.absolutePath}")
        log("modelsDir exists: ${modelsDir.exists()}")
        log("modelsDir writable: ${modelsDir.canWrite()}")
        log("Free space: ${modelsDir.parentFile?.freeSpace?.div(1024 * 1024)}MB")
        log("Missing models: ${missing.size}")
        missing.forEach { log("  - ${it.name} (${it.expectedSizeMb}MB)") }

        var done = 0
        val total = missing.size

        for (model in missing) {
            _currentModelName.value = model.name

            try {
                val ok = downloadOneFile(model) { filePct ->
                    _downloadProgress.value = (done.toFloat() + filePct) / total
                }
                if (!ok) {
                    _downloadState.value = DownloadState.ERROR
                    return@withContext false
                }
            } catch (e: Exception) {
                logErr("EXCEPTION: ${model.name}", e)
                _errorMessage.value = "${model.name}: ${e.javaClass.simpleName}: ${e.message}"
                _downloadState.value = DownloadState.ERROR
                return@withContext false
            }

            done++
            _downloadProgress.value = done.toFloat() / total
        }

        _downloadProgress.value = 1f
        _downloadState.value = DownloadState.COMPLETE
        log("All models downloaded OK")
        true
    }

    private fun downloadOneFile(model: ModelFile, onProgress: (Float) -> Unit): Boolean {
        val outputFile = File(modelsDir, model.relativePath)
        outputFile.parentFile?.mkdirs()

        if (outputFile.exists() && outputFile.length() > 10_000) {
            log("Skip (exists): ${model.name} ${outputFile.length() / 1024}KB")
            return true
        }

        val tempFile = File(outputFile.absolutePath + ".tmp")
        if (tempFile.exists()) tempFile.delete()

        log("== ${model.name} ==")
        log("URL: ${model.url}")
        log("Dest: ${outputFile.absolutePath}")

        // Follow redirects manually (HuggingFace: 307 → 302 → 200)
        var url = model.url
        var conn: HttpURLConnection? = null

        for (hop in 0..10) {
            log("Hop $hop: $url")

            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 30_000
                    readTimeout = 5 * 60_000
                    setRequestProperty("User-Agent", "EarFlows/1.0")
                }
            } catch (e: Exception) {
                logErr("openConnection failed at hop $hop", e)
                _errorMessage.value = "Connexion impossible: ${e.message}"
                return false
            }

            val code: Int
            try {
                code = conn!!.responseCode
            } catch (e: Exception) {
                logErr("getResponseCode failed at hop $hop", e)
                _errorMessage.value = "Pas de reponse serveur: ${e.message}"
                conn?.disconnect()
                return false
            }

            log("HTTP $code")

            when {
                code in 300..399 -> {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()

                    if (loc.isNullOrBlank()) {
                        log("ERROR: redirect sans Location")
                        _errorMessage.value = "Redirect sans Location (hop $hop)"
                        return false
                    }

                    url = if (loc.startsWith("http")) loc
                          else URL(URL(url), loc).toString()
                    continue
                }
                code == 200 -> {
                    log("200 OK, starting download")
                    break
                }
                else -> {
                    val errorBody = try { conn.errorStream?.bufferedReader()?.readText()?.take(200) ?: "" } catch (_: Exception) { "" }
                    log("ERROR: HTTP $code | $errorBody")
                    _errorMessage.value = "HTTP $code: ${model.name}"
                    conn.disconnect()
                    return false
                }
            }
        }

        val c = conn ?: run {
            _errorMessage.value = "Connexion null"
            return false
        }

        val totalBytes = c.contentLengthLong
        log("Content-Length: $totalBytes (${totalBytes / (1024 * 1024)}MB)")

        var totalRead = 0L

        try {
            c.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buf = ByteArray(65_536)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        totalRead += n
                        if (totalBytes > 0) {
                            onProgress(totalRead.toFloat() / totalBytes)
                        }
                    }
                    output.flush()
                }
            }
        } catch (e: Exception) {
            logErr("Download stream error after ${totalRead} bytes", e)
            _errorMessage.value = "Erreur lecture: ${e.message}"
            return false
        } finally {
            c.disconnect()
        }

        log("Downloaded: $totalRead bytes")

        if (totalRead < 10_000) {
            _errorMessage.value = "${model.name}: trop petit ($totalRead bytes)"
            tempFile.delete()
            return false
        }

        if (outputFile.exists()) outputFile.delete()
        if (!tempFile.renameTo(outputFile)) {
            tempFile.copyTo(outputFile, overwrite = true)
            tempFile.delete()
        }

        log("OK: ${model.name} (${outputFile.length() / (1024 * 1024)}MB)")
        return true
    }

    fun getModelPath(relativePath: String): File = File(modelsDir, relativePath)
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
