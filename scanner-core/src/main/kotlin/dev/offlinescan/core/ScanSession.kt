package dev.offlinescan.core

import java.io.File
import java.util.UUID

/** Owns only its UUID directory. Imports are copied, never modified or removed. Single-thread confined. */
class ScanSession(val config: ScanConfig, directory: File, private val processor: ImageProcessor) : AutoCloseable {
    val directory: File = File(directory, "scan-${UUID.randomUUID()}")
    private val mutablePages = mutableListOf<ScanPage>()
    val pages: List<ScanPage> get() = mutablePages.toList()
    private var closed = false
    init { if (!this.directory.mkdirs()) throw ScanException(ScanError(ErrorCode.STORAGE,"Cannot create private session directory")) }
    private fun checkOpen() { check(!closed) { "Session is closed" } }
    private fun copy(source: File): ScanPage {
        checkOpen()
        if (!source.isFile || source.length() !in 1..MAX_INPUT_BYTES) throw ScanException(ScanError(ErrorCode.INVALID_IMAGE,"Input is empty or exceeds 64 MiB"))
        val file=File(directory,"original-${UUID.randomUUID()}")
        try {
            source.inputStream().use { input -> file.outputStream().use { output ->
                val buffer=ByteArray(65536); var total=0L
                while (true) { val count=input.read(buffer); if(count<0) break; total+=count; if(total>MAX_INPUT_BYTES) throw ScanException(ScanError(ErrorCode.RESOURCE_LIMIT,"Input exceeds 64 MiB")); output.write(buffer,0,count) }
            } }
            val (width,height)=processor.inspect(file,config)
            return ScanPage(UUID.randomUUID().toString(),file,width,height,Edits(preset=config.defaultPreset))
        } catch (e: Throwable) { file.delete(); throw e }
    }
    fun `import`(source: File): ScanPage {
        checkOpen(); if(mutablePages.size>=config.pageLimit) throw ScanException(ScanError(ErrorCode.RESOURCE_LIMIT,"Page limit reached"))
        return copy(source).also { mutablePages.add(it) }
    }
    fun update(id: String, edits: Edits) { checkOpen(); Geometry.requireValid(edits.corners); val index=mutablePages.indexOfFirst { it.id==id }; require(index>=0); mutablePages[index]=mutablePages[index].copy(edits=edits) }
    fun replace(id: String, source: File): ScanPage {
        checkOpen(); val index=mutablePages.indexOfFirst { it.id==id }; require(index>=0)
        val replacement=copy(source).copy(id=id); val old=mutablePages[index]; mutablePages[index]=replacement; old.original.delete(); return replacement
    }
    fun remove(id: String) { checkOpen(); val page=mutablePages.firstOrNull { it.id==id } ?: return; mutablePages.remove(page); page.original.delete() }
    fun move(id: String, toIndex: Int) { checkOpen(); require(toIndex in mutablePages.indices); val index=mutablePages.indexOfFirst { it.id==id }; require(index>=0); mutablePages.add(toIndex,mutablePages.removeAt(index)) }
    /** Close only after processing jobs finish. Completed outputs are stored elsewhere and stay host-owned. */
    override fun close() { if(!closed) { closed=true; mutablePages.clear(); directory.deleteRecursively() } }
    companion object { const val MAX_INPUT_BYTES=64L*1024*1024 }
}

