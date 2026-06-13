package com.abuzahra.admin.ui.settings

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.abuzahra.admin.R
import com.abuzahra.admin.databinding.ActivitySettingsBinding
import com.abuzahra.admin.ui.login.LoginActivity
import com.abuzahra.admin.util.Preferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferences: Preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = Preferences.getInstance(this)

        setupToolbar()
        loadSettings()
        setupListeners()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun loadSettings() {
        binding.etServerUrl.setText(preferences.serverUrl)
        binding.switchNotifications.isChecked = preferences.notificationsEnabled
        binding.switchOnlineNotif.isChecked = preferences.onlineNotifications
        binding.switchOfflineNotif.isChecked = preferences.offlineNotifications
        binding.switchEventNotif.isChecked = preferences.eventNotifications
        binding.switchDarkMode.isChecked = preferences.darkMode

        // Make server URL read-only (display only) — it's set at login
        binding.etServerUrl.isEnabled = false
        binding.etServerUrl.alpha = 0.6f
    }

    private fun setupListeners() {
        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            preferences.notificationsEnabled = isChecked
            updateNotificationSwitches(isChecked)
        }

        binding.switchOnlineNotif.setOnCheckedChangeListener { _, isChecked ->
            preferences.onlineNotifications = isChecked
        }

        binding.switchOfflineNotif.setOnCheckedChangeListener { _, isChecked ->
            preferences.offlineNotifications = isChecked
        }

        binding.switchEventNotif.setOnCheckedChangeListener { _, isChecked ->
            preferences.eventNotifications = isChecked
        }

        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            preferences.darkMode = isChecked
        }

        binding.btnLogout.setOnClickListener {
            showLogoutDialog()
        }
    }

    private fun updateNotificationSwitches(enabled: Boolean) {
        binding.switchOnlineNotif.isEnabled = enabled
        binding.switchOfflineNotif.isEnabled = enabled
        binding.switchEventNotif.isEnabled = enabled
    }

    private fun showLogoutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logout)
            .setMessage(R.string.logout_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                preferences.clear()
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
