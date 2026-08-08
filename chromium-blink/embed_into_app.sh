#!/bin/bash
# embed_into_app.sh — copy the Blink engine (the built content_shell.app) into
# a built Minis.app bundle so the dlopen bridge + resource resolution work.
#
# The built content_shell.app already contains the correct assembly of:
#   Frameworks/content_shell_framework.framework  (the 263MB engine dylib)
#   icudtl.dat, *.pak, *.ttf, net/ (SSL certs), locales/
# all resolved by Blink from the MAIN bundle — so we mirror that layout into
# Minis.app (resources at bundle root, framework under Frameworks/).
#
# Usage: ./embed_into_app.sh <minis_app_bundle> <content_shell_app>
set -euo pipefail

APP="${1:?Minis.app bundle required}"
CS="${2:?content_shell.app bundle required}"

[ -d "$CS" ] || { echo "!! content_shell.app not found: $CS"; exit 1; }
echo "==> embedding Blink engine (content_shell.app) into $APP"

# 1. Frameworks (all of them — content_shell_framework may weakly link others).
mkdir -p "$APP/Frameworks"
for fw in "$CS"/Frameworks/*; do
  [ -e "$fw" ] && cp -R "$fw" "$APP/Frameworks/"
done

# 2. Resources at bundle root (mirroring content_shell.app's own layout).
for item in "$CS"/*; do
  name=$(basename "$item")
  case "$name" in
    content_shell|Info.plist|PkgInfo|Assets.car|LaunchScreen*) continue ;;
    *.png) continue ;;  # content_shell's own icons
    _CodeSignature|embedded.mobileprovision) continue ;;
  esac
  cp -R "$item" "$APP/"
done

echo "==> embedding complete:"
du -sh "$APP/Frameworks/content_shell_framework.framework"
ls "$APP" | grep -E "icudtl|\.pak|\.ttf|^net$" || true
