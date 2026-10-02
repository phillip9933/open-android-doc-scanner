package dev.offlinescan.camera

import java.io.File
import java.nio.file.Files

internal object CaptureFiles {
    /** Same-directory move; omit both REPLACE_EXISTING and ATOMIC_MOVE to preserve existing targets. */
    fun commit(staging: File, destination: File) {
        Files.move(staging.toPath(), destination.toPath())
    }
}
