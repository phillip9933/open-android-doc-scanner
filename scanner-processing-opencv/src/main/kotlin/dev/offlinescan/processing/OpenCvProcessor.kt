package dev.offlinescan.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import dev.offlinescan.core.*
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvException
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point as CvPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.IOException
import kotlin.math.*

/** Quality of untouched document pixels, sampled independently of the surrounding scene. */
data class DocumentQuality(val sharpness: Double, val brightness: Double, val informativeTiles: Int, val tileCount: Int = 9)

/** Stateless, synchronous processor. Call on a worker thread; callers retain camera bitmap ownership. */
class OpenCvProcessor : ImageProcessor {
    /** Uses only the upright cropped original. Call on a worker; originals are never modified. */
    fun recommendEnhancement(source: File, corners: Quad, config: ScanConfig, cancellation: Cancellation = NeverCancelled): EnhancementRecommendation = nativeWork(cancellation) { checked ->
        Geometry.requireValid(corners)
        val info=metadata(source,config)
        Mats().use { mats ->
            val upright=decode(source,info,960,960L*960,mats,checked)
            val crop=perspective(upright,corners,768,mats)
            AutoEnhancement.recommend(crop,checked)
        }
    }
    /** Corners use the upright EXIF orientation. Blank paper has no reliable focus evidence. */
    fun assessQuality(source: File, corners: Quad, config: ScanConfig, cancellation: Cancellation = NeverCancelled): DocumentQuality = nativeWork(cancellation) { checked ->
        Geometry.requireValid(corners)
        val info = metadata(source, config)
        Mats().use { mats ->
            val upright = decode(source, info, DETECT_EDGE, DETECT_EDGE.toLong() * DETECT_EDGE, mats, checked)
            val gray = mats.mat(); Imgproc.cvtColor(upright, gray, Imgproc.COLOR_RGB2GRAY)
            documentQuality(gray, corners, mats, checked)
        }
    }

