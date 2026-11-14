/*
 * Watchly — system media controls for the player (cycle 9, §5.3).
 *
 * A Media3 [MediaSession] is bound to the running player for the
 * lifetime of the player screen. The session is what makes playback
 * behave like a premium player outside the app: the system mirrors
 * its state to the lock screen, Bluetooth and headset controls,
 * and transport commands (play / pause / seek) flow back into the
 * player on their own. On top of the session we post our own media
 * notification (black + gold, the title, play/pause and — when the
 * queue has one — Next) whose buttons broadcast to
 * [MediaControlsReceiver]; the receiver forwards to the live
 * ViewModel through [MediaControlsHub], so a notification tap runs
 * exactly the same code as an in-player tap (progress saving,
 * autoplay rules and all).
 *
 * Deliberate limits, so nothing here can surprise:
 *  - No foreground service is started. The session lives and dies
 *    with the player screen; Picture-in-Picture is the
 *    watch-while-doing-other-things path (§5.3).
 *  - No runtime notification-permission prompt: the blocking
 *    Maestro journey 01 opens the player, and a system permission
 *    dialog there would break it. The notification appears when
 *    the user has allowed notifications for Watchly in system
 *    settings; the MediaSession's system controls work regardless.
 */
package com.watchly.app.data.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.watchly.app.R

/** Live callbacks from system surfaces into the running player. */
object MediaControlsHub {
    @Volatile
    var onTogglePlayPause: (() -> Unit)? = null

    @Volatile
    var onNext: (() -> Unit)? = null
}

/** Notification-button target: forwards to [MediaControlsHub]. */
class MediaControlsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE_PLAY_PAUSE -> MediaControlsHub.onTogglePlayPause?.invoke()
            ACTION_NEXT -> MediaControlsHub.onNext?.invoke()
        }
    }

    companion object {
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.watchly.app.media.TOGGLE_PLAY_PAUSE"
        const val ACTION_NEXT = "com.watchly.app.media.NEXT"
    }
}

/**
 * Owns one [MediaSession] + its notification. Created by the player
 * ViewModel when a real player exists, updated as playback state
 * changes, released when the player closes.
 */
@OptIn(UnstableApi::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerMediaSessionController(
    context: Context,
    player: Player
) {
    /** Wired by the ViewModel right after construction. */
    var onTogglePlayPause: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null

    private val appContext: Context = context.applicationContext

    private val session: MediaSession = MediaSession.Builder(appContext, player)
        .setId(SESSION_ID)
        .setSessionActivity(openAppIntent())
        .setCallback(object : MediaSession.Callback {
            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                if (customCommand.customAction == CUSTOM_ACTION_NEXT) {
                    onNext?.invoke()
                }
                return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_SUCCESS)
                )
            }
        })
        .build()

    private var lastShown: ShownState? = null

    init {
        createChannel()
        MediaControlsHub.onTogglePlayPause = { onTogglePlayPause?.invoke() }
        MediaControlsHub.onNext = { onNext?.invoke() }
    }

    /**
     * Refreshes the notification to match playback. Cheap to call
     * on every state poll: an unchanged state posts nothing.
     */
    fun update(title: String, subtitle: String?, isPlaying: Boolean, hasNext: Boolean) {
        // Android 13+: without the user's notification grant there
        // is nothing to post — skip quietly (the MediaSession's
        // system controls are unaffected). Checked before the
        // dedupe so a later grant is picked up on the next change.
        if (android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val shown = ShownState(title, subtitle, isPlaying, hasNext)
        if (shown == lastShown) return
        lastShown = shown
        val toggle = actionIntent(MediaControlsReceiver.ACTION_TOGGLE_PLAY_PAUSE, 1)
        val next = actionIntent(MediaControlsReceiver.ACTION_NEXT, 2)
        // MediaStyle lives in androidx.media (not androidx.core);
        // it pairs with the session's compat token.
        val style = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(session.sessionCompatToken)
        if (hasNext) {
            style.setShowActionsInCompactView(0, 1)
        } else {
            style.setShowActionsInCompactView(0)
        }
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_watchly)
            .setContentTitle(title)
            .setContentText(subtitle ?: WATCHLY_LABEL)
            .setContentIntent(openAppIntent())
            .setStyle(style)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                toggle
            )
        if (hasNext) {
            builder.addAction(android.R.drawable.ic_media_next, "Next", next)
        }
        runCatching {
            NotificationManagerCompat.from(appContext)
                .notify(NOTIFICATION_ID, builder.build())
        }
    }

    /** Removes the notification and releases the session. */
    fun release() {
        runCatching {
            NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
        }
        if (MediaControlsHub.onTogglePlayPause != null) {
            MediaControlsHub.onTogglePlayPause = null
        }
        if (MediaControlsHub.onNext != null) {
            MediaControlsHub.onNext = null
        }
        session.release()
    }

    private fun createChannel() {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Playback",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Now playing controls" }
            )
        }
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            requestCode,
            Intent(appContext, MediaControlsReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun openAppIntent(): PendingIntent {
        val launch = appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
            ?: Intent()
        return PendingIntent.getActivity(
            appContext,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private data class ShownState(
        val title: String,
        val subtitle: String?,
        val isPlaying: Boolean,
        val hasNext: Boolean
    )

    companion object {
        const val CHANNEL_ID = "watchly_playback"
        const val NOTIFICATION_ID = 1001
        const val SESSION_ID = "watchly-player"
        const val WATCHLY_LABEL = "Watchly"

        /** Custom session command a controller can send for "next". */
        const val CUSTOM_ACTION_NEXT = "com.watchly.app.session.NEXT"
    }
}
