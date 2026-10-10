package dev.offlinescan.export

import dev.offlinescan.core.*
import android.graphics.BitmapFactory
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CancellationException

/** A basename only: extension and multi-page suffixes are supplied by the exporter. */
fun isValidDocumentName(name: String): Boolean {
    val stem = name.trim()
    return stem.length in 1..120 && stem != "." && stem != ".." &&
        stem.none { it.code < 32 || it in "<>:\"/\\|?*" } && !stem.endsWith('.')
}

/** Each operation commits an exclusive output subdirectory only on success. Caller owns successful outputs. */
class AndroidExporter(private val processor: ImageProcessor) {
    fun export(pages: List<ScanPage>, destination: File, format: ExportFormat, config: ScanConfig,
        cancellation: Cancellation = NeverCancelled, progress: Progress = Progress { _,_ -> },
        documentName: String? = null, includeDiagnostics: Boolean = false): ScanOutput {
        if (documentName != null && !isValidDocumentName(documentName))
            throw ScanException(ScanError(ErrorCode.STORAGE,"Invalid output file name"))
        val stem = documentName?.trim()
        if (pages.isEmpty() || pages.size>config.pageLimit) throw ScanException(ScanError(ErrorCode.RESOURCE_LIMIT,"Export needs pages within the configured limit"))
        val outputPath=destination.canonicalFile.toPath()
        if(pages.any { outputPath.startsWith(it.original.canonicalFile.parentFile.toPath()) })
            throw ScanException(ScanError(ErrorCode.STORAGE,"Outputs must be outside the temporary original-image directory"))
        val staging=File(destination,".pending-${UUID.randomUUID()}")
        if(!staging.mkdirs()) throw ScanException(ScanError(ErrorCode.STORAGE,"Cannot create output directory"))
        val sizes=mutableListOf<ExportedPage>(); val names=mutableListOf<String>()
        val originalNames=mutableListOf<String>(); val renderNames=mutableListOf<String>()
        var committed: File?=null
        try {
            cancellation.check(); progress.update(0,pages.size)
            if (includeDiagnostics) {
                val diagnosticDir=File(staging,"diagnostics")
                if (!diagnosticDir.mkdirs()) throw java.io.IOException("Cannot create diagnostic directory")
                pages.forEachIndexed { index,page ->
                    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                    BitmapFactory.decodeFile(page.original.path,bounds)
                    val extension=when(bounds.outMimeType) { "image/jpeg"->"jpg"; "image/png"->"png"; "image/webp"->"webp"; else->"image" }
                    val relative="diagnostics/original-${index+1}.$extension"
                    copyChecked(page.original,File(staging,relative),cancellation)
                    originalNames.add(relative)
                }
            }
            if(format==ExportFormat.PDF) {
                val name="${stem ?: "scan"}.pdf"
                val pdfFile=File(staging,name)
                PdfImageWriter(pdfFile,pages.size).use { pdf ->
                    pages.forEachIndexed { index,page ->
                        cancellation.check()
                        // Render one page at the configured output resolution, then stream its JPEG
                        // bytes into the PDF before starting the next page. Peak export memory is
                        // independent of the number of pages in the document.
                        val raster=File(staging,"render.jpg")
                        try {
                            val size=processor.render(page.original,page.edits,raster,ExportFormat.JPEG,config,cancellation)
                            if(!raster.isFile || raster.length()<4) throw ScanException(ScanError(ErrorCode.EXPORT,"Processor did not create a valid rendered page"))
                            pdf.addPage(raster,size.width,size.height,cancellation)
                            sizes.add(size)
                            if (includeDiagnostics) {
                                val relative="diagnostics/export-${index+1}.jpg"
                                copyChecked(raster,File(staging,relative),cancellation)
                                renderNames.add(relative)
                            }
                        } finally { raster.delete() }
                        cancellation.check(); progress.update(index+1,pages.size)
                    }
                }
                names.add(name)
            } else {
                pages.forEachIndexed { index,page ->
                    cancellation.check()
                    val name = if (stem == null) "page-${index+1}.${format.extension}"
                        else "$stem${if (pages.size > 1) "-${index+1}" else ""}.${format.extension}"
                    names.add(name)
                    if (includeDiagnostics) renderNames.add(name)
                    sizes.add(processor.render(page.original,page.edits,File(staging,name),format,config,cancellation))
                    cancellation.check(); progress.update(index+1,pages.size)
                }
            }
            cancellation.check()
            val resultDirectory=File(destination,"scan-${UUID.randomUUID()}")
            if(!staging.renameTo(resultDirectory)) throw ScanException(ScanError(ErrorCode.STORAGE,"Cannot commit output files"))
            committed=resultDirectory
            cancellation.check()
            val warnings=sizes.flatMapIndexed { index,page -> page.warnings.map { "Page ${index+1}: $it" } }
            return ScanOutput(names.map { File(resultDirectory,it) },format.mimeType,sizes,warnings +
                if(format==ExportFormat.PDF) listOf("Image-based PDF; no searchable text. Page images are placed at 300 DPI and preserve the configured rendered pixel dimensions.") else emptyList(),
                if (includeDiagnostics) ScanDiagnostics(originalNames.map { File(resultDirectory,it) },renderNames.map { File(resultDirectory,it) }) else null)
        } catch(e: CancellationException) { committed?.deleteRecursively(); throw e }
        catch(e: OutOfMemoryError) { committed?.deleteRecursively(); throw ScanException(ScanError(ErrorCode.RESOURCE_LIMIT,"Not enough memory to export this session"),e) }
        catch(e: ScanException) { committed?.deleteRecursively(); throw e }
        catch(e: Exception) { committed?.deleteRecursively(); throw ScanException(ScanError(ErrorCode.EXPORT,"Export failed"),e) }
        finally { staging.deleteRecursively() }
    }
}

