package dev.offlinescan.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.offlinescan.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class DocumentQualityTest {
    private val processor=OpenCvProcessor()
    private val axis=Quad(Point(.18,.16),Point(.82,.16),Point(.82,.84),Point(.18,.84))
    private val angled=Quad(Point(.26,.12),Point(.86,.26),Point(.75,.89),Point(.12,.71))

    @Test fun sharpBackgroundCannotHideDocumentBlurInAxisOrPerspectiveView() {
        for (quad in listOf(axis,angled)) {
            val sharp=scene(quad,0.0); val blurred=scene(quad,4.0)
            try {
                val good=processor.assessQualityBitmap(sharp,quad)
                val bad=processor.assessQualityBitmap(blurred,quad)
                assertTrue("Sharp text should support the existing 60 threshold: $good",good.sharpness>60)
                assertTrue("Local blur should remain below 60 despite checkerboard background: $bad",bad.sharpness<60)
                assertTrue("Sharp/blur ordering must survive perspective",good.sharpness>bad.sharpness*5)
                assertTrue(good.informativeTiles>=2); assertTrue(bad.informativeTiles>=2)
                val learnedMetrics=processor.measureDetectionBitmap(blurred,quad,.9)
                assertEquals(bad.sharpness,learnedMetrics.sharpness,.0001)
                assertEquals(bad.brightness,learnedMetrics.brightness,.0001)
                assertEquals(bad.informativeTiles,learnedMetrics.documentDetailTiles)
                assertEquals(64,learnedMetrics.sceneSignature.size)
                assertFalse(sharp.isRecycled); assertFalse(blurred.isRecycled)
                android.util.Log.i("DocumentQualityFixture","quad=$quad sharp=$good blurred=$bad")
            } finally { sharp.recycle(); blurred.recycle() }
        }
    }

    @Test fun blankPaperAndHighContrastBorderAreNotFocusEvidence() {
        for(shadow in listOf(false,true)) {
            val blank=scene(axis,0.0,blank=true,shadow=shadow)
            try {
                val quality=processor.assessQualityBitmap(blank,axis)
                assertEquals("Smooth shadows are not ink texture",0,quality.informativeTiles)
                assertTrue("Blank interior remains low energy",quality.sharpness<2.0)
                assertEquals(if(shadow) 221.5/255 else 238.0/255,quality.brightness,.01)
            } finally { blank.recycle() }
        }
    }

    @Test fun lowContrastInkStillDistinguishesModerateBlurFromSharpText() {
        val sharp=scene(angled,0.0,inkShade=180)
        val blurred=scene(angled,1.5,inkShade=180)
        try {
            val reference=processor.assessQualityBitmap(sharp,angled)
            val candidate=processor.assessQualityBitmap(blurred,angled)
            assertTrue("Low-contrast sharp print still supports capture: $reference",reference.sharpness>60)
            assertTrue("Moderate blur remains visible despite sharp background: $candidate",candidate.sharpness<60)
            assertTrue(reference.informativeTiles>=2); assertTrue(candidate.informativeTiles>=2)
            assertTrue("A still-quality ratio can detect meaningful degradation",candidate.sharpness<reference.sharpness*.70)
            android.util.Log.i("DocumentQualityFixture","low-contrast sharp=$reference moderate-blur=$candidate")
        } finally { sharp.recycle(); blurred.recycle() }
    }

    @Test fun bitmapAndFileUseSameMetricAndUprightExifCorners() {
        val directory=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"quality-${System.nanoTime()}").apply { mkdirs() }
        val bitmap=scene(axis,0.0)
        try {
            val png=File(directory,"quality.png")
            png.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
            val expected=processor.assessQualityBitmap(bitmap,axis)
            val actual=processor.assessQuality(png,axis,ScanConfig())
            assertEquals(expected.sharpness,actual.sharpness,.01)
            assertEquals(expected.informativeTiles,actual.informativeTiles)
            try { processor.assessQuality(png,axis,ScanConfig(maxInputPixels=100)); fail("Expected metadata input limit") }
            catch(error:ScanException) { assertEquals(ErrorCode.RESOURCE_LIMIT,error.error.code) }
            val jpeg=File(directory,"orientation.jpg")
            jpeg.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,96,it)) }
            ExifInterface(jpeg).apply { setAttribute(ExifInterface.TAG_ORIENTATION,"6"); saveAttributes() }
            val decoded=BitmapFactory.decodeFile(jpeg.absolutePath)!!
            val upright=Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,Matrix().apply { postRotate(90f) },false)
            val rotated=Quad(Point(1-axis.bottomLeft.y,axis.bottomLeft.x),Point(1-axis.topLeft.y,axis.topLeft.x),Point(1-axis.topRight.y,axis.topRight.x),Point(1-axis.bottomRight.y,axis.bottomRight.x))
            try {
                val fileQuality=processor.assessQuality(jpeg,rotated,ScanConfig())
                val bitmapQuality=processor.assessQualityBitmap(upright,rotated)
                assertEquals(bitmapQuality.sharpness,fileQuality.sharpness,.01)
                assertEquals(bitmapQuality.brightness,fileQuality.brightness,.0001)
            } finally { upright.recycle(); decoded.recycle() }
        } finally { bitmap.recycle(); directory.deleteRecursively() }
    }

    @Test fun cancellationIsPreservedAndCallerBitmapSurvives() {
        val bitmap=scene(axis,0.0)
        val signal=CancellationException("quality cancelled")
        try {
            try { processor.assessQualityBitmap(bitmap,axis,Cancellation { throw signal }); fail("Expected cancellation") }
            catch(error:CancellationException) { assertSame(signal,error) }
            assertFalse(bitmap.isRecycled)
        } finally { bitmap.recycle() }
    }

    private fun scene(quad:Quad,blurSigma:Double,blank:Boolean=false,shadow:Boolean=false,inkShade:Int=25):Bitmap {
        // Initialize native code before the fixture's own Gaussian blur call.
        val seed=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
        try { processor.assessQualityBitmap(seed,Quad.FULL) } finally { seed.recycle() }
        val page=Bitmap.createBitmap(512,384,Bitmap.Config.ARGB_8888)
        page.eraseColor(Color.rgb(238,238,238))
        val ink=Paint().apply { color=Color.rgb(inkShade,inkShade,inkShade); isAntiAlias=false }
        if(shadow) {
            val canvas=Canvas(page)
            for(y in 0 until 384) {
                val shade=205+33*y/383; ink.color=Color.rgb(shade,shade,shade)
                canvas.drawRect(0f,y.toFloat(),512f,(y+1).toFloat(),ink)
            }
            ink.color=Color.rgb(inkShade,inkShade,inkShade)
        }
        if(!blank) {
            val canvas=Canvas(page)
            for(y in 28 until 356 step 22) for(x in 24 until 480 step 32) canvas.drawRect(x.toFloat(),y.toFloat(),(x+20).toFloat(),(y+5).toFloat(),ink)
        }
        if(blurSigma>0) {
            val mat=Mat()
            try { Utils.bitmapToMat(page,mat); Imgproc.GaussianBlur(mat,mat,Size(0.0,0.0),blurSigma); Utils.matToBitmap(mat,page) }
            finally { mat.release() }
        }
        val result=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(result)
        for(y in 0 until 600 step 8) for(x in 0 until 800 step 8) {
            ink.color=if((x/8+y/8)%2==0) Color.BLACK else Color.WHITE
            canvas.drawRect(x.toFloat(),y.toFloat(),(x+8).toFloat(),(y+8).toFloat(),ink)
        }
        val transform=Matrix()
        val source=floatArrayOf(0f,0f,511f,0f,511f,383f,0f,383f)
        val target=quad.points.flatMap { listOf((it.x*799).toFloat(),(it.y*599).toFloat()) }.toFloatArray()
        check(transform.setPolyToPoly(source,0,target,0,4))
        canvas.drawBitmap(page,transform,Paint().apply { isFilterBitmap=true })
        page.recycle()
        return result
    }
}
