package dev.offlinescan.camera

import dev.offlinescan.core.Detection
import dev.offlinescan.core.Geometry
import dev.offlinescan.core.Quad
import kotlin.math.abs

/** Monotonic timestamps in milliseconds. All methods are synchronized for camera/UI callers. */
class AutoCaptureGate(val settings: Settings = Settings()) {
    data class Settings(
        val minimumConfidence: Double = 0.72,
        val minimumBrightness: Double = 0.18,
        val maximumBrightness: Double = 0.98,
        val minimumSharpness: Double = 60.0,
        val maximumCornerMotion: Double = 0.018,
        val stableDurationMs: Long = 900,
        val maximumFrameGapMs: Long = 600,
        val sceneChangeDistance: Double = 0.12,
        val sceneChangeDurationMs: Long = 400,
        val detectionInterruptionGraceMs: Long = 250,
        val minimumValidObservations: Int = 4,
        val minimumValidObservationFraction: Double = 0.80,
        val minimumObservedStableFraction: Double = 0.70
    ) {
        init {
            require(minimumConfidence in 0.0..1.0)
            require(minimumBrightness in 0.0..maximumBrightness && maximumBrightness <= 1.0)
            require(minimumSharpness >= 0.0 && maximumCornerMotion > 0.0)
            require(stableDurationMs > 0 && maximumFrameGapMs > 0)
            require(sceneChangeDistance > 0.0 && sceneChangeDurationMs > 0)
            require(detectionInterruptionGraceMs >= 0 && minimumValidObservations >= 2)
            require(minimumValidObservationFraction > 0.0 && minimumValidObservationFraction <= 1.0)
            require(minimumObservedStableFraction > 0.0 && minimumObservedStableFraction <= 1.0)
        }
    }

    private var knownDetailedScene: List<Double>? = null
    private var stableSince: Long? = null
    private var anchor: Quad? = null
    private var candidateSceneSignature: List<Double>? = null
    private var lastValidTime: Long? = null
    private var interruptionSince: Long? = null
    private var observedValidTimeMs = 0L
    private var validObservations = 0
    private var candidateObservations = 0
    private var lastTime: Long? = null
    private var changedSince: Long? = null
    private var lockedSignature: List<Double>? = null
    private var latestSignature: List<Double>? = null
    private var reservedSignature: List<Double>? = null
    private var captureSignature: List<Double>? = null
    private var capturing = false
    private var reservedAuto = false

