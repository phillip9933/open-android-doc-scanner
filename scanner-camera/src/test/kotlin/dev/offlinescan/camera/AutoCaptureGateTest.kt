package dev.offlinescan.camera

import dev.offlinescan.core.Detection
import dev.offlinescan.core.Geometry
import dev.offlinescan.core.Point
import dev.offlinescan.core.Quad
import org.junit.Assert.*
import org.junit.Test

class AutoCaptureGateTest {
    @Test fun genuineBlankPageCanStabilizeWithoutPrintedTexture() {
        val blank = frame().copy(sharpness = 0.0, documentDetailTiles = 0)
        assertTrue(stable(AutoCaptureGate(), 0, blank))
    }

    @Test fun lowSharpnessRequiresKnownLowDetailAndSupportedDocumentGeometry() {
        val rejected = listOf(frame().copy(sharpness = 0.0, documentDetailTiles = null),
            frame().copy(sharpness = 0.0, documentDetailTiles = 3),
            frame().copy(sharpness = 0.0, documentDetailTiles = -1),
            frame().copy(sharpness = 0.0, documentDetailTiles = 10),
            frame().copy(sharpness = 0.0, documentDetailTiles = 0, corners = null),
            frame().copy(sharpness = Double.NaN, documentDetailTiles = 0),
            frame().copy(sharpness = -1.0, documentDetailTiles = 0))
        for (bad in rejected) {
            val gate = AutoCaptureGate()
            for (time in 0L..2100L step 300) assertFalse("Rejected quality: $bad", gate.observe(bad, time))
        }
    }

    @Test fun textDisappearingUnderBlurCannotRequalifyAsBlankInTheSameScene() {
        val gate = AutoCaptureGate()
        val detailed = frame().copy(documentDetailTiles = 6)
        val vanished = detailed.copy(sharpness = 0.0, documentDetailTiles = 0)
        assertFalse(gate.observe(detailed, 0))
        assertFalse(gate.observe(detailed, 300))
        for (time in 600L..5400L step 300) {
            assertFalse("Blur cannot erase known detail and become a blank-page bypass", gate.observe(vanished, time))
        }
        assertTrue(stable(gate, 5700, detailed))
    }

    private fun frame(tone: Double = 0.5, dx: Double = 0.0) = Detection(
        Quad(Point(0.1 + dx, 0.1), Point(0.8 + dx, 0.1), Point(0.8 + dx, 0.9), Point(0.1 + dx, 0.9)),
        0.9, 0.55, 140.0, List(64) { tone })
    private fun stable(gate: AutoCaptureGate, start: Long, detection: Detection = frame()): Boolean {
        assertFalse(gate.observe(detection, start))
        assertFalse(gate.observe(detection, start + 300))
        assertFalse(gate.observe(detection, start + 600))
        return gate.observe(detection, start + 900)
    }

    @Test fun requiresDurationAndLocksDuplicateUntilSceneChanges() {
        val gate = AutoCaptureGate()
        assertTrue(stable(gate, 0))
        assertTrue(gate.captureStarted())
        gate.captureFinished(true)
        for (time in 1200L..3000L step 300) assertFalse(gate.observe(frame(), time))
        assertFalse(gate.observe(frame(0.8), 3300))
        assertFalse(gate.observe(frame(0.8), 3600))
        assertFalse(gate.observe(frame(0.8), 3900)) // scene change unlocks and starts a fresh stability window
        assertFalse(gate.observe(frame(0.8), 4200))
        assertFalse(gate.observe(frame(0.8), 4500))
        assertTrue(gate.observe(frame(0.8), 4800))
    }

    @Test fun lowQualityResetsTheEntireStabilityWindow() {
        val failures = listOf(frame().copy(confidence = 0.3), frame().copy(brightness = 0.1),
            frame().copy(brightness = 0.99), frame().copy(sharpness = 10.0),
            frame().copy(corners = null), frame().copy(confidence = Double.NaN),
            frame().copy(sceneSignature = emptyList()))
        for (bad in failures) {
            val gate = AutoCaptureGate()
            assertFalse(gate.observe(frame(), 0))
            assertFalse(gate.observe(frame(), 300))
            assertFalse(gate.observe(bad, 600))
            assertTrue(stable(gate, 900))
        }
    }

    @Test fun supportedSharpLightPageCanCaptureDespiteHighMeanBrightness() {
        // Measured RC3 tuning-faint-light detector metrics; mean brightness alone is not clipping quality.
        val lightPage = frame().copy(confidence = 0.8902088297112686,
            brightness = 0.9205627450980393, sharpness = 119.56743333333334)
        assertTrue(stable(AutoCaptureGate(), 0, lightPage))
    }

