package com.carhome.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel

/**
 * Прямое подключение к Bluetooth-сервису магнитолы NWD (com.bt.bc03) — ровно так же,
 * как это делает родное приложение BT Music. Интерфейс "com.bt.BTFeature" найден в прошивке:
 *   23 playControl(int)   — 0 = play/pause, 2 = следующий, 3 = предыдущий
 *   24 isBTMusicPlaying()
 *   60 queryA2dpId3Info() — сервис заново рассылает название трека
 * При подключении сервис только опрашивает своё состояние, ничего не сбрасывает.
 */
class NwdBtClient(private val context: Context) {

    private var binder: IBinder? = null
    private var bound = false
    var status = "не подключался"
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            status = "подключён"
            DebugLog.add("BT-сервис NWD: подключён")
            queryId3()
            onConnected?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            status = "отключился"
            DebugLog.add("BT-сервис NWD: отключился")
        }
    }

    val isConnected get() = binder != null

    /** Вызывается, когда подключились к сервису */
    var onConnected: (() -> Unit)? = null

    fun bind() {
        if (bound) return
        val intent = Intent(ACTION_BIND).setPackage(PACKAGE)
        bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            status = "ошибка: ${e.message}"
            false
        }
        if (!bound && status == "не подключался") status = "не удалось подключиться"
    }

    fun unbind() {
        if (!bound) return
        try { context.unbindService(connection) } catch (_: Exception) {}
        bound = false
        binder = null
    }

    /** true — команда ушла в сервис */
    fun playControl(cmd: Int): Boolean {
        val ok = call(TX_PLAY_CONTROL) { it.writeInt(cmd) } != null
        DebugLog.add("BT-сервис NWD: playControl($cmd) ${if (ok) "ок" else "не прошло"}")
        return ok
    }

    fun isPlaying(): Boolean? = call(TX_IS_PLAYING) {}?.let { it != 0 }

    fun queryId3() {
        call(TX_QUERY_ID3) {}
    }

    /** Вызов метода сервиса. Возвращает int из ответа (0, если метод ничего не возвращает) или null при ошибке. */
    private fun call(code: Int, write: (Parcel) -> Unit): Int? {
        val b = binder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            b.transact(code, data, reply, 0)
            reply.readException()
            if (reply.dataAvail() >= 4) reply.readInt() else 0
        } catch (e: Exception) {
            DebugLog.add("BT-сервис NWD: вызов $code — ошибка ${e.message}")
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    companion object {
        const val PACKAGE = "com.bt.bc03"
        private const val ACTION_BIND = "com.bt.ACTION_START_SERVICE"
        private const val DESCRIPTOR = "com.bt.BTFeature"
        private const val TX_PLAY_CONTROL = 23
        private const val TX_IS_PLAYING = 24
        private const val TX_QUERY_ID3 = 60

        // Коды playControl (как у кнопок BT Music)
        const val CMD_PLAY_PAUSE = 0
        const val CMD_NEXT = 2
        const val CMD_PREV = 3
    }
}
