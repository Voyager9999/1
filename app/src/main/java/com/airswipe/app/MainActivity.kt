package com.airswipe.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity(), GestureBus.Listener {

    companion object {
        private const val REQ_PERMS = 1
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var motionText: TextView
    private lateinit var sensitivityLabel: TextView
    private lateinit var startButton: Button

    @Volatile private var lastMotionUi = 0L
    private var lastLevel = 0f
    private var lastSwipeText = "—"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = AppSettings.prefs(this)

        statusText = findViewById(R.id.statusText)
        motionText = findViewById(R.id.motionText)
        sensitivityLabel = findViewById(R.id.sensitivityLabel)
        startButton = findViewById(R.id.startButton)

        findViewById<Button>(R.id.permissionButton).setOnClickListener { requestNeededPermissions() }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.appInfoButton).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:$packageName"))
            )
        }
        startButton.setOnClickListener { toggleService() }

        val seek = findViewById<SeekBar>(R.id.sensitivitySeek)
        seek.max = 9
        seek.progress = AppSettings.sensitivity(prefs) - 1
        updateSensitivityLabel(seek.progress + 1)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                updateSensitivityLabel(progress + 1)
                if (fromUser) prefs.edit().putInt(AppSettings.KEY_SENSITIVITY, progress + 1).apply()
                updateMotionText()
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        val onlyUp = findViewById<Switch>(R.id.onlyUpSwitch)
        onlyUp.isChecked = AppSettings.onlyUp(prefs)
        onlyUp.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(AppSettings.KEY_ONLY_UP, checked).apply()
        }

        val invert = findViewById<Switch>(R.id.invertSwitch)
        invert.isChecked = AppSettings.invert(prefs)
        invert.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(AppSettings.KEY_INVERT, checked).apply()
        }

        updateMotionText()
    }

    override fun onResume() {
        super.onResume()
        GestureBus.listener = this
        refreshStatus()
    }

    override fun onPause() {
        GestureBus.listener = null
        super.onPause()
    }

    // ---------------- 按钮逻辑 ----------------

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            toast("权限已全部授予")
        } else {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun toggleService() {
        val intent = Intent(this, GestureCameraService::class.java)
        if (GestureCameraService.isRunning) {
            stopService(intent)
        } else {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                toast("请先授予摄像头权限")
                requestNeededPermissions()
                return
            }
            if (SwipeAccessibilityService.instance == null) {
                toast("无障碍服务还没开启：能识别手势，但无法滑动屏幕")
            }
            startForegroundService(intent)
        }
        statusText.postDelayed({ refreshStatus() }, 400)
    }

    // ---------------- 界面刷新 ----------------

    private fun refreshStatus() {
        val cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val acc = SwipeAccessibilityService.instance != null
        val running = GestureCameraService.isRunning
        statusText.text = buildString {
            append(if (cam) "✅" else "❌").append("  摄像头权限\n")
            append(if (acc) "✅" else "❌").append("  无障碍服务\n")
            append(if (running) "🟢  正在识别手势" else "⚪  未运行")
        }
        startButton.text = if (running) "停止识别" else "③ 开始识别"
    }

    private fun updateSensitivityLabel(level: Int) {
        sensitivityLabel.text = "灵敏度：$level / 10（误触多就调低，挥手没反应就调高）"
    }

    private fun updateMotionText() {
        val level = AppSettings.sensitivity(prefs)
        val need = (MotionSwipeDetector.minFractionFor(level) * 100).roundToInt()
        val now = (lastLevel * 100).roundToInt()
        motionText.text = "画面运动量：$now%（超过 $need% 才算在挥手）\n上次识别：$lastSwipeText"
    }

    override fun onMotion(level: Float) {
        val t = SystemClock.uptimeMillis()
        if (t - lastMotionUi < 100) return
        lastMotionUi = t
        runOnUiThread {
            lastLevel = level
            updateMotionText()
        }
    }

    override fun onSwipe(direction: Int) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val text = (if (direction == MotionSwipeDetector.UP) "向上挥手 ↑" else "向下挥手 ↓") + "   $time"
        runOnUiThread {
            lastSwipeText = text
            updateMotionText()
        }
    }

    override fun onStateChanged() {
        runOnUiThread { refreshStatus() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
