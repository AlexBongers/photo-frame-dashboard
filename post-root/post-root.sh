#!/usr/bin/env bash
# PF1007L after root: launcher, Home HQ kiosk with your Home Assistant URL, clock, CA root,
# WebView 106, disabling the vendor apps, boot fixes. One gated step at a time.
# Guides: docs/pf1007l-tf-card-20260529-home-assistant-report.md, docs/allwinner-a33-pf1007l.md
#
# Usage: post-root/post-root.sh <command> [--apply]
#   check        Read-only: is this a PF1007L with root USB ADB, and what is installed.
#   status       Read-only: which of the steps below are done.
#   launcher     Install Rootless Launcher (escape route).
#   kiosk        Install Home HQ, save HA_URL, make it the Home app (you tap "Always" on the frame).
#   clock        Time zone and automatic time.
#   certs        Add ISRG Root X1 (Let's Encrypt) to /system. Needed for https:// Home Assistant.
#   webview      Replace WebView 44 with 106 (touches /system, keeps the stock tree for undo).
#   webview-undo Put the stock WebView back.
#   disable      Disable AiMOR, the vendor updater and DeviceTest. Do this last-but-one.
#   bootfixes    scripts/pf1007l-boot/install.sh (one MAC, clock after power cuts, no freeze).
#
# Suggested order: check, launcher, kiosk, clock, certs, webview, (power-cycle), disable, bootfixes.
# Without --apply a command shows what it would do and changes nothing on the frame.
# With --apply it asks you to type "yes" first. Uses the one USB ADB device (adb -d).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
ASSETS="$ROOT/diagnostics/post-root/assets"
BACKUP_DIR="$ROOT/diagnostics/post-root/backup"
KIOSK_APK="$ROOT/dashboard/kiosk/build/homehq.apk"
KIOSK_PKG=com.wesbos.homehq
EMMC_BACKUP="$ROOT/backups/a33-emmc-full.img.gz"
CONF="$HERE/ha.conf"
APPLY=0
HA_URL="${HA_URL:-}"
TIMEZONE="${TIMEZONE:-Europe/Amsterdam}"
TEXT_ZOOM="${TEXT_ZOOM:-}"

# Git Bash rewrites POSIX-looking arguments before calling adb.exe.
case "$(uname -s)" in
  MINGW*|MSYS*) export MSYS2_ARG_CONV_EXCL="/system/;/data/;/sys/;/proc/" ;;
esac

say()  { printf '\n==> %s\n' "$*"; }
ok()   { printf '    ok: %s\n' "$*"; }
note() { printf '    note: %s\n' "$*"; }
die()  { printf '    FAIL: %s\n' "$*" >&2; exit 1; }

# adb must not read the script's stdin: it would eat the confirmation answer and garble output.
A() { adb -d "$@" </dev/null; }
sh_dev() { A shell "$@" | tr -d '\r'; }
sha() { shasum -a 256 | cut -d' ' -f1; }
dev_cat_sha() { A exec-out "cat $1" | sha; }

# Run a command that changes the frame, or only show it.
mut() {
  if [ "$APPLY" = 1 ]; then "$@" </dev/null; else local s="$*"; printf '    [dry-run] %s\n' "${s/#A /adb -d }"; fi
}
# Same, for a remote shell command string.
mut_sh() {
  if [ "$APPLY" = 1 ]; then A shell "$1" | tr -d '\r'; else printf '    [dry-run] adb shell %s\n' "$1"; fi
}

load_conf() {
  # shellcheck source=/dev/null
  [ -f "$CONF" ] && . "$CONF"
  return 0
}

preflight() {
  command -v adb >/dev/null || die "adb not found"
  [ "$(A get-state 2>/dev/null || true)" = device ] || die "no USB ADB device (check cable, power, root ADB)"
  [ "$(sh_dev getprop ro.product.device)" = astar-m86_8723cs ] || die "not a PF1007L (astar-m86_8723cs)"
  [ "$(sh_dev getprop ro.build.version.release)" = 6.0.1 ] || die "Android is not 6.0.1"
  [ "$(sh_dev id | cut -d' ' -f1)" = "uid=0(root)" ] || die "adb shell is not root (T7 in docs/pf1007l-start-here.md)"
  [ "$(sh_dev getprop sys.boot_completed)" = 1 ] || die "boot is not completed"
  ok "PF1007L, Android 6.0.1, root USB ADB, $(sh_dev getprop ro.build.display.id)"
}

