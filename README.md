# Abu-Zahra Admin New

نظام إدارة وتحكم متكامل للأجهزة عن بُعد — يتكون من تطبيق Android وسيرفر Python مع لوحة تحكم ويب مدمجة.

---

## هيكل المشروع

```
Abu-Zahra-Admin-New/
├── .github/
│   └── workflows/
│       └── build.yml              # بناء تلقائي عبر GitHub Actions
│
├── Android-App/                    # تطبيق Android (Kotlin)
│   ├── app/
│   │   ├── src/main/java/com/abuzahra/manager/
│   │   │   ├── App.kt             # فئة التطبيق الرئيسية
│   │   │   ├── MainActivity.kt    # النشاط الرئيسي
│   │   │   ├── Config.kt          # إعدادات التطبيق
│   │   │   ├── api/               # ApiClient, FirebaseManager
│   │   │   ├── executor/          # تنفيذ الأوامر (7 ملفات)
│   │   │   ├── service/           # الخدمات الخلفية (7 ملفات)
│   │   │   ├── streaming/         # البث المباشر (9 ملفات)
│   │   │   ├── storage/           # إدارة التخزين (5 ملفات)
│   │   │   ├── model/             # نماذج البيانات
│   │   │   ├── database/          # Room Database
│   │   │   ├── worker/            # WorkManager المهام الخلفية
│   │   │   ├── permission/        # إدارة الصلاحيات
│   │   │   ├── sync/              # مزامنة البيانات
│   │   │   ├── repository/        # نمط Repository
│   │   │   └── util/              # أدوات مساعدة
│   │   └── build.gradle
│   └── gradlew
│
├── Server/
│   ├── server.py                  # سيرفر Python + لوحة تحكم ويب (~4300 سطر)
│   └── requirements.txt           # المتطلبات
│
├── Releases/                      # إصدارات APK
│   ├── Abu-Zahra-Admin-v3.7.0.apk
│   └── AbuZahra-Admin-v4.0.0.apk
│
└── README.md
```

---

## تطبيق Android

- **اللغة:** Kotlin
- **minSdk:** 24 (Android 7.0) | **targetSdk:** 34 (Android 14)
- **عدد ملفات Kotlin:** 53 ملف

### الوحدات الرئيسية

| الوحدة | الوصف |
|--------|-------|
| `executor/` | تنفيذ الأوامر: تطبيقات، تحكم، بيانات، ملفات، مراقبة، أمان، بث |
| `service/` | خدمات النظام: CommandService, ScreenCaptureService, SMS/Call Receiver, BootReceiver, AccessibilityService |
| `streaming/` | بث مباشر: شاشة، كاميرا، صوت عبر WebRTC/MediaCodec |
| `api/` | تواصل مع السيرفر عبر OkHttp + Firebase Realtime Database |
| `database/` | Room Database للتخزين المحلي |
| `storage/` | إدارة الملفات: نسخ احتياطي، ضغط، أرشفة |
| `worker/` | مهام خلفية: فحص صحي، جدولة، إدارة سجلات |

### الميزات

- جمع البيانات: SMS، مكالمات، جهات اتصال، سجلات التطبيقات، مواقع GPS
- التحكم عن بعد: لقطة شاشة، كاميرا أمامية/خلفية، تسجيل صوتي
- إدارة التطبيقات: تثبيت، إلغاء تثبيت، فتح، إغلاق
- إدارة الملفات: رفع، تنزيل، حذف، إعادة تسمية، نسخ، نقل
- البث المباشر: شاشة، كاميرا، صوت بتكيفي bitrate
- الأمان: تشفير AES، قفل الجهاز، مسح البيانات
- المراقبة: keylogger، اعتراض الإشعارات، تتبع التطبيقات

---

## السيرفر (Python + لوحة تحكم ويب)

- **اللغة:** Python 3 / aiohttp
- **الحجم:** ~4300 سطر
- **لوحة التحكم:** ويب مدمجة (HTML/CSS/JS داخل server.py)

### الميزات

- API REST لربط التطبيقات بالسيرفر
- لوحة تحكم ويب مدمجة مع مصادقة
- دعم WebSocket للاتصال الحي
- إدارة الأجهمة المسجلة والأوامر
- بوت Telegram لإرسال الإشعارات
- ربط الأجهمة عبر رمز رابط (Link Code)
- نظام جلسات آمن

### المتطلبات

```bash
pip install -r requirements.txt
```

### التشغيل

```bash
cd Server
python3 server.py
```

---

## CI/CD

البناء التلقائي عبر GitHub Actions عند كل push:
- بناء APK Debug
- رفع الأartifact
- التحقق من صحة الكود

---

## معلومات النشر

- **السيرفر:** يعمل كخدمة systemd (`abuzahra.service`)
- **النطاق:** alsydyabwalzhra.online
- **المنفذ:** 8443 (HTTPS عبر reverse proxy)