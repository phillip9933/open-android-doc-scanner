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
