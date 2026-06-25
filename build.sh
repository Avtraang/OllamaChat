#!/usr/bin/env bash
#
# Builds a signed, installable APK for the Ollama Chat app WITHOUT the full
# Android SDK / Gradle — using only the standalone build tools:
#   aapt (resource+manifest compiler & packager), javac, dx (dexer),
#   zipalign, apksigner.
#
# Required on PATH: aapt, dx, zipalign, apksigner, javac, keytool.
# Required file:    .buildtools/android.jar  (compile-time framework stubs)
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

ANDROID_JAR="$ROOT/.buildtools/android.jar"
OUT="$ROOT/build"
GEN="$OUT/gen"
CLASSES="$OUT/classes"
KEYSTORE="$ROOT/.buildtools/debug.keystore"

APK_UNSIGNED="$OUT/app-unsigned.apk"
APK_ALIGNED="$OUT/app-aligned.apk"
APK_FINAL="$ROOT/OllamaChat.apk"

if [ ! -f "$ANDROID_JAR" ]; then
    echo ">> Fetching android.jar (API 34 compile stubs)"
    mkdir -p "$(dirname "$ANDROID_JAR")"
    curl -fsSL -o "$ANDROID_JAR" \
        "https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar"
fi

echo ">> Cleaning build dir"
rm -rf "$OUT"
mkdir -p "$GEN" "$CLASSES"

echo ">> [1/6] aapt: compile resources + manifest, generate R.java, create base APK"
aapt package -f -m \
    -M AndroidManifest.xml \
    -S res \
    -I "$ANDROID_JAR" \
    -J "$GEN" \
    -F "$APK_UNSIGNED"

echo ">> [2/6] javac: compile sources (Java 8 bytecode for dx)"
SRCS=$(find src "$GEN" -name '*.java')
javac \
    --release 8 \
    -classpath "$ANDROID_JAR" \
    -d "$CLASSES" \
    $SRCS

echo ">> [3/6] dx (dalvik-exchange): convert .class -> classes.dex"
dalvik-exchange --dex --output="$OUT/classes.dex" "$CLASSES"

echo ">> [4/6] aapt: add classes.dex into the APK"
( cd "$OUT" && aapt add -f "$APK_UNSIGNED" classes.dex >/dev/null )

echo ">> [5/6] zipalign: 4-byte align"
zipalign -f 4 "$APK_UNSIGNED" "$APK_ALIGNED"

echo ">> [6/6] apksigner: sign (debug key)"
if [ ! -f "$KEYSTORE" ]; then
    echo "   generating debug keystore"
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -storepass android -keypass android \
        -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
apksigner sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --min-sdk-version 24 \
    --out "$APK_FINAL" \
    "$APK_ALIGNED"

echo ">> Verifying signature"
apksigner verify --min-sdk-version 24 -v "$APK_FINAL" | sed 's/^/   /'

echo ""
echo ">> DONE: $APK_FINAL"
ls -lh "$APK_FINAL"
