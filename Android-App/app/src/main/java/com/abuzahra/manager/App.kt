package com.abuzahra.manager

import android.app.Application
import android.util.Log
import com.abuzahra.manager.streaming.StreamManager
import com.abuzahra.manager.worker.WorkScheduler
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase

class App : Application() {
    var startTime: Long = 0L
        private set

    override fun onCreate() {
        super.onCreate()
        startTime = System.currentTimeMillis()
        instance = this

        // Initialize Firebase
        try {
            FirebaseApp.initializeApp(this)
            Log.i("App", "Firebase initialized successfully")
        } catch (e: Exception) {
            Log.e("App", "Firebase initialization failed", e)
        }

        // Load saved config from SharedPreferences
        val prefs = getSharedPreferences("abuzahra", MODE_PRIVATE)
        val savedDomain = prefs.getString("server_domain", null)
        val savedPort = prefs.getInt("server_port", 0)

        if (!savedDomain.isNullOrBlank()) {
            Config.SERVER_DOMAIN = savedDomain
            if (savedPort > 0) {
                Config.SERVER_PORT = savedPort
            } else {
                Config.SERVER_PORT = if (savedDomain.startsWith("https://")) 443 else 80
            }
            Log.i("App", "Loaded saved server config: ${Config.SERVER_DOMAIN}:${Config.SERVER_PORT}")
        }

        // Initialize StreamManager with application context
        try {
            StreamManager.init(this)
            Log.i("App", "StreamManager initialized")
        } catch (e: Exception) {
            Log.e("App", "StreamManager init failed", e)
        }

        // Initialize EventBuffer (events stored locally, not auto-sent)
        try {
            EventBuffer.init(this)
            Log.i("App", "EventBuffer initialized")
        } catch (e: Exception) {
            Log.e("App", "EventBuffer init failed", e)
        }

        // Schedule periodic workers (health check, sync, cleanup)
        try {
            WorkScheduler.scheduleAll(this)
            Log.i("App", "WorkScheduler initialized")
        } catch (e: Exception) {
            Log.e("App", "WorkScheduler init failed", e)
        }

        // Ensure server URL is up-to-date
        if (savedDomain.isNullOrBlank()) {
            Log.i("App", "Using default server: ${Config.SERVER_DOMAIN}")
        }
    }

    companion object {
        lateinit var instance: App
            private set
        const val APP_VERSION = "3.5.0"
        const val ADGUARD_DNS_SERVER = "https://dns.adguard.com/dns-query"
    }
}
