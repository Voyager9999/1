package com.airswipe.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：只负责在当前前台App里模拟一次上滑/下滑手势，不读取任何屏幕内容。
 */
class SwipeAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: SwipeAccessibilityService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        GestureBus.notifyStateChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        GestureBus.notifyStateChanged()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        GestureBus.notifyStateChanged()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要处理任何事件
    }

    override fun onInterrupt() {}

    /**
     * @param fingerUp true = 手指从下往上滑（短视频里是"下一个"）
     */
    fun swipe(fingerUp: Boolean) {
        mainHandler.post {
            val dm = resources.displayMetrics
            val x = dm.widthPixels / 2f
            val top = dm.heightPixels * 0.30f
            val bottom = dm.heightPixels * 0.70f
            val path = Path().apply {
                if (fingerUp) {
                    moveTo(x, bottom)
                    lineTo(x, top)
                } else {
                    moveTo(x, top)
                    lineTo(x, bottom)
                }
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 200L)
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }
    }
}