    @Test fun highMeanBrightnessDoesNotBypassUniformDarkBlurOrGeometryRejection() {
        val lightPage = frame().copy(brightness = 0.95)
        val rejected = listOf(lightPage.copy(sharpness = 0.0), lightPage.copy(brightness = 1.0, sharpness = 0.0),
            lightPage.copy(brightness = 0.01), lightPage.copy(sharpness = 10.0),
            lightPage.copy(corners = null), lightPage.copy(confidence = 0.4),
            lightPage.copy(corners = Quad(Point(0.1, 0.1), Point(0.8, 0.9), Point(0.8, 0.1), Point(0.1, 0.9))))
        for (bad in rejected) {
            val gate = AutoCaptureGate()
            for (time in 0L..1800L step 300) assertFalse(gate.observe(bad, time))
            assertTrue(stable(gate, 2100, lightPage))
        }
    }

    @Test fun subThreePercentReceiptsRemainBelowTheAutomaticCaptureAreaFloor() {
        val tooSmall = frame().copy(corners = Quad(Point(0.4, 0.2), Point(0.45, 0.2), Point(0.45, 0.6), Point(0.4, 0.6)))
        assertEquals(0.02, Geometry.area(tooSmall.corners!!), 1e-12)
        val gate = AutoCaptureGate()
        for (time in 0L..1800L step 300) assertFalse(gate.observe(tooSmall, time))
    }

    @Test fun gradualMotionIsMeasuredAgainstTheWindowAnchor() {
        val gate = AutoCaptureGate()
        assertFalse(gate.observe(frame(dx = 0.00), 0))
        assertFalse(gate.observe(frame(dx = 0.01), 300))
        assertFalse(gate.observe(frame(dx = 0.02), 600))
        assertFalse(gate.observe(frame(dx = 0.03), 900))
        assertFalse(gate.observe(frame(dx = 0.04), 1200))
        assertFalse(gate.observe(frame(dx = 0.04), 1500))
        assertFalse(gate.observe(frame(dx = 0.04), 1800))
        assertTrue(gate.observe(frame(dx = 0.04), 2100))
    }

    @Test fun droppedFramesAndBackwardTimeCannotCountAsStability() {
        val gate = AutoCaptureGate()
        assertFalse(gate.observe(frame(), 1000))
        assertFalse(gate.observe(frame(), 1300))
        assertFalse(gate.observe(frame(), 2500))
        assertFalse(gate.observe(frame(), 2800))
        assertTrue(stable(gate, 100)) // clock reversal starts a new window
    }

    @Test fun transientDifferentSceneDoesNotUnlockAndCornerMovementAloneDoesNotUnlock() {
        val gate = AutoCaptureGate()
        assertTrue(stable(gate, 0))
        gate.captureStarted(); gate.captureFinished(true)
        assertFalse(gate.observe(frame(0.9), 1200))
        assertFalse(gate.observe(frame(), 1500))
        for (time in 1800L..3600L step 300) assertFalse(gate.observe(frame(dx = 0.1), time))
    }

    @Test fun shutterAcceptsDuplicatesButRejectsConcurrentCapture() {
        val gate = AutoCaptureGate()
        assertTrue(stable(gate, 0))
        assertTrue(gate.captureStarted())
        assertFalse(gate.captureStarted())
        assertFalse(gate.observe(frame(0.9), 1200))
        gate.captureFinished(true)
        assertTrue(gate.captureStarted())
        assertFalse(gate.captureStarted())
    }

    @Test fun pauseDropsStabilityAndPreservesDuplicateLockWhileResetClearsIt() {
        val gate = AutoCaptureGate()
        assertFalse(gate.observe(frame(), 0))
        assertFalse(gate.observe(frame(), 300))
        gate.pause()
        assertTrue(stable(gate, 1000))
        gate.captureStarted(); gate.captureFinished(true)
        gate.pause()
        assertFalse(gate.observe(frame(), 3000))
        gate.reset()
        assertTrue(stable(gate, 4000))
    }

    @Test fun failedCaptureCanRetryAfterFreshStability() {
        val gate = AutoCaptureGate()
        assertTrue(stable(gate, 0))
        gate.captureStarted(); gate.captureFinished(false)
        assertTrue(stable(gate, 1200))
    }

    @Test fun autoCallbackReservationDoesNotRepeatWithoutAnActualCapture() {
        val gate = AutoCaptureGate()
        assertTrue(stable(gate, 0))
        for (time in 1200L..3000L step 300) assertFalse(gate.observe(frame(), time))
        assertTrue(gate.captureStarted()) // the manual shutter is still usable
    }

    @Test fun disablingReleasesUnconsumedReservationAndResumeRequiresFreshStability() {
        val gate = AutoCaptureGate()
        assertTrue(stable(gate, 0))
        gate.pause() // host became busy and declined the outstanding auto request
        assertTrue(stable(gate, 1200)) // same scene can retry because it was never captured
    }