    /** Returns true once when the host should invoke its auto-capture callback. */
    @Synchronized fun observe(detection: Detection, nowMs: Long): Boolean {
        val previousTime = lastTime
        if (previousTime != null && (nowMs < previousTime || nowMs - previousTime > settings.maximumFrameGapMs)) {
            clearStability()
            changedSince = null
        }
        lastTime = nowMs
        val signature = detection.sceneSignature.takeIf { it.isNotEmpty() && it.all { n -> n.isFinite() && n in 0.0..1.0 } }
        latestSignature = signature?.toList()
        val knownDetail = knownDetailedScene
        if (signature != null && knownDetail != null && (knownDetail.size != signature.size ||
                knownDetail.zip(signature).map { (a,b) -> abs(a-b) }.average() >= settings.sceneChangeDistance)) {
            knownDetailedScene = null
        }
        if (signature != null && detection.corners != null && detection.documentDetailTiles in 2..9) {
            knownDetailedScene = signature.toList()
        }
        val locked = if (reservedAuto) reservedSignature else lockedSignature
        if (signature == null) changedSince = null
        if (!capturing && locked != null && signature != null) {
            val distance = if (locked.size != signature.size) 0.0 else locked.zip(signature).map { (a, b) -> abs(a - b) }.average()
            if (distance >= settings.sceneChangeDistance) {
                val since = changedSince ?: nowMs.also { changedSince = it }
                if (nowMs - since >= settings.sceneChangeDurationMs) {
                    lockedSignature = null
                    reservedAuto = false
                    reservedSignature = null
                    changedSince = null
                    clearStability()
                }
            } else changedSince = null
        }
        if (capturing || reservedAuto || lockedSignature != null) {
            clearStability()
            return false
        }
        val quad = detection.corners
        val imageQuality = signature != null && detection.brightness in settings.minimumBrightness..settings.maximumBrightness &&
            detection.sharpness.isFinite() && detection.sharpness >= 0.0 && (detection.sharpness >= settings.minimumSharpness ||
                (quad != null && detection.documentDetailTiles in 0..1 && knownDetailedScene == null))
        val sameScene = candidateSceneSignature?.let { candidate ->
            signature != null && candidate.size == signature.size &&
                candidate.zip(signature).map { (a, b) -> abs(a - b) }.average() < settings.sceneChangeDistance
        } ?: false
        val acceptable = quad != null && Geometry.valid(quad, 0.03) && imageQuality &&
            detection.confidence.isFinite() && detection.confidence >= settings.minimumConfidence
        if (!acceptable) {
            // A brief contour-only miss may keep evidence, but its frame never triggers a shutter.
            // Low-confidence/invalid candidate corners and image-quality failures clear immediately.
            if (quad == null && imageQuality && detection.confidence.isFinite() && sameScene && stableSince != null) {
                val since = interruptionSince ?: nowMs.also { interruptionSince = it }
                if (nowMs - since <= settings.detectionInterruptionGraceMs) {
                    candidateObservations++
                    return false
                }
            }
            clearStability()
            return false
        }
        val oldAnchor = anchor
        val interruptionExpired = interruptionSince?.let { nowMs - it > settings.detectionInterruptionGraceMs } ?: false
        if (oldAnchor == null || !sameScene || interruptionExpired || Geometry.distance(oldAnchor, quad!!) > settings.maximumCornerMotion) {
            beginStability(quad!!, signature!!, nowMs)
            return false
        }
        candidateObservations++
        validObservations++
        if (interruptionSince == null) lastValidTime?.let { observedValidTimeMs += nowMs - it }
        // Do not credit the interval spanning missing corners as observed stable time.
        lastValidTime = nowMs
        interruptionSince = null
        if (nowMs - (stableSince ?: nowMs) < settings.stableDurationMs) return false
        if (validObservations < settings.minimumValidObservations ||
            validObservations.toDouble() / candidateObservations < settings.minimumValidObservationFraction ||
            observedValidTimeMs < settings.stableDurationMs * settings.minimumObservedStableFraction) return false
        reservedSignature = signature!!.toList()
        reservedAuto = true
        clearStability()
        return true
    }

    /** A manual shutter may capture the same page again; only concurrent captures are rejected. */
    @Synchronized fun captureStarted(sceneSignature: List<Double>? = null): Boolean {
        if (capturing) return false
        capturing = true
        // Reserve the scene at shutter request; later analysis may already show the next page.
        captureSignature = (if (reservedAuto) reservedSignature else
            sceneSignature?.takeIf { it.isNotEmpty() && it.all { value -> value.isFinite() } } ?: latestSignature)?.toList()
        reservedAuto = false
        reservedSignature = null
        clearStability()
        return true
    }

    @Synchronized fun captureFinished(success: Boolean) {
        if (!capturing) return
        capturing = false
        reservedAuto = false
        reservedSignature = null
        if (success) captureSignature?.let { lockedSignature = it.toList() }
        captureSignature = null
        clearStability()
        changedSince = null
    }

    /** Cancel an unconsumed auto reservation, keeping successful-page locks and in-flight captures. */
    @Synchronized fun pause() {
        clearStability(); lastTime = null; changedSince = null
        reservedAuto = false; reservedSignature = null; latestSignature = null
    }
    @Synchronized fun reset() {
        pause(); knownDetailedScene = null; lockedSignature = null; latestSignature = null; captureSignature = null; capturing = false
    }
    private fun beginStability(quad: Quad, signature: List<Double>, nowMs: Long) {
        clearStability()
        anchor = quad; candidateSceneSignature = signature.toList(); stableSince = nowMs; lastValidTime = nowMs
        validObservations = 1; candidateObservations = 1
    }
    private fun clearStability() {
        stableSince = null; anchor = null; candidateSceneSignature = null; lastValidTime = null; interruptionSince = null
        observedValidTimeMs = 0; validObservations = 0; candidateObservations = 0
    }
}

/** Skips work before bitmap conversion. The analyzer still closes every skipped frame. */
class AnalysisThrottle(private val intervalMs: Long = 180) {
    init { require(intervalMs > 0) }
    private var lastAccepted: Long? = null
    @Synchronized fun accept(nowMs: Long): Boolean {
        val previous = lastAccepted
        if (previous != null && nowMs >= previous && nowMs - previous < intervalMs) return false
        lastAccepted = nowMs
        return true
    }
    @Synchronized fun reset() { lastAccepted = null }
}
