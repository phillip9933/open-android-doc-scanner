package dev.offlinescan.core

import java.io.File

/** Layout of the text, independent of capture mode. Inputs must already be upright. */
enum class OcrLayout { DOCUMENT, SPARSE, SINGLE_LINE }

/** Normalized rectangle in the rendered image, not in the uncropped camera original. */
data class OcrRegion(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        require(left >= 0 && top >= 0 && right <= 1 && bottom <= 1 && left < right && top < bottom)
    }
}

data class OcrConfig(
    /** Tesseract language IDs; hosts supply the corresponding models. */
    val languages: List<String> = listOf("eng", "jpn"),
    val layout: OcrLayout = OcrLayout.DOCUMENT,
    val region: OcrRegion? = null,
    /** Only use for a known field, never for a whole mixed-language card. */
    val characterWhitelist: String? = null,
    val maxInputPixels: Long = 24_000_000,
    val maxInputBytes: Long = 64L * 1024 * 1024,
    val maxInputDimension: Int = 16_384,
    /** Experimental bounded straightening for an explicitly selected dark-text single line. */
    val straightenLine: Boolean = false
) {
    init {
        require(!straightenLine || (layout == OcrLayout.SINGLE_LINE && region != null)) { "Line straightening requires a selected single-line region" }
        require(languages.size in 1..8 && languages.distinct().size == languages.size)
        require(languages.all { it.matches(Regex("[A-Za-z0-9_]+")) })
        require(characterWhitelist == null || (characterWhitelist.isNotEmpty() && '\u0000' !in characterWhitelist))
        require(maxInputPixels in 1..64_000_000)
        require(maxInputBytes in 1..128L * 1024 * 1024)
        require(maxInputDimension in 1..32_768)
    }
}

/** Bounds use pixels in OcrPage's upright rendered image. Confidence is 0..100, not a guarantee. */
data class OcrWord(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val confidence: Float)
data class OcrPage(val pageId: String, val width: Int, val height: Int, val text: String, val words: List<OcrWord>)

/** Blocking worker-thread API. No persistence, network, field inference, or PDF text layer. */
fun interface TextRecognizer {
    fun recognize(image: File, pageId: String, config: OcrConfig, cancellation: Cancellation): OcrPage
}

/** Render confirmed edits to lossless temporary images and recognize in page order. */
class ScanOcr(private val processor: ImageProcessor, private val recognizer: TextRecognizer) {
    fun recognize(
        pages: List<ScanPage>, temporaryRoot: File, scanConfig: ScanConfig,
        ocrConfig: OcrConfig = OcrConfig(), cancellation: Cancellation = NeverCancelled,
        progress: Progress = Progress { _, _ -> }
    ): List<OcrPage> {
        cancellation.check()
        require(pages.size in 1..scanConfig.pageLimit) { "OCR batch exceeds page limit or is empty" }
        val snapshot = pages.toList()
        require(snapshot.size in 1..scanConfig.pageLimit) { "OCR batch exceeds page limit or is empty" }
        require(temporaryRoot.isDirectory || temporaryRoot.mkdirs()) { "Cannot create OCR temporary directory" }
        val directory = java.nio.file.Files.createTempDirectory(temporaryRoot.toPath(), "ocr-").toFile()
        try {
            val results = ArrayList<OcrPage>()
            progress.update(0, snapshot.size)
            for (page in snapshot) {
                cancellation.check()
                val image = File(directory, "page.png")
                try {
                    processor.render(page.original, page.edits, image, ExportFormat.PNG, scanConfig, cancellation)
                    cancellation.check()
                    results += recognizer.recognize(image, page.id, ocrConfig, cancellation)
                    cancellation.check()
                    progress.update(results.size, snapshot.size)
                } finally { image.delete() }
            }
            return results
        } finally { directory.deleteRecursively() }
    }
}

/** Caller-defined field identity and recognition settings; location/meaning are not inferred. */
data class OcrFieldRequest(val id: String, val config: OcrConfig) {
    init {
        require(id.isNotBlank() && id.length <= 128) { "Field ID must contain 1..128 characters" }
        require(config.region != null) { "Field recognition requires an explicit region" }
    }
}

data class OcrFieldResult(val id: String, val page: OcrPage)

/**
 * Recognize up to 32 caller-selected fields sequentially, retaining caller order and IDs.
 * Use a stable upright raster for the entire call. Does not infer layout, combine text,
 * normalize names/numbers or parallelize native recognition. Results retain full-image bounds.
 * Returns only after every field succeeds; failures/cancellation propagate unchanged.
 */
fun TextRecognizer.recognizeFields(
    image: File, pageId: String, fields: List<OcrFieldRequest>,
    cancellation: Cancellation = NeverCancelled,
    progress: Progress = Progress { _, _ -> }
): List<OcrFieldResult> {
    cancellation.check()
    require(fields.size in 1..32) { "Field batch must contain 1..32 regions" }
    val requests = fields.map { it.copy(config=it.config.copy(languages=it.config.languages.toList())) }
    require(requests.size in 1..32 && requests.map { it.id }.distinct().size == requests.size) { "Field IDs must be unique" }
    val results = ArrayList<OcrFieldResult>(requests.size)
    progress.update(0,requests.size)
    for (request in requests) {
        cancellation.check()
        val page=recognize(image,pageId,request.config,cancellation)
        cancellation.check()
        results += OcrFieldResult(request.id,page)
        progress.update(results.size,requests.size)
    }
    cancellation.check()
    return results
}
