package com.gameserver.manager

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.gameserver.manager.bridge.NativeBridge
import com.gameserver.manager.databinding.ActivityMainBinding
import com.gameserver.manager.hotupdate.HotUpdateManager
import com.gameserver.manager.util.JsonUtil
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var hotUpdateManager: HotUpdateManager
    private lateinit var nativeBridge: NativeBridge

    private var pendingFileCallbackId: String? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val callbackId = pendingFileCallbackId
        pendingFileCallbackId = null
        if (callbackId == null) return@registerForActivityResult

        if (uri == null) {
            nativeBridge.deliverCallback(callbackId, JsonUtil.error("File selection cancelled"))
            return@registerForActivityResult
        }

        lifecycleScope.launch {
            val result = runCatching {
                val localPath = copyUriToCache(uri)
                JsonUtil.success(mapOf("localPath" to localPath, "uri" to uri.toString()))
            }.getOrElse { throwable ->
                JsonUtil.error(throwable.message ?: "Failed to read selected file")
            }
            nativeBridge.deliverCallback(callbackId, result)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hotUpdateManager = HotUpdateManager(this)
        setupWebView()
        loadWebApp()
    }

    private fun setupWebView() {
        nativeBridge = NativeBridge(
            webView = binding.webView,
            scope = lifecycleScope,
            hotUpdateManager = hotUpdateManager,
            onReloadWebView = { loadWebApp() },
            onPickFile = { mimeTypes, callbackId ->
                pendingFileCallbackId = callbackId
                filePickerLauncher.launch(mimeTypes)
            }
        )

        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = false
            }

            addJavascriptInterface(nativeBridge, NativeBridge.JS_BRIDGE_NAME)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        return true
                    }
                    return false
                }
            }
            webChromeClient = WebChromeClient()
        }
    }

    private fun loadWebApp() {
        binding.webView.loadUrl(hotUpdateManager.getEntryUrl())
    }

    private fun copyUriToCache(uri: Uri): String {
        val fileName = queryDisplayName(uri) ?: "picked_${System.currentTimeMillis()}"
        val target = File(cacheDir, "picked/$fileName")
        target.parentFile?.mkdirs()
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Cannot open selected file")
        return target.absolutePath
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(index)
                }
            }
        return null
    }

    override fun onDestroy() {
        GameServerApp.instance.sshSessionManager.disconnectAll()
        binding.webView.removeJavascriptInterface(NativeBridge.JS_BRIDGE_NAME)
        binding.webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