    @Test fun disablingAndResumingKeepsSuccessfulPageLockedUntilSceneChange() {
        val gate = AutoCaptureGate()
        assertTrue(stable(gate, 0))
        gate.captureStarted(); gate.captureFinished(true)
        gate.pause()
        for (time in 1200L..3000L step 300) assertFalse(gate.observe(frame(), time))
        assertFalse(gate.observe(frame(0.8), 3300))
        assertFalse(gate.observe(frame(0.8), 3600))
        assertFalse(gate.observe(frame(0.8), 3900))
        assertFalse(gate.observe(frame(0.8), 4200))
        assertFalse(gate.observe(frame(0.8), 4500))
        assertTrue(gate.observe(frame(0.8), 4800))
    }

    @Test fun disablingDoesNotEndManualCaptureOrRelockTheNextPageSeenDuringCapture() {
        val gate = AutoCaptureGate()
        assertFalse(gate.observe(frame(), 0))
        assertTrue(gate.captureStarted())
        gate.pause()
        assertFalse(gate.captureStarted()) // in-flight manual shutter is still protected
        assertFalse(gate.observe(frame(0.8), 300)) // page may change while JPEG is saving
        gate.pause()
        assertFalse(gate.captureStarted())
        gate.captureFinished(true)
        for (time in 600L..2700L step 300) assertFalse(gate.observe(frame(), time))
        assertTrue(gate.captureStarted()) // shutter becomes available again after completion
    }

    @Test fun autoReservationFreezesItsSceneBeforeLaterAnalysisUpdates() {
        val gate = AutoCaptureGate()
        assertTrue(stable(gate, 0))
        assertFalse(gate.observe(frame(0.8), 1200))
        assertTrue(gate.captureStarted()) // consumes the reserved 0.5 scene, not latest 0.8 analysis
        assertFalse(gate.observe(frame(0.8), 1500))
        gate.captureFinished(true)
        for (time in 1800L..3600L step 300) assertFalse(gate.observe(frame(), time))
    }

    @Test fun manualCaptureWhileAutoPausedUsesFreshCameraSignatureAndFreezesItUntilSaved() {
        val gate = AutoCaptureGate()
        assertFalse(gate.observe(frame(), 0))
        gate.pause()
        assertTrue(gate.captureStarted(frame(0.8).sceneSignature))
        assertFalse(gate.observe(frame(0.3), 300))
        gate.captureFinished(true)
        for (time in 600L..2700L step 300) assertFalse(gate.observe(frame(0.8), time))
    }

    @Test fun failedManualCaptureKeepsTheEarlierSuccessfulPageLock() {
        val gate = AutoCaptureGate()
        assertTrue(stable(gate, 0))
        gate.captureStarted(); gate.captureFinished(true)
        assertTrue(gate.captureStarted(frame(0.8).sceneSignature))
        gate.pause()
        gate.captureFinished(false)
        for (time in 1200L..3000L step 300) assertFalse(gate.observe(frame(), time))
    }

    @Test fun throttleHasBoundedRateAndResetsAfterPause() {
        val throttle = AnalysisThrottle(180)
        assertTrue(throttle.accept(0))
        assertFalse(throttle.accept(179))
        assertTrue(throttle.accept(180))
        assertFalse(throttle.accept(200))
        throttle.reset()
        assertTrue(throttle.accept(200))
        assertTrue(throttle.accept(0))
    }

    @Test fun handheldJitterAndOneBriefContourMissDoNotRestartTheWholeWindow() {
        val gate = AutoCaptureGate()
        for (index in 0..5) {
            val raw = if (index == 2) frame().copy(corners = null, confidence = 0.0)
                else frame(dx = if (index % 2 == 0) -0.006 else 0.006)
            assertFalse(gate.observe(raw, index * 180L))
        }
        // At 900ms only 540ms was directly observed valid; 1080ms supplies 720ms.
        assertTrue(gate.observe(frame(dx = -0.006), 1080))
    }

    @Test fun missingCurrentCornersNeverTriggerEvenWhenTheWindowIsOtherwiseReady() {
        val gate = AutoCaptureGate()
        for (time in 0L..720L step 180) assertFalse(gate.observe(frame(), time))
        assertFalse(gate.observe(frame().copy(corners = null, confidence = 0.0), 900))
        assertTrue(gate.observe(frame(), 1080))
    }

    @Test fun interruptionDeadlineIsInclusiveAndExpiredMissRequiresFreshStability() {
        for (duration in listOf(250L, 251L)) {
            val gate = AutoCaptureGate()
            for (time in 0L..540L step 180) assertFalse(gate.observe(frame(), time))
            assertFalse(gate.observe(frame().copy(corners = null), 720))
            val returned = 720 + duration
            assertFalse(gate.observe(frame(), returned))
            if (duration == 250L) assertTrue(gate.observe(frame(), returned + 180))
            else {
                for (time in returned + 180..returned + 720 step 180) assertFalse(gate.observe(frame(), time))
                assertTrue(gate.observe(frame(), returned + 900))
            }
        }
    }

