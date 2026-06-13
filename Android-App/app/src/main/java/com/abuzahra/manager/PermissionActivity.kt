package com.abuzahra.manager

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.abuzahra.manager.permission.PermissionAdapter
import com.abuzahra.manager.permission.PermissionChecker
import com.abuzahra.manager.permission.PermissionItem
import com.abuzahra.manager.permission.PermissionType
import com.abuzahra.manager.streaming.ScreenStreamService
import com.google.android.material.button.MaterialButton

/**
 * PermissionActivity - Centralized permission management screen.
 * Displays all required permissions in a clean card-based list,
 * similar to AirDroid Kids permission UI.
 *
 * Features:
 * - Real-time permission status checking
 * - Individual permission activation with proper system flows
 * - Auto-refresh on resume (detects permission changes from settings)
 * - "Continue" button only enabled when all essential permissions are granted
 * - No re-requesting of already granted permissions
 * - Gradual permission flow (one at a time)
 */
class PermissionActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PermissionActivity"

        // Request codes
        private const val RC_RUNTIME_PERMS = 3001
        private const val RC_CAMERA = 3002
        private const val RC_MICROPHONE = 3003
        private const val RC_LOCATION = 3004
        private const val RC_NOTIFICATIONS = 3005
        private const val RC_CONTACTS = 3006
        private const val RC_STORAGE = 3007
        private const val RC_BACKGROUND_LOCATION = 3008
        private const val RC_MEDIA_PROJECTION = 3009

        // Extra: if true, pressing "Continue" goes to MainActivity; if false, just finishes
        const val EXTRA_NAVIGATE_TO_MAIN = "navigate_to_main"

        // Extra: skip non-essential on first launch
        const val EXTRA_FIRST_LAUNCH = "first_launch"
    }

    private lateinit var adapter: PermissionAdapter
    private var pendingPermissionItem: PermissionItem? = null
    private var navigateToMain = false
    private var isFirstLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission)

        navigateToMain = intent.getBooleanExtra(EXTRA_NAVIGATE_TO_MAIN, false)
        isFirstLaunch = intent.getBooleanExtra(EXTRA_FIRST_LAUNCH, false)

        // Setup RecyclerView
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerPermissions)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // Build permission items with real-time checks
        val items = PermissionChecker.checkAllPermissions(this)
        adapter = PermissionAdapter(items) { item -> onPermissionClicked(item) }
        recyclerView.adapter = adapter

        // Back button
        findViewById<LinearLayout>(R.id.btnBack).setOnClickListener {
            finish()
        }

        // Continue button
        val btnContinue = findViewById<MaterialButton>(R.id.btnContinue)
        btnContinue.setOnClickListener {
            onContinueClicked()
        }

        // Skip button
        findViewById<TextView>(R.id.btnSkip).setOnClickListener {
            finish()
        }

        // Update UI
        updateUI()

        Log.i(TAG, "PermissionActivity created. Navigate to main: $navigateToMain")
    }

    override fun onResume() {
        super.onResume()
        // Always refresh permissions when returning from settings
        // This ensures status is updated even if user manually toggled permissions
        refreshPermissions()
    }

    /**
     * Refresh all permission states from the system (real checks, not cached).
     * Updates the adapter and UI.
     */
    private fun refreshPermissions() {
        val updatedItems = PermissionChecker.checkAllPermissions(this)
        adapter.updateItems(updatedItems)
        updateUI()
        Log.d(TAG, "Permissions refreshed. Granted: ${PermissionChecker.getPermissionCount(this)}")
    }

    /**
     * Update progress bar, count text, and continue button state.
     */
    private fun updateUI() {
        val (granted, total) = PermissionChecker.getPermissionCount(this)

        // Update count text
        val countText = findViewById<TextView>(R.id.textPermCount)
        countText.text = "$granted/$total"

        // Color the count based on progress
        val colorRes = when {
            granted == total -> R.color.green
            granted > total / 2 -> R.color.yellow
            else -> R.color.red
        }
        countText.setTextColor(getColor(colorRes))

        // Update progress bar
        val progressBar = findViewById<View>(R.id.progressBar)
        val params = progressBar.layoutParams as LinearLayout.LayoutParams
        val progressFraction = if (total > 0) granted.toFloat() / total else 0f
        params.weight = progressFraction
        progressBar.layoutParams = params

        // Update continue button
        val btnContinue = findViewById<MaterialButton>(R.id.btnContinue)
        val allEssentialGranted = adapter.areAllEssentialGranted()
        btnContinue.isEnabled = allEssentialGranted

        if (allEssentialGranted) {
            btnContinue.backgroundTintList = getColorStateList(R.color.blue)
            btnContinue.setTextColor(getColor(android.R.color.white))
        } else {
            btnContinue.backgroundTintList = getColorStateList(R.color.surface2)
            btnContinue.setTextColor(getColor(R.color.text_secondary))
        }

        // Update info text
        val infoText = findViewById<TextView>(R.id.textInfo)
        val essentialGranted = adapter.getGrantedEssentialCount()
        val essentialTotal = adapter.getTotalEssentialCount()

        if (allEssentialGranted) {
            infoText.text = "تم تفعيل جميع الصلاحيات الأساسية بنجاح"
            infoText.setTextColor(getColor(R.color.green))
        } else {
            infoText.text = "يرجى تفعيل الصلاحيات الأساسية المتبقية ($essentialGranted/$essentialTotal) لضمان عمل التطبيق"
            infoText.setTextColor(getColor(R.color.text_secondary))
        }
    }

    /**
     * Handle clicking on a permission item.
     * Opens the appropriate system dialog or settings page.
     */
    private fun onPermissionClicked(item: PermissionItem) {
        // Don't request if already granted
        if (item.isGranted) return

        pendingPermissionItem = item

        when (item.type) {
            PermissionType.RUNTIME -> requestRuntimePermission(item)
            PermissionType.SPECIAL_SETTINGS -> openSettingsPage(item.settingsAction)
            PermissionType.SPECIAL_OVERLAY -> requestOverlayPermission()
            PermissionType.SPECIAL_BATTERY -> requestBatteryOptimization()
            PermissionType.MEDIA_PROJECTION -> requestMediaProjection()
            PermissionType.SPECIAL_INSTALL -> requestInstallPackages()
            PermissionType.SPECIAL_WRITE_SETTINGS -> requestWriteSettings()
            PermissionType.SPECIAL_ALL_FILES -> requestAllFilesAccess()
        }
    }

    // ========== Runtime Permission Requests ==========

    private fun requestRuntimePermission(item: PermissionItem) {
        if (item.permissions.isEmpty()) return

        // Filter out already-granted permissions
        val neededPerms = item.permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (neededPerms.isEmpty()) {
            // Already granted, just refresh
            refreshPermissions()
            return
        }

        // Determine request code
        val requestCode = when (item.id) {
            "camera" -> RC_CAMERA
            "microphone" -> RC_MICROPHONE
            "location" -> RC_LOCATION
            "notifications" -> RC_NOTIFICATIONS
            "contacts" -> RC_CONTACTS
            "storage" -> RC_STORAGE
            else -> RC_RUNTIME_PERMS
        }

        ActivityCompat.requestPermissions(this, neededPerms.toTypedArray(), requestCode)
    }

    // ========== Special Permission Requests ==========

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                Toast.makeText(this, "فعّل 'الظهور فوق التطبيقات الأخرى' ثم ارجع", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open overlay settings", e)
                Toast.makeText(this, "تعذر فتح الإعدادات", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open battery optimization", e)
                Toast.makeText(this, "تعذر فتح إعدادات البطارية", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestMediaProjection() {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = projectionManager.createScreenCaptureIntent()
            @Suppress("DEPRECATION")
            startActivityForResult(intent, RC_MEDIA_PROJECTION)
            Toast.makeText(this, "يرجى الموافقة على تسجيل الشاشة", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request MediaProjection", e)
            Toast.makeText(this, "تعذر طلب إذن تسجيل الشاشة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestInstallPackages() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
                Toast.makeText(this, "فعّل 'التثبيت من مصادر غير معروفة' ثم ارجع", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open install packages settings", e)
                Toast.makeText(this, "تعذر فتح الإعدادات", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestWriteSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
                Toast.makeText(this, "فعّل 'تعديل إعدادات النظام' ثم ارجع", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open write settings", e)
                Toast.makeText(this, "تعذر فتح الإعدادات", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (android.os.Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "تم تفعيل الوصول بالفعل", Toast.LENGTH_SHORT).show()
                    refreshPermissions()
                    return
                }
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
                Toast.makeText(this, "فعّل 'السماح بجميع الملفات' ثم ارجع", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open all files access settings", e)
                Toast.makeText(this, "تعذر فتح الإعدادات", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openSettingsPage(action: String?) {
        if (action == null) return
        try {
            val intent = Intent(action)
            // Some settings pages need the package URI
            if (action == Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS ||
                action == Settings.ACTION_USAGE_ACCESS_SETTINGS ||
                action == Settings.ACTION_ACCESSIBILITY_SETTINGS ||
                action == Settings.ACTION_SECURITY_SETTINGS) {
                // These don't need package URI, but we can try adding it
            }
            startActivity(intent)

            // Show helpful toast based on which setting
            val message = when (action) {
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS ->
                    "ابحث عن 'Abu-Zahra' وفعّله"
                Settings.ACTION_ACCESSIBILITY_SETTINGS ->
                    "ابحث عن 'Abu-Zahra' وفعّل خدمة إمكانية الوصول"
                Settings.ACTION_USAGE_ACCESS_SETTINGS ->
                    "ابحث عن 'Abu-Zahra' وفعّل 'الوصول إلى استخدام التطبيقات'"
                Settings.ACTION_SECURITY_SETTINGS ->
                    "ابحث عن 'Device Admin' وفعّل التطبيق كمسؤول جهاز"
                else -> "ارجع بعد تفعيل الصلاحية"
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open settings: $action", e)
            Toast.makeText(this, "تعذر فتح صفحة الإعدادات", Toast.LENGTH_SHORT).show()
        }
    }

    // ========== Result Handlers ==========

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                // Save MediaProjection permission for later use
                ScreenStreamService.setPermissionData(resultCode, data)
                Log.i(TAG, "MediaProjection permission granted and saved")
                Toast.makeText(this, "تم تفعيل تسجيل الشاشة", Toast.LENGTH_SHORT).show()
            } else {
                Log.w(TAG, "MediaProjection permission denied")
                Toast.makeText(this, "تم رفض إذن تسجيل الشاشة", Toast.LENGTH_SHORT).show()
            }
            // Refresh will happen in onResume
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // Handle background location as a special follow-up
        if (requestCode == RC_LOCATION) {
            val fineGranted = grantResults.indexOfFirst { i ->
                permissions[i] == Manifest.permission.ACCESS_FINE_LOCATION
            }.let { if (it >= 0) grantResults[it] == android.content.pm.PackageManager.PERMISSION_GRANTED else false }

            val coarseGranted = grantResults.indexOfFirst { i ->
                permissions[i] == Manifest.permission.ACCESS_COARSE_LOCATION
            }.let { if (it >= 0) grantResults[it] == android.content.pm.PackageManager.PERMISSION_GRANTED else false }

            if (fineGranted && coarseGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Check if we need to request background location separately
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    // Request background location with a brief delay
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            ActivityCompat.requestPermissions(
                                this,
                                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                                RC_BACKGROUND_LOCATION
                            )
                        }
                    }, 500)
                }
            }
        }

        // Mark permissions as asked (for permanent denial tracking)
        permissions.forEach { perm ->
            PermissionChecker.markPermissionAsked(this, perm)
        }

        // Refresh will happen in onResume via refreshPermissions()
    }

    // ========== Navigation ==========

    private fun onContinueClicked() {
        if (!adapter.areAllEssentialGranted()) {
            Toast.makeText(this, "يرجى تفعيل جميع الصلاحيات الأساسية أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        if (navigateToMain) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } else {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    override fun onBackPressed() {
        // Allow back navigation normally
        super.onBackPressed()
    }
}