package com.carhome.launcher

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Достаёт цвет из обложки без библиотеки Palette.
 * Обложка ужимается до 12x12 (144 пикселя), из них выбирается самый «сочный».
 * Занимает доли миллисекунды и вызывается только при смене трека.
 */
object ArtColors {

    fun dominant(art: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(art, 12, 12, true)
        val pixels = IntArray(144)
        small.getPixels(pixels, 0, 12, 0, 0, 12, 12)
        if (small !== art) small.recycle()

        val hsv = FloatArray(3)
        var best = 0
        var bestScore = -1f
        var r = 0L; var g = 0L; var b = 0L
        for (p in pixels) {
            r += Color.red(p); g += Color.green(p); b += Color.blue(p)
            Color.colorToHSV(p, hsv)
            if (hsv[2] < 0.2f) continue // слишком тёмный пиксель не берём
            val score = hsv[1] * hsv[2]
            if (score > bestScore) { bestScore = score; best = p }
        }
        // Обложка почти серая — берём средний цвет
        return if (bestScore < 0.15f) Color.rgb((r / 144).toInt(), (g / 144).toInt(), (b / 144).toInt())
        else best
    }

    /** Светлый вариант — для прогресс-бара. */
    fun accent(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = hsv[1].coerceAtMost(0.75f)
        hsv[2] = hsv[2].coerceAtLeast(0.92f)
        return Color.HSVToColor(hsv)
    }

    /** Тёмный насыщенный вариант — для цветного отлива на карточке. */
    fun deep(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = hsv[1].coerceAtLeast(0.5f)
        hsv[2] = hsv[2].coerceIn(0.3f, 0.55f)
        return Color.HSVToColor(hsv)
    }

    fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
}
