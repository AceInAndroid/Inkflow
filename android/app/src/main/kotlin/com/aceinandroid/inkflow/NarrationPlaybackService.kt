package com.aceinandroid.inkflow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

class NarrationPlaybackService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var mediaSession: MediaSession
    private var narrator: NativeNarrator? = null
    private var title = ""
    private var foreground = false
    private val endPlayback = Runnable {
        if (narrator?.state !in setOf(NarrationState.PLAYING, NarrationState.PAUSED, NarrationState.PREPARING))
            stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.narration_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        mediaSession = MediaSession(this, "Inkflow narration").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { narrator?.resume() }
                override fun onPause() { narrator?.pause() }
                override fun onStop() { stopPlayback() }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank {
            title.ifBlank { getString(R.string.read_aloud) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !foreground) {
            startForeground(NOTIFICATION_ID, notification(NarrationState.PREPARING))
            foreground = true
        }
        val current = NarrationPlayback.current() ?: run { stopSelf(); return START_NOT_STICKY }
        if (narrator !== current) {
            narrator?.onStateChanged = null
            narrator = current
            current.onStateChanged = ::onStateChanged
        }
        when (intent?.action) {
            ACTION_STOP -> { stopPlayback(); return START_NOT_STICKY }
            ACTION_PAUSE -> current.pause()
            ACTION_RESUME -> current.resume()
        }
        onStateChanged(current.state)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(endPlayback)
        narrator?.onStateChanged = null
        if (narrator?.state in setOf(NarrationState.PLAYING, NarrationState.PAUSED, NarrationState.PREPARING))
            narrator?.stop()
        narrator = null
        mediaSession.release()
        super.onDestroy()
    }

    private fun stopPlayback() {
        narrator?.stop()
        stopSelf()
    }

    // Every notification here has MediaStyle and this service's MediaSession token. Android 13
    // exempts media-session notifications from POST_NOTIFICATIONS; no unrelated alerts are posted.
    // https://developer.android.com/develop/ui/views/notifications/notification-permission#media-sessions
    @android.annotation.SuppressLint("NotificationPermission")
    private fun onStateChanged(state: NarrationState) {
        handler.removeCallbacks(endPlayback)
        if (state !in setOf(NarrationState.PLAYING, NarrationState.PAUSED, NarrationState.PREPARING)) {
            if (state != NarrationState.READY || !foreground) {
                stopSelf()
                return
            }
            mediaSession.setPlaybackState(PlaybackState.Builder()
                .setState(PlaybackState.STATE_BUFFERING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
                .build())
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification(NarrationState.PREPARING))
            handler.postDelayed(endPlayback, 3000)
            return
        }
        val playbackState = when (state) {
            NarrationState.PLAYING -> PlaybackState.STATE_PLAYING
            NarrationState.PAUSED -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_BUFFERING
        }
        mediaSession.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
            .setState(playbackState, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f,
                SystemClock.elapsedRealtime()).build())
        val notification = notification(state)
        if (!foreground) {
            startForeground(NOTIFICATION_ID, notification)
            foreground = true
        } else (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun notification(state: NarrationState): Notification {
        val playing = state == NarrationState.PLAYING
        val toggle = if (playing) ACTION_PAUSE else ACTION_RESUME
        val toggleLabel = if (playing) R.string.pause else R.string.play
        val toggleIcon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val result = builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(getString(when (state) {
                NarrationState.PLAYING -> R.string.narration_playing
                NarrationState.PAUSED -> R.string.narration_paused
                else -> R.string.narration_preparing
            }))
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
        if (state != NarrationState.PREPARING)
            result.addAction(Notification.Action.Builder(toggleIcon, getString(toggleLabel), action(toggle, 1)).build())
        result.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.stop), action(ACTION_STOP, 2)).build())
        val style = Notification.MediaStyle().setMediaSession(mediaSession.sessionToken)
        if (state == NarrationState.PREPARING) style.setShowActionsInCompactView(0)
        else style.setShowActionsInCompactView(0, 1)
        return result.setStyle(style).build()
    }

    private fun action(command: String, requestCode: Int): PendingIntent = PendingIntent.getService(this,
        requestCode, Intent(this, NarrationPlaybackService::class.java).setAction(command),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    companion object {
        private const val CHANNEL = "native_narration"
        private const val NOTIFICATION_ID = 2201
        private const val EXTRA_TITLE = "title"
        private const val ACTION_PAUSE = "com.aceinandroid.inkflow.narration.PAUSE"
        private const val ACTION_RESUME = "com.aceinandroid.inkflow.narration.RESUME"
        private const val ACTION_STOP = "com.aceinandroid.inkflow.narration.STOP"

        fun start(context: Context, title: String) {
            val intent = Intent(context, NarrationPlaybackService::class.java).putExtra(EXTRA_TITLE, title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NarrationPlaybackService::class.java))
        }
    }
}
