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

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val NOTIFICATION_ID = 1
    private val NOTIFICATION_CHANNEL_ID = "baipon_radio_channel"

    companion object {
        const val COMMAND_PLAY_STREAM = "PLAY_STREAM"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("BaiponBridge", "PlaybackService onCreate 开始")

        // 初始化 ExoPlayer
        player = ExoPlayer.Builder(this).build()

        // 创建通知渠道（Android 8.0+）
        createNotificationChannel()

        // 创建 PendingIntent，点击通知栏可返回 App
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 创建 MediaSession 并设置回调
        mediaSession = MediaSession.Builder(this, player)
            .setId("BaiponRadioSession")
            .setSessionActivity(pendingIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {

                    if (customCommand.customAction == COMMAND_PLAY_STREAM) {
                        val url = args.getString("url")
                        if (!url.isNullOrEmpty()) {
                            playStream(url)
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()

        // 启动前台服务 - 必须在 onCreate 中完成
        val notification = createInitialNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.d("BaiponBridge", "前台服务已启动，通知已显示")
    }

    // 创建通知渠道
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "百品电台",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示正在播放的电台节目"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            Log.d("BaiponBridge", "通知渠道已创建")
        }
    }

    // 初始化通知（服务启动时）
    private fun createInitialNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("百品电台")
            .setContentText("待命中...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    // 创建播放通知
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 暂停 Action
        val pauseIntent = Intent(this, PlaybackService::class.java).apply {
            action = "PAUSE_ACTION"
        }
        val pausePendingIntent = PendingIntent.getService(
            this,
            0,
            pauseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 播放 Action
        val playIntent = Intent(this, PlaybackService::class.java).apply {
            action = "PLAY_ACTION"
        }
        val playPendingIntent = PendingIntent.getService(
            this,
            1,
            playIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isPlaying = player.isPlaying
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "暂停",
                pausePendingIntent
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "播放",
                playPendingIntent
            )
        }

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("百品电台")
            .setContentText(
                if (isPlaying) "正在播放..." else "已暂停"
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(playPauseAction)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying)

        // 如果 mediaSession 不为空，添加 MediaStyle
        mediaSession?.let { session ->
            builder.setStyle(
                androidx.media3.session.MediaStyleNotificationHelper
                    .MediaStyle(session)
                    .setShowActionsInCompactView(0)
            )
        }

        return builder.build()
    }

    // 播放直播流的核心方法
    private fun playStream(url: String) {
        try {
            val metadata = MediaMetadata.Builder()
                .setTitle("百品电台")
                .setArtist("在线直播")
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(metadata)
                .build()

            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()

            // 更新前台服务通知
            updateNotification()
            Log.d("BaiponBridge", "开始播放: $url")
        } catch (e: Exception) {
            Log.e("BaiponBridge", "播放失败: ${e.message}", e)
        }
    }

    // 更新通知
    private fun updateNotification() {
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e("BaiponBridge", "更新通知失败: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("BaiponBridge", "PlaybackService onStartCommand, action: ${intent?.action}")
        
        when (intent?.action) {
            "PLAY_ACTION" -> {
                player.play()
                updateNotification()
                Log.d("BaiponBridge", "通知栏播放按钮被点击")
            }
            "PAUSE_ACTION" -> {
                player.pause()
                updateNotification()
                Log.d("BaiponBridge", "通知栏暂停按钮被点击")
            }
        }
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "PLAY_ACTION" -> {
                player.play()
                updateNotification()
                Log.d("BaiponBridge", "通知栏播放按钮被点击")
            }
            "PAUSE_ACTION" -> {
                player.pause()
                updateNotification()
                Log.d("BaiponBridge", "通知栏暂停按钮被点击")
            }
        }
        return START_STICKY
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
