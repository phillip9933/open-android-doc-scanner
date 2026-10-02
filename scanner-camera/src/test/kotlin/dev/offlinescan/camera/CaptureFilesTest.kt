package dev.offlinescan.camera

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.FileAlreadyExistsException

class CaptureFilesTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun commitsUniqueCaptureWithoutLeavingStaging() {
        val staging = folder.newFile(".scan-stage.jpg").apply { writeText("captured") }
        val destination = File(folder.root, "page.jpg")
        CaptureFiles.commit(staging, destination)
        assertEquals("captured", destination.readText())
        assertFalse(staging.exists())
    }
    @Test fun existingDestinationIsPreservedWhenAConcurrentWriterWins() {
        val staging = folder.newFile(".scan-stage.jpg").apply { writeText("captured") }
        val destination = folder.newFile("page.jpg").apply { writeText("original") }
        try { CaptureFiles.commit(staging, destination); fail("Existing file must not be overwritten") }
        catch (_: FileAlreadyExistsException) { }
        assertEquals("original", destination.readText())
        assertEquals("captured", staging.readText())
    }
}
