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
    
    // 保存当前播放URL
    private var currentStreamUrl: String? = null

    companion object {
        const val COMMAND_PLAY_STREAM = "PLAY_STREAM"
        
        // 添加Service Action
        const val ACTION_PLAY = "PLAY_ACTION"
        const val ACTION_PAUSE = "PAUSE_ACTION"
        
        // 启动Service的Intent
        fun startService(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("BaiponBridge", "PlaybackService onCreate")

        player = ExoPlayer.Builder(this).build()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createInitialNotification())

        mediaSession = MediaSession.Builder(this, player)
            .setId("BaiponRadioSession")
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("BaiponBridge", "onStartCommand action: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_PLAY -> {
                // 如果有保存的URL，恢复播放
                currentStreamUrl?.let { 
                    playStream(it)
                } ?: run {
                    Log.w("BaiponBridge", "没有保存的播放流")
                }
            }
            ACTION_PAUSE -> {
                player.pause()
                updateNotification()
            }
        }
        return START_STICKY  // 保持Service存活
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "百品电台",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "控制电台播放"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createInitialNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("百品电台")
            .setContentText("后台运行中")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isPlaying = player.isPlaying
        
        // 使用明确的Intent，而不是隐式action
        val controlIntent = Intent(this, PlaybackService::class.java).apply {
            action = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        }
        val controlPendingIntent = PendingIntent.getService(
            this,
            0,
            controlIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseAction = NotificationCompat.Action(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isPlaying) "暂停" else "播放",
            controlPendingIntent
        )

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("百品电台")
            .setContentText(if (isPlaying) "正在播放" else "已暂停")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(playPauseAction)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying)

        mediaSession?.let { session ->
            builder.setStyle(
                androidx.media3.session.MediaStyleNotificationHelper
                    .MediaStyle(session)
                    .setShowActionsInCompactView(0)
            )
        }

        return builder.build()
    }

    private fun playStream(url: String) {
        currentStreamUrl = url  // 保存URL，用于恢复播放
        try {
            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("百品电台")
                        .setArtist("在线直播")
                        .build()
                )
                .build()

            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
            updateNotification()
            Log.d("BaiponBridge", "开始播放: $url")
        } catch (e: Exception) {
            Log.e("BaiponBridge", "播放失败: ${e.message}", e)
        }
    }

    private fun updateNotification() {
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e("BaiponBridge", "更新通知失败: ${e.message}")
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
        mediaSession?.release()
    }
}
