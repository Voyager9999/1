package com.airswipe.app

import android.content.Context
import android.content.SharedPreferences

object AppSettings {
    const val KEY_SENSITIVITY = "sensitivity"
    const val KEY_ONLY_UP = "only_up"
    const val KEY_INVERT = "invert"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun sensitivity(p: SharedPreferences): Int =
        p.getInt(KEY_SENSITIVITY, MotionSwipeDetector.DEFAULT_SENSITIVITY)

    fun onlyUp(p: SharedPreferences): Boolean = p.getBoolean(KEY_ONLY_UP, true)

    fun invert(p: SharedPreferences): Boolean = p.getBoolean(KEY_INVERT, false)
}

/** 服务 → 界面 的简单通知通道（用于实时显示运动量和识别结果） */
object GestureBus {
    interface Listener {
        fun onMotion(level: Float)
        fun onSwipe(direction: Int)
        fun onStateChanged()
    }

    @Volatile
    var listener: Listener? = null

    fun motion(level: Float) {
        listener?.onMotion(level)
    }

    fun swipe(direction: Int) {
        listener?.onSwipe(direction)
    }

    fun notifyStateChanged() {
        listener?.onStateChanged()
    }
}
