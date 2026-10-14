#!/bin/bash
# Updates the bundled models.dev/api.json used as a fallback model registry.
# Run this periodically to keep the bundled data fresh.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
IOS_DEST="$SCRIPT_DIR/../src/ios/Resources/models-dev-api.json"
# [T-apk-size] Android ships ONLY the gzipped catalog (~416KB vs 4.1MB
# plaintext). The plaintext copy used to sit next to it as a fallback, but
# the app reads the gzip first and a local asset failing to gunzip means a
# corrupt APK — the network path is the real fallback.
ANDROID_GZ="$SCRIPT_DIR/../src/android/app/src/main/assets/models-dev-api.json.gzip"
URL="https://models.dev/api.json"

echo "Downloading $URL ..."
curl -fsSL "$URL" -o "$IOS_DEST.tmp"

# Validate JSON
if ! python3 -m json.tool "$IOS_DEST.tmp" > /dev/null 2>&1; then
    echo "ERROR: Downloaded file is not valid JSON"
    rm -f "$IOS_DEST.tmp"
    exit 1
fi

PROVIDERS=$(python3 -c "import json; d=json.load(open('$IOS_DEST.tmp')); print(len(d))")
echo "Valid JSON — $PROVIDERS providers"

mv "$IOS_DEST.tmp" "$IOS_DEST"
echo "Updated $IOS_DEST"

# The app resolves cross-provider model ids through a precomputed stage-2 index
# (ModelsDevAPI.buildStage2Index). Verify the new catalog still resolves exactly
# as a full per-key scan would, so a catalog update can never silently change
# which entry wins the majority vote.
echo "Verifying stage-2 resolution against the new catalog ..."
python3 "$SCRIPT_DIR/verify_models_dev_resolution.py"

# Android ships a gzipped catalog to keep APK size down; iOS keeps the raw JSON.
python3 - "$IOS_DEST" "$ANDROID_GZ" <<'PY'
import gzip, shutil, sys
src, dst = sys.argv[1], sys.argv[2]
with open(src, "rb") as inf, gzip.open(dst, "wb", compresslevel=9) as out:
    shutil.copyfileobj(inf, out)
PY
echo "Updated $ANDROID_GZ"

# Show size
SIZE=$(wc -c < "$IOS_DEST" | tr -d ' ')
GZ_SIZE=$(wc -c < "$ANDROID_GZ" | tr -d ' ')
echo "File size: ${SIZE} bytes (android gzip: ${GZ_SIZE} bytes)"
