package com.abuzahra.manager.permission

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.abuzahra.manager.service.DeviceAdminReceiver
import com.abuzahra.manager.service.MyAccessibilityService
import com.abuzahra.manager.service.MyNotificationListenerService

/**
 * PermissionChecker - Centralized utility for checking all app permissions.
 * Performs REAL system checks (not cached/local state) every time.
 */
object PermissionChecker {

    private const val TAG = "PermissionChecker"

    // ========== Runtime Permission Checks ==========

    fun isCameraGranted(context: Context): Boolean {
        return hasPermission(context, android.Manifest.permission.CAMERA)
    }

    fun isMicrophoneGranted(context: Context): Boolean {
        return hasPermission(context, android.Manifest.permission.RECORD_AUDIO)
    }

    fun isLocationGranted(context: Context): Boolean {
        return hasPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) &&
               hasPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    fun isBackgroundLocationGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return hasPermission(context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    fun isNotificationsGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true // Not a runtime permission before Android 13
        }
    }

    fun isContactsGranted(context: Context): Boolean {
        return hasPermission(context, android.Manifest.permission.READ_CONTACTS)
    }

    fun isCallLogGranted(context: Context): Boolean {
        return hasPermission(context, android.Manifest.permission.READ_CALL_LOG)
    }

    fun isSmsGranted(context: Context): Boolean {
        return hasPermission(context, android.Manifest.permission.READ_SMS) &&
               hasPermission(context, android.Manifest.permission.RECEIVE_SMS)
    }

    fun isPhoneStateGranted(context: Context): Boolean {
        return hasPermission(context, android.Manifest.permission.READ_PHONE_STATE)
    }

    fun isStorageGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) &&
            hasPermission(context, android.Manifest.permission.READ_MEDIA_VIDEO) &&
            hasPermission(context, android.Manifest.permission.READ_MEDIA_AUDIO)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hasPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) &&
            android.os.Environment.isExternalStorageManager()
        } else {
            hasPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) &&
            hasPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    fun isCalendarGranted(context: Context): Boolean {
        return hasPermission(context, android.Manifest.permission.READ_CALENDAR)
    }

    fun isBodySensorsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT_WATCH) return true
        return hasPermission(context, android.Manifest.permission.BODY_SENSORS)
    }

    fun isActivityRecognitionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return hasPermission(context, android.Manifest.permission.ACTIVITY_RECOGNITION)
    }

    fun isNearbyDevicesGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return hasPermission(context, android.Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    fun isAllFilesAccessGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return android.os.Environment.isExternalStorageManager()
    }

    fun isPhoneStateGranted(context: Context): Boolean {
        if (hasPermission(context, android.Manifest.permission.READ_PHONE_STATE)) {
            // On Android 10+, READ_PHONE_STATE implies READ_CALL_LOG
            return true
        }
        return false
    }

    fun isCallLogGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return hasPermission(context, android.Manifest.permission.READ_CALL_LOG)
        }
        // Before Android 10, READ_CALL_LOG is granted automatically with READ_PHONE_STATE
        return hasPermission(context, android.Manifest.permission.READ_PHONE_STATE)
    }

    fun isSmsGranted(context: Context): Boolean {
        return hasPermission(context, android.Manifest.permission.READ_SMS) &&
               hasPermission(context, android.Manifest.permission.RECEIVE_SMS)
    }

    // ========== Special Permission Checks ==========

    fun isOverlayPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return Settings.canDrawOverlays(context)
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return MyAccessibilityService.isEnabled(context)
    }

    fun isUsageStatsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return true
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOp(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.e(TAG, "Error checking usage stats permission", e)
            false
        }
    }

    fun isNotificationAccessGranted(context: Context): Boolean {
        return MyNotificationListenerService.isEnabled(context)
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking battery optimization", e)
            false
        }
    }

    fun isDeviceAdminGranted(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isAdminActive(ComponentName(context, DeviceAdminReceiver::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Error checking device admin", e)
            false
        }
    }

    fun isMediaProjectionGranted(context: Context): Boolean {
        return com.abuzahra.manager.streaming.ScreenStreamService.hasPermission()
    }

    fun isWriteSettingsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return Settings.System.canWrite(context)
    }

    fun isInstallPackagesGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    // ========== Aggregate Checks ==========

    /**
     * Check all permissions and return a list of PermissionItem with current states.
     * This performs REAL system checks, not cached values.
     */
    fun checkAllPermissions(context: Context): List<PermissionItem> {
        val items = mutableListOf<PermissionItem>()

        // Runtime Permissions Group
        items.add(PermissionItem(
            id = "camera",
            title = "الكاميرا",
            description = "السماح بالتقاط الصور وتسجيل الفيديو والبث المباشر",
            iconRes = "camera",
            iconColor = "#4285F4",
            type = PermissionType.RUNTIME,
            permissions = listOf(android.Manifest.permission.CAMERA),
            isGranted = isCameraGranted(context),
            isEssential = true
        ))

        items.add(PermissionItem(
            id = "microphone",
            title = "الميكروفون",
            description = "السماح بتسجيل الصوت والبث الصوتي المباشر",
            iconRes = "microphone",
            iconColor = "#EA4335",
            type = PermissionType.RUNTIME,
            permissions = listOf(android.Manifest.permission.RECORD_AUDIO),
            isGranted = isMicrophoneGranted(context),
            isEssential = true
        ))

        items.add(PermissionItem(
            id = "location",
            title = "الموقع الجغرافي",
            description = "السماح بتتبع الموقع الحالي وفي الخلفية",
            iconRes = "location",
            iconColor = "#34A853",
            type = PermissionType.RUNTIME,
            permissions = listOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            isGranted = isLocationGranted(context),
            isEssential = true,
            subItems = listOfNotNull(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    SubPermission("background_location", "الموقع في الخلفية", isBackgroundLocationGranted(context))
                else null
            )
        ))

        items.add(PermissionItem(
            id = "notifications",
            title = "الإشعارات",
            description = "السماح بإرسال إشعارات التطبيق",
            iconRes = "notifications",
            iconColor = "#FF9800",
            type = PermissionType.RUNTIME,
            permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                listOf(android.Manifest.permission.POST_NOTIFICATIONS) else emptyList(),
            isGranted = isNotificationsGranted(context),
            isEssential = true
        ))

        items.add(PermissionItem(
            id = "contacts",
            title = "جهات الاتصال",
            description = "السماح بقراءة قائمة جهات الاتصال",
            iconRes = "contacts",
            iconColor = "#2196F3",
            type = PermissionType.RUNTIME,
            permissions = listOf(android.Manifest.permission.READ_CONTACTS),
            isGranted = isContactsGranted(context),
            isEssential = false
        ))

        items.add(PermissionItem(
            id = "storage",
            title = "التخزين والملفات",
            description = "السماح بالوصول إلى الصور والفيديوهات والملفات",
            iconRes = "storage",
            iconColor = "#9C27B0",
            type = PermissionType.RUNTIME,
            permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO,
                    android.Manifest.permission.READ_MEDIA_AUDIO
                )
            } else {
                listOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            },
            isGranted = isStorageGranted(context),
            isEssential = false
        ))

        // Special Permissions Group
        items.add(PermissionItem(
            id = "notification_access",
            title = "الوصول للإشعارات",
            description = "قراءة محتوى الإشعارات من جميع التطبيقات",
            iconRes = "notification_access",
            iconColor = "#FF5722",
            type = PermissionType.SPECIAL_SETTINGS,
            permissions = emptyList(),
            isGranted = isNotificationAccessGranted(context),
            isEssential = true,
            settingsAction = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
        ))

        items.add(PermissionItem(
            id = "accessibility",
            title = "إمكانية الوصول",
            description = "قراءة محتوى الشاشة وأتمتة العمليات",
            iconRes = "accessibility",
            iconColor = "#00BCD4",
            type = PermissionType.SPECIAL_SETTINGS,
            permissions = emptyList(),
            isGranted = isAccessibilityServiceEnabled(context),
            isEssential = true,
            settingsAction = Settings.ACTION_ACCESSIBILITY_SETTINGS
        ))

        items.add(PermissionItem(
            id = "overlay",
            title = "الظهور فوق التطبيقات",
            description = "عرض نوافذ فوق التطبيقات الأخرى",
            iconRes = "overlay",
            iconColor = "#673AB7",
            type = PermissionType.SPECIAL_OVERLAY,
            permissions = emptyList(),
            isGranted = isOverlayPermissionGranted(context),
            isEssential = true
        ))

        items.add(PermissionItem(
            id = "battery_optimization",
            title = "تجاهل تحسين البطارية",
            description = "منع النظام من إيقاف التطبيق في الخلفية",
            iconRes = "battery",
            iconColor = "#4CAF50",
            type = PermissionType.SPECIAL_BATTERY,
            permissions = emptyList(),
            isGranted = isBatteryOptimizationIgnored(context),
            isEssential = true
        ))

        items.add(PermissionItem(
            id = "usage_stats",
            title = "الوصول لاستخدام التطبيقات",
            description = "عرض وقت استخدام التطبيقات والإحصائيات",
            iconRes = "usage_stats",
            iconColor = "#FF9800",
            type = PermissionType.SPECIAL_SETTINGS,
            permissions = emptyList(),
            isGranted = isUsageStatsGranted(context),
            isEssential = false,
            settingsAction = Settings.ACTION_USAGE_ACCESS_SETTINGS
        ))

        items.add(PermissionItem(
            id = "device_admin",
            title = "مسؤول الجهاز",
            description = "تحكم كامل بالجهاز وقفل الشاشة عن بُعد",
            iconRes = "device_admin",
            iconColor = "#F44336",
            type = PermissionType.SPECIAL_SETTINGS,
            permissions = emptyList(),
            isGranted = isDeviceAdminGranted(context),
            isEssential = false,
            settingsAction = Settings.ACTION_SECURITY_SETTINGS
        ))

        items.add(PermissionItem(
            id = "media_projection",
            title = "تسجيل الشاشة",
            description = "السماح بالتقاط الشاشة والبث المباشر",
            iconRes = "screen_capture",
            iconColor = "#E91E63",
            type = PermissionType.MEDIA_PROJECTION,
            permissions = emptyList(),
            isGranted = isMediaProjectionGranted(context),
            isEssential = true
        ))

        items.add(PermissionItem(
            id = "install_packages",
            title = "تثبيت التطبيقات",
            description = "السماح بتثبيت تطبيقات من مصادر خارجية",
            iconRes = "install",
            iconColor = "#795548",
            type = PermissionType.SPECIAL_INSTALL,
            permissions = emptyList(),
            isGranted = isInstallPackagesGranted(context),
            isEssential = false
        ))

        items.add(PermissionItem(
            id = "write_settings",
            title = "تعديل إعدادات النظام",
            description = "تعديل إعدادات النظام مثل السطوع والصوت",
            iconRes = "settings",
            iconColor = "#607D8B",
            type = PermissionType.SPECIAL_WRITE_SETTINGS,
            permissions = emptyList(),
            isGranted = isWriteSettingsGranted(context),
            isEssential = false
        ))

        // ========== Additional Runtime Permissions ==========

        items.add(PermissionItem(
            id = "phone_state",
            title = "الهاتف وسجل المكالمات",
            description = "السماح بقراءة حالة الهاتف وسجل المكالمات الواردة والصادرة",
            iconRes = "phone",
            iconColor = "#009688",
            type = PermissionType.RUNTIME,
            permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                listOf(
                    android.Manifest.permission.READ_PHONE_STATE,
                    android.Manifest.permission.READ_CALL_LOG
                )
            } else {
                listOf(android.Manifest.permission.READ_PHONE_STATE)
            },
            isGranted = isPhoneStateGranted(context) && isCallLogGranted(context),
            isEssential = true
        ))

        items.add(PermissionItem(
            id = "sms_permissions",
            title = "الرسائل القصيرة SMS",
            description = "السماح بقراءة واستقبال الرسائل القصيرة",
            iconRes = "sms",
            iconColor = "#4CAF50",
            type = PermissionType.RUNTIME,
            permissions = listOf(
                android.Manifest.permission.READ_SMS,
                android.Manifest.permission.RECEIVE_SMS
            ),
            isGranted = isSmsGranted(context),
            isEssential = true
        ))

        items.add(PermissionItem(
            id = "calendar",
            title = "التقويم",
            description = "السماح بقراءة أحداث التقويم والمواعيد",
            iconRes = "calendar",
            iconColor = "#2196F3",
            type = PermissionType.RUNTIME,
            permissions = listOf(android.Manifest.permission.READ_CALENDAR),
            isGranted = isCalendarGranted(context),
            isEssential = false
        ))

        items.add(PermissionItem(
            id = "body_sensors",
            title = "أجهزة استشعار الجسم",
            description = "السماح بالوصول إلى مستشعرات الجسم مثل معدل ضربات القلب",
            iconRes = "sensors",
            iconColor = "#F44336",
            type = PermissionType.RUNTIME,
            permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH)
                listOf(android.Manifest.permission.BODY_SENSORS) else emptyList(),
            isGranted = isBodySensorsGranted(context),
            isEssential = false
        ))

        items.add(PermissionItem(
            id = "activity_recognition",
            title = "النشاط البدني",
            description = "السماح بتعرف على النشاط البدني مثل المشي والجري",
            iconRes = "activity",
            iconColor = "#FF9800",
            type = PermissionType.RUNTIME,
            permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                listOf(android.Manifest.permission.ACTIVITY_RECOGNITION) else emptyList(),
            isGranted = isActivityRecognitionGranted(context),
            isEssential = false
        ))

        items.add(PermissionItem(
            id = "nearby_devices",
            title = "الأجهزة المجاورة",
            description = "السماح بالبحث عن الأجهزة المجاورة عبر البلوتوث وواي فاي",
            iconRes = "nearby",
            iconColor = "#9C27B0",
            type = PermissionType.RUNTIME,
            permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                listOf(android.Manifest.permission.NEARBY_WIFI_DEVICES) else emptyList(),
            isGranted = isNearbyDevicesGranted(context),
            isEssential = false
        ))

        items.add(PermissionItem(
            id = "all_files_access",
            title = "الوصول إلى كل الملفات",
            description = "السماح بالوصول الكامل إلى جميع الملفات على الجهاز",
            iconRes = "all_files",
            iconColor = "#795548",
            type = PermissionType.SPECIAL_ALL_FILES,
            permissions = emptyList(),
            isGranted = isAllFilesAccessGranted(context),
            isEssential = false
        ))

        return items
    }

    /**
     * Count granted permissions out of total
     */
    fun getPermissionCount(context: Context): Pair<Int, Int> {
        val items = checkAllPermissions(context)
        val granted = items.count { it.isGranted }
        return Pair(granted, items.size)
    }

    /**
     * Check if all essential permissions are granted
     */
    fun areEssentialPermissionsGranted(context: Context): Boolean {
        return checkAllPermissions(context).filter { it.isEssential }.all { it.isGranted }
    }

    /**
     * Check a single permission item by ID
     */
    fun checkSinglePermission(context: Context, itemId: String): Boolean {
        return when (itemId) {
            "camera" -> isCameraGranted(context)
            "microphone" -> isMicrophoneGranted(context)
            "location" -> isLocationGranted(context)
            "notifications" -> isNotificationsGranted(context)
            "contacts" -> isContactsGranted(context)
            "storage" -> isStorageGranted(context)
            "notification_access" -> isNotificationAccessGranted(context)
            "accessibility" -> isAccessibilityServiceEnabled(context)
            "overlay" -> isOverlayPermissionGranted(context)
            "battery_optimization" -> isBatteryOptimizationIgnored(context)
            "usage_stats" -> isUsageStatsGranted(context)
            "device_admin" -> isDeviceAdminGranted(context)
            "media_projection" -> isMediaProjectionGranted(context)
            "install_packages" -> isInstallPackagesGranted(context)
            "write_settings" -> isWriteSettingsGranted(context)
            "phone_state" -> isPhoneStateGranted(context)
            "sms_permissions" -> isSmsGranted(context)
            "calendar" -> isCalendarGranted(context)
            "body_sensors" -> isBodySensorsGranted(context)
            "activity_recognition" -> isActivityRecognitionGranted(context)
            "nearby_devices" -> isNearbyDevicesGranted(context)
            "all_files_access" -> isAllFilesAccessGranted(context)
            else -> false
        }
    }

    // ========== Helpers ==========

    private fun hasPermission(context: Context, permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if a runtime permission should show rationale (was denied before)
     */
    fun shouldShowRationale(context: Context, permission: String): Boolean {
        val activity = context as? android.app.Activity ?: return false
        return androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    /**
     * Check if a permission was permanently denied
     */
    fun isPermanentlyDenied(context: Context, permission: String): Boolean {
        return !hasPermission(context, permission) &&
               !shouldShowRationale(context, permission) &&
               !isFirstTimeAsking(context, permission)
    }

    private fun isFirstTimeAsking(context: Context, permission: String): Boolean {
        val prefs = context.getSharedPreferences("permission_tracking", Context.MODE_PRIVATE)
        return !prefs.getBoolean("asked_$permission", false)
    }

    fun markPermissionAsked(context: Context, permission: String) {
        val prefs = context.getSharedPreferences("permission_tracking", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("asked_$permission", true).apply()
    }
}

/**
 * Permission type enum for handling different request methods
 */
enum class PermissionType {
    RUNTIME,                    // Standard runtime permission dialog
    SPECIAL_SETTINGS,           // Redirect to specific Settings page
    SPECIAL_OVERLAY,            // Overlay permission (specific intent)
    SPECIAL_BATTERY,            // Battery optimization (specific intent)
    MEDIA_PROJECTION,           // MediaProjection (activity result)
    SPECIAL_INSTALL,            // Install unknown apps
    SPECIAL_WRITE_SETTINGS,      // Write system settings
    SPECIAL_ALL_FILES           // All files access (MANAGE_EXTERNAL_STORAGE)
}

/**
 * Data class representing a single permission item in the list
 */
data class PermissionItem(
    val id: String,
    val title: String,
    val description: String,
    val iconRes: String,
    val iconColor: String,
    val type: PermissionType,
    val permissions: List<String>,   // Runtime permissions to request
    var isGranted: Boolean,
    val isEssential: Boolean,
    val settingsAction: String? = null,  // For special permissions
    val subItems: List<SubPermission> = emptyList()
)

data class SubPermission(
    val id: String,
    val title: String,
    var isGranted: Boolean
)