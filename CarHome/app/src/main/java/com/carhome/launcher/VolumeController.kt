package com.carhome.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Громкость + mute одной кнопкой.
 *
 * Два режима:
 *  - Магнитола NWD: громкость живёт в микроконтроллере. Текущая — в Settings.System
 *    "mcu_system_volume", максимум — "mcu_max_volume". Поставить — сигналом
 *    com.nwd.action.ACTION_REQUEST_SET_SYSTEM_VOLUME (extra_volume). Так делают и родные
 *    приложения прошивки.
 *  - Обычный Android: AudioManager.STREAM_MUSIC.
 *
 * Mute = запомнить текущую громкость и поставить 0. Повторное нажатие — вернуть как было.
 */
class VolumeController(private val context: Context, private val onChange: (level: Int, max: Int, muted: Boolean) -> Unit) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cr = context.contentResolver
    private val prefs = context.getSharedPreferences("volume", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val stream = AudioManager.STREAM_MUSIC

    /** Магнитола NWD: громкость через её сервис, а не через Android */
    val isNwd: Boolean = Settings.System.getString(cr, KEY_NWD_VOLUME) != null

    val max: Int
        get() = if (isNwd) Settings.System.getInt(cr, KEY_NWD_MAX, 40).takeIf { it > 0 } ?: 40
        else audio.getStreamMaxVolume(stream)

    val level: Int
        get() = if (isNwd) Settings.System.getInt(cr, KEY_NWD_VOLUME, 0)
        else audio.getStreamVolume(stream)

    /** Громкость до mute. -1 — mute не включён. Хранится, чтобы пережить перезагрузку. */
    private var savedLevel: Int
        get() = prefs.getInt("saved", -1)
        set(v) = prefs.edit().putInt("saved", v).apply()

    val isMuted: Boolean get() = savedLevel >= 0 && level == 0

    // --- Слежение за изменениями громкости снаружи (ручка, кнопки на руле) ---

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            DebugLog.add("volume NWD: $level/$max")
            externalChange()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != stream) return
            externalChange()
        }
    }

    private fun externalChange() {
        if (level > 0 && savedLevel >= 0) savedLevel = -1 // звук вернули вручную — mute снят
        if (pendingValue < 0) publish() // пока пользователь тянет полосу — не дёргаем её назад
    }

    private var registered = false

    fun start() {
        if (registered) return
        if (isNwd) {
            cr.registerContentObserver(Settings.System.getUriFor(KEY_NWD_VOLUME), false, observer)
            cr.registerContentObserver(Settings.System.getUriFor(KEY_NWD_MAX), false, observer)
        } else {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
        }
        registered = true
        publish()
    }

    fun stop() {
        if (!registered) return
        if (isNwd) cr.unregisterContentObserver(observer) else context.unregisterReceiver(receiver)
        registered = false
        main.removeCallbacks(flush)
        pendingValue = -1
    }

    // --- Установка ---

    /**
     * Полосу тянут пальцем — значения идут потоком. В NWD каждое значение уходит
     * в микроконтроллер, поэтому шлём не чаще раза в 80 мс, но последнее — обязательно.
     */
    private var pendingValue = -1
    private val flush = Runnable {
        val v = pendingValue
        pendingValue = -1
        if (v >= 0) apply(v)
    }

    fun set(value: Int) {
        val v = value.coerceIn(0, max)
        if (v > 0) savedLevel = -1
        if (!isNwd) {
            if (v != level) audio.setStreamVolume(stream, v, 0)
            publish()
            return
        }
        val first = pendingValue < 0
        pendingValue = v
        if (first) main.postDelayed(flush, 80)
    }

    private fun apply(v: Int) {
        DebugLog.add("volume: ставлю $v/$max")
        if (isNwd) {
            context.sendBroadcast(Intent(ACTION_NWD_SET_VOLUME).putExtra("extra_volume", v))
        } else {
            audio.setStreamVolume(stream, v, 0)
        }
    }

    fun toggleMute() {
        DebugLog.add("volume: mute нажат")
        main.removeCallbacks(flush)
        pendingValue = -1
        if (isMuted) {
            val restore = savedLevel.takeIf { it > 0 } ?: (max * 0.3f).toInt().coerceAtLeast(1)
            savedLevel = -1
            apply(restore)
            onChange(restore, max, false) // сразу показываем, не дожидаясь ответа магнитолы
        } else {
            savedLevel = level.takeIf { it > 0 } ?: (max * 0.3f).toInt().coerceAtLeast(1)
            apply(0)
            onChange(0, max, true)
        }
    }

    private fun publish() = onChange(level, max, isMuted)

    companion object {
        private const val KEY_NWD_VOLUME = "mcu_system_volume"
        private const val KEY_NWD_MAX = "mcu_max_volume"
        private const val ACTION_NWD_SET_VOLUME = "com.nwd.action.ACTION_REQUEST_SET_SYSTEM_VOLUME"
    }
}