# Every write needs the finished eMMC backup and the owner's typed yes.
gate() {
  [ "$APPLY" = 1 ] || { note "dry-run: pass --apply to do this"; return 0; }
  [ -f "$EMMC_BACKUP" ] || die "no finished backup at backups/a33-emmc-full.img.gz (run the backup first)"
  [ ! -f "$ROOT/backups/a33-emmc-full.img.partial" ] || die "a backup is still in progress"
  printf '    This changes the frame. Type yes to continue: '
  read -r answer
  [ "$answer" = yes ] || die "not confirmed"
}

need_file() { [ -f "$1" ] || die "missing $1 (run post-root/fetch-assets.sh)"; }

# df wraps the long device name, so join the lines and take the third field from the end.
sys_free_kib() { sh_dev "toybox df -k /system" | tail -n +2 | tr '\n' ' ' | awk '{print $(NF-2)}'; }

pkg_installed() { sh_dev "pm list packages $1" | grep -qx "package:$1"; }

remount_rw() {
  A shell "mount -o remount,rw /system" >/dev/null 2>&1 || true
  sh_dev "mount" | grep -E ' /system ' | grep -q 'rw[, ]' || die "could not remount /system read-write"
  ok "/system is read-write"
}
remount_ro() {
  A shell "sync; mount -o remount,ro /system" >/dev/null 2>&1 || true
  sh_dev "mount" | grep -E ' /system ' | grep -q 'ro[, ]' || die "/system is still read-write: remount it read-only before rebooting"
  ok "/system is read-only"
}

cmd_check() {
  say "Checking the frame (read-only)"
  preflight
  note "SELinux: $(sh_dev getenforce)"
  note "screen: $(sh_dev wm size | tr '\n' ' ') density: $(sh_dev wm density | tr '\n' ' ')"
  note "WebView: $(sh_dev "dumpsys package com.android.webview | grep versionName | head -1" | tr -d ' ')"
  note "/system free (KiB): $(sys_free_kib)"
  note "time zone: $(sh_dev getprop persist.sys.timezone), auto_time: $(sh_dev settings get global auto_time)"
  [ -f "$EMMC_BACKUP" ] && ok "eMMC backup file present" || note "no finished eMMC backup yet; writes are refused"
}

cmd_status() {
  say "Status (read-only)"
  preflight
  pkg_installed amirz.rootless.nexuslauncher && ok "Rootless Launcher installed" || note "Rootless Launcher: no"
  pkg_installed "$KIOSK_PKG" && ok "Home HQ installed" || note "Home HQ: no"
  note "kiosk URL: $("$ROOT/scripts/set-kiosk-url.sh" 2>/dev/null || echo unknown)"
  note "WebView: $(sh_dev "dumpsys package com.android.webview | grep versionName | head -1" | tr -d ' ')"
  [ -n "$(sh_dev "ls /system/etc/security/cacerts/6187b673.0 2>/dev/null")" ] && ok "ISRG Root X1 present" || note "ISRG Root X1: no"
  for p in com.efercro.os.aimor com.yhk.qeota com.DeviceTest; do
    sh_dev "dumpsys package $p | grep -m1 'enabled='" | sed "s/^/    $p: /"
  done
}

cmd_launcher() {
  say "Rootless Launcher"
  preflight
  need_file "$ASSETS/rootless.apk"
  gate
  mut A install -r "$ASSETS/rootless.apk"
  note "Confirm on the frame that it works and that you can reach Settings -> Apps before 'disable'."
}

