package dev.offlinescan.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.offlinescan.camera.ScannerCamera
import dev.offlinescan.camera.AutoCaptureGate
import dev.offlinescan.core.Cancellation
import dev.offlinescan.core.DetectionMode
import dev.offlinescan.core.Detection
import dev.offlinescan.core.Edits
import dev.offlinescan.core.ErrorCode
import dev.offlinescan.core.ExportFormat
import dev.offlinescan.core.Geometry
import dev.offlinescan.core.ImageProcessor
import dev.offlinescan.core.Point
import dev.offlinescan.core.Preset
import dev.offlinescan.core.Quad
import dev.offlinescan.core.ScanConfig
import dev.offlinescan.core.ScanMode
import dev.offlinescan.core.ScanError
import dev.offlinescan.core.ScanException
import dev.offlinescan.core.ScanPage
import dev.offlinescan.core.ScanResult
import dev.offlinescan.core.ScanSession
import dev.offlinescan.export.AndroidExporter
import dev.offlinescan.processing.LearnedDocumentDetector
import dev.offlinescan.processing.OpenCvProcessor
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_IMPORT_BYTES = 64L * 1024L * 1024L

private enum class Stage { CAPTURE, CROP, APPEARANCE, REVIEW, SAVE }

/**
 * Camera and import scanning flow. The session owns copied source images in `cacheDir`; leaving
 * this composable closes the session and discards those temporary originals. Exported files are
 * written to [outputDirectory] and returned through [onResult].
 */
