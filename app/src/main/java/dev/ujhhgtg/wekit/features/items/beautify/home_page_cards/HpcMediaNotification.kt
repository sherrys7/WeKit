package dev.ujhhgtg.wekit.features.items.beautify.home_page_cards

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.ujhhgtg.wekit.utils.WeLogger
import java.net.HttpURLConnection
import java.net.URL

object HpcMediaNotification {

    private const val TAG = "HpcMediaNotification"
    private val M = HpcMusicCard
    private val mh = Handler(Looper.getMainLooper())

    private const val CHANNEL_ID = "wekit_music_playback"
    private const val NOTI_ID = 888
    private const val ACTION_PREV = "dev.ujhhgtg.wekit.media.PREV"
    private const val ACTION_TOGGLE = "dev.ujhhgtg.wekit.media.TOGGLE"
    private const val ACTION_NEXT = "dev.ujhhgtg.wekit.media.NEXT"

    private var mediaSession: MediaSession? = null
    private var notificationManager: NotificationManager? = null
    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null

    private var initialized = false
    private var loopRunnable: Runnable? = null

    private val coverCache = HashMap<String, Bitmap>()

    fun onPlaybackStarted() {
        if (loopRunnable == null) {
            loopRunnable = object : Runnable {
                override fun run() {
                    try {
                        if (!initialized) initMediaSession()
                        updateMediaSession()
                    } catch (_: Exception) {}
                    mh.postDelayed(this, 1000)
                }
            }
            mh.post(loopRunnable!!)
        }
    }

    private fun initMediaSession() {
        try {
            val ctx = M.getActivity()?.applicationContext ?: return
            appContext = ctx
            notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID, "音乐播放", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "后台音乐播放控制"
                    setShowBadge(false)
                }
                notificationManager?.createNotificationChannel(channel)
            }

            if (receiver == null) {
                receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        when (intent?.action) {
                            ACTION_PREV -> M.playPrev()
                            ACTION_TOGGLE -> M.toggle()
                            ACTION_NEXT -> M.playNext()
                        }
                    }
                }
                val filter = IntentFilter().apply {
                    addAction(ACTION_PREV)
                    addAction(ACTION_TOGGLE)
                    addAction(ACTION_NEXT)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    ctx.registerReceiver(receiver, filter)
                }
            }

            mediaSession = MediaSession(ctx, "WeKitMusic").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() { M.handlePlayButtonClick() }
                    override fun onPause() { M.toggle() }
                    override fun onSkipToNext() { M.playNext() }
                    override fun onSkipToPrevious() { M.playPrev() }
                    override fun onStop() { M.toggle() }
                    override fun onSeekTo(pos: Long) { M.seekTo(pos.toInt()) }
                })
                isActive = true
            }
            initialized = true
            WeLogger.i(TAG, "MediaSession 初始化成功")
        } catch (e: Exception) {
            WeLogger.w(TAG, "MediaSession 初始化失败: $e")
        }
    }

    private fun updateMediaSession() {
        val session = mediaSession ?: return
        val ctx = appContext ?: return
        val song = M.getCurrentSong() ?: return
        try {
            val duration = M.getDuration()
            val pos = M.getCurrentPos()
            val playing = M.isPlaying()

            val coverKey = song.title + "|" + song.coverUrl
            var cover = coverCache[coverKey]
            if (cover == null && song.coverUrl.isNotEmpty()) {
                cover = M.coverSharedCache[song.coverUrl]
                if (cover != null) coverCache[coverKey] = cover
            }
            if (cover == null && song.coverUrl.isNotEmpty()) {
                downloadCover(coverKey, song.coverUrl)
            }

            val meta = android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, song.title)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, song.artist)
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, duration.toLong())
            cover?.let { meta.putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
            session.setMetadata(meta.build())

            val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setState(
                        if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        pos.toLong(), 1.0f
                    )
                    .setActions(actions)
                    .build()
            )

            val contentPi = try {
                val intent = Intent().apply {
                    setClassName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            } catch (_: Exception) { null }

            val builder = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(song.title)
                .setContentText(song.artist + " · WeKit 音乐")
                .setSubText(formatTime(pos) + " / " + formatTime(duration))
                .setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(session.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(playing)
            if (contentPi != null) builder.setContentIntent(contentPi)

            builder.addAction(android.R.drawable.ic_media_previous, "上一首", buildActionPi(ctx, ACTION_PREV, 1))
            builder.addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "暂停" else "播放", buildActionPi(ctx, ACTION_TOGGLE, 2)
            )
            builder.addAction(android.R.drawable.ic_media_next, "下一首", buildActionPi(ctx, ACTION_NEXT, 3))

            notificationManager?.notify(NOTI_ID, builder.build())
        } catch (e: Exception) {
            WeLogger.w(TAG, "updateMediaSession 失败: $e")
        }
    }

    private fun buildActionPi(ctx: Context, action: String, reqCode: Int): PendingIntent {
        val intent = Intent(action).apply { setPackage("com.tencent.mm") }
        return PendingIntent.getBroadcast(ctx, reqCode, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun downloadCover(key: String, coverUrl: String) {
        Thread {
            try {
                val conn = URL(coverUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.connect()
                if (conn.responseCode == 200) {
                    val bmp = BitmapFactory.decodeStream(conn.inputStream)
                    if (bmp != null) {
                        coverCache[key] = bmp
                    }
                }
                conn.disconnect()
            } catch (e: Exception) { WeLogger.w(TAG, "封面下载失败: ${e.message}") }
        }.start()
    }

    fun release() {
        loopRunnable?.let { mh.removeCallbacks(it) }
        loopRunnable = null
        try { notificationManager?.cancel(NOTI_ID) } catch (_: Exception) {}
        try { mediaSession?.release() } catch (_: Exception) {}
        mediaSession = null
        try { receiver?.let { appContext?.unregisterReceiver(it) } } catch (_: Exception) {}
        receiver = null
        initialized = false
    }

    private fun formatTime(millis: Int): String {
        val ts = millis / 1000
        return "%02d:%02d".format(ts / 60, ts % 60)
    }
}