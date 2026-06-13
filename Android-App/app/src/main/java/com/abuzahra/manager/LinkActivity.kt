package com.abuzahra.manager

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.abuzahra.manager.api.ApiClient
import com.abuzahra.manager.permission.PermissionChecker
import com.abuzahra.manager.service.CommandService
import com.abuzahra.manager.util.DeviceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LinkActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LinkActivity"
    }

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

                        // Start foreground service
                        CommandService.start(this@LinkActivity)

                        // Navigate to PermissionActivity to set up all permissions
                        delay(1000)
                        val permIntent = Intent(this@LinkActivity, PermissionActivity::class.java)
                        permIntent.putExtra(PermissionActivity.EXTRA_NAVIGATE_TO_MAIN, true)
                        permIntent.putExtra(PermissionActivity.EXTRA_FIRST_LAUNCH, true)
                        startActivity(permIntent)
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

        // Replace old "Grant All Permissions" button with navigation to PermissionActivity
        val parentLayout = findViewById<android.widget.LinearLayout>(R.id.linkRootLayout)
        if (parentLayout != null) {
            val btnPerms = Button(this).apply {
                text = "الصلاحيات"
                textSize = 13f
                setPadding(16, 12, 16, 12)
                setBackgroundColor(0xFF1a1a2e.toInt())
                setTextColor(0xFF60a5fa.toInt())
                setOnClickListener {
                    startActivity(Intent(this@LinkActivity, PermissionActivity::class.java))
                }
            }
            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = 24
            btnPerms.layoutParams = params

            // Add before the status text
            val statusIndex = findChildIndex(textStatus, parentLayout)
            if (statusIndex >= 0) {
                parentLayout.addView(btnPerms, statusIndex)
            } else {
                parentLayout.addView(btnPerms)
            }
        }
    }

    private fun findChildIndex(view: TextView, parent: android.widget.LinearLayout): Int {
        for (i in 0 until parent.childCount) {
            if (parent.getChildAt(i) === view) return i
        }
        return -1
    }
}