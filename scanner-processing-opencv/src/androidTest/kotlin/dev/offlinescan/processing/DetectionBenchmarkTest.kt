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

/** Records actual detector behavior. Difficult cases are measurements, not assumed successes. */
@RunWith(AndroidJUnit4::class)
class DetectionBenchmarkTest {
    @Test fun measureGeneratedTuningAndHeldOutFixtures() {
        // Verify metric arithmetic against known geometry before measuring detector quality.
        val left = Quad(Point(0.0, 0.0), Point(.5, 0.0), Point(.5, 1.0), Point(0.0, 1.0))
        val middle = Quad(Point(.25, 0.0), Point(.75, 0.0), Point(.75, 1.0), Point(.25, 1.0))
        val right = Quad(Point(.75, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(.75, 1.0))
        assertEquals(1.0, intersectionOverUnion(left, left), 1e-12)
        assertEquals(1.0 / 3, intersectionOverUnion(left, middle), 1e-12)
        assertEquals(0.0, intersectionOverUnion(left, right), 1e-12)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val manifest = assets.open("benchmark/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val fixtures = manifest.getJSONArray("cases")
        assertTrue("Benchmark must contain real fixtures", fixtures.length() > 0)
        val processor = OpenCvProcessor()
        val warmup = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try { warmup.eraseColor(Color.GRAY); processor.detectBitmap(warmup) } finally { warmup.recycle() }
        val results = JSONArray()
        val all = ArrayList<Measurement>()
        for (index in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(index)
            val id = fixture.getString("id")
            val file = fixture.getString("file")
            assertFalse("Asset name must be local", file.contains("..") || file.startsWith("/"))
            val split = fixture.getString("split")
            assertTrue(split == "tuning" || split == "held-out")
            val category = fixture.getString("category")
            val expectedDetection = fixture.getBoolean("expectedDetection")
            val truth = if (fixture.isNull("corners")) null else quad(fixture.getJSONArray("corners"))
            if (truth != null) assertTrue("Ground-truth polygon must be valid: $id", Geometry.valid(truth))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            assets.open("benchmark/$file").use { BitmapFactory.decodeStream(it, null, bounds) }
            assertTrue("Fixture must fit the bounded test budget", bounds.outWidth > 1 && bounds.outHeight > 1 && bounds.outWidth.toLong() * bounds.outHeight <= 4_000_000)
            val bitmap = assets.open("benchmark/$file").use { BitmapFactory.decodeStream(it) }
            assertNotNull("Fixture must decode: $id", bitmap)
            val image = bitmap!!
            try {
                val managedBefore = usedManagedHeap()
                val nativeBefore = Debug.getNativeHeapAllocatedSize()
                val start = SystemClock.elapsedRealtimeNanos()
                val detection = processor.detectBitmap(image)
                val elapsedMillis = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                val managedAfter = usedManagedHeap()
                val nativeAfter = Debug.getNativeHeapAllocatedSize()
                assertTrue(detection.confidence.isFinite() && detection.confidence in 0.0..1.0)
                assertTrue(detection.brightness.isFinite() && detection.brightness in 0.0..1.0)
                assertTrue(detection.sharpness.isFinite() && detection.sharpness >= 0)
                assertEquals(64, detection.sceneSignature.size)
                assertTrue(detection.sceneSignature.all { it.isFinite() && it in 0.0..1.0 })
                detection.corners?.let { assertTrue("Detector geometry must be valid: $id", Geometry.valid(it)) }
                if (detection.corners == null) assertEquals(0.0, detection.confidence, 0.0)
                val found = detection.corners != null
                val cornerError = if (truth != null && detection.corners != null) Geometry.distance(truth, detection.corners!!) else null
                val iou = truth?.let { if (detection.corners == null) 0.0 else intersectionOverUnion(it, detection.corners!!) }
                iou?.let { assertTrue(it.isFinite() && it in 0.0..1.0) }
                val record = JSONObject().put("id", id).put("split", split).put("category", category)
                    .put("file", file).put("width", image.width).put("height", image.height)
                    .put("expectedDetection", expectedDetection).put("detected", found)
                    .put("confidence", detection.confidence).put("brightness", detection.brightness).put("sharpness", detection.sharpness)
                    .put("corners", detection.corners?.let(::jsonQuad) ?: JSONObject.NULL)
                    .put("normalizedCornerError", cornerError ?: JSONObject.NULL).put("polygonIoU", iou ?: JSONObject.NULL)
                    .put("sceneSignature", JSONArray(detection.sceneSignature)).put("elapsedMillis", elapsedMillis)
                    .put("managedHeapBeforeBytes", managedBefore).put("managedHeapAfterBytes", managedAfter)
                    .put("nativeHeapBeforeBytes", nativeBefore).put("nativeHeapAfterBytes", nativeAfter)
                results.put(record)
                all.add(Measurement(split, category, expectedDetection, found, cornerError, iou, elapsedMillis))
            } finally { image.recycle() }
        }
        val bySplit = JSONObject()
        all.groupBy { it.split }.forEach { (name, values) -> bySplit.put(name, aggregate(values)) }
        val byCategory = JSONObject()
        all.groupBy { "${it.split}/${it.category}" }.forEach { (name, values) -> byCategory.put(name, aggregate(values)) }
        val report = JSONObject().put("schemaVersion", 1)
            .put("timingScope", "detectBitmap only, after explicit OpenCV warm-up; fixture decoding excluded")
            .put("memoryScope", "Managed and native heap endpoint samples around detection, with the decoded fixture alive. These are not peak measurements. GC and allocator reuse can make deltas negative.")
            .put("device", JSONObject().put("manufacturer", android.os.Build.MANUFACTURER).put("model", android.os.Build.MODEL).put("api", android.os.Build.VERSION.SDK_INT).put("abis", JSONArray(android.os.Build.SUPPORTED_ABIS.toList())))
            .put("aggregate", aggregate(all)).put("bySplit", bySplit).put("byCategory", byCategory).put("cases", results)
        val outputDirectory = instrumentation.targetContext.getExternalFilesDir(null) ?: instrumentation.targetContext.filesDir
        val output = File(outputDirectory, "benchmark-results.json")
        output.writeText(report.toString(2))
        val reread = JSONObject(output.readText())
        assertEquals(fixtures.length(), reread.getJSONArray("cases").length())
    }

    private data class Measurement(val split: String, val category: String, val expected: Boolean, val detected: Boolean, val cornerError: Double?, val iou: Double?, val millis: Double)
    private fun aggregate(values: List<Measurement>): JSONObject {
        val positives = values.filter { it.expected }; val negatives = values.filter { !it.expected }
        val errors = values.mapNotNull { it.cornerError }; val overlaps = values.mapNotNull { it.iou }
        val times = values.map { it.millis }.sorted()
        fun meanOrNull(numbers: List<Double>): Any = if (numbers.isEmpty()) JSONObject.NULL else numbers.average()
        fun ratio(numerator: Int, denominator: Int): Any = if (denominator == 0) JSONObject.NULL else numerator.toDouble() / denominator
        return JSONObject().put("caseCount", values.size).put("positiveCount", positives.size).put("negativeCount", negatives.size)
            .put("truePositiveCount", positives.count { it.detected }).put("falsePositiveCount", negatives.count { it.detected })
            .put("detectionRateOnPositives", ratio(positives.count { it.detected }, positives.size))
            .put("falsePositiveRateOnNegatives", ratio(negatives.count { it.detected }, negatives.size))
            .put("matchedCornerErrorCount", errors.size).put("meanNormalizedCornerErrorOnMatches", meanOrNull(errors))
            .put("groundTruthIoUCount", overlaps.size).put("meanPolygonIoUIncludingMisses", meanOrNull(overlaps))
            .put("meanElapsedMillis", meanOrNull(times)).put("p50ElapsedMillis", times[(ceil(times.size * .5).toInt() - 1).coerceAtLeast(0)])
            .put("p95ElapsedMillis", times[(ceil(times.size * .95).toInt() - 1).coerceAtLeast(0)])
    }

    private fun usedManagedHeap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    private fun quad(array: JSONArray): Quad {
        assertEquals(4, array.length())
        val points = (0..3).map { index -> array.getJSONArray(index).let { Point(it.getDouble(0), it.getDouble(1)) } }
        return Quad(points[0], points[1], points[2], points[3])
    }
    private fun jsonQuad(quad: Quad) = JSONArray().apply { quad.points.forEach { put(JSONArray(listOf(it.x, it.y))) } }

    /** Sutherland-Hodgman clipping in normalized coordinates; clockwise screen-space interior is left of each directed edge. */
    private fun intersectionOverUnion(a: Quad, b: Quad): Double {
        var polygon = a.points
        for (i in b.points.indices) {
            val edgeStart = b.points[i]; val edgeEnd = b.points[(i + 1) % 4]
            fun signedDistance(p: Point) = (edgeEnd.x - edgeStart.x) * (p.y - edgeStart.y) - (edgeEnd.y - edgeStart.y) * (p.x - edgeStart.x)
            val output = ArrayList<Point>()
            if (polygon.isEmpty()) break
            var previous = polygon.last(); var previousDistance = signedDistance(previous)
            for (current in polygon) {
                val currentDistance = signedDistance(current)
                val previousInside = previousDistance >= -1e-12; val currentInside = currentDistance >= -1e-12
                if (previousInside != currentInside) {
                    val t = (previousDistance / (previousDistance - currentDistance)).coerceIn(0.0, 1.0)
                    output.add(Point(previous.x + (current.x - previous.x) * t, previous.y + (current.y - previous.y) * t))
                }
                if (currentInside) output.add(current)
                previous = current; previousDistance = currentDistance
            }
            polygon = output
        }
        val intersection = if (polygon.size < 3) 0.0 else abs(polygon.indices.sumOf { i -> val x = polygon[i]; val y = polygon[(i + 1) % polygon.size]; x.x * y.y - y.x * x.y }) / 2
        val union = Geometry.area(a) + Geometry.area(b) - intersection
        return if (union <= 0) 0.0 else (intersection / union).coerceIn(0.0, 1.0)
    }
}
