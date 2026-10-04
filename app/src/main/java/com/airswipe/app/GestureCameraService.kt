package com.airswipe.app

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * 前台服务：在后台打开前置摄像头，逐帧做手势检测，识别到后让无障碍服务模拟滑动。
 * 屏幕关闭时自动关闭摄像头省电，解锁后自动恢复。
 */
class GestureCameraService : LifecycleService() {

    companion object {
        const val ACTION_STOP = "com.airswipe.app.STOP"
        private const val CHANNEL_ID = "air_swipe"
        private const val NOTIFICATION_ID = 1
        private const val GRID_W = 64          // 降采样后的宽度（像素）
        private const val MIN_FRAME_GAP_MS = 35L
        private const val TAG = "AirSwipe"

        @Volatile
        var isRunning = false
            private set
    }

    private val detector = MotionSwipeDetector()
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var prefs: SharedPreferences
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraRequested = false
    private var started = false
    private var lastProcessed = 0L
    @Volatile private var lastNoticeText = ""

    // 必须持有强引用，否则监听会被回收
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applySettings() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> stopCamera()
                Intent.ACTION_USER_PRESENT -> startCamera()
                Intent.ACTION_SCREEN_ON -> {
                    val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                    if (km == null || !km.isKeyguardLocked) startCamera()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        analysisExecutor = Executors.newSingleThreadExecutor()
        prefs = AppSettings.prefs(this)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        applySettings()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            goForeground()
            isRunning = true
            GestureBus.notifyStateChanged()
            startCamera()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        cameraProvider?.unbindAll()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
        }
        analysisExecutor.shutdown()
        GestureBus.notifyStateChanged()
        super.onDestroy()
    }

    // ---------------- 通知 ----------------

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "隔空滑屏", NotificationManager.IMPORTANCE_LOW)
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            0
        }
        val text = defaultNoticeText()
        lastNoticeText = text
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
    }

    private fun defaultNoticeText(): String =
        if (detector.onlyUp) "在镜头前向上挥手 = 下一个" else "向上挥手 = 下一个，向下挥手 = 上一个"

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, GestureCameraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("隔空滑屏运行中")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        if (text == lastNoticeText || !isRunning) return
        lastNoticeText = text
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ---------------- 摄像头 ----------------

    private fun startCamera() {
        if (!isRunning || cameraRequested) return
        cameraRequested = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!isRunning || !cameraRequested) return@addListener
            val provider = try {
                future.get()
            } catch (e: Exception) {
                Log.e(TAG, "获取摄像头失败", e)
                cameraRequested = false
                return@addListener
            }
            cameraProvider = provider

            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(320, 240),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(Surface.ROTATION_0) // 按手机竖屏计算方向
                .build()
            analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                analysisExecutor.execute { detector.reset() }
                updateNotification(defaultNoticeText())
            } catch (e: Exception) {
                Log.e(TAG, "打开前置摄像头失败", e)
                cameraRequested = false
                updateNotification("无法打开前置摄像头（可能被其他App占用）")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraRequested = false
        cameraProvider?.unbindAll()
        if (!analysisExecutor.isShutdown) analysisExecutor.execute { detector.reset() }
    }

    private fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastProcessed < MIN_FRAME_GAP_MS) return
            lastProcessed = now

            // 只取亮度（Y）平面，并降采样到约 64 像素宽
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val step = max(1, image.width / GRID_W)
            val gw = image.width / step
            val gh = image.height / step
            val gray = IntArray(gw * gh)
            for (gy in 0 until gh) {
                val rowStart = gy * step * rowStride
                for (gx in 0 until gw) {
                    gray[gy * gw + gx] = buffer.get(rowStart + gx * step * pixelStride).toInt() and 0xFF
                }
            }

            val dir = detector.process(gray, gw, gh, image.imageInfo.rotationDegrees, now)
            GestureBus.motion(detector.lastMotionLevel)
            if (dir != MotionSwipeDetector.NONE) {
                GestureBus.swipe(dir)
                performSwipe(dir)
            }
        } catch (e: Exception) {
            Log.e(TAG, "分析画面出错", e)
        } finally {
            image.close()
        }
    }

    private fun performSwipe(dir: Int) {
        val svc = SwipeAccessibilityService.instance
        if (svc == null) {
            updateNotification("请先在设置中开启“隔空滑屏”无障碍服务")
            return
        }
        // 手向上挥 → 手指向上滑 → 下一个视频
        svc.swipe(fingerUp = dir == MotionSwipeDetector.UP)
        updateNotification(defaultNoticeText())
    }

    private fun applySettings() {
        detector.applySensitivity(AppSettings.sensitivity(prefs))
        detector.onlyUp = AppSettings.onlyUp(prefs)
        detector.invert = AppSettings.invert(prefs)
    }
}
