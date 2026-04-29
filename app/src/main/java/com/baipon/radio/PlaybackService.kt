package com.baipon.radio

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
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

    companion object {
        const val COMMAND_PLAY_STREAM = "PLAY_STREAM"
    }

    override fun onCreate() {
        super.onCreate()

        // 初始化 ExoPlayer
        player = ExoPlayer.Builder(this).build()

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
    }

    // 播放直播流的核心方法
    private fun playStream(url: String) {
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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        player.release()
        mediaSession?.release()
        super.onDestroy()
    }
}