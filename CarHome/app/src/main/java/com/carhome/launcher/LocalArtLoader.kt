package com.carhome.launcher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size

/**
 * Обложка для музыки с памяти магнитолы. Встроенный плеер NWD берёт треки из медиатеки
 * Android (MediaStore) и в сигнале о смене трека передаёт их номер "id".
 * Ищем трек в медиатеке (по номеру, иначе по названию) и пробуем по очереди:
 *   1) миниатюру от Android (Android 10+)
 *   2) обложку альбома
 *   3) картинку, встроенную в сам файл
 * Всё вызывается в фоновом потоке и только при смене трека.
 */
object LocalArtLoader {

    private const val MAX_SIDE = 640

    fun permission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context) =
        context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED

    fun load(context: Context, id: Long?, title: String?, artist: String?): Bitmap? {
        if (!hasPermission(context)) {
            DebugLog.add("обложка: нет доступа к музыке")
            return null
        }
        val found = find(context, id, title, artist) ?: run {
            DebugLog.add("обложка: трек не найден в медиатеке (id=$id, $title)")
            return null
        }
        val (audioId, albumId, path) = found
        val cr = context.contentResolver

        // 1) Миниатюра от Android: сама достаёт картинку из файла и уменьшает
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audioId)
                return cr.loadThumbnail(uri, Size(MAX_SIDE, MAX_SIDE), null).also {
                    DebugLog.add("обложка: миниатюра Android")
                }
            } catch (_: Exception) { }
        }

        // 2) Обложка альбома
        if (albumId > 0) {
            try {
                val uri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
                decodeStream(context, uri)?.let {
                    DebugLog.add("обложка: альбом")
                    return it
                }
            } catch (_: Exception) { }
        }

        // 3) Картинка внутри файла
        if (path != null) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(path)
                mmr.embeddedPicture?.let { bytes ->
                    DebugLog.add("обложка: из файла")
                    return decodeBytes(bytes)
                }
            } catch (_: Exception) {
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            }
        }
        DebugLog.add("обложка: в файле её нет")
        return null
    }

    /** Возвращает (id трека, id альбома, путь к файлу). */
    private fun find(context: Context, id: Long?, title: String?, artist: String?): Triple<Long, Long, String?>? {
        val cr = context.contentResolver
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DATA)

        fun query(sel: String, args: Array<String>): Triple<Long, Long, String?>? = try {
            cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, args, null)?.use { c ->
                if (c.moveToFirst()) Triple(c.getLong(0), c.getLong(1), c.getString(2)) else null
            }
        } catch (_: Exception) { null }

        if (id != null && id > 0) query("${MediaStore.Audio.Media._ID}=?", arrayOf(id.toString()))?.let { return it }
        if (title.isNullOrBlank()) return null
        if (!artist.isNullOrBlank()) {
            query("${MediaStore.Audio.Media.TITLE}=? AND ${MediaStore.Audio.Media.ARTIST}=?", arrayOf(title, artist))
                ?.let { return it }
        }
        return query("${MediaStore.Audio.Media.TITLE}=?", arrayOf(title))
    }

    private fun decodeStream(context: Context, uri: Uri): Bitmap? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight) }
        return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun decodeBytes(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** Уменьшаем прямо при чтении — в память не попадает огромная картинка. */
    private fun sample(w: Int, h: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= MAX_SIDE) s *= 2
        return s
    }
}
