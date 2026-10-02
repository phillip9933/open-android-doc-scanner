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
            var count=0; var colored=0
            for(y in marginY until h-marginY) {
                if(y%32==0) cancellation.check()
                for(x in marginX until w-marginX) {
                    val index=y*w+x; val value=lum[index].toInt() and 255
                    histogram[value]++; count++
                    val tile=minOf(7,y*8/h)*6+minOf(5,x*6/w)
                    tiles[tile][value]++; tileCounts[tile]++
                    val r=color[index*3].toInt() and 255; val g=color[index*3+1].toInt() and 255; val b=color[index*3+2].toInt() and 255
                    if(maxOf(r,g,b)-minOf(r,g,b)>=18) colored++
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
            for(tile in tiles.indices) {
                if(tilePaper[tile]>=100) paperPixels+=tiles[tile].sliceArray(maxOf(0,tilePaper[tile]-20)..255).sum()
            }
            val paperFraction=paperPixels.toDouble()/count
            val inkFraction=1.0-paperFraction
            // Without strong paper/content evidence, leave photos, blank pages and ambiguous scenes alone.
            if(paper<145 || paperFraction<.65 || inkFraction !in .004.. .30)
                return EnhancementRecommendation(Preset.ORIGINAL)
            val backgrounds=tiles.indices.filter { tileCounts[it]>=16 }.map { quantile(tiles[it],tileCounts[it],.90) }.sorted()
            val spread=if(backgrounds.size<8) 0 else backgrounds[backgrounds.size*9/10]-backgrounds[backgrounds.size/10]
            if(colored.toDouble()/count>=.0005)
                return EnhancementRecommendation(Preset.COLOR_DOCUMENT,0.0,1.0,0.0,.65)
            val middle=if(paper>125) histogram.sliceArray(96 until paper-25).sum().toDouble()/count else 1.0
            val dark=histogram.sliceArray(0..95).sum().toDouble()/count
            // Strict binary gate: shadows, gray ink/pencil and tonal photographs keep continuous tones.
            if(paper>=215 && paperFraction>=.75 && dark in .008.. .20 && middle<.001 && spread<15)
                return EnhancementRecommendation(Preset.BLACK_WHITE)
            return EnhancementRecommendation(Preset.GRAYSCALE,0.0,1.04,0.0,.85)
        } finally { gray.release(); reduced.release() }
    }
}
