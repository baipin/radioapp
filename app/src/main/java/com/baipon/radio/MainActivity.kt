package com.baipon.radio

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import java.util.concurrent.ExecutionException

class MainActivity : AppCompatActivity() {

    private lateinit var myWebView: WebView
    private val webUrl = "https://radio.baipon.com/"
    private val updateJsonUrl = "https://radio.baipon.com/android.json"

    // Media3 Controller
    private var mediaController: MediaController? = null
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var mediaControllerInitialized = false
    private val mainScope = MainScope()

    // MDUI 风格错误页面
    private val errorHtmlContent = """
        <!DOCTYPE html>
        <html class="mdui-theme-auto">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                :root { --mdui-color-primary: rgb(103, 80, 164); }
                body { font-family: sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; background-color: #f3edf7; }
                .mdui-card { background: white; border-radius: 28px; padding: 32px; text-align: center; max-width: 280px; box-shadow: 0 4px 12px rgba(0,0,0,0.1); }
                .icon { font-size: 48px; margin-bottom: 16px; }
                h1 { font-size: 24px; margin: 0 0 12px 0; color: #1C1B1F; font-weight: 400; }
                p { font-size: 14px; color: #49454F; margin-bottom: 24px; line-height: 1.5; }
                .mdui-btn { background: var(--mdui-color-primary); color: white; border: none; padding: 12px 24px; border-radius: 20px; font-weight: 500; cursor: pointer; transition: opacity 0.2s; }
                .mdui-btn:active { opacity: 0.8; }
            </style>
        </head>
        <body>
            <div class="mdui-card">
                <div class="icon">📻</div>
                <h1>信号中断</h1>
                <p>百品电台无法连接到服务器<br>请检查您的网络设置</p>
                <button class="mdui-btn" onclick="Android.retry()">尝试重连</button>
            </div>
        </body>
        </html>
    """.trimIndent()

    // 网页与原生交互桥接
    inner class WebAppInterface {
        @JavascriptInterface
        fun retry() {
            runOnUiThread { myWebView.loadUrl(webUrl) }
        }

        @JavascriptInterface
        fun playStream(streamUrl: String, stationName: String) { // 增加 stationName 参数
            mainScope.launch {
                ensureMediaControllerConnected {
                    mediaController?.sendCustomCommand(
                        androidx.media3.session.SessionCommand(
                            PlaybackService.COMMAND_PLAY_STREAM,
                            android.os.Bundle().apply {
                                putString("url", streamUrl)
                                putString("name", stationName) // 将名称放入 Bundle
                            }
                        ), android.os.Bundle.EMPTY
                    )
                    Log.d("BaiponBridge", "播放流: $stationName - $streamUrl")
                }
            }
        }

        @JavascriptInterface
        fun pauseStream() {
            mainScope.launch {
                mediaController?.pause()
                Log.d("BaiponBridge", "暂停播放")
            }
        }
    }

