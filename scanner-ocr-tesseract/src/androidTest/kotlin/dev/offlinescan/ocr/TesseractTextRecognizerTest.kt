package dev.offlinescan.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import dev.offlinescan.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Run tools/prepare_ocr_test_models.py before building the test APK. */
class TesseractTextRecognizerTest {
    @Test fun rejectsOverlappingJobsAcrossInstances() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "ocr-concurrency-${System.nanoTime()}").apply { mkdirs() }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            File(root, "tessdata").mkdir()
            File(root, "tessdata/eng.traineddata").writeText("untrusted")
            var checks = 0
            val first = executor.submit<Boolean> {
                try {
                    TesseractTextRecognizer(root).recognize(File(root,"image"), "one",
                        OcrConfig(languages = listOf("eng")), Cancellation {
                            if (++checks == 2) { entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
                        })
                    false
                } catch (_: IllegalArgumentException) { true }
            }
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            try {
                TesseractTextRecognizer(root).recognize(File(root,"image"), "two",
                    OcrConfig(languages = listOf("eng")), NeverCancelled)
                fail("Expected concurrency rejection")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("Another OCR job"))
            } finally { release.countDown() }
            assertTrue(first.get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(listOf("tessdata"), root.list()!!.toList())
        } finally { release.countDown(); executor.shutdownNow(); root.deleteRecursively() }
    }

    @Test fun rejectsUntrustedModelBeforeNativeInitializationAndCleansWorkspace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "ocr-untrusted-${System.nanoTime()}").apply { mkdirs() }
        try {
            File(root, "tessdata").mkdir()
            File(root, "tessdata/eng.traineddata").writeText("not an approved model")
            try {
                TesseractTextRecognizer(root).recognize(File(root, "unused.png"), "page",
                    OcrConfig(languages = listOf("eng")), NeverCancelled)
                fail("Expected model rejection")
            } catch (expected: IllegalArgumentException) {
                assertEquals("OCR model integrity check failed", expected.message)
            }
            assertEquals(listOf("tessdata"), root.list()!!.toList())
        } finally { root.deleteRecursively() }
    }

    @Test fun recognizesJapaneseAndEnglishTogether() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "ocr-jpn-${System.nanoTime()}").apply { mkdirs() }
        try {
            val models = File(root, "tessdata").apply { mkdirs() }
            for (language in listOf("eng", "jpn")) {
                instrumentation.context.assets.open("$language.traineddata").use { input ->
                    File(models, "$language.traineddata").outputStream().use { input.copyTo(it) }
                }
            }
            val image = File(root, "bilingual.png")
            // Identical pixels across Android releases: platform fallback fonts differ.
            instrumentation.context.assets.open("bilingual.png").use { input ->
                image.outputStream().use { input.copyTo(it) }
            }
            val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
            val peakPss = java.util.concurrent.atomic.AtomicInteger(0)
            val sampler = Thread {
                while (sampling.get()) {
                    val memory = android.os.Debug.MemoryInfo()
                    android.os.Debug.getMemoryInfo(memory)
                    peakPss.updateAndGet { maxOf(it, memory.totalPss) }
                    Thread.sleep(50)
                }
            }.apply { start() }
            val start = android.os.SystemClock.elapsedRealtime()
            val result = try {
                TesseractTextRecognizer(root).recognize(image, "front",
                    OcrConfig(layout = OcrLayout.SPARSE), NeverCancelled)
            } finally { sampling.set(false); sampler.join() }
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("stream", "\nFixed bilingual fixture: ${android.os.SystemClock.elapsedRealtime()-start} ms; sampled process peak PSS ${peakPss.get()} KiB (50 ms sampling, includes test harness).\n")
            })
            assertTrue(result.text, result.text.replace(" ", "").contains("会員番号"))
            assertTrue(result.text, result.text.contains("MEMBER"))
            assertTrue(result.text, result.text.contains("0012345678"))
        } finally { root.deleteRecursively() }
    }

    @Test fun recognizesSyntheticCardNumberAndReportsFullImageCoordinates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "ocr-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val models = File(root, "tessdata").apply { mkdirs() }
            instrumentation.context.assets.open("eng.traineddata").use { input ->
                File(models, "eng.traineddata").outputStream().use { input.copyTo(it) }
            }
            val image = File(root, "card.png")
            val bitmap = Bitmap.createBitmap(1400, 600, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                canvas.drawText("0012345678", 300f, 350f, Paint().apply { color = Color.BLACK; textSize = 90f; isAntiAlias = true })
                image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
            val recognizer = TesseractTextRecognizer(root)
            val config = OcrConfig(languages = listOf("eng"), layout = OcrLayout.SINGLE_LINE,
                region = OcrRegion(0.15, 0.3, 0.9, 0.75), characterWhitelist = "0123456789")
            val result = recognizer.recognize(image, "front", config, NeverCancelled)
            assertEquals("0012345678", result.text.trim())
            assertEquals("front", result.pageId)
            assertEquals(1400, result.width)
            assertTrue(result.words.isNotEmpty())
            assertTrue(result.words.all { it.left >= 210 && it.top >= 180 && it.right <= 1260 && it.bottom <= 450 })
            try {
                recognizer.recognize(image, "front", config.copy(maxInputPixels = 100), NeverCancelled)
                fail("Expected pixel limit")
            } catch (_: IllegalArgumentException) { }
            try {
                recognizer.recognize(image, "front", config.copy(languages = listOf("jpn")), NeverCancelled)
                fail("Expected missing model")
            } catch (_: IllegalArgumentException) { }
        } finally { root.deleteRecursively() }
    }
    @Test fun smallFieldKeepsDetailWhenThePageExceedsTheDecodeBudget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "region-detail-${System.nanoTime()}").apply { mkdirs() }
        try {
            val models = File(root, "tessdata").apply { mkdirs() }
            instrumentation.context.assets.open("eng.traineddata").use { input ->
                File(models, "eng.traineddata").outputStream().use { input.copyTo(it) }
            }
            val image = File(root, "card.jpg")
            val bitmap = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).apply {
                    drawColor(Color.WHITE)
                    drawText("0012345678", 1730f, 1520f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 28f
                    })
                }
                image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 98, it) }
            } finally { bitmap.recycle() }
            val config = OcrConfig(languages = listOf("eng"), layout = OcrLayout.SINGLE_LINE,
                region = OcrRegion(.425, .49, .575, .51), characterWhitelist = "0123456789",
                maxWorkingBytes = 30L * 1024 * 1024)
            val result = TesseractTextRecognizer(root).recognize(image, "card", config, NeverCancelled)
            assertEquals("0012345678", result.text.trim())
            assertEquals(4000 to 3000, result.width to result.height)
            assertTrue(result.words.isNotEmpty())
            assertTrue(result.words.all { it.left in 1725..1900 && it.top in 1485..1519 &&
                it.right in (it.left + 1)..1920 && it.bottom in 1505..1528 })
        } finally { root.deleteRecursively() }
    }

    @Test fun straightensTiltedNumericFieldAndMapsBounds() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val root=File(instrumentation.targetContext.cacheDir,"ocr-tilt-${System.nanoTime()}").apply {mkdirs()}
        try {
            val models=File(root,"tessdata").apply {mkdirs()}
            instrumentation.context.assets.open("eng.traineddata").use { input ->
                File(models,"eng.traineddata").outputStream().use {input.copyTo(it)}
            }
            val image=File(root,"tilted.png")
            val fixture=instrumentation.context.assets.open("tilted-digits.png").use {android.graphics.BitmapFactory.decodeStream(it)}!!
            val canvasImage=Bitmap.createBitmap(800,400,Bitmap.Config.ARGB_8888)
            try {
                Canvas(canvasImage).apply {drawColor(Color.WHITE); drawBitmap(fixture,100f,80f,null)}
                image.outputStream().use {canvasImage.compress(Bitmap.CompressFormat.PNG,100,it)}
            } finally {fixture.recycle();canvasImage.recycle()}
            val config=OcrConfig(languages=listOf("eng"),layout=OcrLayout.SINGLE_LINE,
                region=OcrRegion(0.125,0.2,0.75,0.5),characterWhitelist="0123456789",straightenLine=true)
            val result=TesseractTextRecognizer(root).recognize(image,"tilted",config,NeverCancelled)
            assertEquals("38180624",result.text.trim())
            assertEquals(800,result.width)
            assertEquals(400,result.height)
            assertTrue(result.words.isNotEmpty())
            assertTrue(result.words.all {it.left>=100 && it.right<=600 && it.top>=80 && it.bottom<=200 && it.left<it.right && it.top<it.bottom})
        } finally {root.deleteRecursively()}
    }

}
