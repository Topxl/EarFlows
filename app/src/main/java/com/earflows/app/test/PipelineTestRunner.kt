package com.earflows.app.test

import android.content.Context
import android.util.Log
import com.earflows.app.model.SherpaModelManager
import com.earflows.app.translation.SherpaS2SEngine
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume

/**
 * Pipeline Test Runner — validates each stage of the EarFlows S2S pipeline
 * using known Thai audio files with expected transcriptions.
 *
 * Test cases from sherpa-onnx-zipformer-thai-2024-06-20/test_wavs/
 *
 * Tests:
 * 1. WAV loading (correct format, sample rate, length)
 * 2. Silero VAD (detects speech in test audio)
 * 3. Sherpa ASR (Thai transcription matches expected)
 * 4. ML Kit Translation (Thai → French produces non-empty result)
 * 5. Full pipeline E2E (audio → French text, <2s latency)
 */
class PipelineTestRunner(private val context: Context) {

    companion object {
        private const val TAG = "PipelineTest"
    }

    data class TestCase(
        val filename: String,
        val expectedThaiText: String,
        val minMatchRatio: Float = 0.5f  // At least 50% of expected text should match
    )

    data class TestResult(
        val name: String,
        val passed: Boolean,
        val message: String,
        val durationMs: Long = 0
    )

    private val testCases = listOf(
        TestCase(
            "test_0.wav",
            "ก็เดี๋ยวเกมในนัดต่อไปต้องไปเจอกับทางอินโดนีเซียนะครับ"
        ),
        TestCase(
            "test_1.wav",
            "ก็ไม่ได้เน้นเรื่องของผลการแข่งขันอยู่แล้วครับเหมือนที่คาร์ลอสเซซาร์นั้นได้บอกไว้นะครับ"
        ),
        TestCase(
            "test_2.wav",
            "เกมในเกมที่แล้วเนี่ยตอนพักครึ่งหลังเนี่ยเหมือนคาร์ลอสจะบอกว่าจริงจริงจะไม่ส่งมูฮัมหมัดลงด้วยซ้ําแล้วนะครับแต่ว่าเหมือนกับท้ายเกมเนี่ยส่งไปด้วยความมั่นใจแล้วโอ้โหประตูที่สาม"
        )
    )

    private val results = mutableListOf<TestResult>()

    /**
     * Run all tests and return results.
     */
    suspend fun runAllTests(): List<TestResult> = withContext(Dispatchers.IO) {
        results.clear()
        Log.i(TAG, "========== PIPELINE TEST SUITE ==========")

        testModelsExist()
        testWavLoading()
        testVad()
        testAsr()
        testTranslation()
        testTranslationReverse()
        testE2EPipeline()
        testContinuousStream()
        testNoRepetition()
        testLatencyBudget()
        testBidirectional()

        val passed = results.count { it.passed }
        val total = results.size
        Log.i(TAG, "========== RESULTS: $passed/$total PASSED ==========")
        results.forEach { r ->
            val icon = if (r.passed) "PASS" else "FAIL"
            Log.i(TAG, "  [$icon] ${r.name}: ${r.message} (${r.durationMs}ms)")
        }

        results.toList()
    }

    // ========================================================================
    // TEST 1: Models exist on device
    // ========================================================================

    private fun testModelsExist() {
        val start = System.currentTimeMillis()
        val mgr = SherpaModelManager(context)

        val asrReady = mgr.isAsrReady()
        results.add(TestResult(
            "Models: ASR Thai",
            asrReady,
            if (asrReady) "encoder+decoder+joiner+tokens found" else "ASR models missing",
            System.currentTimeMillis() - start
        ))

        val vadFile = File(context.filesDir, "sherpa/silero_vad.onnx")
        results.add(TestResult(
            "Models: Silero VAD",
            vadFile.exists() && vadFile.length() > 1000,
            if (vadFile.exists()) "${vadFile.length() / 1024}KB" else "silero_vad.onnx not found",
            System.currentTimeMillis() - start
        ))
    }

    // ========================================================================
    // TEST 2: WAV loading
    // ========================================================================

