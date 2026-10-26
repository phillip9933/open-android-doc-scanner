package dev.offlinescan.ocr

import dev.offlinescan.core.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.test.*

class OcrFilesTest {
    private fun scenario(action: (File, File) -> Unit) {
        val root = Files.createTempDirectory("ocr-files").toFile()
        try {
            action(File(root, "source").apply { writeText("approved model") }, File(root, "copy"))
        } finally { root.deleteRecursively() }
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    @Test fun verifiedSnapshotDoesNotChangeWithSource() = scenario { source, target ->
        OcrFiles.copyVerified(source, target, 100, hash(source), NeverCancelled)
        source.writeText("replacement")
        assertEquals("approved model", target.readText())
    }
    @Test fun tamperedModelIsRejectedAndCopyRemoved() = scenario { source, target ->
        val approved = hash(source)
        source.appendText("tampered")
        assertFailsWith<IllegalArgumentException> { OcrFiles.copyVerified(source, target, 100, approved, NeverCancelled) }
        assertFalse(target.exists())
    }
    @Test fun byteLimitRejectsBeforeCreatingCopy() = scenario { source, target ->
        assertFailsWith<IllegalArgumentException> { OcrFiles.copyVerified(source, target, 1, null, NeverCancelled) }
        assertFalse(target.exists())
    }
    @Test fun growthDuringCopyCannotBypassLimit() = scenario { source, target ->
        var checks = 0
        val token = Cancellation { if (++checks == 2) source.appendText("x".repeat(100)) }
        assertFailsWith<IllegalArgumentException> { OcrFiles.copyVerified(source, target, 20, null, token) }
        assertFalse(target.exists())
    }
    @Test fun cancellationRemovesPartialCopy() = scenario { source, target ->
        var checks = 0
        assertFailsWith<CancellationException> {
            OcrFiles.copyVerified(source, target, 100, null, Cancellation { if (++checks == 3) throw CancellationException() })
        }
        assertFalse(target.exists())
    }
    @Test fun directoriesAreNotAccepted() = scenario { source, target ->
        assertFailsWith<IllegalArgumentException> { OcrFiles.copyVerified(source.parentFile, target, 100, null, NeverCancelled) }
        assertFalse(target.exists())
    }
}
