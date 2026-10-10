package dev.offlinescan.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.Debug
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.offlinescan.core.*
import dev.offlinescan.processing.OpenCvProcessor
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidExporterTest {
    @Test fun pdfKeepsConfiguredResolutionAsPageCountGrows() {
        val root=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"pdf-resolution-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source=File(root,"temp/source.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
            val processor=RecordingProcessor()
            val config=ScanConfig(maxOutputDimension=4096)
            val one=listOf(ScanPage("one",source,3000,4000,Edits()))
            val three=one + listOf(one[0].copy(id="two"),one[0].copy(id="three"))
            val exporter=AndroidExporter(processor)
            val single=exporter.export(one,File(root,"single"),ExportFormat.PDF,config)
            val multi=exporter.export(three,File(root,"multi"),ExportFormat.PDF,config)
            assertEquals(listOf(4096),processor.outputLimits.take(1))
            assertEquals(listOf(4096,4096,4096,4096),processor.outputLimits)
            assertEquals(single.pages.single(),multi.pages.first())
            assertEquals(3000,single.pages.single().width)
            assertEquals(4000,single.pages.single().height)
            listOf(single to 1,multi to 3).forEach { (output,count) ->
                ParcelFileDescriptor.open(output.files.single(),ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { pdf ->
                        assertEquals(count,pdf.pageCount)
                        assertEquals(count,output.pages.size)
                        for(index in 0 until count) pdf.openPage(index).use { page ->
                            val expected=output.pages[index]
                            assertEquals((expected.width*72/300.0).toInt(),page.width)
                            assertEquals((expected.height*72/300.0).toInt(),page.height)
                        }
                    }
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun failedPdfExportRemovesStagingAndKeepsExistingFiles() {
        val root=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"pdf-failure-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source=File(root,"temp/source.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
            val destination=File(root,"out").apply { mkdirs() }
            val retained=File(destination,"host.txt").apply { writeText("keep") }
            val processor=RecordingProcessor(failAt=2)
            val pages=(1..3).map { ScanPage("$it",source,3000,4000,Edits()) }
            try { AndroidExporter(processor).export(pages,destination,ExportFormat.PDF,ScanConfig()); fail("Expected export failure") }
            catch (e:ScanException) { assertEquals(ErrorCode.EXPORT,e.error.code) }
            assertEquals("keep",retained.readText())
            assertEquals(listOf("host.txt"),destination.listFiles()!!.map { it.name })
        } finally { root.deleteRecursively() }
    }

    @Test fun sameSyntheticPrintedPageMatchesJpegAndPdfAtRenderedImageResolution() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val evidenceRoot=requireNotNull(context.getExternalFilesDir("scanner-quality-evidence"))
        val run=File(evidenceRoot,"export-${System.currentTimeMillis()}").apply { mkdirs() }
        val originals=File(run,"originals").apply { mkdirs() }
        val source=File(originals,"synthetic-document.png")
        val width=1600
        val height=2200
        val input=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(input)
            canvas.drawColor(Color.WHITE)
            val heading=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=48f; typeface=Typeface.create("serif",Typeface.BOLD) }
            val text=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(35,35,35); textSize=29f; typeface=Typeface.create("serif",Typeface.NORMAL) }
            val faint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(176,176,176); textSize=27f; typeface=Typeface.create("serif",Typeface.NORMAL) }
            canvas.drawText("INVOICE  No. 2048-017",100f,125f,heading)
            val lines=listOf("Date: 2026-10-10     Ref: A-1042", "Subtotal         \$1,234.56", "Tax (10%)          \$123.46", "Total           \$1,358.02", "Received in full. Thank you!", "Signature:  M. Example")
            lines.forEachIndexed { index,line -> canvas.drawText(line,100f,220f+index*82f,text) }
            canvas.drawText("Pale note: verify item 7; amount may be adjusted.",100f,830f,faint)
            val rules=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(165,165,165); strokeWidth=2f }
            for(y in listOf(270,352,434,516,598,680,762)) canvas.drawLine(90f,y.toFloat(),1480f,y.toFloat(),rules)
            val stamp=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(175,45,55); style=Paint.Style.STROKE; strokeWidth=8f }
            canvas.drawCircle(1280f,1050f,105f,stamp)
            val stampText=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(175,45,55); textSize=30f; typeface=Typeface.DEFAULT_BOLD }
            canvas.drawText("PAID",1234f,1060f,stampText)
            source.outputStream().use { assertTrue(input.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { input.recycle() }

        val config=ScanConfig(mode=ScanMode.DOCUMENT,maxOutputDimension=2048,jpegQuality=95)
        val page=ScanPage("synthetic",source,width,height,Edits(preset=Preset.ORIGINAL))
        val processor=OpenCvProcessor()
        val exporter=AndroidExporter(processor)
        val jpg=exporter.export(listOf(page),File(run,"jpeg"),ExportFormat.JPEG,config,documentName="printed-document")
        val pdf=exporter.export(listOf(page),File(run,"pdf"),ExportFormat.PDF,config,documentName="printed-document")
        val jpegBitmap=BitmapFactory.decodeFile(jpg.files.single().path) ?: error("JPEG export was not readable")
        try {
            val size=pdf.pages.single()
            assertEquals(jpg.pages.single().width,size.width)
            assertEquals(jpg.pages.single().height,size.height)
            val pdfBitmap=Bitmap.createBitmap(size.width,size.height,Bitmap.Config.ARGB_8888)
            try {
                ParcelFileDescriptor.open(pdf.files.single(),ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        renderer.openPage(0).use { it.render(pdfBitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                    }
                }
                var absoluteError=0L
                var compared=0L
                for(y in 0 until size.height step 4) for(x in 0 until size.width step 4) {
                    val a=jpegBitmap.getPixel(x,y)
                    val b=pdfBitmap.getPixel(x,y)
                    absoluteError+=kotlin.math.abs(Color.red(a)-Color.red(b))
                    absoluteError+=kotlin.math.abs(Color.green(a)-Color.green(b))
                    absoluteError+=kotlin.math.abs(Color.blue(a)-Color.blue(b))
                    compared+=3
                }
                val meanAbsoluteError=absoluteError.toDouble()/compared
                assertTrue("JPEG/PDF mean pixel error was $meanAbsoluteError",meanAbsoluteError<=3.0)
                File(run,"comparison.txt").writeText("Source: ${source.absolutePath}\nJPEG: ${jpg.files.single().absolutePath}\nPDF: ${pdf.files.single().absolutePath}\nRendered pixels: ${size.width}x${size.height}\nPDF media box: ${(size.width*72/300.0).toInt()}x${(size.height*72/300.0).toInt()} points\nSampled RGB mean absolute error at image resolution: $meanAbsoluteError\n")
            } finally { pdfBitmap.recycle() }
        } finally { jpegBitmap.recycle() }
    }

    @Test fun manyHighResolutionPdfPagesKeepSampledNativeHeapBounded() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val evidenceRoot=requireNotNull(context.getExternalFilesDir("scanner-quality-evidence"))
        val run=File(evidenceRoot,"memory-${System.currentTimeMillis()}").apply { mkdirs() }
        val originals=File(run,"originals").apply { mkdirs() }
        val fixture=File(originals,"three-thousand-pixel-page.jpg")
        val bitmap=Bitmap.createBitmap(3000,3000,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val canvas=Canvas(bitmap)
            val text=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=48f }
            for(index in 0 until 30) canvas.drawText("Invoice line $index: 123.45",100f,150f+index*85f,text)
            fixture.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,92,it)) }
        } finally { bitmap.recycle() }

        val pageCount=12
        val pages=(0 until pageCount).map { ScanPage("page-$it",fixture,3000,3000,Edits()) }
        val samples=mutableListOf<Long>()
        val output=AndroidExporter(CopyJpegProcessor(fixture)).export(pages,File(run,"pdf"),ExportFormat.PDF,
            ScanConfig(maxOutputDimension=3072),progress=Progress { completed,_ ->
                if(completed>0) samples.add(Debug.getNativeHeapAllocatedSize())
            })
        assertEquals(pageCount,output.pageCount)
        assertEquals(pageCount,samples.size)
        val minSample=samples.minOrNull()!!
        val maxSample=samples.maxOrNull()!!
        val sampledGrowth=maxSample-minSample
        assertTrue("Sampled native heap grew by $sampledGrowth bytes across pages",sampledGrowth<96L*1024*1024)
        File(run,"memory.txt").writeText("PDF: ${output.files.single().absolutePath}\nPages: $pageCount\nRendered image size: 3000x3000 each\nNative heap sampled at page progress callbacks (bytes): ${samples.joinToString(",")}\nSampled range: $sampledGrowth bytes\nThis is progress-boundary sampling, not a continuous peak-memory measurement.\n")
    }

    @Test fun allFormatsProduceCorrectPageMetadataAndRemainAfterSessionClose() {
        val root=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"export-${System.nanoTime()}").apply { mkdirs() }
        try {
            val original=File(root,"source.png")
            val bitmap=Bitmap.createBitmap(480,320,Bitmap.Config.ARGB_8888)
            try { bitmap.eraseColor(Color.rgb(100,170,210)); original.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() }
            val processor=OpenCvProcessor(); val config=ScanConfig(mode=ScanMode.PHOTO,maxOutputDimension=1024)
            val outputs=mutableListOf<ScanOutput>()
            ScanSession(config,root,processor).use { session ->
                session.`import`(original); session.`import`(original)
                try { AndroidExporter(processor).export(session.pages,session.directory,ExportFormat.PNG,config); fail("Output inside session must be rejected") }
                catch(e:ScanException) { assertEquals(ErrorCode.STORAGE,e.error.code) }
                for(format in ExportFormat.entries) {
                    val progress=mutableListOf<Int>()
                    val output=AndroidExporter(processor).export(session.pages,File(root,"results"),format,config,progress=Progress { done,total -> assertEquals(2,total); progress.add(done) },documentName="Named scan")
                    assertEquals(if(format==ExportFormat.PDF) listOf("Named scan.pdf") else listOf("Named scan-1.${format.extension}","Named scan-2.${format.extension}"),output.files.map { it.name })
                    assertEquals(2,output.pageCount); assertEquals(listOf(0,1,2),progress); assertEquals(format.mimeType,output.mimeType)
                    if(format==ExportFormat.PDF) {
                        ParcelFileDescriptor.open(output.files.single(),ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                            PdfRenderer(descriptor).use { pdf ->
                                assertEquals(2,pdf.pageCount)
                                pdf.openPage(0).use { page -> assertEquals((output.pages[0].width*72/300.0).toInt(),page.width); assertEquals((output.pages[0].height*72/300.0).toInt(),page.height)
                                    val raster=Bitmap.createBitmap(page.width,page.height,Bitmap.Config.ARGB_8888)
                                    try { page.render(raster,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); assertPixelNear(Color.rgb(100,170,210),raster.getPixel(raster.width/2,raster.height/2),4) } finally { raster.recycle() }
                                }
                            }
                        }
                    } else output.files.forEachIndexed { i,file ->
                        val raster=BitmapFactory.decodeFile(file.path)
                        try { assertEquals(output.pages[i].width,raster.width); assertEquals(output.pages[i].height,raster.height) } finally { raster.recycle() }
                    }
                    outputs.add(output)
                }
            }
            assertTrue(original.exists()); assertTrue(outputs.flatMap { it.files }.all { it.exists() })
            assertFalse(File(root,"results").listFiles()!!.any { it.name.startsWith(".pending-") })
        } finally { root.deleteRecursively() }
    }
    @Test fun cancellationRollsBackOnlyOwnOutputs() {
        val root=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"cancel-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source=File(root,"source.png"); val bitmap=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888)
            try { source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() }
            val config=ScanConfig(); val processor=OpenCvProcessor(); val destination=File(root,"results").apply { mkdirs() }; val hostFile=File(destination,"host.txt").apply { writeText("keep") }
            ScanSession(config,root,processor).use { session ->
                session.`import`(source); session.`import`(source)
                for(format in ExportFormat.entries) {
                    var cancel=false
                    try { AndroidExporter(processor).export(session.pages,destination,format,config,Cancellation { if(cancel) throw CancellationException("cancel") },Progress { n,_ -> if(n==1) cancel=true }); fail("Expected cancellation") }
                    catch(_:CancellationException) { }
                    assertEquals(listOf("host.txt"),destination.listFiles()!!.map { it.name }); assertTrue(source.exists()); assertTrue(hostFile.exists())
                }
            }
        } finally { root.deleteRecursively() }
    }

    private class RecordingProcessor(private val failAt:Int?=null):ImageProcessor {
        val outputLimits=mutableListOf<Int>()
        private var rendered=0
        override fun inspect(source:File,config:ScanConfig)=3000 to 4000
        override fun detect(source:File,config:ScanConfig,cancellation:Cancellation)=Detection(null,0.0,0.0,0.0,emptyList())
        override fun render(source:File,edits:Edits,destination:File,format:ExportFormat,config:ScanConfig,cancellation:Cancellation):ExportedPage {
            cancellation.check()
            rendered++
            outputLimits.add(config.maxOutputDimension)
            if(rendered==failAt) throw ScanException(ScanError(ErrorCode.EXPORT,"synthetic failure"))
            val bitmap=Bitmap.createBitmap(3000,4000,Bitmap.Config.ARGB_8888)
            try { bitmap.eraseColor(Color.WHITE); destination.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,config.jpegQuality,it)) } }
            finally { bitmap.recycle() }
            return ExportedPage(3000,4000)
        }
    }

    private class CopyJpegProcessor(private val fixture:File):ImageProcessor {
        override fun inspect(source:File,config:ScanConfig)=3000 to 3000
        override fun detect(source:File,config:ScanConfig,cancellation:Cancellation)=Detection(null,0.0,0.0,0.0,emptyList())
        override fun render(source:File,edits:Edits,destination:File,format:ExportFormat,config:ScanConfig,cancellation:Cancellation):ExportedPage {
            cancellation.check()
            fixture.inputStream().use { input -> destination.outputStream().use { input.copyTo(it,64*1024) } }
            cancellation.check()
            return ExportedPage(3000,3000)
        }
    }

    private fun assertPixelNear(expected:Int,actual:Int,tolerance:Int) {
        assertTrue("red differs: $expected / $actual",kotlin.math.abs(Color.red(expected)-Color.red(actual))<=tolerance)
        assertTrue("green differs: $expected / $actual",kotlin.math.abs(Color.green(expected)-Color.green(actual))<=tolerance)
        assertTrue("blue differs: $expected / $actual",kotlin.math.abs(Color.blue(expected)-Color.blue(actual))<=tolerance)
    }
}