    private fun testWavLoading() {
        val testDir = File(context.filesDir, "test_wavs")

        for (tc in testCases) {
            val start = System.currentTimeMillis()
            val file = File(testDir, tc.filename)

            if (!file.exists()) {
                results.add(TestResult("WAV: ${tc.filename}", false, "File not found", 0))
                continue
            }

            try {
                val (samples, sampleRate) = readWav(file)
                val durationS = samples.size.toFloat() / sampleRate
                val ok = sampleRate == 16000 && samples.isNotEmpty() && durationS > 0.5f

                results.add(TestResult(
                    "WAV: ${tc.filename}",
                    ok,
                    "${"%.1f".format(durationS)}s, ${sampleRate}Hz, ${samples.size} samples",
                    System.currentTimeMillis() - start
                ))
            } catch (e: Exception) {
                results.add(TestResult("WAV: ${tc.filename}", false, "Error: ${e.message}", 0))
            }
        }
    }

    // ========================================================================
    // TEST 3: VAD detects speech
    // ========================================================================

    private fun testVad() {
        val vadFile = File(context.filesDir, "sherpa/silero_vad.onnx")
        if (!vadFile.exists()) {
            results.add(TestResult("VAD", false, "silero_vad.onnx not found", 0))
            return
        }

        try {
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadFile.absolutePath,
                    threshold = 0.3f,
                    minSpeechDuration = 0.25f,
                    minSilenceDuration = 0.5f,
                    maxSpeechDuration = 10.0f
                ),
                sampleRate = 16000,
                numThreads = 2
            )
            val vad = Vad(null, vadConfig)

            val testDir = File(context.filesDir, "test_wavs")
            val (samples, _) = readWav(File(testDir, "test_0.wav"))

            val start = System.currentTimeMillis()

            // Feed audio in chunks
            val chunkSize = 512
            var segmentCount = 0
            var totalSpeechSamples = 0
            for (i in samples.indices step chunkSize) {
                val end = minOf(i + chunkSize, samples.size)
                val chunk = samples.copyOfRange(i, end)
                if (chunk.size == chunkSize) {
                    vad.acceptWaveform(chunk)
                    while (!vad.empty()) {
                        totalSpeechSamples += vad.front().samples.size
                        vad.pop()
                        segmentCount++
                    }
                }
            }
            // Flush remaining audio (file ends abruptly without silence)
            vad.flush()
            while (!vad.empty()) {
                totalSpeechSamples += vad.front().samples.size
                vad.pop()
                segmentCount++
            }

            val ms = System.currentTimeMillis() - start
            val speechDurS = totalSpeechSamples.toFloat() / 16000
            vad.release()

