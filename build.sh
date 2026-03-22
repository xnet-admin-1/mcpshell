#!/bin/bash
# Build MCPShell via Docker container
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONTAINER="xnet-dev"
DOCKER_PATH="/workspace/mcpshell"

# Sync source to Docker workspace (clean first to avoid permission issues)
docker exec $CONTAINER rm -rf $DOCKER_PATH/app/src $DOCKER_PATH/build.gradle.kts $DOCKER_PATH/app/build.gradle.kts $DOCKER_PATH/settings.gradle.kts $DOCKER_PATH/gradle.properties 2>/dev/null || true
docker cp "$PROJECT_DIR/app" $CONTAINER:$DOCKER_PATH/app
docker cp "$PROJECT_DIR/build.gradle.kts" $CONTAINER:$DOCKER_PATH/build.gradle.kts
docker cp "$PROJECT_DIR/app/build.gradle.kts" $CONTAINER:$DOCKER_PATH/app/build.gradle.kts
docker cp "$PROJECT_DIR/settings.gradle.kts" $CONTAINER:$DOCKER_PATH/settings.gradle.kts
docker cp "$PROJECT_DIR/gradle.properties" $CONTAINER:$DOCKER_PATH/gradle.properties
docker cp "$PROJECT_DIR/gradle" $CONTAINER:$DOCKER_PATH/gradle
docker cp "$PROJECT_DIR/gradlew" $CONTAINER:$DOCKER_PATH/gradlew

# Build
docker exec $CONTAINER bash -c "cd $DOCKER_PATH && chmod +x gradlew && ./gradlew :app:assembleDebug --parallel --build-cache 2>&1"

# Copy APK back
docker cp $CONTAINER:$DOCKER_PATH/app/build/outputs/apk/debug/app-debug.apk "$PROJECT_DIR/app-debug.apk"

echo ""
echo "APK: $PROJECT_DIR/app-debug.apk"

if [ "$1" = "--install" ]; then
    adb install -r "$PROJECT_DIR/app-debug.apk"
fi
