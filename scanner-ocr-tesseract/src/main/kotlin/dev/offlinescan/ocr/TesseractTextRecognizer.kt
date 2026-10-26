package dev.offlinescan.ocr

import android.graphics.BitmapFactory
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
        require(bounds.outWidth in 1..config.maxInputDimension && bounds.outHeight in 1..config.maxInputDimension) { "Invalid OCR image" }
        require(bounds.outWidth.toLong() * bounds.outHeight <= config.maxInputPixels) { "OCR image exceeds pixel budget" }
        cancellation.check()
        val bitmap = requireNotNull(BitmapFactory.decodeFile(image.path)) { "Cannot decode OCR image" }
        try {
            require(bitmap.width in 1..config.maxInputDimension && bitmap.height in 1..config.maxInputDimension &&
                bitmap.width.toLong() * bitmap.height <= config.maxInputPixels) { "OCR image exceeds pixel budget" }
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
                config.region?.takeIf { prepared == null }?.let {
                    val left = floor(it.left * bitmap.width).toInt()
                    val top = floor(it.top * bitmap.height).toInt()
                    api.setRectangle(left, top, ceil(it.right * bitmap.width).toInt() - left,
                        ceil(it.bottom * bitmap.height).toInt() - top)
                }
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
                            words += OcrWord(word, box[0], box[1], box[2], box[3], iterator.confidence(level))
                        }
                    } while (iterator.next(level))
                } finally { iterator.delete() }
                return OcrPage(pageId, bitmap.width, bitmap.height, text, words)
            } finally { try { api.recycle() } finally { prepared?.bitmap?.recycle() } }
        } finally { bitmap.recycle() }
    }
    companion object { private val jobLock = Semaphore(1) }
}
