package com.zipgrade.omrscanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Android Handheld OMR Scanner Activity
 * - Grants Android CameraX / WebRTC rear camera permissions for live 6-fiducial auto-shutter
 * - Hardware Volume Up / Down keys trigger instant sheet capture
 * - Connects to the OMR Scanner PWA with full offline Service Worker caching
 */
class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        loadScannerApp()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    request.grant(request.resources)
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            loadScannerApp()
        }
    }

    private fun loadScannerApp() {
        webView.loadUrl("undefined?mode=android")
    }

    // Allow grading papers one-handed with physical Android Volume buttons
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('android-hardware-shutter'));", null)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}