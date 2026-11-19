#!/usr/bin/env bash
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

require() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "missing dependency: $1"
        exit 1
    }
}

echo "[larp] verificando dependências..."
require java
require unzip

if [ ! -f "$PROJECT_DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
    echo "[larp] baixando gradle-wrapper.jar..."
    mkdir -p "$PROJECT_DIR/gradle/wrapper"
    if command -v gradle >/dev/null 2>&1; then
        (cd "$PROJECT_DIR" && gradle wrapper --gradle-version 8.10.2 --distribution-type bin)
    else
        curl -sSL -o /tmp/gradle-8.10.2-bin.zip \
            "https://services.gradle.org/distributions/gradle-8.10.2-bin.zip"
        unzip -q -o /tmp/gradle-8.10.2-bin.zip -d /tmp
        /tmp/gradle-8.10.2/bin/gradle wrapper --gradle-version 8.10.2 --distribution-type bin
        rm -rf /tmp/gradle-8.10.2 /tmp/gradle-8.10.2-bin.zip
    fi
fi

if [ ! -f "$PROJECT_DIR/local.properties" ]; then
    if [ -n "$ANDROID_HOME" ]; then
        echo "sdk.dir=$ANDROID_HOME" > "$PROJECT_DIR/local.properties"
        echo "[larp] local.properties criado com ANDROID_HOME=$ANDROID_HOME"
    elif [ -n "$ANDROID_SDK_ROOT" ]; then
        echo "sdk.dir=$ANDROID_SDK_ROOT" > "$PROJECT_DIR/local.properties"
        echo "[larp] local.properties criado com ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
    else
        echo "[larp] ANDROID_HOME não definido. Configure o Android SDK e crie local.properties com sdk.dir=/caminho/do/sdk"
    fi
fi

echo "[larp] pronto. Execute: ./gradlew :app:assembleStableDebug"
