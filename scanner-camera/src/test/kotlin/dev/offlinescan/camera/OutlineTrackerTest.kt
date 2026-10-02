package dev.offlinescan.camera

import dev.offlinescan.core.Detection
import dev.offlinescan.core.Geometry
import dev.offlinescan.core.Point
import dev.offlinescan.core.Quad
import org.junit.Assert.*
import org.junit.Test

class OutlineTrackerTest {
    private fun frame(dx: Double = 0.0, tone: Double = 0.5) = Detection(
        Quad(Point(0.1 + dx, 0.1), Point(0.8 + dx, 0.1), Point(0.8 + dx, 0.9), Point(0.1 + dx, 0.9)),
        0.9, 0.55, 140.0, List(64) { tone })

    @Test fun reducesAlternatingJitterWithoutChangingRawMetricsOrInput() {
        val tracker = OutlineTracker()
        var rawError = 0.0
        var displayError = 0.0
        for (index in 0..20) {
            val raw = frame(if (index % 2 == 0) -0.006 else 0.006)
            val display = tracker.update(raw, index * 180L)
            assertEquals(raw.copy(corners = display.corners), display)
            assertEquals(if (index % 2 == 0) 0.094 else 0.106, raw.corners!!.topLeft.x, 1e-12)
            if (index > 0) {
                rawError += Geometry.distance(raw.corners!!, frame().corners!!)
                displayError += Geometry.distance(display.corners!!, frame().corners!!)
            }
        }
        assertTrue("Display jitter should decrease materially", displayError < rawError * 0.65)
    }

    @Test fun followsSmallMotionWithinOneFrameAndConvergesQuickly() {
        val tracker = OutlineTracker()
        assertEquals(frame(), tracker.update(frame(), 0))
        val moved = frame(0.01)
        val first = tracker.update(moved, 180)
        assertEquals(0.1065, first.corners!!.topLeft.x, 1e-12)
        tracker.update(moved, 360)
        val third = tracker.update(moved, 540)
        assertTrue(Geometry.distance(third.corners!!, moved.corners!!) < 0.0005)
    }

    @Test fun largerWholeDocumentMovementResetsImmediately() {
        val tracker = OutlineTracker()
        tracker.update(frame(), 0)
        tracker.update(frame(0.006), 180)
        val jumped = frame(0.10)
        assertEquals(jumped, tracker.update(jumped, 360))
    }

    @Test fun oneCornerJumpCannotBeHiddenByAverageCornerDistance() {
        val tracker = OutlineTracker()
        tracker.update(frame(), 0)
        val raw = frame()
        val jumped = raw.copy(corners = raw.corners!!.copy(topLeft = Point(0.16, 0.1)))
        assertTrue(Geometry.valid(jumped.corners!!))
        assertEquals(jumped, tracker.update(jumped, 180))
    }

    @Test fun aNewSceneWithSimilarGeometryResetsImmediately() {
        val tracker = OutlineTracker()
        tracker.update(frame(tone = 0.5), 0)
        tracker.update(frame(0.006, 0.5), 180)
        val changed = frame(-0.006, 0.8)
        assertEquals(changed, tracker.update(changed, 360))
    }

    @Test fun missingDetectionClearsOutlineAndNextDetectionStartsUnsmoothed() {
        val tracker = OutlineTracker()
        tracker.update(frame(), 0)
        tracker.update(frame(0.006), 180)
        val missing = frame().copy(corners = null)
        assertNull(tracker.update(missing, 360).corners)
        val returned = frame(-0.006)
        assertEquals(returned, tracker.update(returned, 540))
    }

    @Test fun invalidGeometryAndLowConfidenceCannotKeepAGhostOutline() {
        val valid = frame()
        val corners = valid.corners!!
        val rejected = listOf(valid.copy(corners = corners.copy(topLeft = Point(Double.NaN, 0.1))),
            valid.copy(corners = Quad(corners.topLeft, corners.bottomRight, corners.topRight, corners.bottomLeft)),
            valid.copy(confidence = 0.59), valid.copy(confidence = Double.NaN))
        for (bad in rejected) {
            val tracker = OutlineTracker()
            tracker.update(valid, 0)
            tracker.update(frame(0.006), 180)
            assertNull(tracker.update(bad, 360).corners)
            assertEquals(frame(-0.006), tracker.update(frame(-0.006), 540))
        }
    }

    @Test fun timeGapsClockReversalViewportChangeAndExplicitResetDiscardHistory() {
        val tracker = OutlineTracker()
        tracker.update(frame(), 0, 1)
        tracker.update(frame(0.006), 180, 1)
        assertEquals(frame(-0.006), tracker.update(frame(-0.006), 1000, 1))
        assertEquals(frame(0.006), tracker.update(frame(0.006), 100, 1))
        assertEquals(frame(-0.006), tracker.update(frame(-0.006), 280, 2))
        tracker.update(frame(0.006), 460, 2)
        tracker.reset()
        assertEquals(frame(-0.006), tracker.update(frame(-0.006), 640, 2))
    }

    @Test fun invalidSceneSignatureBypassesSmoothingRatherThanReusingOldScene() {
        val tracker = OutlineTracker()
        tracker.update(frame(), 0)
        val unknown = frame(0.006).copy(sceneSignature = emptyList())
        assertEquals(unknown, tracker.update(unknown, 180))
        val returned = frame(-0.006)
        assertEquals(returned, tracker.update(returned, 360))
    }

    @Test fun displaySmoothingMustNotFabricateAutoCaptureStability() {
        val tracker = OutlineTracker()
        val rawGate = AutoCaptureGate()
        val incorrectlySmoothedGate = AutoCaptureGate()
        var artificialCapture = false
        for (index in 0..20) {
            val raw = frame(if (index % 2 == 0) -0.016 else 0.016)
            val display = tracker.update(raw, index * 180L)
            assertFalse("Raw corner motion must keep resetting capture stability", rawGate.observe(raw, index * 180L))
            artificialCapture = incorrectlySmoothedGate.observe(display, index * 180L) || artificialCapture
        }
        assertTrue("This sequence would fabricate stability if the display output reached the gate", artificialCapture)
    }

    @Test fun displayClearsMissingOutlineWhileRawGateCanRetainShortInterruptionEvidence() {
        val tracker = OutlineTracker()
        val gate = AutoCaptureGate()
        for (index in 0..5) {
            val raw = if (index == 2) frame().copy(corners = null, confidence = 0.0)
                else frame(if (index % 2 == 0) -0.006 else 0.006)
            val display = tracker.update(raw, index * 180L)
            if (index == 2) assertNull(display.corners)
            if (index == 3) assertEquals(raw, display)
            assertFalse(gate.observe(raw, index * 180L))
        }
        assertTrue(gate.observe(frame(-0.006), 1080))
    }
}
