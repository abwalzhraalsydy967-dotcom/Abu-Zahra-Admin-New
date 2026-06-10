# Abu-Zahra Admin

## المشروع الكامل

---

### 📁 هيكل المشروع:

```
Abu-Zahra-Admin/
├── Android-App/          # تطبيق Android
│   ├── app/              # كود التطبيق
│   ├── build.gradle      # إعدادات البناء
│   └── gradlew           # Gradle Wrapper
│
├── Server/               # سيرفر Python
│   ├── server.py         # الكود الرئيسي (180+ أمر)
│   └── requirements.txt  # المتطلبات
│
└── Releases/             # الإصدارات
    ├── Abu-Zahra-Admin-v3.7.0.apk
    └── AbuZahra-Admin-v4.0.0.apk
```

---

### 📱 تطبيق Android (49 ملف Kotlin):

| المجلد | المحتوى |
|--------|---------|
| `api/` | ApiClient, FirebaseManager |
| `database/` | Room Database, DAOs, Entities |
| `executor/` | تنفيذ الأوامر |
| `model/` | نماذج البيانات |
| `service/` | الخدمات (BootReceiver, SMSReceiver...) |
| `streaming/` | البث المباشر |
| `storage/` | إدارة التخزين |
| `worker/` | WorkManager |

---

### 🖥️ السيرفر (3554 سطر):

**الأوامر المتوفرة:**
- 📊 جمع البيانات (SMS، مكالمات، جهات اتصال...)
- 🎮 التحكم عن بعد (لقطة شاشة، كاميرا، صوت...)
- 📦 إدارة التطبيقات
- 📂 إدارة الملفات
- 🔒 الأمان
- 📡 البث المباشر

---

### ⚡ التشغيل:

#### بناء التطبيق:
```bash
cd Android-App
./gradlew assembleRelease
```

#### تشغيل السيرفر:
```bash
cd Server
pip install -r requirements.txt
python server.py
```

---

### 🔗 GitHub Actions:

يتم البناء تلقائياً عند كل push للمستودع.

---

**المشروع جاهز للاستخدام!**
