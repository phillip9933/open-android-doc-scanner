package dev.offlinescan.ocr

import dev.offlinescan.core.Cancellation
import java.io.File
import java.security.MessageDigest

/** Approved tessdata_fast models. Changes require a reviewed SDK update. */
internal object OcrFiles {
    val approvedModels = mapOf(
        "eng" to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
        "jpn" to "1f5de9236d2e85f5fdf4b3c500f2d4926f8d9449f28f5394472d9e8d83b91b4d"
    )
    const val MAX_MODEL_BYTES = 64L * 1024 * 1024

    /** Check both the initial length and streamed count; hash the exact private copy used by native code. */
    fun copyVerified(source: File, destination: File, maxBytes: Long, expectedSha256: String?, cancellation: Cancellation) {
        cancellation.check()
        require(source.isFile && source.length() in 1..maxBytes) { "OCR input is not a regular file or exceeds byte limit" }
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            source.inputStream().use { input -> destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                while (true) {
                    cancellation.check()
                    val n = input.read(buffer)
                    if (n < 0) break
                    count += n
                    require(count <= maxBytes) { "OCR input exceeds byte limit" }
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
                require(count > 0) { "Empty OCR input" }
            } }
            cancellation.check()
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(expectedSha256 == null || actual == expectedSha256) { "OCR model integrity check failed" }
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        }
    }
}
