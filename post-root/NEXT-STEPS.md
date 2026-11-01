# Next steps from "backup finished" to "Home Assistant on the frame"

Commands marked **you** need sudo or the frame in your hands. Everything else Claude can run once the
backup files are readable. Sources: `docs/allwinner-a33-mainline-uboot.md` sections 4 to 7.

## 1. Backup finished (you)

The backup writes `backups/a33-emmc-full.img` and `.img.gz` as root, mode 0600. One command lets Claude
read them for the offline checks, without sudo from then on:

```sh
cd "/Users/alexbongers/Documents/Default Project/photo-frame-dashboard"
sudo chown "$USER" backups/a33-emmc-full.img backups/a33-emmc-full.img.gz
```

(`backup-resume` already compares each segment three ways, so a separate verify of the fresh backup
is not needed. `verify-original-resume` is for after a restore.)

## 2. Offline checks (Claude, reads the backup only)

```sh
scripts/a33-uboot/inspect-emmc-backup.sh backups/a33-emmc-full.img     # sizes, partitions, T6 hash, saved USB setting
python3 -I scripts/a33-uboot/boot-patch.py backups/a33-emmc-full.img 172032
```

Stops if the boot hash is not in the firmware table or the saved USB setting is not `mass_storage`.
Writes `diagnostics/a33-boot-patch/` (plan.json, payloads, write and restore scripts). Touches no device.

## 3. Your approval of the exact write

Claude shows the route (Route U), the byte ranges from plan.json (expected: header `[172032, 172034)`
plus one ramdisk slot), and the undo (`restore-boot.scr`). Nothing is written before you say yes to that.

## 4. The write (you, frame in FEL, after approval)

Power-cycle into FEL, then the command in section 6 of the U-Boot guide (`sunxi-fel ... uboot ... write-boot.scr`),
and when the read-only eMMC reappears:

```sh
sudo python3 -I scripts/a33-uboot/emmc-macos.py verify diskN 15758000128
```

Use the same 12 MHz U-Boot build that made the backup (`diagnostics/a33-uboot-out-12mhz`), not the
default one.

## 5. Boot and root (you)

Unplug, remove the FEL card, plug back in. Expect USB `1f3a:1002` and `adb -d shell id` = `uid=0(root)`.
Wait for the AiMOR screen; keep Wi-Fi off until `disable` has run.

## 6. Everything else (Claude)

`post-root/post-root.sh` steps `check`, `launcher`, `kiosk`, `clock`, `certs`, `webview`, `disable`,
`bootfixes`, `status`, run with `--apply` once the backup is finished. Needs `post-root/ha.conf` with `HA_URL`.

## On the frame, once Home HQ shows the dashboard (you)

Sign in as `frame` (non-admin since 2026-10-30). Then Profile -> "Always hide the sidebar". That
setting is stored per browser, so it can only be set on the frame itself. Dashboard:
`/dashboard-fotolijst/0` (Fotolijst, created in Home Assistant; edit it there).

Before the frame goes live: `frame` was made admin again on 2026-10-30 so the dashboard could be edited.
Demote it to a normal user (Settings -> People -> Users -> frame -> turn off Administrator) once the
dashboard is final. A wall panel should not carry admin rights.

## State on 2026-10-31

Done and checked on the frame: launcher, Home HQ as Home app with the Fotolijst URL, time zone, WebView
106.0.5249.126, vendor apps disabled, boot fixes (MAC 02:83:c6:82:54:ca), Wi-Fi joined, dashboard rendering.
Measured: Home HQ about 118 MB PSS, about 627 MB free RAM, zRAM unused.
Open, on the frame in Profile: language Dutch, 24-hour time, hide the sidebar, dark theme.
Open in Home Assistant: demote the `frame` user to a normal user; optionally reserve the IP for the MAC.
