package dev.offlinescan.ui

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.annotation.RestrictTo
import dev.offlinescan.core.ErrorCode
import dev.offlinescan.core.ScanError
import dev.offlinescan.core.ScanException
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Shared with the sample's Storage Access Framework export. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object ProviderTransfers {
    private const val TRANSFER_TIMEOUT_MS = 30_000L
    private val workers = boundedExecutor()
    private val cancellationWorkers = boundedExecutor()

    internal suspend fun importImage(resolver: ContentResolver, uri: Uri, target: File, maxBytes: Long) {
        transfer { signal, checkActive ->
            var complete = false
            try {
                val asset = resolver.openAssetFileDescriptor(uri, "r", signal)
                    ?: throw ScanException(ScanError(ErrorCode.INVALID_IMAGE, "Unable to open image"))
                asset.use {
                    val fd = asset.fileDescriptor
                    val pipe = configurePipe(fd)
                    asset.createInputStream().use { input ->
                        checkActive()
                        target.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var remaining = maxBytes
                            while (true) {
                                awaitReady(fd, pipe, OsConstants.POLLIN, checkActive)
                                val count = input.read(buffer)
                                if (count < 0) break
                                remaining -= count
                                if (remaining < 0) {
                                    throw ScanException(ScanError(ErrorCode.RESOURCE_LIMIT, "Image exceeds import size limit"))
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
                checkActive()
                complete = true
            } finally {
                if (!complete) target.delete()
            }
        }
    }

    suspend fun saveDocument(resolver: ContentResolver, uri: Uri, source: File) {
        transfer { signal, checkActive ->
            val handle = resolver.openFileDescriptor(uri, "wt", signal)
                ?: throw IOException("Cannot open the selected document")
            ParcelFileDescriptor.AutoCloseOutputStream(handle).use { output ->
                val fd = handle.fileDescriptor
                val pipe = configurePipe(fd)
                source.inputStream().use { input ->
                    // Small writes bound how much a pipe is asked to accept at once.
                    val buffer = ByteArray(4096)
                    while (true) {
                        checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        awaitReady(fd, pipe, OsConstants.POLLOUT, checkActive)
                        output.write(buffer, 0, count)
                    }
                }
                checkActive()
            }
        }
    }

    suspend fun deleteDocument(resolver: ContentResolver, uri: Uri) {
        transfer(timeoutMs = 1_000) { _, checkActive ->
            checkActive()
            DocumentsContract.deleteDocument(resolver, uri)
        }
    }

    private suspend fun transfer(
        timeoutMs: Long = TRANSFER_TIMEOUT_MS,
        block: (CancellationSignal, () -> Unit) -> Unit
    ) {
        val finished = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val cancelled = AtomicBoolean()
                val signal = CancellationSignal()
                val checkActive = {
                    if (cancelled.get()) throw CancellationException("File transfer cancelled")
                }
                continuation.invokeOnCancellation {
                    cancelled.set(true)
                    // A provider can stall inside cancellation Binder IPC too. Keep it off
                    // the UI thread and bound the total number of occupied workers.
                    try {
                        cancellationWorkers.execute { signal.cancel() }
                    } catch (_: RejectedExecutionException) {
                        // Pipe I/O still observes the cancellation flag.
                    }
                }
                try {
                    workers.execute {
                        try {
                            checkActive()
                            block(signal, checkActive)
                            continuation.resume(Unit)
                        } catch (failure: Exception) {
                            continuation.resumeWithException(failure)
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    continuation.resumeWithException(IOException("File transfer is busy. Try again later."))
                }
            }
        }
        if (finished == null) throw IOException("The selected file provider timed out")
    }

    private fun boundedExecutor() =
        ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>())

    private fun configurePipe(fd: FileDescriptor): Boolean {
        val mode = Os.fstat(fd).st_mode
        val pipe = OsConstants.S_ISFIFO(mode) || OsConstants.S_ISSOCK(mode)
        if (pipe && Build.VERSION.SDK_INT >= 30) {
            val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
            Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        }
        return pipe
    }

    private fun awaitReady(fd: FileDescriptor, pipe: Boolean, events: Int, checkActive: () -> Unit) {
        checkActive()
        if (!pipe) return
        val watch = arrayOf(StructPollfd().apply {
            this.fd = fd
            this.events = events.toShort()
        })
        while (Os.poll(watch, 150) == 0) checkActive()
        checkActive()
    }
}
