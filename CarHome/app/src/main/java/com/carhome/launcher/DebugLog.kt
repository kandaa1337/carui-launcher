package com.carhome.launcher

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Небольшой журнал последних событий для экрана диагностики. Живёт только в памяти. */
object DebugLog {
    private const val MAX = 40
    private val items = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(line: String) = synchronized(items) {
        if (items.firstOrNull()?.substringAfter(' ') == line) return@synchronized // без дублей подряд
        items.addFirst("${fmt.format(Date())} $line")
        while (items.size > MAX) items.removeLast()
    }

    fun dump(): String = synchronized(items) {
        if (items.isEmpty()) "—" else items.joinToString("\n")
    }
}
