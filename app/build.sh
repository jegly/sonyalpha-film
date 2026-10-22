#!/bin/bash
# Builds Film Sim for Sony PMCA cameras (Android 2.3.7 / API 10).
#   ANDROID_SDK_ROOT  needs platforms/android-10 and build-tools 36.0.0
#   SONY_STUBS        compile-only jar of the com.sony.scalar API (from OpenMemories-Framework stubs)
set -e
cd "$(dirname "$0")"

SDK=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}
BT=$SDK/build-tools/36.0.0
ANDROID_JAR=$SDK/platforms/android-10/android.jar
STUBS=${SONY_STUBS:?set SONY_STUBS to the Sony stub jar}
NAME=FilmSim
KEYSTORE=debug.keystore

rm -rf build && mkdir -p build/gen build/classes build/dex

if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -alias debug -keyalg RSA -keysize 2048 -validity 10000 \
    -keystore "$KEYSTORE" -storepass android -keypass android -dname "CN=FilmSim Debug"
fi

"$BT/aapt" package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$ANDROID_JAR"
find build/gen src -name '*.java' > build/sources.txt
javac -source 8 -target 8 -Xlint:-options -nowarn -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" -cp "$STUBS" -d build/classes @build/sources.txt
"$BT/d8" --min-api 10 --release --lib "$ANDROID_JAR" --classpath "$STUBS" \
  --output build/dex $(find build/classes -name '*.class')
"$BT/aapt" package -f -M AndroidManifest.xml -S res -A assets -I "$ANDROID_JAR" -F build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
# Android 2.3 only understands v1 (JAR) signatures.
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false \
  --min-sdk-version 10 --out "$NAME.apk" build/aligned.apk
echo "built $NAME.apk ($(stat -c %s "$NAME.apk") bytes)"
