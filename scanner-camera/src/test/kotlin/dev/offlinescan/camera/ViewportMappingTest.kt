package dev.offlinescan.camera

import dev.offlinescan.core.Point
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportMappingTest {
    @Test fun wideImageInPortraitViewHasVerticalLetterboxing() {
        assertEquals(Point(0.0, 150.0), ViewportMapping.toPreview(Point(0.0, 0.0), 400, 200, 200, 400))
        assertEquals(Point(200.0, 250.0), ViewportMapping.toPreview(Point(1.0, 1.0), 400, 200, 200, 400))
    }
    @Test fun portraitImageInWideViewHasHorizontalLetterboxing() {
        assertEquals(Point(150.0, 0.0), ViewportMapping.toPreview(Point(0.0, 0.0), 200, 400, 400, 200))
        assertEquals(Point(250.0, 200.0), ViewportMapping.toPreview(Point(1.0, 1.0), 200, 400, 400, 200))
    }
    @Test fun mappingRoundTripsAfterViewportCropAndUprightRotation() {
        val point = Point(0.27, 0.71)
        val preview = ViewportMapping.toPreview(point, 720, 960, 1080, 2400)
        val restored = ViewportMapping.fromPreview(preview, 720, 960, 1080, 2400)
        assertEquals(point.x, restored.x, 1e-12)
        assertEquals(point.y, restored.y, 1e-12)
    }
    @Test(expected = IllegalArgumentException::class)
    fun zeroSizedPreviewCannotBeMapped() {
        ViewportMapping.toPreview(Point(0.0, 0.0), 720, 960, 0, 0)
    }

    @Test fun fillWideImageInPortraitViewCropsHorizontalEdgesAroundCenter() {
        assertEquals(Point(-300.0, 0.0), ViewportMapping.toPreview(Point(0.0, 0.0), 400, 200, 200, 400, true))
        assertEquals(Point(500.0, 400.0), ViewportMapping.toPreview(Point(1.0, 1.0), 400, 200, 200, 400, true))
        assertEquals(Point(100.0, 200.0), ViewportMapping.toPreview(Point(0.5, 0.5), 400, 200, 200, 400, true))
    }

    @Test fun fillPortraitImageInWideViewCropsVerticalEdgesAroundCenter() {
        assertEquals(Point(0.0, -300.0), ViewportMapping.toPreview(Point(0.0, 0.0), 200, 400, 400, 200, true))
        assertEquals(Point(400.0, 500.0), ViewportMapping.toPreview(Point(1.0, 1.0), 200, 400, 400, 200, true))
    }

    @Test fun fullscreenFillMappingRoundTripsIncludingOutsideVisibleCrop() {
        for (point in listOf(Point(0.27, 0.71), Point(0.0, 0.0), Point(1.0, 1.0))) {
            val preview = ViewportMapping.toPreview(point, 720, 960, 1080, 2400, true)
            val restored = ViewportMapping.fromPreview(preview, 720, 960, 1080, 2400, true)
            assertEquals(point.x, restored.x, 1e-12)
            assertEquals(point.y, restored.y, 1e-12)
        }
        val topLeft = ViewportMapping.fromPreview(Point(0.0, 0.0), 400, 200, 200, 400, true)
        assertEquals(Point(0.375, 0.0), topLeft)
    }

    @Test fun matchingViewportAspectUsesTheSameCoordinatesForFitAndFill() {
        val point = Point(0.27, 0.71)
        assertEquals(ViewportMapping.toPreview(point, 720, 960, 1080, 1440),
            ViewportMapping.toPreview(point, 720, 960, 1080, 1440, true))
    }
}
