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

    // MDUI 风格错误页面（使用函数确保 getString 可用）
    private fun getErrorHtmlContent(): String {
        return """
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
                <h1>${getString(R.string.signal_lost)}</h1>
                <p>${getString(R.string.error_message)}</p>
                <button class="mdui-btn" onclick="Android.retry()">${getString(R.string.retry)}</button>
            </div>
        </body>
        </html>
    """.trimIndent()
    }

    // 网页与原生交互桥接
    inner class WebAppInterface {
        @JavascriptInterface
        fun retry() {
            runOnUiThread { myWebView.loadUrl(webUrl) }
        }

        @JavascriptInterface
        fun playStream(streamUrl: String, stationName: String, logoUrl: String = "") {
            Log.d("BaiponBridge", "playStream 被调用: $stationName - $streamUrl - Logo: $logoUrl")

            mainScope.launch {
                ensureMediaControllerConnected {
                    val metadataBuilder = MediaMetadata.Builder()
                        .setTitle(stationName)
                        .setArtist(getString(R.string.app_name))

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
                            }
                        } catch (e: Exception) {
                            Log.d("BaiponBridge", "加载 Logo 失败: ${e.message}")
                        }
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
                    .setTitle(getString(R.string.notification_permission_title))
                    .setMessage(getString(R.string.notification_permission_message))
                    .setPositiveButton(getString(R.string.go_grant)) { _, _ ->
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val savedLanguage = LocaleHelper.getSavedLanguage(newBase)
        val languageCode = savedLanguage ?: LocaleHelper.getCurrentLanguage(newBase)
        super.attachBaseContext(LocaleHelper.setLocale(newBase, languageCode))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindowDisplay()
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val windowController = WindowInsetsControllerCompat(window, window.decorView)
            val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            windowController.isAppearanceLightStatusBars = !isNightMode
        }

        setupWebView()

        // 恢复 WebView 状态
        if (savedInstanceState != null) {
            myWebView.restoreState(savedInstanceState)
        }

        // 绑定 FAB 并设置点击事件
        findViewById<FloatingActionButton>(R.id.fab_settings).setOnClickListener { view ->
            showFabMenu(view)
        }

        setupBackNavigation()
        checkUpdate(isManual = false)
        requestNotificationPermission()

        startPlaybackService()

        // 检查是否需要重新加载 WebView
        if (intent?.getBooleanExtra("reload_webview", false) == true) {
            myWebView.reload()  // 重新加载当前页面
            intent.removeExtra("reload_webview")
        }
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
            var lastIsPlaying = false
            var lastStation = ""
            while (true) {
                delay(200)
                try {
                    mediaController?.let { controller ->
                        val isPlaying = controller.isPlaying
                        val playbackState = controller.playbackState
                        val mediaItem = controller.currentMediaItem
                        val stationName = mediaItem?.mediaMetadata?.title?.toString() ?: ""

                        if (lastIsPlaying != isPlaying || lastStation != stationName) {
                            lastIsPlaying = isPlaying
                            lastStation = stationName
                            Log.d("BaiponBridge", "UI状态更新: isPlaying=$isPlaying, station=$stationName")

                            myWebView.post {
                                try {
                                    val jsCode = when {
                                        playbackState == Player.STATE_BUFFERING -> """
                                        (function() {
                                            var statusText = document.getElementById('play-status');
                                            if(statusText) statusText.innerText = '${getString(R.string.status_buffering)}';
                                            var masterBtn = document.getElementById('master-play-btn');
                                            if(masterBtn) masterBtn.icon = 'pause';
                                            var waveAnim = document.getElementById('playing-anim');
                                            if(waveAnim) waveAnim.style.display = 'flex';
                                        })();
                                    """.trimIndent()
                                        isPlaying -> """
                                        (function() {
                                            var statusText = document.getElementById('play-status');
                                            if(statusText) statusText.innerText = '${getString(R.string.status_playing)}';
                                            var masterBtn = document.getElementById('master-play-btn');
                                            if(masterBtn) masterBtn.icon = 'pause';
                                            var waveAnim = document.getElementById('playing-anim');
                                            if(waveAnim) waveAnim.style.display = 'flex';
                                        })();
                                    """.trimIndent()
                                        else -> """
                                        (function() {
                                            var statusText = document.getElementById('play-status');
                                            if(statusText) statusText.innerText = '${getString(R.string.status_paused)}';
                                            var masterBtn = document.getElementById('master-play-btn');
                                            if(masterBtn) masterBtn.icon = 'play_arrow';
                                            var waveAnim = document.getElementById('playing-anim');
                                            if(waveAnim) waveAnim.style.display = 'none';
                                        })();
                                    """.trimIndent()
                                    }
                                    myWebView.evaluateJavascript(jsCode, null)
                                } catch (e: Exception) {
                                    Log.e("BaiponBridge", "更新UI失败: ${e.message}")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("BaiponBridge", "状态观察器异常: ${e.message}")
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
            cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
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
                    .setTitle(getString(R.string.app_name))
                    .setMessage(message)
                    .setPositiveButton(getString(R.string.ok)) { _, _ -> result?.confirm() }
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
                    .setTitle(getString(R.string.app_name))
                    .setMessage(message)
                    .setPositiveButton(getString(R.string.ok)) { _, _ -> result?.confirm() }
                    .setNegativeButton(getString(R.string.cancel)) { _, _ -> result?.cancel() }
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
                    .setTitle(getString(R.string.app_name))
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton(getString(R.string.ok)) { _, _ ->
                        result?.confirm(input.text.toString())
                    }
                    .setNegativeButton(getString(R.string.cancel)) { _, _ ->
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
                        Toast.makeText(this@MainActivity, getString(R.string.cannot_open_link), Toast.LENGTH_SHORT).show()
                    }
                    true
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    myWebView.loadDataWithBaseURL(null, getErrorHtmlContent(), "text/html", "UTF-8", null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val currentLanguage = LocaleHelper.getCurrentLanguage(this@MainActivity)
                val script = """
                                (function() {
                console.log('Baipon Radio Bridge injecting...');
                
                // 设置当前语言
                window.appLanguage = '$currentLanguage';
                console.log('App 语言: ' + window.appLanguage);
                
                // ========== 隐藏网页中的语言切换按钮（因为 App 已有语言设置）==========
                var langSelector = document.getElementById('language-selector');
                if (langSelector) {
                    langSelector.style.display = 'none';
                    console.log('已隐藏网页中的语言切换按钮');
                }
                
                // 通过 CSS 隐藏语言选择器
                var style2 = document.createElement('style');
                style2.textContent = `
                    #language-selector,
                    #language-switch-btn,
                    .language-selector {
                        display: none !important;
                    }
                `;
                document.head.appendChild(style2);
                
                // ========== 1. 隐藏导航栏右侧的管理按钮（但保留搜索按钮）==========
                var navRightButtons = document.querySelector('.nav-content > div:last-child');
                if (navRightButtons) {
                    // 获取所有按钮
                    var buttons = navRightButtons.querySelectorAll('mdui-button-icon');
                    buttons.forEach(function(btn) {
                        var iconName = btn.getAttribute('icon');
                        // 保留搜索按钮，隐藏其他按钮
                        if (iconName !== 'search') {
                            btn.style.display = 'none';
                            console.log('已隐藏按钮: ' + iconName);
                        } else {
                            console.log('保留搜索按钮');
                        }
                    });
                    
                    // 额外处理：如果还有其他非 mdui-button-icon 的元素（比如原生按钮），也处理
                    var otherBtns = navRightButtons.querySelectorAll('button:not(mdui-button-icon)');
                    otherBtns.forEach(function(btn) {
                        btn.style.display = 'none';
                    });
                    
                    console.log('导航栏右侧按钮处理完成（保留搜索按钮）');
                }
                
                // ========== 2. 隐藏音量控件 ==========
                var volumeBox = document.querySelector('.volume-box');
                if (volumeBox) {
                    volumeBox.style.display = 'none';
                    console.log('已隐藏音量控件');
                }
                
                // ========== 3. 修复播放器布局 ==========
                var playerCard = document.querySelector('.player-card');
                if (playerCard) {
                    playerCard.style.flexWrap = 'wrap';
                    playerCard.style.gap = '10px';
                    playerCard.style.display = 'flex';
                    playerCard.style.alignItems = 'center';
                }
                
                var titleContainer = document.querySelector('.player-card > div:first-of-type + div');
                if (titleContainer) {
                    titleContainer.style.flex = '1';
                    titleContainer.style.minWidth = '100px';
                }
                
                var currentNameSpan = document.getElementById('current-name');
                if (currentNameSpan) {
                    currentNameSpan.style.whiteSpace = 'normal';
                    currentNameSpan.style.wordBreak = 'break-word';
                    currentNameSpan.style.fontSize = '1rem';
                }
                
                // ========== 4. 添加 CSS 强制规则 ==========
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
                
                // ========== 5. 全局变量 ==========
                var currentStation = null;
                
                // 获取当前选中的电台
                var activeItem = document.querySelector('.radio-item.active');
                if (activeItem && activeItem.dataset.info) {
                    try {
                        currentStation = JSON.parse(activeItem.dataset.info);
                        console.log('当前选中电台: ' + currentStation.name);
                    } catch(e) {
                        console.error('解析当前电台失败:', e);
                    }
                }
                
                // ========== 6. 保存原始函数 ==========
                var originalPlay = window.play;
                
                // ========== 7. 覆盖 play 函数 ==========
                window.play = function(s, el) {
                    console.log('play 被调用: ' + (s && s.name));
                    currentStation = s;
                    
                    // 调用原始play函数（更新网页UI）
                    if (originalPlay && typeof originalPlay === 'function') {
                        try {
                            originalPlay(s, el);
                        } catch(e) {
                            console.error('原始play函数执行失败:', e);
                        }
                    }
                    
                    // 通知原生播放，让Media3播放同一个流
                    if (window.AndroidBridge && window.AndroidBridge.playStream) {
                        window.AndroidBridge.playStream(s.url, s.name, s.logo || "");
                    }
                };
                
                // ========== 8. 重新绑定播放按钮（只绑定一次）==========
                var masterBtn = document.getElementById('master-play-btn');
                if (masterBtn && !masterBtn._hasBridgeListener) {
                    var newBtn = masterBtn.cloneNode(true);
                    if (masterBtn.parentNode) {
                        masterBtn.parentNode.replaceChild(newBtn, masterBtn);
                        masterBtn = newBtn;
                    }
                    
                    masterBtn.onclick = function(e) {
                        e.preventDefault();
                        e.stopPropagation();
                        console.log('播放按钮被点击');
                        
                        if (window.AndroidBridge) {
                            var statusText = document.getElementById('play-status');
                            var isPlaying = statusText && (statusText.innerText === '${getString(R.string.status_playing)}' || statusText.innerText === '${getString(R.string.status_buffering)}');
                            
                            if (isPlaying) {
                                console.log('调用暂停');
                                window.AndroidBridge.pauseStream();
                            } else {
                                console.log('调用播放');
                                if (currentStation) {
                                    if (originalPlay && typeof originalPlay === 'function') {
                                        var activeItem = document.querySelector('.radio-item.active');
                                        originalPlay(currentStation, activeItem);
                                    }
                                } else {
                                    var activeItem = document.querySelector('.radio-item.active');
                                    if (activeItem && activeItem.dataset.info) {
                                        try {
                                            var station = JSON.parse(activeItem.dataset.info);
                                            if (originalPlay && typeof originalPlay === 'function') {
                                                originalPlay(station, activeItem);
                                            }
                                        } catch(e) {
                                            console.error('解析电台失败:', e);
                                        }
                                    }
                                }
                            }
                        }
                    };
                    masterBtn._hasBridgeListener = true;
                    console.log('播放按钮已重新绑定');
                }
                
                // ========== 9. 确保收藏按钮可见 ==========
                function ensureFavoriteButtonVisible() {
                    var favBtn = document.getElementById('fav-btn-main');
                    if (favBtn) {
                        favBtn.style.display = 'inline-flex';
                        favBtn.style.visibility = 'visible';
                        favBtn.style.opacity = '1';
                        return true;
                    }
                    return false;
                }
                
                // ========== 10. 暴露全局方法 ==========
                window.showAddStationDialog = function() {
                    var addBtn = document.getElementById('open-add');
                    if (addBtn) addBtn.click();
                };
                
                window.refreshStations = function() {
                    var refreshBtn = document.getElementById('refresh');
                    if (refreshBtn) refreshBtn.click();
                };
                
                // 修改：切换主题而不刷新页面
                window.toggleTheme = function() {
                    console.log('切换主题（不刷新页面）');
                    
                    var isDark = document.body.classList.contains('mdui-theme-dark');
                    
                    if (isDark) {
                        document.body.classList.remove('mdui-theme-dark');
                        localStorage.setItem('bp_theme_mode', 'light');
                        console.log('切换到浅色模式');
                    } else {
                        document.body.classList.add('mdui-theme-dark');
                        localStorage.setItem('bp_theme_mode', 'dark');
                        console.log('切换到深色模式');
                    }
                    
                    var themeBtn = document.getElementById('theme-toggle');
                    if (themeBtn) {
                        themeBtn.icon = document.body.classList.contains('mdui-theme-dark') ? 'light_mode' : 'dark_mode';
                    }
                };
                
                window.showAdminMode = function() {
                    var adminBtn = document.getElementById('toggle-admin');
                    if (adminBtn) adminBtn.click();
                };
                
                // ========== 11. 初始化 ==========
                setTimeout(function() {
                    try {
                        ensureFavoriteButtonVisible();
                        console.log('Bridge 注入完成');
                    } catch(e) {
                        console.error('初始化失败:', e);
                    }
                }, 500);
                
                // 使用MutationObserver监听DOM变化，确保收藏按钮始终可见
                var observer = new MutationObserver(function(mutations) {
                    mutations.forEach(function(mutation) {
                        if (mutation.type === 'childList' || mutation.type === 'attributes') {
                            var favBtn = document.getElementById('fav-btn-main');
                            if (favBtn && (favBtn.style.display === 'none' || getComputedStyle(favBtn).display === 'none')) {
                                favBtn.style.display = 'inline-flex';
                                console.log('检测到收藏按钮被隐藏，已恢复');
                            }
                        }
                    });
                });
                
                observer.observe(document.body, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['style', 'class']
                });
                
                // ========== 12. 禁用网页原生音频，所有播放由 Android 处理 ==========
                // 获取并清空 audio 元素
                var audioElement = document.getElementById('audio-player');
                if (audioElement) {
                    // 移除所有事件监听器
                    var newAudio = audioElement.cloneNode(true);
                    audioElement.parentNode.replaceChild(newAudio, audioElement);
                    audioElement = newAudio;
                    
                    // 禁用所有播放方法
                    audioElement.play = function() {
                        console.log('网页播放已被禁用，使用 Android 播放器');
                        return Promise.resolve();
                    };
                    audioElement.pause = function() {
                        console.log('网页暂停已被禁用，使用 Android 播放器');
                    };
                    audioElement.load = function() {};
                    audioElement.src = '';
                    
                    console.log('网页 audio 元素已被禁用');
                }

                // 覆盖网页的 loadSource 函数
                if (window.loadSource) {
                    window.loadSource = function(url, isM3U8) {
                        console.log('loadSource 被拦截，使用 Android 播放器播放: ' + url);
                        // 不执行任何播放操作，由 Android 处理
                    };
                }

                // 覆盖音频错误处理
                if (window.audio && window.audio.onerror) {
                    window.audio.onerror = null;
                }
                
            })();
    """.trimIndent()

                view?.evaluateJavascript(script, null)
            }
        }

        // 添加语言参数到 URL
        val currentLanguage = LocaleHelper.getCurrentLanguage(this)
        val urlWithLang = if (webUrl.contains("?")) {
            "$webUrl&lang=$currentLanguage"
        } else {
            "$webUrl?lang=$currentLanguage"
        }
        myWebView.loadUrl(urlWithLang)
    }

    private fun setAppCacheEnabled(bool: Boolean) {}

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        myWebView.saveState(outState)
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

    private var isAdminMode = false

    private fun showFabMenu(anchorView: View) {
        val popupMenu = PopupMenu(this, anchorView)

        popupMenu.menuInflater.inflate(R.menu.fab_menu, popupMenu.menu)

        val menu = popupMenu.menu

        menu.findItem(R.id.menu_exit_admin).isVisible = isAdminMode
        menu.findItem(R.id.menu_admin).isVisible = !isAdminMode

        // ========== 关键：强制刷新所有菜单项的标题 ==========
        menu.findItem(R.id.menu_add_station).title = getString(R.string.add_station)
        menu.findItem(R.id.menu_refresh).title = getString(R.string.refresh_station)
        menu.findItem(R.id.menu_admin).title = getString(R.string.edit_mode)
        menu.findItem(R.id.menu_exit_admin).title = getString(R.string.exit_edit)
        menu.findItem(R.id.menu_settings).title = getString(R.string.settings)

        // 主题切换菜单项需要根据当前模式显示不同文字
        val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val themeItem = menu.findItem(R.id.menu_theme)
        if (isDarkTheme) {
            themeItem.title = getString(R.string.light_mode)
        } else {
            themeItem.title = getString(R.string.dark_mode)
        }


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
                    isAdminMode = true
                    myWebView.evaluateJavascript("javascript:window.showAdminMode()", null)
                    Toast.makeText(this, getString(R.string.edit_mode), Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.menu_exit_admin -> {
                    isAdminMode = false
                    myWebView.evaluateJavascript("javascript:window.showAdminMode()", null)
                    Toast.makeText(this, getString(R.string.exit_edit), Toast.LENGTH_SHORT).show()
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
        myWebView.onResume()
        myWebView.resumeTimers()

        if (intent?.getBooleanExtra("check_update", false) == true) {
            intent.removeExtra("check_update")
            checkUpdate(isManual = true)
        }
    }

    override fun onPause() {
        super.onPause()
        myWebView.onPause()
        myWebView.pauseTimers()
    }

    private fun getAppVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (_: PackageManager.NameNotFoundException) {
        "1.0"
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage(getString(R.string.about_message, getAppVersionName()))
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    private fun checkUpdate(isManual: Boolean) {
        if (isManual) Toast.makeText(this, getString(R.string.checking_update), Toast.LENGTH_SHORT).show()

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

                // 获取多语言更新日志
                val updateLog = getUpdateLogByLanguage(json)

                runOnUiThread {
                    if (serverCode > currentCode) {
                        showUpdateDialog(serverName, updateLog, downloadUrl)
                    } else if (isManual) {
                        Toast.makeText(this, getString(R.string.already_latest), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (isManual) runOnUiThread {
                    Toast.makeText(this, getString(R.string.update_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /**
     * 根据当前语言获取对应的更新日志
     */
    private fun getUpdateLogByLanguage(jsonObject: JSONObject): String {
        return try {
            // 检查 updateLog 是否是 JSONObject
            if (jsonObject.has("updateLog")) {
                val updateLogObj = jsonObject.getJSONObject("updateLog")

                // 获取当前语言
                val currentLang = LocaleHelper.getCurrentLanguage(this)

                // 根据当前语言选择对应的日志
                val logText = when (currentLang) {
                    "zh-CN" -> updateLogObj.optString("zh-CN", "")
                    "zh-TW" -> updateLogObj.optString("zh-TW", "")
                    "zh-HK" -> updateLogObj.optString("zh-HK", "")
                    "en" -> updateLogObj.optString("en", "")
                    else -> updateLogObj.optString("en", "")
                }

                // 如果对应语言的日志为空，尝试使用英文或简体中文
                if (logText.isEmpty()) {
                    val fallback = updateLogObj.optString("en", "")
                    if (fallback.isNotEmpty()) fallback else updateLogObj.optString("zh-CN", getString(R.string.new_version_found))
                } else {
                    logText
                }
            } else {
                // 兼容旧版本 JSON（纯文本格式）
                jsonObject.getString("updateLog")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            jsonObject.optString("updateLog", getString(R.string.new_version_found))
        }
    }

    private fun showUpdateDialog(newName: String, log: String, url: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.new_version_found) + ": $newName")
            .setMessage(log)
            .setPositiveButton(getString(R.string.download_now)) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                } catch (_: Exception) {
                    Toast.makeText(this, getString(R.string.cannot_open_link), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.later), null)
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