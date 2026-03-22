#!/bin/bash
#
# EarFlows - Setup script
# Sets up Android SDK, Gradle, and downloads required model files.
#
# Usage: chmod +x setup.sh && ./setup.sh
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "============================================"
echo "  EarFlows Setup"
echo "============================================"
echo ""

# -----------------------------------------------
# 1. Check Java (JDK 17+)
# -----------------------------------------------
echo "[1/5] Checking Java..."
if command -v java &>/dev/null; then
    JAVA_VER=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    echo "  Java version: $JAVA_VER"
    if [ "$JAVA_VER" -lt 17 ] 2>/dev/null; then
        echo "  WARNING: JDK 17+ required. Install with:"
        echo "    sudo apt install openjdk-17-jdk"
    fi
else
    echo "  Java not found. Install with:"
    echo "    sudo apt install openjdk-17-jdk"
    echo ""
    echo "  Then re-run this script."
    exit 1
fi

# -----------------------------------------------
# 2. Check/Install Android SDK (command line tools)
# -----------------------------------------------
echo ""
echo "[2/5] Checking Android SDK..."

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

if [ ! -d "$ANDROID_HOME" ]; then
    echo "  Android SDK not found at $ANDROID_HOME"
    echo ""
    echo "  Option A (recommended): Install Android Studio from https://developer.android.com/studio"
    echo "  Option B (CLI only):"
    echo "    mkdir -p $ANDROID_HOME/cmdline-tools"
    echo "    cd $ANDROID_HOME/cmdline-tools"
    echo "    wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    echo "    unzip commandlinetools-linux-*.zip"
    echo "    mv cmdline-tools latest"
    echo "    export ANDROID_HOME=$ANDROID_HOME"
    echo "    export PATH=\$PATH:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools"
    echo "    sdkmanager --install 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'"
    echo ""

    read -p "  Try to install Android SDK CLI tools automatically? [y/N] " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        mkdir -p "$ANDROID_HOME/cmdline-tools"
        cd "$ANDROID_HOME/cmdline-tools"

        if ! command -v wget &>/dev/null; then
            sudo apt-get install -y wget unzip
        fi

        wget -q --show-progress "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -O cmdline-tools.zip
        unzip -q cmdline-tools.zip
        mv cmdline-tools latest 2>/dev/null || true
        rm cmdline-tools.zip

        export ANDROID_HOME="$ANDROID_HOME"
        export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

        echo "  Installing SDK components..."
        yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
        "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
            "platform-tools" \
            "platforms;android-35" \
            "build-tools;35.0.0" \
            "ndk;27.2.12479018"

        cd "$SCRIPT_DIR"
        echo "  Android SDK installed at $ANDROID_HOME"
    else
        echo "  Skipping SDK install. Set ANDROID_HOME and re-run."
    fi
else
    echo "  Found Android SDK at $ANDROID_HOME"
fi

# Write local.properties for Gradle
echo "sdk.dir=$ANDROID_HOME" > local.properties
echo "  local.properties written"

# -----------------------------------------------
# 3. Download Gradle wrapper JAR
# -----------------------------------------------
echo ""
echo "[3/5] Setting up Gradle wrapper..."

WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
    GRADLE_VERSION="8.9"
    echo "  Downloading gradle-wrapper.jar (v$GRADLE_VERSION)..."
    mkdir -p gradle/wrapper

    # Download from Gradle's GitHub releases
    WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v${GRADLE_VERSION}/gradle/wrapper/gradle-wrapper.jar"
    wget -q --show-progress "$WRAPPER_URL" -O "$WRAPPER_JAR" 2>/dev/null || \
    curl -sL "$WRAPPER_URL" -o "$WRAPPER_JAR" 2>/dev/null || {
        echo "  Could not download wrapper JAR. Trying alternative..."
        # Alternative: let Gradle generate it
        if command -v gradle &>/dev/null; then
            gradle wrapper --gradle-version $GRADLE_VERSION
        else
            echo "  Please install gradle or download the wrapper manually."
            echo "  Run: gradle wrapper --gradle-version $GRADLE_VERSION"
        fi
    }
fi

if [ -f "$WRAPPER_JAR" ]; then
    echo "  Gradle wrapper ready"
else
    echo "  WARNING: gradle-wrapper.jar missing. Build may fail."
fi

# -----------------------------------------------
# 4. Download ML model files
# -----------------------------------------------
echo ""
echo "[4/5] Downloading ML models..."

ASSETS_DIR="app/src/main/assets"
MODELS_DIR="$ASSETS_DIR/models"
mkdir -p "$ASSETS_DIR" "$MODELS_DIR"

# Silero VAD v5 (~2MB)
VAD_MODEL="$ASSETS_DIR/silero_vad.onnx"
if [ ! -f "$VAD_MODEL" ]; then
    echo "  Downloading Silero VAD v5..."
    wget -q --show-progress \
        "https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx" \
        -O "$VAD_MODEL" 2>/dev/null || \
    curl -sL \
        "https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx" \
        -o "$VAD_MODEL"
    echo "  Silero VAD downloaded: $(du -h "$VAD_MODEL" | cut -f1)"
else
    echo "  Silero VAD already present"
fi

# SeamlessStreaming model - needs to be exported (see tools/export_model.py)
if [ ! -f "$MODELS_DIR/seamless_streaming_unity_q8.onnx" ]; then
    echo ""
    echo "  SeamlessStreaming ONNX model not found."
    echo "  To generate it, run:"
    echo "    cd tools && pip install -r requirements.txt && python export_model.py"
    echo "  Then copy the output to: $MODELS_DIR/"
    echo ""
    echo "  (The app will still work in Cloud mode without this model)"
fi

# -----------------------------------------------
# 5. Test build
# -----------------------------------------------
echo ""
echo "[5/5] Testing Gradle build..."
echo ""

if [ -f "$WRAPPER_JAR" ] && [ -d "$ANDROID_HOME" ]; then
    echo "  Running: ./gradlew assembleDebug"
    ./gradlew assembleDebug --no-daemon 2>&1 | tail -5
    echo ""
    if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
        APK_SIZE=$(du -h "app/build/outputs/apk/debug/app-debug.apk" | cut -f1)
        echo "  BUILD SUCCESS! APK: app/build/outputs/apk/debug/app-debug.apk ($APK_SIZE)"
        echo ""
        echo "  Install on device:"
        echo "    adb install app/build/outputs/apk/debug/app-debug.apk"
    fi
else
    echo "  Skipping build (missing SDK or Gradle wrapper)"
    echo "  Once ready, run: ./gradlew assembleDebug"
fi

echo ""
echo "============================================"
echo "  Setup complete!"
echo "============================================"
echo ""
echo "  Next steps:"
echo "    1. Open project in Android Studio: File > Open > $(pwd)"
echo "    2. Or build from CLI: ./gradlew assembleDebug"
echo "    3. Install: adb install app/build/outputs/apk/debug/app-debug.apk"
echo ""
