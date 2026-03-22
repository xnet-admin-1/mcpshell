#!/bin/bash
# Build MCPShell via Docker container
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORKSPACE_COPY="$HOME/dev-container/workspace/mcpshell"

# Sync source to Docker workspace
rsync -a --delete --exclude='.git' --exclude='build' --exclude='.gradle' \
    "$PROJECT_DIR/" "$WORKSPACE_COPY/"

# Build
docker exec xnet-dev bash -c 'cd /workspace/mcpshell && ./gradlew :app:assembleDebug --parallel --build-cache 2>&1'

# Copy APK back
cp "$WORKSPACE_COPY/app/build/outputs/apk/debug/app-debug.apk" "$PROJECT_DIR/app-debug.apk"

echo ""
echo "APK: $PROJECT_DIR/app-debug.apk"

if [ "$1" = "--install" ]; then
    adb install -r "$PROJECT_DIR/app-debug.apk"
fi
