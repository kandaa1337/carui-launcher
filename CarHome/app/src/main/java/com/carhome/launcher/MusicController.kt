package com.carhome.launcher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.browse.MediaBrowser
import android.net.Uri
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
 * Источники информации:
 *  1. MediaSession — стандартный способ (Spotify, YouTube Music, Яндекс Музыка и т.д.)
 *  2. Сигналы магнитолы NWD:
 *     - Bluetooth (сервис com.bt.bc03): трек, исполнитель, играет/пауза
 *     - встроенный плеер (com.nwd.android.music.ui): трек, исполнитель, играет/пауза, время
 *  3. Стандартные "music broadcast'ы" других плееров
 *
 * Сигналы магнитолы слушаем всё время, пока жив лаунчер (а не только когда он на экране),
 * чтобы не пропустить смену трека, пока открыт другой экран. Сигналы редкие — нагрузки нет.
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
    /** Лаунчер на экране — можно обновлять UI */
    private var visible = false

    /** Это магнитола NWD (есть её Bluetooth-сервис или настройка громкости) */
    val isNwd: Boolean = isInstalled(NWD_BT_SERVICE) ||
        Settings.System.getString(context.contentResolver, "mcu_system_volume") != null

    fun hasPermission(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return enabled.contains(context.packageName)
    }

    /** Вызывать из onDestroy. */
    fun release() {
        stop()
        try { context.unregisterReceiver(extReceiver) } catch (_: Exception) {}
    }

    // ---------------- Экран показан / скрыт ----------------

    fun start() {
        visible = true
        if (!sessionsRegistered && hasPermission()) {
            try {
                sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, main)
                sessionsRegistered = true
                pickController(safeSessions())
            } catch (e: SecurityException) {
                DebugLog.add("sessions: нет доступа (${e.message})")
            }
        }
        // Медиа-сервис Bluetooth самого Android: в нём время трека и (если телефон умеет) обложка
        connectBtBrowser()
        // Попросим Bluetooth-сервис магнитолы прислать текущий трек заново
        if (isNwd) context.sendBroadcast(Intent(NWD_QUERY_BT_ID3))
        refresh()
    }

    fun stop() {
        visible = false
        if (sessionsRegistered) {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
            sessionsRegistered = false
        }
        controller?.unregisterCallback(callback)
        controller = null
        lastKey = null
        shownExtKey = null
        main.removeCallbacks(progressTick)
        btBrowser?.disconnect()
        btBrowser = null
        btController = null
    }

    // ---------------- 1b. Bluetooth-медиасервис Android ----------------
    //
    // В Android есть BluetoothMediaBrowserService (пакет com.android.bluetooth). Он отдаёт трек,
    // время и обложку с телефона по AVRCP, но создаёт сессию, только когда к нему подключаются.

    private var btBrowser: MediaBrowser? = null
    private var btController: MediaController? = null
    private var btBrowserStatus = "не пробовали"

    private fun connectBtBrowser() {
        if (btBrowser != null) return
        val component = try {
            context.packageManager.queryIntentServices(
                Intent("android.media.browse.MediaBrowserService").setPackage("com.android.bluetooth"), 0
            ).firstOrNull()?.serviceInfo?.let { ComponentName(it.packageName, it.name) }
        } catch (_: Exception) { null }
        if (component == null) {
            btBrowserStatus = "не найден"
            return
        }
        val browser = MediaBrowser(context, component, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val b = btBrowser ?: return
                btController = MediaController(context, b.sessionToken)
                btBrowserStatus = "подключён"
                DebugLog.add("BT media: подключён (${component.className.substringAfterLast('.')})")
                pickController(safeSessions())
            }
            override fun onConnectionFailed() {
                btBrowserStatus = "ошибка подключения"
                DebugLog.add("BT media: не удалось подключиться")
                btBrowser = null
            }
            override fun onConnectionSuspended() {
                btBrowserStatus = "отключён"
                btController = null
                pickController(safeSessions())
            }
        }, null)
        btBrowser = browser
        btBrowserStatus = "подключаюсь"
        try { browser.connect() } catch (e: Exception) {
            btBrowserStatus = "ошибка: ${e.message}"
            btBrowser = null
        }
    }

    /** Кто сейчас главный: MediaSession или сигналы магнитолы. */
    private fun useExt(): Boolean {
        val c = controller ?: return ext.source != null
        val sessionPlaying = c.playbackState?.state == PlaybackState.STATE_PLAYING
        return ext.source != null && ext.playing && !sessionPlaying
    }

    private fun refresh() {
        if (!visible) return
        if (useExt()) {
            publishExt()
        } else if (controller != null) {
            shownExtKey = null
            publishTrack()
            publishState()
        } else {
            shownExtKey = null
            listener.onTrackChanged(null)
            listener.onPlayStateChanged(false)
            listener.onProgress(0, 0)
        }
    }

    // ---------------- 1. MediaSession ----------------

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pickController(list) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() = pickController(safeSessions())
    }

    private fun safeSessions(): List<MediaController> = try {
        sessionManager.getActiveSessions(listenerComponent)
    } catch (_: SecurityException) {
        emptyList()
    }

    /** Берём играющий плеер, а если такого нет — самый свежий. */
    private fun pickController(list: List<MediaController>?) {
        val bt = btController
        val sessions = list.orEmpty().let { l ->
            if (bt != null && l.none { it.sessionToken == bt.sessionToken }) l + bt else l
        }
        DebugLog.add("sessions: " + sessions.joinToString { "${it.packageName}(${stateName(it.playbackState?.state)})" }
            .ifEmpty { "нет" })
        val best = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()

        if (best?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = best
        best?.registerCallback(callback, main)
        lastKey = null
        refresh()
    }

    // Обновление прогресс-бара раз в секунду, только пока играет музыка из MediaSession
    private val progressTick = object : Runnable {
        override fun run() {
            if (useExt()) return
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

        val artUri = if (rawArt == null) {
            m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: m.getString(MediaMetadata.METADATA_KEY_ART_URI)
                ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        } else null

        val key = "${c.packageName}|$title|$artist|$duration|${rawArt?.generationId}|$artUri"
        if (key == lastKey) return
        lastKey = key

        // Обложки бывают 1000+ px. Уменьшаем один раз при смене трека.
        val art = when {
            rawArt != null && rawArt === lastRawArt -> lastScaledArt
            rawArt != null -> downscale(rawArt, 640).also { lastRawArt = rawArt; lastScaledArt = it }
            artUri != null && artUri == uriArtKey -> uriArt
            else -> null
        }
        val track = Track(title, artist, art, duration, c.packageName)
        listener.onTrackChanged(track)
        if (art == null && artUri != null) loadArtFromUri(artUri, key, track)
    }

    // Обложка по ссылке: грузим в фоне один раз, уменьшая прямо при чтении
    private var uriArtKey: String? = null
    private var uriArt: Bitmap? = null

    private fun loadArtFromUri(uri: String, trackKey: String, track: Track) {
        Thread {
            val bmp = try {
                val cr = context.contentResolver
                val u = Uri.parse(uri)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                cr.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 640) sample *= 2
                cr.openInputStream(u)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            } catch (e: Exception) {
                DebugLog.add("обложка: не открылась ($uri) ${e.message}")
                null
            }
            if (bmp != null) main.post {
                uriArtKey = uri
                uriArt = bmp
                if (lastKey == trackKey && visible) listener.onTrackChanged(track.copy(art = bmp))
            }
        }.start()
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

    // ---------------- 2–3. Сигналы магнитолы и других плееров ----------------

    /** Что известно из сигналов: источник, трек, состояние, время. */
    private class ExtState {
        var source: String? = null      // SRC_BT / SRC_LOCAL / SRC_OTHER
        var pkg: String? = null
        var title: String? = null
        var artist: String? = null
        var playing = false
        var posMs = 0L
        var durMs = 0L
    }

    private val ext = ExtState()
    private var shownExtKey: String? = null

    private fun publishExt() {
        val key = "${ext.source}|${ext.title}|${ext.artist}"
        if (key != shownExtKey) {
            shownExtKey = key
            lastKey = null
            listener.onTrackChanged(Track(ext.title, ext.artist, null, ext.durMs, ext.pkg))
        }
        listener.onPlayStateChanged(ext.playing)
        listener.onProgress(ext.posMs, ext.durMs)
    }

    private val extReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val e = i.extras
            val action = i.action ?: return
            if (action != NWD_LOCAL_TIME) { // время приходит каждую секунду — в журнал не пишем
                DebugLog.add("broadcast $action " + (e?.keySet()?.joinToString { "$it=${e.get(it)}" } ?: ""))
            }

            when (action) {
                // --- Bluetooth магнитолы ---
                BT_ID3 -> {
                    switchTo(SRC_BT, NWD_BT_MUSIC_APP)
                    ext.title = e?.getString("extra_avrcp_id3_title")?.takeIf { it.isNotBlank() }
                    ext.artist = e?.getString("extra_avrcp_id3_artist")?.takeIf { it.isNotBlank() }
                }
                BT_PLAY -> { switchTo(SRC_BT, NWD_BT_MUSIC_APP); ext.playing = true }
                BT_PAUSE -> { if (ext.source == SRC_BT) ext.playing = false }
                BT_A2DP_RELEASE, BT_AVRCP_RELEASE -> if (ext.source == SRC_BT) clearExt()

                // --- Встроенный плеер магнитолы: время трека ---
                NWD_LOCAL_TIME -> {
                    // Время идёт — значит играет встроенный плеер (даже если трек ещё не пришёл)
                    if (ext.source != null && ext.source != SRC_LOCAL) return
                    if (ext.source == null) {
                        switchTo(SRC_LOCAL, NWD_LOCAL_MUSIC_APP)
                        ext.playing = true
                        refresh()
                    }
                    val pos = toNumber(e?.get("extra_widgetcurtime"))
                    val dur = toNumber(e?.get("extra_widgettotaltime"))
                    // Длительность песни в секундах почти всегда < 36000, в миллисекундах — больше
                    val k = if (dur in 1..36_000) 1000L else 1L
                    ext.posMs = pos * k
                    ext.durMs = dur * k
                    if (visible && useExt()) listener.onProgress(ext.posMs, ext.durMs)
                    return
                }
                NWD_MUSIC_STOP -> if (ext.source == SRC_LOCAL) clearExt()

                // --- Стандартные сигналы (их шлёт и встроенный плеер NWD) ---
                else -> {
                    val title = e?.getString("track") ?: e?.getString("title")
                    val playing = e?.let {
                        when {
                            it.containsKey("playing") -> it.getBoolean("playing")
                            it.containsKey("isPlaying") -> it.getBoolean("isPlaying")
                            else -> null
                        }
                    }
                    if (title == null && playing == null) return
                    val src = if (isNwd && action.startsWith("com.android.music.")) SRC_LOCAL else SRC_OTHER
                    switchTo(src, if (src == SRC_LOCAL) NWD_LOCAL_MUSIC_APP else null)
                    if (title != null) {
                        ext.title = title
                        ext.artist = e?.getString("artist")
                    }
                    if (playing != null) ext.playing = playing
                }
            }
            refresh()
        }
    }

    // Регистрация — строго после объявления extReceiver (порядок инициализации в Kotlin)
    init {
        val filter = IntentFilter().apply { EXT_ACTIONS.forEach { addAction(it) } }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(extReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(extReceiver, filter)
        }
    }

    private fun switchTo(source: String, pkg: String?) {
        if (ext.source == source) return
        ext.source = source
        ext.pkg = pkg
        ext.title = null
        ext.artist = null
        ext.posMs = 0
        ext.durMs = 0
    }

    private fun clearExt() {
        ext.source = null
        ext.pkg = null
        ext.title = null
        ext.artist = null
        ext.playing = false
        ext.posMs = 0
        ext.durMs = 0
    }

    /** Время может прийти числом или строкой "мм:сс" (тогда возвращаем секунды). */
    private fun toNumber(v: Any?): Long = when (v) {
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull() ?: v.split(":").mapNotNull { it.trim().toLongOrNull() }
            .fold(0L) { acc, part -> acc * 60 + part }
        else -> 0L
    }

    // ---------------- Управление ----------------

    fun currentPackage(): String? = if (useExt()) ext.pkg else controller?.packageName ?: ext.pkg

    fun playPause() = command(CMD_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { c ->
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun next() = command(CMD_NEXT, KeyEvent.KEYCODE_MEDIA_NEXT) { it.transportControls.skipToNext() }

    fun previous() = command(CMD_PREV, KeyEvent.KEYCODE_MEDIA_PREVIOUS) { it.transportControls.skipToPrevious() }

    private fun command(nwdCmd: Int, keyCode: Int, session: (MediaController) -> Unit) {
        val c = controller
        if (c != null && !useExt()) return session(c)

        when (nwdTarget()) {
            SRC_BT -> send(Intent(NWD_BT_CMD).putExtra("extra_command", nwdCmd))
            SRC_LOCAL -> send(Intent(NWD_LOCAL_CMD).putExtra("extra_command", nwdCmd))
            else -> sendKey(keyCode)
        }
    }

    /** Какому плееру магнитолы слать команду: по текущему источнику звука, иначе по последнему сигналу. */
    private fun nwdTarget(): String? {
        if (!isNwd) return null
        return when (Settings.System.getInt(context.contentResolver, "mcu_current_source", -1)) {
            SOURCE_BT_MUSIC -> SRC_BT
            SOURCE_MUSIC -> SRC_LOCAL
            else -> ext.source?.takeIf { it != SRC_OTHER }
        }
    }

    private fun send(intent: Intent) {
        DebugLog.add("cmd: ${intent.action} ${intent.getIntExtra("extra_command", -1)}")
        context.sendBroadcast(intent)
    }

    /** Та же команда, что шлют кнопки на руле. */
    private fun sendKey(code: Int) {
        DebugLog.add("key: ${KeyEvent.keyCodeToString(code)}")
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun isInstalled(pkg: String) = try {
        context.packageManager.getApplicationInfo(pkg, 0); true
    } catch (_: Exception) { false }

    // ---------------- Диагностика ----------------

    fun describe(): String = buildString {
        appendLine("Доступ к уведомлениям: ${if (hasPermission()) "есть" else "НЕТ"}")
        appendLine("Магнитола NWD: ${if (isNwd) "да" else "нет"}, источник звука: " +
            Settings.System.getInt(context.contentResolver, "mcu_current_source", -1))
        appendLine("Музыка играет (AudioManager): ${if (audio.isMusicActive) "да" else "нет"}")
        appendLine("Громкость NWD: " +
            "${Settings.System.getInt(context.contentResolver, "mcu_system_volume", -1)}/" +
            "${Settings.System.getInt(context.contentResolver, "mcu_max_volume", -1)}, mute " +
            "${Settings.System.getInt(context.contentResolver, "mcu_mute_state", -1)}")
        appendLine("Громкость Android (музыка): " +
            "${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
        appendLine("Bluetooth-медиасервис Android: $btBrowserStatus")
        btController?.let { bc ->
            val md = bc.metadata
            appendLine("  BT трек: ${md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "—"}, " +
                "длительность ${md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0} мс, " +
                "обложка ${if (md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null ||
                    md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) != null) "есть" else "нет"}, " +
                "позиция ${bc.playbackState?.position ?: -1} мс")
        }
        appendLine("Сигналы магнитолы: ${ext.source ?: "—"} | ${ext.title ?: "—"} | " +
            "${ext.artist ?: "—"} | ${if (ext.playing) "играет" else "пауза"}")
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
        private const val SRC_BT = "bt"
        private const val SRC_LOCAL = "local"
        private const val SRC_OTHER = "other"

        // Найдено в прошивке NWD (com.bt.bc03, com.nwd.bt.music, com.nwd.android.music.ui)
        private const val NWD_BT_SERVICE = "com.bt.bc03"
        private const val NWD_BT_MUSIC_APP = "com.nwd.bt.music"
        private const val NWD_LOCAL_MUSIC_APP = "com.nwd.android.music.ui"

        private const val BT_ID3 = "com.bt.ACTION_AVRCP_MUSIC_ID3"
        private const val BT_PLAY = "com.bt.ACTION_BT_MUSIC_PLAY"
        private const val BT_PAUSE = "com.bt.ACTION_BT_MUSIC_PAUSE"
        private const val BT_A2DP_RELEASE = "com.bt.ACTION_A2DP_RELEASE"
        private const val BT_AVRCP_RELEASE = "com.bt.ACTION_AVRCP_RELEASE"
        private const val NWD_QUERY_BT_ID3 = "com.nwd.ACTION_QUERY_A2DP_ID3"
        /** Команды BT Music: extra_command 1 = play/pause, 2 = предыдущий, 3 = следующий */
        private const val NWD_BT_CMD = "com.nwd.ACTION_PLAY_BTMUSIC_CMD"
        /** Команды встроенного плеера: extra_command 1 = play/pause, 2 = предыдущий, 3 = следующий */
        private const val NWD_LOCAL_CMD = "com.nwd.ACTION_PLAY_COMMAND"
        private const val NWD_LOCAL_TIME = "com.nwd.ACTION_REFRESH_WIDGETPLAYTIME"
        private const val NWD_MUSIC_STOP = "com.nwd.music.stop"

        private const val CMD_PLAY_PAUSE = 1
        private const val CMD_PREV = 2
        private const val CMD_NEXT = 3

        /** Значения mcu_current_source */
        private const val SOURCE_MUSIC = 2
        private const val SOURCE_BT_MUSIC = 15

        private val EXT_ACTIONS = listOf(
            BT_ID3, BT_PLAY, BT_PAUSE, BT_A2DP_RELEASE, BT_AVRCP_RELEASE,
            NWD_LOCAL_TIME, NWD_MUSIC_STOP,
            "com.android.music.metachanged",
            "com.android.music.playstatechanged",
            "com.spotify.music.metadatachanged",
            "com.spotify.music.playbackstatechanged",
            "com.sec.android.app.music.metachanged",
            "com.miui.player.metachanged",
            "com.htc.music.metachanged",
            "fm.last.android.metachanged",
        )
    }
}
