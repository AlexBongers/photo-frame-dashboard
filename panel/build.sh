#!/usr/bin/env bash
# Builds panel/build/panel.apk without Gradle: JDK 17 + Android build-tools 34 + platform 34
# (the same toolchain as dashboard/kiosk, see post-root/build-kiosk.sh for the one-time setup).
# Install: adb -d install -r panel/build/panel.apk
set -euo pipefail
cd "$(dirname "$0")"

export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17 ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17
fi
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"

BT="${BUILD_TOOLS:-$ANDROID_HOME/build-tools/34.0.0}"
JAR="$ANDROID_HOME/platforms/android-34/android.jar"
KS="$HOME/.android/debug.keystore"

rm -rf build && mkdir -p build/gen build/classes build/assets
cp -R assets/. build/assets/

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml -A build/assets build/res.zip \
  --java build/gen --min-sdk-version 23 --target-sdk-version 28 --version-code 1 --version-name 0.1

javac -nowarn -source 8 -target 8 -classpath "$JAR" -d build/classes \
  src/nl/fotolijst/panel/*.java build/gen/nl/fotolijst/panel/R.java

"$BT/d8" --min-api 23 --lib "$JAR" --output build $(find build/classes -name '*.class')
(cd build && zip -qj base.apk classes.dex)
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk

if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out build/panel.apk build/aligned.apk
echo "Built panel/build/panel.apk"
