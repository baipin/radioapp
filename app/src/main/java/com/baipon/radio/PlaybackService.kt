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

        // 1. 初始化 ExoPlayer 并设置音频属性（必须设置，否则系统不会将其视为活跃媒体）
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true // 自动处理音频焦点
            )
            .build()

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
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    // 2. 【关键修改】必须同时授权 Player 命令和 Session 命令
                    // 只有设置了 PlayerCommands，系统控制台（锁屏、下拉菜单）才能显示并控制播放
                    val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .build()

                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(COMMAND_PLAY_STREAM, Bundle.EMPTY))
                        .build()

                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(playerCommands) // 允许系统控制播放/暂停
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
                        // 3. 【修改】尝试从 Bundle 获取网页传来的电台名称
                        val name = args.getString("name") ?: "百品电台"
                        if (!url.isNullOrEmpty()) {
                            playStream(url, name)
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()

        // 启动前台服务
        val notification = createInitialNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.d("BaiponBridge", "前台服务已启动，通知已显示")
    }

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
        }
    }

    private fun createInitialNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 4. 【修改】根据 Player 状态动态显示标题
        val currentTitle = player.currentMediaItem?.mediaMetadata?.title ?: "百品电台"
        val currentStatus = if (player.isPlaying) "正在播放" else "待命中..."

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(currentStatus)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(player.isPlaying)
            .build()
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 5. 【修改】从元数据中动态获取标题
        val currentTitle = player.currentMediaItem?.mediaMetadata?.title ?: "百品电台"
        val isPlaying = player.isPlaying

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(if (isPlaying) "正在播放直播流" else "已暂停")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying)

        // 添加 MediaStyle，使通知能显示播放控制按钮并被系统接管
        mediaSession?.let { session ->
            builder.setStyle(
                androidx.media3.session.MediaStyleNotificationHelper
                    .MediaStyle(session)
                    .setShowActionsInCompactView(0)
            )
        }

        return builder.build()
    }

    // 6. 【修改】增加 name 参数，并设置给 MediaMetadata
    private fun playStream(url: String, name: String) {
        try {
            val metadata = MediaMetadata.Builder()
                .setTitle(name) // 将网页传来的名称设置为标题
                .setArtist("在线直播")
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(metadata)
                .build()

            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()

            // 播放状态改变，立即更新通知
            updateNotification()
            Log.d("BaiponBridge", "开始播放: $name ($url)")
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("BaiponBridge", "PlaybackService onStartCommand, action: ${intent?.action}")

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