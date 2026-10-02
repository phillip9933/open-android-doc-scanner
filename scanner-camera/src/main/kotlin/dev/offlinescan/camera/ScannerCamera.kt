package dev.offlinescan.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.View
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import dev.offlinescan.core.Cancellation
import dev.offlinescan.core.Detection
import dev.offlinescan.core.ErrorCode
import dev.offlinescan.core.Geometry
import dev.offlinescan.core.Quad
import dev.offlinescan.core.Point
import dev.offlinescan.core.ScanConfig
import dev.offlinescan.core.ScanError
import dev.offlinescan.processing.OpenCvProcessor
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Lifecycle-bound rear-camera scanner. Request CAMERA permission before calling [bind]. */
class ScannerCamera @JvmOverloads constructor(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val config: ScanConfig,
    private val onAnalysis: (Detection) -> Unit,
    private val onAutoCapture: () -> Unit,
    private val onError: (ScanError) -> Unit,
    autoCaptureGate: AutoCaptureGate = AutoCaptureGate(),
    private val bitmapDetector: ((Bitmap, Cancellation) -> Detection)? = null,
    private val stillDetector: ((File, Cancellation) -> Detection)? = null
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "offline-scan-analysis") }
    private val closed = AtomicBoolean(false)
    private val active = AtomicBoolean(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    private val generation = AtomicLong(0)
    private val deliveryPending = AtomicBoolean(false)
    private val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val throttle = AnalysisThrottle()
    private val gate = autoCaptureGate
    private val outlineTracker = OutlineTracker()
    private var autoCaptureEnabled = config.autoCapture
    private var latestAnalysisSignature: List<Double>? = null
    @Volatile private var latestDetection: Detection? = null
    private var latestDetectionTime = 0L
    private val processor by lazy { OpenCvProcessor() }
    private var provider: ProcessCameraProvider? = null
    private var requestingProvider = false
    private var bindingRequested = false
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var boundWidth = 0
    private var boundHeight = 0
    private var boundRotation = -1
    private var boundScaleType: PreviewView.ScaleType? = null
    private val captureLock = Any()
    @Volatile private var pendingCapture: PendingCapture? = null
    private class PendingCapture(val staging: File, val destination: File, val onSaved: (File) -> Unit,
        val automatic: Boolean, val reference: Detection?, val epoch: Long, val onRejected: (String) -> Unit) {
        val files = mutableListOf(staging)
        var bestFile: File? = null
        var bestQuality: CaptureQualityPolicy.Sample? = null
        var shotCount = 0
        @Volatile var cancelled = false
        var published = false
        var delivered = false
    }
    @Volatile var analysisImageSize: Size? = null
        private set

    private val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { active.set(true); throttle.reset() }
        override fun onStop(owner: LifecycleOwner) {
            interruptPendingCapture()
            active.set(false); generation.incrementAndGet(); gate.pause(); throttle.reset()
            outlineTracker.reset()
            latestAnalysisSignature = null
        }
        override fun onDestroy(owner: LifecycleOwner) { close() }
    }
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (bindingRequested && !closed.get()) bindWhenReady()
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (previewView.display?.displayId == displayId && bindingRequested && !closed.get()) bindWhenReady()
        }
    }

    init {
        onMain {
            if (!closed.get()) {
                previewView.addOnLayoutChangeListener(layoutListener)
                displayManager.registerDisplayListener(displayListener, mainHandler)
                lifecycleOwner.lifecycle.addObserver(observer)
            }
        }
    }

    /** Safe before layout; binding starts after PreviewView has a valid shared viewport. */
    fun bind() = onMain {
        if (closed.get()) return@onMain
        bindingRequested = true
        bindWhenReady()
    }

    /** Pause/resume automatic shutter requests without rebinding camera use cases. */
    fun setAutoCaptureEnabled(enabled: Boolean) = onMain {
        if (closed.get()) return@onMain
        val effective = config.autoCapture && enabled
        if (autoCaptureEnabled != effective) {
            autoCaptureEnabled = effective
            gate.pause()
        }
    }

    private fun bindWhenReady() {
        if (closed.get() || !bindingRequested || previewView.width <= 0 || previewView.height <= 0) return
        val viewport = previewView.viewPort ?: return
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        if (camera != null && boundWidth == previewView.width && boundHeight == previewView.height &&
            boundRotation == rotation && boundScaleType == previewView.scaleType) return
        val available = provider
        if (available == null) {
            if (requestingProvider) return
            requestingProvider = true
            val future = ProcessCameraProvider.getInstance(appContext)
            future.addListener({
                requestingProvider = false
                if (!closed.get()) {
                    try { provider = future.get(); bindWhenReady() }
                    catch (error: Exception) { report("Unable to initialize the camera", error) }
                }
            }, mainExecutor)
            return
        }
        try {
            interruptPendingCapture()
            unbindOwnedCases()
            generation.incrementAndGet()
            analysisImageSize = null
            latestAnalysisSignature = null
            gate.pause()
            outlineTracker.reset()
            throttle.reset()
            val newPreview = Preview.Builder().setTargetRotation(rotation).build()
            val newCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(config.jpegQuality)
                .setTargetRotation(rotation)
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build())
                .build()
            val newAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(rotation)
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(960, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                .build()
            preview = newPreview; imageCapture = newCapture; analysis = newAnalysis
            newPreview.setSurfaceProvider(previewView.surfaceProvider)
            newAnalysis.setAnalyzer(worker, ::analyze)
            val group = UseCaseGroup.Builder().setViewPort(viewport)
                .addUseCase(newPreview).addUseCase(newCapture).addUseCase(newAnalysis).build()
            camera = available.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
            boundWidth = previewView.width; boundHeight = previewView.height; boundRotation = rotation
            boundScaleType = previewView.scaleType
        } catch (error: Exception) {
            unbindOwnedCases()
            report("Unable to bind the rear camera", error)
        }
    }

    private fun analyze(frame: ImageProxy) {
        val epoch = generation.get()
        var bitmap: Bitmap? = null
        try {
            if (closed.get() || !active.get() || !throttle.accept(SystemClock.elapsedRealtime())) return
            val raw = frame.toBitmap()
            try {
                val crop = frame.cropRect
                val cropped = Bitmap.createBitmap(raw, crop.left, crop.top, crop.width(), crop.height())
                try {
                    bitmap = if (frame.imageInfo.rotationDegrees == 0) cropped.copy(Bitmap.Config.ARGB_8888, false)
                    else Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height,
                        Matrix().apply { postRotate(frame.imageInfo.rotationDegrees.toFloat()) }, false)
                } finally { if (cropped !== raw && cropped !== bitmap) cropped.recycle() }
            } finally { if (raw !== bitmap) raw.recycle() }
        } catch (error: Exception) {
            if (!closed.get() && active.get()) report("Unable to read the camera frame", error)
            return
        } finally { frame.close() }
        val upright = bitmap ?: return
        try {
            val cancel = Cancellation {
                if (closed.get() || !active.get() || generation.get() != epoch) throw CancellationException()
            }
            cancel.check()
            val detection = bitmapDetector?.invoke(upright, cancel) ?: processor.detectBitmap(upright, cancel)
            val size = Size(upright.width, upright.height)
            cancel.check()
            if (deliveryPending.compareAndSet(false, true)) mainExecutor.execute {
                try {
                    if (!closed.get() && active.get() && generation.get() == epoch) {
                        // A scale change may not change view bounds, so layout alone cannot detect it.
                        if (boundScaleType != previewView.scaleType) {
                            bindWhenReady()
                            return@execute
                        }
                        if (analysisImageSize != null && analysisImageSize != size) outlineTracker.reset()
                        analysisImageSize = size
                        latestAnalysisSignature = detection.sceneSignature.toList()
                        latestDetection = detection
                        latestDetectionTime = SystemClock.elapsedRealtime()
                        onAnalysis(outlineTracker.update(detection, SystemClock.elapsedRealtime(), epoch))
                        // Automatic shutter and scene snapshots use the raw detector output.
                        if (!closed.get() && active.get() && generation.get() == epoch && autoCaptureEnabled &&
                            gate.observe(detection, SystemClock.elapsedRealtime())) onAutoCapture()
                    }
                } finally { deliveryPending.set(false) }
            }
        } catch (_: CancellationException) {
            // Lifecycle/viewport changes invalidate work already on the single analysis worker.
        } catch (error: Exception) {
            if (!closed.get() && active.get() && generation.get() == epoch) report("Camera analysis failed", error)
        } finally { upright.recycle() }
    }

    /** Manual capture remains available even for documents with insufficient texture to judge blur. */
    fun capture(destination: File, onSaved: (File) -> Unit) = capture(destination, false, {}, onSaved)

    /** Automatic capture settles focus, compares two original stills, and publishes only an acceptable one. */
    fun capture(destination: File, automatic: Boolean, onRejected: (String) -> Unit, onSaved: (File) -> Unit) = onMain {
        if (closed.get() || imageCapture == null || !active.get()) {
            report("Camera is not ready for capture"); return@onMain
        }
        if (!gate.captureStarted(latestAnalysisSignature)) return@onMain
        val staging: File
        try {
            if (destination.exists()) throw IllegalArgumentException("Capture destination already exists")
            val parent = destination.absoluteFile.parentFile ?: throw IllegalArgumentException("Missing capture directory")
            if (!parent.exists() && !parent.mkdirs()) throw IllegalStateException("Cannot create capture directory")
            staging = File.createTempFile(".scan-", ".jpg", parent)
        } catch (error: Exception) {
            gate.captureFinished(false); report("Unable to prepare the capture file", error, ErrorCode.STORAGE); return@onMain
        }
        val pending = PendingCapture(staging, destination, onSaved, automatic, latestDetection, generation.get(), onRejected)
        synchronized(captureLock) {
            if (closed.get()) { staging.delete(); gate.captureFinished(false); return@onMain }
            pendingCapture = pending
        }
        settleFocus(pending) {
            val current = latestDetection
            val reference = pending.reference
            if (automatic && (reference?.corners == null || current?.corners == null ||
                    SystemClock.elapsedRealtime() - latestDetectionTime > 600 ||
                    Geometry.distance(reference.corners!!, current.corners!!) > 0.035)) {
                rejectCapture(pending)
            } else takeCandidate(pending, staging)
        }
    }

    private fun captureCurrent(pending: PendingCapture): Boolean = !closed.get() && active.get() &&
        !pending.cancelled && pendingCapture === pending && generation.get() == pending.epoch

    private fun settleFocus(pending: PendingCapture, ready: () -> Unit) {
        val bound = camera ?: run { discardCapture(pending); return }
        val focusPoint = pending.reference?.corners?.points?.let { points ->
            Point(points.map { it.x }.average(), points.map { it.y }.average())
        }?.let(::normalizedToPreview)
        val x = focusPoint?.x?.toFloat() ?: previewView.width / 2f
        val y = focusPoint?.y?.toFloat() ?: previewView.height / 2f
        val finished = AtomicBoolean(false)
        fun finish() {
            if (finished.compareAndSet(false, true)) mainHandler.postDelayed({
                if (captureCurrent(pending)) ready() else discardCapture(pending)
            }, 250)
        }
        mainHandler.postDelayed({ finish() }, 1500)
        try {
            val point = previewView.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
            if (!bound.cameraInfo.isFocusMeteringSupported(action)) { finish(); return }
            val focus = bound.cameraControl.startFocusAndMetering(action)
            focus.addListener({ try { focus.get() } catch (_: Exception) { }; finish() }, mainExecutor)
        } catch (_: Exception) { finish() }
    }

    private fun takeCandidate(pending: PendingCapture, file: File) {
        if (!captureCurrent(pending)) { discardCapture(pending); return }
        val capture = imageCapture ?: run { discardCapture(pending); return }
        pending.shotCount++
        try {
            capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        try {
                            worker.execute {
                                try {
                                    val cancel = Cancellation { if (!captureCurrent(pending)) throw CancellationException() }
                                    cancel.check()
                                    var quality: CaptureQualityPolicy.Sample? = null
                                    if (pending.automatic) {
                                        val detected = stillDetector?.invoke(file, cancel) ?: processor.detect(file, config, cancel)
                                        val quad = detected.corners
                                        val reference = pending.reference
                                        val sameDocument = quad != null && reference?.corners != null &&
                                            Geometry.valid(quad, 0.03) && detected.confidence >= 0.60 &&
                                            Geometry.distance(reference.corners!!, quad) <= 0.075 &&
                                            detected.sceneSignature.size == reference.sceneSignature.size &&
                                            detected.sceneSignature.zip(reference.sceneSignature).map { (a,b) -> kotlin.math.abs(a-b) }.average() < 0.12
                                        if (quad != null) {
                                            val measured = processor.assessQuality(file, quad, config, cancel)
                                            quality = CaptureQualityPolicy.Sample(measured.sharpness, measured.brightness,
                                                measured.informativeTiles, sameDocument)
                                        }
                                    }
                                    cancel.check()
                                    val assessed = quality
                                    mainExecutor.execute {
                                        if (!captureCurrent(pending)) { discardCapture(pending); return@execute }
                                        val acceptable = !pending.automatic || (assessed != null && CaptureQualityPolicy.acceptable(
                                            assessed, pending.reference?.sharpness ?: 0.0, pending.reference?.documentDetailTiles))
                                        if (acceptable && (!pending.automatic || CaptureQualityPolicy.better(assessed!!, pending.bestQuality))) {
                                            pending.bestFile = file; pending.bestQuality = assessed
                                        }
                                        if (pending.automatic && pending.shotCount < 2) {
                                            try {
                                                val next = File.createTempFile(".scan-", ".jpg", pending.staging.parentFile)
                                                synchronized(captureLock) {
                                                    if (!captureCurrent(pending)) { next.delete(); return@execute }
                                                    pending.files.add(next)
                                                }
                                                takeCandidate(pending, next)
                                            } catch (error: Exception) {
                                                captureFailure(pending,"Unable to prepare the capture file",error,ErrorCode.STORAGE)
                                            }
                                        } else if (pending.bestFile == null) rejectCapture(pending)
                                        else publishCapture(pending)
                                    }
                                } catch (_: CancellationException) { discardCapture(pending) }
                                catch (error: Exception) {
                                    captureFailure(pending,"Unable to check or save the capture",error,ErrorCode.PROCESSING)
                                }
                            }
                        } catch (_: RejectedExecutionException) { discardCapture(pending) }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        captureFailure(pending,"Camera capture failed",exception)
                    }
                })
        } catch (error: Exception) { captureFailure(pending,"Camera capture failed",error) }
    }

    private fun publishCapture(pending: PendingCapture) {
        try {
            worker.execute {
                try {
                    synchronized(captureLock) {
                        if (!captureCurrent(pending)) { discardCapture(pending); return@execute }
                        CaptureFiles.commit(pending.bestFile!!, pending.destination)
                        pending.published = true
                        pending.files.forEach { it.delete() }
                    }
                    mainExecutor.execute {
                        synchronized(captureLock) {
                            if (!captureCurrent(pending)) { discardCapture(pending); return@execute }
                            pending.delivered = true
                            pendingCapture = null
                            gate.captureFinished(true)
                            pending.onSaved(pending.destination)
                        }
                    }
                } catch (error: Exception) { captureFailure(pending,"Unable to save the capture",error,ErrorCode.STORAGE,allowBest=false) }
            }
        } catch (_: RejectedExecutionException) { discardCapture(pending) }
    }

    private fun interruptPendingCapture() {
        val pending = pendingCapture ?: return
        discardCapture(pending)
        if (!closed.get()) onMain { pending.onRejected("Capture paused. Hold steady and try again.") }
    }

    private fun captureFailure(pending: PendingCapture, message: String, error: Exception,
        code: ErrorCode = ErrorCode.CAMERA, allowBest: Boolean = true) = onMain {
        if (!captureCurrent(pending)) { discardCapture(pending); return@onMain }
        // The first original is still usable if a later burst shot fails.
        if (allowBest && pending.bestFile != null) publishCapture(pending)
        else { discardCapture(pending); report(message,error,code) }
    }

    private fun rejectCapture(pending: PendingCapture) {
        val notify = captureCurrent(pending)
        discardCapture(pending)
        if (notify) pending.onRejected("Hold steady while the camera focuses, then try again.")
    }

    fun setTorch(enabled: Boolean) = onMain {
        val bound = camera ?: return@onMain
        if (!bound.cameraInfo.hasFlashUnit()) { if (enabled) report("This camera has no torch"); return@onMain }
        val result = bound.cameraControl.enableTorch(enabled)
        result.addListener({ try { result.get() } catch (error: Exception) { report("Unable to change the torch", error) } }, mainExecutor)
    }

    /** x/y are PreviewView-local pixel coordinates with the caller's preview scale type. */
    fun focus(x: Float, y: Float) = onMain {
        val bound = camera ?: return@onMain
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x > previewView.width || y > previewView.height) return@onMain
        // Focus is best effort. CameraX cancels the previous request on another tap;
        // neither that cancellation nor unsupported metering is a capture failure.
        try {
            val point = previewView.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
            if (!bound.cameraInfo.isFocusMeteringSupported(action)) return@onMain
            val result = bound.cameraControl.startFocusAndMetering(action)
            result.addListener({
                try { result.get() } catch (_: Exception) {
                    // A cancelled/unsuccessful focus leaves the preview and shutter usable.
                }
            }, mainExecutor)
        } catch (_: Exception) {
            // Some cameras reject metering points; manual capture remains available.
        }
    }

    fun normalizedToPreview(point: Point): Point? {
        val size = analysisImageSize ?: return null
        if (previewView.width <= 0 || previewView.height <= 0) return null
        if (boundScaleType != previewView.scaleType) return null
        val fillCenter = when (previewView.scaleType) {
            PreviewView.ScaleType.FIT_CENTER -> false
            PreviewView.ScaleType.FILL_CENTER -> true
            else -> return null // Start/end alignment requires a different mapping.
        }
        return ViewportMapping.toPreview(point, size.width, size.height, previewView.width, previewView.height, fillCenter)
    }

    override fun close() {
        synchronized(captureLock) {
            if (!closed.compareAndSet(false, true)) return
            pendingCapture?.let { pending -> pending.cancelled = true; discardCapture(pending) }
        }
        active.set(false); generation.incrementAndGet(); gate.pause()
        outlineTracker.reset()
        onMain {
            bindingRequested = false
            previewView.removeOnLayoutChangeListener(layoutListener)
            displayManager.unregisterDisplayListener(displayListener)
            lifecycleOwner.lifecycle.removeObserver(observer)
            unbindOwnedCases()
            worker.shutdown()
        }
    }
    private fun discardCapture(pending: PendingCapture) = synchronized(captureLock) {
        pending.cancelled = true
        pending.files.forEach { it.delete() }
        if (pending.published && !pending.delivered) pending.destination.delete()
        if (pendingCapture === pending) { pendingCapture = null; gate.captureFinished(false) }
    }
    private fun unbindOwnedCases() {
        analysis?.clearAnalyzer()
        val owned = listOfNotNull(preview, analysis, imageCapture)
        if (owned.isNotEmpty()) provider?.unbind(*owned.toTypedArray())
        preview = null; analysis = null; imageCapture = null; camera = null
    }
    private fun report(message: String, error: Exception? = null, code: ErrorCode = ErrorCode.CAMERA) {
        val actualCode = if (error is SecurityException || error?.cause is SecurityException) ErrorCode.PERMISSION_DENIED else code
        onMain { if (!closed.get()) onError(ScanError(actualCode, message)) }
    }
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
    }
}
