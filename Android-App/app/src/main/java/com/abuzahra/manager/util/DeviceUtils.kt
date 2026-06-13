package com.abuzahra.manager.util

import android.content.Context
import android.provider.Settings
import com.abuzahra.manager.Config
import java.security.MessageDigest
import java.util.UUID

object DeviceUtils {

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences("abuzahra", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        if (deviceId == null) {
            deviceId = generateDeviceId(context)
            prefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
    }

    private fun generateDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        )
        // ANDROID_ID is stable per app-signing-key per device, making it deterministic.
        // We hash it (without timestamp) so the ID remains the same across app restarts.
        val raw = "abuzahra_${androidId}"
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun getDeviceToken(context: Context): String {
        val prefs = context.getSharedPreferences("abuzahra", Context.MODE_PRIVATE)
        var token = prefs.getString("device_token", null)
        if (token == null) {
            token = UUID.randomUUID().toString().replace("-", "").take(32)
            prefs.edit().putString("device_token", token).apply()
        }
        return token
    }

    fun isLinked(context: Context): Boolean {
        val prefs = context.getSharedPreferences("abuzahra", Context.MODE_PRIVATE)
        return prefs.getBoolean("is_linked", false)
    }

    fun setLinked(context: Context, linked: Boolean) {
        context.getSharedPreferences("abuzahra", Context.MODE_PRIVATE)
            .edit().putBoolean("is_linked", linked).apply()
    }

    fun saveServerInfo(context: Context, domain: String, port: Int) {
        context.getSharedPreferences("abuzahra", Context.MODE_PRIVATE)
            .edit()
            .putString("server_domain", domain)
            .putInt("server_port", port)
            .apply()
        Config.SERVER_DOMAIN = domain
        Config.SERVER_PORT = port
    }
}
