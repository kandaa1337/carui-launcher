package com.carhome.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity(), MusicController.Listener {

    private lateinit var music: MusicController

    private lateinit var cardArt: ImageView
    private lateinit var cardTint: View
    private lateinit var sourceChip: TextView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var timeCurrent: TextView
    private lateinit var timeTotal: TextView
    private lateinit var progress: ProgressBar
    private lateinit var playPause: ImageButton
    private lateinit var permissionHint: TextView

    private lateinit var volume: VolumeController
    private lateinit var volumeBar: SeekBar
    private lateinit var volumeValue: TextView
    private lateinit var muteButton: ImageButton
    private var muteShown: Boolean? = null

    private var currentArt: Bitmap? = null
    private var lastSecond = -1L
    private var lastDurationSec = -1L
    private var isPlaying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cardArt = findViewById(R.id.cardArt)
        cardTint = findViewById(R.id.cardTint)
        sourceChip = findViewById(R.id.sourceChip)
        title = findViewById(R.id.trackTitle)
        artist = findViewById(R.id.trackArtist)
        timeCurrent = findViewById(R.id.timeCurrent)
        timeTotal = findViewById(R.id.timeTotal)
        progress = findViewById(R.id.trackProgress)
        playPause = findViewById(R.id.btnPlayPause)
        permissionHint = findViewById(R.id.permissionHint)

        music = MusicController(this, this)

        findViewById<View>(R.id.btnPrev).setOnClickListener { music.previous() }
        findViewById<View>(R.id.btnNext).setOnClickListener { music.next() }
        playPause.setOnClickListener {
            if (music.currentPackage() == null) openMusicApp() else music.playPause()
        }
        findViewById<View>(R.id.musicCard).setOnClickListener { openMusicApp() }
        permissionHint.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<View>(R.id.dockNav).setOnClickListener { openNavigation() }
        findViewById<View>(R.id.dockMusic).setOnClickListener { openMusicApp() }
        findViewById<View>(R.id.dockApps).setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        findViewById<View>(R.id.dockSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        setupVolume()
        onTrackChanged(null)
    }

    // ---------- Громкость ----------

    private fun setupVolume() {
        volumeBar = findViewById(R.id.volumeBar)
        volumeValue = findViewById(R.id.volumeValue)
        muteButton = findViewById(R.id.btnMute)

        volume = VolumeController(this) { level, max, muted -> showVolume(level, max, muted) }
        volumeBar.max = volume.max

        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) volume.set(value)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        muteButton.setOnClickListener { volume.toggleMute() }
    }

    private fun showVolume(level: Int, max: Int, muted: Boolean) {
        if (volumeBar.progress != level) volumeBar.progress = level
        volumeValue.text = if (max > 0) "${level * 100 / max}" else "0"
        // Иконку меняем только когда реально сменилось состояние
        val showMuted = muted || level == 0
        if (muteShown != showMuted) {
            muteShown = showMuted
            muteButton.setImageResource(if (showMuted) R.drawable.ic_volume_off else R.drawable.ic_volume)
            volumeValue.setTextColor(getColor(if (showMuted) R.color.mute_red else R.color.white_70))
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        val granted = music.hasPermission()
        permissionHint.visibility = if (granted) View.GONE else View.VISIBLE
        if (granted) music.start()
        volume.start()
    }

    override fun onPause() {
        super.onPause()
        // Пока лаунчер не на экране — ничего не обновляем и не тратим ресурсы
        music.stop()
        volume.stop()
    }

    // Кнопка "Назад" на главном экране ничего не делает
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

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
        if (art != null && art === currentArt) return
        currentArt = art

        if (art != null) cardArt.setImageBitmap(art)
        else cardArt.setImageResource(R.drawable.art_placeholder_big)

        val base = if (art != null) ArtColors.dominant(art) else DEFAULT_COLOR
        val accent = ArtColors.accent(base)

        // Отлив: насыщенный цвет обложки в левом верхнем углу, растворяется к центру
        cardTint.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(ArtColors.withAlpha(ArtColors.deep(base), 0xB0), Color.TRANSPARENT)
        )
        val accentTint = ColorStateList.valueOf(accent)
        progress.progressTintList = accentTint
        volumeBar.progressTintList = accentTint

        // Плавная смена обложки. withLayer() — анимация идёт на GPU одним слоем.
        cardArt.alpha = 0.3f
        cardArt.animate().alpha(1f).setDuration(350).withLayer().start()
    }

    override fun onPlayStateChanged(isPlaying: Boolean) {
        if (this.isPlaying == isPlaying && playPause.drawable != null) return
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
        // 2) Музыкальное приложение по умолчанию
        safeStart(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))
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

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        getString(R.string.music)
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
        private val DEFAULT_COLOR = Color.rgb(0xC2, 0x7A, 0x2A) // тёплый янтарь, когда нет обложки
    }
}
