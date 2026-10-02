package dev.offlinescan.sample

import android.Manifest
import android.graphics.BitmapFactory
import androidx.activity.compose.setContent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.test.rule.GrantPermissionRule
import dev.offlinescan.camera.ScannerCamera
import dev.offlinescan.core.Detection
import dev.offlinescan.core.Quad
import dev.offlinescan.core.ScanConfig
import dev.offlinescan.core.ScanError
import dev.offlinescan.processing.DocumentQuality
import dev.offlinescan.processing.OpenCvProcessor
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/** Uses real emulator camera images and real QC; supplied corners isolate detector accuracy. */
class ScannerAutomaticCaptureTest {
    private val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule val rules:RuleChain=RuleChain.outerRule(GrantPermissionRule.grant(Manifest.permission.CAMERA)).around(compose)
    private val config=ScanConfig(autoCapture=false)
    private val processor=OpenCvProcessor()
    private val fixedScene=List(64) { .5 }

    private data class Original(val sha256:String,val quality:DocumentQuality)
    private class Harness(val directory:File) {
        val camera=AtomicReference<ScannerCamera?>(null)
        val latest=AtomicReference<Detection?>(null)
        val frames=AtomicInteger()
        val errors=Collections.synchronizedList(mutableListOf<ScanError>())
        val originals=Collections.synchronizedList(mutableListOf<Original>())
    }

    @Test fun automaticCapturePublishesSharpestOriginalOfTwoAndRemovesStagingFiles() {
        val harness=mount(rejectStills=false)
        val destination=File(harness.directory,"accepted.jpg")
        val saved=AtomicReference<File?>(null)
        val rejected=AtomicReference<String?>(null)
        try {
            awaitReady(harness)
            compose.runOnUiThread { harness.camera.get()!!.capture(destination,true,{rejected.set(it)},{saved.set(it)}) }
            compose.waitUntil(20_000) { saved.get()!=null || rejected.get()!=null || harness.errors.isNotEmpty() }
            assertTrue("Camera errors: ${harness.errors}",harness.errors.isEmpty())
            assertNull("Automatic capture rejected real virtualscene pixels: ${harness.originals}",rejected.get())
            assertEquals(destination,saved.get())
            val originals=synchronized(harness.originals) { harness.originals.toList() }
            assertEquals("Automatic capture must compare exactly two encoded originals",2,originals.size)
            val best=originals.maxBy { it.quality.sharpness }
            assertEquals("Saved JPEG must be the chosen original, without recompression",best.sha256,sha256(destination))
            assertOnly(harness.directory,destination)
            val image=BitmapFactory.decodeFile(destination.absolutePath)
            assertNotNull("Delivered file must decode as an image",image)
            image!!.let { try { assertTrue(it.width>1 && it.height>1) } finally { it.recycle() } }
        } finally { dispose(harness) }
    }

    @Test fun rejectedAutomaticBurstLeavesNoOutputAndManualCaptureStillWorks() {
        val harness=mount(rejectStills=true)
        val destination=File(harness.directory,"rejected.jpg")
        val saved=AtomicInteger()
        val rejected=AtomicInteger()
        try {
            awaitReady(harness)
            compose.runOnUiThread { harness.camera.get()!!.capture(destination,true,{rejected.incrementAndGet()},{saved.incrementAndGet()}) }
            compose.waitUntil(20_000) { rejected.get()>0 || saved.get()>0 || harness.errors.isNotEmpty() }
            assertTrue("Camera errors: ${harness.errors}",harness.errors.isEmpty())
            assertEquals(1,rejected.get()); assertEquals(0,saved.get())
            assertEquals("Both encoded originals must be assessed before rejecting",2,harness.originals.size)
            assertFalse(destination.exists()); assertOnly(harness.directory)
            val manual=File(harness.directory,"manual-after-rejection.jpg")
            val delivered=AtomicReference<File?>(null)
            compose.runOnUiThread { harness.camera.get()!!.capture(manual) { delivered.set(it) } }
            compose.waitUntil(15_000) { delivered.get()!=null || harness.errors.isNotEmpty() }
            assertEquals("A rejected burst must release the capture gate",manual,delivered.get())
            assertOnly(harness.directory,manual)
            assertTrue(harness.errors.isEmpty())
        } finally { dispose(harness) }
    }