    /** Shares the still-image metric; caller retains ownership of the supplied bitmap. */
    fun assessQualityBitmap(bitmap: Bitmap, corners: Quad, cancellation: Cancellation = NeverCancelled): DocumentQuality = nativeWork(cancellation) { checked ->
        Geometry.requireValid(corners)
        if (bitmap.isRecycled || bitmap.width < 2 || bitmap.height < 2) fail(ErrorCode.INVALID_IMAGE, "Camera image is unavailable")
        Mats().use { mats ->
            checked.check()
            val scale = minOf(1.0, DETECT_EDGE.toDouble() / maxOf(bitmap.width, bitmap.height))
            var reduced: Bitmap? = null
            try {
                val input = if (scale < 1.0) Bitmap.createScaledBitmap(bitmap, maxOf(2,(bitmap.width*scale).roundToInt()), maxOf(2,(bitmap.height*scale).roundToInt()), true).also { reduced=it } else bitmap
                val rgba=mats.mat(); Utils.bitmapToMat(input,rgba)
                val gray=mats.mat(); Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY)
                documentQuality(gray,corners,mats,checked)
            } finally { reduced?.takeIf { it !== bitmap }?.recycle() }
        }
    }

    /** Attach shared untouched-pixel metrics to corners supplied by another offline detector. */
    fun measureDetectionBitmap(bitmap: Bitmap, corners: Quad?, confidence: Double, cancellation: Cancellation = NeverCancelled): Detection = nativeWork(cancellation) { checked ->
        corners?.let { Geometry.requireValid(it) }
        if (bitmap.isRecycled || bitmap.width < 2 || bitmap.height < 2) fail(ErrorCode.INVALID_IMAGE,"Camera image is unavailable")
        Mats().use { mats ->
            checked.check()
            val scale=minOf(1.0,DETECT_EDGE.toDouble()/maxOf(bitmap.width,bitmap.height))
            var reduced:Bitmap?=null
            try {
                val input=if(scale<1.0) Bitmap.createScaledBitmap(bitmap,maxOf(2,(bitmap.width*scale).roundToInt()),maxOf(2,(bitmap.height*scale).roundToInt()),true).also { reduced=it } else bitmap
                val rgba=mats.mat(); Utils.bitmapToMat(input,rgba)
                val gray=mats.mat(); Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY)
                val signature=mats.mat(); Imgproc.resize(gray,signature,Size(8.0,8.0),0.0,0.0,Imgproc.INTER_AREA)
                val bytes=ByteArray(64); signature.get(0,0,bytes)
                val quality=corners?.let { documentQuality(gray,it,mats,checked) }
                val sharpness=quality?.sharpness ?: wholeFrameSharpness(gray,mats)
                Detection(corners,if(corners==null) 0.0 else confidence.coerceIn(0.0,1.0),quality?.brightness ?: (Core.mean(gray).`val`[0]/255).coerceIn(0.0,1.0),sharpness,bytes.map { (it.toInt() and 255)/255.0 },quality?.informativeTiles)
            } finally { reduced?.takeIf { it !== bitmap }?.recycle() }
        }
    }

    override fun inspect(source: File, config: ScanConfig): Pair<Int, Int> {
        val info = metadata(source, config)
        return if (info.orientation in 5..8) info.height to info.width else info.width to info.height
    }

    override fun detect(source: File, config: ScanConfig, cancellation: Cancellation): Detection = nativeWork(cancellation) { checked ->
        checked.check()
        val info = metadata(source, config)
        Mats().use { mats ->
            val upright = decode(source, info, DETECT_EDGE, DETECT_EDGE.toLong() * DETECT_EDGE, mats, checked)
            detectMat(upright, mats, checked)
        }
    }

    /** Corners refer to the supplied bitmap's current visual orientation, without EXIF transforms. */
    fun detectBitmap(bitmap: Bitmap, cancellation: Cancellation = NeverCancelled): Detection = nativeWork(cancellation) { checked ->
        checked.check()
        if (bitmap.isRecycled || bitmap.width < 2 || bitmap.height < 2) fail(ErrorCode.INVALID_IMAGE, "Camera image is unavailable")
        Mats().use { mats ->
            val scale = minOf(1.0, DETECT_EDGE.toDouble() / maxOf(bitmap.width, bitmap.height))
            var reduced: Bitmap? = null
            try {
                val input = if (scale < 1.0) {
                    Bitmap.createScaledBitmap(bitmap, maxOf(2, (bitmap.width * scale).roundToInt()), maxOf(2, (bitmap.height * scale).roundToInt()), true).also { reduced = it }
                } else bitmap
                val rgba = mats.mat()
                Utils.bitmapToMat(input, rgba)
                val rgb = mats.mat()
                Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                detectMat(rgb, mats, checked)
            } finally { reduced?.takeIf { it !== bitmap }?.recycle() }
        }
    }

    override fun render(source: File, edits: Edits, destination: File, format: ExportFormat, config: ScanConfig, cancellation: Cancellation): ExportedPage = nativeWork(cancellation) { checked ->
        checked.check()
        if (format == ExportFormat.PDF) fail(ErrorCode.EXPORT, "Render pages as JPEG or PNG before PDF assembly")
        if (source.canonicalFile == destination.canonicalFile) fail(ErrorCode.STORAGE, "Output must not overwrite the original")
        Geometry.requireValid(edits.corners)
        val info = metadata(source, config)
        Mats().use { mats ->
            val automatic=if(edits.preset==Preset.AUTO) recommendEnhancement(source,edits.corners,config,checked) else null
            val upright = decode(source, info, minOf(config.maxOutputDimension, RENDER_EDGE), RENDER_PIXELS, mats, checked)
            checked.check()
            val warped = perspective(upright, edits.corners, config.maxOutputDimension, mats)
            upright.release()
            val rotated = mats.mat()
            when (Math.floorMod(edits.rotationQuarterTurns, 4)) {
                1 -> Core.rotate(warped, rotated, Core.ROTATE_90_CLOCKWISE)
                2 -> Core.rotate(warped, rotated, Core.ROTATE_180)
                3 -> Core.rotate(warped, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> warped.copyTo(rotated)
            }
            warped.release()
            checked.check()
            val result = enhance(rotated, edits, mats, checked, automatic)
            rotated.release()
            var bitmap: Bitmap? = null
            try {
                checked.check()
                bitmap = Bitmap.createBitmap(result.cols(), result.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(result, bitmap)
                checked.check()
                destination.outputStream().use { out ->
                    if (!bitmap.compress(if (format == ExportFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, config.jpegQuality, out)) {
                        fail(ErrorCode.EXPORT, "Image encoder failed")
                    }
                }
                checked.check()
                ExportedPage(bitmap.width, bitmap.height,
                    if(info.width.toLong()*info.height>RENDER_PIXELS || maxOf(info.width,info.height)>minOf(config.maxOutputDimension,RENDER_EDGE))
                        listOf("Original image was downsampled before processing to respect pixel and dimension limits.") else emptyList())
            } finally { bitmap?.recycle() }
        }
    }

    private data class Metadata(val width: Int, val height: Int, val orientation: Int)

    private fun metadata(file: File, config: ScanConfig): Metadata {
        if (!file.isFile || !file.canRead()) fail(ErrorCode.INVALID_IMAGE, "Image cannot be read")
        if (file.length() > 128L * 1024 * 1024) fail(ErrorCode.RESOURCE_LIMIT, "Encoded image exceeds 128 MB")
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val w = options.outWidth
        val h = options.outHeight
        if (w < 2 || h < 2) fail(ErrorCode.INVALID_IMAGE, "Unsupported or damaged image")
        if (w.toLong() * h > config.maxInputPixels || maxOf(w, h) > 100_000) fail(ErrorCode.RESOURCE_LIMIT, "Image exceeds the configured input pixel limit")
        val orientation = try {
            ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: IOException) { fail(ErrorCode.INVALID_IMAGE, "Image metadata cannot be read", e) }
        return Metadata(w, h, if (orientation in 1..8) orientation else 1)
    }

    /** Both dimensions and pixel count are bounded using metadata BEFORE pixel decoding. */
    private fun decode(file: File, info: Metadata, maxEdge: Int, maxPixels: Long, mats: Mats, cancellation: Cancellation): Mat {
        var sample = 1
        fun width() = (info.width.toLong() + sample - 1) / sample
        fun height() = (info.height.toLong() + sample - 1) / sample
        while (maxOf(width(), height()) > maxEdge || width() * height() > maxPixels) sample *= 2
        cancellation.check()
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }) ?: fail(ErrorCode.INVALID_IMAGE, "Image decoding failed")
        try {
            if (maxOf(bitmap.width, bitmap.height) > maxEdge || bitmap.width.toLong() * bitmap.height > maxPixels) {
                fail(ErrorCode.RESOURCE_LIMIT, "Decoder returned an unexpectedly large image")
            }
            if (bitmap.width < 2 || bitmap.height < 2) fail(ErrorCode.INVALID_IMAGE, "Decoded image is too small")
            cancellation.check()
            val rgba = mats.mat()
            Utils.bitmapToMat(bitmap, rgba)
            val rgb = mats.mat()
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            val upright = mats.mat()
            when (info.orientation) {
                2 -> Core.flip(rgb, upright, 1)
                3 -> Core.rotate(rgb, upright, Core.ROTATE_180)
                4 -> Core.flip(rgb, upright, 0)
                5 -> Core.transpose(rgb, upright)
                6 -> Core.rotate(rgb, upright, Core.ROTATE_90_CLOCKWISE)
                7 -> { Core.transpose(rgb, upright); Core.flip(upright, upright, -1) }
                8 -> Core.rotate(rgb, upright, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> rgb.copyTo(upright)
            }
            rgba.release()
            rgb.release()
            return upright
        } finally { bitmap.recycle() }
    }

    private fun perspective(source: Mat, quad: Quad, maxDimension: Int, mats: Mats): Mat {
        val p = quad.points.map { CvPoint(it.x * (source.cols() - 1), it.y * (source.rows() - 1)) }
        fun length(a: Int, b: Int) = hypot(p[a].x - p[b].x, p[a].y - p[b].y)
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
            val ab = length(i, (i + 1) % 4); val bc = length((i + 1) % 4, (i + 2) % 4)
            val sine = abs((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)) / (ab * bc)
            if (ab < 2 || !sine.isFinite() || sine < 0.015) fail(ErrorCode.INVALID_GEOMETRY, "Crop is too small or nearly degenerate")
        }
        val width = maxOf(length(0, 1), length(3, 2)) + 1
        val height = maxOf(length(0, 3), length(1, 2)) + 1
        val scale = minOf(1.0, maxDimension / maxOf(width, height), sqrt(OUTPUT_PIXELS / (width * height)))
        val w = maxOf(2, floor(width * scale).toInt())
        val h = maxOf(2, floor(height * scale).toInt())
        val from = mats.keep(MatOfPoint2f()).apply { fromArray(*p.toTypedArray()) }
        val to = mats.keep(MatOfPoint2f()).apply { fromArray(CvPoint(0.0, 0.0), CvPoint((w - 1).toDouble(), 0.0), CvPoint((w - 1).toDouble(), (h - 1).toDouble()), CvPoint(0.0, (h - 1).toDouble())) }
        val transform = mats.keep(Imgproc.getPerspectiveTransform(from, to))
        if (!Core.checkRange(transform) || abs(Core.determinant(transform)) < 1e-10) fail(ErrorCode.INVALID_GEOMETRY, "Crop transform is singular")
        val output = mats.mat()
        Imgproc.warpPerspective(source, output, transform, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE, Scalar.all(255.0))
        return output
    }

    private fun enhance(original: Mat, requested: Edits, mats: Mats, cancellation: Cancellation, automatic: EnhancementRecommendation?): Mat {
        val input=if(automatic!=null && automatic.paperCleanup>0.0) cleanPaper(original,automatic.paperCleanup,mats,cancellation) else original
        val edits=if(automatic==null) requested else requested.copy(preset=automatic.preset,
            brightness=(requested.brightness+automatic.brightness).coerceIn(-.25,.25),
            contrast=(requested.contrast*automatic.contrast).coerceIn(.75,1.5),
            illumination=(requested.illumination+automatic.illumination).coerceIn(0.0,.5))
        var color = input
        if (edits.illumination > 0) {
            val gray = mats.mat(); Imgproc.cvtColor(input, gray, Imgproc.COLOR_RGB2GRAY)
            val small = mats.mat()
            val scale = minOf(1.0, 128.0 / maxOf(input.cols(), input.rows()))
            Imgproc.resize(gray, small, Size(maxOf(2.0, input.cols() * scale), maxOf(2.0, input.rows() * scale)), 0.0, 0.0, Imgproc.INTER_AREA)
            val smooth = mats.mat(); Imgproc.GaussianBlur(small, smooth, Size(0.0, 0.0), 12.0)
            val background = mats.mat(); Imgproc.resize(smooth, background, input.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val correction = mats.mat()
            // Add a restrained common luminance offset to all channels; preserve hue and texture.
            background.convertTo(correction, CvType.CV_32F, -edits.illumination, Core.mean(smooth).`val`[0] * edits.illumination)
            color = mats.keep(input.clone())
            val rgbRow = ByteArray(input.cols() * 3)
            val offsets = FloatArray(input.cols())
            for (y in 0 until input.rows()) {
                if (y % 32 == 0) cancellation.check()
                input.get(y, 0, rgbRow)
                correction.get(y, 0, offsets)
                for (x in offsets.indices) for (channel in 0..2) {
                    val index = x * 3 + channel
                    rgbRow[index] = ((rgbRow[index].toInt() and 255) + offsets[x]).roundToInt().coerceIn(0, 255).toByte()
                }
                color.put(y, 0, rgbRow)
            }
            gray.release(); small.release(); smooth.release(); background.release(); correction.release()
        }
        cancellation.check()
        val adjusted = mats.mat()
        val contrast = edits.contrast * if (edits.preset == Preset.COLOR_DOCUMENT) 1.04 else 1.0
        color.convertTo(adjusted, CvType.CV_8U, contrast, 128.0 * (1 - contrast) + 255.0 * edits.brightness)
        if (color !== input) color.release()
        if (edits.preset != Preset.GRAYSCALE && edits.preset != Preset.BLACK_WHITE) return adjusted
        val gray = mats.mat(); Imgproc.cvtColor(adjusted, gray, Imgproc.COLOR_RGB2GRAY)
        if (edits.preset == Preset.GRAYSCALE) return gray
        val bw = mats.mat()
        val minEdge = minOf(gray.cols(), gray.rows())
        val block = minOf(31, if (minEdge % 2 == 0) minEdge - 1 else minEdge)
        if (block < 3 || automatic?.preset==Preset.BLACK_WHITE) Imgproc.threshold(gray, bw, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        else Imgproc.adaptiveThreshold(gray, bw, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 12.0)
        return bw
    }

    /** Estimate light paper at low resolution; lift illumination without sharpening or inventing ink. */
    private fun cleanPaper(input: Mat, strength: Double, mats: Mats, cancellation: Cancellation): Mat {
        val gray=mats.mat(); Imgproc.cvtColor(input,gray,Imgproc.COLOR_RGB2GRAY)
        val small=mats.mat(); val scale=minOf(1.0,192.0/maxOf(input.cols(),input.rows()))
        Imgproc.resize(gray,small,Size(maxOf(2.0,floor(input.cols()*scale)),maxOf(2.0,floor(input.rows()*scale))),0.0,0.0,Imgproc.INTER_AREA)
        val kernel=mats.keep(Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,Size(17.0,17.0)))
        val paper=mats.mat(); Imgproc.morphologyEx(small,paper,Imgproc.MORPH_CLOSE,kernel)
        Imgproc.GaussianBlur(paper,paper,Size(0.0,0.0),3.0)
        val background=mats.mat(); Imgproc.resize(paper,background,input.size(),0.0,0.0,Imgproc.INTER_LINEAR)
        val result=mats.keep(input.clone()); val row=ByteArray(input.cols()*3); val levels=ByteArray(input.cols())
        for(y in 0 until input.rows()) {
            if(y%32==0) cancellation.check()
            input.get(y,0,row); background.get(y,0,levels)
            for(x in levels.indices) {
                val light=(levels[x].toInt() and 255).coerceAtLeast(100)
                val gain=1.0+strength*(242.0/light-1.0).coerceIn(0.0,.65)
                for(c in 0..2) { val i=x*3+c; row[i]=((row[i].toInt() and 255)*gain).roundToInt().coerceIn(0,255).toByte() }
            }
            result.put(y,0,row)
        }
        gray.release(); small.release(); paper.release(); kernel.release(); background.release()
        return result
    }

    private fun detectMat(rgb: Mat, mats: Mats, cancellation: Cancellation): Detection {
        val gray = mats.mat(); Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val brightness = (Core.mean(gray).`val`[0] / 255.0).coerceIn(0.0, 1.0)
        val signature = mats.mat(); Imgproc.resize(gray, signature, Size(8.0, 8.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val pixels = ByteArray(64); signature.get(0, 0, pixels)
        val scene = pixels.map { (it.toInt() and 255) / 255.0 }
        cancellation.check()
        val blurred = mats.mat(); Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
        val edges = mats.mat()
        val kernel = mats.keep(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        // Only segmentation closes thin ink cuts; edge proposals retain the smaller kernel.
        val segmentationKernel = mats.keep(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0)))
        val hierarchy = mats.mat()
        // One bounded grayscale copy makes side validation independent of native per-pixel calls.
        val luminance = ByteArray(gray.rows() * gray.cols()); gray.get(0, 0, luminance)
        var best: Quad? = null
        var confidence = 0.0
        var localContrast: Mat? = null
        var localPixels: ByteArray? = null
        // Keep accepted RC3 candidates unchanged. Only a miss reaches normalized illumination
        // proposals, which must also demonstrate a real edge in the untouched image.
        for (pass in 0..4) {
            cancellation.check()
            when (pass) {
                0 -> Imgproc.Canny(blurred, edges, 35.0, 110.0)
                1 -> Imgproc.Canny(blurred, edges, 6.0, 20.0)
                2 -> Imgproc.threshold(blurred, edges, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                3 -> {
                    val normalized=normalizeIllumination(gray,mats)
                    localContrast=normalized
                    localPixels=ByteArray(gray.rows()*gray.cols()).also { normalized.get(0,0,it) }
                    Imgproc.Canny(normalized,edges,6.0,20.0)
                }
                else -> Imgproc.threshold(localContrast!!,edges,0.0,255.0,Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            }
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, if (pass==2 || pass==4) segmentationKernel else kernel)
            val contours = ArrayList<MatOfPoint>()
            try {
                // Dense boundary points support fitting actual side lines instead of rounded corners.
                Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE)
                // Cache area once: comparator callbacks otherwise repeatedly cross JNI in clutter.
                val proposals=contours.map { it to abs(Imgproc.contourArea(it)) }
                    .filter { it.second >= gray.total()*0.015 && it.second <= gray.total()*0.96 }
                    .sortedByDescending { it.second }.take(120)
                for ((contour,area) in proposals) {
                    cancellation.check()
                    val boundary = contour.toArray()
                    val curve = MatOfPoint2f()
                    var approximation: MatOfPoint2f? = null
                    var polygon: MatOfPoint? = null
                    var hullIndices: MatOfInt? = null
                    try {
                        val approx = MatOfPoint2f().also { approximation = it }
                        val poly = MatOfPoint().also { polygon = it }
                        val indices=MatOfInt().also { hullIndices=it }
                        Imgproc.convexHull(contour,indices)
                        // A hull bridges ink notches only as a proposal. Fitting and side evidence
                        // still use the original observed contour and untouched grayscale pixels.
                        val hull=indices.toArray().map { boundary[it] }.toTypedArray()
                        var supported=false
                        for (proposal in listOf(boundary,hull)) {
                            curve.fromArray(*proposal)
                            val perimeter = Imgproc.arcLength(curve, true)
                            for (tolerance in doubleArrayOf(0.012, 0.022, 0.035)) {
                                Imgproc.approxPolyDP(curve, approx, perimeter * tolerance, true)
                                if (approx.total() != 4L) continue
                                val points = approx.toArray(); poly.fromArray(*points)
                                if (!Imgproc.isContourConvex(poly)) continue
                                val initial = ordered(points, gray.cols(), gray.rows())
                                if (!Geometry.valid(initial, 0.015)) continue
                                val quad = refineSides(initial, boundary, gray.cols(), gray.rows()) ?: continue
                                if (!Geometry.valid(quad, 0.015)) continue
                                // Clipped pages have no observed fourth edge and require manual corners.
                                if (quad.points.any { it.x * (gray.cols()-1) < 2 || it.y * (gray.rows()-1) < 2 ||
                                        it.x * (gray.cols()-1) > gray.cols()-3 || it.y * (gray.rows()-1) > gray.rows()-3 }) continue
                                val qArea = Geometry.area(quad)
                                val solidity = (area / (qArea * (gray.cols()-1) * (gray.rows()-1))).coerceIn(0.0, 1.0)
                                if (solidity < 0.86) continue
                                val edgeQuality = sideEvidence(quad,luminance,gray.cols(),gray.rows(),if(pass>=3) localPixels else null) ?: continue
                                val angles = quad.points.indices.map { i ->
                                    val a = quad.points[(i+3)%4]; val b = quad.points[i]; val c = quad.points[(i+1)%4]
                                    val ux = (a.x-b.x)*gray.cols(); val uy = (a.y-b.y)*gray.rows()
                                    val vx = (c.x-b.x)*gray.cols(); val vy = (c.y-b.y)*gray.rows()
                                    1 - abs((ux*vx+uy*vy)/(hypot(ux,uy)*hypot(vx,vy))).coerceIn(0.0,1.0)
                                }
                                if (angles.min() < 0.12) continue
                                // Geometry alone never earns high confidence; all four measured sides matter.
                                val score = (0.25 + 0.20*solidity + 0.18*angles.average() +
                                    0.22*edgeQuality + 0.15*minOf(1.0,qArea/0.5)).coerceIn(0.0,if(pass>=3) 0.86 else 0.94)
                                if (score > confidence) { confidence = score; best = quad }
                                supported=true
                                break
                            }
                            if (supported) break
                        }
                    } finally { hullIndices?.release(); polygon?.release(); approximation?.release(); curve.release() }
                }
            } finally { contours.forEach { it.release() } }
            // Supported edge or threshold candidates stop before additional fallbacks, avoiding
            // corrected illumination fragments displacing an already accepted document.
            if (confidence >= 0.90 || (pass in 1..3 && best != null)) break
        }
        cancellation.check()
        val quality = best?.let { documentQuality(gray, it, mats, cancellation) }
        return Detection(best, if (best == null) 0.0 else confidence, quality?.brightness ?: brightness, quality?.sharpness ?: wholeFrameSharpness(gray,mats), scene, quality?.informativeTiles)
    }

    private fun wholeFrameSharpness(gray:Mat,mats:Mats):Double {
        val laplacian=mats.mat(); val mean=mats.keep(MatOfDouble()); val deviation=mats.keep(MatOfDouble())
        Imgproc.Laplacian(gray,laplacian,CvType.CV_64F)
        Core.meanStdDev(laplacian,mean,deviation)
        return deviation.toArray()[0].pow(2).coerceAtLeast(0.0).also { laplacian.release() }
    }

    /** Fixed 512px document sampling excludes paper edges, shadows outside it and background texture. */
    private fun documentQuality(gray: Mat, corners: Quad, mats: Mats, cancellation: Cancellation): DocumentQuality {
        cancellation.check()
        val p=corners.points.map { CvPoint(it.x*(gray.cols()-1),it.y*(gray.rows()-1)) }
        fun length(a:Int,b:Int)=hypot(p[a].x-p[b].x,p[a].y-p[b].y)
        val width=maxOf(length(0,1),length(3,2))
        val height=maxOf(length(0,3),length(1,2))
        if (minOf(width,height)<2) fail(ErrorCode.INVALID_GEOMETRY,"Document is too small to assess")
        val scale=512.0/maxOf(width,height)
        val w=maxOf(16,(width*scale).roundToInt()); val h=maxOf(16,(height*scale).roundToInt())
        val from=mats.keep(MatOfPoint2f(*p.toTypedArray()))
        val to=mats.keep(MatOfPoint2f(CvPoint(0.0,0.0),CvPoint((w-1).toDouble(),0.0),CvPoint((w-1).toDouble(),(h-1).toDouble()),CvPoint(0.0,(h-1).toDouble())))
        val transform=mats.keep(Imgproc.getPerspectiveTransform(from,to))
        if (!Core.checkRange(transform) || abs(Core.determinant(transform))<1e-10) fail(ErrorCode.INVALID_GEOMETRY,"Quality transform is singular")
        val document=mats.mat()
        Imgproc.warpPerspective(gray,document,transform,Size(w.toDouble(),h.toDouble()),Imgproc.INTER_LINEAR,Core.BORDER_REPLICATE)
        val marginX=maxOf(2,(w*.08).roundToInt()); val marginY=maxOf(2,(h*.08).roundToInt())
        val scores=ArrayList<Double>(9); val informative=ArrayList<Double>(9)
        var luminance=0.0
        val mean=mats.keep(MatOfDouble()); val deviation=mats.keep(MatOfDouble())
        for (row in 0..2) for (col in 0..2) {
            cancellation.check()
            val left=marginX+(w-2*marginX)*col/3; val right=marginX+(w-2*marginX)*(col+1)/3
            val top=marginY+(h-2*marginY)*row/3; val bottom=marginY+(h-2*marginY)*(row+1)/3
            val tile=document.submat(top,bottom,left,right); val laplacian=Mat(); val field=Mat(); val detailPixels=Mat()
            try {
                Core.meanStdDev(tile,mean,deviation)
                luminance+=mean.toArray()[0]/255.0
                val detail=deviation.toArray()[0]
                // Smooth illumination gradients are not focus evidence. Broad ink texture can
                // remain informative after moderate blur, unlike a sharpness-dependent test.
                Imgproc.GaussianBlur(tile,field,Size(0.0,0.0),10.0)
                Core.absdiff(tile,field,detailPixels)
                val localDetail=Core.mean(detailPixels).`val`[0]
                Imgproc.Laplacian(tile,laplacian,CvType.CV_64F)
                // Tile boundary reflection must not create artificial energy in the score.
                val interior=laplacian.submat(1,laplacian.rows()-1,1,laplacian.cols()-1)
                try { Core.meanStdDev(interior,mean,deviation) } finally { interior.release() }
                val score=deviation.toArray()[0].pow(2).coerceAtLeast(0.0)
                scores.add(score)
                if (detail>=8.0 && localDetail>=3.0) informative.add(score)
            } finally { tile.release(); laplacian.release(); field.release(); detailPixels.release() }
        }
        // Ignore blank tiles rather than treating blank margins as blur; one detailed tile can
        // describe a sparse receipt, while informativeTiles lets callers avoid blank-paper rejection.
        val selected=if(informative.isEmpty()) scores else informative
        val sorted=selected.sorted()
        val score=sorted[((sorted.size-1)*.75).roundToInt()]
        return DocumentQuality(score,(luminance/9).coerceIn(0.0,1.0),informative.size)
    }

    /** Candidate-only shading correction. Never changes the original or scene-quality measures. */
    private fun normalizeIllumination(gray: Mat,mats: Mats): Mat {
        val small=mats.mat(); val field=mats.mat(); val source=mats.mat(); val ratio=mats.mat()
        val output=mats.mat()
        try {
            val scale=minOf(1.0,160.0/maxOf(gray.cols(),gray.rows()))
            Imgproc.resize(gray,small,Size(maxOf(2.0,gray.cols()*scale),maxOf(2.0,gray.rows()*scale)),0.0,0.0,Imgproc.INTER_AREA)
            Imgproc.GaussianBlur(small,small,Size(0.0,0.0),10.0)
            Imgproc.resize(small,field,gray.size(),0.0,0.0,Imgproc.INTER_LINEAR)
            field.convertTo(field,CvType.CV_32F)
            // Bound amplification in near-black areas; darkness cannot create missing evidence.
            Core.max(field,Scalar(24.0),field)
            gray.convertTo(source,CvType.CV_32F)
            Core.divide(source,field,ratio,128.0)
            ratio.convertTo(output,CvType.CV_8U)
            Imgproc.GaussianBlur(output,output,Size(5.0,5.0),0.0)
            return output
        } finally { small.release(); field.release(); source.release(); ratio.release() }
    }

    /** Fit each observed straight side, excluding corner arcs, then intersect adjacent lines. */
    private fun refineSides(quad: Quad, boundary: Array<CvPoint>, width: Int, height: Int): Quad? {
        val p = quad.points.map { CvPoint(it.x*(width-1), it.y*(height-1)) }
        data class Line(val x: Double, val y: Double, val dx: Double, val dy: Double)
        val lines = p.indices.map { i ->
            val a=p[i]; val b=p[(i+1)%4]; val dx=b.x-a.x; val dy=b.y-a.y
            val length=hypot(dx,dy)
            if (length < 18) return null
            val nearby=boundary.filter { v ->
                val t=((v.x-a.x)*dx+(v.y-a.y)*dy)/(length*length)
                t in 0.08..0.92 && abs((v.x-a.x)*dy-(v.y-a.y)*dx)/length <= maxOf(3.0,length*0.012)
            }
            if (nearby.size < maxOf(10,(length*0.35).toInt())) return null
            val x=nearby.map { it.x }.average(); val y=nearby.map { it.y }.average()
            val xx=nearby.sumOf { (it.x-x).pow(2) }; val yy=nearby.sumOf { (it.y-y).pow(2) }
            val xy=nearby.sumOf { (it.x-x)*(it.y-y) }
            val angle=0.5*atan2(2*xy,xx-yy)
            val ux=cos(angle); val uy=sin(angle)
            val residual=sqrt(nearby.sumOf { ((it.x-x)*uy-(it.y-y)*ux).pow(2) }/nearby.size)
            if (residual > 2.5) return null
            Line(x,y,ux,uy)
        }
        val refined=p.indices.map { i ->
            val a=lines[(i+3)%4]; val b=lines[i]
            val determinant=a.dx*b.dy-a.dy*b.dx
            if (abs(determinant)<0.08) return null
            val t=((b.x-a.x)*b.dy-(b.y-a.y)*b.dx)/determinant
            val x=a.x+t*a.dx; val y=a.y+t*a.dy
            if (!x.isFinite() || !y.isFinite() || hypot(x-p[i].x,y-p[i].y)>12 ||
                x !in 0.0..(width-1).toDouble() || y !in 0.0..(height-1).toDouble()) return null
            Point(x/(width-1),y/(height-1))
        }
        return Quad(refined[0],refined[1],refined[2],refined[3])
    }

    /** Reject polygons whose sides are merely implied by approximation, ink or image borders. */
    private fun sideEvidence(quad: Quad,pixels: ByteArray,width: Int,height: Int,normalized: ByteArray? = null): Double? {
        fun sample(x: Double,y: Double,data: ByteArray=pixels): Int? {
            val ix=x.roundToInt(); val iy=y.roundToInt()
            return if (ix in 0 until width && iy in 0 until height) data[iy*width+ix].toInt() and 255 else null
        }
        val qualities=quad.points.indices.map { i ->
            val a=quad.points[i]; val b=quad.points[(i+1)%4]
            val ax=a.x*(width-1); val ay=a.y*(height-1)
            val dx=(b.x-a.x)*(width-1); val dy=(b.y-a.y)*(height-1); val length=hypot(dx,dy)
            val nx=-dy/length; val ny=dx/length
            val rawJumps=ArrayList<Double>(32)
            val contrasts=(0 until 32).map { index ->
                val t=0.10+0.80*(index+0.5)/32; val x=ax+t*dx; val y=ay+t*dy
                val inside=sample(x+nx*5,y+ny*5) ?: return null
                val outside=sample(x-nx*5,y-ny*5) ?: return null
                if (normalized==null) abs(inside-outside).toDouble() else {
                    val farInside=sample(x+nx*15,y+ny*15) ?: return null
                    val farOutside=sample(x-nx*15,y-ny*15) ?: return null
                    // Remove a local illumination slope using two outer intervals. A broad
                    // penumbra alone must not satisfy the contrast test for a sharp paper edge.
                    rawJumps.add(abs((inside-outside)-0.5*((farInside-inside)+(outside-farOutside))))
                    val correctedInside=sample(x+nx*5,y+ny*5,normalized) ?: return null
                    val correctedOutside=sample(x-nx*5,y-ny*5,normalized) ?: return null
                    abs(correctedInside-correctedOutside).toDouble()
                }
            }
            val coverage=contrasts.count { it>=4.0 }/32.0
            val strength=contrasts.sorted()[16]
            if (coverage<0.70 || strength<5.0) return null
            if (normalized==null) coverage*minOf(1.0,strength/20.0) else {
                val rawStrength=rawJumps.sorted()[16]
                if (rawJumps.count { it>=2.5 }/32.0 < 0.65 || rawStrength<2.5) return null
                // Confidence uses untouched-image edge strength; normalized gain earns no boost.
                coverage*minOf(1.0,rawStrength/20.0)
            }
        }
        return qualities.average()
    }

    private fun ordered(points: Array<CvPoint>, width: Int, height: Int): Quad {
        val cx = points.map { it.x }.average(); val cy = points.map { it.y }.average()
        val clockwise = points.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = clockwise.indices.minBy { clockwise[it].x + clockwise[it].y }
        val p = (0..3).map { clockwise[(start + it) % 4] }.map { Point((it.x / (width - 1)).coerceIn(0.0, 1.0), (it.y / (height - 1)).coerceIn(0.0, 1.0)) }
        return Quad(p[0], p[1], p[2], p[3])
    }

    private class Mats : AutoCloseable {
        private val all = ArrayList<Mat>()
        fun mat() = keep(Mat())
        fun <T : Mat> keep(mat: T): T {
            try { all.add(mat) } catch (failure: Throwable) { mat.release(); throw failure }
            return mat
        }
        override fun close() { all.asReversed().forEach { it.release() } }
    }

    private class CancellationSignal(val signal: Throwable) : RuntimeException(signal)

    private inline fun <T> nativeWork(cancellation: Cancellation, block: (Cancellation) -> T): T {
        // Preserve ANY exception raised by the caller's cancellation check, including IOException.
        val checked = Cancellation {
            try { cancellation.check() } catch (signal: Throwable) { throw CancellationSignal(signal) }
        }
        ensureInitialized()
        return try { block(checked) }
        catch (e: CancellationSignal) { throw e.signal }
        catch (e: CvException) { fail(ErrorCode.PROCESSING, "OpenCV could not process this image", e) }
        catch (e: OutOfMemoryError) { fail(ErrorCode.RESOURCE_LIMIT, "Not enough memory for this image", e) }
        catch (e: IOException) { fail(ErrorCode.STORAGE, "Image storage operation failed", e) }
    }

    companion object {
        private const val DETECT_EDGE = 960
        private const val RENDER_EDGE = 4096
        private const val RENDER_PIXELS = 6_000_000L
        private const val OUTPUT_PIXELS = 8_000_000.0
        @Volatile private var initialized = false
        @Synchronized private fun ensureInitialized() {
            if (!initialized) {
                val loaded = try { OpenCVLoader.initLocal() }
                catch (e: UnsatisfiedLinkError) { fail(ErrorCode.PROCESSING, "OpenCV native library is unavailable for this device", e) }
                if (!loaded) fail(ErrorCode.PROCESSING, "OpenCV native library could not be initialized")
                initialized = true
            }
        }
        private fun fail(code: ErrorCode, message: String, cause: Throwable? = null): Nothing = throw ScanException(ScanError(code, message), cause)
    }
}