    @Test fun consecutiveMissingFramesAndSustainedMissingClearAccumulatedEvidence() {
        val gate = AutoCaptureGate()
        assertFalse(gate.observe(frame(), 0))
        assertFalse(gate.observe(frame(), 180))
        for (time in 360L..1620L step 180) assertFalse(gate.observe(frame().copy(corners = null), time))
        assertTrue(stable(gate, 1800))
    }

    @Test fun frequentShortMissesCannotQualifyWithTooLittleValidEvidence() {
        val gate = AutoCaptureGate()
        for (index in 0..30) {
            val raw = if (index % 3 == 1) frame().copy(corners = null, confidence = 0.0) else frame()
            assertFalse("One missed observation in every three is insufficient evidence", gate.observe(raw, index * 180L))
        }
    }

    @Test fun shortMissWithBlurExposureOrUnknownSceneImmediatelyResets() {
        val missing = frame().copy(corners = null, confidence = 0.0)
        val failures = listOf(missing.copy(sharpness = 10.0), missing.copy(sharpness = Double.NaN),
            missing.copy(brightness = 0.1), missing.copy(brightness = 0.99),
            missing.copy(sceneSignature = emptyList()), missing.copy(sceneSignature = List(64) { Double.NaN }),
            missing.copy(sceneSignature = List(64) { 1.5 }), missing.copy(confidence = Double.NaN))
        for (bad in failures) {
            val gate = AutoCaptureGate()
            for (time in 0L..540L step 180) assertFalse(gate.observe(frame(), time))
            assertFalse(gate.observe(bad, 720))
            assertTrue(stable(gate, 900))
        }
    }

    @Test fun falseEdgesLargeMotionAndSceneChangesCannotUseTheInterruptionGrace() {
        val corners = frame().corners!!
        val rejected = listOf(frame().copy(confidence = 0.4),
            frame().copy(corners = Quad(corners.topLeft, corners.bottomRight, corners.topRight, corners.bottomLeft)),
            frame(dx = 0.08), frame(tone = 0.8))
        for (bad in rejected) {
            val gate = AutoCaptureGate()
            for (time in 0L..360L step 180) assertFalse(gate.observe(frame(), time))
            assertFalse(gate.observe(frame().copy(corners = null), 540))
            assertFalse(gate.observe(bad, 720))
            assertTrue(stable(gate, 900))
        }
    }

    @Test fun changedSceneDuringMissingDetectionCannotInheritEarlierPageEvidence() {
        val gate = AutoCaptureGate()
        for (time in 0L..540L step 180) assertFalse(gate.observe(frame(), time))
        assertFalse(gate.observe(frame(0.8).copy(corners = null), 720))
        assertTrue(stable(gate, 900, frame(0.8)))
    }

    @Test fun sustainedRawMotionWithOccasionalMissesNeverQualifies() {
        val gate = AutoCaptureGate()
        for (index in 0..30) {
            val dx = (index % 10) * 0.01
            val raw = if (index % 7 == 3) frame(dx = dx).copy(corners = null) else frame(dx = dx)
            assertFalse(gate.observe(raw, index * 180L))
        }
    }

    @Test fun sparseValidFramesRequireAtLeastFourIndependentObservations() {
        val gate = AutoCaptureGate()
        assertFalse(gate.observe(frame(), 0))
        assertFalse(gate.observe(frame(), 600))
        assertFalse(gate.observe(frame(), 1200))
        assertTrue(gate.observe(frame(), 1800))
    }

    @Test fun interruptedCaptureFreezesItsRawSceneAndRetainsDuplicateLockAfterFailure() {
        val gate = AutoCaptureGate()
        for (time in 0L..720L step 180) assertFalse(gate.observe(frame(), time))
        assertFalse(gate.observe(frame().copy(corners = null), 900))
        assertTrue(gate.observe(frame(), 1080))
        assertTrue(gate.captureStarted())
        assertFalse(gate.observe(frame(0.8), 1260))
        gate.captureFinished(true)
        assertTrue(gate.captureStarted(frame(0.8).sceneSignature))
        gate.pause()
        gate.captureFinished(false)
        for (time in 1440L..3240L step 180) assertFalse(gate.observe(frame(), time))
    }

    @Test fun pauseDuringShortInterruptionRequiresAnEntireNewWindow() {
        val gate = AutoCaptureGate()
        for (time in 0L..540L step 180) assertFalse(gate.observe(frame(), time))
        assertFalse(gate.observe(frame().copy(corners = null), 720))
        gate.pause()
        assertTrue(stable(gate, 900))
    }
}
