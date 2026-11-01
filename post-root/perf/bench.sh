#!/usr/bin/env bash
# Runs bench.mjs against the frame: starts the kiosk with WebView debugging, forwards the port,
# measures, then restarts the kiosk normally (debugging off). Changes nothing else on the frame.
#
# Usage: post-root/perf/bench.sh [label]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PKG=com.wesbos.homehq
a() { adb -d "$@" </dev/null; }

command -v node >/dev/null || { echo "node (v22 or newer) is needed" >&2; exit 1; }
a get-state | grep -q device || { echo "no USB ADB device" >&2; exit 1; }

cleanup() {
  adb -d forward --remove tcp:9222 >/dev/null 2>&1 || true
  a shell am force-stop "$PKG" || true
  a shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true
}
trap cleanup EXIT

a shell am force-stop "$PKG"
a shell am start -n "$PKG/.MainActivity" --ez debug true >/dev/null
sleep "${BENCH_WAIT:-8}"
# pidof is unreliable on Android 6; the debug socket name carries the right pid.
sock="$(a shell cat /proc/net/unix | tr -d '\r' | grep -o 'webview_devtools_remote_[0-9]*' | head -1)"
[ -n "$sock" ] || { echo "WebView debug socket not found (is the kiosk running?)" >&2; exit 1; }
adb -d forward tcp:9222 "localabstract:${sock}" >/dev/null
node "$HERE/bench.mjs" "${1:-run}"
echo "--- cpu: $(a shell cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq | tr -d '\r') kHz, online: $(a shell cat /sys/devices/system/cpu/online | tr -d '\r')"
