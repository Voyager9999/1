package com.airswipe.app

import kotlin.math.abs

/**
 * 基于帧差法的上下挥手检测（不依赖任何机器学习模型）。
 *
 * 思路：比较相邻两帧的灰度图，找出"变化了的像素"，计算它们的重心。
 * 手在镜头前挥过时，变化区域很大，而且重心会在短时间内朝一个方向移动一大段距离；
 * 而人脸轻微晃动、光线变化等，要么变化区域太小，要么重心不会单向大幅移动。
 *
 * 输入：降采样后的灰度图（0..255），宽 w、高 h，按传感器原始方向排列。
 * 输出：UP（手向上挥）、DOWN（手向下挥）或 NONE。
 */
class MotionSwipeDetector {

    /** 运动重心需要移动的最小距离（占画面高度的比例） */
    @Volatile var travelThreshold = travelFor(DEFAULT_SENSITIVITY)

    /** 画面中至少多少比例的像素在动，才算"有手在挥" */
    @Volatile var minMotionFraction = minFractionFor(DEFAULT_SENSITIVITY)

    /** 只响应向上挥手（避免挥完手收回来时被识别成反方向） */
    @Volatile var onlyUp = true

    /** 方向反转（万一某些机型摄像头方向特殊） */
    @Volatile var invert = false

    /** 触发一次后的冷却时间 */
    @Volatile var cooldownMs = 1000L

    /** 最近一帧的运动量（0..1），用于界面显示 */
    @Volatile var lastMotionLevel = 0f
        private set

    private class Sample(val t: Long, val y: Float)

    private var prev: IntArray? = null
    private var prevW = 0
    private var prevH = 0
    private val samples = ArrayDeque<Sample>()
    private var lastTrigger = Long.MIN_VALUE / 2

    fun applySensitivity(level: Int) {
        travelThreshold = travelFor(level)
        minMotionFraction = minFractionFor(level)
    }

    fun reset() {
        prev = null
        samples.clear()
    }

    /**
     * @param gray 本帧灰度图，调用方每帧必须传一个新数组（本类会保留它作为"上一帧"）
     * @param rotationDegrees CameraX 给出的 imageInfo.rotationDegrees
     * @param now 当前时间（毫秒，单调递增）
     */
    fun process(gray: IntArray, w: Int, h: Int, rotationDegrees: Int, now: Long): Int {
        val p = prev
        prev = gray
        if (p == null || prevW != w || prevH != h || p.size != gray.size) {
            prevW = w
            prevH = h
            return NONE
        }

        val n = gray.size
        // 整体亮度变化补偿：自动曝光调整时整幅画面一起变亮/变暗，不应算作运动
        var sumCur = 0L
        var sumPrev = 0L
        for (i in 0 until n) {
            sumCur += gray[i]
            sumPrev += p[i]
        }
        val shift = ((sumCur - sumPrev) / n).toInt()

        var count = 0
        var sx = 0L
        var sy = 0L
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (abs(gray[i] - p[i] - shift) > PIXEL_DIFF) {
                    count++
                    sx += x
                    sy += y
                }
                i++
            }
        }
        val frac = count.toFloat() / n
        lastMotionLevel = frac

        if (now - lastTrigger < cooldownMs) {
            samples.clear()
            return NONE
        }

        if (frac < minMotionFraction || frac > MAX_MOTION_FRACTION) {
            if (samples.isNotEmpty() && now - samples.last().t > GAP_MS) samples.clear()
            return NONE
        }

        val cx = sx.toFloat() / count / (w - 1).coerceAtLeast(1)
        val cy = sy.toFloat() / count / (h - 1).coerceAtLeast(1)

        // 转换成"手机竖着拿时"的纵向坐标：0 = 屏幕顶部，1 = 屏幕底部
        var uy = when (((rotationDegrees % 360) + 360) % 360) {
            90 -> cx
            180 -> 1f - cy
            270 -> 1f - cx
            else -> cy
        }
        if (invert) uy = 1f - uy

        samples.addLast(Sample(now, uy))
        while (samples.isNotEmpty() && now - samples.first().t > WINDOW_MS) samples.removeFirst()
        if (samples.size < MIN_SAMPLES) return NONE

        val dy = samples.last().y - samples.first().y
        if (abs(dy) < travelThreshold) return NONE

        // 要求重心大体上一直朝同一个方向移动
        var agree = 0
        for (k in 1 until samples.size) {
            if ((samples[k].y - samples[k - 1].y) * dy > 0f) agree++
        }
        if (agree < (samples.size - 1) * CONSISTENCY) return NONE

        val dir = if (dy < 0f) UP else DOWN
        samples.clear()
        if (onlyUp && dir == DOWN) return NONE
        lastTrigger = now
        return dir
    }

    companion object {
        const val NONE = 0
        const val UP = 1
        const val DOWN = -1
        const val DEFAULT_SENSITIVITY = 6

        private const val PIXEL_DIFF = 28
        private const val MAX_MOTION_FRACTION = 0.92f
        private const val GAP_MS = 250L
        private const val WINDOW_MS = 700L
        private const val MIN_SAMPLES = 3
        private const val CONSISTENCY = 0.6f

        /** 灵敏度 1..10 → 需要的移动距离（越灵敏越短） */
        fun travelFor(level: Int): Float = 0.50f - (level.coerceIn(1, 10) - 1) * 0.035f

        /** 灵敏度 1..10 → 需要的最小运动面积 */
        fun minFractionFor(level: Int): Float = 0.10f - (level.coerceIn(1, 10) - 1) * 0.008f
    }
}
