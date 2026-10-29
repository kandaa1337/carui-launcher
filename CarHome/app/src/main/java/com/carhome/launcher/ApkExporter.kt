package com.carhome.launcher

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

/**
 * Копирует APK системных BT/музыкальных приложений магнитолы в "Загрузки/CarHome",
 * чтобы их можно было разобрать и понять, как они передают название трека.
 * Ничего не меняет в системе — только читает файлы.
 */
object ApkExporter {

    /** Пакеты, которые интересны: всё, что похоже на BT и музыку производителя. */
    private val PATTERN = Regex("nwd|bc03|carkit|mcu|canbus|(bt|blue).*(music|service)", RegexOption.IGNORE_CASE)
    /** Системные библиотеки производителя (там часто живёт управление громкостью через MCU) */
    private val JAR_PATTERN = Regex("nwd|mcu|car|canbus|bc03", RegexOption.IGNORE_CASE)
    private val SKIP = setOf("com.android.bluetooth", "com.android.bluetoothmidiservice", "com.android.providers.media")

    /** Возвращает список сохранённых файлов. Вызывать НЕ в главном потоке. */
    fun export(context: Context): List<String> {
        val pm = context.packageManager
        val saved = mutableListOf<String>()
        val apps = pm.getInstalledApplications(0)
            .filter { PATTERN.containsMatchIn(it.packageName) && it.packageName !in SKIP }

        for (app in apps) {
            val files = listOf(app.sourceDir) + (app.splitSourceDirs?.toList() ?: emptyList())
            files.forEachIndexed { i, path ->
                val suffix = if (i == 0) "" else "_split$i"
                val name = "${app.packageName}$suffix.apk"
                try {
                    save(context, File(path), name)
                    saved += name
                } catch (e: Exception) {
                    DebugLog.add("export $name: ошибка ${e.message}")
                }
            }
        }

        // Jar-файлы фреймворка с "nwd/mcu/car" в имени
        for (dir in listOf("/system/framework", "/system_ext/framework", "/vendor/framework")) {
            File(dir).listFiles()?.filter { it.isFile && JAR_PATTERN.containsMatchIn(it.name) }?.forEach { f ->
                try {
                    save(context, f, "framework_${f.name}")
                    saved += f.name
                } catch (e: Exception) {
                    DebugLog.add("export ${f.name}: ошибка ${e.message}")
                }
            }
        }
        return saved
    }

    private fun save(context: Context, src: File, name: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            // Android 10+: пишем в "Загрузки" через MediaStore, разрешения не нужны
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CarHome")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("не удалось создать файл")
            context.contentResolver.openOutputStream(uri)!!.use { out ->
                FileInputStream(src).use { it.copyTo(out) }
            }
        } else {
            // Старые Android: папка приложения на карте памяти
            val dir = File(context.getExternalFilesDir(null), "export").apply { mkdirs() }
            FileInputStream(src).use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
        }
    }
}
