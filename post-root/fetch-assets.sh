#!/usr/bin/env bash
# Downloads the pinned post-root assets into diagnostics/post-root/assets/ and checks each SHA-256.
# A file that fails its check is deleted. Nothing is installed and the frame is not touched.
#
# Usage: post-root/fetch-assets.sh [--sizes]
#   --sizes   only print the download size of each asset (HEAD request), download nothing.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
OUT="$ROOT/diagnostics/post-root/assets"
LOCK="$HERE/assets.lock"

die() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
sha() { shasum -a 256 | cut -d' ' -f1; }

mkdir -p "$OUT"

while IFS='|' read -r name url want kind; do
  case "$name" in ''|'#'*) continue ;; esac

  if [ "${1:-}" = "--sizes" ]; then
    size="$(curl -fsIL --proto '=https' --tlsv1.2 "$url" | tr -d '\r' | awk 'tolower($1)=="content-length:"{s=$2} END{print s}')"
    printf '%-16s %s bytes  %s\n' "$name" "${size:-unknown}" "$url"
    continue
  fi

  dest="$OUT/$name"
  hash_of() {
    if [ "$kind" = cert ]; then openssl x509 -in "$1" -outform der | sha; else sha < "$1"; fi
  }

  if [ -f "$dest" ] && [ "$(hash_of "$dest")" = "$want" ]; then
    printf 'ok (already present): %s\n' "$name"
    continue
  fi

  printf 'downloading %s\n' "$name"
  curl -fL --proto '=https' --tlsv1.2 -o "$dest.part" "$url"
  got="$(hash_of "$dest.part")"
  if [ "$got" != "$want" ]; then
    rm -f "$dest.part"
    die "$name: SHA-256 is $got, expected $want"
  fi
  mv "$dest.part" "$dest"
  printf 'ok: %s\n' "$name"
done < "$LOCK"

[ "${1:-}" = "--sizes" ] && exit 0

# The certificate must also carry the filename Android 6 expects (old OpenSSL subject hash).
want_hash=6187b673
got_hash="$(openssl x509 -in "$OUT/isrgrootx1.pem" -noout -subject_hash_old)"
[ "$got_hash" = "$want_hash" ] || die "isrgrootx1.pem subject hash is $got_hash, expected $want_hash"
printf 'ok: isrgrootx1.pem will be installed as %s.0\n' "$want_hash"
