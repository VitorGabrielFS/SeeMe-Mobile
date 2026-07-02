package br.com.seeme.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
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
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.Locale
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
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    private var eyeEnabled = false
    private var voiceEnabled = false
    private var voiceMode = VoiceMode.WakeWord
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
        runCatching {
            addBubble()
            addCursor()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stopVoice()
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
            addView(panelButton(if (voiceEnabled) "Voz ON" else "Voz OFF", if (voiceEnabled) "#00C853" else "#FFFFFF") { toggleVoice() })
            addView(panelButton("Ouvir", "#FFFFFF") { listenCommandNow() })
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
        refreshPanel()
    }

    private fun toggleVoice() {
        if (voiceEnabled) stopVoice() else startVoice()
        refreshPanel()
    }

    private fun listenCommandNow() {
        if (!voiceEnabled) startVoice()
        voiceMode = VoiceMode.Command
        speak("Pode falar.")
        restartListening(250L)
        refreshPanel()
    }

    private fun refreshPanel() {
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
            startForegroundServiceNotification()
            faceHelper = EyeTrackerHelper(this)
        } catch (_: Throwable) {
            eyeEnabled = false
            cursor?.visibility = View.GONE
            openAppSettings()
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
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
            } catch (_: Throwable) {
                stopEyeTracking()
                openAppSettings()
            }
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

    private fun startVoice() {
        if (voiceEnabled) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            openAppSettings()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Reconhecimento de voz indisponivel neste aparelho.")
            return
        }

        voiceEnabled = true
        voiceMode = VoiceMode.WakeWord
        initTts()
        startForegroundServiceNotification()
        restartListening()
        speak("Voz ativada. Diga Bruna.")
    }

    private fun stopVoice() {
        voiceEnabled = false
        speechRecognizer?.setRecognitionListener(null)
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
    }

    private fun restartListening(delayMs: Long = 350L) {
        if (!voiceEnabled) return
        bubble?.postDelayed({
            if (!voiceEnabled) return@postDelayed
            val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
                speechRecognizer = it
                it.setRecognitionListener(VoiceListener())
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            }
            runCatching {
                recognizer.cancel()
                recognizer.startListening(intent)
            }
        }, delayMs)
    }

    private fun handleVoiceText(text: String) {
        val normalized = text.lowercase(Locale("pt", "BR")).trim()
        if (normalized.isBlank()) return

        if (voiceMode == VoiceMode.WakeWord) {
            if (isWakeWord(normalized)) {
                voiceMode = VoiceMode.Command
                speak("Ouvindo.")
                restartListening(250L)
            }
            return
        }

        voiceMode = VoiceMode.WakeWord
        executeVoiceCommand(normalized)
    }

    private fun executeVoiceCommand(command: String) {
        when {
            command.contains("voltar") -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                speak("Voltando.")
            }
            command.contains("início") || command.contains("inicio") || command.contains("home") -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                speak("Inicio.")
            }
            command.contains("clicar") || command.contains("clique") -> {
                tapAtCursor()
                speak("Clique.")
            }
            command.contains("calibrar") -> {
                faceHelper?.recalibrate()
                speak("Calibrado.")
            }
            command.contains("desligar voz") || command.contains("parar voz") -> {
                speak("Voz desligada.")
                stopVoice()
            }
            command.startsWith("pesquisar ") || command.startsWith("procure ") || command.startsWith("buscar ") -> {
                val query = command
                    .removePrefix("pesquisar ")
                    .removePrefix("procure ")
                    .removePrefix("buscar ")
                    .trim()
                if (query.isNotBlank()) {
                    val url = "https://www.google.com/search?q=${android.net.Uri.encode(query)}"
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    speak("Pesquisando.")
                }
            }
            command.startsWith("abrir ") -> {
                val target = command.removePrefix("abrir ").trim()
                openKnownAppOrSearch(target)
            }
            else -> speak("Comando nao reconhecido.")
        }

        if (voiceEnabled) restartListening(700L)
    }

    private fun isWakeWord(text: String): Boolean {
        val compact = text
            .lowercase(Locale("pt", "BR"))
            .replace(Regex("[^a-zà-ú ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return listOf("bruna", "brunna", "bruna", "bruma", "bruna oi", "oi bruna").any { compact.contains(it) }
    }

    private fun openKnownAppOrSearch(target: String) {
        val packageName = when {
            target.contains("youtube") -> "com.google.android.youtube"
            target.contains("chrome") -> "com.android.chrome"
            target.contains("whatsapp") || target.contains("zap") -> "com.whatsapp"
            target.contains("configura") -> "com.android.settings"
            else -> null
        }
        val launch = packageName?.let { packageManager.getLaunchIntentForPackage(it) }
        if (launch != null) {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            speak("Abrindo.")
        } else {
            val url = "https://www.google.com/search?q=${android.net.Uri.encode(target)}"
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            speak("Pesquisando.")
        }
    }

    private fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pt", "BR")
            }
        }
    }

    private fun speak(text: String) {
        initTts()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "seeme-${SystemClock.elapsedRealtime()}")
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

    private inner class VoiceListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) {
            if (voiceEnabled) restartListening(650L)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (voiceMode != VoiceMode.WakeWord) return
            val matches = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
            if (matches.any { isWakeWord(it) }) {
                handleVoiceText("bruna")
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
            val best = matches.firstOrNull().orEmpty()
            handleVoiceText(best)
            if (voiceEnabled && voiceMode == VoiceMode.WakeWord) restartListening()
        }
    }

    private enum class VoiceMode {
        WakeWord,
        Command
    }
}
