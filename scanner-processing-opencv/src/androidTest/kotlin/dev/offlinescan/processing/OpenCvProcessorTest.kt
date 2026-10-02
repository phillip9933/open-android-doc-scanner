package dev.offlinescan.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.offlinescan.core.*
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenCvProcessorTest {
    private val processor = OpenCvProcessor()
    private lateinit var directory: File
    @Before fun setup() { directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "processor-${System.nanoTime()}").apply { mkdirs() } }
    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun uniformImageIsNotInventedAsDocument() {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(120, 120, 120))
            val detection = processor.detectBitmap(bitmap)
            assertNull(detection.corners)
            assertEquals(0.0, detection.confidence, 0.0)
            assertEquals(120.0 / 255, detection.brightness, 0.01)
            assertEquals(0.0, detection.sharpness, 0.01)
            assertEquals(64, detection.sceneSignature.size)
            assertFalse(bitmap.isRecycled)
        } finally { bitmap.recycle() }
    }

    @Test fun perspectiveAndRotatedDocumentsProduceMeasuredCorners() {
        val fixtures = listOf(
            Quad(Point(.16,.14), Point(.85,.20), Point(.79,.87), Point(.20,.79)),
            Quad(Point(.31,.12), Point(.88,.38), Point(.67,.91), Point(.11,.66))
        )
        for (quad in fixtures) {
            val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.rgb(35, 40, 45))
                val path = Path().apply {
                    moveTo((quad.topLeft.x * 799).toFloat(), (quad.topLeft.y * 599).toFloat())
                    quad.points.drop(1).forEach { lineTo((it.x * 799).toFloat(), (it.y * 599).toFloat()) }
                    close()
                }
                Canvas(bitmap).drawPath(path, Paint().apply { color = Color.rgb(238, 236, 232) })
                val detection = processor.detectBitmap(bitmap)
                assertNotNull(detection.corners)
                assertTrue(Geometry.valid(detection.corners!!))
                assertTrue("Detected quad must match the fixture, not the whole frame", Geometry.distance(quad, detection.corners!!) < .035)
                assertTrue(detection.confidence > .65)
                assertEquals("Blank paper edges are not focus evidence", 0.0, detection.sharpness, .01)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun exifAllEightOrientationsPreserveCornerColorsAndDimensions() {
        // Upright order TL, TR, BR, BL. Expected transformed corner source indices for EXIF 1..8.
        val expected = listOf(listOf(0,1,2,3), listOf(1,0,3,2), listOf(2,3,0,1), listOf(3,2,1,0),
            listOf(0,3,2,1), listOf(3,0,1,2), listOf(2,1,0,3), listOf(1,2,3,0))
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        for (orientation in 1..8) {
            val input = cornerFixture("orientation-$orientation.jpg", Bitmap.CompressFormat.JPEG)
            ExifInterface(input).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
            val output = File(directory, "upright-$orientation.png")
            val info = processor.inspect(input, ScanConfig())
            assertEquals(if (orientation >= 5) 60 to 80 else 80 to 60, info)
            val page = processor.render(input, Edits(), output, ExportFormat.PNG, ScanConfig())
            assertEquals(info, page.width to page.height)
            val image = BitmapFactory.decodeFile(output.absolutePath)!!
            try {
                val locations = listOf(.2 to .2, .8 to .2, .8 to .8, .2 to .8)
                for (i in locations.indices) {
                    val (x, y) = locations[i]
                    assertColorNear(colors[expected[orientation - 1][i]], image.getPixel((x * image.width).toInt(), (y * image.height).toInt()), 25)
                }
            } finally { image.recycle() }
        }
    }

    @Test fun photographAndOriginalPreserveColorWhileGrayscaleAndBwAreExplicit() {
        val input = cornerFixture("photo.png", Bitmap.CompressFormat.PNG)
        for (preset in listOf(Preset.ORIGINAL, Preset.PHOTO, Preset.GRAYSCALE, Preset.BLACK_WHITE)) {
            val output = File(directory, "$preset.png")
            processor.render(input, Edits(preset = preset), output, ExportFormat.PNG, ScanConfig(mode = ScanMode.PHOTO))
            val image = BitmapFactory.decodeFile(output.absolutePath)!!
            try {
                val pixel = image.getPixel(10, 10)
                if (preset == Preset.ORIGINAL || preset == Preset.PHOTO) assertColorNear(Color.RED, pixel, 2)
                else {
                    assertEquals(Color.red(pixel), Color.green(pixel)); assertEquals(Color.green(pixel), Color.blue(pixel))
                    if (preset == Preset.BLACK_WHITE) assertTrue(Color.red(pixel) == 0 || Color.red(pixel) == 255)
                }
            } finally { image.recycle() }
        }
    }

    @Test fun outputIsBoundedAndRotationMetadataMatchesEncodedImage() {
        val bitmap = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)
        val input = File(directory, "wide.png")
        try { input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
        val output = File(directory, "bounded.png")
        val page = processor.render(input, Edits(rotationQuarterTurns = 1), output, ExportFormat.PNG, ScanConfig(maxOutputDimension = 512))
        assertTrue(page.width <= 512 && page.height <= 512)
        assertEquals(page.width * 2, page.height)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.absolutePath, bounds)
        assertEquals(page.width, bounds.outWidth); assertEquals(page.height, bounds.outHeight)
    }

    @Test fun perspectiveWarpExcludesBackgroundAndPreservesPaperColor() {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val quad = Quad(Point(.16,.14), Point(.85,.20), Point(.79,.87), Point(.20,.79))
        val paperColor = Color.rgb(60, 175, 205)
        bitmap.eraseColor(Color.rgb(10, 20, 30))
        val path = Path().apply {
            moveTo((quad.topLeft.x * 799).toFloat(), (quad.topLeft.y * 599).toFloat())
            quad.points.drop(1).forEach { lineTo((it.x * 799).toFloat(), (it.y * 599).toFloat()) }
            close()
        }
        Canvas(bitmap).drawPath(path, Paint().apply { color = paperColor })
        val input = File(directory, "perspective.png")
        try { input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
        val output = File(directory, "rectified.png")
        processor.render(input, Edits(corners = quad, preset = Preset.PHOTO), output, ExportFormat.PNG, ScanConfig())
        val image = BitmapFactory.decodeFile(output.absolutePath)!!
        try {
            assertTrue(image.width in 540..570); assertTrue(image.height in 400..430)
            for ((x, y) in listOf(.05 to .05, .95 to .05, .95 to .95, .05 to .95, .5 to .5)) {
                assertColorNear(paperColor, image.getPixel((x * image.width).toInt(), (y * image.height).toInt()), 2)
            }
        } finally { image.recycle() }
    }

    @Test fun brightnessContrastAndIlluminationKeepUniformColorWellDefined() {
        val input = cornerFixture("tone.png", Bitmap.CompressFormat.PNG)
        val output = File(directory, "tone-output.png")
        processor.render(input, Edits(brightness = .1, contrast = .75), output, ExportFormat.PNG, ScanConfig())
        val image = BitmapFactory.decodeFile(output.absolutePath)!!
        try { assertColorNear(Color.rgb(249, 58, 58), image.getPixel(10, 10), 2) } finally { image.recycle() }
        val uniform = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        uniform.eraseColor(Color.rgb(50, 120, 180))
        val uniformInput = File(directory, "uniform.png")
        try { uniformInput.outputStream().use { uniform.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { uniform.recycle() }
        processor.render(uniformInput, Edits(illumination = .5), output, ExportFormat.PNG, ScanConfig())
        val corrected = BitmapFactory.decodeFile(output.absolutePath)!!
        try { assertColorNear(Color.rgb(50, 120, 180), corrected.getPixel(160, 120), 1) } finally { corrected.recycle() }
    }

    @Test fun invalidGeometryAndInputLimitsFailBeforeWritingAndCancellationPropagates() {
        val input = cornerFixture("input.png", Bitmap.CompressFormat.PNG)
        val output = File(directory, "never.png")
        try {
            processor.render(input, Edits(corners = Quad(Point(0.0,0.0),Point(1.0,1.0),Point(1.0,0.0),Point(0.0,1.0))), output, ExportFormat.PNG, ScanConfig())
            fail("Expected invalid geometry")
        } catch (e: ScanException) { assertEquals(ErrorCode.INVALID_GEOMETRY, e.error.code) }
        assertFalse(output.exists())
        try { processor.inspect(input, ScanConfig(maxInputPixels = 100)); fail("Expected resource limit") }
        catch (e: ScanException) { assertEquals(ErrorCode.RESOURCE_LIMIT, e.error.code) }
        val signal = CancellationException("fixture cancellation")
        try { processor.detect(input, ScanConfig(), Cancellation { throw signal }); fail("Expected cancellation") }
        catch (e: CancellationException) { assertSame(signal, e) }
        // Repeated cancellation after decoding exercises finally cleanup and does not poison later calls.
        repeat(8) {
            var calls = 0
            try { processor.render(input, Edits(illumination = .25), output, ExportFormat.PNG, ScanConfig(), Cancellation { if (++calls == 4) throw signal }); fail("Expected cancellation") }
            catch (e: CancellationException) { assertSame(signal, e) }
        }
        assertEquals(80 to 60, processor.inspect(input, ScanConfig()))
    }

    private fun cornerFixture(name: String, format: Bitmap.CompressFormat): File {
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); val paint = Paint()
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val rectangles = listOf(floatArrayOf(0f,0f,40f,30f), floatArrayOf(40f,0f,80f,30f), floatArrayOf(40f,30f,80f,60f), floatArrayOf(0f,30f,40f,60f))
        for (i in colors.indices) { paint.color = colors[i]; val r = rectangles[i]; canvas.drawRect(r[0], r[1], r[2], r[3], paint) }
        val file = File(directory, name)
        try { file.outputStream().use { assertTrue(bitmap.compress(format, 100, it)) } } finally { bitmap.recycle() }
        return file
    }
    private fun assertColorNear(expected: Int, actual: Int, tolerance: Int) {
        assertTrue("Red differs", kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= tolerance)
        assertTrue("Green differs", kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= tolerance)
        assertTrue("Blue differs", kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance)
    }
}