    private suspend fun ensureMediaControllerConnected(action: suspend () -> Unit) {
        return withContext(Dispatchers.Main) {
            if (mediaController != null) {
                action()
            } else if (!mediaControllerInitialized) {
                connectMediaController()
                delay(500) // 等待连接建立
                if (mediaController != null) {
                    action()
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {  // Android 13+
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {

                AlertDialog.Builder(this)
                    .setTitle("需要通知权限")
                    .setMessage("为了在后台播放时显示媒体控制面板（播放/暂停按钮），请允许通知权限。")
                    .setPositiveButton("去授权") { _, _ ->
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindowDisplay()
        setContentView(R.layout.activity_main)

        setupWebView()
        findViewById<FloatingActionButton>(R.id.fab_settings).setOnClickListener {
            showSettingsMenu(it)
        }
        setupBackNavigation()
        checkUpdate(isManual = false)
        requestNotificationPermission()

        // 启动 PlaybackService
        startPlaybackService()
    }

    private fun startPlaybackService() {
        val intent = Intent(this, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        connectMediaController()
    }

    private fun connectMediaController() {
        if (mediaControllerInitialized || mediaController != null) {
            return
        }

        mediaControllerInitialized = true
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()

        controllerFuture.addListener({
            try {
                mediaController = controllerFuture.get()
                Log.d("BaiponBridge", "MediaController 连接成功")
            } catch (e: ExecutionException) {
                Log.e("BaiponBridge", "MediaController 连接失败", e)
                mediaControllerInitialized = false
            } catch (e: java.util.concurrent.CancellationException) {
                Log.w("BaiponBridge", "MediaController 任务被取消")
                mediaControllerInitialized = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        super.onStop()
        // 不释放 mediaController，保持后台播放
        try {
            if (::controllerFuture.isInitialized && mediaControllerInitialized) {
                // 只在明确不需要时才释放
                mediaControllerInitialized = false
            }
        } catch (e: Exception) {
            Log.w("BaiponBridge", "停止时出错", e)
        }
    }

    private fun setupWindowDisplay() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.parseColor("#6750A4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        myWebView = findViewById(R.id.webview)
        myWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = if (isNetworkAvailable()) {
                WebSettings.LOAD_DEFAULT
            } else {
                WebSettings.LOAD_CACHE_ELSE_NETWORK
            }
        }

        myWebView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")
        myWebView.webChromeClient = WebChromeClient()

        myWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                return if (url.contains("baipon.com")) {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request?.url))
                    } catch (_: Exception) {
                        Toast.makeText(this@MainActivity, "无法处理外部链接", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    myWebView.loadDataWithBaseURL(null, errorHtmlContent, "text/html", "UTF-8", null)
                }
            }

            // 修改 MainActivity.kt 中的 script 字符串
            override fun onPageFinished(view: WebView?, url: String?) {
                val script = """
        (function() {
            console.log('Baipon Radio Bridge injected');
            var lastUrl = "";

            function notifyPlay() {
                var audio = document.querySelector('audio');
                // 从网页 DOM 中抓取当前的电台名称
                var stationNameElement = document.getElementById('current-name');
                var stationName = (stationNameElement && stationNameElement.innerText.trim()) 
                                  ? stationNameElement.innerText.trim() 
                                  : "百品电台";
                
                if (audio && (audio.src || audio.currentSrc)) {
                    var realUrl = audio.currentSrc || audio.src;
                    
                    // 只有当 URL 有效且与上次不同时，才发送指令给 App
                    if (realUrl.startsWith('http') && realUrl !== lastUrl) {
                        // 必须调用 AndroidBridge (确保与 addJavascriptInterface 一致)
                        if (window.AndroidBridge && window.AndroidBridge.playStream) {
                            window.AndroidBridge.playStream(realUrl, stationName);
                            lastUrl = realUrl;
                            console.log('同步到 App: ' + stationName + ' -> ' + realUrl);
                        }
                    }
                }
            }

            // 监听播放事件：当用户点击网页播放按钮时立即同步
            document.addEventListener('play', notifyPlay, true);
            document.addEventListener('playing', notifyPlay, true);
            
            // 轮询检查：应对网页内部逻辑自动切换下一首的情况
            setInterval(notifyPlay, 3000); 
        })();
    """.trimIndent()

                view?.evaluateJavascript(script, null)
            }
        }

        myWebView.loadUrl(webUrl)
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val cap = cm.getNetworkCapabilities(network) ?: return false
            cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } catch (_: Exception) {
            false
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (myWebView.canGoBack()) {
                    myWebView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun showSettingsMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add("关于")
        popup.menu.add("检查更新")
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "关于" -> { showAboutDialog(); true }
                "检查更新" -> { checkUpdate(isManual = true); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun getAppVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (_: PackageManager.NameNotFoundException) {
        "1.0"
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("关于百品电台")
            .setMessage("版本: ${getAppVersionName()}\n致力于提供最纯净的收听体验。")
            .setPositiveButton("确定", null)
            .show()
    }

    private fun checkUpdate(isManual: Boolean) {
        if (isManual) Toast.makeText(this, "正在检查更新...", Toast.LENGTH_SHORT).show()

        val currentCode = try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else @Suppress("DEPRECATION") pInfo.versionCode.toLong()
        } catch (_: Exception) { 1L }

        Thread {
            try {
                val connection = URL(updateJsonUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                val data = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(data)
                val serverCode = json.getLong("versionCode")
                val serverName = json.getString("versionName")
                val downloadUrl = json.getString("downloadUrl")
                val updateLog = json.getString("updateLog")

                runOnUiThread {
                    if (serverCode > currentCode) {
                        showUpdateDialog(serverName, updateLog, downloadUrl)
                    } else if (isManual) {
                        Toast.makeText(this, "当前已是最新版本", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
                if (isManual) runOnUiThread {
                    Toast.makeText(this, "检查更新失败", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun showUpdateDialog(newName: String, log: String, url: String) {
        AlertDialog.Builder(this)
            .setTitle("发现新版本: $newName")
            .setMessage(log)
            .setPositiveButton("立即下载") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                } catch (_: Exception) {
                    Toast.makeText(this, "链接失效", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("以后再说", null)
            .show()
    }

    override fun onDestroy() {
        mainScope.cancel()
        super.onDestroy()
        // 清空 mediaController
        mediaController = null
        mediaControllerInitialized = false
        try {
            if (::controllerFuture.isInitialized) {
                MediaController.releaseFuture(controllerFuture)
            }
        } catch (e: Exception) {
            Log.w("BaiponBridge", "销毁时释放失败", e)
        }
    }
}