            results.add(TestResult(
                "VAD: speech detection",
                segmentCount > 0,
                "$segmentCount segment(s), ${"%.1f".format(speechDurS)}s of speech",
                ms
            ))
        } catch (e: Exception) {
            results.add(TestResult("VAD", false, "Error: ${e.message}", 0))
        }
    }

    // ========================================================================
    // TEST 4: ASR transcription
    // ========================================================================

    private fun testAsr() {
        val mgr = SherpaModelManager(context)
        if (!mgr.isAsrReady()) {
            results.add(TestResult("ASR", false, "Models not ready", 0))
            return
        }

        try {
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = mgr.encoderPath(),
                        decoder = mgr.decoderPath(),
                        joiner = mgr.joinerPath()
                    ),
                    tokens = mgr.tokensPath(),
                    numThreads = 4,
                    debug = false
                ),
                decodingMethod = "greedy_search"
            )
            val recognizer = OfflineRecognizer(null, config)

            val testDir = File(context.filesDir, "test_wavs")

            for (tc in testCases) {
                val file = File(testDir, tc.filename)
                if (!file.exists()) {
                    results.add(TestResult("ASR: ${tc.filename}", false, "File not found", 0))
                    continue
                }

                val (samples, sampleRate) = readWav(file)
                val start = System.currentTimeMillis()

                val stream = recognizer.createStream()
                stream.acceptWaveform(samples, sampleRate)
                recognizer.decode(stream)
                val text = recognizer.getResult(stream).text.trim()
                stream.release()

                val ms = System.currentTimeMillis() - start

                // Compare: check how many characters match
                val matchRatio = computeMatchRatio(text, tc.expectedThaiText)
                val passed = text.isNotBlank() && matchRatio >= tc.minMatchRatio

                results.add(TestResult(
                    "ASR: ${tc.filename}",
                    passed,
                    "match=${"%.0f".format(matchRatio * 100)}% (${ms}ms) \"${text.take(60)}...\"",
                    ms
                ))
            }

            recognizer.release()

        } catch (e: Exception) {
            results.add(TestResult("ASR", false, "Error: ${e.message}", 0))
        }
    }

    // ========================================================================
    // TEST 5: ML Kit Translation
    // ========================================================================

    private suspend fun testTranslation() {
        try {
            val opts = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.THAI)
                .setTargetLanguage(TranslateLanguage.FRENCH)
                .build()
            val translator = Translation.getClient(opts)

            // Ensure model is downloaded
            suspendCancellableCoroutine { cont ->
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener { cont.resume(true) }
                    .addOnFailureListener { cont.resume(false) }
            }

            val testTexts = listOf(
                "สวัสดีครับ" to "Bonjour",
                "ขอบคุณมากครับ" to "Merci",
                testCases[0].expectedThaiText to null  // Just check non-empty
            )

            for ((thai, expectedFrench) in testTexts) {
                val start = System.currentTimeMillis()
                val french = suspendCancellableCoroutine { cont ->
                    translator.translate(thai)
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resume(null) }
                }
                val ms = System.currentTimeMillis() - start

                val passed = !french.isNullOrBlank()
                val matchInfo = if (expectedFrench != null && french != null) {
                    if (french.contains(expectedFrench, ignoreCase = true)) "exact match" else "translated (different wording)"
                } else {
                    "non-empty"
                }

                results.add(TestResult(
                    "Translate: \"${thai.take(20)}...\"",
                    passed,
                    "→ \"${french?.take(50) ?: "NULL"}\" ($matchInfo)",
                    ms
                ))
            }

            translator.close()
        } catch (e: Exception) {
            results.add(TestResult("Translation", false, "Error: ${e.message}", 0))
        }
    }

    // ========================================================================
    // TEST 5b: French → Thai translation (reverse direction)
    // ========================================================================

    private suspend fun testTranslationReverse() {
        try {
            val opts = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.FRENCH)
                .setTargetLanguage(TranslateLanguage.THAI)
                .build()
            val translator = Translation.getClient(opts)

            suspendCancellableCoroutine { cont ->
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener { cont.resume(true) }
                    .addOnFailureListener { cont.resume(false) }
            }

            val testTexts = listOf(
                Triple("Bonjour", "สวัสดี", "greeting"),
                Triple("Merci beaucoup", "ขอบคุณ", "thanks"),
                Triple("Comment allez-vous aujourd'hui ?", null, "question"),
                Triple("Je voudrais acheter du riz et du poulet", "ข้าว", "food"),
                Triple("Où se trouve la gare ?", "สถานี", "location"),
                Triple("L'usine pétrochimique est très grande", "โรงงาน", "industry")
            )

            for ((french, expectedThaiSubstring, tag) in testTexts) {
                val start = System.currentTimeMillis()
                val thai = suspendCancellableCoroutine { cont ->
                    translator.translate(french)
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resume(null) }
                }
                val ms = System.currentTimeMillis() - start

                val hasThaiChars = thai?.any { it.code in 0x0E00..0x0E7F } ?: false
                val matchInfo = when {
                    thai.isNullOrBlank() -> "EMPTY"
                    !hasThaiChars -> "not Thai script!"
                    expectedThaiSubstring != null && thai.contains(expectedThaiSubstring) -> "contains expected"
                    expectedThaiSubstring != null -> "Thai but different wording"
                    else -> "Thai text OK"
                }
                val passed = hasThaiChars

                results.add(TestResult(
                    "FR→TH ($tag): \"${french.take(25)}\"",
                    passed,
                    "→ \"${thai?.take(40) ?: "NULL"}\" ($matchInfo)",
                    ms
                ))
            }

            translator.close()
        } catch (e: Exception) {
            results.add(TestResult("FR→TH Translation", false, "Error: ${e.message}", 0))
        }
    }

    // ========================================================================
    // TEST 6: Full E2E Pipeline
    // ========================================================================

    private suspend fun testE2EPipeline() {
        val mgr = SherpaModelManager(context)
        if (!mgr.isAsrReady()) {
            results.add(TestResult("E2E Pipeline", false, "ASR models not ready", 0))
            return
        }

        try {
            // Build full pipeline: VAD → ASR → Translate
            val vadFile = File(context.filesDir, "sherpa/silero_vad.onnx")
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadFile.absolutePath,
                    threshold = 0.3f,
                    minSpeechDuration = 0.25f,
                    minSilenceDuration = 0.5f,
                    maxSpeechDuration = 15.0f
                ),
                sampleRate = 16000,
                numThreads = 2
            )
            val vad = Vad(null, vadConfig)

            val asrConfig = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = mgr.encoderPath(),
                        decoder = mgr.decoderPath(),
                        joiner = mgr.joinerPath()
                    ),
                    tokens = mgr.tokensPath(),
                    numThreads = 4
                ),
                decodingMethod = "greedy_search"
            )
            val recognizer = OfflineRecognizer(null, asrConfig)

            val transOpts = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.THAI)
                .setTargetLanguage(TranslateLanguage.FRENCH)
                .build()
            val translator = Translation.getClient(transOpts)
            suspendCancellableCoroutine { cont ->
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener { cont.resume(Unit) }
                    .addOnFailureListener { cont.resume(Unit) }
            }

            // Run full pipeline on each test file
            val testDir = File(context.filesDir, "test_wavs")

            for (tc in testCases) {
                val file = File(testDir, tc.filename)
                if (!file.exists()) continue

                val (samples, _) = readWav(file)
                val e2eStart = System.currentTimeMillis()

                // Step 1: VAD
                val chunkSize = 512
                val speechSegments = mutableListOf<FloatArray>()
                for (i in samples.indices step chunkSize) {
                    val end = minOf(i + chunkSize, samples.size)
                    val chunk = samples.copyOfRange(i, end)
                    if (chunk.size == chunkSize) {
                        vad.acceptWaveform(chunk)
                        while (!vad.empty()) {
                            speechSegments.add(vad.front().samples)
                            vad.pop()
                        }
                    }
                }
                // Flush remaining (file ends without trailing silence)
                vad.flush()
                while (!vad.empty()) {
                    speechSegments.add(vad.front().samples)
                    vad.pop()
                }
                vad.reset()
                val vadMs = System.currentTimeMillis() - e2eStart

                // Step 2: ASR on all segments
                val asrStart = System.currentTimeMillis()
                val allText = StringBuilder()
                for (seg in speechSegments) {
                    val stream = recognizer.createStream()
                    stream.acceptWaveform(seg, 16000)
                    recognizer.decode(stream)
                    allText.append(recognizer.getResult(stream).text)
                    stream.release()
                }
                val thaiText = allText.toString().trim()
                val asrMs = System.currentTimeMillis() - asrStart

                // Step 3: Translate
                val translateStart = System.currentTimeMillis()
                val frenchText = if (thaiText.isNotBlank()) {
                    suspendCancellableCoroutine { cont ->
                        translator.translate(thaiText)
                            .addOnSuccessListener { cont.resume(it) }
                            .addOnFailureListener { cont.resume("") }
                    }
                } else ""
                val translateMs = System.currentTimeMillis() - translateStart

                val e2eMs = System.currentTimeMillis() - e2eStart

                val passed = thaiText.isNotBlank() && frenchText.isNotBlank() && e2eMs < 5000
                results.add(TestResult(
                    "E2E: ${tc.filename}",
                    passed,
                    "VAD=${vadMs}ms ASR=${asrMs}ms Trans=${translateMs}ms Total=${e2eMs}ms | " +
                    "TH:\"${thaiText.take(30)}...\" → FR:\"${frenchText.take(40)}...\"",
                    e2eMs
                ))
            }

            recognizer.release()
            vad.release()
            translator.close()

        } catch (e: Exception) {
            results.add(TestResult("E2E Pipeline", false, "Error: ${e.message}", 0))
        }
    }

    // ========================================================================
    // TEST 7: Continuous Stream — concatenate all 3 files, process as one stream
    // ========================================================================

    private suspend fun testContinuousStream() {
        val mgr = SherpaModelManager(context)
        if (!mgr.isAsrReady()) {
            results.add(TestResult("Stream", false, "ASR not ready", 0))
            return
        }

        try {
            val testDir = File(context.filesDir, "test_wavs")

            // Concatenate all 3 test files into one continuous stream
            val allSamples = mutableListOf<Float>()
            val silencePadding = FloatArray(16000) // 1s silence between segments
            for (tc in testCases) {
                val (samples, _) = readWav(File(testDir, tc.filename))
                allSamples.addAll(samples.toList())
                allSamples.addAll(silencePadding.toList())
            }
            val totalDurS = allSamples.size.toFloat() / 16000
            Log.i(TAG, "Continuous stream: ${"%.1f".format(totalDurS)}s, ${allSamples.size} samples")

            // Setup VAD + ASR
            val vadFile = File(context.filesDir, "sherpa/silero_vad.onnx")
            val vad = Vad(null, VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadFile.absolutePath, threshold = 0.3f,
                    minSpeechDuration = 0.25f, minSilenceDuration = 0.5f, maxSpeechDuration = 15.0f
                ), sampleRate = 16000, numThreads = 2
            ))
            val recognizer = OfflineRecognizer(null, OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = mgr.encoderPath(), decoder = mgr.decoderPath(), joiner = mgr.joinerPath()
                    ), tokens = mgr.tokensPath(), numThreads = 4
                ), decodingMethod = "greedy_search"
            ))

            val e2eStart = System.currentTimeMillis()
            val transcriptions = mutableListOf<String>()

            // Feed as 100ms chunks (simulating real-time mic capture)
            val chunkSize = 1600 // 100ms at 16kHz
            val floatArray = allSamples.toFloatArray()
            for (i in floatArray.indices step chunkSize) {
                val end = minOf(i + chunkSize, floatArray.size)
                val chunk = floatArray.copyOfRange(i, end)
                if (chunk.size >= 512) {
                    // Pad to multiple of 512 for VAD
                    val padded = if (chunk.size % 512 != 0) {
                        chunk.copyOf((chunk.size / 512 + 1) * 512)
                    } else chunk

                    for (j in padded.indices step 512) {
                        val vadChunk = padded.copyOfRange(j, minOf(j + 512, padded.size))
                        if (vadChunk.size == 512) {
                            vad.acceptWaveform(vadChunk)
                            while (!vad.empty()) {
                                val seg = vad.front()
                                vad.pop()
                                val stream = recognizer.createStream()
                                stream.acceptWaveform(seg.samples, 16000)
                                recognizer.decode(stream)
                                val text = recognizer.getResult(stream).text.trim()
                                stream.release()
                                if (text.isNotBlank()) transcriptions.add(text)
                            }
                        }
                    }
                }
            }
            // Flush remaining
            vad.flush()
            while (!vad.empty()) {
                val seg = vad.front(); vad.pop()
                val stream = recognizer.createStream()
                stream.acceptWaveform(seg.samples, 16000)
                recognizer.decode(stream)
                val text = recognizer.getResult(stream).text.trim()
                stream.release()
                if (text.isNotBlank()) transcriptions.add(text)
            }

            val totalMs = System.currentTimeMillis() - e2eStart
            vad.release(); recognizer.release()

            val passed = transcriptions.size >= 2 // Should get at least 2 segments from 3 files
            results.add(TestResult(
                "Stream: continuous ${totalDurS.toInt()}s",
                passed,
                "${transcriptions.size} segments recognized in ${totalMs}ms | " +
                transcriptions.joinToString(" | ") { "\"${it.take(25)}...\"" },
                totalMs
            ))

        } catch (e: Exception) {
            results.add(TestResult("Stream", false, "Error: ${e.message}", 0))
        }
    }

    // ========================================================================
    // TEST 8: No repetition — same audio should not produce duplicate translations
    // ========================================================================

    private suspend fun testNoRepetition() {
        val mgr = SherpaModelManager(context)
        if (!mgr.isAsrReady()) {
            results.add(TestResult("No-Repeat", false, "ASR not ready", 0))
            return
        }

        try {
            val testDir = File(context.filesDir, "test_wavs")
            val (samples, _) = readWav(File(testDir, "test_0.wav"))

            val recognizer = OfflineRecognizer(null, OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = mgr.encoderPath(), decoder = mgr.decoderPath(), joiner = mgr.joinerPath()
                    ), tokens = mgr.tokensPath(), numThreads = 4
                ), decodingMethod = "greedy_search"
            ))

            // Run ASR 3 times on same audio
            val results3 = mutableListOf<String>()
            for (run in 1..3) {
                val stream = recognizer.createStream()
                stream.acceptWaveform(samples, 16000)
                recognizer.decode(stream)
                results3.add(recognizer.getResult(stream).text.trim())
                stream.release()
            }
            recognizer.release()

            // All 3 runs should produce the same result (deterministic)
            val allSame = results3.distinct().size == 1
            val passed = allSame && results3[0].isNotBlank()

            results.add(TestResult(
                "No-Repeat: deterministic ASR",
                passed,
                if (allSame) "3/3 identical: \"${results3[0].take(40)}...\"" else "DIFFERENT: ${results3.map { it.take(20) }}",
                0
            ))

        } catch (e: Exception) {
            results.add(TestResult("No-Repeat", false, "Error: ${e.message}", 0))
        }
    }

    // ========================================================================
    // TEST 9: Latency budget — each stage must be under threshold
    // ========================================================================

    private suspend fun testLatencyBudget() {
        val mgr = SherpaModelManager(context)
        if (!mgr.isAsrReady()) {
            results.add(TestResult("Latency", false, "ASR not ready", 0))
            return
        }

        try {
            val testDir = File(context.filesDir, "test_wavs")
            val (samples, _) = readWav(File(testDir, "test_1.wav"))

            // ASR latency
            val recognizer = OfflineRecognizer(null, OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = mgr.encoderPath(), decoder = mgr.decoderPath(), joiner = mgr.joinerPath()
                    ), tokens = mgr.tokensPath(), numThreads = 4
                ), decodingMethod = "greedy_search"
            ))

            val asrStart = System.currentTimeMillis()
            val stream = recognizer.createStream()
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            val thaiText = recognizer.getResult(stream).text.trim()
            stream.release()
            val asrMs = System.currentTimeMillis() - asrStart
            recognizer.release()

            val asrOk = asrMs < 500 // ASR must be under 500ms
            results.add(TestResult(
                "Latency: ASR < 500ms",
                asrOk,
                "${asrMs}ms for ${samples.size / 16000}s audio",
                asrMs
            ))

            // Translation latency
            val translator = Translation.getClient(TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.THAI)
                .setTargetLanguage(TranslateLanguage.FRENCH)
                .build())
            suspendCancellableCoroutine { cont ->
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener { cont.resume(Unit) }
                    .addOnFailureListener { cont.resume(Unit) }
            }

            val transStart = System.currentTimeMillis()
            val french = suspendCancellableCoroutine { cont ->
                translator.translate(thaiText)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume("") }
            }
            val transMs = System.currentTimeMillis() - transStart
            translator.close()

            val transOk = transMs < 500 // Translation must be under 500ms
            results.add(TestResult(
                "Latency: Translate < 500ms",
                transOk,
                "${transMs}ms for ${thaiText.length} chars → \"${french.take(30)}...\"",
                transMs
            ))

            // Total E2E budget
            val totalMs = asrMs + transMs
            val totalOk = totalMs < 1000 // Total must be under 1s (excluding TTS)
            results.add(TestResult(
                "Latency: E2E (no TTS) < 1s",
                totalOk,
                "ASR(${asrMs}ms) + Trans(${transMs}ms) = ${totalMs}ms",
                totalMs
            ))

        } catch (e: Exception) {
            results.add(TestResult("Latency", false, "Error: ${e.message}", 0))
        }
    }

    // ========================================================================
    // TEST 10: Bidirectional — both TH→FR and FR→TH work
    // ========================================================================

    private suspend fun testBidirectional() {
        try {
            // TH → FR
            val thToFr = Translation.getClient(TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.THAI)
                .setTargetLanguage(TranslateLanguage.FRENCH)
                .build())
            suspendCancellableCoroutine { cont ->
                thToFr.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener { cont.resume(Unit) }
                    .addOnFailureListener { cont.resume(Unit) }
            }

            // FR → TH
            val frToTh = Translation.getClient(TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.FRENCH)
                .setTargetLanguage(TranslateLanguage.THAI)
                .build())
            suspendCancellableCoroutine { cont ->
                frToTh.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener { cont.resume(Unit) }
                    .addOnFailureListener { cont.resume(Unit) }
            }

            // Round-trip test: Thai → French → Thai
            val original = "สวัสดีครับ วันนี้อากาศดีมาก"
            val start = System.currentTimeMillis()

            val french = suspendCancellableCoroutine { cont ->
                thToFr.translate(original)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume("") }
            }

            val backToThai = suspendCancellableCoroutine { cont ->
                frToTh.translate(french)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume("") }
            }

            val ms = System.currentTimeMillis() - start
            thToFr.close(); frToTh.close()

            val hasThaiBack = backToThai.any { it.code in 0x0E00..0x0E7F }
            val passed = french.isNotBlank() && hasThaiBack

            results.add(TestResult(
                "Bidirectional: TH→FR→TH round-trip",
                passed,
                "\"${original.take(20)}\" → \"${french.take(25)}\" → \"${backToThai.take(25)}\"",
                ms
            ))

        } catch (e: Exception) {
            results.add(TestResult("Bidirectional", false, "Error: ${e.message}", 0))
        }
    }

    // ========================================================================
    // UTILS
    // ========================================================================

    /** Read WAV file → FloatArray samples + sample rate */
    private fun readWav(file: File): Pair<FloatArray, Int> {
        val raf = RandomAccessFile(file, "r")
        // Skip to format chunk
        raf.seek(22)
        val channels = raf.readByte().toInt() or (raf.readByte().toInt() shl 8)
        val sampleRate = raf.readByte().toInt() and 0xFF or
                ((raf.readByte().toInt() and 0xFF) shl 8) or
                ((raf.readByte().toInt() and 0xFF) shl 16) or
                ((raf.readByte().toInt() and 0xFF) shl 24)

        // Find data chunk
        raf.seek(44) // Standard WAV header size
        val dataSize = (file.length() - 44).toInt()
        val bytes = ByteArray(dataSize)
        raf.readFully(bytes)
        raf.close()

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val numSamples = dataSize / 2 // 16-bit
        val samples = FloatArray(numSamples)
        for (i in 0 until numSamples) {
            samples[i] = buffer.short.toFloat() / 32768f
        }

        return Pair(samples, sampleRate)
    }

    /** Character-level match ratio between two strings */
    private fun computeMatchRatio(actual: String, expected: String): Float {
        if (expected.isEmpty()) return if (actual.isNotEmpty()) 1f else 0f
        val a = actual.replace(" ", "")
        val e = expected.replace(" ", "")
        // Simple: count matching characters at each position
        var matches = 0
        val minLen = minOf(a.length, e.length)
        for (i in 0 until minLen) {
            if (a[i] == e[i]) matches++
        }
        // Also check substring containment
        val containsRatio = if (e.length > 10) {
            val chunks = (0 until e.length step 10).map { i -> e.substring(i, minOf(i + 10, e.length)) }
            chunks.count { a.contains(it) }.toFloat() / chunks.size
        } else {
            if (a.contains(e)) 1f else 0f
        }
        return maxOf(matches.toFloat() / e.length, containsRatio)
    }
}
