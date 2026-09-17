package com.myra.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AccessibilityHelperService : AccessibilityService() {

    companion object {
        private const val TAG = "AccessibilityService"
        var instance: AccessibilityHelperService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val expectedComponentName = ComponentName(context, AccessibilityHelperService::class.java)
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)

            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                if (enabledComponent != null && enabledComponent == expectedComponentName) {
                    return true
                }
            }
            return false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AccessibilityHelperService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Can be used for context awareness
    }

    override fun onInterrupt() {
        Log.d(TAG, "AccessibilityHelperService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "AccessibilityHelperService destroyed")
    }

    fun closeCurrentApp(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun goBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun clickOnText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) {
                    val clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) return true
                }
                current = current.parent
            }
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return false
    }

    fun scrollDown(): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    fun scrollUp(): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }
}