@Composable
fun ScannerFlow(
    config: ScanConfig = ScanConfig(detectionMode=DetectionMode.AI),
    outputDirectory: File,
    onResult: (ScanResult) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val delivered = remember { AtomicBoolean(false) }
    val disposed = remember { AtomicBoolean(false) }
    val latestOnResult = rememberUpdatedState(onResult)
    val processor = remember { OpenCvProcessor() }
    val learnedDetector = remember(context) { LearnedDocumentDetector(context) }
    val sessionCreation = remember(context, config, processor) {
        runCatching { ScanSession(config, File(context.cacheDir, "offline-scans"), processor) }
    }
    val session = sessionCreation.getOrNull()
    if (session == null) {
        LaunchedEffect(sessionCreation) {
            if (delivered.compareAndSet(false,true)) latestOnResult.value(ScanResult.Failed(toScanError(sessionCreation.exceptionOrNull()!!,ErrorCode.STORAGE)))
        }
        return
    }
    val sessionLifetime = remember(session) { SessionLifetime(session) }
    val autoCaptureGate = remember(session) { AutoCaptureGate() }
    var captureMode by remember(session) { mutableStateOf(config.mode) }
    var detectionMode by remember(session) { mutableStateOf(config.detectionMode) }
    val captureConfig = config.copy(mode=captureMode,autoCapture=config.autoCapture && captureMode != ScanMode.PHOTO,detectionMode=detectionMode)
    val pages = remember { mutableStateListOf<ScanPage>() }
    var stage by remember { mutableStateOf(Stage.CAPTURE) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var format by remember { mutableStateOf(ExportFormat.PDF) }
    var documentName by remember(session) { mutableStateOf("Scanned_" +
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<ScanError?>(null) }
    var cameraAllowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var cameraError by remember { mutableStateOf(false) }
    var replaceTargetId by remember { mutableStateOf<String?>(null) }
    var editReturn by remember { mutableStateOf(Stage.REVIEW) }
    var editBaseline by remember { mutableStateOf<Edits?>(null) }
    var discardRequested by remember { mutableStateOf(false) }
    var deleteRequestedId by remember { mutableStateOf<String?>(null) }
    var retakeRequestedId by remember { mutableStateOf<String?>(null) }
    var pendingRetakeIndex by remember { mutableStateOf<Int?>(null) }
    var activeCancellation by remember { mutableStateOf<AtomicBoolean?>(null) }
    var activeOperationJob by remember { mutableStateOf<Job?>(null) }
    val cameraHolder = remember { mutableStateOf<ScannerCamera?>(null) }

    fun syncPages() {
        pages.clear()
        pages.addAll(sessionLifetime.readPages())
        sessionLifetime.retainPreviews(pages)
    }
    fun acceptPage(page: ScanPage, targetId: String?) {
        val retakeIndex = pendingRetakeIndex
        if (retakeIndex != null) session.move(page.id, retakeIndex.coerceAtMost(session.pages.lastIndex))
        pendingRetakeIndex = null
        replaceTargetId = null
        syncPages()
        selectedId = page.id
        stage = if (targetId != null || retakeIndex != null) Stage.REVIEW else Stage.CAPTURE
    }
    fun deliver(result: ScanResult): Boolean {
        if (disposed.get() || !delivered.compareAndSet(false, true)) return false
        latestOnResult.value(result)
        return true
    }

    LaunchedEffect(stage) {
        if (stage != Stage.CAPTURE) {
            cameraHolder.value?.close()
            cameraHolder.value = null
        }
    }
    BackHandler {
        if (!busy && (stage == Stage.CROP || stage == Stage.APPEARANCE)) { selectedId?.let { id -> session.pages.firstOrNull { it.id==id }?.let { page -> editBaseline?.let { session.update(id,it);syncPages() } } }; stage = editReturn }
        else if (!busy && stage == Stage.SAVE) stage = Stage.REVIEW
        else if (!busy && stage == Stage.REVIEW) stage = Stage.CAPTURE
        else if (!busy) { if(pages.isEmpty()) deliver(ScanResult.Cancelled) else discardRequested=true }
        else if (activeCancellation != null) activeCancellation?.set(true)
        else { activeOperationJob?.cancel(); deliver(ScanResult.Cancelled) }
    }

    DisposableEffect(session) {
        onDispose {
            disposed.set(true)
            cameraHolder.value?.close()
            cameraHolder.value = null
            activeCancellation?.set(true)
            sessionLifetime.closeWhenIdle()
            learnedDetector.close()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !busy) {
            busy = true
            error = null
            activeOperationJob = scope.launch {
                var source: File? = null
                try {
                    val importFile = File(context.cacheDir, "import-${System.nanoTime()}.img")
                    source = importFile
                    withContext(Dispatchers.IO) {
                        val job = currentCoroutineContext()[Job]
                        copyBoundedUri(context, uri, importFile, Cancellation { if (job?.isActive != true) throw CancellationException() })
                    }
                    val targetId = replaceTargetId
                    val operationJob = currentCoroutineContext()[Job]
                    val page = sessionLifetime.run {
                        importPage(session, processor, captureConfig, importFile, targetId,
                            Cancellation { if (operationJob?.isActive != true) throw CancellationException() }, learnedDetector)
                    }
                    acceptPage(page, targetId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Exception) {
                    error = toScanError(t, ErrorCode.INVALID_IMAGE)
                } finally { source?.delete(); busy = false; activeOperationJob = null }
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraAllowed = granted
        if (!granted) cameraError = true
    }

    LaunchedEffect(session) { if (!cameraAllowed) permissionLauncher.launch(Manifest.permission.CAMERA) }

    fun exportScan() {
                        if (!busy && pages.isNotEmpty()) {
                            busy = true; progress = 0f; error = null
                            val token = AtomicBoolean(false)
                            activeCancellation = token
                            activeOperationJob = scope.launch {
                                var completedResult: ScanResult? = null
                                try {
                                    val out = withContext(NonCancellable) {
                                        sessionLifetime.run {
                                            AndroidExporter(processor).export(
                                                pages = session.pages, destination = outputDirectory, format = format,
                                                config = config, cancellation = Cancellation { if (token.get()) throw CancellationException() },
                                                progress = { done, total -> progress = if (total <= 0) 0f else done.toFloat() / total },
                                                documentName = documentName
                                            )
                                        }
                                    }
                                    completedResult = ScanResult.Completed(out)
                                } catch (t: Throwable) {
                                    if (t is CancellationException) { completedResult = ScanResult.Cancelled }
                                    else error = toScanError(t, ErrorCode.EXPORT)
                                } finally { busy = false; activeCancellation = null; activeOperationJob = null }
                                completedResult?.let { result ->
                                    if (!deliver(result) && result is ScanResult.Completed) deleteUndeliveredOutput(result, outputDirectory)
                                }
                            }
                        }
    }

    val selected = pages.firstOrNull { it.id == selectedId } ?: pages.lastOrNull()
    val lightBars = stage != Stage.CAPTURE && MaterialTheme.colorScheme.background.luminance() > .5f
    DisposableEffect(context, lightBars) {
        var host = context
        while (host is ContextWrapper && host !is Activity) host = host.baseContext
        val window = (host as? Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = lightBars
        controller?.isAppearanceLightNavigationBars = lightBars
        onDispose {
            if (previousStatus != null) controller?.isAppearanceLightStatusBars = previousStatus
            if (previousNavigation != null) controller?.isAppearanceLightNavigationBars = previousNavigation
        }
    }
    Surface(modifier = if(stage == Stage.CAPTURE) Modifier.fillMaxSize() else Modifier.fillMaxSize().systemBarsPadding(), color = if(stage == Stage.CAPTURE) Color.Black else MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            when (stage) {
                Stage.CAPTURE -> CaptureStage(
                    context = context,
                    allowed = cameraAllowed,
                    failed = cameraError,
                    config = captureConfig,
                    autoCaptureAllowed = config.autoCapture,
                    onDetectionMode = { mode ->
                        cameraHolder.value?.close(); cameraHolder.value = null
                        detectionMode = mode
                    },
                    bitmapDetector = { bitmap, cancel ->
                        if (detectionMode == DetectionMode.AI) learnedDetector.detectBitmap(bitmap,cancel)
                        else processor.detectBitmap(bitmap,cancel)
                    },
                    stillDetector = { file, cancel ->
                        if (detectionMode == DetectionMode.AI) learnedDetector.detect(file,captureConfig,cancel)
                        else processor.detect(file,captureConfig,cancel)
                    },
                    onMode = { mode -> cameraHolder.value?.setAutoCaptureEnabled(false); captureMode = mode },
                    cameraHolder = cameraHolder,
                    autoCaptureGate = autoCaptureGate,
                    onPermission = {
                        cameraError = false
                        cameraHolder.value?.close()
                        cameraHolder.value = null
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onImport = { importLauncher.launch("image/*") },
                    onCapture = { file ->
                        busy = true
                        activeOperationJob = scope.launch {
                            try {
                                val targetId = replaceTargetId
                                val operationJob = currentCoroutineContext()[Job]
                                val page = sessionLifetime.run {
                                    importPage(session, processor, captureConfig, file, targetId,
                                        Cancellation { if (operationJob?.isActive != true) throw CancellationException() }, learnedDetector)
                                }
                                file.delete()
                                acceptPage(page, targetId)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (t: Exception) { error = toScanError(t, ErrorCode.CAMERA) }
                            finally { file.delete(); busy = false; activeOperationJob = null }
                        }
                    },
                    onReview = { if (pages.isNotEmpty()) { pendingRetakeIndex = null; stage = Stage.REVIEW } },
                    pages = pages.toList(),
                    canAdd = pages.size < config.pageLimit || replaceTargetId != null,
                    onPage = { id -> pendingRetakeIndex = null; selectedId = id; stage = Stage.REVIEW },
                    onCancel = { if (!busy) { if(pages.isEmpty()) deliver(ScanResult.Cancelled) else discardRequested=true } },
                    pageBitmap = { page -> rememberRenderedPreview(page,processor,config,sessionLifetime) },
                    busy = busy,
                    obscured = discardRequested || deleteRequestedId != null || retakeRequestedId != null || error != null,
                    error = error?.let { userMessage(it) },
                    onCameraError = { scanError -> cameraError = true; error = scanError }
                )
                Stage.CROP -> selected?.let { page ->
                    EditStage(page,processor,config,sessionLifetime,onEdits = { edits ->
                        try { session.update(page.id, edits); syncPages() }
                        catch (t: Exception) { error = toScanError(t, ErrorCode.INVALID_GEOMETRY) }
                    }, onBack = { editBaseline?.let { session.update(page.id,it);syncPages() };stage = editReturn }, onNext = { stage = editReturn })
                }
                Stage.APPEARANCE -> selected?.let { page ->
                    AppearanceStage(page, processor, config, sessionLifetime, onPreset = { preset -> updateEdits(page, session, ::syncPages, { error = it }, page.edits.copy(preset = preset)) },
                        onBrightness = { value -> updateEdits(page, session, ::syncPages, { error = it }, page.edits.copy(brightness = value.toDouble())) },
                        onContrast = { value -> updateEdits(page, session, ::syncPages, { error = it }, page.edits.copy(contrast = value.toDouble())) },
                        onIllumination = { value -> updateEdits(page, session, ::syncPages, { error = it }, page.edits.copy(illumination = value.toDouble())) },
                        onReset = { updateEdits(page, session, ::syncPages, { error = it }, page.edits.copy(preset = Preset.ORIGINAL, brightness = 0.0, contrast = 1.0, illumination = 0.0)) },
                        onRotate = { updateEdits(page, session, ::syncPages, { error = it }, page.edits.copy(rotationQuarterTurns = (page.edits.rotationQuarterTurns + 1) % 4)) },
                        onApplyAll = { val appearance=page.edits;pages.toList().forEach { current -> session.update(current.id,current.edits.copy(preset=appearance.preset,brightness=appearance.brightness,contrast=appearance.contrast,illumination=appearance.illumination)) };syncPages();stage=editReturn },
                        onBack = { editBaseline?.let { session.update(page.id,it);syncPages() };stage=editReturn }, onNext = { stage = editReturn })
                }
                Stage.REVIEW -> ScannerReview(
                    pages = pages.toList(), selectedId = selectedId, pageLimit = config.pageLimit,
                    format = format, onFormat = { format = it },
                    onSelect = { selectedId = it }, onEdit = { editBaseline=selected?.edits;editReturn = Stage.REVIEW; stage = Stage.CROP },
                    onAppearance = { editBaseline=selected?.edits;editReturn = Stage.REVIEW; stage = Stage.APPEARANCE },
                    onDiscard = { discardRequested=true },
                    onEnhance = { selected?.let { page -> updateEdits(page,session,::syncPages,{error=it},page.edits.copy(preset=if(page.edits.preset==Preset.AUTO) Preset.ORIGINAL else Preset.AUTO)) } },
                    onRotate = { selected?.let { updateEdits(it,session,::syncPages,{error=it},it.edits.copy(rotationQuarterTurns=(it.edits.rotationQuarterTurns+1)%4)) } },
                    onRemove = { id -> deleteRequestedId = id },
                    onMove = { id, index -> try { session.move(id, index); syncPages() } catch (t: Exception) { error = toScanError(t, ErrorCode.PROCESSING) } },
                    onAdd = { pendingRetakeIndex = null; replaceTargetId = null; stage = Stage.CAPTURE },
                    onRetake = { retakeRequestedId = selected?.id },
                    onFinish = { stage = Stage.SAVE },
                    busy = busy, progress = progress,
                    onCancelExport = { activeCancellation?.set(true) },
                    onBack = { stage = Stage.CAPTURE },
                    pageBitmap = { page -> rememberRenderedPreview(page,processor,config,sessionLifetime) }
                )
                Stage.SAVE -> ScannerSave(pages=pages.toList(),selectedId=selectedId,onSelect={selectedId=it},format=format,onFormat={format=it},
                    documentName=documentName,onDocumentNameChange={documentName=it},onBack={stage=Stage.REVIEW},onClose={discardRequested=true},
                    onFinish=::exportScan,busy=busy,progress=progress,onCancel={activeCancellation?.set(true)},
                    pageBitmap={page->rememberRenderedPreview(page,processor,config,sessionLifetime)})
            }
        }
    }
    val pageActionId = retakeRequestedId ?: deleteRequestedId
    if (pageActionId != null) {
        val retaking = retakeRequestedId != null
        fun dismissPageAction() { retakeRequestedId = null; deleteRequestedId = null }
        AlertDialog(onDismissRequest=::dismissPageAction,
            title={Text(stringResource(if(retaking) R.string.retake_title else R.string.delete_page_title))},
            text={Text(stringResource(if(retaking) R.string.retake_message else R.string.delete_page_message))},
            confirmButton={TextButton(onClick={
                dismissPageAction()
                try {
                    val index = pages.indexOfFirst { it.id == pageActionId }
                    if (index >= 0) {
                        session.remove(pageActionId); syncPages()
                        selectedId = pages.getOrNull(index.coerceAtMost(pages.lastIndex))?.id
                        if (retaking) {
                            pendingRetakeIndex = index; replaceTargetId = null
                            autoCaptureGate.reset(); stage = Stage.CAPTURE
                        } else if (pages.isEmpty()) stage = Stage.CAPTURE
                    }
                } catch(t: Exception) { error = toScanError(t, ErrorCode.PROCESSING) }
            }) { Text(stringResource(if(retaking) R.string.retake_confirm else R.string.delete_page_confirm)) }},
            dismissButton={TextButton(onClick=::dismissPageAction) {Text(stringResource(R.string.page_action_keep))}})
    }
    if(discardRequested) AlertDialog(onDismissRequest={discardRequested=false},title={Text(stringResource(R.string.discard_title))},
        text={Text(stringResource(R.string.discard_message))},confirmButton={TextButton(onClick={discardRequested=false;deliver(ScanResult.Cancelled)}){Text(stringResource(R.string.discard_confirm))}},
        dismissButton={TextButton(onClick={discardRequested=false}){Text(stringResource(R.string.discard_keep))}})
    error?.let { e ->
        AlertDialog(onDismissRequest = { error = null }, title = { Text(stringResource(R.string.scan_error_title)) },
            text = { Text(userMessage(e)) }, confirmButton = {
                TextButton(onClick = { error = null; deliver(ScanResult.Failed(e)) }) { Text(stringResource(R.string.action_return_error)) }
            }, dismissButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.action_ok)) } })
    }
    if (cameraError && !cameraAllowed) AlertDialog(
        onDismissRequest = { cameraError = false }, title = { Text(stringResource(R.string.camera_unavailable_title)) },
        text = { Text(stringResource(R.string.camera_permission_message)) },
        confirmButton = { TextButton(onClick = { cameraError = false; importLauncher.launch("image/*") }) { Text(stringResource(R.string.action_import)) } },
        dismissButton = { TextButton(onClick = { cameraError = false }) { Text(stringResource(R.string.action_close)) } }
    )
}

private fun updateEdits(page: ScanPage, session: ScanSession,
                       sync: () -> Unit, setError: (ScanError?) -> Unit, edits: Edits) {
    try { session.update(page.id, edits); sync() }
    catch (t: Exception) { setError(toScanError(t, ErrorCode.PROCESSING)) }
}

private fun importPage(
    session: ScanSession,
    processor: ImageProcessor,
    config: ScanConfig,
    source: File,
    replaceId: String?,
    cancellation: Cancellation,
    learnedDetector: LearnedDocumentDetector
): ScanPage {
    val imported = if (replaceId == null) session.`import`(source) else session.replace(replaceId, source)
    val detected = try { if(config.mode == ScanMode.PHOTO) null else if(config.detectionMode == DetectionMode.AI) learnedDetector.detect(imported.original,config,cancellation) else processor.detect(imported.original, config, cancellation) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
    val corners = detected?.corners
    session.update(imported.id, imported.edits.copy(preset=config.defaultPreset,
        corners=if(corners != null && detected.confidence >= 0.6 && Geometry.valid(corners)) corners else Quad.FULL))
    return session.pages.first { it.id == imported.id }
}

/** Defers session cleanup until an in-flight import or export has left its IO section. */
private class SessionLifetime(private val session: ScanSession) {
    private val lock = Any()
    private var active = 0
    private var closeRequested = false
    private data class PreviewKey(val id: String, val original: File, val edits: Edits)
    private val previews = LinkedHashMap<PreviewKey, Bitmap>(16, .75f, true)
    private var previewBytes = 0L
    private val maximumPreviewBytes = 16L * 1024 * 1024

    fun cachedPreview(page: ScanPage): Bitmap? = synchronized(lock) {
        previews[PreviewKey(page.id,page.original,page.edits)]?.takeUnless { it.isRecycled }
    }

    fun cachePreview(page: ScanPage, bitmap: Bitmap) = synchronized(lock) {
        if (closeRequested || bitmap.isRecycled) return@synchronized
        val key = PreviewKey(page.id,page.original,page.edits)
        val obsolete = previews.keys.filter { it.id == page.id }
        obsolete.forEach { previewBytes -= previews.remove(it)!!.allocationByteCount }
        previews[key] = bitmap
        previewBytes += bitmap.allocationByteCount
        while (previewBytes > maximumPreviewBytes && previews.isNotEmpty()) {
            previewBytes -= previews.remove(previews.keys.first())!!.allocationByteCount
        }
        // Evicted images may still be drawn by Compose: release cache references, never recycle them.
    }

    fun retainPreviews(pages: List<ScanPage>) = synchronized(lock) {
        val retained = pages.map { PreviewKey(it.id,it.original,it.edits) }.toSet()
        previews.keys.filter { it !in retained }.forEach { previewBytes -= previews.remove(it)!!.allocationByteCount }
    }

    fun readPages(): List<ScanPage> = synchronized(lock) { session.pages }

    suspend fun <T> run(block: () -> T): T {
        synchronized(lock) {
            check(!closeRequested) { "Scan session is closing" }
            active++
        }
        try {
            return withContext(Dispatchers.IO) { block() }
        } finally {
            synchronized(lock) {
                active--
                if (closeRequested && active == 0) session.close()
            }
        }
    }

    fun closeWhenIdle() = synchronized(lock) {
        closeRequested = true
        previews.clear(); previewBytes = 0
        if (active == 0) session.close()
    }
}

@Composable private fun CaptureStage(context: Context, allowed: Boolean, failed: Boolean, config: ScanConfig,autoCaptureGate:AutoCaptureGate,
    autoCaptureAllowed: Boolean,
    onMode: (ScanMode) -> Unit,
    onDetectionMode: (DetectionMode) -> Unit,
    bitmapDetector: (Bitmap,Cancellation) -> Detection,
    stillDetector: (File,Cancellation) -> Detection,
    cameraHolder: androidx.compose.runtime.MutableState<ScannerCamera?>, onPermission: () -> Unit, onImport: () -> Unit,
    onCapture: (File) -> Unit, onReview: () -> Unit, pages: List<ScanPage>, canAdd: Boolean,
    onPage: (String) -> Unit, onCancel: () -> Unit, pageBitmap: @Composable (ScanPage) -> Bitmap?,
    busy: Boolean, obscured:Boolean, error: String?, onCameraError: (ScanError) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var torch by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var detection by remember { mutableStateOf<Detection?>(null) }
    var autoMode by remember(config.autoCapture) { mutableStateOf(config.autoCapture) }
    var autoPaused by remember { mutableStateOf(false) }
    var takingPhoto by remember { mutableStateOf(false) }
    var showAdded by remember { mutableStateOf(false) }
    var modesExpanded by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }
    var qualityHint by remember { mutableStateOf<String?>(null) }
    val blocked = busy || takingPhoto || !canAdd || obscured || modesExpanded || settingsExpanded
    val latestBitmapDetector = rememberUpdatedState(bitmapDetector)
    val latestStillDetector = rememberUpdatedState(stillDetector)
    val latestCaptureMode = rememberUpdatedState(config.mode)
    val latestBlocked = rememberUpdatedState(blocked)
    val latestOnCapture = rememberUpdatedState(onCapture)
    val latestAuto = rememberUpdatedState(autoMode && !autoPaused)
    val takePhoto: (Boolean) -> Unit = { automatic ->
        if (!latestBlocked.value && cameraReady) {
            takingPhoto = true
            qualityHint = null
            cameraHolder.value?.capture(File(context.cacheDir, "capture-${System.nanoTime()}.jpg"), automatic,
                onRejected = { message -> takingPhoto = false; qualityHint = message }) { saved ->
                latestOnCapture.value(saved)
                takingPhoto = false
            }
        }
    }
    val latestTakePhoto = rememberUpdatedState(takePhoto)
    LaunchedEffect(cameraHolder.value, blocked, autoMode, autoPaused, allowed, failed, config.mode) {
        cameraHolder.value?.setAutoCaptureEnabled(allowed && !failed && !blocked && autoMode && !autoPaused)
    }
    LaunchedEffect(failed) { if(failed) { cameraHolder.value?.close();cameraHolder.value=null } }
    LaunchedEffect(pages.size) {
        if (pages.isNotEmpty()) { showAdded = true; delay(1800); showAdded = false }
    }
    LaunchedEffect(qualityHint) { if(qualityHint != null) { delay(5000); qualityHint=null } }
    if(settingsExpanded) AlertDialog(onDismissRequest={settingsExpanded=false},
        title={Text(stringResource(R.string.scanner_settings))},
        text={Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.detection_mode),style=MaterialTheme.typography.titleMedium)
            DetectionMode.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable {
                    onDetectionMode(mode); cameraReady=false; detection=null; settingsExpanded=false
                }.testTag("detection-mode-${mode.name.lowercase(java.util.Locale.ROOT)}").semantics { selected=config.detectionMode==mode },
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.RadioButton(selected=config.detectionMode==mode,onClick=null)
                    Text(stringResource(if(mode==DetectionMode.STANDARD) R.string.detection_standard else R.string.detection_ai))
                }
            }
            Text(stringResource(R.string.detection_ai_description),style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton(onClick={settingsExpanded=false}) {Text(stringResource(R.string.capture_done))}})
    CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
        Column(Modifier.fillMaxSize().testTag("capture-screen")) {
            Box(Modifier.fillMaxWidth().weight(1f).testTag("capture-preview").clip(RoundedCornerShape(bottomStart=32.dp,bottomEnd=32.dp)).background(Color.Black)) {
                if (allowed && !failed) {
                    AndroidView(factory = { ctx -> PreviewView(ctx).also { it.scaleType = PreviewView.ScaleType.FILL_CENTER } },
                        modifier = Modifier.fillMaxSize()) { view ->
                        if (cameraHolder.value == null) {
                            val camera = ScannerCamera(context, lifecycleOwner, view, config.copy(autoCapture=autoCaptureAllowed),
                                onAnalysis = { result -> detection = if(latestCaptureMode.value==ScanMode.PHOTO) result.copy(corners=null) else result; cameraReady = true },
                                onAutoCapture = { if (latestAuto.value && !latestBlocked.value) latestTakePhoto.value(true) },
                                onError = { takingPhoto = false; cameraReady = false; onCameraError(it) },autoCaptureGate=autoCaptureGate,
                                bitmapDetector={bitmap,cancel -> latestBitmapDetector.value(bitmap,cancel)},
                                stillDetector={file,cancel -> latestStillDetector.value(file,cancel)})
                            camera.setAutoCaptureEnabled(!blocked && autoMode && !autoPaused)
                            cameraHolder.value = camera
                            camera.bind()
                        }
                    }
                    Canvas(Modifier.fillMaxSize().pointerInput(cameraHolder.value,blocked) {
                        detectTapGestures { position -> if(!blocked) cameraHolder.value?.focus(position.x, position.y) }
                    }) {
                        val quad = if(config.mode == ScanMode.PHOTO) null else detection?.corners
                        if(quad == null) return@Canvas
                        val points = quad.points.mapNotNull { cameraHolder.value?.normalizedToPreview(it) }
                        if (points.size == 4) {
                            val path = Path().apply { moveTo(points[0].x.toFloat(), points[0].y.toFloat()); points.drop(1).forEach { lineTo(it.x.toFloat(),it.y.toFloat()) }; close() }
                            drawPath(path,Color(0x223DA7FF)); drawPath(path,Color(0xFF8BC3FF),style=Stroke(2.dp.toPx()))
                        }
                    }
                } else {
                    Column(Modifier.align(Alignment.Center).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(if (failed) R.string.camera_unavailable_title else R.string.capture_allow),color=Color.White)
                        Button(onClick=onPermission,enabled=!busy) { Text(stringResource(R.string.action_enable_camera)) }
                        TextButton(onClick=onImport,enabled=!blocked) { Text(stringResource(R.string.action_import)) }
                    }
                }
                Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding().padding(8.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    ScannerIconButton(ScannerGlyph.CLOSE,stringResource(R.string.action_cancel),onCancel,enabled=!busy)
                    Text(stringResource(R.string.scanner_title),color=Color.White,style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                    ScannerIconButton(ScannerGlyph.FLASH,stringResource(if(torch) R.string.action_torch_off else R.string.action_torch_on),{ torch=!torch;cameraHolder.value?.setTorch(torch) },enabled=cameraReady&&!blocked)
                    ScannerIconButton(ScannerGlyph.SETTINGS,stringResource(R.string.scanner_settings),{ settingsExpanded=true },enabled=!blocked)
                }
                if (allowed && !failed) Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                    val status = when {
                        busy || takingPhoto -> R.string.capture_saving
                        !canAdd -> R.string.capture_limit
                        showAdded -> R.string.capture_added
                        !cameraReady -> R.string.camera_starting
                        config.mode == ScanMode.PHOTO -> R.string.capture_photo_hint
                        !autoMode -> R.string.capture_manual_hint
                        autoPaused -> R.string.capture_paused
                        detection?.corners == null -> R.string.capture_find
                        else -> cameraGuidance(detection)
                    }
                    Column(horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(qualityHint ?: stringResource(status),color=Color.White,
                        style=MaterialTheme.typography.bodySmall,
                        modifier=Modifier.padding(bottom=8.dp).testTag("capture-guidance"))
                    Surface(onClick={modesExpanded=true},enabled=!busy&&!takingPhoto&&!obscured,
                        modifier=Modifier.testTag("capture-mode-button"),color=Color(0xB3222429),shape=RoundedCornerShape(24.dp)) {
                        val guidance = stringResource(status)
                        Text(stringResource(captureModePosition(config.mode)),
                            color=Color.White,style=MaterialTheme.typography.bodyMedium,
                            modifier=Modifier.padding(horizontal=16.dp,vertical=12.dp).semantics { liveRegion=LiveRegionMode.Polite; stateDescription=guidance })
                    }
                    }
                    DropdownMenu(expanded=modesExpanded,onDismissRequest={modesExpanded=false}) {
                        ScanMode.entries.forEach { mode ->
                            DropdownMenuItem(text={Text(stringResource(captureModeName(mode)))},
                                trailingIcon=if(mode==config.mode) {{Text("\u2713")}} else null,
                                modifier=Modifier.testTag("capture-mode-${mode.name.lowercase(java.util.Locale.ROOT)}").semantics {selected=config.mode==mode},
                                onClick={modesExpanded=false;onMode(mode)})
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().background(Color(0xFF17191E)).navigationBarsPadding().padding(top=16.dp,bottom=24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                    Surface(shape=CircleShape,color=Color(0xFF075A7B),contentColor=Color(0xFF9ED8FF)) { ScannerIconButton(ScannerGlyph.IMPORT,stringResource(R.string.action_import),onImport,enabled=!blocked) }
                    Surface(shape=CircleShape,color=Color.White,contentColor=Color.Black,modifier=Modifier.size(76.dp).border(4.dp,Color(0xFF84BFFF),CircleShape)) {
                        Box(contentAlignment=Alignment.Center) {
                            if (busy || takingPhoto) CircularProgressIndicator(Modifier.size(32.dp),color=Color.Black,strokeWidth=2.dp)
                            else {
                                val shutterLabel = stringResource(R.string.action_shutter)
                                IconButton(onClick={takePhoto(false)},enabled=cameraReady&&!blocked,
                                    modifier=Modifier.fillMaxSize().testTag("manual-shutter").semantics { contentDescription=shutterLabel }) {}
                            }
                        }
                    }
                    CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color(0xFF86CAFF)) {
                        Box(Modifier.size(56.dp),contentAlignment=Alignment.Center) {
                            if(allowed&&!failed&&cameraReady&&!blocked&&autoMode&&!autoPaused) AutoScanningRing()
                            ScannerIconButton(if(autoMode&&!autoPaused) ScannerGlyph.STOP else ScannerGlyph.PLAY,
                                stringResource(if(autoMode&&!autoPaused) R.string.capture_pause else R.string.capture_resume),
                                { autoMode=true;autoPaused=!autoPaused },enabled=config.autoCapture&&!busy,modifier=Modifier.size(48.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top=8.dp,start=12.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    LazyRow(Modifier.weight(1f).height(80.dp).testTag("capture-pages"),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(pages,key={_,p->p.id}) { index,page ->
                            val label=stringResource(R.string.capture_edit_page,index+1)
                            Box(Modifier.size(56.dp,76.dp).clip(RoundedCornerShape(8.dp)).border(1.dp,Color(0xFF8BC3FF),RoundedCornerShape(8.dp))
                                .clickable(enabled=!busy&&!takingPhoto){onPage(page.id)}.semantics{contentDescription=label}) {
                                val bitmap=pageBitmap(page)
                                if(bitmap!=null) Image(bitmap.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                                Text("${index+1}",color=Color.White,style=MaterialTheme.typography.labelSmall,modifier=Modifier.align(Alignment.BottomEnd).background(Color(0xAA000000)).padding(4.dp))
                            }
                        }
                    }
                    Button(onClick=onReview,enabled=pages.isNotEmpty()&&!busy&&!takingPhoto,modifier=Modifier.padding(start=8.dp).heightIn(min=48.dp)) { Text(stringResource(R.string.capture_next)) }
                }
            }
        }
    }
}

private fun captureModePosition(mode: ScanMode) = when(mode) {
    ScanMode.DOCUMENT -> R.string.position_document
    ScanMode.PHOTO -> R.string.position_photo
    ScanMode.RECEIPT -> R.string.position_receipt
    ScanMode.CARD -> R.string.position_card
}

private fun captureModeName(mode: ScanMode) = when(mode) {
    ScanMode.DOCUMENT -> R.string.capture_mode_document
    ScanMode.PHOTO -> R.string.capture_mode_photo
    ScanMode.RECEIPT -> R.string.capture_mode_receipt
    ScanMode.CARD -> R.string.capture_mode_card
}

@Composable private fun AutoScanningRing() {
    val transition = rememberInfiniteTransition(label="Automatic scanning")
    val angle by transition.animateFloat(initialValue=0f,targetValue=360f,
        animationSpec=infiniteRepeatable(animation=tween(1400,easing=LinearEasing)),label="Search rotation")
    val label = stringResource(R.string.capture_search_active)
    Canvas(Modifier.size(54.dp).testTag("auto-scanning-indicator").semantics {contentDescription=label}) {
        val inset = 3.dp.toPx()
        drawArc(Color(0xFF86CAFF),angle,280f,false,Offset(inset,inset),
            androidx.compose.ui.geometry.Size(size.width-inset*2,size.height-inset*2),style=Stroke(2.dp.toPx(),cap=StrokeCap.Round))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun EditStage(page: ScanPage, processor: ImageProcessor, config: ScanConfig, sessionLifetime:SessionLifetime,
    onEdits:(Edits)->Unit,onBack:()->Unit,onNext:()->Unit) {
    val bitmap=rememberBitmap(page.original)
    val scope=rememberCoroutineScope()
    var edits by remember(page.id,page.original) { mutableStateOf(page.edits) }
    var selectedCorner by remember { mutableIntStateOf(0) }
    var precise by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var detecting by remember { mutableStateOf(false) }
    var noBoundary by remember { mutableStateOf(false) }
    val turns=edits.rotationQuarterTurns
    val displayBitmap=remember(bitmap,turns) { bitmap?.let { source -> if(turns==0) source else
        Bitmap.createBitmap(source,0,0,source.width,source.height,Matrix().apply{postRotate(turns*90f)},true) } }
    val indices=List(4){(it-turns+4)%4}
    val points=indices.map{Geometry.rotate(edits.corners.points[it],turns)}
    val displayQuad=Quad(points[0],points[1],points[2],points[3])
    fun setQuad(q:Quad) {
        val original=edits.corners.points.toMutableList()
        q.points.forEachIndexed { index,point -> original[indices[index]]=Geometry.rotate(point,-turns) }
        val candidate=Quad(original[0],original[1],original[2],original[3])
        if(Geometry.valid(candidate)) { edits=edits.copy(corners=candidate);onEdits(edits) }
    }
    fun nudge(x:Double,y:Double) {
        val current=displayQuad.points[selectedCorner]
        val adjusted=withCorner(edits.copy(corners=displayQuad),selectedCorner,Point((current.x+x).coerceIn(0.0,1.0),(current.y+y).coerceIn(0.0,1.0)))
        setQuad(adjusted.corners)
    }
    Column(Modifier.fillMaxSize().testTag("crop-screen")) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
            displayBitmap?.let { image -> BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
                val aspect=image.width.toFloat()/image.height
                val fitted=if(maxWidth.value/maxHeight.value>aspect) Modifier.height(maxHeight).width(maxHeight*aspect) else Modifier.width(maxWidth).height(maxWidth/aspect)
                CropCanvas(image,displayQuad,selectedCorner,{selectedCorner=it},{setQuad(it)},fitted.align(Alignment.Center),{dragging=it})
            } }
            ScannerIconButton(ScannerGlyph.CLOSE,stringResource(R.string.action_back),onBack,Modifier.align(Alignment.TopStart),enabled=!detecting)
            ScannerIconButton(ScannerGlyph.SETTINGS,stringResource(R.string.capture_precision),{precise=true},Modifier.align(Alignment.TopEnd),enabled=!detecting)
            if(dragging&&displayBitmap!=null) Box(Modifier.align(Alignment.TopStart).padding(start=56.dp,top=48.dp)) { CornerMagnifier(displayBitmap,displayQuad.points[selectedCorner]) }
            if(detecting) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        if(noBoundary) Text(stringResource(R.string.editor_no_boundary),modifier=Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilledTonalButton(onClick={
                detecting=true;noBoundary=false
                scope.launch { try {
                    val job=currentCoroutineContext()[Job]
                    val found=sessionLifetime.run { processor.detect(page.original,config,Cancellation{if(job?.isActive!=true) throw CancellationException()}) }
                    val corners=found.corners
                    if(corners!=null&&found.confidence>=.6&&Geometry.valid(corners)) { edits=edits.copy(corners=corners);onEdits(edits) } else noBoundary=true
                } catch(cancelled:CancellationException) { throw cancelled } catch(_:Exception) { noBoundary=true } finally { detecting=false } }
            },enabled=!detecting,modifier=Modifier.heightIn(min=48.dp)) { Text(stringResource(R.string.editor_auto_crop)) }
            androidx.compose.material3.FilledTonalButton(onClick={edits=edits.copy(corners=Quad.FULL);onEdits(edits)},enabled=!detecting,modifier=Modifier.heightIn(min=48.dp)) { Text(stringResource(R.string.editor_no_crop)) }
            androidx.compose.material3.FilledTonalButton(onClick={edits=edits.copy(rotationQuarterTurns=(turns+1)%4);onEdits(edits)},enabled=!detecting,modifier=Modifier.heightIn(min=48.dp)) { Text(stringResource(R.string.editor_rotate)) }
        }
        Button(onClick=onNext,enabled=!detecting,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp).heightIn(min=56.dp)) { Text(stringResource(R.string.editor_apply)) }
    }
    if(precise) ModalBottomSheet(onDismissRequest={precise=false}) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.capture_precision),style=MaterialTheme.typography.titleMedium)
            for(row in 0..1) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                for(index in row*2..row*2+1) FilterChip(selected=index==selectedCorner,onClick={selectedCorner=index},label={Text(stringResource(cornerName(index)))},modifier=Modifier.weight(1f))
            }
            Text(stringResource(R.string.selected_corner,stringResource(cornerName(selectedCorner))))
            val point=displayQuad.points[selectedCorner]
            Text(stringResource(R.string.corner_horizontal))
            val horizontalLabel=stringResource(R.string.corner_horizontal)+", "+stringResource(cornerName(selectedCorner))
            Slider(point.x.toFloat(),{value->setQuad(withCorner(edits.copy(corners=displayQuad),selectedCorner,point.copy(x=value.toDouble())).corners)},valueRange=0f..1f,modifier=Modifier.semantics{contentDescription=horizontalLabel})
            Text(stringResource(R.string.corner_vertical))
            val verticalLabel=stringResource(R.string.corner_vertical)+", "+stringResource(cornerName(selectedCorner))
            Slider(point.y.toFloat(),{value->setQuad(withCorner(edits.copy(corners=displayQuad),selectedCorner,point.copy(y=value.toDouble())).corners)},valueRange=0f..1f,modifier=Modifier.semantics{contentDescription=verticalLabel})
            listOf(Triple(R.string.nudge_left,-.0025,0.0),Triple(R.string.nudge_right,.0025,0.0),Triple(R.string.nudge_up,0.0,-.0025),Triple(R.string.nudge_down,0.0,.0025)).chunked(2).forEach { row->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {row.forEach { (label,x,y)->OutlinedButton(onClick={nudge(x,y)},modifier=Modifier.weight(1f).heightIn(min=48.dp)){Text(stringResource(label))} }}
            }
            Button(onClick={precise=false},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(stringResource(R.string.capture_done))}
        }
    }
}

