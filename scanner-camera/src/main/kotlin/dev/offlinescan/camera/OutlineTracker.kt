package dev.offlinescan.camera

import dev.offlinescan.core.Detection
import dev.offlinescan.core.Geometry
import dev.offlinescan.core.Point
import dev.offlinescan.core.Quad
import kotlin.math.abs
import kotlin.math.hypot

/** Display-only corner smoothing. Never feed its output to capture decisions or source-image edits. */
class OutlineTracker(private val settings: Settings = Settings()) {
    data class Settings(
        val currentFrameWeight: Double = 0.65,
        val minimumConfidence: Double = 0.60,
        val maximumCornerStep: Double = 0.04,
        val sceneChangeDistance: Double = 0.12,
        val maximumFrameGapMs: Long = 600
    ) {
        init {
            require(currentFrameWeight > 0.0 && currentFrameWeight <= 1.0)
            require(minimumConfidence in 0.0..1.0)
            require(maximumCornerStep > 0.0 && maximumCornerStep.isFinite())
            require(sceneChangeDistance > 0.0 && sceneChangeDistance.isFinite())
            require(maximumFrameGapMs > 0)
        }
    }

    private var previousRaw: Quad? = null
    private var displayed: Quad? = null
    private var sceneAnchor: List<Double>? = null
    private var previousTime: Long? = null
    private var previousViewport: Long? = null

    /** viewportVersion identifies a crop/rotation/layout epoch; a change clears the old outline. */
    @Synchronized fun update(raw: Detection, nowMs: Long, viewportVersion: Long = 0): Detection {
        val corners = raw.corners
        if (corners == null || !Geometry.valid(corners) || !raw.confidence.isFinite() ||
            raw.confidence < settings.minimumConfidence) {
            reset()
            return raw.copy(corners = null)
        }
        val signature = raw.sceneSignature.takeIf { it.isNotEmpty() && it.all { value -> value.isFinite() && value in 0.0..1.0 } }
        val lastTime = previousTime
        val prior = previousRaw
        val anchor = sceneAnchor
        val discontinuity = prior == null || displayed == null || signature == null || anchor == null ||
            previousViewport != viewportVersion || lastTime == null || nowMs <= lastTime ||
            nowMs - lastTime > settings.maximumFrameGapMs ||
            (prior != null && prior.points.zip(corners.points).any { (a, b) -> hypot(a.x - b.x, a.y - b.y) > settings.maximumCornerStep }) ||
            (signature != null && anchor != null && (signature.size != anchor.size ||
                signature.zip(anchor).map { (a, b) -> abs(a - b) }.average() >= settings.sceneChangeDistance))
        val result = if (discontinuity) corners else blend(displayed!!, corners).takeIf { Geometry.valid(it) } ?: corners
        if (discontinuity) sceneAnchor = signature?.toList()
        previousRaw = corners
        displayed = result
        previousTime = nowMs
        previousViewport = viewportVersion
        return raw.copy(corners = result)
    }

    @Synchronized fun reset() {
        previousRaw = null; displayed = null; sceneAnchor = null; previousTime = null; previousViewport = null
    }

    private fun blend(previous: Quad, current: Quad): Quad {
        fun point(a: Point, b: Point) = Point(a.x + (b.x - a.x) * settings.currentFrameWeight,
            a.y + (b.y - a.y) * settings.currentFrameWeight)
        return Quad(point(previous.topLeft, current.topLeft), point(previous.topRight, current.topRight),
            point(previous.bottomRight, current.bottomRight), point(previous.bottomLeft, current.bottomLeft))
    }
}
