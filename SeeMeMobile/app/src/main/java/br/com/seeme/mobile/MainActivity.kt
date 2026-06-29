package br.com.seeme.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var webView: WebView
    private lateinit var cameraContainer: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var helper: HandLandmarkerHelper

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val shortcuts = mutableListOf(
        GestureShortcut(1, "Abrir Google", ShortcutType.Website, "https://www.google.com"),
        GestureShortcut(2, "Compartilhar texto", ShortcutType.ShareText, "Acionado pelo SeeMe Mobile"),
        GestureShortcut(5, "Voltar para web", ShortcutType.WebHome, "")
    )

    private var lastGesture = -1
    private var gestureStartedAt = 0L
    private var lastActionAt = 0L
    private var cameraStarted = false

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else showMessage("Permissao de camera negada")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        setupWeb()

        try {
            helper = HandLandmarkerHelper(this)
        } catch (error: Throwable) {
            showMessage("Modelo nao encontrado. Rode tools/download-models.ps1")
            statusText.text = "Modelo MediaPipe ausente"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::helper.isInitialized) helper.close()
    }

    private fun buildLayout() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 248, 250))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 12)
        }

        val webButton = navButton("Web") { showWeb() }
        val cameraButton = navButton("Camera") { ensureCameraPermission() }
        toolbar.addView(webButton, LinearLayout.LayoutParams(0, 52, 1f))
        toolbar.addView(cameraButton, LinearLayout.LayoutParams(0, 52, 1f))

        webView = WebView(this)
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setPadding(18, 14, 18, 14)
            text = "Camera pronta"
        }
        cameraContainer = FrameLayout(this).apply {
            visibility = View.GONE
            addView(previewView, FrameLayout.LayoutParams(-1, -1))
            addView(
                statusText,
                FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
            )
        }

        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(cameraContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun navButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(WebBridge(), "SeeMe")
        loadHome()
    }

    private fun showWeb() {
        cameraContainer.visibility = View.GONE
        webView.visibility = View.VISIBLE
        loadHome()
    }

    private fun showCamera() {
        webView.visibility = View.GONE
        cameraContainer.visibility = View.VISIBLE
    }

    private fun ensureCameraPermission() {
        if (!::helper.isInitialized) {
            showMessage("Baixe o modelo antes de abrir a camera")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        showCamera()
        if (cameraStarted) return
        cameraStarted = true

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analyzer ->
                    analyzer.setAnalyzer(cameraExecutor) { image ->
                        analyzeFrame(image)
                    }
                }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: androidx.camera.core.ImageProxy) {
        try {
            val detection = helper.detect(image, SystemClock.uptimeMillis())
            val fingers = if (detection.hasHand) detection.fingers else 0
            handleGesture(fingers, detection.hasHand)
        } catch (_: Throwable) {
            runOnUiThread { statusText.text = "Falha ao processar frame" }
        } finally {
            image.close()
        }
    }

    private fun handleGesture(fingers: Int, hasHand: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!hasHand) {
            lastGesture = -1
            runOnUiThread { statusText.text = "Mostre a mao para a camera" }
            return
        }

        if (fingers != lastGesture) {
            lastGesture = fingers
            gestureStartedAt = now
        }

        val heldMs = now - gestureStartedAt
        val shortcut = shortcuts.firstOrNull { it.fingers == fingers }
        val label = shortcut?.name ?: "Sem atalho"
        runOnUiThread {
            statusText.text = "$fingers dedo(s) | $label | ${heldMs / 1000.0}s"
        }

        if (shortcut != null && heldMs >= 1000 && now - lastActionAt >= 2200) {
            lastActionAt = now
            runOnUiThread { executeShortcut(shortcut) }
        }
    }

    private fun executeShortcut(shortcut: GestureShortcut) {
        when (shortcut.type) {
            ShortcutType.Website -> {
                val url = if (shortcut.value.startsWith("http")) shortcut.value else "https://${shortcut.value}"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            ShortcutType.ShareText -> {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shortcut.value)
                }
                startActivity(Intent.createChooser(intent, shortcut.name))
            }
            ShortcutType.WebHome -> showWeb()
        }
        showMessage("Atalho: ${shortcut.name}")
    }

    private fun loadHome() {
        webView.loadDataWithBaseURL(
            "https://seeme.local/",
            buildHtml(),
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun buildHtml(): String {
        val rows = shortcuts.joinToString("") {
            "<tr><td>${it.fingers}</td><td>${it.name}</td><td>${it.type}</td><td>${it.value}</td></tr>"
        }
        return """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <style>
                body{margin:0;font-family:Arial,sans-serif;background:#f7f8fa;color:#1d2433}
                header{padding:18px 18px 8px}
                h1{font-size:24px;margin:0 0 6px}
                main{padding:10px 18px 28px}
                section{margin:16px 0}
                input,select,button{width:100%;box-sizing:border-box;margin:6px 0;padding:12px;font-size:16px}
                button{background:#1261a6;color:white;border:0;border-radius:6px}
                table{width:100%;border-collapse:collapse;background:white}
                td,th{padding:10px;border-bottom:1px solid #e1e4ea;text-align:left;font-size:14px}
                .hint{color:#556070;font-size:14px}
              </style>
            </head>
            <body>
              <header>
                <h1>SeeMe Mobile</h1>
                <div class="hint">WebView local integrada com controle por gestos.</div>
              </header>
              <main>
                <section>
                  <button onclick="SeeMe.openCamera()">Abrir camera</button>
                </section>
                <section>
                  <h2>Novo atalho</h2>
                  <input id="fingers" type="number" min="1" max="5" placeholder="Dedos: 1 a 5">
                  <input id="name" placeholder="Nome do atalho">
                  <select id="type">
                    <option value="Website">Abrir site</option>
                    <option value="ShareText">Compartilhar texto</option>
                    <option value="WebHome">Voltar para web</option>
                  </select>
                  <input id="value" placeholder="URL ou texto">
                  <button onclick="save()">Salvar atalho</button>
                </section>
                <section>
                  <h2>Atalhos ativos</h2>
                  <table>
                    <tr><th>Dedos</th><th>Nome</th><th>Tipo</th><th>Valor</th></tr>
                    $rows
                  </table>
                </section>
              </main>
              <script>
                function save(){
                  SeeMe.addShortcut(
                    document.getElementById('fingers').value,
                    document.getElementById('name').value,
                    document.getElementById('type').value,
                    document.getElementById('value').value
                  );
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    inner class WebBridge {
        @JavascriptInterface
        fun openCamera() {
            runOnUiThread { ensureCameraPermission() }
        }

        @JavascriptInterface
        fun addShortcut(fingersRaw: String, name: String, typeRaw: String, value: String) {
            val fingers = fingersRaw.toIntOrNull()
            if (fingers == null || fingers !in 1..5 || name.isBlank()) {
                runOnUiThread { showMessage("Preencha dedos e nome") }
                return
            }
            val type = ShortcutType.entries.firstOrNull { it.name == typeRaw } ?: ShortcutType.Website
            shortcuts.removeAll { it.fingers == fingers }
            shortcuts.add(GestureShortcut(fingers, name.trim(), type, value.trim()))
            runOnUiThread {
                showMessage("Atalho salvo")
                loadHome()
            }
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
