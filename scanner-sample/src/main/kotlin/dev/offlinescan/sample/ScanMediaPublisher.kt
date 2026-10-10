package dev.offlinescan.sample

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.offlinescan.core.Cancellation
import dev.offlinescan.core.NeverCancelled
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

/** The published MediaStore row created for a successful scan export. */
data class PublishedScan(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long
)

/** Copies a completed scanner export into a user-visible Pictures or Downloads collection. */
object ScanMediaPublisher {
    /**
     * Publishes JPEG/PNG scans under Pictures/Offline Scanner and PDFs under Downloads/Offline Scanner.
     * Android 10+ owns cleanup for rows created by this app, so no broad storage permission is needed.
     */
    @Throws(IOException::class, CancellationException::class)
    fun publish(
        context: Context,
        source: File,
        mimeType: String,
        displayName: String = source.name,
        cancellation: Cancellation = NeverCancelled
    ): PublishedScan {
        if (!source.isFile) throw IOException("Scan output does not exist")
        return publishFrom(context, source.length(), mimeType, displayName, cancellation) { FileInputStream(source) }
    }

    /** Internal input seam lets instrumentation verify rollback after a partial stream failure. */
    @Throws(IOException::class, CancellationException::class)
    internal fun publishFrom(
        context: Context,
        expectedSize: Long,
        mimeType: String,
        displayName: String,
        cancellation: Cancellation = NeverCancelled,
        openInput: () -> InputStream
    ): PublishedScan {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UnsupportedOperationException("MediaStore publishing requires Android 10 or later")
        }
        validateDisplayName(displayName)
        val (collection, relativePath) = when (mimeType) {
            "image/jpeg", "image/png" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to
                "${Environment.DIRECTORY_PICTURES}/Offline Scanner"
            "application/pdf" -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to
                "${Environment.DIRECTORY_DOWNLOADS}/Offline Scanner"
            else -> throw IllegalArgumentException("Unsupported scan MIME type: $mimeType")
        }

        cancellation.check()
        val resolver = context.contentResolver
        val row = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, row) ?: throw IOException("Cannot create a MediaStore item")
        try {
            cancellation.check()
            var copied = 0L
            openInput().use { input ->
                val output = resolver.openOutputStream(uri, "w") ?: throw IOException("Cannot open the MediaStore item")
                output.use { destination ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        cancellation.check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        destination.write(buffer, 0, count)
                        copied += count
                    }
                    destination.flush()
                }
            }
            cancellation.check()
            if (expectedSize >= 0 && copied != expectedSize) {
                throw IOException("Scan output changed while it was being published")
            }
            val finalized = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, finalized, null, null) != 1) {
                throw IOException("Cannot finalize the MediaStore item")
            }
            cancellation.check()
            return PublishedScan(uri, displayName, mimeType, copied)
        } catch (failure: Throwable) {
            try {
                resolver.delete(uri, null, null)
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    private fun validateDisplayName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." && name.length <= 180) {
            "Invalid scan display name"
        }
        require(name.none { it.code < 32 || it == '/' || it == '\\' }) { "Invalid scan display name" }
    }

    private const val COPY_BUFFER_BYTES = 64 * 1024
}
