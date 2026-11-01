# post-root: from root ADB to a Home Assistant panel

Prepared ahead of time, so that once the eMMC backup is complete and root USB ADB works, the rest
is a short run of gated steps. Nothing here writes to the frame unless you pass `--apply` and type
`yes`, and writes are refused until `backups/a33-emmc-full.img.gz` exists.

Based on [the TF-card Home Assistant report](../docs/pf1007l-tf-card-20260529-home-assistant-report.md)
(same model and eMMC size). Kiosk: Home HQ from `dashboard/kiosk/` (no certificate bypass, offline
screen, retries, Home app, Back/Dashboard buttons).

## Prepare on the Mac (no frame needed)

```sh
post-root/fetch-assets.sh --sizes     # sizes only
post-root/fetch-assets.sh             # downloads, verifies SHA-256, deletes anything that fails
post-root/build-kiosk.sh              # needs JDK 17 + Android build-tools 34 (see the script header)
cp post-root/ha.conf.example post-root/ha.conf    # then set HA_URL
cp scripts/pf1007l-boot/frame-boot.conf.example scripts/pf1007l-boot/frame-boot.conf
```

## After root (frame on USB, `adb -d shell id` = uid=0)

Each step first without `--apply` (dry-run), then with it.

```sh
post-root/post-root.sh check
post-root/post-root.sh launcher --apply
post-root/post-root.sh kiosk --apply      # then tap Home HQ + Always on the frame
post-root/post-root.sh clock --apply
post-root/post-root.sh certs --apply      # only for https:// Home Assistant
post-root/post-root.sh webview --apply    # then power-cycle, check version 106 and a real page
post-root/post-root.sh disable --apply    # AiMOR, qeota, DeviceTest; waits for the state on disk
post-root/post-root.sh bootfixes --apply
post-root/post-root.sh status
```

Undo for WebView: `post-root/post-root.sh webview-undo --apply`. The stock tree is also pulled to
`diagnostics/post-root/backup/`.

## Not tested

These scripts were checked for syntax and for failing cleanly without a device. They have not run
against a frame. The WebView and certificate steps follow the repo's documented procedure but are
new code: read the dry-run output before using `--apply`.

## Not included

- Home Assistant sign-in: do it on the frame, with a dedicated user. No tokens in the URL or files.
- "Ignore SSL errors": not used. Fix certificates instead.
