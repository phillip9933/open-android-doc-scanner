package dev.offlinescan.processing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.os.Debug
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.offlinescan.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil

/** Measures both detectors on the same frozen images. Model quality is observed, never assumed. */
@RunWith(AndroidJUnit4::class)
class LearnedDocumentDetectorTest {
    /** Opt-in: public licensed camera samples are pushed separately, never packaged into an APK. */
    @Test fun compareLicensedCameraFixturesOptIn() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val supplied=InstrumentationRegistry.getArguments().getString("fixtureDir")
        assumeTrue("Explicit external camera fixture directory is required",supplied!=null)
        val directory=File(supplied!!)
        val manifest=JSONObject(File(directory,"manifest.json").readText())
        val samples=manifest.getJSONArray("samples")
        val rows=JSONArray()
        val standard=OpenCvProcessor()
        LearnedDocumentDetector(instrumentation.targetContext).use { learned ->
            for(i in 0 until samples.length()) {
                val fixture=samples.getJSONObject(i)
                val name=fixture.getString("file")
                assertFalse(name.contains("..") || name.contains("/") || name.contains("\\"))
                val source=File(directory,name)
                val hash=MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
                assertEquals(fixture.getString("sha256"),hash)
                val bitmap=BitmapFactory.decodeFile(source.absolutePath)!!
                try {
                    val truth=if(fixture.has("corners_tl_tr_br_bl_pixels")) {
                        val coordinates=fixture.getJSONArray("corners_tl_tr_br_bl_pixels")
                        val points=(0..3).map { coordinates.getJSONArray(it).let { value -> Point(value.getDouble(0)/bitmap.width,value.getDouble(1)/bitmap.height) } }
                        Quad(points[0],points[1],points[2],points[3])
                    } else null
                    for((mode,detect) in listOf<Pair<String,(Bitmap)->Detection>>("Standard" to { standard.detectBitmap(it) },"AI" to { learned.detectBitmap(it) })) {
                        val start=SystemClock.elapsedRealtimeNanos();val result=detect(bitmap)
                        val elapsed=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
                        finite(result)
                        val evidence=if(mode=="AI") learned.lastEvidence else null
                        rows.put(JSONObject().put("mode",mode).put("file",name).put("width",bitmap.width).put("height",bitmap.height)
                            .put("detected",result.corners!=null).put("confidence",result.confidence)
                            .put("corners",result.corners?.let { JSONArray(it.points.map { p -> JSONArray(listOf(p.x,p.y)) }) } ?: JSONObject.NULL)
                            .put("polygonIoU",truth?.let { t -> result.corners?.let { intersectionOverUnion(t,it) } ?: 0.0 } ?: JSONObject.NULL)
                            .put("normalizedCornerError",truth?.let { t -> result.corners?.let { Geometry.distance(t,it) } } ?: JSONObject.NULL)
                            .put("minimumCornerPeakProbability",evidence?.minimumPeak ?: JSONObject.NULL)
                            .put("maskAgreement",evidence?.agreement ?: JSONObject.NULL).put("rejection",evidence?.rejection ?: JSONObject.NULL)
                            .put("elapsedMillis",elapsed).put("sharpness",result.sharpness).put("brightness",result.brightness))
                    }
                } finally { bitmap.recycle() }
            }
        }
        File(instrumentation.targetContext.getExternalFilesDir(null),"learned-camera-results.json").writeText(JSONObject()
            .put("schemaVersion",1).put("sourceManifest",manifest).put("scope","Real camera diagnostic; possible model training overlap; only frame1 author ground truth; not independent quality acceptance")
            .put("timingScope","First inference per detector may include cold initialization; subsequent same-session runs warmed; not a latency benchmark")
            .put("cases",rows).toString(2))
        assertEquals(samples.length()*2,rows.length())
    }

    /** Opt-in sustained 10Hz pipeline exercise; explicit pacing prevents a misleading full-load claim. */
    @Test fun sustainedCpuInferenceOptIn() {
        assumeTrue("Explicit sustainedAi=true is required",InstrumentationRegistry.getArguments().getString("sustainedAi")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        fun nanos()=SystemClock.elapsedRealtimeNanos()
        fun millisSince(start:Long)=(nanos()-start)/1_000_000.0
        val runtimeStart=nanos();val environment=OrtEnvironment.getEnvironment();val environmentMillis=millisSince(runtimeStart)
        val readStart=nanos();val bytes=context.assets.open(LearnedDocumentDetector.MODEL_ASSET).use { it.readBytes() };val assetReadMillis=millisSince(readStart)
        val createStart=nanos()
        val direct=OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1)
            environment.createSession(bytes,options)
        }
        val sessionCreateMillis=millisSince(createStart)
        val directFirstStart=nanos()
        direct.use { active ->
            OnnxTensor.createTensor(environment,FloatBuffer.wrap(FloatArray(3*256*256)),longArrayOf(1,3,256,256)).use { tensor ->
                active.run(mapOf("input" to tensor)).use { result -> assertEquals(2,result.size()) }
            }
        }
        val directFirstInferenceAndCloseMillis=millisSince(directFirstStart)
        val page=Bitmap.createBitmap(300,400,Bitmap.Config.ARGB_8888)
        val bitmap=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888)
        val paper=Canvas(page);paper.drawColor(Color.rgb(245,244,237))
        val text=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(35,35,35);textSize=17f }
        paper.drawText("OFFLINE CAMERA TEST",20f,36f,text);text.textSize=12f
        for(line in 0..22) paper.drawText("${line+1}. Capture a clear document page.",20f,65f+line*13f,text)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.rgb(73,64,52))
        val transform=Matrix().apply { setPolyToPoly(floatArrayOf(0f,0f,300f,0f,300f,400f,0f,400f),0,floatArrayOf(130f,45f,510f,70f,485f,430f,100f,405f),0,4) }
        canvas.drawBitmap(page,transform,Paint(Paint.FILTER_BITMAP_FLAG));page.recycle()
        val timings=ArrayList<Double>();var errors=0;var changedOutputs=0
        fun managed()=Runtime.getRuntime().let { it.totalMemory()-it.freeMemory() }
        fun collect() { System.gc();System.runFinalization();SystemClock.sleep(100) }
        fun quantile(values:List<Double>,fraction:Double)=values.sorted()[(ceil(values.size*fraction).toInt()-1).coerceAtLeast(0)]
        try {
            LearnedDocumentDetector(context).use { learned ->
                val coldPipelineStart=nanos();val reference=learned.detectBitmap(bitmap);val pipelineColdMillis=millisSince(coldPipelineStart)
                assertNotNull(reference.corners)
                repeat(10) { learned.detectBitmap(bitmap) }
                collect();val managedBefore=managed();val nativeBefore=Debug.getNativeHeapAllocatedSize()
                val started=SystemClock.elapsedRealtime()
                repeat(1200) { index ->
                    val until=started+index*100-SystemClock.elapsedRealtime()
                    if(until>0) SystemClock.sleep(until)
                    val begin=nanos()
                    try {
                        val result=learned.detectBitmap(bitmap)
                        finite(result)
                        if(result.corners==null || Geometry.distance(reference.corners!!,result.corners!!)>1e-6 || abs(reference.confidence-result.confidence)>1e-6) changedOutputs++
                    } catch (_:Exception) { errors++ }
                    timings.add(millisSince(begin))
                }
                val remaining=started+120000-SystemClock.elapsedRealtime()
                if(remaining>0) SystemClock.sleep(remaining)
                val duration=SystemClock.elapsedRealtime()-started
                collect();val managedAfter=managed();val nativeAfter=Debug.getNativeHeapAllocatedSize()
                val report=JSONObject().put("schemaVersion",1).put("inferenceCount",timings.size).put("errors",errors).put("changedOutputs",changedOutputs)
                    .put("modelSha256",LearnedDocumentDetector.MODEL_SHA256).put("actualDurationMillis",duration).put("targetIntervalMillis",100)
                    .put("scope","1200 actual complete detector calls, explicitly paced at up to10Hz for120seconds on one static original printed bitmap; not fullCPU load, camera footage or phone thermal evidence")
                    .put("environmentInitMillis",environmentMillis).put("assetReadMillis",assetReadMillis).put("newSessionCreateMillis",sessionCreateMillis)
                    .put("directFirstInferenceAndSessionCloseMillis",directFirstInferenceAndCloseMillis).put("pipelineFirstInferenceIncludingNewSessionMillis",pipelineColdMillis)
                    .put("first100",JSONObject().put("p50Millis",quantile(timings.take(100),.5)).put("p95Millis",quantile(timings.take(100),.95)))
                    .put("last100",JSONObject().put("p50Millis",quantile(timings.takeLast(100),.5)).put("p95Millis",quantile(timings.takeLast(100),.95)))
                    .put("all",JSONObject().put("p50Millis",quantile(timings,.5)).put("p95Millis",quantile(timings,.95)).put("maxMillis",timings.maxOrNull()))
                    .put("managedBeforeBytes",managedBefore).put("managedAfterBytes",managedAfter).put("nativeBeforeBytes",nativeBefore).put("nativeAfterBytes",nativeAfter)
                    .put("memoryScope","Managed/native allocator endpoints after requestedGC with session and bitmap alive; not peakRSS, proof of no leaks, or guaranteed immediate native reclamation")
                    .put("device",JSONObject().put("api",android.os.Build.VERSION.SDK_INT).put("model",android.os.Build.MODEL)).put("elapsedMillis",JSONArray(timings))
                File(context.getExternalFilesDir(null),"learned-sustained-results.json").writeText(report.toString(2))
                assertEquals(0,errors);assertEquals(0,changedOutputs);assertEquals(1200,timings.size)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun compareOwnPrintedDocumentsExploratory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rows = JSONArray()
        val standard = OpenCvProcessor()
        val truth = Quad(Point(130.0/640,45.0/480),Point(510.0/640,70.0/480),Point(485.0/640,430.0/480),Point(100.0/640,405.0/480))
        val destination = floatArrayOf(130f,45f,510f,70f,485f,430f,100f,405f)
        val outputDirectory=File(context.getExternalFilesDir(null) ?: context.filesDir,"learned-printed-cases").apply { mkdirs() }
        LearnedDocumentDetector(context).use { learned ->
            for (variant in listOf("dark", "light", "shadow", "torn", "receipt")) {
                val page=Bitmap.createBitmap(300,400,Bitmap.Config.ARGB_8888)
                val image=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888)
                try {
                    val paper=Canvas(page); paper.drawColor(Color.rgb(245,244,237))
                    val text=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(35,35,35);textSize=12f }
                    text.textSize=17f; paper.drawText("OFFLINE CAMERA TEST",20f,36f,text)
                    text.textSize=12f
                    for (line in 0..22) paper.drawText("${line+1}. Capture a clear document page.",20f,65f+line*13f,text)
                    if (variant=="receipt") {
                        paper.drawColor(Color.rgb(245,244,237)); text.textSize=16f
                        paper.drawText("TEST RECEIPT",35f,45f,text)
                        text.textSize=14f
                        for (line in 0..16) paper.drawText("Item ${line+1}             12.00",25f,80f+line*16f,text)
                    }
                    val canvas=Canvas(image)
                    val background=if(variant=="light") Color.rgb(227,222,209) else Color.rgb(73,64,52)
                    canvas.drawColor(background)
                    val paint=Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                    val transform=Matrix().apply { setPolyToPoly(floatArrayOf(0f,0f,300f,0f,300f,400f,0f,400f),0,destination,0,4) }
                    canvas.drawBitmap(page,transform,paint)
                    if(variant=="shadow") canvas.drawPath(Path().apply { moveTo(220f,0f);lineTo(640f,210f);lineTo(640f,320f);lineTo(130f,60f);close() },Paint().apply { color=Color.argb(70,0,0,0) })
                    if(variant=="torn") canvas.drawPath(Path().apply { moveTo(490f,68f);lineTo(510f,70f);lineTo(508f,100f);close() },Paint().apply { color=background })
                    File(outputDirectory,"$variant.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                    for ((mode, detect) in listOf<Pair<String,(Bitmap)->Detection>>("Standard" to { standard.detectBitmap(it) },"AI" to { learned.detectBitmap(it) })) {
                        val start=SystemClock.elapsedRealtimeNanos();val result=detect(image)
                        val elapsed=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
                        finite(result)
                        val evidence=if(mode=="AI") learned.lastEvidence else null
                        rows.put(JSONObject().put("mode",mode).put("case",variant).put("detected",result.corners!=null)
                            .put("confidence",result.confidence).put("polygonIoU",result.corners?.let { intersectionOverUnion(truth,it) } ?: 0.0)
                            .put("normalizedCornerError",result.corners?.let { Geometry.distance(truth,it) } ?: JSONObject.NULL)
                            .put("minimumCornerPeakProbability",evidence?.minimumPeak ?: JSONObject.NULL)
                            .put("maskAgreement",evidence?.agreement ?: JSONObject.NULL).put("maskFraction",evidence?.maskFraction ?: JSONObject.NULL)
                            .put("rejection",evidence?.rejection ?: JSONObject.NULL).put("elapsedMillis",elapsed))
                    }
                } finally { page.recycle();image.recycle() }
            }
        }
        File(context.getExternalFilesDir(null) ?: context.filesDir,"learned-printed-results.json").writeText(JSONObject()
            .put("provenance","Original generated text/paper scenes; exploratory integration diagnostic, not real camera footage and not independent held-out acceptance")
            .put("cases",rows).toString(2))
        assertEquals(10,rows.length())
    }

    @Test fun fileDetectionUsesUprightExifCoordinates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val image = instrumentation.context.assets.open("benchmark/held-out-paper-dark.png").use { BitmapFactory.decodeStream(it) }!!
        val source = File.createTempFile("learned-exif-", ".jpg", context.cacheDir)
        try {
            source.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            val original = BitmapFactory.decodeFile(source.absolutePath)
            try {
                LearnedDocumentDetector(context).use { detector ->
                    for (orientation in listOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE)) {
                        ExifInterface(source).apply { setAttribute(ExifInterface.TAG_ORIENTATION,orientation.toString());saveAttributes() }
                        val exif=ExifInterface(source)
                        val matrix=Matrix().apply { if(exif.isFlipped) postScale(-1f,1f);postRotate(exif.rotationDegrees.toFloat()) }
                        val upright=Bitmap.createBitmap(original,0,0,original.width,original.height,matrix,true)
                        try {
                            val expected=detector.detectBitmap(upright)
                            val actual=detector.detect(source,ScanConfig())
                            assertEquals(expected.confidence,actual.confidence,1e-6)
                            assertEquals(expected.corners,actual.corners)
                            assertEquals(expected.sceneSignature,actual.sceneSignature)
                        } finally { upright.takeIf { it !== original }?.recycle() }
                    }
                }
            } finally { original.recycle() }
        } finally { image.recycle();source.delete() }
    }

    @Test fun offlineModelLoadsRejectsBlankAndHonorsLifetime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val detector = LearnedDocumentDetector(context)
        try {
            bitmap.eraseColor(Color.GRAY)
            val result = detector.detectBitmap(bitmap)
            finite(result)
            assertNull("A uniform scene must not become an automatic document", result.corners)
            assertFalse("Caller bitmap remains owned by its caller", bitmap.isRecycled)
            try {
                detector.detectBitmap(bitmap, Cancellation { throw InterruptedException("cancelled") })
                fail("Cancelled work must not run")
            } catch (_: InterruptedException) { }
            detector.close()
            try { detector.detectBitmap(bitmap); fail("Closed detector must reject reuse") } catch (_: IllegalStateException) { }
        } finally { detector.close(); bitmap.recycle() }
    }

    @Test fun compareFrozenFootageAndChallengeCases() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val context = instrumentation.targetContext
        val split = InstrumentationRegistry.getArguments().getString("learnedSplit", "held-out")!!
        assertTrue(split == "tuning" || split == "held-out")
        val standard = OpenCvProcessor()
        val rows = JSONArray()
        val warm = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        LearnedDocumentDetector(context).use { learned ->
            val coldStart = SystemClock.elapsedRealtimeNanos()
            try { finite(learned.detectBitmap(warm)); standard.detectBitmap(warm) } finally { warm.recycle() }
            val coldMillis = (SystemClock.elapsedRealtimeNanos()-coldStart)/1_000_000.0
            for (cohort in listOf("benchmark", "challenge", "temporal-shadow")) {
                val cases = assets.open("$cohort/manifest.json").bufferedReader().use { JSONObject(it.readText()).getJSONArray("cases") }
                for (i in 0 until cases.length()) {
                    val fixture = cases.getJSONObject(i)
                    if (fixture.getString("split") != split) continue
                    val bitmap = assets.open("$cohort/${fixture.getString("file")}").use { BitmapFactory.decodeStream(it) }!!
                    try {
                        for ((mode, detect) in listOf<Pair<String, (Bitmap)->Detection>>("Standard" to { standard.detectBitmap(it) }, "AI" to { learned.detectBitmap(it) })) {
                            val start = SystemClock.elapsedRealtimeNanos()
                            val detection = detect(bitmap)
                            val elapsed = (SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
                            finite(detection)
                            val truth = if (fixture.isNull("corners")) null else quad(fixture.getJSONArray("corners"))
                            val evidence = if (mode == "AI") learned.lastEvidence else null
                            rows.put(JSONObject().put("mode", mode).put("cohort", cohort).put("id", fixture.getString("id"))
                                .put("category", fixture.getString("category")).put("expectedDetection", fixture.getBoolean("expectedDetection"))
                                .put("detected", detection.corners != null).put("confidence", detection.confidence)
                                .put("corners", detection.corners?.let { JSONArray(it.points.map { p -> JSONArray(listOf(p.x,p.y)) }) } ?: JSONObject.NULL)
                                .put("normalizedCornerError", truth?.let { t -> detection.corners?.let { Geometry.distance(t,it) } } ?: JSONObject.NULL)
                                .put("polygonIoU", truth?.let { t -> detection.corners?.let { intersectionOverUnion(t,it) } ?: 0.0 } ?: JSONObject.NULL)
                                .put("minimumCornerPeakProbability",evidence?.minimumPeak ?: JSONObject.NULL)
                                .put("maskFraction",evidence?.maskFraction ?: JSONObject.NULL).put("maskAgreement",evidence?.agreement ?: JSONObject.NULL)
                                .put("rejection",evidence?.rejection ?: JSONObject.NULL)
                                .put("rawCorners",evidence?.corners?.let { JSONArray(it.points.map { p -> JSONArray(listOf(p.x,p.y)) }) } ?: JSONObject.NULL)
                                .put("elapsedMillis", elapsed).put("sharpness", detection.sharpness).put("brightness", detection.brightness))
                        }
                    } finally { bitmap.recycle() }
                }
            }
            assertTrue(rows.length() >= 100)
            val output = File(context.getExternalFilesDir(null) ?: context.filesDir, if(split == "tuning") "learned-tuning-results.json" else "learned-comparison-results.json")
            output.writeText(JSONObject().put("schemaVersion",1).put("modelSha256",LearnedDocumentDetector.MODEL_SHA256)
                .put("coldStartIncludingStandardWarmupMillis",coldMillis)
                .put("timingScope","Whole detector including preprocessing, inference, postprocessing, document ROI quality and scene signature; fixture decoding excluded; warmed session")
                .put("device",JSONObject().put("api",android.os.Build.VERSION.SDK_INT).put("model",android.os.Build.MODEL).put("abis",JSONArray(android.os.Build.SUPPORTED_ABIS.toList())))
                .put("cases",rows).toString(2))
            assertEquals(rows.length(), JSONObject(output.readText()).getJSONArray("cases").length())
        }
    }

    private fun finite(result: Detection) {
        assertTrue(result.confidence.isFinite() && result.confidence in 0.0..1.0)
        assertTrue(result.brightness.isFinite() && result.brightness in 0.0..1.0)
        assertTrue(result.sharpness.isFinite() && result.sharpness >= 0)
        assertEquals(64,result.sceneSignature.size)
        assertTrue(result.sceneSignature.all { it.isFinite() && it in 0.0..1.0 })
        result.corners?.let { assertTrue(Geometry.valid(it)) }
        if (result.corners == null) assertEquals(0.0,result.confidence,0.0)
    }

    private fun quad(array: JSONArray): Quad {
        val p=(0..3).map { array.getJSONArray(it).let { value -> Point(value.getDouble(0),value.getDouble(1)) } }
        return Quad(p[0],p[1],p[2],p[3])
    }

    private fun intersectionOverUnion(a: Quad,b: Quad): Double {
        var polygon=a.points
        for (i in b.points.indices) {
            if (polygon.isEmpty()) break
            val start=b.points[i]; val end=b.points[(i+1)%4]
            fun distance(p: Point)=(end.x-start.x)*(p.y-start.y)-(end.y-start.y)*(p.x-start.x)
            val output=ArrayList<Point>(); var previous=polygon.last(); var before=distance(previous)
            for (current in polygon) {
                val after=distance(current)
                if ((before>=-1e-12)!=(after>=-1e-12)) {
                    val t=(before/(before-after)).coerceIn(0.0,1.0)
                    output.add(Point(previous.x+(current.x-previous.x)*t,previous.y+(current.y-previous.y)*t))
                }
                if (after>=-1e-12) output.add(current)
                previous=current; before=after
            }
            polygon=output
        }
        val intersection=if(polygon.size<3) 0.0 else abs(polygon.indices.sumOf { i -> val p=polygon[i];val q=polygon[(i+1)%polygon.size];p.x*q.y-q.x*p.y })/2
        val union=Geometry.area(a)+Geometry.area(b)-intersection
        return if(union<=0) 0.0 else (intersection/union).coerceIn(0.0,1.0)
    }
}
