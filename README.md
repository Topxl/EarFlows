<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0+-3DDC84?style=flat-square&logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/Kotlin-2.1-7F52FF?style=flat-square&logo=kotlin&logoColor=white" />
  <img src="https://img.shields.io/badge/Jetpack_Compose-Material3-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white" />
  <img src="https://img.shields.io/badge/License-MIT-green?style=flat-square" />
  <img src="https://img.shields.io/github/v/release/Topxl/EarFlows?style=flat-square&color=orange" />
</p>

<h1 align="center">EarFlows</h1>

<p align="center">
  <strong>Real-time ambient translation, directly in your earbuds.</strong><br>
  Someone speaks Thai? You hear French. Instantly. 100% offline.
</p>

<p align="center">
  <a href="https://earflows.com">Website</a> &bull;
  <a href="https://github.com/Topxl/EarFlows/releases/latest">Download APK</a> &bull;
  <a href="#contributing">Contribute</a> &bull;
  <a href="#architecture">Architecture</a>
</p>

---

## What is EarFlows?

EarFlows is an open-source Android app that listens to conversations around you and translates them in real-time into your Bluetooth earbuds. It also lets you reply — speak in your language and the translation plays on the phone speaker for the other person.

**Two modes:**

| Mode | Input | Output | Use case |
|------|-------|--------|----------|
| **Ambient** | Phone mic captures Thai speech | French translation in your BT earbuds | Understand what people say around you |
| **Reply** | BT earbuds mic captures your French | Thai translation on phone speaker | Speak back to them in their language |

## Key Features

- **~300ms offline latency** — Sherpa-ONNX ASR + ML Kit Translate + TTS, all on-device
- **~2s cloud latency** — OpenRouter Gemini SSE streaming (optional boost)
- **14+ languages** — Thai, French, English, Chinese, Japanese, Korean, Spanish, German, and more
- **Conversation mode** — Bidirectional translation with automatic audio routing
- **Independent mic selector** — Choose phone mic or BT mic regardless of mode
- **Echo cancellation** — Mic muted during TTS to prevent feedback loops
- **Parallel recording** — All sessions saved as WAV in `/Download/EarFlowsRecords/`
- **Debug pipeline** — Real-time visualization of every stage (VAD, ASR, translate, TTS)
- **27+ automated tests** — ASR accuracy, translation quality, latency budget, E2E pipeline

## Quick Start

### Download

