package dev.offlinescan.core

import kotlin.math.abs

/** Select outlines from one analysis stream. Call on a single worker with monotonic timestamps. */
class DocumentOutlineFallback {
    private var lastFailedProbe: List<Double>? = null
    private var previousTime = 0L

    /** Probe the first miss immediately. Skip one repeat after an unsuccessful probe in the same scene. */
    fun onFrame(ai: Detection, nowMs: Long, standard: () -> Detection): Detection {
        if (usable(ai)) {
            lastFailedProbe = null
            return ai
        }
        val signature = ai.sceneSignature
        val last = lastFailedProbe
        lastFailedProbe = null
        val validSignature = signature.isNotEmpty() && signature.all { it in 0.0..1.0 }
        if (last != null && validSignature && nowMs > previousTime && nowMs - previousTime <= 600 &&
            last.size == signature.size && last.indices.sumOf { abs(last[it] - signature[it]) } / last.size < 0.12)
            return ai
        val candidate = standard()
        if (usable(candidate)) return candidate
        previousTime = nowMs
        if (validSignature) lastFailedProbe = signature.toList()
        return ai
    }

    companion object {
        /** An acquired still has no later frame. Try Standard immediately if AI missed it. */
        fun onStill(ai: Detection, standard: () -> Detection): Detection =
            if (usable(ai)) ai else standard().takeIf(::usable) ?: ai

        /** Prefer measured page edges for a still when both detectors identify the same page. */
        fun refineStill(ai: Detection, standard: () -> Detection): Detection {
            val edge = standard()
            if (!usable(edge)) return ai
            if (!usable(ai)) return edge
            val reference = ai.corners!!
            val candidate = edge.corners!!
            if (Geometry.intersectionOverUnion(reference, candidate) < 0.90) return ai
            // A rotated page can have two plausible top-left corners. Keep the orientation
            // established by the learned detector when replacing the four positions.
            val points = candidate.points
            val referencePoints = reference.points
            val first = points.indices.minBy { offset ->
                referencePoints.indices.sumOf { i ->
                    val a = referencePoints[i]
                    val b = points[(i+offset)%4]
                    (a.x-b.x)*(a.x-b.x) + (a.y-b.y)*(a.y-b.y)
                }
            }
            return if (first == 0) edge else edge.copy(corners = Quad(
                points[first], points[(first+1)%4], points[(first+2)%4], points[(first+3)%4]))
        }

        private fun usable(result: Detection) = result.confidence in 0.60..1.0 &&
            result.corners?.let { Geometry.valid(it) } == true
    }
}
