#!/usr/bin/env bash
# Copies Paparazzi snapshots into mobile/screenshots/ with clean file names
# and regenerates mobile/screenshots/README.md (a gallery of every screen).
set -euo pipefail
cd "$(dirname "$0")/.."

SRC="app/src/test/snapshots/images"
DEST="screenshots"
mkdir -p "$DEST"
find "$DEST" -name '*.png' -delete

shopt -s nullglob
for f in "$SRC"/*.png; do
  base="$(basename "$f" .png)"
  # Paparazzi: <package>_<Class>_<testMethod>_<snapshotName>.png -> <snapshotName>.png
  name="$(echo "$base" | grep -oE '[0-9]{2}_[a-z0-9_]+$' || echo "$base")"
  cp "$f" "$DEST/$name.png"
done

{
  echo "# App screenshots"
  echo
  echo "Generated automatically by the **Android CI** workflow (\`.github/workflows/mobile-android.yml\`)"
  echo "using [Paparazzi](https://github.com/cashapp/paparazzi) — every borrower-facing screen of the DPC"
  echo "rendered on a Pixel 5 profile with sample tenant data. Do not edit by hand."
  echo
  for img in $(ls "$DEST"/*.png | sort); do
    file="$(basename "$img")"
    title="$(echo "${file%.png}" | sed -E 's/^[0-9]+_//; s/_/ /g')"
    echo "### ${title^}"
    echo
    echo "<img src=\"$file\" width=\"300\" alt=\"$title\" />"
    echo
  done
} > "$DEST/README.md"

echo "Collected $(ls "$DEST"/*.png | wc -l) screenshots into mobile/$DEST"
