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
            val beforeInk=luma(original,.14,.164);val afterInk=luma(enhanced,.14,.164)
            assertTrue("Cleanup preserves at least 95% of the source ink contrast",
                after-afterInk >= (before-beforeInk)*.95)
            assertTrue("Lighting variation shrinks",luma(enhanced,.8,.12)-after < luma(original,.8,.12)-before)
            assertArrayEquals(hash,MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
            val external=InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
            File(external,"rc10-shadow-original.png").outputStream().use{original.compress(Bitmap.CompressFormat.PNG,100,it)}
            File(external,"rc10-shadow-auto.png").outputStream().use{enhanced.compress(Bitmap.CompressFormat.PNG,100,it)}
            File(external,"rc10-enhancement-values.json").writeText("""{"shadowPaperBefore":$before,"shadowPaperAfter":$after,"shadowInkAfter":${luma(enhanced,.14,.164)},"litPaperAfter":${luma(enhanced,.8,.12)}}""")
        } finally {original.recycle();enhanced.recycle()}
    }
    @Test fun backgroundCleanupFlattensShadowsAndSuppressesWeakShowThrough() {
        val file=fixture("bleed.png",paper=225,ink=80,shadow=true)
        val source=BitmapFactory.decodeFile(file.absolutePath).copy(Bitmap.Config.ARGB_8888,true)
        val canvas=Canvas(source)
        // Weak reverse-side print, genuine pale ink and a small pale blue annotation.
        canvas.drawRect(450f,90f,490f,100f,Paint().apply{color=Color.rgb(213,213,213)})
        canvas.drawRect(530f,90f,570f,100f,Paint().apply{color=Color.rgb(175,175,175)})
        canvas.drawRect(610f,90f,650f,100f,Paint().apply{color=Color.rgb(170,190,220)})
        file.outputStream().use{source.compress(Bitmap.CompressFormat.PNG,100,it)};source.recycle()
        val result=render(file)
        try {
            val paper=luma(result,.6,.12)
            val bleed=luma(result,470.0/799,95.0/999)
            val faint=luma(result,550.0/799,95.0/999)
            assertTrue("Paper should approach white: $paper",paper>=248)
            assertTrue("Broad shadow should approach lit paper",paper-luma(result,.15,.12)<15)
            assertTrue("Weak bleed contrast should fall below source 12 levels: ${paper-bleed}",paper-bleed<10)
            assertTrue("Genuine pale ink must retain contrast",paper-faint>=40)
            val blue=result.getPixel(630*result.width/800,95*result.height/1000)
            assertTrue("Pale colored annotation stays colored",Color.blue(blue)-Color.red(blue)>=40)
        } finally {result.recycle()}
    }
    @Test fun cyanPaperBecomesNeutralWithoutWashingOutInkOrRedMarks() {
        val bitmap=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.rgb(195,225,235))
        val ink=Paint().apply{color=Color.rgb(60,69,72)}
        for(y in 160..820 step 60) for(x in 100..650 step 70)
            canvas.drawRect(x.toFloat(),y.toFloat(),x+42f,y+10f,ink)
        canvas.drawRect(600f,80f,640f,110f,Paint().apply{color=Color.rgb(190,140,145)})
        val source=File(directory,"cyan.png")
        source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val result=render(source)
        try {
            val paper=result.getPixel(400,100)
            val channels=listOf(Color.red(paper),Color.green(paper),Color.blue(paper))
            assertTrue("Paper cast removed: $channels",channels.maxOrNull()!!-channels.minOrNull()!!<=5)
            assertTrue("Paper remains near white",channels.minOrNull()!!>=248)
            assertTrue("Ink stays dark instead of receiving the paper lift",luma(result,.14,.164)<80)
            val red=result.getPixel(620,95)
            assertTrue("Red annotation remains red",Color.red(red)-Color.green(red)>35)
        } finally {result.recycle()}
    }
    @Test fun edgeShadowBroadStainAndReverseLinesAreReducedWithoutLosingPencil() {
        val file=fixture("background-regions.png",paper=235,ink=65)
        val image=BitmapFactory.decodeFile(file.absolutePath).copy(Bitmap.Config.ARGB_8888,true)
        val canvas=Canvas(image)
        // Broad colored stain and shadow; small ink features must not define the paper field.
        canvas.drawCircle(430f,105f,48f,Paint().apply{color=Color.rgb(225,222,202)})
        for(y in 0 until 65) {
            val v=190+y*45/65
            canvas.drawLine(0f,y.toFloat(),800f,y.toFloat(),Paint().apply{color=Color.rgb(v,v,v)})
        }
        canvas.drawRect(500f,95f,575f,99f,Paint().apply{color=Color.rgb(217,217,217)})
        canvas.drawRect(600f,95f,675f,99f,Paint().apply{color=Color.rgb(185,185,185)})
        file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        val result=render(file)
        try {
            val stain=result.getPixel(430,105)
            val rgb=listOf(Color.red(stain),Color.green(stain),Color.blue(stain))
            assertTrue("Broad yellow stain should neutralize: $rgb",rgb.maxOrNull()!!-rgb.minOrNull()!!<12)
            assertTrue("Top shadow should approach white",luma(result,.4,.01)>235)
            val background=luma(result,.68,.12)
            val reverse=luma(result,540.0/799,97.0/999)
            val pencil=luma(result,640.0/799,97.0/999)
            assertTrue("Reverse line contrast reduced: ${background-reverse}",background-reverse<15)
            assertTrue("Pencil stays distinct",background-pencil>35)
            assertTrue("Dark print preserved",luma(result,.14,.164)<85)
        } finally {result.recycle()}
    }
    @Test fun smallColourStampKeepsColourAndFaintWritingKeepsContinuousTones() {
        val color=fixture("stamp.png",stamp=true)
        assertEquals(Preset.COLOR_DOCUMENT,processor.recommendEnhancement(color,Quad.FULL,ScanConfig()).preset)
        val image=render(color)
        try {val p=image.getPixel(image.width*625/800,image.height*95/1000);assertTrue(Color.red(p)-Color.green(p)>70)} finally{image.recycle()}
        val faint=fixture("faint.png",paper=225,ink=165)
        assertEquals(Preset.GRAYSCALE,processor.recommendEnhancement(faint,Quad.FULL,ScanConfig()).preset)
        val result=render(faint)
        try {
            assertTrue("Pale ink remains below white after cleanup",luma(result,.14,.164) in 130..210)
            assertTrue("Pale ink remains separated from paper",luma(result,.15,.12)-luma(result,.14,.164)>35)
        } finally {result.recycle()}
    }
    @Test fun autoKeepsCleanPrintContinuousAndManualBlackWhiteKeepsSolidInk() {
        val file=fixture("clean.png",paper=245,ink=10)
        assertEquals(Preset.GRAYSCALE,processor.recommendEnhancement(file,Quad.FULL,ScanConfig()).preset)
        val automatic=render(file)
        val manual=render(file,Preset.BLACK_WHITE)
        try {
            assertTrue("Auto keeps tonal detail",luma(automatic,.14,.164) in 0..80)
            assertEquals(0,luma(manual,.14,.164));assertEquals(255,luma(manual,.15,.12))
        } finally {automatic.recycle();manual.recycle()}
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