@Composable private fun CornerMagnifier(bitmap: Bitmap, point: Point) {
    val centerColor = MaterialTheme.colorScheme.error
    val borderColor = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.magnifier_label), style = MaterialTheme.typography.labelMedium)
        Canvas(Modifier.size(112.dp).clip(CircleShape).border(2.dp, borderColor, CircleShape)) {
            val cropW = minOf(bitmap.width, (bitmap.width / 4).coerceAtLeast(24))
            val cropH = minOf(bitmap.height, (bitmap.height / 4).coerceAtLeast(24))
            val centerX = (point.x * bitmap.width).toInt()
            val centerY = (point.y * bitmap.height).toInt()
            val left = (centerX - cropW / 2).coerceIn(0, (bitmap.width - cropW).coerceAtLeast(0))
            val top = (centerY - cropH / 2).coerceIn(0, (bitmap.height - cropH).coerceAtLeast(0))
            drawImage(bitmap.asImageBitmap(), srcOffset = IntOffset(left, top), srcSize = IntSize(cropW, cropH), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            val focus = Offset(((centerX - left).toFloat() / cropW) * size.width, ((centerY - top).toFloat() / cropH) * size.height)
            drawCircle(centerColor, radius = 4.dp.toPx(), center = focus)
        }
    }
}

