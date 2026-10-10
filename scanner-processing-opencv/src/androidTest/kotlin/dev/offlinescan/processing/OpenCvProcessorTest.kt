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
import kotlin.math.sqrt
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

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

    @Test fun pngExifOrientationIsAppliedAfterPowerOfTwoPreviewDecode() {
        val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val bounds = listOf(floatArrayOf(0f,0f,800f,600f), floatArrayOf(800f,0f,1600f,600f),
            floatArrayOf(800f,600f,1600f,1200f), floatArrayOf(0f,600f,800f,1200f))
        for (i in colors.indices) { val b=bounds[i]; canvas.drawRect(b[0],b[1],b[2],b[3],Paint().apply{color=colors[i]}) }
        val input=File(directory,"png-orientation-preview.png")
        try { input.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
        finally { bitmap.recycle() }
        ExifInterface(input).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
        val output=File(directory,"png-orientation-preview-render.png")
        val page=processor.render(input,Edits(),output,ExportFormat.PNG,ScanConfig(maxOutputDimension=512))
        assertTrue("PNG EXIF orientation survives bounded decode: $page",page.height>page.width)
        val rendered=BitmapFactory.decodeFile(output.absolutePath)!!
        try {
            val locations=listOf(.1 to .1,.9 to .1,.9 to .9,.1 to .9)
            val expected=listOf(Color.YELLOW,Color.RED,Color.GREEN,Color.BLUE)
            for(i in locations.indices) {
                val (x,y)=locations[i]
                assertColorNear(expected[i],rendered.getPixel((x*rendered.width).toInt(),(y*rendered.height).toInt()),4)
            }
        } finally {rendered.recycle()}
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

    @Test fun twelveMegapixelCaptureKeepsResolutionThroughPerspectiveCrop() {
        val bitmap = Bitmap.createBitmap(4032, 3024, Bitmap.Config.ARGB_8888)
        var source: File? = null
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK; textSize = 44f; isAntiAlias = true }
            for (y in 150..2850 step 105) canvas.drawText("Invoice 2026  18.75   0123456789", 170f, y.toFloat(), paint)
            val input = File(directory, "twelve-megapixel.jpg").also { source = it }
            input.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 98, it)) }
            ExifInterface(input).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
            bitmap.recycle()
            val output = File(directory, "twelve-megapixel-render.png")
            val page = processor.render(input, Edits(), output, ExportFormat.PNG, ScanConfig())
            assertEquals("Full-crop Original stays at the source's native 12 MP", 4032L * 3024L, page.width.toLong() * page.height)
            assertTrue(page.width <= 8192 && page.height <= 8192)
            assertTrue("Full export follows EXIF 6 into portrait orientation",page.height>page.width)
            val previewOutput = File(directory, "twelve-megapixel-preview.png")
            val preview = processor.render(input, Edits(), previewOutput, ExportFormat.PNG,
                ScanConfig(maxOutputDimension = 512))
            assertTrue("EXIF 6 preview remains upright after exact downscale: $preview", preview.height > preview.width)
            assertTrue(preview.width <= 512 && preview.height <= 512)
            val budgetOutput = File(directory, "twelve-megapixel-budgeted.png")
            val budgetConfig = ScanConfig(maxRenderPixels = 4_000_000, maxWorkingBytes = 64_000_000)
            val budgeted = processor.render(input, Edits(), budgetOutput, ExportFormat.PNG, budgetConfig)
            assertTrue("Runtime budget reduces larger input smoothly", budgeted.width.toLong() * budgeted.height <= 4_000_000)
            assertTrue("Budget reduction is reported", budgeted.warnings.isNotEmpty())
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(output.absolutePath, bounds)
            assertEquals(page.width, bounds.outWidth)
            assertEquals(page.height, bounds.outHeight)
            // Save same-source visual evidence. The baseline is a simulated former sample=2
            // decode; it is clearly labeled and is not a real-camera acceptance result.
            val external = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
            if (external != null) {
                input.copyTo(File(external, "scanner-quality-12mp-source.jpg"), overwrite = true)
                output.copyTo(File(external, "scanner-quality-12mp-full-resolution.png"), overwrite = true)
                previewOutput.copyTo(File(external, "scanner-quality-12mp-preview-512.png"), overwrite = true)
                budgetOutput.copyTo(File(external, "scanner-quality-12mp-budgeted-4mp.png"), overwrite = true)
                File(external, "scanner-quality-12mp-dimensions.json").writeText(
                    """{"fixture":"synthetic Android bitmap, not camera acceptance","exifOrientation":6,"capture":[4032,3024],"decodeUpright":[3024,4032],"perspectiveCropBeforeOutputLimit":[3024,4032],"defaultExport":[${page.width},${page.height}],"defaultMaxRenderPixels":24000000,"defaultMaxOutputDimension":8192,"budgetedExport":[${budgeted.width},${budgeted.height}],"budgetedMaxRenderPixels":4000000,"budgetedMaxWorkingBytes":64000000}"""
                )
                val decoded = BitmapFactory.decodeFile(input.absolutePath)!!
                val half = try { Bitmap.createScaledBitmap(decoded, 2016, 1512, true) } finally { decoded.recycle() }
                val legacyInput = File(directory, "legacy-sample2.jpg")
                try { legacyInput.outputStream().use { assertTrue(half.compress(Bitmap.CompressFormat.JPEG, 98, it)) } }
                finally { half.recycle() }
                ExifInterface(legacyInput).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
                val legacyOutput = File(directory, "legacy-sample2-render.png")
                val legacyPage = processor.render(legacyInput, Edits(), legacyOutput, ExportFormat.PNG, ScanConfig())
                assertTrue("Full detail should exceed the old simulated sample=2 result", page.width.toLong() * page.height > legacyPage.width.toLong() * legacyPage.height * 1.8)
                legacyInput.copyTo(File(external, "scanner-quality-12mp-baseline-simulated-sample2-input.png"), overwrite = true)
                legacyOutput.copyTo(File(external, "scanner-quality-12mp-baseline-simulated-sample2-render.png"), overwrite = true)
            }
        } finally { if (!bitmap.isRecycled) bitmap.recycle(); source?.delete() }
    }

    @Test fun blackWhiteRetainsFaintPrintAcrossBroadPaperShadow() {
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        val row = IntArray(1200)
        for (y in 0 until 800) {
            val paper = 205 + 35 * y / 799
            java.util.Arrays.fill(row, Color.rgb(paper, paper, paper))
            bitmap.setPixels(row, 0, 1200, 0, y, 1200, 1)
        }
        Canvas(bitmap).drawText("Ref: 18.75, item 7", 110f, 420f, Paint().apply {
            color = Color.rgb(168, 168, 168); textSize = 58f; isAntiAlias = true
        })
        val paleInk = Paint().apply { color = Color.rgb(221, 221, 221); textSize = 34f; isAntiAlias = true }
        Canvas(bitmap).drawText("i: 7, 3.1", 110f, 610f, paleInk)
        Canvas(bitmap).drawLine(110f, 650f, 790f, 650f, Paint().apply {
            color = Color.rgb(221, 221, 221); strokeWidth = 1f; isAntiAlias = false
        })
        val input = File(directory, "shadow-faint-print.jpg")
        try { input.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 98, it)) } } finally { bitmap.recycle() }
        val output = File(directory, "shadow-faint-print-bw.png")
        val sourceBitmap = BitmapFactory.decodeFile(input.absolutePath)!!
        val sauvolaBlack = try { sauvolaBlackPixels(sourceBitmap, 96, 790, 320, 440) } finally { sourceBitmap.recycle() }
        val page = processor.render(input, Edits(preset = Preset.BLACK_WHITE), output, ExportFormat.PNG, ScanConfig())
        val cleanedOutput = File(directory, "shadow-faint-print-auto.png")
        processor.render(input, Edits(preset = Preset.AUTO), cleanedOutput, ExportFormat.PNG, ScanConfig())
        val legacyOutput = File(directory, "shadow-faint-print-legacy-gaussian.png")
        val raw = BitmapFactory.decodeFile(input.absolutePath)!!
        val rgba = Mat(); val gray = Mat(); val legacyBw = Mat()
        try {
            Utils.bitmapToMat(raw, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.adaptiveThreshold(gray, legacyBw, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY, 31, 12.0)
            val legacyBitmap = Bitmap.createBitmap(legacyBw.cols(), legacyBw.rows(), Bitmap.Config.ARGB_8888)
            try {
                Utils.matToBitmap(legacyBw, legacyBitmap)
                legacyOutput.outputStream().use { assertTrue(legacyBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { legacyBitmap.recycle() }
        } finally { raw.recycle(); rgba.release(); gray.release(); legacyBw.release() }
        val rendered = BitmapFactory.decodeFile(output.absolutePath)!!
        try {
            var blackTextPixels = 0
            var currentThinLineHits = 0
            var legacyThinLineHits = 0
            val renderedPixels = IntArray(page.width * page.height)
            rendered.getPixels(renderedPixels, 0, page.width, 0, 0, page.width, page.height)
            for (y in page.height * 40 / 100..page.height * 55 / 100)
                for (x in page.width * 8 / 100..page.width * 66 / 100)
                    if (Color.red(renderedPixels[y * page.width + x]) < 128) blackTextPixels++
            val legacy = BitmapFactory.decodeFile(legacyOutput.absolutePath)!!
            try {
                for (x in 110..790) {
                    if (Color.red(rendered.getPixel(x, 650)) < 128) currentThinLineHits++
                    if (Color.red(legacy.getPixel(x, 650)) < 128) legacyThinLineHits++
                }
            } finally { legacy.recycle() }
            android.util.Log.i("BwThresholdComparison", "same synthetic source strong=$blackTextPixels paleThinLine current=$currentThinLineHits legacy=$legacyThinLineHits sauvola=$sauvolaBlack window=45 k=0.20")
            assertTrue("Faint letters and punctuation should survive Gaussian thresholding", blackTextPixels > 500)
            assertTrue("Sauvola comparator should also retain printed strokes", sauvolaBlack > 500)
            assertTrue("C=7 plus correction should retain the faint 1px line better than legacy C=12: current=$currentThinLineHits legacy=$legacyThinLineHits",
                currentThinLineHits > legacyThinLineHits)
            assertTrue("Most of the synthetic thin line should remain visible", currentThinLineHits > 500)
            val external = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
            if (external != null) {
                input.copyTo(File(external, "scanner-bw-source-synthetic-shadow.jpg"), overwrite = true)
                output.copyTo(File(external, "scanner-bw-new-adaptive-gaussian.png"), overwrite = true)
                cleanedOutput.copyTo(File(external, "scanner-bw-auto-cleaned-grayscale.png"), overwrite = true)
                legacyOutput.copyTo(File(external, "scanner-bw-before-fixed-gaussian-31-c12.png"), overwrite = true)
                File(external, "scanner-bw-threshold-comparison.json").writeText(
                    """{"fixture":"synthetic 1200x800 shaded paper with strong and pale print; not camera acceptance","legacy":"adaptive Gaussian block=31 C=12","current":"background corrected adaptive Gaussian block=scaled C=7","comparison":"Sauvola k=0.20 R=128 window=45 on same source","currentStrongInkPixels":$blackTextPixels,"sauvolaStrongInkPixels":$sauvolaBlack,"currentPaleThinLineHits":$currentThinLineHits,"legacyPaleThinLineHits":$legacyThinLineHits,"currentPaperSamples":[${Color.red(rendered.getPixel(page.width / 2, page.height / 10))},${Color.red(rendered.getPixel(page.width / 2, page.height * 9 / 10))}]}"""
                )
            }
            assertEquals("Paper at both ends of the shadow is cleaned", 255, Color.red(rendered.getPixel(page.width / 2, page.height / 10)))
            assertEquals(255, Color.red(rendered.getPixel(page.width / 2, page.height * 9 / 10)))
        } finally { rendered.recycle() }
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

    /** Test-only classic Sauvola comparator using integral sums; source pixels are shared. */
    private fun sauvolaBlackPixels(bitmap: Bitmap, left: Int, right: Int, top: Int, bottom: Int): Int {
        val width = bitmap.width; val height = bitmap.height; val stride = width + 1
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val sums = LongArray((width + 1) * (height + 1))
        val squares = LongArray(sums.size)
        for (y in 1..height) {
            var rowSum = 0L; var rowSquares = 0L
            for (x in 1..width) {
                val color = pixels[(y - 1) * width + x - 1]
                val gray = (Color.red(color) * 77 + Color.green(color) * 150 + Color.blue(color) * 29) shr 8
                rowSum += gray; rowSquares += gray.toLong() * gray
                val index = y * stride + x; val above = index - stride
                sums[index] = sums[above] + rowSum
                squares[index] = squares[above] + rowSquares
            }
        }
        val radius = 22; var black = 0
        for (y in top..bottom) for (x in left..right) {
            val x0 = maxOf(0, x - radius); val x1 = minOf(width - 1, x + radius)
            val y0 = maxOf(0, y - radius); val y1 = minOf(height - 1, y + radius)
            val count = (x1 - x0 + 1) * (y1 - y0 + 1).toDouble()
            fun box(integral: LongArray): Double {
                val a = y0 * stride + x0; val b = y0 * stride + x1 + 1
                val c = (y1 + 1) * stride + x0; val d = (y1 + 1) * stride + x1 + 1
                return (integral[d] - integral[b] - integral[c] + integral[a]).toDouble()
            }
            val mean = box(sums) / count
            val variance = maxOf(0.0, box(squares) / count - mean * mean)
            val threshold = mean * (1 + .20 * (sqrt(variance) / 128.0 - 1))
            val color = pixels[y * width + x]
            val gray = (Color.red(color) * 77 + Color.green(color) * 150 + Color.blue(color) * 29) shr 8
            if (gray < threshold) black++
        }
        return black
    }
}
