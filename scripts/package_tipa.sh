#!/bin/bash
# package_tipa.sh — package a built .app into .ipa and .tipa (TrollStore).
# Usage: ./package_tipa.sh <app_bundle> <output_dir>
set -euo pipefail

APP="${1:?app bundle required}"
OUT="${2:?output dir required}"
mkdir -p "$OUT" /tmp/payload/Payload

rm -rf /tmp/payload/Payload/*
cp -R "$APP" /tmp/payload/Payload/
cd /tmp/payload

NAME="$(basename "$APP" .app)"
STAMP="$(date +%Y%m%d-%H%M)"
IPA="$OUT/OpenMinis-Blink-${STAMP}.ipa"
TIPA="$OUT/OpenMinis-Blink-${STAMP}.tipa"

rm -f "$IPA" "$TIPA"
zip -q -r "$IPA" Payload/
cp "$IPA" "$TIPA"

echo "IPA:  $IPA  ($(du -h "$IPA" | cut -f1))"
echo "TIPA: $TIPA  ($(du -h "$TIPA" | cut -f1))"
