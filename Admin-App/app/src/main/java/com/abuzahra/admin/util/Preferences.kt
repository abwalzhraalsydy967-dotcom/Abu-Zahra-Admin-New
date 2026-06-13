package com.abuzahra.admin.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.abuzahra.admin.data.api.ApiClient

class Preferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "abu_zahra_admin_secure_prefs"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEFAULT_SERVER = "https://alsydyabwalzhra.online/"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        private const val KEY_ONLINE_NOTIF = "notif_online"
        private const val KEY_OFFLINE_NOTIF = "notif_offline"
        private const val KEY_EVENT_NOTIF = "notif_events"
        private const val KEY_DARK_MODE = "dark_mode"

        @Volatile
        private var instance: Preferences? = null

        fun getInstance(context: Context): Preferences {
            return instance ?: synchronized(this) {
                instance ?: Preferences(context.applicationContext).also { instance = it }
            }
        }
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, KEY_DEFAULT_SERVER) ?: KEY_DEFAULT_SERVER
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var isLoggedIn: Boolean
        get() = !token.isNullOrEmpty()
        set(_) {} // computed from token

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, value).apply()

    var onlineNotifications: Boolean
        get() = prefs.getBoolean(KEY_ONLINE_NOTIF, true)
        set(value) = prefs.edit().putBoolean(KEY_ONLINE_NOTIF, value).apply()

    var offlineNotifications: Boolean
        get() = prefs.getBoolean(KEY_OFFLINE_NOTIF, true)
        set(value) = prefs.edit().putBoolean(KEY_OFFLINE_NOTIF, value).apply()

    var eventNotifications: Boolean
        get() = prefs.getBoolean(KEY_EVENT_NOTIF, true)
        set(value) = prefs.edit().putBoolean(KEY_EVENT_NOTIF, value).apply()

    var darkMode: Boolean
        get() = prefs.getBoolean(KEY_DARK_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DARK_MODE, value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun getApiService(): com.abuzahra.admin.data.api.ApiService {
        return ApiClient.createWithToken(serverUrl, token ?: "")
    }

    fun getApiServiceWithoutToken(): com.abuzahra.admin.data.api.ApiService {
        return ApiClient.create(serverUrl)
    }
}