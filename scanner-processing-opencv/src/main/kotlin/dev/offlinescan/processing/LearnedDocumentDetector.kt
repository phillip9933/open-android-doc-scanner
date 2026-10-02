package dev.offlinescan.processing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import dev.offlinescan.core.*
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.*

/** Offline, single-document DocQuadNet inference. Call on a worker thread; inputs remain caller-owned.
 * RGB/255, black letterbox and peak coordinates follow MakeACopy's Apache-2 reference decoder.
 * Learned evidence alone selects the quad; Standard remains a separate detector.
 */
class LearnedDocumentDetector(context: Context) : AutoCloseable {
    private val assets = context.applicationContext.assets
    private val quality = OpenCvProcessor()
    private var session: OrtSession? = null
    private var closed = false
    internal data class ModelEvidence(val corners: Quad, val minimumPeak: Double, val maskFraction: Double?, val agreement: Double?, val rejection: String?)
    internal var lastEvidence: ModelEvidence? = null
        private set

    @Synchronized
    fun detectBitmap(bitmap: Bitmap, cancellation: Cancellation = NeverCancelled): Detection {
        check(!closed) { "Learned detector has been closed" }
        lastEvidence = null
        cancellation.check()
        if (bitmap.isRecycled || bitmap.width < 2 || bitmap.height < 2)
            throw ScanException(ScanError(ErrorCode.INVALID_IMAGE, "Camera image is unavailable"))
        val geometry = Letterbox(bitmap.width, bitmap.height)
        val input = preprocess(bitmap, geometry)
        val decoded = try {
            val environment = OrtEnvironment.getEnvironment()
            val active = session ?: loadSession(environment).also { session = it }
            cancellation.check()
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, 256, 256)).use { tensor ->
                active.run(mapOf("input" to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val corners = result.get("corner_heatmaps").orElseThrow().value as Array<Array<Array<FloatArray>>>
                    @Suppress("UNCHECKED_CAST")
                    val mask = result.get("mask_logits").orElseThrow().value as Array<Array<Array<FloatArray>>>
                    decode(corners, mask, geometry)
                }
            }
        } catch (error: Exception) {
            cancellation.check()
            throw ScanException(ScanError(ErrorCode.PROCESSING, "Offline AI document detection could not run"), error)
        }
        cancellation.check()
        return quality.measureDetectionBitmap(bitmap, decoded.first, decoded.second, cancellation)
    }

    /** Uses the same upright coordinate convention and resource bounds as Standard. */
    fun detect(source: File, config: ScanConfig, cancellation: Cancellation = NeverCancelled): Detection {
        cancellation.check()
        quality.inspect(source, config)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 960) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw ScanException(ScanError(ErrorCode.INVALID_IMAGE, "Image cannot be decoded"))
        var upright: Bitmap? = null
        try {
            cancellation.check()
            val exif = ExifInterface(source)
            val matrix = Matrix().apply {
                if (exif.isFlipped) postScale(-1f, 1f)
                postRotate(exif.rotationDegrees.toFloat())
            }
            upright = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            return detectBitmap(upright, cancellation)
        } finally {
            upright?.takeIf { it !== decoded }?.recycle()
            decoded.recycle()
        }
    }

    private fun loadSession(environment: OrtEnvironment): OrtSession {
        val bytes = assets.open(MODEL_ASSET).use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(digest == MODEL_SHA256) { "Bundled document model integrity check failed" }
        return OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setIntraOpNumThreads(minOf(2, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1))
            options.setInterOpNumThreads(1)
            environment.createSession(bytes, options)
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) { closed = true; session?.close(); session = null }
        // OrtEnvironment is shared across the application; do not close it here.
    }

    private data class Letterbox(val width: Int, val height: Int) {
        val scale = minOf(256.0 / width, 256.0 / height)
        val left = (256 - width * scale) / 2
        val top = (256 - height * scale) / 2
        fun normalized(x: Double, y: Double) = Point((x-left)/(width*scale), (y-top)/(height*scale))
    }

    private fun preprocess(bitmap: Bitmap, geometry: Letterbox): FloatArray {
        val letterbox = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(letterbox)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap, null, RectF(geometry.left.toFloat(), geometry.top.toFloat(),
                (geometry.left+geometry.width*geometry.scale).toFloat(), (geometry.top+geometry.height*geometry.scale).toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG or Paint.ANTI_ALIAS_FLAG))
            val pixels = IntArray(256*256)
            letterbox.getPixels(pixels, 0, 256, 0, 0, 256, 256)
            return FloatArray(3*pixels.size).also { result ->
                for (i in pixels.indices) {
                    result[i] = ((pixels[i] ushr 16) and 255) / 255f
                    result[pixels.size+i] = ((pixels[i] ushr 8) and 255) / 255f
                    result[2*pixels.size+i] = (pixels[i] and 255) / 255f
                }
            }
        } finally { letterbox.recycle() }
    }

    /** No background rectangle fallback: uncertain/non-finite masks and unsupported quads are rejected. */
    private fun decode(corners: Array<Array<Array<FloatArray>>>, mask: Array<Array<Array<FloatArray>>>, geometry: Letterbox): Pair<Quad?, Double> {
        require(corners.size == 1 && corners[0].size == 4 && corners[0].all { channel -> channel.size == 64 && channel.all { it.size == 64 } })
        require(mask.size == 1 && mask[0].size == 1 && mask[0][0].size == 64 && mask[0][0].all { it.size == 64 })
        if (corners[0].any { channel -> channel.any { row -> row.any { !it.isFinite() } } } || mask[0][0].any { row -> row.any { !it.isFinite() } }) return null to 0.0
        var minimumPeak = 1.0
        val points = corners[0].map { channel ->
            var peak = -Float.MAX_VALUE; var px = 0; var py = 0
            for (y in 0..63) for (x in 0..63) if (channel[y][x] > peak) { peak = channel[y][x]; px = x; py = y }
            minimumPeak = minOf(minimumPeak, sigmoid(peak.toDouble()))
            // Local softmax centroid refines the 4px output grid without inventing a missing corner.
            var total = 0.0; var weightedX = 0.0; var weightedY = 0.0
            for (y in maxOf(0, py-1)..minOf(63, py+1)) for (x in maxOf(0, px-1)..minOf(63, px+1)) {
                val weight = exp((channel[y][x]-peak).toDouble())
                total += weight; weightedX += weight*(x+.5); weightedY += weight*(y+.5)
            }
            geometry.normalized(weightedX/total*4, weightedY/total*4)
        }
        val quad = Quad(points[0], points[1], points[2], points[3])
        if (!Geometry.valid(quad, .015) || Geometry.area(quad) > .95) {
            lastEvidence = ModelEvidence(quad,minimumPeak,null,null,"geometry")
            return null to 0.0
        }
        var foreground = 0; var intersection = 0; var union = 0; var imageCells = 0
        val polygon = quad.points
        for (y in 0..63) for (x in 0..63) {
            val point = geometry.normalized((x+.5)*4, (y+.5)*4)
            if (point.x !in 0.0..1.0 || point.y !in 0.0..1.0) continue
            imageCells++
            val positive = mask[0][0][y][x] > 0
            val inside = polygon.indices.all { i ->
                val a=polygon[i]; val b=polygon[(i+1)%4]
                (b.x-a.x)*(point.y-a.y)-(b.y-a.y)*(point.x-a.x) >= 0
            }
            if (positive) foreground++
            if (positive && inside) intersection++
            if (positive || inside) union++
        }
        val fraction = foreground.toDouble()/imageCells
        val agreement = if (union > 0) intersection.toDouble()/union else 0.0
        val rejection = when { minimumPeak < .55 -> "corner-evidence"; fraction !in .015.. .95 -> "mask-area"; agreement < .55 -> "mask-agreement"; else -> null }
        lastEvidence = ModelEvidence(quad,minimumPeak,fraction,agreement,rejection)
        if (rejection != null) return null to 0.0
        return quad to (minimumPeak*agreement).coerceIn(0.0, .94)
    }

    private fun sigmoid(value: Double) = if (value >= 0) 1/(1+exp(-value)) else exp(value)/(1+exp(value))

    companion object {
        const val MODEL_ASSET = "docquad/docquadnet256.ort"
        const val MODEL_SHA256 = "f0f2f52d7d79ff02d346c8f9d0c9e903407366aeea1747cdcff160c401e3e72a"
    }
}
