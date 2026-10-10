package dev.offlinescan.sample

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticImageGeometryTest {
    @Test fun exifOrientationsMapCropBoundsAndDimensions() {
        val rawWidth=4
        val rawHeight=3
        for(orientation in 1..8) {
            val (uprightWidth,uprightHeight)=DiagnosticImageGeometry.uprightSize(rawWidth,rawHeight,orientation)
            val full=DiagnosticImageGeometry.rawBoundsForUprightCrop(Rect(0,0,uprightWidth,uprightHeight),rawWidth,rawHeight,orientation)
            assertEquals(Rect(0,0,rawWidth,rawHeight),full)
        }
        for(orientation in 1..4) assertEquals(4 to 3,DiagnosticImageGeometry.uprightSize(rawWidth,rawHeight,orientation))
        for(orientation in 5..8) assertEquals(3 to 4,DiagnosticImageGeometry.uprightSize(rawWidth,rawHeight,orientation))

        val expectedTopLeftRaw=listOf(0 to 0,3 to 0,3 to 2,0 to 2,0 to 0,0 to 2,3 to 2,3 to 0)
        for(orientation in 1..8) {
            val rect=DiagnosticImageGeometry.rawBoundsForUprightCrop(Rect(0,0,1,1),rawWidth,rawHeight,orientation)
            val (x,y)=expectedTopLeftRaw[orientation-1]
            assertEquals(Rect(x,y,x+1,y+1),rect)
        }
    }

    @Test fun transformedBitmapPlacesAllEightExifOrientationsUpright() {
        val rawWidth=4
        val rawHeight=3
        for(orientation in 1..8) {
            val source=Bitmap.createBitmap(rawWidth,rawHeight,Bitmap.Config.ARGB_8888)
            val colors=Array(rawHeight) { y -> IntArray(rawWidth) { x -> Color.rgb(20+x*40,30+y*60,90+x+y) } }
            for(y in 0 until rawHeight) for(x in 0 until rawWidth) source.setPixel(x,y,colors[y][x])
            val upright=DiagnosticImageGeometry.orient(source,orientation)
            val (width,height)=DiagnosticImageGeometry.uprightSize(rawWidth,rawHeight,orientation)
            try {
                assertEquals(width,upright.width)
                assertEquals(height,upright.height)
                for(y in 0 until rawHeight) for(x in 0 until rawWidth) {
                    val (u,v)=when(orientation) {
                        2 -> rawWidth-1-x to y
                        3 -> rawWidth-1-x to rawHeight-1-y
                        4 -> x to rawHeight-1-y
                        5 -> y to x
                        6 -> rawHeight-1-y to x
                        7 -> rawHeight-1-y to rawWidth-1-x
                        8 -> y to rawWidth-1-x
                        else -> x to y
                    }
                    assertEquals("orientation=$orientation raw=($x,$y) upright=($u,$v)",colors[y][x],upright.getPixel(u,v))
                }
            } finally { upright.recycle(); if(upright!==source) source.recycle() }
        }
    }
}