    @Test fun stoppingDuringFocusRejectsOnceAndResumeAllowsAnotherCapture() {
        val harness=mount(rejectStills=false)
        val destination=File(harness.directory,"interrupted.jpg")
        val saved=AtomicInteger()
        val rejected=AtomicInteger()
        try {
            awaitReady(harness)
            compose.runOnUiThread { harness.camera.get()!!.capture(destination,true,{rejected.incrementAndGet()},{saved.incrementAndGet()}) }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.waitUntil(5_000) { rejected.get()>0 }
            assertEquals(1,rejected.get()); assertEquals(0,saved.get())
            assertFalse(destination.exists()); assertOnly(harness.directory)
            val oldFrameCount=harness.frames.get()
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(15_000) { harness.frames.get()>oldFrameCount+2 || harness.errors.isNotEmpty() }
            assertTrue("Lifecycle resume camera errors: ${harness.errors}",harness.errors.isEmpty())
            val resumed=File(harness.directory,"manual-after-resume.jpg")
            val delivered=AtomicReference<File?>(null)
            compose.runOnUiThread { harness.camera.get()!!.capture(resumed) { delivered.set(it) } }
            compose.waitUntil(15_000) { delivered.get()!=null || harness.errors.isNotEmpty() }
            assertEquals("Interruption must release the capture gate",resumed,delivered.get())
            assertEquals("Interrupted automatic callbacks must not arrive after resume",1,rejected.get())
            assertEquals(0,saved.get()); assertFalse(destination.exists())
            assertOnly(harness.directory,resumed)
            assertTrue(harness.errors.isEmpty())
        } finally { dispose(harness) }
    }

    private fun mount(rejectStills:Boolean):Harness {
        val directory=File(compose.activity.cacheDir,"automatic-camera-${System.nanoTime()}").apply { check(mkdirs()) }
        val harness=Harness(directory)
        compose.runOnUiThread {
            compose.activity.setContent {
                val preview=remember { PreviewView(compose.activity).apply { scaleType=PreviewView.ScaleType.FILL_CENTER } }
                val camera=remember {
                    ScannerCamera(compose.activity,compose.activity,preview,config,
                        onAnalysis={ harness.latest.set(it); harness.frames.incrementAndGet() },
                        onAutoCapture={}, onError={ harness.errors.add(it) },
                        bitmapDetector={ bitmap,cancellation -> processor.measureDetectionBitmap(bitmap,Quad.FULL,.9,cancellation).copy(sceneSignature=fixedScene) },
                        stillDetector={ file,cancellation ->
                            val quality=processor.assessQuality(file,Quad.FULL,config,cancellation)
                            harness.originals.add(Original(sha256(file),quality))
                            Detection(if(rejectStills) null else Quad.FULL,if(rejectStills) 0.0 else .9,
                                quality.brightness,quality.sharpness,fixedScene,quality.informativeTiles)
                        }).also { harness.camera.set(it) }
                }
                AndroidView(factory={preview},modifier=Modifier.fillMaxSize())
                DisposableEffect(camera) { camera.bind(); onDispose { camera.close() } }
            }
        }
        return harness
    }

    private fun awaitReady(harness:Harness) {
        compose.waitUntil(15_000) {
            val current=harness.latest.get()
            harness.errors.isNotEmpty() || (harness.frames.get()>=3 && current!=null &&
                current.brightness in .18.. .98 && (current.sharpness>=60 || current.documentDetailTiles in 0..1))
        }
        assertTrue("Binding/analysis errors: ${harness.errors}",harness.errors.isEmpty())
        assertNotNull(harness.latest.get())
    }

    private fun assertOnly(directory:File,vararg expected:File) {
        assertEquals("Capture directory must contain only delivered originals",expected.map { it.name }.sorted(),directory.listFiles()!!.map { it.name }.sorted())
    }

    private fun dispose(harness:Harness) {
        compose.runOnUiThread { harness.camera.get()?.close() }
        harness.directory.deleteRecursively()
    }

    private fun sha256(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
}
