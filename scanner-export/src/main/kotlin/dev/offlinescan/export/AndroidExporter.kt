package dev.offlinescan.export

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import dev.offlinescan.core.*
import java.io.File
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
        documentName: String? = null): ScanOutput {
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
        var committed: File?=null
        try {
            cancellation.check(); progress.update(0,pages.size)
            if(format==ExportFormat.PDF) {
                val pdf=PdfDocument()
                val pdfConfig=config.copy(maxOutputDimension=minOf(config.maxOutputDimension,2048,kotlin.math.sqrt(16_000_000.0/pages.size).toInt()))
                try {
                    pages.forEachIndexed { index,page ->
                        cancellation.check()
                        val raster=File(staging,"render.png")
                        val size=processor.render(page.original,page.edits,raster,ExportFormat.PNG,pdfConfig,cancellation)
                        val bitmap=BitmapFactory.decodeFile(raster.path) ?: throw ScanException(ScanError(ErrorCode.EXPORT,"Cannot decode rendered page"))
                        try {
                            val pdfPage=pdf.startPage(PdfDocument.PageInfo.Builder(size.width,size.height,index+1).create())
                            pdfPage.canvas.drawBitmap(bitmap,0f,0f,Paint(Paint.FILTER_BITMAP_FLAG))
                            pdf.finishPage(pdfPage); sizes.add(size)
                        } finally { bitmap.recycle(); raster.delete() }
                        cancellation.check(); progress.update(index+1,pages.size)
                    }
                    cancellation.check(); val name="${stem ?: "scan"}.pdf"
                    File(staging,name).outputStream().use { pdf.writeTo(it) }; names.add(name)
                } finally { pdf.close() }
            } else {
                pages.forEachIndexed { index,page ->
                    cancellation.check()
                    val name = if (stem == null) "page-${index+1}.${format.extension}"
                        else "$stem${if (pages.size > 1) "-${index+1}" else ""}.${format.extension}"
                    names.add(name)
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
                if(format==ExportFormat.PDF) listOf("Image-based PDF; no searchable text. Page dimensions use one pixel per PDF point.","PDF page edges limited to 2048 pixels and a 16 megapixel aggregate raster budget to bound memory.") else emptyList())
        } catch(e: CancellationException) { committed?.deleteRecursively(); throw e }
        catch(e: OutOfMemoryError) { committed?.deleteRecursively(); throw ScanException(ScanError(ErrorCode.RESOURCE_LIMIT,"Not enough memory to export this session"),e) }
        catch(e: ScanException) { committed?.deleteRecursively(); throw e }
        catch(e: Exception) { committed?.deleteRecursively(); throw ScanException(ScanError(ErrorCode.EXPORT,"Export failed"),e) }
        finally { staging.deleteRecursively() }
    }
}