@Composable private fun CropCanvas(bitmap: Bitmap, quad: Quad, selected: Int, onSelectCorner: (Int) -> Unit, onCorners: (Quad) -> Unit, modifier:Modifier=Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat()/bitmap.height), onDragging:(Boolean)->Unit={}) {
    val latestQuad = rememberUpdatedState(quad)
    Canvas(modifier
        .clip(RoundedCornerShape(12.dp)).background(Color.Black).pointerInput(bitmap) {
            var dragIndex = 0
            var currentQuad = latestQuad.value
            detectDragGestures(onDragEnd={onDragging(false)},onDragCancel={onDragging(false)},onDragStart = { pos ->
                onDragging(true)
                currentQuad = latestQuad.value
                dragIndex = currentQuad.points.indices.minByOrNull { i -> val p = currentQuad.points[i]; (p.x * size.width - pos.x) * (p.x * size.width - pos.x) + (p.y * size.height - pos.y) * (p.y * size.height - pos.y) } ?: 0
                onSelectCorner(dragIndex)
            }, onDrag = { change, _ ->
                change.consume()
                val points = currentQuad.points.toMutableList()
                points[dragIndex] = Point((change.position.x / size.width).coerceIn(0f, 1f).toDouble(), (change.position.y / size.height).coerceIn(0f, 1f).toDouble())
                val candidate = Quad(points[0], points[1], points[2], points[3])
                if (Geometry.valid(candidate)) { currentQuad = candidate; onCorners(candidate) }
            })
        }) {
        drawImage(bitmap.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        val pts = quad.points.map { Offset((it.x * size.width).toFloat(), (it.y * size.height).toFloat()) }
        val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
        drawPath(path, Color(0x4433B5E5)); drawPath(path, Color(0xFFFFC857), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
        pts.forEachIndexed { i, p -> drawCircle(if (i == selected) Color.White else Color(0xFFFFC857), radius = if (i == selected) 13.dp.toPx() else 10.dp.toPx(), center = p); drawCircle(Color.Black, radius = 5.dp.toPx(), center = p) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AppearanceStage(page:ScanPage,processor:ImageProcessor,config:ScanConfig,sessionLifetime:SessionLifetime,
    onPreset:(Preset)->Unit,onBrightness:(Float)->Unit,onContrast:(Float)->Unit,onIllumination:(Float)->Unit,onReset:()->Unit,onRotate:()->Unit,
    onApplyAll:()->Unit,onBack:()->Unit,onNext:()->Unit) {
    var tuning by remember { mutableStateOf(false) }
    var compareOriginal by remember { mutableStateOf(false) }
    val adjusted=rememberRenderedPreview(page,processor,config,sessionLifetime)
    val original=rememberRenderedPreview(page.copy(edits=page.edits.copy(preset=Preset.ORIGINAL,brightness=0.0,contrast=1.0,illumination=0.0)),processor,config,sessionLifetime)
    val preview=if(compareOriginal) original else adjusted
    Column(Modifier.fillMaxSize().testTag("filters-screen")) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp).background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center) {
            if(preview!=null) ZoomablePageImage(preview,stringResource(R.string.page_image_description),Triple(page.id,page.edits.corners,page.edits.rotationQuarterTurns),Modifier.fillMaxSize().padding(8.dp))
            else CircularProgressIndicator()
            ScannerIconButton(ScannerGlyph.CLOSE,stringResource(R.string.action_back),onBack,Modifier.align(Alignment.TopStart))
            ScannerIconButton(ScannerGlyph.SETTINGS,stringResource(R.string.editor_adjustments),{tuning=true},Modifier.align(Alignment.TopEnd))
        }
        LazyRow(Modifier.fillMaxWidth().testTag("appearance-presets").padding(vertical=8.dp),contentPadding=androidx.compose.foundation.layout.PaddingValues(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            itemsIndexed(listOf(Preset.AUTO)+Preset.entries.filter { it != Preset.AUTO }) {_,preset->
                val label=stringResource(presetName(preset))
                Column(Modifier.width(82.dp).clickable{onPreset(preset)}.semantics{contentDescription=label;selected=page.edits.preset==preset},horizontalAlignment=Alignment.CenterHorizontally) {
                    Canvas(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).border(if(page.edits.preset==preset)4.dp else 1.dp,if(page.edits.preset==preset)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,RoundedCornerShape(10.dp))) {
                        drawRect(if(preset==Preset.BLACK_WHITE||preset==Preset.GRAYSCALE) Color.White else Color(0xFFFFF3CF))
                        val ink=if(preset==Preset.BLACK_WHITE) Color.Black else Color(0xFF848A91)
                        for(row in 0..3) drawLine(ink,Offset(size.width*.2f,size.height*(.4f+row*.13f)),Offset(size.width*.8f,size.height*(.4f+row*.13f)),3.dp.toPx())
                        drawRect(if(preset==Preset.GRAYSCALE||preset==Preset.BLACK_WHITE) Color.Gray else Color(0xFF76CDBD),topLeft=Offset(size.width*.2f,size.height*.13f),size=androidx.compose.ui.geometry.Size(size.width*.33f,size.height*.2f))
                    }
                    Text(label,style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(top=6.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick=onApplyAll,modifier=Modifier.weight(1f).heightIn(min=56.dp)){Text(stringResource(R.string.editor_apply_all))}
            Button(onClick=onNext,modifier=Modifier.weight(1f).heightIn(min=56.dp)){Text(stringResource(R.string.editor_apply))}
        }
    }
    if(tuning) ModalBottomSheet(onDismissRequest={tuning=false}) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.editor_adjustments),style=MaterialTheme.typography.titleMedium)
            TextButton(onClick={compareOriginal=!compareOriginal},modifier=Modifier.heightIn(min=48.dp)){Text(stringResource(if(compareOriginal)R.string.action_show_adjusted else R.string.action_compare_original))}
            Text(stringResource(R.string.brightness_value,page.edits.brightness.toFloat()))
            Slider(page.edits.brightness.toFloat(),onBrightness,valueRange=-.25f.. .25f)
            Text(stringResource(R.string.contrast_value,page.edits.contrast.toFloat()))
            Slider(page.edits.contrast.toFloat(),onContrast,valueRange=.75f..1.5f)
            Text(stringResource(R.string.illumination_value,page.edits.illumination.toFloat()))
            Slider(page.edits.illumination.toFloat(),onIllumination,valueRange=0f.. .5f)
            TextButton(onClick=onReset,modifier=Modifier.heightIn(min=48.dp)){Text(stringResource(R.string.action_reset_appearance))}
            Button(onClick={tuning=false},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(stringResource(R.string.capture_done))}
        }
    }
}

@Composable private fun rememberRenderedPreview(page: ScanPage, processor: ImageProcessor, config: ScanConfig, sessionLifetime: SessionLifetime): Bitmap? {
    var bitmap by remember(page.id, page.original) { mutableStateOf(sessionLifetime.cachedPreview(page)) }
    LaunchedEffect(page.id, page.original, page.edits) {
        sessionLifetime.cachedPreview(page)?.let { bitmap = it; return@LaunchedEffect }
        delay(180)
        val preview = File(page.original.parentFile, "preview-${page.id}-${System.nanoTime()}.png")
        val pendingBitmap = java.util.concurrent.atomic.AtomicReference<Bitmap?>(null)
        try {
            val job = currentCoroutineContext()[Job]
            sessionLifetime.run {
                processor.render(page.original, page.edits, preview, ExportFormat.PNG, config.copy(maxOutputDimension = minOf(config.maxOutputDimension, 1024)),
                    Cancellation { if (job?.isActive != true) throw CancellationException() })
                decodeThumbnail(preview, maxEdge = 1024).also { pendingBitmap.set(it) }
            }.also { decoded ->
                if (currentCoroutineContext().isActive) {
                    if (decoded != null) sessionLifetime.cachePreview(page, decoded)
                    bitmap = decoded
                    if (decoded != null) pendingBitmap.compareAndSet(decoded, null)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            bitmap = null
        } finally {
            pendingBitmap.getAndSet(null)?.takeUnless { it.isRecycled }?.recycle()
            preview.delete()
        }
    }
    return bitmap
}

@Composable private fun rememberBitmap(file: File, maxEdge: Int = 1800): Bitmap? {
    var bitmap by remember(file, maxEdge) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file, maxEdge) {
        val pendingBitmap = java.util.concurrent.atomic.AtomicReference<Bitmap?>(null)
        try {
            val decoded = withContext(Dispatchers.IO) { decodeThumbnail(file, maxEdge).also { pendingBitmap.set(it) } }
            if (currentCoroutineContext().isActive) {
                bitmap = decoded
                if (decoded != null) pendingBitmap.compareAndSet(decoded, null)
            }
        } finally {
            pendingBitmap.getAndSet(null)?.takeUnless { it.isRecycled }?.recycle()
        }
    }
    return bitmap
}

private fun decodeThumbnail(file: File, maxEdge: Int = 1800): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) sample *= 2
    val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }) ?: return null
    return try {
        val exif = ExifInterface(file)
        val degrees = exif.rotationDegrees
        val flip = exif.isFlipped
        if (degrees == 0 && !flip) decoded else {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()); if (flip) postScale(-1f, 1f) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
        }
    } catch (_: Exception) { decoded }
}

