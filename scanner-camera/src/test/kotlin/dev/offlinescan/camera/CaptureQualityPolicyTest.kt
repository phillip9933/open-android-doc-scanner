package dev.offlinescan.camera

import org.junit.Assert.*
import org.junit.Test

class CaptureQualityPolicyTest {
    private fun sample(sharpness: Double = 140.0, brightness: Double = 0.55,
        tiles: Int = 6, sameDocument: Boolean = true) =
        CaptureQualityPolicy.Sample(sharpness, brightness, tiles, sameDocument)

    @Test fun detailedStillMustMeetAbsoluteAndRelativeSharpness() {
        assertFalse(CaptureQualityPolicy.acceptable(sample(59.0), 60.0, 6))
        assertFalse(CaptureQualityPolicy.acceptable(sample(80.0), 140.0, 6))
        assertTrue(CaptureQualityPolicy.acceptable(sample(100.0), 140.0, 6))
        assertTrue(CaptureQualityPolicy.acceptable(sample(180.0), 140.0, 6))
    }

    @Test fun vanishedTextCannotBecomeABlankPaperException() {
        for (tiles in 0..1) {
            assertFalse("Lost text must fail even if residual edges score well",
                CaptureQualityPolicy.acceptable(sample(200.0, tiles = tiles), 140.0, 6))
            assertFalse(CaptureQualityPolicy.acceptable(sample(0.0, tiles = tiles), 140.0, 6))
            assertFalse("Unknown preview texture cannot authorize blank-paper bypass",
                CaptureQualityPolicy.acceptable(sample(0.0, tiles = tiles), 140.0, null))
        }
    }

    @Test fun genuinelyBlankReferenceDoesNotRequirePrintedDetail() {
        for (referenceTiles in 0..1) for (stillTiles in 0..1) {
            assertTrue(CaptureQualityPolicy.acceptable(sample(0.0, tiles = stillTiles), 0.0, referenceTiles))
        }
        assertFalse(CaptureQualityPolicy.acceptable(sample(0.0, tiles = 0, sameDocument = false), 0.0, 0))
    }

    @Test fun invalidOrUnusableMeasurementsNeverPublishAStill() {
        val invalid = listOf(sample(Double.NaN), sample(Double.POSITIVE_INFINITY),
            sample(Double.NEGATIVE_INFINITY), sample(-1.0, tiles = 0),
            sample(brightness = Double.NaN), sample(brightness = Double.POSITIVE_INFINITY),
            sample(brightness = 0.17), sample(brightness = 0.99),
            sample(tiles = -1), sample(tiles = 10), sample(sameDocument = false))
        for (candidate in invalid) {
            assertFalse("Invalid still $candidate must fail", CaptureQualityPolicy.acceptable(candidate, 0.0, 0))
        }
    }

    @Test fun sharperDifferentDocumentCannotReplaceAnAcceptedFirstStill() {
        val candidates = listOf(sample(110.0), sample(240.0, sameDocument = false), sample(150.0))
        var best: CaptureQualityPolicy.Sample? = null
        for (candidate in candidates) {
            if (CaptureQualityPolicy.acceptable(candidate, 140.0, 6) && CaptureQualityPolicy.better(candidate, best)) {
                best = candidate
            }
        }
        assertEquals(candidates[2], best)
    }

    @Test fun twoStillSelectionPreservesSharperFirstAndRejectsBlurrySecond() {
        val first = sample(180.0)
        val slightlySofter = sample(150.0)
        val blurred = sample(30.0)
        assertTrue(CaptureQualityPolicy.acceptable(first, 140.0, 6))
        assertTrue(CaptureQualityPolicy.better(first, null))
        assertFalse(CaptureQualityPolicy.better(slightlySofter, first))
        assertFalse(CaptureQualityPolicy.acceptable(blurred, 140.0, 6))
        assertFalse(CaptureQualityPolicy.better(first, first))
        assertTrue(CaptureQualityPolicy.better(sample(200.0), first))
    }
}
