package com.carhome.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Следит за активным плеером в системе и отдаёт его состояние в UI.
 * Работает с любым приложением, которое публикует MediaSession
 * (почти все плееры и Bluetooth-аудио с телефона).
 */
class MusicController(private val context: Context, private val listener: Listener) {

    data class Track(
        val title: String?,
        val artist: String?,
        val art: Bitmap?,
        val durationMs: Long,
        val appPackage: String,
    )

    interface Listener {
        fun onTrackChanged(track: Track?)
        fun onPlayStateChanged(isPlaying: Boolean)
        fun onProgress(positionMs: Long, durationMs: Long)
    }

    private val main = Handler(Looper.getMainLooper())
    private val sessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent = ComponentName(context, MediaListenerService::class.java)

    private var controller: MediaController? = null
    private var started = false

    fun hasPermission(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return enabled.contains(context.packageName)
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pickController(list) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publishTrack()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publishState()
        override fun onSessionDestroyed() {
            pickController(safeSessions())
        }
    }

    // Обновление прогресс-бара раз в секунду, только пока играет музыка
    private val progressTick = object : Runnable {
        override fun run() {
            val c = controller ?: return
            val state = c.playbackState ?: return
            val duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            var pos = state.position
            if (state.state == PlaybackState.STATE_PLAYING) {
                val elapsed = android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
                pos += (elapsed * state.playbackSpeed).toLong()
                main.postDelayed(this, 1000)
            }
            listener.onProgress(pos.coerceAtLeast(0), duration)
        }
    }

    fun start() {
        if (started || !hasPermission()) return
        started = true
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, main)
        } catch (_: SecurityException) {
            started = false
            return
        }
        pickController(safeSessions())
    }

    fun stop() {
        if (!started) return
        started = false
        sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        controller?.unregisterCallback(callback)
        controller = null
        main.removeCallbacks(progressTick)
    }

    private fun safeSessions(): List<MediaController> = try {
        sessionManager.getActiveSessions(listenerComponent)
    } catch (_: SecurityException) {
        emptyList()
    }

    /** Берём играющий плеер, а если такого нет — самый свежий. */
    private fun pickController(list: List<MediaController>?) {
        val sessions = list.orEmpty()
        val best = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()

        if (best?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = best
        best?.registerCallback(callback, main)
        publishTrack()
        publishState()
    }

    // Последний отправленный трек — чтобы не перерисовывать карточку,
    // когда плеер шлёт одинаковые метаданные по нескольку раз
    private var lastKey: String? = null
    private var lastRawArt: Bitmap? = null
    private var lastScaledArt: Bitmap? = null

    private fun publishTrack() {
        val c = controller
        val m = c?.metadata
        if (c == null || m == null) {
            lastKey = null
            listener.onTrackChanged(null)
            return
        }
        val rawArt = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val title = m.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val key = "${c.packageName}|$title|$artist|$duration|${rawArt?.generationId}"
        if (key == lastKey) return
        lastKey = key

        // Плееры отдают обложки до 1000+ px. Уменьшаем один раз при смене трека —
        // меньше памяти и дешевле отрисовка каждого кадра.
        val art = when {
            rawArt == null -> null
            rawArt === lastRawArt -> lastScaledArt
            else -> downscale(rawArt, 480).also { lastRawArt = rawArt; lastScaledArt = it }
        }

        listener.onTrackChanged(Track(title, artist, art, duration, c.packageName))
    }

    private fun downscale(src: Bitmap, maxSide: Int): Bitmap {
        val side = maxOf(src.width, src.height)
        if (side <= maxSide) return src
        val k = maxSide.toFloat() / side
        return Bitmap.createScaledBitmap(
            src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true
        )
    }

    private fun publishState() {
        val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        listener.onPlayStateChanged(playing)
        main.removeCallbacks(progressTick)
        main.post(progressTick)
    }

    fun currentPackage(): String? = controller?.packageName

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()
}
