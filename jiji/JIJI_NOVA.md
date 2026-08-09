# 🎙️ จีจี้ — ทำเสียงผู้หญิง (nova) ให้แอป Open Minis

## ว่าไง
เปลี่ยนเสียงตอบเริ่มต้นของแอปจาก **alloy (ชาย)** → **nova (หญิง)** ให้ได้เสียงผู้หญิง**ตั้งแต่ติดตั้งครั้งแรก**

## แก้ที่ไหน (นี้ออกจาก cherry)
| # | ไฟล์ (ใน repo นี้) | บรรทัด | แก้ |
|---|---|---|---|
| 1 | `src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProvider.kt` | 116 | `defaultVoiceOutputVoice() = "nova"` |
| 2 | `src/ios/Providers/Voice/VoiceProvider.swift` | 76 | `defaultVoiceOutputVoice() -> "nova"` |
| 3 | `src/android/.../sandbox/offload/ModelUseOffloadHandler.kt` | 1769 | sample body `"voice":"nova"` |
| 4 | `src/ios/NativeOffloads/ModelUseOffload.m` | 110 | sample body `"voice":"nova"` |

> จุด 3-4 เป็นตัวอย่าง `ModelUseOffload` ให้ตรงกับ 1-2 (ไม่กระทบฟังก์ชัน)

## Build เป็น APK (Android, ใช้กับ Oppo)
ให้ **GitHub Actions** (cloud) build ให้ โดยไม่ต้องใช้คอม:
- workflow: `.github/workflows/build-apk.yml` (อยู่ใน repo นี้แล้ว ถ้าพบ)
- ไปที่ **Actions → build-apk → Run workflow**
- เสร็จแล้ว **Artifacts → ดาวน์โหลด `MinisApp-nova.apk`** → โหลดลง Oppo

หรือ build เองที่คอม (ถ้ามี):
```sh
cd src/android
export ANDROID_HOME=<path sdk>
./gradlew assembleDebug
# APK อยู่: app/build/outputs/apk/debug/app-debug.apk
```

## ติดตั้ง Oppo (A9 5G / Android)
1. ตั้งค่า → รหัสผ่านและความปลอดภัย → ติดตั้งแอปจากแหล่งที่ไม่รู้จัก → เปิด **Chrome**
2. เปิดไฟล์ `MinisApp-nova.apk` → ติดตั้ง
3. เปิดแอป → เสียงตอบเป็นผู้หญิง (nova) 🎉

## หมายเหตุ iOS
โค้ด iOS แก้ nova แล้ว (row 2,4) แต่การติดตั้ง iPhone ต้อง Xcode+Developer — ยาก ใช้ Oppo เป็นหลัก

_— จีจี้ (บันทึกไว้ที่ repo พี่ เพื่อไม่หาย)_