package com.carhome.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), MusicController.Listener {

    private lateinit var music: MusicController
    private lateinit var volume: VolumeController

    private lateinit var bgArt: ImageView
    private lateinit var sourceChip: TextView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var timeCurrent: TextView
    private lateinit var timeTotal: TextView
    private lateinit var progress: ProgressBar
    private lateinit var playPause: ImageButton
    private lateinit var permissionHint: TextView
    private lateinit var dateView: TextView
    private lateinit var volumeBar: VolumeSlider
    private lateinit var muteButton: ImageButton

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private var currentArt: Bitmap? = null
    private var artInitialized = false
    private var lastSecond = -1L
    private var lastDurationSec = -1L
    private var isPlaying: Boolean? = null
    private var muteShown: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bgArt = findViewById(R.id.bgArt)
        sourceChip = findViewById(R.id.sourceChip)
        title = findViewById(R.id.trackTitle)
        artist = findViewById(R.id.trackArtist)
        timeCurrent = findViewById(R.id.timeCurrent)
        timeTotal = findViewById(R.id.timeTotal)
        progress = findViewById(R.id.trackProgress)
        playPause = findViewById(R.id.btnPlayPause)
        permissionHint = findViewById(R.id.permissionHint)
        dateView = findViewById(R.id.date)

        music = MusicController(this, this)

        findViewById<View>(R.id.btnPrev).setOnClickListener { music.previous() }
        findViewById<View>(R.id.btnNext).setOnClickListener { music.next() }
        // Даже если плеер не найден, кнопка шлёт "play/pause" как кнопка на руле
        playPause.setOnClickListener { music.playPause() }

        // Нажатие на обложку/название — открыть плеер; долгое — диагностика
        val trackInfo = findViewById<View>(R.id.trackInfo)
        trackInfo.setOnClickListener { openMusicApp() }
        trackInfo.setOnLongClickListener { showDiagnostics(); true }

        permissionHint.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<View>(R.id.dockNav).setOnClickListener { openNavigation() }
        val dockMusic = findViewById<View>(R.id.dockMusic)
        dockMusic.setOnClickListener { openMusicApp() }
        dockMusic.setOnLongClickListener { pickMusicApp(); true }
        findViewById<View>(R.id.dockApps).setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        findViewById<View>(R.id.dockSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        setupVolume()
        onTrackChanged(null)
        onPlayStateChanged(false)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        permissionHint.visibility = if (music.hasPermission()) View.GONE else View.VISIBLE
        music.start()
        volume.start()
        updateDate()
        registerReceiver(clockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
    }

    override fun onPause() {
        super.onPause()
        // Пока лаунчер не на экране — ничего не обновляем и не тратим ресурсы
        music.stop()
        volume.stop()
        unregisterReceiver(clockReceiver)
    }

    // Кнопка "Назад" на главном экране ничего не делает
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

    // ---------- Громкость ----------

    private fun setupVolume() {
        volumeBar = findViewById(R.id.volumeBar)
        muteButton = findViewById(R.id.btnMute)

        volume = VolumeController(this) { level, max, muted -> showVolume(level, max, muted) }
        volumeBar.max = volume.max
        volumeBar.onUserChange = { v -> volume.set(v) }
        muteButton.setOnClickListener { volume.toggleMute() }
    }

    private fun showVolume(level: Int, max: Int, muted: Boolean) {
        volumeBar.max = max
        volumeBar.setValue(level)
        val showMuted = muted || level == 0
        volumeBar.setMuted(showMuted)
        // Иконку меняем только когда реально сменилось состояние
        if (muteShown != showMuted) {
            muteShown = showMuted
            muteButton.setImageResource(if (showMuted) R.drawable.ic_volume_off else R.drawable.ic_volume)
            muteButton.isActivated = showMuted // красная подложка из bg_mute
        }
    }

    // ---------- Музыка ----------

    override fun onTrackChanged(track: MusicController.Track?) {
        if (track == null) {
            title.text = getString(R.string.no_music)
            artist.text = getString(R.string.tap_to_open)
            sourceChip.text = getString(R.string.music)
            setArt(null)
            onProgress(0, 0)
            return
        }
        title.text = track.title ?: getString(R.string.unknown_track)
        artist.text = track.artist ?: ""
        sourceChip.text = appLabel(track.appPackage)
        setArt(track.art)
    }

    /** Меняет обложку и цвета. Вызывается только при смене трека, не каждый кадр. */
    private fun setArt(art: Bitmap?) {
        if (artInitialized && art === currentArt) return
        artInitialized = true
        currentArt = art

        if (art != null) bgArt.setImageBitmap(art)
        else bgArt.setImageResource(R.drawable.art_placeholder_bg)

        // Цвет из обложки — для прогресса и полосы громкости
        val base = if (art != null) ArtColors.dominant(art) else DEFAULT_COLOR
        val accent = ArtColors.accent(base)
        progress.progressTintList = ColorStateList.valueOf(accent)
        volumeBar.setFillColor(accent)

        // Плавная смена обложки. withLayer() — анимация идёт на GPU одним слоем.
        bgArt.alpha = 0.2f
        bgArt.animate().alpha(1f).setDuration(450).withLayer().start()
    }

    override fun onPlayStateChanged(isPlaying: Boolean) {
        if (this.isPlaying == isPlaying) return
        this.isPlaying = isPlaying
        playPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    override fun onProgress(positionMs: Long, durationMs: Long) {
        // Перерисовываем текст только если реально сменилась секунда
        val sec = positionMs / 1000
        val durSec = durationMs / 1000
        if (sec == lastSecond && durSec == lastDurationSec) return
        lastSecond = sec
        lastDurationSec = durSec

        if (durationMs <= 0) {
            progress.progress = 0
            timeCurrent.text = if (sec > 0) formatTime(sec) else ""
            timeTotal.text = ""
            return
        }
        progress.progress = ((positionMs * 1000) / durationMs).toInt().coerceIn(0, 1000)
        timeCurrent.text = formatTime(sec)
        timeTotal.text = formatTime(durSec)
    }

    private fun formatTime(totalSec: Long): String {
        val m = totalSec / 60
        val s = totalSec % 60
        return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s) else "%d:%02d".format(m, s)
    }

    // ---------- Запуск приложений ----------

    private fun openMusicApp() {
        // 1) Плеер, который сейчас играет
        music.currentPackage()?.let { pkg ->
            packageManager.getLaunchIntentForPackage(pkg)?.let { return safeStart(it) }
        }
        // 2) Приложение, которое пользователь выбрал сам (долгое нажатие на "Музыка")
        prefs.getString(KEY_MUSIC_APP, null)?.let { pkg ->
            packageManager.getLaunchIntentForPackage(pkg)?.let { return safeStart(it) }
        }
        // 3) Музыкальное приложение по умолчанию, если оно вообще есть
        val selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
        if (selector.resolveActivity(packageManager) != null) return safeStart(selector)
        // 4) Ничего нет — просим выбрать один раз и запоминаем
        pickMusicApp()
    }

    private fun pickMusicApp() {
        Toast.makeText(this, R.string.pick_music_app, Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, AppsActivity::class.java).putExtra(AppsActivity.EXTRA_PICK_MUSIC, true))
    }

    private fun openNavigation() {
        // Пробуем популярные навигаторы по очереди, потом любой, кто умеет geo:
        val candidates = listOf(
            "ru.yandex.yandexnavi",
            "ru.yandex.yandexmaps",
            "com.waze",
            "com.google.android.apps.maps",
        )
        for (pkg in candidates) {
            packageManager.getLaunchIntentForPackage(pkg)?.let { return safeStart(it) }
        }
        safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")))
    }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(this, AppsActivity::class.java))
        }
    }

    private fun appLabel(pkg: String?): String = try {
        if (pkg == null) throw IllegalArgumentException()
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        getString(R.string.music)
    }

    // ---------- Дата на русском ----------

    private val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))
    private var lastDate = ""

    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateDate()
    }

    private fun updateDate() {
        val text = dateFormat.format(Date())
        if (text != lastDate) {
            lastDate = text
            dateView.text = text
        }
    }

    // ---------- Диагностика (долгое нажатие на обложку) ----------

    private fun showDiagnostics() {
        val pm = packageManager
        val related = pm.getInstalledApplications(0)
            .filter { DIAG_PATTERN.containsMatchIn(it.packageName) }
            .joinToString("\n") { "  ${it.packageName} — ${pm.getApplicationLabel(it)}" }
            .ifEmpty { "  —" }
        val text = music.describe() +
            "\nВыбранное муз. приложение: ${prefs.getString(KEY_MUSIC_APP, null) ?: "—"}" +
            "\n\nПриложения (bt/music/media):\n$related" +
            "\n\nПоследние события:\n${DebugLog.dump()}"

        val tv = TextView(this).apply {
            this.text = text
            setTextIsSelectable(true)
            textSize = 13f
            setPadding(40, 24, 40, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.diagnostics)
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    companion object {
        const val PREFS = "launcher"
        const val KEY_MUSIC_APP = "music_app"
        private val DIAG_PATTERN = Regex("bt|blue|music|audio|media|a2dp|player", RegexOption.IGNORE_CASE)
        private val DEFAULT_COLOR = Color.rgb(0xC2, 0x7A, 0x2A) // тёплый янтарь, когда нет обложки
    }
}
