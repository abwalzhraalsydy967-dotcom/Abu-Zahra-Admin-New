package com.abuzahra.manager

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Firebase
        try {
            FirebaseApp.initializeApp(this)
            // Note: setPersistenceEnabled should only be called once globally
            // It's handled in FirebaseManager, so we don't call it here
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

        // Ensure server URL is up-to-date
        // If no saved config, use the default (which is now the correct HTTPS URL)
        if (savedDomain.isNullOrBlank()) {
            Log.i("App", "Using default server: ${Config.SERVER_DOMAIN}")
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
