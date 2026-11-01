#!/usr/bin/env bash
# Builds the Home HQ kiosk APK (dashboard/kiosk/build/homehq.apk). Never touches the frame.
# Needs JDK 17 and Android build-tools 34.0.0 + platform android-34 (JDK 21 breaks d8).
#
# One-time toolchain on macOS (Homebrew):
#   brew install openjdk@17 && brew install --cask android-commandlinetools
#   yes | sdkmanager --licenses
#   sdkmanager "build-tools;34.0.0" "platforms;android-34"
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17 ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17
fi
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"

die() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

command -v javac >/dev/null 2>&1 && javac -version 2>&1 | grep -q ' 17\.' || die "JDK 17 not found (see the header of this script)"
[ -x "$ANDROID_HOME/build-tools/34.0.0/aapt2" ] || die "build-tools 34.0.0 missing in $ANDROID_HOME"
[ -f "$ANDROID_HOME/platforms/android-34/android.jar" ] || die "platform android-34 missing in $ANDROID_HOME"

bash "$ROOT/dashboard/kiosk/build.sh"
ls -l "$ROOT/dashboard/kiosk/build/homehq.apk"
