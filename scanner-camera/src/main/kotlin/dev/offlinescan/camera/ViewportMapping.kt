package dev.offlinescan.camera

import dev.offlinescan.core.Point
import kotlin.math.max
import kotlin.math.min

/** Centered coordinates of the already cropped, upright analysis image; FIT_CENTER by default. */
object ViewportMapping {
    fun toPreview(point: Point, imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int,
                  fillCenter: Boolean = false): Point {
        require(imageWidth > 0 && imageHeight > 0 && viewWidth > 0 && viewHeight > 0)
        val horizontal = viewWidth.toDouble() / imageWidth
        val vertical = viewHeight.toDouble() / imageHeight
        val scale = if (fillCenter) max(horizontal, vertical) else min(horizontal, vertical)
        return Point((viewWidth - imageWidth * scale) / 2 + point.x * imageWidth * scale,
            (viewHeight - imageHeight * scale) / 2 + point.y * imageHeight * scale)
    }
    fun fromPreview(point: Point, imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int,
                    fillCenter: Boolean = false): Point {
        val origin = toPreview(Point(0.0, 0.0), imageWidth, imageHeight, viewWidth, viewHeight, fillCenter)
        val end = toPreview(Point(1.0, 1.0), imageWidth, imageHeight, viewWidth, viewHeight, fillCenter)
        return Point((point.x - origin.x) / (end.x - origin.x), (point.y - origin.y) / (end.y - origin.y))
    }
}
