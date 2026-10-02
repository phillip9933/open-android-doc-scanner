package dev.offlinescan.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.offlinescan.core.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResourceBenchmarkTest {
    @Test fun measureBoundedHighResolutionRenderMemoryAndTime() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"resources-${System.nanoTime()}").apply { mkdirs() }
        val source=File(root,"synthetic.png")
        val bitmap=Bitmap.createBitmap(3200,1800,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(230,233,236)); val canvas=Canvas(bitmap)
            val pen=Paint().apply { color=Color.rgb(70,90,120); strokeWidth=3f }
            for(y in 100 until 1700 step 50) canvas.drawLine(200f,y.toFloat(),2900f,y.toFloat(),pen)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        } finally { bitmap.recycle() }
        val reports=JSONArray()
        try {
            val processor=OpenCvProcessor()
            for(preset in Preset.entries) {
                val nativePeak=AtomicLong(Debug.getNativeHeapAllocatedSize()); val managedPeak=AtomicLong(managed())
                val running=AtomicBoolean(true)
                val sampler=Thread {
                    while(running.get()) {
                        nativePeak.accumulateAndGet(Debug.getNativeHeapAllocatedSize(),::maxOf)
                        managedPeak.accumulateAndGet(managed(),::maxOf)
                        Thread.sleep(2)
                    }
                }
                val start=SystemClock.elapsedRealtimeNanos(); sampler.start()
                val dimensions=try { processor.render(source,Edits(preset=preset,illumination=if(preset==Preset.COLOR_DOCUMENT) .3 else .0),File(root,"result.png"),ExportFormat.PNG,ScanConfig(),NeverCancelled) }
                    finally { running.set(false); sampler.join() }
                val elapsed=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
                assertTrue(dimensions.width<=4096 && dimensions.height<=4096)
                reports.put(JSONObject().put("preset",preset.name).put("width",dimensions.width).put("height",dimensions.height).put("elapsedMillis",elapsed)
                    .put("sampledPeakNativeHeapBytes",nativePeak.get()).put("sampledPeakManagedHeapBytes",managedPeak.get()))
            }
            val report=JSONObject().put("inputWidth",3200).put("inputHeight",1800).put("sampleIntervalMillis",2)
                .put("scope","Process heap sampled during bounded 5.76MP render; approximate peaks, not exact RSS; fixture creation excluded. Native codec allocations and scheduling can miss short-lived peaks.")
                .put("opencvBuildInformation",org.opencv.core.Core.getBuildInformation()).put("renders",reports)
            File(context.getExternalFilesDir(null),"resource-results.json").writeText(report.toString(2))
        } finally { root.deleteRecursively() }
    }
    private fun managed():Long=Runtime.getRuntime().let { it.totalMemory()-it.freeMemory() }
}
