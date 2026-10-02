package dev.offlinescan.processing

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.offlinescan.core.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class) class AutoEnhancementTest {
    private val processor=OpenCvProcessor()
    private lateinit var directory:File
    @Before fun setup() { directory=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"enhance-${System.nanoTime()}").apply{mkdirs()} }
    @After fun cleanup() { directory.deleteRecursively() }
    private fun fixture(name:String,paper:Int=215,ink:Int=65,shadow:Boolean=false,stamp:Boolean=false):File {
        val image=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image);canvas.drawColor(Color.rgb(paper,paper,paper))
        if(shadow) canvas.drawRect(0f,0f,360f,1000f,Paint().apply{color=Color.rgb(150,150,150)})
        val paint=Paint().apply{color=Color.rgb(ink,ink,ink)}
        for(y in 160..820 step 60) for(x in 100..650 step 70) canvas.drawRect(x.toFloat(),y.toFloat(),x+42f,y+10f,paint)
        if(stamp) canvas.drawRect(610f,80f,645f,115f,Paint().apply{color=Color.rgb(150,40,30)})
        return File(directory,name).also{file->file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
    }
    private fun render(file:File,preset:Preset=Preset.AUTO,edge:Int=1200):Bitmap {
        val out=File(directory,"out-${System.nanoTime()}.png")
        processor.render(file,Edits(preset=preset),out,ExportFormat.PNG,ScanConfig(maxOutputDimension=edge))
        return BitmapFactory.decodeFile(out.absolutePath)!!
    }
    private fun luma(bitmap:Bitmap,x:Double,y:Double)=Color.red(bitmap.getPixel((x*(bitmap.width-1)).toInt(),(y*(bitmap.height-1)).toInt()))
    @Test fun broadShadowIsLiftedWithoutErasingDarkTextOrChangingOriginal() {
        val file=fixture("shadow.png",shadow=true)
        val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        assertEquals(Preset.GRAYSCALE,processor.recommendEnhancement(file,Quad.FULL,ScanConfig()).preset)
        val original=render(file,Preset.ORIGINAL);val enhanced=render(file)
        try {
            val before=luma(original,.15,.12);val after=luma(enhanced,.15,.12)
            assertTrue("Shaded paper must visibly brighten: $before -> $after",after-before>=35)
            assertTrue("Ink stays separated from paper",after-luma(enhanced,.14,.164)>90)
            assertTrue("Lighting variation shrinks",luma(enhanced,.8,.12)-after < luma(original,.8,.12)-before)
            assertArrayEquals(hash,MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
            val external=InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
            File(external,"rc10-shadow-original.png").outputStream().use{original.compress(Bitmap.CompressFormat.PNG,100,it)}
            File(external,"rc10-shadow-auto.png").outputStream().use{enhanced.compress(Bitmap.CompressFormat.PNG,100,it)}
            File(external,"rc10-enhancement-values.json").writeText("""{"shadowPaperBefore":$before,"shadowPaperAfter":$after,"shadowInkAfter":${luma(enhanced,.14,.164)},"litPaperAfter":${luma(enhanced,.8,.12)}}""")
        } finally {original.recycle();enhanced.recycle()}
    }
    @Test fun smallColourStampKeepsColourAndFaintWritingKeepsContinuousTones() {
        val color=fixture("stamp.png",stamp=true)
        assertEquals(Preset.COLOR_DOCUMENT,processor.recommendEnhancement(color,Quad.FULL,ScanConfig()).preset)
        val image=render(color)
        try {val p=image.getPixel(image.width*625/800,image.height*95/1000);assertTrue(Color.red(p)-Color.green(p)>70)} finally{image.recycle()}
        val faint=fixture("faint.png",paper=225,ink=165)
        assertEquals(Preset.GRAYSCALE,processor.recommendEnhancement(faint,Quad.FULL,ScanConfig()).preset)
        val result=render(faint)
        try {assertTrue(luma(result,.14,.164) in 130..210);assertTrue(luma(result,.15,.12)-luma(result,.14,.164)>35)}finally{result.recycle()}
    }
    @Test fun cleanHighContrastPageUsesBinaryWithoutHollowingSolidInk() {
        val file=fixture("clean.png",paper=245,ink=10)
        assertEquals(Preset.BLACK_WHITE,processor.recommendEnhancement(file,Quad.FULL,ScanConfig()).preset)
        val image=render(file)
        try {assertEquals(0,luma(image,.14,.164));assertEquals(255,luma(image,.15,.12))}finally{image.recycle()}
    }
    @Test fun continuousTonePhotoAndBlankPageArePreserved() {
        val image=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888)
        for(y in 0 until 1000) for(x in 0 until 800) image.setPixel(x,y,Color.rgb(x*255/799,y*255/999,(x+y)*255/1798))
        val photo=File(directory,"photo.png");photo.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.eraseColor(Color.rgb(205,205,205));val blank=File(directory,"blank.png");blank.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        for(file in listOf(photo,blank)) {
            assertEquals(Preset.ORIGINAL,processor.recommendEnhancement(file,Quad.FULL,ScanConfig()).preset)
            val before=render(file,Preset.ORIGINAL);val after=render(file)
            try {assertTrue(before.sameAs(after))}finally{before.recycle();after.recycle()}
        }
    }
    @Test fun previewAndExportChooseSameFilterAndCancellationLeavesNoOutput() {
        val file=fixture("bounded.png",shadow=true,stamp=true)
        val small=render(file,edge=512);val large=render(file,edge=1800)
        try {assertEquals(luma(small,.15,.12).toDouble(),luma(large,.15,.12).toDouble(),4.0)}finally{small.recycle();large.recycle()}
        val output=File(directory,"cancelled.png")
        try {processor.render(file,Edits(preset=Preset.AUTO),output,ExportFormat.PNG,ScanConfig(),Cancellation{throw CancellationException()});fail("Expected cancellation")}
        catch(_:CancellationException){assertFalse(output.exists())}
    }
}

