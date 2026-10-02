package dev.offlinescan.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.offlinescan.core.Geometry
import dev.offlinescan.core.Point
import dev.offlinescan.core.Quad
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

/** Raw, stateless detector measurements. No outline tracker, retained corners or capture smoothing. */
@RunWith(AndroidJUnit4::class)
class TemporalShadowBenchmarkTest {
    @Test fun cancellationDuringNormalizedFallbackPreservesSignalAndBorrowedBitmap() {
        val image=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.rgb(128,128,128))
        val processor=OpenCvProcessor()
        val signal=java.util.concurrent.CancellationException("normalized fallback cancellation")
        try {
            repeat(8) {
                var checks=0
                try {
                    // Uniform input has no accepted candidate. Check seven is between the two
                    // normalized passes, after the shading field and corrected image were made.
                    processor.detectBitmap(image,dev.offlinescan.core.Cancellation { if (++checks==7) throw signal })
                    fail("Expected cancellation during fallback")
                } catch (actual: java.util.concurrent.CancellationException) { assertSame(signal,actual) }
                assertFalse(image.isRecycled)
            }
            val later=processor.detectBitmap(image)
            assertNull(later.corners); assertEquals(0.0,later.confidence,0.0)
            assertEquals(128.0/255,later.brightness,1e-6)
        } finally { image.recycle() }
    }

    @Test fun measureSmallMotionShadowSequences() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val assets=instrumentation.context.assets
        val fixtures=assets.open("temporal-shadow/manifest.json").bufferedReader().use { JSONObject(it.readText()).getJSONArray("cases") }
        val processor=OpenCvProcessor()
        val warm=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
        try { warm.eraseColor(Color.GRAY); repeat(3) { processor.detectBitmap(warm) } } finally { warm.recycle() }
        val records=JSONArray(); val measurements=ArrayList<Measurement>()
        val previous=HashMap<String,Measurement>()
        for (index in 0 until fixtures.length()) {
            val fixture=fixtures.getJSONObject(index)
            val split=fixture.getString("split"); val sequence=fixture.getString("sequence")
            val frame=fixture.getInt("frameIndex"); val file=fixture.getString("file")
            assertTrue(split=="tuning" || split=="held-out")
            assertTrue(frame in 0..7)
            assertFalse(file.contains("..") || file.startsWith("/"))
            val expected=fixture.getBoolean("expectedDetection")
            val truth=if (fixture.isNull("corners")) null else quad(fixture.getJSONArray("corners"))
            assertEquals(expected,truth!=null)
            truth?.let { assertTrue(Geometry.valid(it)) }
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            assets.open("temporal-shadow/$file").use { BitmapFactory.decodeStream(it,null,bounds) }
            assertTrue(bounds.outWidth in 2..960 && bounds.outHeight in 2..960)
            val image=assets.open("temporal-shadow/$file").use { BitmapFactory.decodeStream(it) }!!
            try {
                val managedBefore=managedHeap(); val nativeBefore=Debug.getNativeHeapAllocatedSize()
                val start=SystemClock.elapsedRealtimeNanos()
                val result=processor.detectBitmap(image)
                val millis=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
                val managedAfter=managedHeap(); val nativeAfter=Debug.getNativeHeapAllocatedSize()
                assertTrue(result.confidence.isFinite() && result.confidence in 0.0..1.0)
                assertTrue(result.brightness.isFinite() && result.brightness in 0.0..1.0)
                assertTrue(result.sharpness.isFinite() && result.sharpness>=0)
                assertEquals(64,result.sceneSignature.size)
                result.corners?.let { assertTrue(Geometry.valid(it)) }
                if (result.corners==null) assertEquals(0.0,result.confidence,0.0)
                val prior=previous[sequence]
                if (frame==0) assertNull(prior) else assertEquals(frame-1,prior!!.frame)
                val iou=truth?.let { if (result.corners==null) 0.0 else intersectionOverUnion(it,result.corners!!) }
                val error=if (truth!=null && result.corners!=null) Geometry.distance(truth,result.corners!!) else null
                val jitter=if (truth!=null && result.corners!=null && prior?.truth!=null && prior.prediction!=null)
                    motionResidual(prior.truth,truth,prior.prediction,result.corners!!) else null
                val drop=expected && prior?.prediction!=null && result.corners==null
                val measurement=Measurement(split,sequence,fixture.getString("category"),frame,expected,truth,result.corners,error,iou,jitter,drop,millis)
                measurements.add(measurement); previous[sequence]=measurement
                records.put(JSONObject().put("id",fixture.getString("id")).put("split",split).put("sequence",sequence)
                    .put("category",measurement.category).put("frameIndex",frame).put("file",file)
                    .put("expectedDetection",expected).put("detected",result.corners!=null)
                    .put("truth",truth?.let(::jsonQuad) ?: JSONObject.NULL).put("corners",result.corners?.let(::jsonQuad) ?: JSONObject.NULL)
                    .put("confidence",result.confidence).put("brightness",result.brightness).put("sharpness",result.sharpness)
                    .put("polygonIoU",iou ?: JSONObject.NULL).put("normalizedCornerError",error ?: JSONObject.NULL)
                    .put("motionCompensatedCornerJitter",jitter ?: JSONObject.NULL).put("foundToMissingTransition",drop)
                    .put("elapsedMillis",millis).put("managedHeapBeforeBytes",managedBefore).put("managedHeapAfterBytes",managedAfter)
                    .put("nativeHeapBeforeBytes",nativeBefore).put("nativeHeapAfterBytes",nativeAfter))
            } finally { image.recycle() }
        }
        val splits=JSONObject(); measurements.groupBy { it.split }.forEach { (key,values) -> splits.put(key,aggregate(values)) }
        val sequences=JSONObject(); measurements.groupBy { it.sequence }.forEach { (key,values) -> sequences.put(key,aggregate(values)) }
        val report=JSONObject().put("schemaVersion",1).put("cases",records).put("bySplit",splits).put("bySequence",sequences)
            .put("aggregate",aggregate(measurements))
            .put("device",JSONObject().put("model",android.os.Build.MODEL).put("api",android.os.Build.VERSION.SDK_INT))
            .put("timingScope","detectBitmap only after three explicit warm-ups; decoding excluded; one measured call per frame")
            .put("memoryScope","Native/managed endpoint heap samples with fixture alive; not peak measurements or proof of leak freedom")
            .put("jitterScope","Mean per-corner residual of detected motion minus independent truth motion on adjacent matched frames. Missing pairs are excluded and counted separately; raw geometry, no tracker smoothing.")
        val directory=instrumentation.targetContext.getExternalFilesDir(null) ?: instrumentation.targetContext.filesDir
        val output=File(directory,"temporal-shadow-results.json"); output.writeText(report.toString(2))
        assertEquals(fixtures.length(),JSONObject(output.readText()).getJSONArray("cases").length())
    }

    @Test fun jitterMetricRemovesTrueMotionButRetainsMeasurementNoise() {
        val a=Quad(Point(.1,.1),Point(.6,.1),Point(.6,.7),Point(.1,.7))
        fun shift(q: Quad,x: Double,y: Double): Quad=q.points.map { Point(it.x+x,it.y+y) }.let { Quad(it[0],it[1],it[2],it[3]) }
        val b=shift(a,.02,-.01)
        assertEquals(0.0,motionResidual(a,b,a,b),1e-12)
        assertEquals(.003,motionResidual(a,b,a,shift(b,.003,0.0)),1e-12)
        assertEquals(1.0,intersectionOverUnion(a,a),1e-12)
        assertEquals(0.0,intersectionOverUnion(a,shift(a,.7,0.0)),1e-12)
    }

    private data class Measurement(val split: String,val sequence: String,val category: String,val frame: Int,val expected: Boolean,
        val truth: Quad?,val prediction: Quad?,val error: Double?,val iou: Double?,val jitter: Double?,val drop: Boolean,val millis: Double)
    private fun aggregate(values: List<Measurement>): JSONObject {
        val positives=values.filter { it.expected }; val negatives=values.filter { !it.expected }
        val errors=values.mapNotNull { it.error }; val overlaps=values.mapNotNull { it.iou }; val jitter=values.mapNotNull { it.jitter }
        val times=values.map { it.millis }.sorted()
        fun mean(v: List<Double>): Any=if (v.isEmpty()) JSONObject.NULL else v.average()
        var longest=0
        positives.groupBy { it.sequence }.values.forEach { sequence ->
            var run=0
            sequence.sortedBy { it.frame }.forEach { run=if (it.prediction==null) run+1 else 0; longest=maxOf(longest,run) }
        }
        return JSONObject().put("frameCount",values.size).put("positiveFrameCount",positives.size).put("negativeFrameCount",negatives.size)
            .put("detectedPositiveCount",positives.count { it.prediction!=null }).put("missCount",positives.count { it.prediction==null })
            .put("falsePositiveCount",negatives.count { it.prediction!=null }).put("accuratePositiveCount",positives.count { (it.iou ?: 0.0)>=.8 })
            .put("recall",if (positives.isEmpty()) JSONObject.NULL else positives.count { it.prediction!=null }.toDouble()/positives.size)
            .put("meanPolygonIoUIncludingMisses",mean(overlaps)).put("meanNormalizedCornerErrorOnMatches",mean(errors)).put("matchedCornerErrorCount",errors.size)
            .put("meanMotionCompensatedCornerJitter",mean(jitter)).put("matchedAdjacentPairCount",jitter.size)
            .put("foundToMissingTransitions",values.count { it.drop }).put("longestMissingRun",longest)
            .put("meanElapsedMillis",mean(times)).put("p50ElapsedMillis",times[(ceil(times.size*.5).toInt()-1).coerceAtLeast(0)])
            .put("p95ElapsedMillis",times[(ceil(times.size*.95).toInt()-1).coerceAtLeast(0)])
    }
    private fun motionResidual(previousTruth: Quad,truth: Quad,previous: Quad,current: Quad): Double = (0..3).map { i ->
        hypot((current.points[i].x-previous.points[i].x)-(truth.points[i].x-previousTruth.points[i].x),
            (current.points[i].y-previous.points[i].y)-(truth.points[i].y-previousTruth.points[i].y))
    }.average()
    private fun managedHeap()=Runtime.getRuntime().let { it.totalMemory()-it.freeMemory() }
    private fun quad(array: JSONArray): Quad=(0..3).map { array.getJSONArray(it).let { p -> Point(p.getDouble(0),p.getDouble(1)) } }.let { Quad(it[0],it[1],it[2],it[3]) }
    private fun jsonQuad(quad: Quad)=JSONArray().apply { quad.points.forEach { put(JSONArray(listOf(it.x,it.y))) } }

    private fun intersectionOverUnion(a: Quad,b: Quad): Double {
        var polygon=a.points
        for (i in b.points.indices) {
            val start=b.points[i]; val end=b.points[(i+1)%4]
            fun distance(p: Point)=(end.x-start.x)*(p.y-start.y)-(end.y-start.y)*(p.x-start.x)
            val out=ArrayList<Point>(); if (polygon.isEmpty()) break
            var previous=polygon.last(); var previousDistance=distance(previous)
            for (current in polygon) {
                val currentDistance=distance(current)
                if ((previousDistance>= -1e-12)!=(currentDistance>= -1e-12)) {
                    val t=(previousDistance/(previousDistance-currentDistance)).coerceIn(0.0,1.0)
                    out.add(Point(previous.x+(current.x-previous.x)*t,previous.y+(current.y-previous.y)*t))
                }
                if (currentDistance>= -1e-12) out.add(current)
                previous=current; previousDistance=currentDistance
            }
            polygon=out
        }
        val intersection=if (polygon.size<3) 0.0 else abs(polygon.indices.sumOf { i -> val p=polygon[i]; val q=polygon[(i+1)%polygon.size]; p.x*q.y-q.x*p.y })/2
        val union=Geometry.area(a)+Geometry.area(b)-intersection
        return if (union<=0) 0.0 else (intersection/union).coerceIn(0.0,1.0)
    }
}
