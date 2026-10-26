package dev.offlinescan.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.*

class OcrTest {
    @Test fun validatesLanguagePathsAndRegions() {
        assertEquals(listOf("eng", "jpn"), OcrConfig().languages)
        for (languages in listOf(emptyList(), listOf("../eng"), listOf("eng+jpn"), listOf("eng", "eng"))) {
            assertFailsWith<IllegalArgumentException> { OcrConfig(languages = languages) }
        }
        assertFailsWith<IllegalArgumentException> { OcrRegion(0.5, 0.0, 0.4, 1.0) }
        assertFailsWith<IllegalArgumentException> { OcrRegion(Double.NaN, 0.0, 1.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { OcrConfig(maxInputPixels = 0) }
        assertFailsWith<IllegalArgumentException> { OcrConfig(maxInputBytes = 0) }
        assertFailsWith<IllegalArgumentException> { OcrConfig(maxInputDimension = 100_000) }
    }

    private fun runPipeline(fail: Boolean) {
        val root = Files.createTempDirectory("ocr-test").toFile()
        try {
            val original = File(root, "original").apply { writeText("host source") }
            val keep = File(root, "keep").apply { writeText("host output") }
            val edits = Edits(rotationQuarterTurns = 1, preset = Preset.COLOR_DOCUMENT)
            val pages = listOf("back", "front").map { ScanPage(it, original, 30, 20, edits) }
            var renders = 0
            val processor = object : ImageProcessor {
                override fun inspect(source: File, config: ScanConfig) = 30 to 20
                override fun detect(source: File, config: ScanConfig, cancellation: Cancellation): Detection = error("unused")
                override fun render(source: File, edits: Edits, destination: File, format: ExportFormat, config: ScanConfig, cancellation: Cancellation): ExportedPage {
                    assertEquals(original, source)
                    assertEquals(pages[0].edits, edits)
                    assertEquals(ExportFormat.PNG, format)
                    renders++
                    destination.writeText("rendered")
                    return ExportedPage(20, 30)
                }
            }
            val recognizer = TextRecognizer { image, id, _, _ ->
                assertEquals("rendered", image.readText())
                if (fail) throw CancellationException("cancelled during OCR")
                OcrPage(id, 20, 30, "会員 MEMBER 001234", emptyList())
            }
            val progress = mutableListOf<Int>()
            val action = { ScanOcr(processor, recognizer).recognize(pages, root, ScanConfig(), progress = Progress { n, total ->
                assertEquals(2, total); progress += n
            }) }
            if (fail) {
                assertFailsWith<CancellationException> { action() }
                assertEquals(1, renders)
            } else {
                assertEquals(listOf("back", "front"), action().map { it.pageId })
                assertEquals(listOf(0, 1, 2), progress)
            }
            assertEquals(setOf(original.name, keep.name), root.list()!!.toSet())
            assertEquals("host source", original.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun preservesOrderAndEditsAndCleansTemporaryRasters() = runPipeline(false)
    @Test fun cancellationPropagatesAndCleansTemporaryRasters() = runPipeline(true)
    @Test fun rejectsOversizedBatchBeforeRenderingOrCreatingTemporaryFiles() {
        val root = Files.createTempDirectory("ocr-batch").toFile()
        try {
            val processor = object : ImageProcessor {
                override fun inspect(source: File, config: ScanConfig): Pair<Int,Int> = error("unused")
                override fun detect(source: File, config: ScanConfig, cancellation: Cancellation): Detection = error("unused")
                override fun render(source: File, edits: Edits, destination: File, format: ExportFormat, config: ScanConfig, cancellation: Cancellation): ExportedPage = error("must not render")
            }
            val pages = List(3) { ScanPage("$it", File(root,"source"),10,10,Edits()) }
            assertFailsWith<IllegalArgumentException> {
                ScanOcr(processor, TextRecognizer { _, _, _, _ -> error("must not recognize") }).recognize(
                    pages, root, ScanConfig(mode = ScanMode.CARD, cardFrontBack = true))
            }
            assertTrue(root.list()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun alreadyCancelledDoesNotCreateFiles() {
        val root = Files.createTempDirectory("ocr-cancel").toFile()
        try {
            val processor = object : ImageProcessor {
                override fun inspect(source: File, config: ScanConfig): Pair<Int,Int> = error("unused")
                override fun detect(source: File, config: ScanConfig, cancellation: Cancellation): Detection = error("unused")
                override fun render(source: File, edits: Edits, destination: File, format: ExportFormat, config: ScanConfig, cancellation: Cancellation): ExportedPage = error("must not render")
            }
            assertFailsWith<CancellationException> {
                ScanOcr(processor, TextRecognizer { _, _, _, _ -> error("must not recognize") }).recognize(
                    emptyList(), root, ScanConfig(), cancellation = Cancellation { throw CancellationException() })
            }
            assertTrue(root.list()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }
}
