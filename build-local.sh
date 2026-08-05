#!/bin/bash
# Local build script for MCPShell
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "Building MCPShell locally..."
echo "Project directory: $PROJECT_DIR"

# Set Android SDK path
export ANDROID_HOME=/usr/lib/android-sdk
export PATH=$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools:$PATH

# Make gradlew executable
chmod +x "$PROJECT_DIR/gradlew"

# Build debug APK
echo "Building debug APK..."
cd "$PROJECT_DIR"
./gradlew :app:assembleDebug --parallel --build-cache

# Copy APK to project root for easy access
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
    cp "$APK_PATH" "$PROJECT_DIR/app-debug.apk"
    echo ""
    echo "APK successfully built: $PROJECT_DIR/app-debug.apk"
    
    # Install if --install flag is passed
    if [ "$1" = "--install" ]; then
        echo "Installing APK via adb..."
        adb install -r "$PROJECT_DIR/app-debug.apk"
    fi
else
    echo "Error: APK not found at $APK_PATH"
    exit 1
fi