cmd_kiosk() {
  say "Home HQ kiosk"
  preflight
  load_conf
  [ -n "$HA_URL" ] || die "set HA_URL in post-root/ha.conf"
  need_file "$KIOSK_APK"
  gate
  mut A install -r "$KIOSK_APK"
  # Keeps the Back/Dashboard buttons alive across starts; the grant survives updates.
  mut A shell pm grant "$KIOSK_PKG" android.permission.WRITE_SECURE_SETTINGS
  mut "$ROOT/scripts/set-kiosk-url.sh" "$HA_URL"
  if [ -n "$TEXT_ZOOM" ]; then
    case "$TEXT_ZOOM" in *[!0-9]*) die "TEXT_ZOOM must be a number (percent)" ;; esac
    mut A shell am start -n "$KIOSK_PKG/.MainActivity" --ei text_zoom "$TEXT_ZOOM"
  fi
  mut A shell am start -n "$KIOSK_PKG/.MainActivity" --ez home true
  mut A shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
  note "On the frame: tap Home HQ and Always in the Home chooser. Wait about 30 s before any reboot."
  note "Then enable 'Home HQ navigation' in Android Settings -> Accessibility (Back/Dashboard buttons)."
  note "Check: adb -d shell 'grep -A2 \"<preferred-activities\" /data/system/users/0/package-restrictions.xml'"
  note "Sign in to Home Assistant on the frame itself, with a dedicated user."
}

cmd_clock() {
  say "Clock"
  preflight
  load_conf
  case "$TIMEZONE" in *[!A-Za-z0-9_/+-]*|'') die "TIMEZONE has characters adb cannot carry safely" ;; esac
  gate
  mut_sh "setprop persist.sys.timezone $TIMEZONE"
  mut_sh "service call alarm 3 s16 $TIMEZONE"
  mut_sh "settings put global auto_time 1"
  note "Restart the kiosk afterwards so the page picks up the zone."
}

cmd_certs() {
  say "ISRG Root X1"
  preflight
  need_file "$ASSETS/isrgrootx1.pem"
  local dir=/system/etc/security/cacerts dest=6187b673.0
  [ -z "$(sh_dev "ls $dir/$dest 2>/dev/null")" ] || die "$dir/$dest already exists; not overwriting"
  gate
  if [ "$APPLY" = 1 ]; then
    mkdir -p "$BACKUP_DIR"
    sh_dev "ls -l $dir" > "$BACKUP_DIR/cacerts-listing-before.txt"
    A push "$ASSETS/isrgrootx1.pem" /data/local/tmp/isrg.pem >/dev/null
    remount_rw
    A shell "cp /data/local/tmp/isrg.pem $dir/$dest && chmod 644 $dir/$dest && chown root:root $dir/$dest"
    # Same SELinux type as its neighbours, if the tool exists on this build.
    A shell "chcon --reference=$dir/$(sh_dev "ls $dir | head -1") $dir/$dest" >/dev/null 2>&1 || note "chcon not available (SELinux is usually off here)"
    [ "$(dev_cat_sha $dir/$dest)" = "$(sha < "$ASSETS/isrgrootx1.pem")" ] || die "readback of $dest differs"
    ok "installed and read back $dest"
    remount_ro
    A shell rm -f /data/local/tmp/isrg.pem
  else
    printf '    [dry-run] install isrgrootx1.pem as %s/%s (mode 644 root:root), read back, remount ro\n' "$dir" "$dest"
  fi
  note "Undo: remount /system rw, rm $dir/$dest, remount ro. Restart Home HQ to use it."
}

WV_LIVE=/system/app/webview
WV_NEW=/system/homehq-new-webview
WV_STOCK=/system/homehq-stock-webview

