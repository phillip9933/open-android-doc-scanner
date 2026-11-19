package dev.offlinescan.ocr

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class OcrDecodeSizingTest {
    @Test fun sampleAccountsForBothOddEdges() {
        assertEquals(1, ocrSampleSize(100, 100, 10_000))
        assertEquals(2, ocrSampleSize(101, 99, 2_550))
        assertEquals(4, ocrSampleSize(101, 99, 2_549))
    }

    @Test fun wordBoundsCoverTheSameAreaInSourceCoordinates() {
        assertContentEquals(intArrayOf(1, 1, 100, 98),
            scaleOcrBounds(intArrayOf(1, 1, 50, 49), 51, 50, 101, 99))
    }
}
