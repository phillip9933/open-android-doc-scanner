package dev.offlinescan.ocr

import dev.offlinescan.core.Cancellation
import kotlin.math.*

/** Bounded projection search for a single dark-text line; returns clockwise degrees. */
internal object LineSkew {
    fun estimate(width: Int, height: Int, dark: BooleanArray, cancellation: Cancellation): Float {
        require(width > 0 && height > 0 && dark.size == width * height)
        val points = dark.indices.filter { dark[it] }
        // Empty, nearly blank and mostly filled rectangles are not reliable text lines.
        if (points.size < 20 || points.size > dark.size * 0.6) return 0f
        val span = ceil(hypot(width.toDouble(), height.toDouble())).toInt() + 4
        fun score(angle: Int): Double {
            cancellation.check()
            val rad = Math.toRadians(angle.toDouble())
            val sine = sin(rad); val cosine = cos(rad)
            val rows = IntArray(span)
            for (index in points) {
                val x = index % width - width / 2.0
                val y = index / width - height / 2.0
                val row = (sine*x + cosine*y + span/2).roundToInt().coerceIn(0,span-1)
                rows[row]++
            }
            return rows.sumOf { it.toDouble()*it }
        }
        val baseline = score(0)
        var best = baseline; var angle = 0
        for (candidate in -12..12) {
            val value = score(candidate)
            if (value > best) { best = value; angle = candidate }
        }
        // Require a meaningful gain; boundary optima may indicate out-of-range tilt.
        return if (best > baseline*1.05 && abs(angle) < 12) angle.toFloat() else 0f
    }
}
