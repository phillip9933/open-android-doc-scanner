package dev.offlinescan.sample

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.offlinescan.ui.ProviderTransfers
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderTransferTest {
    @Test fun savingReplacesSelectedDocumentAndPreservesSource() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "scans/save-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source = File(root, "original.txt").apply { writeText("new scan") }
            val target = File(root, "document.txt").apply { writeText("longer earlier document") }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
            ProviderTransfers.saveDocument(context.contentResolver, uri, source)
            assertEquals("new scan", target.readText())
            assertEquals("new scan", source.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
