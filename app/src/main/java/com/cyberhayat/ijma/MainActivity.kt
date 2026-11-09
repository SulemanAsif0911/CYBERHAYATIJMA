package com.cyberhayat.ijma

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import java.io.File
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.webkit.WebViewAssetLoader
import java.security.MessageDigest

/**
 * Same shell format as Cyber Hayat Nijah: a native Kotlin host serving assets/www inside a WebView
 * (https://appassets.androidplatform.net) and talking to it through the `AndroidNative` bridge.
 * Background alerts live in SyncService.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView

    private var pendingPerm: PermissionRequest? = null
    private var chooserCb: ValueCallback<Array<Uri>>? = null

    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        pendingPerm?.let { req ->
            val ok = req.resources.filter { r ->
                (r == PermissionRequest.RESOURCE_AUDIO_CAPTURE && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) ||
                (r == PermissionRequest.RESOURCE_VIDEO_CAPTURE && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            }
            if (ok.isEmpty()) req.deny() else req.grant(ok.toTypedArray())
        }
        pendingPerm = null
    }
    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        chooserCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        chooserCb = null
    }

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        setContentView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != "appassets.androidplatform.net"
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
        }
        web.webChromeClient = object : WebChromeClient() {
            // microphone for voice notes and calls (Android asks the person once)
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val need = mutableListOf<String>()
                    for (r in request.resources) {
                        if (r == PermissionRequest.RESOURCE_AUDIO_CAPTURE && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
                        if (r == PermissionRequest.RESOURCE_VIDEO_CAPTURE && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA)
                    }
                    if (need.isEmpty()) request.grant(request.resources)
                    else { pendingPerm = request; micPerm.launch(need.toTypedArray()) }
                }
            }
            // photo / file picker for sending attachments
            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                chooserCb?.onReceiveValue(null)
                chooserCb = cb
                return try { chooser.launch(p.createIntent()); true } catch (e: Exception) { chooserCb = null; false }
            }
        }
        web.addJavascriptInterface(Bridge(), "AndroidNative")
        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.onBack&&onBack())?1:0") { r ->
                    if (r != "1") moveTaskToBack(true) // hide the app, keep the service running
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        SyncService.appVisible = true
    }

    override fun onPause() {
        SyncService.appVisible = false
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    /** Only our own bundled page is ever loaded, so this bridge is not exposed to the web. */
    inner class Bridge {
        /** Stable per phone; sent to the server as a hash only. */
        @JavascriptInterface fun deviceId(): String {
            val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
            val d = MessageDigest.getInstance("SHA-256").digest(("ijma:$id").toByteArray(Charsets.UTF_8))
            return d.joinToString("") { "%02x".format(it) }
        }

        /** Page hands over server URL, token, cursors and chat names; starts the background service. */
        @JavascriptInterface fun startSync(json: String) {
            SyncService.prefs(this@MainActivity).edit().putString("cfg", json).apply()
            if (!SyncService.running) runOnUiThread {
                try { SyncService.start(this@MainActivity) } catch (_: Exception) {}
            }
        }

        @JavascriptInterface fun stopSync() {
            SyncService.prefs(this@MainActivity).edit().remove("cfg").apply()
            SyncService.stop(this@MainActivity)
        }

        @JavascriptInterface fun clearNotifications(convId: String) {
            NotificationManagerCompat.from(this@MainActivity).cancel(convId.hashCode())
        }

        @JavascriptInterface fun requestNotifPerm() {
            if (Build.VERSION.SDK_INT >= 33) runOnUiThread { notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }

        /** Saves a received file into Downloads/Ijma. */
        @JavascriptInterface fun saveFile(b64: String, name: String, mime: String): Boolean {
            return try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
                if (Build.VERSION.SDK_INT >= 29) {
                    val v = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safe)
                        put(MediaStore.Downloads.MIME_TYPE, if (mime.isEmpty()) "application/octet-stream" else mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/Ijma")
                    }
                    val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return false
                    contentResolver.openOutputStream(u)?.use { it.write(bytes) }
                } else {
                    File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), safe).writeBytes(bytes)
                }
                true
            } catch (e: Exception) { false }
        }

        @JavascriptInterface fun batteryOk(): Boolean {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(packageName)
        }

        @JavascriptInterface fun batterySettings() {
            runOnUiThread {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }
    }
}
