package com.example.trailblazer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var sensorBridge: NativeSensorBridge

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        android.util.Log.d("TrailBlazer", "Permissions granted: $allGranted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Modern Android Edge-to-Edge with transparent system bars
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        super.onCreate(savedInstanceState)

        // Ensure status bar icons and navigation bar are light (white) on dark glass theme
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        // Extend into display cutout / notch area
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // Enable screen wake lock support
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize Native Sensor & Safe Area Inset Bridge
        sensorBridge = NativeSensorBridge(this)

        // Initialize WebViewAssetLoader for secure loading from app assets
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#080b14"))

            // Edge-to-edge insets listener: do NOT pad WebView view itself so dark liquid
            // glass background and header blur extend behind notification panel and nav bar.
            // Instead, measure precise dp insets and bridge them directly to CSS custom properties.
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
                )
                val density = resources.displayMetrics.density
                val safeDensity = if (density > 0f) density else 1f
                val topDp = insets.top / safeDensity
                val bottomDp = insets.bottom / safeDensity
                val leftDp = insets.left / safeDensity
                val rightDp = insets.right / safeDensity

                sensorBridge.updateSafeAreaInsets(topDp, bottomDp, leftDp, rightDp)
                applySafeAreaInsetsToWeb(topDp, bottomDp, leftDp, rightDp)

                windowInsets
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                setGeolocationEnabled(true)
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    applySafeAreaInsetsToWeb(
                        sensorBridge.getSafeAreaTop(),
                        sensorBridge.getSafeAreaBottom(),
                        sensorBridge.getSafeAreaLeft(),
                        sensorBridge.getSafeAreaRight()
                    )
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread {
                        request.grant(request.resources)
                    }
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String,
                    callback: GeolocationPermissions.Callback
                ) {
                    callback.invoke(origin, true, false)
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    android.util.Log.d(
                        "TrailBlazerJS",
                        "[${consoleMessage.messageLevel()}] ${consoleMessage.message()} (at ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                    )
                    return true
                }
            }

            addJavascriptInterface(sensorBridge, "AndroidBridge")
        }

        setContentView(webView)

        // Check and request runtime permissions
        requestRuntimePermissions()

        // Handle Back button
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Load TrailBlazer web application
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    private fun applySafeAreaInsetsToWeb(topDp: Float, bottomDp: Float, leftDp: Float, rightDp: Float) {
        val js = """
            (function() {
                var root = document.documentElement;
                if (!root) return;
                root.style.setProperty('--safe-top', '${topDp}px');
                root.style.setProperty('--safe-bottom', '${bottomDp}px');
                root.style.setProperty('--safe-left', '${leftDp}px');
                root.style.setProperty('--safe-right', '${rightDp}px');
                if (typeof window.__onSafeAreaInsetsChanged === 'function') {
                    window.__onSafeAreaInsetsChanged({ top: $topDp, bottom: $bottomDp, left: $leftDp, right: $rightDp });
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun requestRuntimePermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        sensorBridge.start()
    }

    override fun onPause() {
        sensorBridge.stop()
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