cmd_webview() {
  say "WebView 106 (touches /system)"
  preflight
  need_file "$ASSETS/webview.apk"
  local tmp libs n
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  unzip -q -o "$ASSETS/webview.apk" 'lib/armeabi-v7a/*.so' -d "$tmp" || die "could not extract libraries"
  libs="$(cd "$tmp/lib/armeabi-v7a" && ls *.so)"
  for n in libchromium_android_linker.so libcrashpad_handler_trampoline.so libwebviewchromium.so; do
    printf '%s\n' "$libs" | grep -qx "$n" || die "APK has no $n"
  done
  ok "APK libraries: $(printf '%s' "$libs" | tr '\n' ' ')"

  [ -n "$(sh_dev "ls -d $WV_LIVE 2>/dev/null")" ] || die "$WV_LIVE not found"
  [ -z "$(sh_dev "ls -d $WV_NEW $WV_STOCK 2>/dev/null")" ] || die "$WV_NEW or $WV_STOCK already exists; refusing to overwrite"
  local free
  free="$(sys_free_kib)"
  [ "${free:-0}" -ge 300000 ] || die "only ${free:-0} KiB free on /system, need 300000"
  ok "free on /system: $free KiB"
  gate

  if [ "$APPLY" != 1 ]; then
    cat <<EOF
    [dry-run] pull $WV_LIVE (whole stock tree) to $BACKUP_DIR/webview-stock and compare hashes
    [dry-run] stage webview.apk and the 3 libraries in /data/local/tmp/wv-new, read back
    [dry-run] remount /system rw, build $WV_NEW (dirs 0755, files 0644, root:root), read back
    [dry-run] mv $WV_LIVE $WV_STOCK, mv $WV_NEW $WV_LIVE, read back, sync, remount ro
EOF
    return 0
  fi

  say "Backing up the stock WebView tree"
  mkdir -p "$BACKUP_DIR/webview-stock"
  A pull "$WV_LIVE/." "$BACKUP_DIR/webview-stock" >/dev/null
  (cd "$BACKUP_DIR/webview-stock" && find . -type f | sort) | while read -r f; do
    [ "$(sha < "$BACKUP_DIR/webview-stock/$f")" = "$(dev_cat_sha "$WV_LIVE/${f#./}")" ] || die "backup of $f differs"
  done
  ok "stock tree backed up and verified in diagnostics/post-root/backup/webview-stock"

  say "Staging"
  A shell "rm -rf /data/local/tmp/wv-new; mkdir -p /data/local/tmp/wv-new/lib/arm"
  A push "$ASSETS/webview.apk" /data/local/tmp/wv-new/webview.apk >/dev/null
  for n in $libs; do A push "$tmp/lib/armeabi-v7a/$n" "/data/local/tmp/wv-new/lib/arm/$n" >/dev/null; done
  [ "$(dev_cat_sha /data/local/tmp/wv-new/webview.apk)" = "$(sha < "$ASSETS/webview.apk")" ] || die "staged APK differs"
  for n in $libs; do
    [ "$(dev_cat_sha "/data/local/tmp/wv-new/lib/arm/$n")" = "$(sha < "$tmp/lib/armeabi-v7a/$n")" ] || die "staged $n differs"
  done
  ok "staged and read back"

  say "Building the new tree on /system"
  remount_rw
  A shell "mkdir -p $WV_NEW/lib/arm && cp /data/local/tmp/wv-new/webview.apk $WV_NEW/ && cp /data/local/tmp/wv-new/lib/arm/*.so $WV_NEW/lib/arm/ && chmod 0755 $WV_NEW $WV_NEW/lib $WV_NEW/lib/arm && chmod 0644 $WV_NEW/webview.apk $WV_NEW/lib/arm/*.so && chown -R root:root $WV_NEW"
  [ "$(dev_cat_sha $WV_NEW/webview.apk)" = "$(sha < "$ASSETS/webview.apk")" ] || die "$WV_NEW/webview.apk differs"
  for n in $libs; do
    [ "$(dev_cat_sha "$WV_NEW/lib/arm/$n")" = "$(sha < "$tmp/lib/armeabi-v7a/$n")" ] || die "$WV_NEW $n differs"
  done
  ok "new tree read back"

  say "Swapping"
  A shell "mv $WV_LIVE $WV_STOCK && mv $WV_NEW $WV_LIVE"
  [ "$(dev_cat_sha $WV_LIVE/webview.apk)" = "$(sha < "$ASSETS/webview.apk")" ] || die "live webview.apk differs (undo: post-root.sh webview-undo --apply)"
  for n in $libs; do
    [ "$(dev_cat_sha "$WV_LIVE/lib/arm/$n")" = "$(sha < "$tmp/lib/armeabi-v7a/$n")" ] || die "live $n differs (undo: webview-undo)"
  done
  ok "live tree read back"
  remount_ro
  A shell rm -rf /data/local/tmp/wv-new
  note "Power-cycle the frame (adb reboot often hangs). A hub-powered unit may need one boot with Wi-Fi off."
  note "Then: adb -d shell dumpsys package com.android.webview | grep versionName   (106.0.5249.126)"
  note "And: adb -d logcat -d | grep -iE 'Failed to load library|dlopen failed'   (must be empty)"
}

cmd_webview_undo() {
  say "Restoring the stock WebView"
  preflight
  [ -n "$(sh_dev "ls -d $WV_STOCK 2>/dev/null")" ] || die "$WV_STOCK not found, nothing to restore"
  gate
  if [ "$APPLY" = 1 ]; then
    remount_rw
    A shell "mv $WV_LIVE $WV_NEW && mv $WV_STOCK $WV_LIVE"
    remount_ro
    note "Power-cycle the frame. The 106 tree is kept at $WV_NEW until you delete it."
  else
    printf '    [dry-run] mv %s %s; mv %s %s; remount ro\n' "$WV_LIVE" "$WV_NEW" "$WV_STOCK" "$WV_LIVE"
  fi
}

cmd_disable() {
  say "Disable vendor apps"
  preflight
  pkg_installed amirz.rootless.nexuslauncher || die "install the launcher first"
  pkg_installed "$KIOSK_PKG" || die "install Home HQ first"
  note "Disabling AiMOR also closes its remote root shell. Keep USB ADB available afterwards."
  gate
  local p
  mkdir -p "$BACKUP_DIR"
  for p in com.efercro.os.aimor com.yhk.qeota com.DeviceTest; do
    pkg_installed "$p" || { note "$p not installed, skipping"; continue; }
    [ "$APPLY" = 1 ] && sh_dev "dumpsys package $p" > "$BACKUP_DIR/dumpsys-$p.txt"
    mut A shell pm disable "$p"
    mut A shell am force-stop "$p"
  done
  [ "$APPLY" = 1 ] || return 0
  say "Waiting for Package Manager to write the disabled state"
  for _ in $(seq 1 30); do
    sleep 3
    xml="$(sh_dev "cat /data/system/users/0/package-restrictions.xml")"
    done_all=1
    for p in com.efercro.os.aimor com.yhk.qeota com.DeviceTest; do
      pkg_installed "$p" || continue
      printf '%s' "$xml" | grep "name=\"$p\"" | grep -q 'enabled="2"' || done_all=0
    done
    [ "$done_all" = 1 ] && { ok "enabled=\"2\" is on disk for every package"; return 0; }
  done
  die "package-restrictions.xml does not show the disabled state yet; do not reboot"
}

cmd_bootfixes() {
  say "Boot fixes"
  preflight
  [ -f "$ROOT/scripts/pf1007l-boot/frame-boot.conf" ] ||
    die "copy scripts/pf1007l-boot/frame-boot.conf.example to frame-boot.conf and edit it first"
  "$ROOT/scripts/pf1007l-boot/install.sh" check </dev/null
  gate
  mut "$ROOT/scripts/pf1007l-boot/install.sh" apply
  note "After a reboot: scripts/pf1007l-boot/install.sh verify"
}

[ $# -ge 1 ] || { sed -n '2,22p' "$0"; exit 1; }
cmd="$1"; shift
for arg in "$@"; do
  case "$arg" in
    --apply) APPLY=1 ;;
    *) die "unknown argument: $arg" ;;
  esac
done

case "$cmd" in
  check) cmd_check ;;
  status) cmd_status ;;
  launcher) cmd_launcher ;;
  kiosk) cmd_kiosk ;;
  clock) cmd_clock ;;
  certs) cmd_certs ;;
  webview) cmd_webview ;;
  webview-undo) cmd_webview_undo ;;
  disable) cmd_disable ;;
  bootfixes) cmd_bootfixes ;;
  *) sed -n '2,22p' "$0"; exit 1 ;;
esac
