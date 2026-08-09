#!/bin/sh
# ============================================================
#  จีจี้ — Build Open Minis (Android) APK เสียง nova (ผู้หญิง)
#  รันที่ คอมพิวเตอร์ ที่มี JDK 17 + Android SDK
#  ผลลัพธ์: MinisApp-nova.apk -> นำไปติดตั้ง Oppo ได้เลย
# ============================================================
set -e
BASE="$(cd "$(dirname "$0")/OpenMinis" && pwd)"
cd "$BASE/src/android"

echo "[1/3] ตรวจเครื่องมือ..."
command -v java >/dev/null 2>&1 || { echo "✗ ไม่มี JDK — รัน: sudo apt install openjdk-17-jdk (Debian/Ubuntu)"; exit 1; }
echo "   JDK: $(java -version 2>&1 | head -1)"
[ -n "$ANDROID_HOME" ] || [ -d "$HOME/Android/Sdk" ] \
  || { echo "✗ ไม่มี Android SDK — ตั้ง ANDROID_HOME หรือติดตั้ง Android Studio + SDK"; exit 1; }
[ -n "$ANDROID_HOME" ] || export ANDROID_HOME="$HOME/Android/Sdk"

echo "[2/3] Build APK (debug = เซ็นต์เองได้เลย ไม่ต้องคีย์)..."
./gradlew assembleDebug --no-daemon

echo "[3/3] เก็บไฟล์ APK"
APK="$(find app/build/outputs/apk/debug -name '*.apk' | head -1)"
[ -n "$APK" ] || { echo "✗ build ล้ม หรือไม่พบ apk"; exit 1; }
cp "$APK" "$BASE/MinISApp-nova.apk"
echo
echo "============================================"
echo " ✅ สำเร็จ — APK เสียงผู้หญิง (nova):"
echo "    $BASE/MinISApp-nova.apk"
echo " นำไปติดตั้ง Oppo ตาม android-install.sh"
echo "============================================"