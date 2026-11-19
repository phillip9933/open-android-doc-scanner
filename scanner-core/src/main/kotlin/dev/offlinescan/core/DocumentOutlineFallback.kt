package dev.offlinescan.core

import kotlin.math.abs

/** Select outlines from one analysis stream. Call on a single worker with monotonic timestamps. */
class DocumentOutlineFallback {
    private var previousMiss: List<Double>? = null
    private var previousTime = 0L

    /** A second similar missed frame can use Standard. Every returned outline belongs to this frame. */
    fun onFrame(ai: Detection, nowMs: Long, standard: () -> Detection): Detection {
        if (usable(ai)) {
            previousMiss = null
            return ai
        }
        val signature = ai.sceneSignature
        val last = previousMiss
        val consecutive = last != null && nowMs > previousTime && nowMs - previousTime <= 600 &&
            last.size == signature.size && last.indices.sumOf { abs(last[it] - signature[it]) } / last.size < 0.12
        previousTime = nowMs
        previousMiss = signature.takeIf { it.isNotEmpty() && it.all { value -> value in 0.0..1.0 } }?.toList()
        if (!consecutive || previousMiss == null) return ai
        val candidate = standard()
        if (usable(candidate)) return candidate
        // Probe every other frame in an empty scene. Keep probing each frame while Standard succeeds.
        previousMiss = null
        return ai
    }

    companion object {
        /** An acquired still has no later frame. Try Standard immediately if AI missed it. */
        fun onStill(ai: Detection, standard: () -> Detection): Detection =
            if (usable(ai)) ai else standard().takeIf(::usable) ?: ai

        private fun usable(result: Detection) = result.confidence in 0.60..1.0 &&
            result.corners?.let { Geometry.valid(it) } == true
    }
}
