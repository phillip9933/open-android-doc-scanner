package dev.offlinescan.core

import kotlin.test.*

class DocumentOutlineFallbackTest {
    private val miss = Detection(null, 0.0, 0.5, 0.0, List(64) { 0.5 })
    private val edge = miss.copy(corners = Quad.FULL, confidence = 0.8)
    private val policy = DocumentOutlineFallback()

    @Test fun checksCurrentFrameAfterTwoMissesAndReturnsImmediatelyToAi() {
        assertSame(miss, policy.onFrame(miss, 0) { error("first miss") })
        assertSame(edge, policy.onFrame(miss, 180) { edge })
        val nextEdge = edge.copy(confidence = 0.85)
        assertSame(nextEdge, policy.onFrame(miss, 360) { nextEdge })
        val ai = edge.copy(confidence = 0.72)
        assertSame(ai, policy.onFrame(ai, 540) { error("AI found the page") })
        assertSame(miss, policy.onFrame(miss, 720) { error("new miss streak") })
    }

    @Test fun gapsAndNewScenesRestartTheMissCountAndEmptyScenesAreRateLimited() {
        policy.onFrame(miss, 1) { error("first miss") }
        policy.onFrame(miss, 701) { error("gap") }
        val changed = miss.copy(sceneSignature = List(64) { 0.1 })
        policy.onFrame(changed, 881) { error("scene changed") }
        assertSame(changed, policy.onFrame(changed, 1061) { edge.copy(confidence = 0.59) })
        policy.onFrame(changed, 1241) { error("skip after unsuccessful probe") }
        assertSame(edge, policy.onFrame(changed, 1421) { edge })
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
