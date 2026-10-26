package dev.offlinescan.ocr

import dev.offlinescan.core.*
import java.util.concurrent.CancellationException
import kotlin.math.*
import kotlin.test.*

class LineSkewTest {
    @Test fun estimatesBothDirectionsAndLeavesBlankRegionsAlone() {
        val width=360; val height=100
        for(angle in listOf(-7,0,7)) {
            val dark=BooleanArray(width*height)
            for(x in 30..330) for(offset in -3..3) {
                val y=(50+tan(Math.toRadians(angle.toDouble()))*(x-180)).roundToInt()+offset
                if(y in 0 until height) dark[y*width+x]=true
            }
            val result=LineSkew.estimate(width,height,dark,NeverCancelled)
            assertTrue(abs(result+angle)<=1, "angle=$angle result=$result")
        }
        assertEquals(0f,LineSkew.estimate(width,height,BooleanArray(width*height),NeverCancelled))
        assertEquals(0f,LineSkew.estimate(width,height,BooleanArray(width*height) {true},NeverCancelled))
    }
    @Test fun projectionSearchHonorsCancellation() {
        val dark=BooleanArray(100*30) {it/100==15}
        assertFailsWith<CancellationException> {
            LineSkew.estimate(100,30,dark,Cancellation {throw CancellationException()})
        }
    }
}
