package com.nemo.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

class NemoAccessibilityService : AccessibilityService() {

    companion object {
        var instance: NemoAccessibilityService? = null
        const val TAG = "NemoAccessibility"
    }

    override fun onServiceConnected() {
        instance = this
        Log.d(TAG, "Nemo Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "Nothing on screen"
        val builder = StringBuilder()
        collectText(root, builder)
        return builder.toString().trim()
    }

    private fun collectText(node: AccessibilityNodeInfo?, builder: StringBuilder) {
        node ?: return
        if (!node.text.isNullOrBlank()) builder.appendLine(node.text.toString())
        for (i in 0 until node.childCount) collectText(node.getChild(i), builder)
    }

    fun tapAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun tapByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return findAndTapText(root, text)
    }

    private fun findAndTapText(node: AccessibilityNodeInfo?, text: String): Boolean {
        node ?: return false
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            tapAt(rect.exactCenterX(), rect.exactCenterY())
            return true
        }
        for (i in 0 until node.childCount) {
            if (findAndTapText(node.getChild(i), text)) return true
        }
        return false
    }

    fun scrollDown() {
        val path = Path().apply {
            moveTo(540f, 1400f)
            lineTo(540f, 600f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
        
        fun goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun showRecents() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    fun pullDownNotifications() {
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }
    }
}
