package com.carhome.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build

/**
 * Громкость музыки + mute одной кнопкой.
 * Mute = запомнить текущую громкость и поставить 0. Повторное нажатие — вернуть как было.
 * Ставим громкость напрямую (а не через системный mute), потому что на китайских
 * магнитолах системный mute часто не доходит до усилителя.
 */
class VolumeController(private val context: Context, private val onChange: (level: Int, max: Int, muted: Boolean) -> Unit) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = context.getSharedPreferences("volume", Context.MODE_PRIVATE)
    private val stream = AudioManager.STREAM_MUSIC

    val max: Int = audio.getStreamMaxVolume(stream)

    /** Громкость до mute. -1 — mute не включён. Хранится, чтобы пережить перезагрузку. */
    private var savedLevel: Int
        get() = prefs.getInt("saved", -1)
        set(v) = prefs.edit().putInt("saved", v).apply()

    val level: Int get() = audio.getStreamVolume(stream)
    val isMuted: Boolean get() = savedLevel >= 0 && level == 0

    // Громкость поменяли снаружи (кнопки на руле, ручка, другое приложение)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != stream) return
            if (level > 0 && savedLevel >= 0) savedLevel = -1 // звук вернули вручную — mute снят
            publish()
        }
    }
    private var registered = false

    fun start() {
        if (registered) return
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        registered = true
        publish()
    }

    fun stop() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
    }

    /** Ползунок. Флаг 0 — без системного всплывающего окна громкости. */
    fun set(value: Int) {
        val v = value.coerceIn(0, max)
        if (v == level) return
        if (v > 0) savedLevel = -1
        audio.setStreamVolume(stream, v, 0)
        publish()
    }

    fun toggleMute() {
        if (isMuted) {
            val restore = savedLevel.takeIf { it > 0 } ?: (max * 0.3f).toInt().coerceAtLeast(1)
            savedLevel = -1
            audio.setStreamVolume(stream, restore, 0)
        } else {
            savedLevel = level.takeIf { it > 0 } ?: (max * 0.3f).toInt().coerceAtLeast(1)
            audio.setStreamVolume(stream, 0, 0)
        }
        publish()
    }

    private fun publish() = onChange(level, max, isMuted)
}
