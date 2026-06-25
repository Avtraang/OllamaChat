#!/usr/bin/env bash
#
# Builds a signed, installable APK for the Ollama Chat app WITHOUT the full
# Android SDK / Gradle — using only the standalone build tools:
#   aapt (resource+manifest compiler & packager), javac, dalvik-exchange (dx),
#   zipalign, apksigner.
#
# Third-party libraries (OkHttp + okhttp-sse + okio for SSE streaming, Markwon
# + commonmark for Markdown) are plain Maven Central artifacts, fetched on
# first build. Markwon ships as an AAR; we use its classes.jar and drop the
# few optional AndroidX-dependent classes we never call.
#
# Required on PATH: aapt, dalvik-exchange, zipalign, apksigner, javac, keytool.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

BT="$ROOT/.buildtools"
LIBS="$BT/libs"
ANDROID_JAR="$BT/android.jar"
OUT="$ROOT/build"
GEN="$OUT/gen"
CLASSES="$OUT/classes"
KEYSTORE="$BT/debug.keystore"

APK_UNSIGNED="$OUT/app-unsigned.apk"
APK_ALIGNED="$OUT/app-aligned.apk"
APK_FINAL="$ROOT/OllamaChat.apk"

MAVEN="https://repo1.maven.org/maven2"
OKHTTP_V=3.12.13
OKIO_V=1.17.6
MARKWON_V=4.6.2
COMMONMARK_V=0.13.0

fetch() {  # url dest
    [ -f "$2" ] && return 0
    echo "   fetch $(basename "$2")"
    curl -fsSL -o "$2" "$1"
}

echo ">> [0/7] Resolving toolchain + dependencies"
mkdir -p "$LIBS"
fetch "https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar" "$ANDROID_JAR"
fetch "$MAVEN/com/squareup/okhttp3/okhttp/$OKHTTP_V/okhttp-$OKHTTP_V.jar"          "$LIBS/okhttp.jar"
fetch "$MAVEN/com/squareup/okhttp3/okhttp-sse/$OKHTTP_V/okhttp-sse-$OKHTTP_V.jar"  "$LIBS/okhttp-sse.jar"
fetch "$MAVEN/com/squareup/okio/okio/$OKIO_V/okio-$OKIO_V.jar"                     "$LIBS/okio.jar"
fetch "$MAVEN/com/atlassian/commonmark/commonmark/$COMMONMARK_V/commonmark-$COMMONMARK_V.jar" "$LIBS/commonmark.jar"
fetch "$MAVEN/io/noties/markwon/core/$MARKWON_V/core-$MARKWON_V.aar"               "$LIBS/markwon-core.aar"

# Extract Markwon's classes.jar from the AAR and drop the optional
# AndroidX-dependent text-setter classes we never invoke (keeps dex clean).
MARKWON_CLASSES="$LIBS/markwon-classes"
if [ ! -d "$MARKWON_CLASSES" ]; then
    echo "   unpack markwon-core.aar"
    tmp="$LIBS/.markwon-tmp"
    rm -rf "$tmp" "$MARKWON_CLASSES"
    mkdir -p "$tmp" "$MARKWON_CLASSES"
    ( cd "$tmp" && unzip -oq "$LIBS/markwon-core.aar" classes.jar )
    ( cd "$MARKWON_CLASSES" && unzip -oq "$tmp/classes.jar" )
    find "$MARKWON_CLASSES" -name 'Precomputed*TextSetterCompat*.class' -delete
    rm -rf "$tmp"
fi

CP="$ANDROID_JAR:$LIBS/okhttp.jar:$LIBS/okhttp-sse.jar:$LIBS/okio.jar:$LIBS/commonmark.jar:$MARKWON_CLASSES"

echo ">> Cleaning build dir"
rm -rf "$OUT"
mkdir -p "$GEN" "$CLASSES"

echo ">> [1/7] aapt: compile resources + manifest, generate R.java, create base APK"
aapt package -f -m \
    -M AndroidManifest.xml \
    -S res \
    -I "$ANDROID_JAR" \
    -J "$GEN" \
    -F "$APK_UNSIGNED"

echo ">> [2/7] javac: compile app + stubs (Java 8 bytecode for dx)"
SRCS=$(find src stubs "$GEN" -name '*.java')
javac \
    --release 8 \
    -nowarn \
    -classpath "$CP" \
    -d "$CLASSES" \
    $SRCS 2>&1 | grep -vE "warning:|^Note:|^[0-9]+ warning" || true

echo ">> [3/7] dx: convert app + libs -> classes.dex"
dalvik-exchange --dex --no-strict --output="$OUT/classes.dex" \
    "$CLASSES" \
    "$LIBS/okhttp.jar" \
    "$LIBS/okhttp-sse.jar" \
    "$LIBS/okio.jar" \
    "$LIBS/commonmark.jar" \
    "$MARKWON_CLASSES" 2>&1 | grep -viE "warning: Ignoring|could not find|warning: .*conscrypt|warning: .*androidx|warning: .*bouncycastle|warning: .*openjsse" || true

echo ">> [4/7] aapt: add classes.dex into the APK"
( cd "$OUT" && aapt add -f "$APK_UNSIGNED" classes.dex >/dev/null )

echo ">> [5/7] zipalign: 4-byte align"
zipalign -f 4 "$APK_UNSIGNED" "$APK_ALIGNED"

echo ">> [6/7] apksigner: sign (debug key)"
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

echo ">> [7/7] Verifying signature"
apksigner verify --min-sdk-version 24 -v "$APK_FINAL" | sed 's/^/   /'

echo ""
echo ">> DONE: $APK_FINAL"
ls -lh "$APK_FINAL"
