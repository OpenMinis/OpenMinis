#!/bin/sh
# ============================================================
#  จีจี้ — ติดตั้ง Open Minis บน Android (วิธีครบ)
#  ใช้กับไฟล์: openminis.apk  (ดาวน์โหลดมาระบุ path ตรงไฟล์)
# ============================================================
echo "========================================="
echo "  ติดตั้ง Open Minis บน Android"
echo "========================================="
echo
echo "[Step 1] เตรียมมือถือ Android: เปิด 'ติดตั้งแอปที่ไม่รู้จักแหล่ง'"
echo "   ตั้งค่า(Settings) > ความปลอดภัย/Privacy >"
echo "   เปิด 'Unknown sources / Install unknown apps'"
echo
echo "[Step 2] เลือกวิธีติดตั้ง (อย่างใดอย่างหนึ่ง):"
echo
echo "  ---- วิธี A: เปิดไฟล์ APK บนมือถือ (ง่ายสุด) ----"
echo "    1) โหลด openminis.apk ลงเครื่อง (จาก Chrome/โหลดไฟล์)"
echo "    2) เปิดแอป 'Files/ไฟล์' แล้วแตะไฟล์ openminis.apk"
echo "    3) แตะ 'ติดตั้ง (Install)' -> 'เสร็จ (Done)'"
echo
echo "  ---- วิธี B: คอมพิวเตอร์ + adb (USB) ----"
echo "    เปิด USB Debugging ที่มือถือ (Developer options)"
echo "    adb install openminis.apk"
echo "\n"
echo "  ---- วิธี C: ในเครื่องผ่าน Termux (ไม่มีคอม) ----"
echo "    pkg install termux-adb android-tools"
echo "    adb install openminis.apk"  # ต้องเชื่อม USB debug
echo
echo "[Step 3] หลังติดตั้ง: เปิดแอป -> ตั้งค่าเสียงถามจีจี้ได้เลย"
echo "========================================="