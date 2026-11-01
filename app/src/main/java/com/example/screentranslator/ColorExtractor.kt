package com.example.screentranslator
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.max

data class ThemeColors(val backgroundColor: Int, val textColor: Int)

object ColorExtractor {
    fun extractColors(bitmap: Bitmap, rect: Rect): ThemeColors {
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val right = rect.right.coerceIn(0, bitmap.width - 1)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val bottom = rect.bottom.coerceIn(0, bitmap.height - 1)
        if (right - left < 4 || bottom - top < 4) return ThemeColors(Color.WHITE, Color.BLACK)

        val edgePixels = mutableListOf<Int>()
        val stepX = max(1, (right - left) / 6)
        val stepY = max(1, (bottom - top) / 4)
        for (x in left..right step stepX) {
            edgePixels.add(bitmap.getPixel(x, top + 1))
            edgePixels.add(bitmap.getPixel(x, bottom - 1))
        }
        for (y in top..bottom step stepY) {
            edgePixels.add(bitmap.getPixel(left + 1, y))
            edgePixels.add(bitmap.getPixel(right - 1, y))
        }
        val bgColor = edgePixels.groupBy {
            Color.rgb(Color.red(it) and 0xF8, Color.green(it) and 0xF8, Color.blue(it) and 0xF8)
        }.maxByOrNull { it.value.size }?.value?.first() ?: Color.WHITE

        val centerY = (top + bottom) / 2
        var maxContrastColor = bgColor
        var maxDiff = 0
        for (x in (left + 2) until (right - 2) step max(1, (right - left) / 25)) {
            val p = bitmap.getPixel(x, centerY)
            val d = abs(Color.red(bgColor) - Color.red(p)) + abs(Color.green(bgColor) - Color.green(p)) + abs(Color.blue(bgColor) - Color.blue(p))
            if (d > maxDiff) { maxDiff = d; maxContrastColor = p }
        }
        val textColor = if (maxDiff > 90 && ColorUtils.calculateContrast(maxContrastColor, bgColor) >= 3.0) {
            maxContrastColor
        } else if (ColorUtils.calculateLuminance(bgColor) > 0.5) Color.parseColor("#202124") else Color.parseColor("#E8EAED")
        return ThemeColors(bgColor, textColor)
    }
}