private fun cameraGuidance(detection: Detection?): Int = when {
    detection == null -> R.string.guidance_find_page
    detection.corners == null -> R.string.guidance_find_edges
    detection.brightness < 0.18 -> R.string.guidance_more_light
    detection.brightness > 0.90 -> R.string.guidance_reduce_glare
    detection.sharpness < 60.0 -> R.string.guidance_hold_steady
    detection.confidence < 0.72 -> R.string.guidance_move_closer
    else -> R.string.guidance_ready_hold
}

private fun deleteUndeliveredOutput(result: ScanResult.Completed, destination: File) {
    val files = result.output.files
    val directory = files.firstOrNull()?.parentFile?.canonicalFile ?: return
    val root = destination.canonicalFile
    if (directory.parentFile == root && directory.name.startsWith("scan-") && files.all { it.canonicalFile.parentFile == directory }) {
        directory.deleteRecursively()
    }
}

private fun withCorner(edits: Edits, index: Int, point: Point): Edits {
    val points = edits.corners.points.toMutableList().also { it[index] = point }
    val q = Quad(points[0], points[1], points[2], points[3])
    return if (Geometry.valid(q)) edits.copy(corners = q) else edits
}

private fun cornerName(i: Int) = when(i) { 0 -> R.string.corner_top_left; 1 -> R.string.corner_top_right; 2 -> R.string.corner_bottom_right; else -> R.string.corner_bottom_left }
private fun presetName(p: Preset) = when(p) { Preset.AUTO -> R.string.preset_auto; Preset.ORIGINAL -> R.string.preset_original; Preset.COLOR_DOCUMENT -> R.string.preset_color; Preset.GRAYSCALE -> R.string.preset_grayscale; Preset.BLACK_WHITE -> R.string.preset_black_white; Preset.PHOTO -> R.string.preset_photo }

