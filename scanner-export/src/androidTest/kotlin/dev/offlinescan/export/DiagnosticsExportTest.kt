package dev.offlinescan.export

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import dev.offlinescan.core.*
import dev.offlinescan.processing.OpenCvProcessor
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsExportTest {
    @Test fun optInCopiesOriginalBytesAndPdfRasterSurviveSessionClose() {
        val root=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"diagnostics-${System.nanoTime()}").apply{mkdirs()}
        try {
            val source=File(root,"source.png");val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.CYAN);source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            val bytes=source.readBytes();val processor=OpenCvProcessor()
            lateinit var output:ScanOutput
            ScanSession(ScanConfig(),root,processor).use { session->
                session.`import`(source)
                val plain=AndroidExporter(processor).export(session.pages,File(root,"out"),ExportFormat.JPEG,ScanConfig())
                assertNull(plain.diagnostics);assertEquals(1,plain.files.first().parentFile!!.listFiles()!!.size)
                output=AndroidExporter(processor).export(session.pages,File(root,"out"),ExportFormat.PDF,ScanConfig(),includeDiagnostics=true)
                assertEquals(1,output.files.size)
            }
            val diagnostic=output.diagnostics!!
            assertArrayEquals(bytes,diagnostic.originals.single().readBytes())
            assertTrue(diagnostic.renderedPages.single().isFile)
            // Exact JPEG diagnostic stream is embedded in the PDF, without re-encoding.
            val pdf=output.files.single().readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(pdf.contains(diagnostic.renderedPages.single().readBytes().toString(Charsets.ISO_8859_1)))
            assertArrayEquals(bytes,source.readBytes())
        } finally {root.deleteRecursively()}
    }
    @Test fun cancellationDuringOriginalCopyLeavesNoDiagnosticFiles() {
        val root=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"diagnostic-cancel-${System.nanoTime()}").apply{mkdirs()}
        try {
            val source=File(root,"original/source.bin").apply{parentFile!!.mkdirs();writeBytes(ByteArray(200_000){17})}
            val dest=File(root,"out").apply{mkdirs()};val retained=File(dest,"host.txt").apply{writeText("keep")}
            var calls=0
            try {
                AndroidExporter(OpenCvProcessor()).export(listOf(ScanPage("one",source,100,100,Edits())),dest,ExportFormat.JPEG,ScanConfig(),
                    Cancellation{if(++calls==3)throw CancellationException()},includeDiagnostics=true)
                fail("Cancellation expected")
            } catch(_:CancellationException){}
            assertEquals(listOf(retained.name),dest.listFiles()!!.map{it.name});assertTrue(source.exists())
        } finally {root.deleteRecursively()}
    }
}
