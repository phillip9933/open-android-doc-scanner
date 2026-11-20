package dev.offlinescan.processing

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.offlinescan.core.*
import java.io.File
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardBoundaryTest {
    @Test fun tiltedCardIncludesBothTheDarkPanelAndTheLighterStock() {
        val image = Bitmap.createBitmap(900, 800, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(image)
            canvas.drawColor(Color.rgb(214,216,214))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.rgb(205,207,205)
            canvas.drawRect(32f,120f,475f,638f,paint)
            val projected = floatArrayOf(155f,239f,786f,316f,719f,578f,221f,518f)
            val plane = Matrix()
            check(plane.setPolyToPoly(floatArrayOf(0f,0f,1080f,0f,1080f,680f,0f,680f),0,projected,0,4))
            canvas.concat(plane)
            paint.color = Color.rgb(174,202,220)
            canvas.drawRect(0f,0f,1080f,680f,paint)
            paint.color = Color.rgb(42,86,129)
            canvas.drawRect(0f,0f,291f,680f,paint)
            paint.color = Color.BLACK
            paint.textSize = 48f
            canvas.drawText("MEMBER 001234",340f,450f,paint)
            val actual = requireNotNull(OpenCvProcessor().detectBitmap(image).corners)
            for ((i, corner) in actual.points.withIndex()) {
                val error = kotlin.math.hypot(corner.x*900-projected[i*2], corner.y*800-projected[i*2+1])
                assertTrue("Outer corner $i is ${error}px from the reference", error < 12)
            }
        } finally { image.recycle() }
    }

    @Test fun proposesOuterRoundedCardInsteadOfInternalNumberBox() {
        val root=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"cards-${System.nanoTime()}").apply {mkdirs()}
        try {
            for((small,lowContrast,angle) in listOf(Triple(false,false,0f),Triple(true,false,0f),Triple(true,false,12f),Triple(false,true,-9f))) {
                val bitmap=Bitmap.createBitmap(1200,900,Bitmap.Config.ARGB_8888)
                val left=if(small) 400f else 180f; val top=if(small) 300f else 180f
                val width=if(small) 400f else 840f; val height=width/1.586f
                Canvas(bitmap).apply {
                    drawColor(if(lowContrast) Color.rgb(60,140,120) else Color.rgb(65,65,65))
                    rotate(angle,left+width/2,top+height/2)
                    drawRoundRect(RectF(left,top,left+width,top+height),18f,18f,Paint().apply {color=Color.rgb(25,160,130)})
                    drawRect(left+width*.08f,top+height*.12f,left+width*.42f,top+height*.38f,Paint().apply {color=Color.WHITE})
                    drawRect(left+width*.08f,top+height*.58f,left+width*.92f,top+height*.85f,Paint().apply {color=Color.WHITE})
                }
                val image=File(root,"card-$small.png")
                image.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
                val result=OpenCvProcessor().detect(image,ScanConfig(),NeverCancelled)
                val quad=requireNotNull(result.corners) {"Missing rounded card, small=$small"}
                val expected=width*height/(1200.0*900)
                assertTrue("Should select full card, small=$small area=${Geometry.area(quad)}",Geometry.area(quad)>expected*.85)
                assertTrue("Should not select background",Geometry.area(quad)<expected*1.15)
                val output=File(root,"crop-$small.png")
                val rendered=OpenCvProcessor().render(image,Edits(corners=quad,preset=Preset.ORIGINAL),output,ExportFormat.PNG,ScanConfig(),NeverCancelled)
                assertTrue("Card aspect retained",rendered.width.toDouble()/rendered.height in 1.4..1.8)
            }
        } finally {root.deleteRecursively()}
    }
}