Grab the latest APK from [Releases](https://github.com/Topxl/EarFlows/releases/latest) and install on your Android device.

### Build from source

```bash
git clone https://github.com/Topxl/EarFlows.git
cd EarFlows
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### First launch

1. Grant microphone + Bluetooth + notification permissions
2. Models download automatically on first start (~250MB for offline Thai+French)
3. Connect Bluetooth earbuds
4. Press **Start** and speak!

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                   FOREGROUND SERVICE                      │
│                                                          │
│  AudioRecord ──→ Sherpa VAD ──→ ASR ──→ Translate ──→ TTS│
│  (CAMCORDER)     (Silero)       │         (ML Kit)    │  │
│                                 │                     │  │
│                    ┌────────────┴──────────┐          │  │
│                    │ Thai: Zipformer (155MB)│     ┌────┘  │
│                    │ French: Whisper (98MB) │     │       │
│                    └───────────────────────-┘     ▼       │
│                                              AudioTrack  │
│                                              or TTS.speak│
│                                                          │
│  Cloud fallback: Gemini Flash SSE (OpenRouter API)       │
└──────────────────────────────────────────────────────────┘
```

### Pipeline (offline)

| Stage | Technology | Latency | Size |
|-------|-----------|---------|------|
| Voice detection | Sherpa Silero VAD | ~5ms | 2MB |
| Thai ASR | Sherpa Zipformer transducer | ~150ms | 155MB |
| French ASR | Sherpa Whisper tiny multilingual | ~300ms | 98MB |
| Translation | Google ML Kit (on-device) | ~50ms | 30MB |
| Speech synthesis | Android TTS | ~100ms | System |
| **Total E2E** | | **~300-500ms** | **~285MB** |

### Pipeline (cloud)

| Stage | Technology | Latency |
|-------|-----------|---------|
| Audio capture | AudioRecord CAMCORDER 16kHz | Realtime |
| Adaptive VAD | RMS energy + noise floor | ~30ms |
| Transcribe + Translate | Gemini Flash via OpenRouter SSE | ~500ms first token |
| Speech synthesis | Android TTS (progressive, per-sentence) | ~100ms |
| **Total E2E** | | **~1-2s** |

### Project Structure

```
com.earflows.app/
├── service/
│   └── EarFlowsForegroundService.kt    # Main orchestrator
├── translation/
│   ├── TranslationEngine.kt            # Common interface
│   ├── SherpaS2SEngine.kt              # Offline: VAD → ASR → Translate → TTS
│   ├── RealtimeTranslationEngine.kt    # Cloud: Gemini SSE streaming
│   ├── DirectS2SEngine.kt              # Future: StreamSpeech/Hibiki S2S
│   └── TranslationManager.kt           # Engine selection + fallback
├── audio/
│   ├── ConversationModeManager.kt       # Ambient/Reply audio routing
│   ├── AudioPlaybackManager.kt          # AudioTrack + split-channel
│   ├── AudioRecordingManager.kt         # WAV recording in segments
│   └── BluetoothAudioManager.kt         # BT device detection
├── model/
│   ├── SherpaModelManager.kt            # Model download + paths
│   └── SeamlessModelLoader.kt           # Future: SeamlessStreaming ONNX
├── vad/
│   └── SileroVadDetector.kt             # Silero VAD v5 ONNX
├── test/
│   ├── PipelineTestRunner.kt            # 27+ pipeline tests
│   └── ConversationModeTest.kt          # Audio routing tests
├── ui/screens/
│   ├── HomeScreen.kt                    # Main UI + mic selector + reply button
│   ├── DebugScreen.kt                   # Real-time pipeline visualization
│   ├── SettingsScreen.kt                # Languages, API key, models
│   └── TestScreen.kt                    # Test runner UI
├── viewmodel/
│   ├── MainViewModel.kt                 # Service binding + state
│   └── SettingsViewModel.kt             # Preferences
└── data/
    └── PreferencesManager.kt            # DataStore + EncryptedSharedPrefs
```

## Contributing

EarFlows is a community project. We welcome contributions of all kinds!

### Priority Areas

| Priority | Area | Description |
|----------|------|-------------|
| **P0** | Whisper ONNX mel preprocessing | Enable Whisper ONNX for offline ASR without sherpa dependency |
| **P0** | Fix Silero VAD v5 state tensor | VAD returns 0 for all audio — needs state format investigation |
| **P1** | SeamlessStreaming ONNX export | True speech-to-speech without text intermediate |
| **P1** | Piper TTS integration | Offline French/Thai TTS via sherpa-onnx (currently Android TTS) |
| **P2** | Streaming ASR model for Thai | Replace offline zipformer with online/streaming for lower latency |
| **P2** | Recording playback UI | Browse and replay recorded sessions |
| **P2** | Split-channel stereo | Original audio left ear + translation right ear |
| **P3** | More languages | Add ASR models for Vietnamese, Korean, Japanese, Chinese |
| **P3** | Speaker diarization | Distinguish between multiple speakers |

### How to Contribute

1. **Fork** the repository
2. **Create a branch** for your feature: `git checkout -b feat/my-feature`
3. **Run tests** before submitting: Debug screen → "Lancer la batterie de tests" (all 27 should pass)
4. **Submit a PR** with a clear description

### Development Setup

**Requirements:**
- Android Studio Ladybug (2024.3+) or newer
- JDK 17
- Android SDK 35
- A physical Android device (emulator won't work for audio/BT features)

**Build:**
```bash
git clone https://github.com/Topxl/EarFlows.git
cd EarFlows
./gradlew assembleDebug
```

**Run tests:**
- Install on device: `adb install app/build/outputs/apk/debug/app-debug.apk`
- Open app → Debug (bug icon) → "Lancer la batterie de tests"
- Or for conversation mode tests: "Tests Mode Conversation (BT)"

**Key files to know:**
- `SherpaS2SEngine.kt` — Main offline translation engine (start here)
- `RealtimeTranslationEngine.kt` — Cloud engine with SSE streaming
- `ConversationModeManager.kt` — Audio routing for ambient/reply modes
- `PipelineTestRunner.kt` — All pipeline tests
- `Constants.kt` — All configurable parameters (thresholds, timeouts, etc.)

### Models

Models are downloaded automatically on first launch. To manually manage:

| Model | Size | Source | Purpose |
|-------|------|--------|---------|
| Thai ASR (zipformer) | 155MB | [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases) | Thai speech recognition |
| Whisper tiny | 98MB | [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases) | French/multilingual ASR |
| Silero VAD | 2MB | [silero-vad](https://github.com/snakers4/silero-vad) | Voice activity detection |
| ML Kit Translate | ~30MB/pair | Google Play Services | On-device text translation |

## Tech Stack

- **Language**: Kotlin 2.1
- **UI**: Jetpack Compose + Material 3
- **Architecture**: MVVM + Foreground Service
- **ASR**: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Zipformer + Whisper)
- **VAD**: Silero VAD v5 (ONNX)
- **Translation**: Google ML Kit (offline) / OpenRouter Gemini (cloud)
- **TTS**: Android TTS (Google)
- **Audio**: AudioRecord + AudioTrack with Bluetooth routing
- **Cloud**: OkHttp + SSE streaming
- **Storage**: DataStore + EncryptedSharedPreferences
- **Build**: Gradle 8.13 + AGP 8.13.2

## License

MIT License — see [LICENSE](LICENSE) for details.

## Acknowledgements

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) by k2-fsa — On-device ASR/TTS framework
- [Silero VAD](https://github.com/snakers4/silero-vad) — Voice activity detection
- [Google ML Kit](https://developers.google.com/ml-kit/language/translation) — On-device translation
- [OpenRouter](https://openrouter.ai) — LLM API gateway

---

<p align="center">
  <strong>Built with love for travelers, expats, and language learners.</strong><br>
  <a href="https://earflows.com">earflows.com</a>
</p>
