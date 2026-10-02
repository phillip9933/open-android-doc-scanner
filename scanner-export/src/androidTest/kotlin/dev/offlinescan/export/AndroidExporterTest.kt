package dev.offlinescan.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
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
                                pdf.openPage(0).use { page -> assertEquals(output.pages[0].width,page.width); assertEquals(output.pages[0].height,page.height)
                                    val raster=Bitmap.createBitmap(page.width,page.height,Bitmap.Config.ARGB_8888)
                                    try { page.render(raster,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); assertEquals(Color.rgb(100,170,210),raster.getPixel(200,150)) } finally { raster.recycle() }
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
}
