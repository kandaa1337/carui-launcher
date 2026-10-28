package com.carhome.launcher

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Когда пользователь даёт этому сервису "доступ к уведомлениям", лаунчер получает право
 * видеть и управлять плеерами. Заодно пишем уведомления в журнал диагностики —
 * по ним видно, как Bluetooth-приложение магнитолы сообщает о треке.
 */
class MediaListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        DebugLog.add("listener: подключён")
    }

    override fun onListenerDisconnected() {
        DebugLog.add("listener: отключён")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val e = sbn.notification.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)
        val text = e.getCharSequence(Notification.EXTRA_TEXT)
        val hasSession = e.containsKey(Notification.EXTRA_MEDIA_SESSION)
        DebugLog.add("notif ${sbn.packageName} | $title | $text${if (hasSession) " | session" else ""}")
    }
}
