package dev.offlinescan.sample

import android.Manifest
import android.graphics.BitmapFactory
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.offlinescan.core.ScanConfig
import dev.offlinescan.core.ScanMode
import dev.offlinescan.core.ScanResult
import dev.offlinescan.ui.ScannerFlow
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/** Exercises the bundled model through the actual settings and live camera, without detector injection. */
class ScannerDetectionModeTest {
    private val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule val rules:RuleChain=RuleChain.outerRule(GrantPermissionRule.grant(Manifest.permission.CAMERA)).around(compose)

    @Test fun aiLiveCameraAndSwitchingBackPreserveTheCapturedPageAndJpegExport() {
        val activity=compose.activity
        val destination=File(activity.filesDir,"detection-mode-test-${System.nanoTime()}")
        val result=AtomicReference<ScanResult?>(null)
        try {
            awaitCamera()
            compose.onNodeWithContentDescription("Scanner settings").performClick()
            compose.onNodeWithTag("detection-mode-ai").assertIsSelected()
            compose.onNodeWithText("Done").performClick()
            compose.runOnUiThread {
                activity.setContent {
                    var show by remember { mutableStateOf(true) }
                    val fontScale=InstrumentationRegistry.getArguments().getString("uiFontScale")?.toFloatOrNull() ?: 1f
                    MaterialTheme(colorScheme=darkColorScheme()) {
                        CompositionLocalProvider(LocalDensity provides Density(activity.resources.displayMetrics.density,fontScale)) {
                            if(show) ScannerFlow(ScanConfig(mode=ScanMode.PHOTO,autoCapture=false,detectionMode=dev.offlinescan.core.DetectionMode.AI),destination) { result.set(it); show=false }
                        }
                    }
                }
            }
            awaitCamera()
            compose.onNodeWithTag("capture-mode-button").assert(hasText("Position a Photo"))
            compose.onNodeWithTag("auto-scanning-indicator").assertDoesNotExist()
            compose.onNodeWithContentDescription("Scanner settings").performClick()
            compose.onNodeWithTag("detection-mode-ai").assertIsSelected()
            compose.onNodeWithTag("detection-mode-standard").assertIsNotSelected().performClick()
            awaitCamera()
            compose.onNodeWithContentDescription("Scanner settings").performClick()
            compose.onNodeWithTag("detection-mode-standard").assertIsSelected()
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300,2000)
            File(activity.getExternalFilesDir(null),"rc8-settings-${InstrumentationRegistry.getArguments().getString("uiFontScale", "1")}.png").outputStream().use {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                    try { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } finally { bitmap.recycle() }
                }
            }
            compose.onNodeWithTag("detection-mode-ai").assertIsNotSelected().performClick()
            compose.onNodeWithText("Detection mode").assertDoesNotExist()
            // Selecting a mode clears cameraReady. The shutter becomes enabled only after the
            // newly bound camera delivers real analysis through the selected learned detector.
            awaitCamera()
            compose.onNodeWithContentDescription("Scanner settings").performClick()
            compose.onNodeWithTag("detection-mode-ai").assertIsSelected()
            compose.onNodeWithTag("detection-mode-standard").assertIsNotSelected()
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithTag("manual-shutter").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Review page 1").fetchSemanticsNodes().isNotEmpty() || hasError() }
            assertNoError()
            compose.onNodeWithContentDescription("Review page 1").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Page 1 of 1").fetchSemanticsNodes().isNotEmpty() || hasError() }
            assertNoError()
            compose.onNodeWithTag("review-back").performClick()
            awaitCamera()
            val before=thumbnailPixels()
            compose.onNodeWithContentDescription("Scanner settings").performClick()
            compose.onNodeWithTag("detection-mode-standard").performClick()
            awaitCamera()
            compose.onNodeWithContentDescription("Review page 1").assertExists()
            compose.onNodeWithContentDescription("Review page 2").assertDoesNotExist()
            assertArrayEquals("Switching detectors must preserve the exact captured preview",before,thumbnailPixels())
            compose.onNodeWithContentDescription("Scanner settings").performClick()
            compose.onNodeWithTag("detection-mode-standard").assertIsSelected()
            compose.onNodeWithTag("detection-mode-ai").assertIsNotSelected()
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithContentDescription("Review page 1").performClick()
            compose.onNodeWithText("Next").performClick()
            compose.onNodeWithTag("save-screen").assertExists()
            compose.onNodeWithText("JPEG").performScrollTo().performClick()
            compose.onNodeWithTag("document-name").performScrollTo().performTextReplacement("Detection mode test")
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(30_000) { result.get()!=null || hasError() }
            val value=result.get()
            assertTrue("Expected completed JPEG after switching detectors, got $value",value is ScanResult.Completed)
            val output=(value as ScanResult.Completed).output
            assertEquals(1,output.pageCount); assertEquals("image/jpeg",output.mimeType)
            val jpeg=output.files.single()
            assertEquals("Detection mode test.jpg",jpeg.name)
            val bitmap=BitmapFactory.decodeFile(jpeg.absolutePath)
            assertNotNull(bitmap)
            bitmap!!.let { try { assertTrue(it.width>1 && it.height>1) } finally { it.recycle() } }
            assertTrue(jpeg.exists())
        } finally {
            compose.runOnUiThread { activity.setContent {} }
            destination.deleteRecursively()
        }
    }

    private fun awaitCamera() {
        compose.waitUntil(20_000) {
            hasError() || runCatching { compose.onNodeWithTag("manual-shutter").assertIsEnabled() }.isSuccess
        }
        assertNoError()
        compose.onNodeWithTag("manual-shutter").assertIsEnabled()
    }

    private fun hasError()=compose.onAllNodesWithText("Could not complete scan").fetchSemanticsNodes().isNotEmpty()
    private fun assertNoError() { compose.onNodeWithText("Could not complete scan").assertDoesNotExist() }

    private fun thumbnailPixels():IntArray {
        val bitmap=compose.onNodeWithContentDescription("Review page 1").captureToImage().asAndroidBitmap()
        return IntArray(bitmap.width*bitmap.height).also { bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height) }
    }
}
