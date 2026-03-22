package com.earflows.app.util

object Constants {
    // Audio capture
    const val SAMPLE_RATE = 16_000           // 16kHz mono for speech models
    const val AUDIO_CHANNEL_CONFIG = 16       // AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_ENCODING = 2              // AudioFormat.ENCODING_PCM_16BIT
    const val CHUNK_DURATION_MS = 640         // ~640ms chunks for streaming (balance latency/efficiency)
    const val CHUNK_SIZE_BYTES = SAMPLE_RATE * 2 * CHUNK_DURATION_MS / 1000  // 16-bit = 2 bytes/sample

    // VAD
    const val VAD_THRESHOLD = 0.5f            // Silero VAD probability threshold
    const val VAD_MIN_SPEECH_MS = 250         // Min speech duration to trigger translation
    const val VAD_MIN_SILENCE_MS = 800        // Min silence to consider end of utterance

    // Recording
    const val RECORDING_SEGMENT_DURATION_MS = 5 * 60 * 1000L  // 5 minutes per segment
    const val RECORDING_DIR = "EarFlowsRecords"
    const val RECORDING_FORMAT_EXTENSION = ".ogg"

    // Translation
    const val DEFAULT_SOURCE_LANG = "tha"     // Thai (ISO 639-3 for Seamless)
    const val DEFAULT_TARGET_LANG = "fra"     // French
    const val DEFAULT_SOURCE_DISPLAY = "Thaï"
    const val DEFAULT_TARGET_DISPLAY = "Français"

    // Cloud
    const val OPENAI_REALTIME_WS_URL = "wss://api.openai.com/v1/realtime"
    const val CLOUD_TIMEOUT_MS = 5_000L
    const val CLOUD_RECONNECT_DELAY_MS = 3_000L

    // Notification
    const val NOTIFICATION_CHANNEL_ID = "earflows_service_channel"
    const val NOTIFICATION_ID = 1001
    const val SERVICE_STOP_ACTION = "com.earflows.app.STOP_SERVICE"
    const val SERVICE_PAUSE_ACTION = "com.earflows.app.PAUSE_SERVICE"
    const val SERVICE_RESUME_ACTION = "com.earflows.app.RESUME_SERVICE"

    // Model
    const val SEAMLESS_MODEL_FILENAME = "seamless_streaming_unity_q8.onnx"
    const val SEAMLESS_TOKENIZER_FILENAME = "seamless_tokenizer.json"
    const val MODEL_DIR = "models"
}
