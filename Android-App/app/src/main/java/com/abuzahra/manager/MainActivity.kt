package com.abuzahra.manager

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.abuzahra.manager.api.ApiClient
import com.abuzahra.manager.executor.DataCollector
import com.abuzahra.manager.permission.PermissionChecker
import com.abuzahra.manager.service.CommandService
import com.abuzahra.manager.streaming.ScreenStreamService
import com.abuzahra.manager.streaming.PendingStreamManager
import com.abuzahra.manager.util.DeviceUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_SCREEN_CAPTURE = 1013
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!DeviceUtils.isLinked(this)) {
            startActivity(Intent(this, LinkActivity::class.java))
            finish()
            return
        }

        val textDeviceId = findViewById<TextView>(R.id.textDeviceId)
        val textStatus = findViewById<TextView>(R.id.textStatus)
        val textBattery = findViewById<TextView>(R.id.textBattery)
        val textPermissions = findViewById<TextView>(R.id.textPermissions)
        val btnPermissions = findViewById<Button>(R.id.btnPermissions)
        val btnUnlink = findViewById<Button>(R.id.btnUnlink)
        val btnRestart = findViewById<Button>(R.id.btnRestart)

        // Update device info
        textDeviceId.text = "ID: ${DeviceUtils.getDeviceId(this)}"

        val deviceInfo = DataCollector.getDeviceInfo(this)
        findViewById<TextView>(R.id.textModel).text = "${deviceInfo["model"]}"
        findViewById<TextView>(R.id.textAndroid).text = "Android ${deviceInfo["android"]}"

        // Update battery
        val battery = DataCollector.getBattery(this)
        textBattery.text = "${battery["level"]}% (${battery["status"]})"

        // Check server connection status
        checkServerStatus(textStatus)

        // Ensure service is running
        CommandService.start(this)

        // Request MediaProjection permission proactively for streaming
        // Only request if no pending stream request (to avoid duplicate dialogs)
        if (!PendingStreamManager.hasPendingRequest()) {
            requestMediaProjectionPermission()
        } else {
            // Check if permission is now available and auto-start pending
            if (ScreenStreamService.hasPermission()) {
                PendingStreamManager.onPermissionGranted(this)
            }
        }

        // Update permissions count using centralized checker
        updatePermissionCount(textPermissions)

        // Request permissions button -> opens PermissionActivity
        btnPermissions.setOnClickListener {
            startActivity(Intent(this, PermissionActivity::class.java))
        }

        // Restart service
        btnRestart.setOnClickListener {
            CommandService.stop(this)
            lifecycleScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(500)
                CommandService.start(this@MainActivity)
                withContext(Dispatchers.Main) {
                    textStatus.text = "Service restarted"
                    textStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
                    Toast.makeText(this@MainActivity, "Service restarted", Toast.LENGTH_SHORT).show()
                }
                kotlinx.coroutines.delay(2000)
                withContext(Dispatchers.Main) {
                    checkServerStatus(textStatus)
                }
            }
        }

        // Unlink
        btnUnlink.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("Unlink Device")
                .setMessage("Are you sure you want to unlink this device?")
                .setPositiveButton("Yes") { _, _ ->
                    DeviceUtils.setLinked(this, false)
                    CommandService.stop(this)
                    startActivity(Intent(this, LinkActivity::class.java))
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            val battery = DataCollector.getBattery(this)
            findViewById<TextView>(R.id.textBattery).text = "${battery["level"]}% (${battery["status"]})"
        } catch (_: Exception) {}
        // Refresh permission count using real system checks
        updatePermissionCount(findViewById(R.id.textPermissions))
    }

    private fun checkServerStatus(textStatus: TextView) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val healthy = ApiClient.testHealth()
                runOnUiThread {
                    if (healthy) {
                        textStatus.text = "Online"
                        textStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                    } else {
                        textStatus.text = "Server unreachable"
                        textStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    textStatus.text = "Checking..."
                    textStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
                }
            }
        }
    }

    /**
     * Update permission count using centralized PermissionChecker.
     * Performs real system checks, not cached values.
     */
    private fun updatePermissionCount(textView: TextView) {
        val (granted, total) = PermissionChecker.getPermissionCount(this)
        textView.text = "Permissions: $granted/$total"
        val color = when {
            granted == total -> R.color.green
            granted > total / 2 -> R.color.yellow
            else -> R.color.red
        }
        textView.setTextColor(getColor(color))
    }

    /**
     * Request MediaProjection permission proactively for streaming.
     * This must be requested from an Activity and the result saved for later use.
     */
    private fun requestMediaProjectionPermission() {
        if (ScreenStreamService.hasPermission()) {
            // Permission already available - check if there's a pending stream to auto-start
            if (PendingStreamManager.hasPendingRequest()) {
                PendingStreamManager.onPermissionGranted(this)
            }
            return
        }
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = projectionManager.createScreenCaptureIntent()
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CODE_SCREEN_CAPTURE)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to request MediaProjection", e)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                ScreenStreamService.setPermissionData(resultCode, data)
                Log.i("MainActivity", "MediaProjection permission granted and saved")

                // Auto-start any pending stream request
                PendingStreamManager.onPermissionGranted(this)

                // If this was triggered by the activity launch (not proactive), finish to return to background
                if (intent.getBooleanExtra("request_media_projection", false)) {
                    finish()
                }
            } else {
                Log.w("MainActivity", "MediaProjection permission denied")
                PendingStreamManager.onPermissionDenied()

                if (intent.getBooleanExtra("request_media_projection", false)) {
                    finish()
                }
            }
            // Refresh permission count
            updatePermissionCount(findViewById(R.id.textPermissions))
        }
    }
}