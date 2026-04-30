package com.baipon.radio

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutionException

@OptIn(UnstableApi::class)
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
        fun playStream(streamUrl: String, stationName: String, logoUrl: String = "") {
            Log.d("BaiponBridge", "playStream 被调用: $stationName - $streamUrl - Logo: $logoUrl")

            // 立即显示缓冲状态
            myWebView.post {
                myWebView.evaluateJavascript(
                    "javascript:(function() { " +
                            "var statusText = document.getElementById('play-status'); " +
                            "if(statusText) statusText.innerText = '连接中...'; " +
                            "var masterBtn = document.getElementById('master-play-btn'); " +
                            "if(masterBtn) masterBtn.icon = 'pause'; " +
                            "var waveAnim = document.getElementById('playing-anim'); " +
                            "if(waveAnim) waveAnim.style.display = 'flex'; " +
                            "})()", null
                )
            }

            mainScope.launch {
                ensureMediaControllerConnected {
                    val metadataBuilder = MediaMetadata.Builder()
                        .setTitle(stationName)
                        .setArtist("百品电台")

                    // 只有当 logoUrl 有效且不是空字符串时才尝试加载
                    if (logoUrl.isNotEmpty() && logoUrl.startsWith("http")) {
                        try {
                            val bitmap = withContext(Dispatchers.IO) {
                                val url = URL(logoUrl)
                                val connection = url.openConnection()
                                connection.connectTimeout = 5000
                                connection.doInput = true
                                val inputStream = connection.getInputStream()
                                android.graphics.BitmapFactory.decodeStream(inputStream)
                            }
                            if (bitmap != null) {
                                metadataBuilder.setArtworkData(bitmap.toByteArray(), 1)
                            } else {
                                Log.d("BaiponBridge", "图片解码失败，使用默认图标")
                            }
                        } catch (e: Exception) {
                            Log.d("BaiponBridge", "加载 Logo 失败: ${e.message}，使用默认图标")
                        }
                    } else {
                        Log.d("BaiponBridge", "无有效 Logo URL，使用默认图标")
                    }

                    val metadata = metadataBuilder.build()

                    val mediaItem = MediaItem.Builder()
                        .setUri(streamUrl)
                        .setMediaMetadata(metadata)
                        .build()

                    mediaController?.setMediaItem(mediaItem)
                    mediaController?.prepare()
                    mediaController?.play()

                    Log.d("BaiponBridge", "播放成功 - 名称: $stationName, URL: $streamUrl")
                }
            }
        }

        @JavascriptInterface
        fun onStationChanged(stationName: String, stationUrl: String, logoUrl: String = "") {
            Log.d("BaiponBridge", "onStationChanged 被调用: $stationName - $stationUrl - Logo: $logoUrl")
            playStream(stationUrl, stationName, logoUrl)
        }

        @JavascriptInterface
        fun pauseStream() {
            Log.d("BaiponBridge", "pauseStream 被调用")
            mainScope.launch {
                mediaController?.pause()
            }
        }
    }

    // 将 Bitmap 转换为 ByteArray
    private fun Bitmap.toByteArray(): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        this.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val windowController = WindowInsetsControllerCompat(window, window.decorView)
            // 根据当前是否是深色模式切换图标颜色
            val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            // 如果不是深色模式（即白天模式），则将状态栏文字设为黑色（true）
            windowController.isAppearanceLightStatusBars = !isNightMode
        }

        setupWebView()

        // 绑定 FAB 并设置点击事件
        findViewById<FloatingActionButton>(R.id.fab_settings).setOnClickListener { view ->
            showFabMenu(view)
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

                mediaController?.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        Log.d("BaiponBridge", "PlaybackState 改变: $playbackState")
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        Log.d("BaiponBridge", "isPlaying 改变: $isPlaying")
                    }
                })

            } catch (e: ExecutionException) {
                Log.e("BaiponBridge", "MediaController 连接失败", e)
                mediaControllerInitialized = false
            } catch (e: java.util.concurrent.CancellationException) {
                Log.w("BaiponBridge", "MediaController 任务被取消")
                mediaControllerInitialized = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startPlayStateObserver() {
        mainScope.launch {
            var lastState = -1
            var lastStation = ""
            while (true) {
                delay(300)
                mediaController?.let { controller ->
                    val playbackState = controller.playbackState
                    val isPlaying = controller.isPlaying
                    val mediaItem = controller.currentMediaItem
                    val stationName = mediaItem?.mediaMetadata?.title?.toString() ?: ""

                    val currentState = when {
                        playbackState == Player.STATE_BUFFERING -> 2
                        isPlaying -> 1
                        else -> 0
                    }

                    if (lastState != currentState || lastStation != stationName) {
                        lastState = currentState
                        lastStation = stationName

                        myWebView.post {
                            val jsCode = when (currentState) {
                                1 -> "javascript:(function() { " +
                                        "var statusText = document.getElementById('play-status'); " +
                                        "if(statusText) statusText.innerText = '正在直播'; " +
                                        "var masterBtn = document.getElementById('master-play-btn'); " +
                                        "if(masterBtn) masterBtn.icon = 'pause'; " +
                                        "var waveAnim = document.getElementById('playing-anim'); " +
                                        "if(waveAnim) waveAnim.style.display = 'flex'; " +
                                        "})()"
                                2 -> "javascript:(function() { " +
                                        "var statusText = document.getElementById('play-status'); " +
                                        "if(statusText) statusText.innerText = '缓冲中...'; " +
                                        "var masterBtn = document.getElementById('master-play-btn'); " +
                                        "if(masterBtn) masterBtn.icon = 'pause'; " +
                                        "var waveAnim = document.getElementById('playing-anim'); " +
                                        "if(waveAnim) waveAnim.style.display = 'flex'; " +
                                        "})()"
                                else -> "javascript:(function() { " +
                                        "var statusText = document.getElementById('play-status'); " +
                                        "if(statusText) statusText.innerText = '已暂停'; " +
                                        "var masterBtn = document.getElementById('master-play-btn'); " +
                                        "if(masterBtn) masterBtn.icon = 'play_arrow'; " +
                                        "var waveAnim = document.getElementById('playing-anim'); " +
                                        "if(waveAnim) waveAnim.style.display = 'none'; " +
                                        "})()"
                            }
                            myWebView.evaluateJavascript(jsCode, null)
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
        myWebView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("百品电台")  // 修改弹窗标题
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result?.confirm() }
                    .setCancelable(false)
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("百品电台")  // 修改弹窗标题
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result?.confirm() }
                    .setNegativeButton("取消") { _, _ -> result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                val input = android.widget.EditText(this@MainActivity)
                input.setText(defaultValue)

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("百品电台")  // 修改弹窗标题
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton("确定") { _, _ ->
                        result?.confirm(input.text.toString())
                    }
                    .setNegativeButton("取消") { _, _ ->
                        result?.cancel()
                    }
                    .show()
                return true
            }
        }

        myWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                return if (url.contains("radio.baipon.com")) {
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
        console.log('Baipon Radio Bridge injecting...');
        
        // ========== 1. 只隐藏导航栏右侧的管理按钮，不影响播放器 ==========
        // 原网页: .nav-content > div:last-child 包含了 settings, add, refresh, theme-toggle 按钮
        var navRightButtons = document.querySelector('.nav-content > div:last-child');
        if (navRightButtons) {
            navRightButtons.style.display = 'none';
            console.log('已隐藏导航栏右侧按钮组');
        }
        
        // ========== 2. 隐藏音量控件（不影响收藏按钮）==========
        var volumeBox = document.querySelector('.volume-box');
        if (volumeBox) {
            volumeBox.style.display = 'none';
            console.log('已隐藏音量控件');
        }
        
        // ========== 3. 修复播放器布局（保持收藏按钮可见）==========
        var playerCard = document.querySelector('.player-card');
        if (playerCard) {
            playerCard.style.flexWrap = 'wrap';
            playerCard.style.gap = '10px';
            // 确保播放器卡片内所有元素都可见
            playerCard.style.display = 'flex';
            playerCard.style.alignItems = 'center';
        }
        
        // 让标题区域可以换行，但不影响收藏按钮
        var titleContainer = document.querySelector('.player-card > div:first-of-type + div');
        if (titleContainer) {
            titleContainer.style.flex = '1';
            titleContainer.style.minWidth = '100px';
            // 确保收藏按钮在标题容器内可见
            var favBtnInTitle = titleContainer.querySelector('#fav-btn-main');
            if (favBtnInTitle) {
                favBtnInTitle.style.display = 'inline-flex';
            }
        }
        
        var currentNameSpan = document.getElementById('current-name');
        if (currentNameSpan) {
            currentNameSpan.style.whiteSpace = 'normal';
            currentNameSpan.style.wordBreak = 'break-word';
            currentNameSpan.style.fontSize = '1rem';
        }
        
        // ========== 4. 强制确保收藏按钮可见 ==========
        function ensureFavoriteButtonVisible() {
            var favBtn = document.getElementById('fav-btn-main');
            if (favBtn) {
                favBtn.style.display = 'inline-flex !important';
                favBtn.style.visibility = 'visible';
                favBtn.style.opacity = '1';
                favBtn.style.position = 'relative';
                favBtn.style.zIndex = '100';
                console.log('已强制显示收藏按钮');
                return true;
            } else {
                console.log('未找到收藏按钮元素');
                return false;
            }
        }
        
        // ========== 5. 添加 CSS 强制规则 ==========
        var style = document.createElement('style');
        style.textContent = `
            #fav-btn-main {
                display: inline-flex !important;
                visibility: visible !important;
                opacity: 1 !important;
                pointer-events: auto !important;
            }
            .player-card #fav-btn-main {
                display: inline-flex !important;
            }
        `;
        document.head.appendChild(style);
        
        // ========== 6. 保存当前选中的电台 ==========
        var currentStation = null;
        var activeItem = document.querySelector('.radio-item.active');
        if (activeItem && activeItem.dataset.info) {
            try {
                currentStation = JSON.parse(activeItem.dataset.info);
                console.log('当前选中电台: ' + currentStation.name);
            } catch(e) {}
        }
        
        // ========== 7. 覆盖 play 函数 ==========
        window.play = function(s, el) {
            console.log('play 被调用: ' + s.name);
            currentStation = s;
            
            // 更新 UI
            document.querySelectorAll('.radio-item').forEach(function(i) {
                i.classList.remove('active');
            });
            if (el) el.classList.add('active');
            var nameSpan = document.getElementById('current-name');
            if (nameSpan) nameSpan.innerText = s.name;
            
            var logoBox = document.getElementById('player-logo');
            if (logoBox) {
                logoBox.innerHTML = '<div class="dynamic-logo">' + (s.logo ? '<img src="' + s.logo + '" class="dynamic-logo">' : s.name.charAt(0)) + '</div>';
            }
            
            var statusText = document.getElementById('play-status');
            if (statusText) statusText.innerText = "连接中...";
            
            // 更新收藏按钮状态
            setTimeout(function() {
                var favBtn = document.getElementById('fav-btn-main');
                if (favBtn && s) {
                    var favorites = JSON.parse(localStorage.getItem('bp_radios_favs') || '[]');
                    var isFav = favorites.includes(s.url);
                    favBtn.icon = isFav ? 'favorite' : 'favorite_border';
                    ensureFavoriteButtonVisible();
                }
            }, 50);
            
            // 通知原生播放
            if (window.AndroidBridge && window.AndroidBridge.playStream) {
                window.AndroidBridge.playStream(s.url, s.name, s.logo || "");
            }
        };
        
        // ========== 8. 重新绑定播放按钮 ==========
        var masterBtn = document.getElementById('master-play-btn');
        if (masterBtn) {
            var newBtn = masterBtn.cloneNode(true);
            masterBtn.parentNode.replaceChild(newBtn, masterBtn);
            masterBtn = newBtn;
            
            masterBtn.onclick = function() {
                console.log('播放按钮被点击');
                if (window.AndroidBridge) {
                    var statusText = document.getElementById('play-status');
                    var isPlaying = statusText && (statusText.innerText === '正在直播' || statusText.innerText === '缓冲中...');
                    
                    if (isPlaying) {
                        console.log('调用暂停');
                        window.AndroidBridge.pauseStream();
                    } else {
                        console.log('调用播放');
                        if (currentStation) {
                            window.play(currentStation, document.querySelector('.radio-item.active'));
                        } else {
                            var activeItem = document.querySelector('.radio-item.active');
                            if (activeItem && activeItem.dataset.info) {
                                try {
                                    var station = JSON.parse(activeItem.dataset.info);
                                    window.play(station, activeItem);
                                } catch(e) {}
                            }
                        }
                    }
                }
            };
            console.log('播放按钮已重新绑定');
        }
        
        // ========== 9. 重新绑定收藏按钮（添加自动刷新）==========
        function rebindFavorite() {
            var favBtn = document.getElementById('fav-btn-main');
            if (favBtn) {
                // 移除原有事件，重新绑定
                var newFavBtn = favBtn.cloneNode(true);
                favBtn.parentNode.replaceChild(newFavBtn, favBtn);
                favBtn = newFavBtn;
                
                favBtn.onclick = function(e) {
                    e.preventDefault();
                    e.stopPropagation();
                    console.log('收藏按钮被点击');
                    
                    if (currentStation) {
                        var favorites = JSON.parse(localStorage.getItem('bp_radios_favs') || '[]');
                        var index = favorites.indexOf(currentStation.url);
                        
                        if (index > -1) {
                            favorites.splice(index, 1);
                            favBtn.icon = 'favorite_border';
                            console.log('已取消收藏: ' + currentStation.name);
                            // 显示提示
                            if (window.AndroidBridge && window.AndroidBridge.showToast) {
                                window.AndroidBridge.showToast('已取消收藏: ' + currentStation.name);
                            }
                        } else {
                            favorites.push(currentStation.url);
                            favBtn.icon = 'favorite';
                            console.log('已添加收藏: ' + currentStation.name);
                            if (window.AndroidBridge && window.AndroidBridge.showToast) {
                                window.AndroidBridge.showToast('已添加收藏: ' + currentStation.name);
                            }
                        }
                        
                        localStorage.setItem('bp_radios_favs', JSON.stringify(favorites));
                        
                        // ========== 关键修改：收藏后自动刷新列表 ==========
                        if (typeof refresh === 'function') {
                            // 保存当前分类
                            var currentFilterBackup = window.currentFilter;
                            // 刷新列表
                            refresh();
                            // 恢复当前播放的电台高亮
                            setTimeout(function() {
                                if (currentStation) {
                                    var items = document.querySelectorAll('.radio-item');
                                    for (var i = 0; i < items.length; i++) {
                                        var item = items[i];
                                        if (item.dataset.info) {
                                            try {
                                                var station = JSON.parse(item.dataset.info);
                                                if (station.url === currentStation.url) {
                                                    item.classList.add('active');
                                                    break;
                                                }
                                            } catch(e) {}
                                        }
                                    }
                                }
                                // 重新绑定按钮
                                rebindFavorite();
                            }, 100);
                        }
                    } else {
                        console.log('请先选择一个电台');
                        if (window.AndroidBridge && window.AndroidBridge.showToast) {
                            window.AndroidBridge.showToast('请先选择一个电台');
                        }
                    }
                };
                
                ensureFavoriteButtonVisible();
                console.log('收藏按钮已重新绑定');
            }
        }
        
        // ========== 10. 初始化 ==========
        setTimeout(function() {
            ensureFavoriteButtonVisible();
            rebindFavorite();
            // 每隔2秒检查一次，确保收藏按钮不会被隐藏
            setInterval(function() {
                var favBtn = document.getElementById('fav-btn-main');
                if (favBtn && (favBtn.style.display === 'none' || getComputedStyle(favBtn).display === 'none')) {
                    favBtn.style.display = 'inline-flex';
                    console.log('检测到收藏按钮被隐藏，已恢复显示');
                }
            }, 2000);
        }, 500);
        
        // ========== 11. 暴露全局方法 ==========
        window.showAddStationDialog = function() {
            var addBtn = document.getElementById('open-add');
            if (addBtn) addBtn.click();
        };
        
        window.refreshStations = function() {
            var refreshBtn = document.getElementById('refresh');
            if (refreshBtn) refreshBtn.click();
            setTimeout(function() {
                rebindFavorite();
                ensureFavoriteButtonVisible();
            }, 300);
        };
        
        window.toggleTheme = function() {
            var themeBtn = document.getElementById('theme-toggle');
            if (themeBtn) themeBtn.click();
        };
        
        window.showAdminMode = function() {
            var adminBtn = document.getElementById('toggle-admin');
            if (adminBtn) adminBtn.click();
        };
        
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

    /**
     * 显示 Fab 菜单，模仿 Speed Dial 样式
     */
    // 1. 在类中定义一个变量记录状态
    private var isAdminMode = false

    // 2. 更新 showFabMenu 方法
    private fun showFabMenu(anchorView: View) {
        val popupMenu = PopupMenu(this, anchorView)

        // 使用刚才定义的 XML 菜单
        popupMenu.menuInflater.inflate(R.menu.fab_menu, popupMenu.menu)

        // --- 动态控制显示逻辑 ---
        val menu = popupMenu.menu

        // 如果是管理模式：显示“退出编辑”，隐藏“管理模式”
        menu.findItem(R.id.menu_exit_admin).isVisible = isAdminMode
        menu.findItem(R.id.menu_admin).isVisible = !isAdminMode

        // 为了美观，管理模式下也可以隐藏设置或添加逻辑，视需求而定
        // -----------------------

        // 强制显示图标 (保持之前的反射代码)
        try {
            val field = PopupMenu::class.java.getDeclaredField("mPopup")
            field.isAccessible = true
            val menuPopupHelper = field.get(popupMenu)
            val setForceShowIcon = menuPopupHelper.javaClass.getDeclaredMethod("setForceShowIcon", Boolean::class.java)
            setForceShowIcon.invoke(menuPopupHelper, true)
        } catch (e: Exception) {
            Log.e("BaiponBridge", "Set Icon Failed", e)
        }

        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_add_station -> {
                    myWebView.evaluateJavascript("javascript:window.showAddStationDialog()", null)
                    true
                }
                R.id.menu_refresh -> {
                    myWebView.evaluateJavascript("javascript:window.refreshStations()", null)
                    true
                }
                R.id.menu_theme -> {
                    myWebView.evaluateJavascript("javascript:window.toggleTheme()", null)
                    true
                }
                R.id.menu_admin -> {
                    isAdminMode = true // 标记进入
                    myWebView.evaluateJavascript("javascript:window.showAdminMode()", null)
                    Toast.makeText(this, "已进入编辑模式，再次点击以退出", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.menu_exit_admin -> {
                    isAdminMode = false // 标记退出
                    // 假设网页端有对应的退出方法，如果没有，可以重新调用 showAdminMode 切换回去
                    myWebView.evaluateJavascript("javascript:window.showAdminMode()", null)
                    Toast.makeText(this, "已退出编辑模式", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.menu_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
        popupMenu.show()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("check_update", false) == true) {
            checkUpdate(isManual = true)
        }
    }

    override fun onResume() {
        super.onResume()
        if (intent?.getBooleanExtra("check_update", false) == true) {
            intent.removeExtra("check_update")
            checkUpdate(isManual = true)
        }
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