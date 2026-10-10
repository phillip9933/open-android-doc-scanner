package dev.offlinescan.sample

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.offlinescan.core.Cancellation
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanMediaPublisherTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun publishesImageAndPdfWithExpectedCollectionMetadataAndExactBytes() {
        assumeTrue("MediaStore relative paths require Android 10+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val imageBytes=byteArrayOf(0xFF.toByte(),0xD8.toByte(),1,2,3,0xFF.toByte(),0xD9.toByte())
        val pdfBytes="%PDF-1.4\nsynthetic\n%%EOF\n".toByteArray()
        val imageName="publisher-${System.nanoTime()}.jpg"
        val pdfName="publisher-${System.nanoTime()}.pdf"
        val published=mutableListOf<Pair<Uri,ByteArray>>()
        try {
            val image=publishBytes(imageBytes,"image/jpeg",imageName)
            published.add(image.uri to imageBytes)
            val pdf=publishBytes(pdfBytes,"application/pdf",pdfName)
            published.add(pdf.uri to pdfBytes)
            assertEquals(imageBytes.size.toLong(),image.sizeBytes)
            assertEquals(pdfBytes.size.toLong(),pdf.sizeBytes)
            assertRow(image.uri,imageName,"image/jpeg","Pictures/Offline Scanner",imageBytes)
            assertRow(pdf.uri,pdfName,"application/pdf","Download/Offline Scanner",pdfBytes)
        } finally {
            published.forEach { (uri,_) -> context.contentResolver.delete(uri,null,null) }
        }
    }

    @Test fun partialStreamFailureDeletesOnlyItsOwnPendingRow() {
        assumeTrue("MediaStore relative paths require Android 10+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val name="publisher-failure-${System.nanoTime()}.png"
        val partial=byteArrayOf(1,2,3,4)
        val input=object:InputStream() {
            private var sent=false
            override fun read():Int = throw UnsupportedOperationException()
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                if(!sent) { sent=true; partial.copyInto(buffer,offset); return partial.size }
                throw IOException("synthetic source failure")
            }
        }
        try {
            ScanMediaPublisher.publishFrom(context,-1,"image/png",name,openInput={input})
            fail("Expected source read failure")
        } catch (error:IOException) {
            assertEquals("synthetic source failure",error.message)
        }
        assertNoRow(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,name)
    }

    @Test fun cancellationDuringCopyDeletesItsOwnPendingRow() {
        assumeTrue("MediaStore relative paths require Android 10+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val name="publisher-cancel-${System.nanoTime()}.jpg"
        var delivered=false
        val cancellation=Cancellation { if(delivered) throw CancellationException("synthetic cancellation") }
        val input=object:ByteArrayInputStream(ByteArray(96*1024) { 7 }) {
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                val count=super.read(buffer,offset,length)
                if(count>0) delivered=true
                return count
            }
        }
        try {
            ScanMediaPublisher.publishFrom(context,-1,"image/jpeg",name,cancellation) { input }
            fail("Expected cancellation")
        } catch (_:CancellationException) { }
        assertNoRow(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,name)
    }

    private fun publishBytes(bytes:ByteArray,mime:String,name:String):PublishedScan {
        val source=File(context.cacheDir,"${System.nanoTime()}-${name}")
        source.writeBytes(bytes)
        try { return ScanMediaPublisher.publish(context,source,mime,name) }
        finally { source.delete() }
    }

    private fun assertRow(uri:Uri,name:String,mime:String,path:String,bytes:ByteArray) {
        val projection=arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.SIZE)
        context.contentResolver.query(uri,projection,null,null,null).use { cursor ->
            assertNotNull(cursor)
            assertTrue(cursor!!.moveToFirst())
            assertEquals(name,cursor.getString(0))
            assertEquals(mime,cursor.getString(1))
            assertEquals(path,cursor.getString(2)?.trimEnd('/'))
            assertEquals(0,cursor.getInt(3))
            assertEquals(bytes.size.toLong(),cursor.getLong(4))
        }
        val actual=context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertArrayEquals(bytes,actual)
    }

    private fun assertNoRow(collection:Uri,name:String) {
        val column=MediaStore.MediaColumns.DISPLAY_NAME
        context.contentResolver.query(collection,arrayOf(column),"$column = ?",arrayOf(name),null).use { cursor ->
            assertNotNull(cursor)
            assertEquals(0,cursor!!.count)
        }
    }
}
