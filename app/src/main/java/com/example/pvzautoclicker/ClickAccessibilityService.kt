package com.example.pvzautoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class ClickAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { report("无障碍服务收到中断") }
    override fun onServiceConnected() { instance = this; report("无障碍服务已连接") }
    override fun onDestroy() { if (instance === this) instance = null; report("无障碍服务已断开"); super.onDestroy() }

    fun tap(x: Float, y: Float, onResult: (Boolean) -> Unit): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 90))
            .build()
        return try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { onResult(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { onResult(false) }
            }, null)
        } catch (e: Exception) {
            report("提交点击手势失败：${e.message}")
            false
        }
    }

    private fun report(message: String) {
        sendBroadcast(android.content.Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_MESSAGE, message))
    }

    companion object {
        const val ACTION_STATUS = "com.example.pvzautoclicker.STATUS"
        const val EXTRA_MESSAGE = "message"
        @Volatile var instance: ClickAccessibilityService? = null
            private set
    }
}
