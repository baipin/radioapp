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
        fun playStream(streamUrl: String, stationName: String) {
            Log.d("BaiponBridge", "playStream 被调用: $stationName - $streamUrl")
            mainScope.launch {
                ensureMediaControllerConnected {
                    val metadata = androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(stationName)
                        .setArtist("在线直播")
                        .build()

                    val mediaItem = androidx.media3.common.MediaItem.Builder()
                        .setUri(streamUrl)
                        .setMediaMetadata(metadata)
                        .build()

                    mediaController?.setMediaItem(mediaItem)
                    mediaController?.prepare()
                    mediaController?.play()

                    // 只更新 UI，不控制网页 audio
                    myWebView.post {
                        myWebView.evaluateJavascript(
                            "javascript:(function() { " +
                                    "var statusText = document.getElementById('play-status'); " +
                                    "if(statusText) statusText.innerText = '正在直播'; " +
                                    "var masterBtn = document.getElementById('master-play-btn'); " +
                                    "if(masterBtn) masterBtn.icon = 'pause'; " +
                                    "var waveAnim = document.getElementById('playing-anim'); " +
                                    "if(waveAnim) waveAnim.style.display = 'flex'; " +
                                    "})()", null
                        )
                    }

                    Log.d("BaiponBridge", "直接播放成功 - 名称: $stationName, URL: $streamUrl")
                }
            }
        }

        @JavascriptInterface
        fun onStationChanged(stationName: String, stationUrl: String) {
            Log.d("BaiponBridge", "onStationChanged 被调用: $stationName - $stationUrl")
            playStream(stationUrl, stationName)
        }

        @JavascriptInterface
        fun pauseStream() {
            Log.d("BaiponBridge", "pauseStream 被调用")
            mainScope.launch {
                mediaController?.pause()
                // 不要手动控制网页 audio，让状态观察器去同步
                // 只更新 UI 文字和按钮图标
                myWebView.post {
                    myWebView.evaluateJavascript(
                        "javascript:(function() { " +
                                "var statusText = document.getElementById('play-status'); " +
                                "if(statusText) statusText.innerText = '已暂停'; " +
                                "var masterBtn = document.getElementById('master-play-btn'); " +
                                "if(masterBtn) masterBtn.icon = 'play_arrow'; " +
                                "var waveAnim = document.getElementById('playing-anim'); " +
                                "if(waveAnim) waveAnim.style.display = 'none'; " +
                                "})()", null
                    )
                }
            }
        }
    }

    // 统一更新网页 UI 的方法
    private fun updateWebViewUI(isPlaying: Boolean, stationName: String?) {
        myWebView.post {
            val jsCode = if (isPlaying) {
                "javascript:(function() { " +
                        "var statusText = document.getElementById('play-status'); " +
                        "if(statusText) statusText.innerText = '正在直播'; " +
                        "var masterBtn = document.getElementById('master-play-btn'); " +
                        "if(masterBtn) masterBtn.icon = 'pause'; " +
                        "var waveAnim = document.getElementById('playing-anim'); " +
                        "if(waveAnim) waveAnim.style.display = 'flex'; " +
                        "})()"
            } else {
                "javascript:(function() { " +
                        "var statusText = document.getElementById('play-status'); " +
                        "if(statusText) statusText.innerText = '已暂停'; " +
                        "var masterBtn = document.getElementById('master-play-btn'); " +
                        "if(masterBtn) masterBtn.icon = 'play_arrow'; " +
                        "var waveAnim = document.getElementById('playing-anim'); " +
                        "if(waveAnim) waveAnim.style.display = 'none'; " +
                        "})()"
            }
            myWebView.evaluateJavascript(jsCode, null)
            Log.d("BaiponBridge", "更新网页 UI: ${if(isPlaying) "播放" else "暂停"}")
        }
    }

    private suspend fun ensureMediaControllerConnected(action: suspend () -> Unit) {
        return withContext(Dispatchers.Main) {
            if (mediaController != null) {
                action()
            } else if (!mediaControllerInitialized) {
                connectMediaController()
                delay(500)
                if (mediaController != null) {
                    action()
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
        startPlayStateObserver()
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

    // 监听播放状态变化，只更新 UI，不控制网页 audio 元素
    private fun startPlayStateObserver() {
        mainScope.launch {
            var lastState = false
            var lastStation = ""
            while (true) {
                delay(500)
                mediaController?.let { controller ->
                    val isPlaying = controller.isPlaying
                    val mediaItem = controller.currentMediaItem
                    val stationName = mediaItem?.mediaMetadata?.title?.toString() ?: ""

                    // 当状态或电台改变时更新 UI
                    if (lastState != isPlaying || lastStation != stationName) {
                        lastState = isPlaying
                        lastStation = stationName

                        myWebView.post {
                            val jsCode = if (isPlaying) {
                                "javascript:(function() { " +
                                        "var statusText = document.getElementById('play-status'); " +
                                        "if(statusText) statusText.innerText = '正在直播'; " +
                                        "var masterBtn = document.getElementById('master-play-btn'); " +
                                        "if(masterBtn) masterBtn.icon = 'pause'; " +
                                        "var waveAnim = document.getElementById('playing-anim'); " +
                                        "if(waveAnim) waveAnim.style.display = 'flex'; " +
                                        "})()"
                            } else {
                                "javascript:(function() { " +
                                        "var statusText = document.getElementById('play-status'); " +
                                        "if(statusText) statusText.innerText = '已暂停'; " +
                                        "var masterBtn = document.getElementById('master-play-btn'); " +
                                        "if(masterBtn) masterBtn.icon = 'play_arrow'; " +
                                        "var waveAnim = document.getElementById('playing-anim'); " +
                                        "if(waveAnim) waveAnim.style.display = 'none'; " +
                                        "})()"
                            }
                            myWebView.evaluateJavascript(jsCode, null)
                            Log.d("BaiponBridge", "同步 UI 状态: ${if(isPlaying) "播放" else "暂停"}, 电台: $stationName")
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            if (::controllerFuture.isInitialized && mediaControllerInitialized) {
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

            override fun onPageFinished(view: WebView?, url: String?) {
                val script = """
        (function() {
            console.log('Baipon Radio Bridge injected');
            
            // 保存当前选中的电台
            var currentStation = null;
            
            // 覆盖全局的 play 函数
            window.play = function(s, el) {
                console.log('拦截 play 调用: ' + s.name);
                currentStation = s;
                
                // 更新 UI 状态
                document.querySelectorAll('.radio-item').forEach(i => i.classList.remove('active'));
                if(el) el.classList.add('active');
                document.getElementById('current-name').innerText = s.name;
                
                // 更新 logo
                var logoBox = document.getElementById('player-logo');
                if (logoBox) {
                    logoBox.innerHTML = '<div class="dynamic-logo">' + (s.logo ? '<img src="' + s.logo + '" class="dynamic-logo">' : s.name.charAt(0)) + '</div>';
                }
                
                // 通知原生播放
                if (window.AndroidBridge && window.AndroidBridge.playStream) {
                    console.log('通知原生播放: ' + s.name + ' - ' + s.url);
                    window.AndroidBridge.playStream(s.url, s.name);
                }
                
                // 更新播放状态显示
                var statusText = document.getElementById('play-status');
                if (statusText) statusText.innerText = "连接中...";
            };
            
            // 覆盖播放/暂停按钮的事件
            var masterBtn = document.getElementById('master-play-btn');
            if (masterBtn) {
                var newBtn = masterBtn.cloneNode(true);
                masterBtn.parentNode.replaceChild(newBtn, masterBtn);
                newBtn.onclick = function() {
                    console.log('网页按钮被点击');
                    if (window.AndroidBridge) {
                        // 查看当前原生播放状态（通过 UI 判断）
                        var statusText = document.getElementById('play-status');
                        var isPlaying = statusText && statusText.innerText === '正在直播';
                        
                        if (isPlaying) {
                            // 正在播放，执行暂停
                            console.log('执行暂停');
                            window.AndroidBridge.pauseStream();
                        } else {
                            // 已暂停，执行播放
                            if (currentStation) {
                                console.log('播放当前电台: ' + currentStation.name);
                                window.play(currentStation, document.querySelector('.radio-item.active'));
                            } else {
                                var activeItem = document.querySelector('.radio-item.active');
                                if (activeItem && activeItem.dataset.info) {
                                    var station = JSON.parse(activeItem.dataset.info);
                                    console.log('播放选中电台: ' + station.name);
                                    window.play(station, activeItem);
                                }
                            }
                        }
                    }
                };
            }
            
            // 记录当前选中的电台
            var activeItem = document.querySelector('.radio-item.active');
            if (activeItem && activeItem.dataset.info) {
                currentStation = JSON.parse(activeItem.dataset.info);
                console.log('当前选中电台: ' + currentStation.name);
            }
            
            console.log('Bridge 注入完成');
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