/** Incremental image-only PDF writer. Raster page bytes are copied directly and released one at a time. */
private class PdfImageWriter(file: File, private val pageCount: Int) : AutoCloseable {
    private val output = CountingOutputStream(BufferedOutputStream(FileOutputStream(file)))
    private val offsets = LongArray(3 + pageCount * 3)
    private val pageIds = IntArray(pageCount)
    private var nextPage = 0
    private var finished = false

    init {
        try {
            output.write("%PDF-1.4\n".toByteArray(StandardCharsets.US_ASCII))
            output.write(byteArrayOf('%'.code.toByte(), 0xe2.toByte(), 0xe3.toByte(), 0xcf.toByte(), 0xd3.toByte(), '\n'.code.toByte()))
        } catch (error: Throwable) {
            try { output.close() } catch (closeError: Throwable) { error.addSuppressed(closeError) }
            throw error
        }
    }

    fun addPage(jpeg: File, width: Int, height: Int, cancellation: Cancellation) {
        check(!finished && nextPage < pageCount)
        cancellation.check()
        require(width > 0 && height > 0)
        val pageId = 3 + nextPage * 3
        val contentId = pageId + 1
        val imageId = pageId + 2
        pageIds[nextPage] = pageId
        val pageWidth = formatPdfNumber(width * PDF_POINTS_PER_PIXEL)
        val pageHeight = formatPdfNumber(height * PDF_POINTS_PER_PIXEL)
        writeObject(pageId, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pageWidth $pageHeight] /Resources << /XObject << /Im0 $imageId 0 R >> >> /Contents $contentId 0 R >>")
        val content = "q\n$pageWidth 0 0 $pageHeight 0 0 cm\n/Im0 Do\nQ\n".toByteArray(StandardCharsets.US_ASCII)
        writeObject(contentId, "<< /Length ${content.size} >>\nstream\n", content, "endstream")
        val length = jpeg.length()
        offsets[imageId] = output.count
        writeAscii("$imageId 0 obj\n<< /Type /XObject /Subtype /Image /Width $width /Height $height /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length $length >>\nstream\n")
        FileInputStream(jpeg).use { input ->
            val buffer=ByteArray(64 * 1024)
            while(true) {
                cancellation.check()
                val count=input.read(buffer)
                if(count<0) break
                output.write(buffer,0,count)
            }
        }
        cancellation.check()
        writeAscii("\nendstream\nendobj\n")
        nextPage++
    }

    override fun close() {
        if (finished) return
        if (nextPage != pageCount) {
            try { output.close() } finally { finished = true }
            return
        }
        try {
            writeObject(2, "<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count $pageCount >>")
            writeObject(1, "<< /Type /Catalog /Pages 2 0 R >>")
            val xref = output.count
            writeAscii("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
            for (id in 1 until offsets.size) writeAscii(String.format(java.util.Locale.US, "%010d 00000 n \n", offsets[id]))
            writeAscii("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        } finally {
            try { output.close() } finally { finished = true }
        }
    }

    private fun writeObject(id: Int, dictionary: String, stream: ByteArray? = null, suffix: String = "") {
        offsets[id] = output.count
        writeAscii("$id 0 obj\n$dictionary\n")
        if (stream != null) { output.write(stream); writeAscii("\n") }
        if (suffix.isNotEmpty()) writeAscii("$suffix\n")
        writeAscii("endobj\n")
    }

    private fun writeAscii(value: String) = output.write(value.toByteArray(StandardCharsets.US_ASCII))

    private fun formatPdfNumber(value: Double) = String.format(java.util.Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')

    companion object { private const val PDF_POINTS_PER_PIXEL = 72.0 / 300.0 }
}

private class CountingOutputStream(output: OutputStream) : java.io.FilterOutputStream(output) {
    var count: Long = 0
        private set
    override fun write(value: Int) { out.write(value); count++ }
    override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length); count += length }
}

private fun copyChecked(source: File, destination: File, cancellation: Cancellation) {
    source.inputStream().use { input -> destination.outputStream().use { output ->
        val buffer=ByteArray(64*1024)
        while(true) { cancellation.check(); val count=input.read(buffer); if(count<0) break; output.write(buffer,0,count) }
    } }
    cancellation.check()
}
