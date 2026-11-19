package dev.offlinescan.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import dev.offlinescan.core.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Semaphore
import android.os.Looper
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Offline OCR with host-installed models in dataDirectory/tessdata/<language>.traineddata.
 * Each call verifies approved model hashes and uses private file snapshots.
 * A process-wide gate rejects overlapping jobs. Run sequentially on a worker thread.
 * Cancellation is checked before/after native recognition; it cannot interrupt that blocking call.
 * Direct inputs must be physically upright PNG/JPEG rasters (EXIF rotation is not applied).
 */
class TesseractTextRecognizer(private val dataDirectory: File) : TextRecognizer {
    override fun recognize(image: File, pageId: String, config: OcrConfig, cancellation: Cancellation): OcrPage {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Run OCR on a worker thread" }
        cancellation.check()
        check(jobLock.tryAcquire()) { "Another OCR job is running; submit pages sequentially" }
        try {
            val safeConfig = config.copy(languages = config.languages.toList())
            require(safeConfig.languages.all { it in OcrFiles.approvedModels }) { "Unsupported or unapproved OCR language" }
            require(dataDirectory.isDirectory) { "Missing private OCR data directory" }
            val workspace = Files.createTempDirectory(dataDirectory.toPath(), ".ocr-").toFile()
            try {
                val models = File(workspace, "tessdata").apply { check(mkdir()) }
                for (language in safeConfig.languages) {
                    OcrFiles.copyVerified(File(dataDirectory, "tessdata/$language.traineddata"),
                        File(models, "$language.traineddata"), OcrFiles.MAX_MODEL_BYTES,
                        OcrFiles.approvedModels.getValue(language), cancellation)
                }
                val snapshot = File(workspace, "image")
                OcrFiles.copyVerified(image, snapshot, safeConfig.maxInputBytes, null, cancellation)
                return recognizeSnapshot(snapshot, workspace, pageId, safeConfig, cancellation)
            } finally { workspace.deleteRecursively() }
        } finally { jobLock.release() }
    }

    private fun recognizeSnapshot(image: File, modelRoot: File, pageId: String, config: OcrConfig, cancellation: Cancellation): OcrPage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.path, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        require(width in 1..config.maxInputDimension && height in 1..config.maxInputDimension) { "Invalid OCR image" }
        require(width.toLong() * height <= config.maxInputPixels) { "OCR image exceeds pixel limit" }
        val pixelBudget = config.inputPixelBudget()
        require(pixelBudget > 0) { "OCR working-memory budget is too small" }
        val area = config.region?.let {
            Rect(floor(it.left * width).toInt(), floor(it.top * height).toInt(),
                ceil(it.right * width).toInt(), ceil(it.bottom * height).toInt())
        } ?: Rect(0, 0, width, height)
        val decode = BitmapFactory.Options().apply {
            inSampleSize = ocrSampleSize(area.width(), area.height(), pixelBudget)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        cancellation.check()
        val bitmap = requireNotNull(if (config.region == null) BitmapFactory.decodeFile(image.path, decode)
            else decodeRegion(image, area, decode)) { "Cannot decode OCR image" }
        try {
            require(bitmap.width in 1..area.width() && bitmap.height in 1..area.height() &&
                bitmap.width.toLong() * bitmap.height <= pixelBudget) { "OCR image exceeds working-memory budget" }
            cancellation.check()
            val prepared = if(config.straightenLine) PreparedLine.create(bitmap,config,cancellation) else null
            val api = TessBaseAPI()
            try {
                check(api.init(modelRoot.absolutePath, config.languages.joinToString("+"), TessBaseAPI.OEM_LSTM_ONLY)) {
                    "Cannot initialize OCR models"
                }
                api.setPageSegMode(when (config.layout) {
                    OcrLayout.DOCUMENT -> TessBaseAPI.PageSegMode.PSM_AUTO
                    OcrLayout.SPARSE -> TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
                    OcrLayout.SINGLE_LINE -> TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
                })
                config.characterWhitelist?.let { check(api.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, it)) }
                api.setImage(prepared?.bitmap ?: bitmap)
                cancellation.check()
                val text = api.utF8Text.orEmpty()
                cancellation.check()
                val words = ArrayList<OcrWord>()
                val iterator = api.resultIterator
                if (iterator != null) try {
                    val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                    iterator.begin()
                    do {
                        cancellation.check()
                        val word = iterator.getUTF8Text(level).orEmpty()
                        if (word.isNotBlank()) {
                            val rawBox = iterator.getBoundingBox(level)
                            val box = prepared?.bounds(rawBox) ?: rawBox
                            val scaled = scaleOcrBounds(box, bitmap.width, bitmap.height, area.width(), area.height())
                            words += OcrWord(word, area.left + scaled[0], area.top + scaled[1],
                                area.left + scaled[2], area.top + scaled[3], iterator.confidence(level))
                        }
                    } while (iterator.next(level))
                } finally { iterator.delete() }
                return OcrPage(pageId, width, height, text, words)
            } finally { try { api.recycle() } finally { prepared?.bitmap?.recycle() } }
        } finally { bitmap.recycle() }
    }
    @Suppress("DEPRECATION") // The one-argument constructor requires API 31.
    private fun decodeRegion(image: File, area: Rect, options: BitmapFactory.Options): Bitmap? {
        val decoder = BitmapRegionDecoder.newInstance(image.path, false) ?: return null
        return try { decoder.decodeRegion(area, options) } finally { decoder.recycle() }
    }

    companion object { private val jobLock = Semaphore(1) }
}


/** BitmapFactory rounds down to a power of two and a decoder may round up an odd edge. */
internal fun ocrSampleSize(width: Int, height: Int, budget: Long): Int {
    require(width > 0 && height > 0 && budget > 0)
    var sample = 1
    while (((width + sample - 1L) / sample) * ((height + sample - 1L) / sample) > budget) sample *= 2
    return sample
}

internal fun scaleOcrBounds(box: IntArray, decodedWidth: Int, decodedHeight: Int, width: Int, height: Int): IntArray {
    fun scale(value: Int, from: Int, to: Int, roundUp: Boolean): Int {
        val scaled = value.coerceIn(0, from).toLong() * to
        return ((scaled + if (roundUp) from - 1 else 0) / from).toInt()
    }
    return intArrayOf(
        scale(box[0], decodedWidth, width, false), scale(box[1], decodedHeight, height, false),
        scale(box[2], decodedWidth, width, true), scale(box[3], decodedHeight, height, true)
    )
}