private fun copyBoundedUri(context: Context, uri: Uri, source: File, cancellation: Cancellation) {
    try {
        val input = context.contentResolver.openInputStream(uri) ?: throw ScanException(ScanError(ErrorCode.INVALID_IMAGE, "Unable to open image"))
        input.use { stream -> FileOutputStream(source).use { out ->
            val buffer = ByteArray(32 * 1024); var total = 0L
            while (true) { cancellation.check(); val count = stream.read(buffer); if (count < 0) break; total += count
                if (total > MAX_IMPORT_BYTES) throw ScanException(ScanError(ErrorCode.RESOURCE_LIMIT, "Image exceeds import size limit"))
                out.write(buffer, 0, count)
            }
        } }
    } catch (t: Throwable) { source.delete(); throw t }
}

private fun toScanError(t: Throwable, fallback: ErrorCode) = when (t) {
    is ScanException -> t.error
    is CancellationException -> ScanError(fallback, "Operation cancelled")
    else -> ScanError(fallback, t.message ?: "Operation failed")
}

@Composable private fun userMessage(error: ScanError): String = stringResource(when(error.code) {
    ErrorCode.PERMISSION_DENIED -> R.string.error_permission
    ErrorCode.INVALID_IMAGE -> R.string.error_invalid_image
    ErrorCode.INVALID_GEOMETRY -> R.string.error_geometry
    ErrorCode.RESOURCE_LIMIT -> R.string.error_resource_limit
    ErrorCode.PROCESSING -> R.string.error_processing
    ErrorCode.CAMERA -> R.string.error_camera
    ErrorCode.EXPORT -> R.string.error_export
    ErrorCode.STORAGE -> R.string.error_storage
})


