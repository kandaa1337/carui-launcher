package com.carhome.launcher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent

/**
 * Следит за активным плеером и отдаёт его состояние в UI.
 *
 * Источники информации, по порядку:
 *  1. MediaSession — стандартный способ (Spotify, YouTube Music, Яндекс Музыка и т.д.)
 *  2. Старые "music broadcast'ы" — их шлют некоторые плееры и BT-приложения магнитол
 * Если ни одного источника нет, кнопки всё равно работают: шлём медиа-клавиши,
 * как кнопки на руле.
 */
class MusicController(private val context: Context, private val listener: Listener) {

    data class Track(
        val title: String?,
        val artist: String?,
        val art: Bitmap?,
        val durationMs: Long,
        val appPackage: String?,
    )

    interface Listener {
        fun onTrackChanged(track: Track?)
        fun onPlayStateChanged(isPlaying: Boolean)
        fun onProgress(positionMs: Long, durationMs: Long)
    }

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent = ComponentName(context, MediaListenerService::class.java)

    private var controller: MediaController? = null
    private var sessionsRegistered = false
    private var broadcastsRegistered = false

    fun hasPermission(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return enabled.contains(context.packageName)
    }

    // ---------------- Жизненный цикл ----------------

    fun start() {
        if (!broadcastsRegistered) {
            val filter = IntentFilter().apply { LEGACY_ACTIONS.forEach { addAction(it) } }
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(legacyReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(legacyReceiver, filter)
            }
            broadcastsRegistered = true
        }
        if (!sessionsRegistered && hasPermission()) {
            try {
                sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, main)
                sessionsRegistered = true
                pickController(safeSessions())
            } catch (e: SecurityException) {
                DebugLog.add("sessions: нет доступа (${e.message})")
            }
        }
    }

    fun stop() {
        if (broadcastsRegistered) {
            context.unregisterReceiver(legacyReceiver)
            broadcastsRegistered = false
        }
        if (sessionsRegistered) {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
            sessionsRegistered = false
        }
        controller?.unregisterCallback(callback)
        controller = null
        lastKey = null
        main.removeCallbacks(progressTick)
    }

    // ---------------- 1. MediaSession ----------------

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pickController(list) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publishTrack()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publishState()
        override fun onSessionDestroyed() = pickController(safeSessions())
    }

    private fun safeSessions(): List<MediaController> = try {
        sessionManager.getActiveSessions(listenerComponent)
    } catch (_: SecurityException) {
        emptyList()
    }

    /** Берём играющий плеер, а если такого нет — самый свежий. */
    private fun pickController(list: List<MediaController>?) {
        val sessions = list.orEmpty()
        DebugLog.add("sessions: " + sessions.joinToString { "${it.packageName}(${stateName(it.playbackState?.state)})" }
            .ifEmpty { "нет" })
        val best = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()

        if (best?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = best
        best?.registerCallback(callback, main)
        lastKey = null
        if (best != null) {
            publishTrack()
            publishState()
        } else if (!legacyActive) {
            listener.onTrackChanged(null)
            listener.onPlayStateChanged(false)
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
                val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
                pos += (elapsed * state.playbackSpeed).toLong()
                main.postDelayed(this, 1000)
            }
            listener.onProgress(pos.coerceAtLeast(0), duration)
        }
    }

    // Последний отправленный трек — чтобы не перерисовывать карточку на одинаковых данных
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

        // Обложки бывают 1000+ px. Уменьшаем один раз при смене трека.
        val art = when {
            rawArt == null -> null
            rawArt === lastRawArt -> lastScaledArt
            else -> downscale(rawArt, 640).also { lastRawArt = rawArt; lastScaledArt = it }
        }
        listener.onTrackChanged(Track(title, artist, art, duration, c.packageName))
    }

    private fun publishState() {
        val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        listener.onPlayStateChanged(playing)
        main.removeCallbacks(progressTick)
        main.post(progressTick)
    }

    private fun downscale(src: Bitmap, maxSide: Int): Bitmap {
        val side = maxOf(src.width, src.height)
        if (side <= maxSide) return src
        val k = maxSide.toFloat() / side
        return Bitmap.createScaledBitmap(
            src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true
        )
    }

    // ---------------- 2. Старые music broadcast'ы ----------------

    private var legacyActive = false

    private val legacyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val e = i.extras
            DebugLog.add("broadcast ${i.action} " +
                (e?.keySet()?.joinToString { "$it=${e.get(it)}" } ?: ""))
            if (controller != null) return // MediaSession точнее — он главный

            val title = e?.getString("track") ?: e?.getString("title")
            val artist = e?.getString("artist")
            val playing = when {
                e == null -> null
                e.containsKey("playing") -> e.getBoolean("playing")
                e.containsKey("isPlaying") -> e.getBoolean("isPlaying")
                e.containsKey("playstate") -> e.getBoolean("playstate")
                else -> null
            }
            if (title != null) {
                legacyActive = true
                val duration = (e?.get("duration") as? Number)?.toLong() ?: 0L
                listener.onTrackChanged(Track(title, artist, null, duration, null))
                listener.onProgress(0, 0)
            }
            if (playing != null) listener.onPlayStateChanged(playing)
        }
    }

    // ---------------- Управление ----------------

    fun currentPackage(): String? = controller?.packageName

    fun playPause() {
        val c = controller
        if (c == null) return sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun next() {
        controller?.transportControls?.skipToNext() ?: sendKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious() ?: sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    /** Та же команда, что шлют кнопки на руле. Её слушают и BT-приложения магнитол. */
    private fun sendKey(code: Int) {
        DebugLog.add("key: ${KeyEvent.keyCodeToString(code)}")
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ---------------- Диагностика ----------------

    fun describe(): String = buildString {
        appendLine("Доступ к уведомлениям: ${if (hasPermission()) "есть" else "НЕТ"}")
        appendLine("Музыка играет (AudioManager): ${if (audio.isMusicActive) "да" else "нет"}")
        appendLine()
        appendLine("Медиасессии:")
        val sessions = safeSessions()
        if (sessions.isEmpty()) appendLine("  нет")
        sessions.forEach {
            val t = it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            appendLine("  ${it.packageName} — ${stateName(it.playbackState?.state)} — $t")
        }
    }

    private fun stateName(s: Int?) = when (s) {
        PlaybackState.STATE_PLAYING -> "играет"
        PlaybackState.STATE_PAUSED -> "пауза"
        PlaybackState.STATE_STOPPED -> "стоп"
        PlaybackState.STATE_BUFFERING -> "буфер"
        null -> "?"
        else -> s.toString()
    }

    companion object {
        private val LEGACY_ACTIONS = listOf(
            "com.android.music.metachanged",
            "com.android.music.playstatechanged",
            "com.android.music.playbackcomplete",
            "com.android.music.queuechanged",
            "com.spotify.music.metadatachanged",
            "com.spotify.music.playbackstatechanged",
            "com.sec.android.app.music.metachanged",
            "com.miui.player.metachanged",
            "com.htc.music.metachanged",
            "fm.last.android.metachanged",
            // Возможные варианты у прошивок китайских магнитол (не повредят, если их нет)
            "com.android.bluetooth.music.metachanged",
            "com.fyt.music.metachanged",
            "com.syu.music.metachanged",
        )
    }
}
