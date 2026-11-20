package dev.offlinescan.processing

import dev.offlinescan.core.*
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** A bounded, reversible recommendation. It classifies appearance, not document meaning. */
data class EnhancementRecommendation(val preset: Preset, val brightness: Double = 0.0,
    val contrast: Double = 1.0, val illumination: Double = 0.0, val paperCleanup: Double = 0.0)

internal object AutoEnhancement {
    fun recommend(rgb: Mat, cancellation: Cancellation): EnhancementRecommendation {
        val reduced=Mat(); val gray=Mat()
        try {
            cancellation.check()
            val scale=minOf(1.0,768.0/maxOf(rgb.cols(),rgb.rows()))
            Imgproc.resize(rgb,reduced,Size(maxOf(2.0,floor(rgb.cols()*scale)),maxOf(2.0,floor(rgb.rows()*scale))),0.0,0.0,Imgproc.INTER_AREA)
            Imgproc.cvtColor(reduced,gray,Imgproc.COLOR_RGB2GRAY)
            val w=gray.cols(); val h=gray.rows()
            val lum=ByteArray(w*h); gray.get(0,0,lum)
            val color=ByteArray(w*h*3); reduced.get(0,0,color)
            val histogram=IntArray(256)
            val tiles=Array(48) { IntArray(256) }
            val tileCounts=IntArray(48)
            val marginX=maxOf(1,w*3/100); val marginY=maxOf(1,h*3/100)
            var count=0; var colored=0; var saturated=0
            for(y in marginY until h-marginY) {
                if(y%32==0) cancellation.check()
                for(x in marginX until w-marginX) {
                    val index=y*w+x; val value=lum[index].toInt() and 255
                    histogram[value]++; count++
                    val tile=minOf(7,y*8/h)*6+minOf(5,x*6/w)
                    tiles[tile][value]++; tileCounts[tile]++
                    val r=color[index*3].toInt() and 255; val g=color[index*3+1].toInt() and 255; val b=color[index*3+2].toInt() and 255
                    val chroma = maxOf(r,g,b)-minOf(r,g,b)
                    if (chroma >= 18) colored++
                    if (chroma >= 64) saturated++
                }
            }
            if(count<64) return EnhancementRecommendation(Preset.ORIGINAL)
            fun quantile(hist:IntArray,n:Int,q:Double):Int {
                val target=maxOf(1,ceil(n*q).toInt()); var sum=0
                for(i in hist.indices) { sum+=hist[i]; if(sum>=target) return i }
                return 255
            }
            val paper=quantile(histogram,count,.90)
            // Local paper evidence tolerates a broad shadow without counting it as half a page of ink.
            val tilePaper=tiles.indices.map { quantile(tiles[it],tileCounts[it],.90) }
            var paperPixels=0
            var deepInkPixels=0
            for(tile in tiles.indices) {
                for (value in 0..(tilePaper[tile]-50)) deepInkPixels += tiles[tile][value]
                if(tilePaper[tile]>=100) paperPixels+=tiles[tile].sliceArray(maxOf(0,tilePaper[tile]-20)..255).sum()
            }
            val paperFraction=paperPixels.toDouble()/count
            val inkFraction=1.0-paperFraction
            // Without strong paper/content evidence, leave photos, blank pages and ambiguous scenes alone.
            if (paper < 145 || paperFraction < .65 || inkFraction !in .004.. .30) {
                // A dim grid can have clear repeated strokes in both directions even when its
                // paper never reaches the normal brightness threshold.
                if (paper in 85..155 && colored < count*.02 && hasRuledGrid(lum, w, h, cancellation))
                    return EnhancementRecommendation(Preset.GRAYSCALE, contrast = 1.04, paperCleanup = .85)
                return EnhancementRecommendation(Preset.ORIGINAL)
            }
            // When almost all local marks are faint, cleanup cannot reliably separate writing
            // from show-through. Retain the original tones with a modest contrast adjustment.
            if (deepInkPixels < count*.001)
                return EnhancementRecommendation(if (colored > count*.0005) Preset.COLOR_DOCUMENT else Preset.GRAYSCALE,
                    contrast = 1.08)
            // Broad colored stock plus a substantial saturated design is evidence of printed
            // color. Per-channel paper whitening would turn the stock white and distort the logo.
            if (colored > count * .55 && saturated > count * .08)
                return EnhancementRecommendation(Preset.COLOR_DOCUMENT)
            if(colored.toDouble()/count>=.0005)
                return EnhancementRecommendation(Preset.COLOR_DOCUMENT,0.0,1.0,0.0,.65)
            // Keep Auto continuous-tone for ordinary print. A clean histogram cannot tell
            // thin strokes, punctuation, pencil, or pale annotations from noise reliably;
            // callers can still choose the explicit Black and white preset.
            return EnhancementRecommendation(Preset.GRAYSCALE,0.0,1.04,0.0,.85)
        } finally { gray.release(); reduced.release() }
    }

    private fun hasRuledGrid(pixels: ByteArray, width: Int, height: Int, cancellation: Cancellation): Boolean {
        fun hasRepeatedLines(length: Int, across: Int, lineStride: Int, acrossStride: Int): Boolean {
            val offset = maxOf(2, across/120)
            var lines = 0
            var last = -across
            for (position in offset until across-offset) {
                if (position % 32 == 0) cancellation.check()
                if (position-last < maxOf(4, across/40)) continue
                var support = 0
                for (sample in 1..48) {
                    val index = position*acrossStride + (sample*length/50)*lineStride
                    val center = pixels[index].toInt() and 255
                    val above = pixels[index-offset*acrossStride].toInt() and 255
                    val below = pixels[index+offset*acrossStride].toInt() and 255
                    if (minOf(above, below) - center >= 15) support++
                }
                if (support >= 24) {
                    lines++
                    if (lines >= 4) return true
                    last = position
                }
            }
            return false
        }
        return hasRepeatedLines(width, height, 1, width) && hasRepeatedLines(height, width, width, 1)
    }

}
