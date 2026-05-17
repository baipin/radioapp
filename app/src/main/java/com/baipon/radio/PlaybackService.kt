package com.baipon.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.exoplayer.DefaultLoadControl

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val notificationId = 1
    private val notificationChannelId = "baipon_radio_channel"
    private var currentStationName: String = "百品电台"

    companion object {
        const val COMMAND_PLAY_STREAM = "PLAY_STREAM"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("BaiponBridge", "PlaybackService onCreate 开始")

        // 1. 关键：配置一个支持重定向和 UA 伪装的数据源工厂
        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .setAllowCrossProtocolRedirects(true) // 必须：允许 https -> http 的跳转

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15000, // minBufferMs: 最小缓存15秒的数据（为了抗网络抖动）
                50000, // maxBufferMs: 最大缓存50秒
                1000,  // bufferForPlaybackMs: 🌟 首次播放只需要1秒的数据就立刻出声
                1000   // bufferForPlaybackAfterRebufferMs: 🌟 卡顿后，只要攒够1秒数据就继续播（默认是5000ms，太长了）
            )
            .build()

        // 2. 将数据源工厂注入到 Player 中
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(httpDataSourceFactory)
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setWakeMode(C.WAKE_MODE_NETWORK) // 🌟 关键修复：保持 CPU 和网络在后台运行
            .setHandleAudioBecomingNoisy(true) // 💡 推荐增强：耳机拔出时自动暂停
            .setLoadControl(loadControl)
            .build()

        // 3. (可选) 添加详细日志监听器，帮你定位到底是哪一步断了
        player.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger())

        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d("BaiponBridge", "onIsPlayingChanged: $isPlaying")
                updateNotification()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem != null) {
                    val title = mediaItem.mediaMetadata.title
                    if (title != null) {
                        currentStationName = title.toString()
                        Log.d("BaiponBridge", "更新电台名称: $currentStationName")
                    }
                }
                updateNotification()
            }
            // 🌟 关键修复：处理直播流异常断开和播放结束的假象
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    androidx.media3.common.Player.STATE_ENDED -> {
                        Log.w("BaiponBridge", "直播流意外结束 (可能 EOF)，尝试自动重连...")
                        // 对于直播流，收到 ENDED 重置播放器以重新拉流
                        player.seekToDefaultPosition()
                        player.prepare()
                        player.play()
                    }
                    androidx.media3.common.Player.STATE_BUFFERING -> {
                        Log.d("BaiponBridge", "正在缓冲...")
                    }
                }
            }

            // 🌟 关键修复：处理网络波动导致的流错误
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e("BaiponBridge", "播放器发生错误，尝试恢复: ${error.message}", error)
                // 遇到网络异常时，尝试重新 prepare 并播放
                player.prepare()
                player.play()
            }
        })

        createNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setId("BaiponRadioSession")
            .setSessionActivity(pendingIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .build()

                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(COMMAND_PLAY_STREAM, Bundle.EMPTY))
                        .build()

                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(playerCommands)
                        .setAvailableSessionCommands(sessionCommands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == COMMAND_PLAY_STREAM) {
                        val url = args.getString("url")
                        val name = args.getString("name") ?: "百品电台"
                        Log.d("BaiponBridge", "收到播放指令: $name - $url")
                        if (!url.isNullOrEmpty()) {
                            playStream(url, name)
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()

        startForeground(notificationId, createNotification())
        Log.d("BaiponBridge", "前台服务已启动")
    }

    private fun getAppIcon(): Int {
        // 返回 App 的 launcher icon
        return try {
            val packageManager = packageManager
            val packageName = packageName
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            applicationInfo.icon
        } catch (e: Exception) {
            Log.e("BaiponBridge", "获取 App 图标失败: ${e.message}")
            R.drawable.ic_launcher_foreground // 备用图标
        }
    }

    private fun getDefaultIcon(): Int {
        // 返回默认的通知图标
        return R.drawable.ic_launcher_foreground
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                notificationChannelId,
                "百品电台",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示正在播放的电台节目"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val isPlaying = player.isPlaying
        val mediaItem = player.currentMediaItem
        val title = mediaItem?.mediaMetadata?.title?.toString()

        val displayTitle = if (!title.isNullOrEmpty() && title != "null") {
            title
        } else if (currentStationName != "百品电台") {
            currentStationName
        } else {
            "百品电台"
        }

        val contentText = if (isPlaying) "正在播放" else "已暂停"

        Log.d("BaiponBridge", "通知 - 标题: $displayTitle, 状态: $contentText")

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 添加播放/暂停按钮的 Intent
        val playPauseIntent = Intent(this, PlaybackService::class.java).apply {
            action = if (isPlaying) "PAUSE_ACTION" else "PLAY_ACTION"
        }
        val playPausePendingIntent = PendingIntent.getService(
            this, 0, playPauseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseAction = NotificationCompat.Action.Builder(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isPlaying) "暂停" else "播放",
            playPausePendingIntent
        ).build()

        return NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle(displayTitle)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(playPauseAction)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying)
            .build()
    }

    private fun playStream(url: String, name: String) {
        try {
            Log.d("BaiponBridge", "Service playStream 开始处理: $name - $url")

            currentStationName = name

            val metadata = MediaMetadata.Builder()
                .setTitle(name)
                .setArtist("在线直播")
                .build()

            val mediaItemBuilder = MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(metadata)
                .setMediaMetadata(metadata)
                // 🌟 新增：显式告诉 ExoPlayer 这是直播流
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setMaxPlaybackSpeed(1.02f) // 允许轻微加速（不易察觉）来追赶直播进度，防止延迟越来越大
                        .build()
                )

            // 根据 URL 动态设置正确的 MimeType，让 ExoPlayer 能够正确路由
            val urlLowerCase = url.lowercase()
            when {
                urlLowerCase.contains("type=hls") ||
                        urlLowerCase.contains("type=m3u8") ||
                        urlLowerCase.contains(".m3u8") -> {
                    mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                    Log.d("BaiponBridge", "Service 识别为：HLS (M3U8) 流")
                }
                urlLowerCase.contains("type=aac") ||
                        urlLowerCase.contains(".aac") -> {
                    // AAC 裸流不要强行设为 AUDIO_AAC，不设 MimeType 让 DefaultMediaSourceFactory 的 Extractor 自动嗅探 ADTS 帧更稳妥
                    Log.d("BaiponBridge", "Service 识别为：AAC 裸流 (启用自动嗅探)")
                }
                urlLowerCase.contains("type=mp3") ||
                        urlLowerCase.contains(".mp3") -> {
                    mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.AUDIO_MPEG)
                    Log.d("BaiponBridge", "Service 识别为：MP3 音频")
                }
            }

            val mediaItem = mediaItemBuilder.build()

            // 【核心修改】：利用你在 onCreate 里配置好的、带万能自适应的默认工厂
            // 不要再写死 HlsMediaSource 了！
            player.stop() // 清理上一首的状态
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()

            Log.d("BaiponBridge", "Service 原生播放指令已成功执行: $name")
        } catch (e: Exception) {
            Log.e("BaiponBridge", "Service 播放失败: ${e.message}", e)
        }
    }

    private fun updateNotification() {
        try {
            val notification = createNotification()
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(notificationId, notification)
            startForeground(notificationId, notification)
        } catch (e: Exception) {
            Log.e("BaiponBridge", "更新通知失败: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("BaiponBridge", "onStartCommand, action: ${intent?.action}")

        when (intent?.action) {
            "PLAY_ACTION" -> {
                player.play()
                updateNotification()
            }
            "PAUSE_ACTION" -> {
                player.pause()
                updateNotification()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        Log.d("BaiponBridge", "PlaybackService onDestroy")
        try {
            player.release()
            mediaSession?.release()
        } catch (e: Exception) {
            Log.e("BaiponBridge", "销毁 Service 时出错: ${e.message}")
        }
        super.onDestroy()
    }
}