package dev.offlinescan.core

import kotlin.test.*

class DocumentOutlineFallbackTest {
    private val miss = Detection(null, 0.0, 0.5, 0.0, List(64) { 0.5 })
    private val edge = miss.copy(corners = Quad.FULL, confidence = 0.8)
    private val policy = DocumentOutlineFallback()

    @Test fun checksFirstMissAndReturnsImmediatelyToAi() {
        assertSame(edge, policy.onFrame(miss, 0) { edge })
        assertSame(edge, policy.onFrame(miss, 180) { edge })
        val nextEdge = edge.copy(confidence = 0.85)
        assertSame(nextEdge, policy.onFrame(miss, 360) { nextEdge })
        val ai = edge.copy(confidence = 0.72)
        assertSame(ai, policy.onFrame(ai, 540) { error("AI found the page") })
        assertSame(edge, policy.onFrame(miss, 720) { edge })
    }

    @Test fun gapsAndNewScenesProbeImmediatelyAndEmptyScenesAreRateLimited() {
        policy.onFrame(miss, 1) { miss }
        policy.onFrame(miss, 702) { miss }
        val changed = miss.copy(sceneSignature = List(64) { 0.1 })
        policy.onFrame(changed, 881) { miss }
        assertSame(changed, policy.onFrame(changed, 1061) { error("repeat after failed probe") })
        assertSame(changed, policy.onFrame(changed, 1241) { edge.copy(confidence = 0.59) })
        policy.onFrame(changed, 1421) { error("skip after unsuccessful probe") }
        assertSame(edge, policy.onFrame(changed, 1601) { edge })
    }

    @Test fun stillRefinementRequiresBothOutlinesToIdentifyTheSamePage() {
        val near = edge.copy(corners = Quad(Point(.01,.01), Point(.99,.01), Point(.99,.99), Point(.01,.99)))
        val panel = edge.copy(corners = Quad(Point(0.0,0.0), Point(.28,0.0), Point(.28,1.0), Point(0.0,1.0)))
        assertSame(near, DocumentOutlineFallback.refineStill(edge) { near })
        val p = near.corners!!.points
        val rotated = near.copy(corners = Quad(p[1], p[2], p[3], p[0]))
        assertEquals(near, DocumentOutlineFallback.refineStill(edge) { rotated })
        assertSame(edge, DocumentOutlineFallback.refineStill(edge) { panel })
        assertSame(edge, DocumentOutlineFallback.refineStill(edge) { miss })
        assertSame(near, DocumentOutlineFallback.refineStill(miss) { near })
        assertSame(miss, DocumentOutlineFallback.refineStill(miss) { miss })
        assertFailsWith<java.util.concurrent.CancellationException> {
            DocumentOutlineFallback.refineStill(edge) { throw java.util.concurrent.CancellationException() }
        }
    }

    @Test fun stillsOnlyTryEdgesWhenNeededAndPropagateCancellation() {
        assertSame(edge, DocumentOutlineFallback.onStill(edge) { error("AI found the page") })
        assertSame(edge, DocumentOutlineFallback.onStill(miss) { edge })
        assertSame(miss, DocumentOutlineFallback.onStill(miss) { edge.copy(confidence = 0.59) })
        assertFailsWith<java.util.concurrent.CancellationException> {
            DocumentOutlineFallback.onStill(miss) { throw java.util.concurrent.CancellationException() }
        }
    }
}
