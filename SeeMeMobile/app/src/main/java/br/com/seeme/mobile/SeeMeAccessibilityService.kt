package br.com.seeme.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class SeeMeAccessibilityService : AccessibilityService(), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var cursor: View? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var faceHelper: EyeTrackerHelper? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var eyeEnabled = false
    private var panelOpen = false
    private var cursorX = 540f
    private var cursorY = 960f
    private var lastBlinkAt = 0L
    private var leftClosedAt = 0L

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onServiceConnected() {
        super.onServiceConnected()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundServiceNotification()
        addBubble()
        addCursor()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stopEyeTracking()
        removeOverlays()
        cameraExecutor.shutdown()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private fun addBubble() {
        val view = TextView(this).apply {
            text = "S"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(18, 97, 166))
            setOnClickListener { togglePanel() }
        }
        val params = overlayParams(72, 72, Gravity.TOP or Gravity.START).apply {
            x = 20
            y = 220
        }
        view.setOnTouchListener(DragTouch(params))
        windowManager.addView(view, params)
        bubble = view
    }

    private fun addPanel() {
        if (panel != null) return
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            setBackgroundColor(Color.argb(230, 18, 24, 33))
            addView(panelButton("Olhos", if (eyeEnabled) "#00C853" else "#FFFFFF") { toggleEyeTracking() })
            addView(panelButton("Calibrar", "#FFFFFF") { faceHelper?.recalibrate() })
            addView(panelButton("Clique", "#FFFFFF") { tapAtCursor() })
            addView(panelButton("Voltar", "#FFFFFF") { performGlobalAction(GLOBAL_ACTION_BACK) })
            addView(panelButton("Home", "#FFFFFF") { performGlobalAction(GLOBAL_ACTION_HOME) })
            addView(panelButton("Fechar", "#FFFFFF") { togglePanel() })
        }
        val params = overlayParams(260, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            x = 20
            y = 310
        }
        windowManager.addView(view, params)
        panel = view
    }

    private fun panelButton(label: String, color: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.parseColor(color))
            setOnClickListener { action() }
        }
    }

    private fun addCursor() {
        val size = 56
        val view = View(this).apply {
            setBackgroundResource(R.drawable.cursor_ring)
            visibility = View.GONE
        }
        val params = overlayParams(size, size, Gravity.TOP or Gravity.START).apply {
            x = cursorX.toInt()
            y = cursorY.toInt()
        }
        windowManager.addView(view, params)
        cursor = view
        cursorParams = params
    }

    private fun togglePanel() {
        panelOpen = !panelOpen
        if (panelOpen) addPanel() else {
            panel?.let { windowManager.removeView(it) }
            panel = null
        }
    }

    private fun toggleEyeTracking() {
        if (eyeEnabled) stopEyeTracking() else startEyeTracking()
        panel?.let {
            windowManager.removeView(it)
            panel = null
            panelOpen = false
            togglePanel()
        }
    }

    private fun startEyeTracking() {
        if (eyeEnabled) return
        eyeEnabled = true
        cursor?.visibility = View.VISIBLE

        try {
            faceHelper = EyeTrackerHelper(this)
        } catch (_: Throwable) {
            eyeEnabled = false
            cursor?.visibility = View.GONE
            openAppSettings()
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analyzer ->
                    analyzer.setAnalyzer(cameraExecutor) { image ->
                        analyzeEyeFrame(image)
                    }
                }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopEyeTracking() {
        eyeEnabled = false
        cursor?.visibility = View.GONE
        cameraProvider?.unbindAll()
        cameraProvider = null
        faceHelper?.close()
        faceHelper = null
    }

    private fun analyzeEyeFrame(image: androidx.camera.core.ImageProxy) {
        try {
            val frame = faceHelper?.detect(image, SystemClock.uptimeMillis())
            if (frame is EyeFrame.Face) {
                updateCursor(frame)
                updateBlink(frame)
            }
        } catch (_: Throwable) {
            // Keeps the accessibility service alive if a single camera frame fails.
        } finally {
            image.close()
        }
    }

    private fun updateCursor(frame: EyeFrame.Face) {
        val metrics = resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val centerY = metrics.heightPixels / 2f
        val targetX = centerX + frame.offsetX * metrics.widthPixels * 5.5f
        val targetY = centerY + frame.offsetY * metrics.heightPixels * 5.5f
        cursorX = (cursorX * 0.72f) + (targetX * 0.28f)
        cursorY = (cursorY * 0.72f) + (targetY * 0.28f)
        cursorX = min(max(cursorX, 0f), metrics.widthPixels - 56f)
        cursorY = min(max(cursorY, 0f), metrics.heightPixels - 56f)

        val params = cursorParams ?: return
        params.x = cursorX.toInt()
        params.y = cursorY.toInt()
        cursor?.post {
            cursor?.let { windowManager.updateViewLayout(it, params) }
        }
    }

    private fun updateBlink(frame: EyeFrame.Face) {
        val now = SystemClock.elapsedRealtime()
        val leftClosed = frame.leftEar < 0.18f
        val rightOpen = frame.rightEar > 0.24f

        if (leftClosed && rightOpen && leftClosedAt == 0L) {
            leftClosedAt = now
        }

        if (!leftClosed && leftClosedAt != 0L) {
            val duration = now - leftClosedAt
            leftClosedAt = 0L
            if (duration in 120..650 && now - lastBlinkAt > 900) {
                lastBlinkAt = now
                tapAtCursor()
            }
        }
    }

    private fun tapAtCursor() {
        val path = Path().apply {
            moveTo(cursorX + 28f, cursorY + 28f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun overlayParams(width: Int, height: Int, gravityValue: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = gravityValue
        }
    }

    private fun removeOverlays() {
        listOfNotNull(panel, bubble, cursor).forEach {
            runCatching { windowManager.removeView(it) }
        }
        panel = null
        bubble = null
        cursor = null
    }

    private fun startForegroundServiceNotification() {
        val channelId = "seeme_accessibility"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "SeeMe Acessibilidade",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("SeeMe ativo")
            .setContentText("Controle por olhos e atalhos flutuantes disponiveis.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        startForeground(42, notification)
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private inner class DragTouch(private val params: WindowManager.LayoutParams) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    moved = true
                    windowManager.updateViewLayout(view, params)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved || (kotlin.math.abs(event.rawX - touchX) < 8 && kotlin.math.abs(event.rawY - touchY) < 8)) {
                        view.performClick()
                    }
                    return true
                }
            }
            return false
        }
    }
}
