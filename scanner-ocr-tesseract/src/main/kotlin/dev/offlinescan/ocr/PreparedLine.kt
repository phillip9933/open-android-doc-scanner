package dev.offlinescan.ocr

import android.graphics.*
import dev.offlinescan.core.*
import kotlin.math.*

/** Owns a temporary field raster and maps its boxes back to the original image. */
internal class PreparedLine(val bitmap: Bitmap, private val inverse: Matrix, private val sourceWidth: Int, private val sourceHeight: Int) {
    fun bounds(box: IntArray): IntArray {
        val points = floatArrayOf(box[0].toFloat(),box[1].toFloat(),box[2].toFloat(),box[1].toFloat(),
            box[2].toFloat(),box[3].toFloat(),box[0].toFloat(),box[3].toFloat())
        inverse.mapPoints(points)
        val xs = listOf(points[0],points[2],points[4],points[6])
        val ys = listOf(points[1],points[3],points[5],points[7])
        return intArrayOf(floor(xs.min()).toInt().coerceIn(0,sourceWidth),floor(ys.min()).toInt().coerceIn(0,sourceHeight),
            ceil(xs.max()).toInt().coerceIn(0,sourceWidth),ceil(ys.max()).toInt().coerceIn(0,sourceHeight))
    }
    companion object {
        fun create(source: Bitmap, config: OcrConfig, cancellation: Cancellation): PreparedLine {
            val r = requireNotNull(config.region)
            val left=floor(r.left*source.width).toInt(); val top=floor(r.top*source.height).toInt()
            val width=ceil(r.right*source.width).toInt()-left; val height=ceil(r.bottom*source.height).toInt()-top
            require(width.toLong()*height <= minOf(config.maxInputPixels,4_000_000L)) { "Selected OCR line exceeds preprocessing budget" }
            cancellation.check()
            val crop=Bitmap.createBitmap(source,left,top,width,height)
            try {
                val scale=minOf(1.0, 768.0/width, 256.0/height)
                val sample=Bitmap.createScaledBitmap(crop,maxOf(1,(width*scale).toInt()),maxOf(1,(height*scale).toInt()),true)
                val angle=try {
                    val pixels=IntArray(sample.width*sample.height)
                    sample.getPixels(pixels,0,sample.width,0,0,sample.width,sample.height)
                    LineSkew.estimate(sample.width,sample.height,BooleanArray(pixels.size) { i ->
                        val c=pixels[i]; (Color.red(c)*299+Color.green(c)*587+Color.blue(c)*114)/1000 < 160
                    }, cancellation)
                } finally { if(sample !== crop && sample !== source) sample.recycle() }
                val rotation=Matrix().apply { setRotate(angle,width/2f,height/2f) }
                val extent=RectF(0f,0f,width.toFloat(),height.toFloat())
                rotation.mapRect(extent)
                val padding=12
                val outWidth=ceil(extent.width()).toInt()+padding*2
                val outHeight=ceil(extent.height()).toInt()+padding*2
                // Additional work is bounded independently; original input validation still applies.
                require(outWidth <= config.maxInputDimension && outHeight <= config.maxInputDimension &&
                    outWidth.toLong()*outHeight <= minOf(config.maxInputPixels,4_000_000L)) { "Selected OCR line exceeds preprocessing budget" }
                rotation.postTranslate(padding-extent.left,padding-extent.top)
                val inverse=Matrix()
                check(rotation.invert(inverse))
                inverse.postTranslate(left.toFloat(),top.toFloat())
                cancellation.check()
                val output=Bitmap.createBitmap(outWidth,outHeight,Bitmap.Config.ARGB_8888)
                try {
                    Canvas(output).apply { drawColor(Color.WHITE); drawBitmap(crop,rotation,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) }
                    cancellation.check()
                    return PreparedLine(output,inverse,source.width,source.height)
                } catch(t: Throwable) { output.recycle(); throw t }
            } finally { if(crop !== source) crop.recycle() }
        }
    }
}
