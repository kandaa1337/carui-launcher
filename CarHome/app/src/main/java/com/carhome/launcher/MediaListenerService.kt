package com.carhome.launcher

import android.service.notification.NotificationListenerService

/**
 * Пустой сервис. Сам по себе ничего не делает, но когда пользователь даёт ему
 * "доступ к уведомлениям", лаунчер получает право видеть и управлять
 * плеерами (Spotify, YouTube Music, VK, Bluetooth-аудио и т.д.).
 */
class MediaListenerService : NotificationListenerService()
