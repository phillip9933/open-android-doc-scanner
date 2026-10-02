package dev.offlinescan.sample

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import dev.offlinescan.core.*
import dev.offlinescan.ui.ScannerFlow
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the actual ready-made UI using synthetic content and a deterministic system-picker result. */
class ScannerFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun importCropAppearanceReviewAndPdfComplete() {
        val context=compose.activity
        val source=File(context.cacheDir,"host-test-${System.nanoTime()}.png")
        val destination=File(context.filesDir,"ui-export-${System.nanoTime()}")
        val bitmap=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(Color.rgb(80,130,180)); source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() }
        val result=AtomicReference<ScanResult?>(null)
        val pickedSource=AtomicReference(source)
        val registry=object: ActivityResultRegistry() {
            override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
                Handler(Looper.getMainLooper()).post { dispatchResult(requestCode,Activity.RESULT_OK,Intent().setData(Uri.fromFile(pickedSource.get()))) }
            }
        }
        val registryOwner=object: ActivityResultRegistryOwner { override val activityResultRegistry=registry }
        try {
            compose.activity.runOnUiThread {
                compose.activity.setContentForTest {
                    var show by remember { mutableStateOf(true) }
                    MaterialTheme(colorScheme=darkColorScheme()) { CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner, LocalDensity provides Density(compose.activity.resources.displayMetrics.density,androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("uiFontScale")?.toFloatOrNull() ?: 2f)) {
                        if(show) ScannerFlow(ScanConfig(mode=ScanMode.PHOTO,autoCapture=false),destination) { result.set(it); show=false }
                    } }
                }
            }
            val emptyPreviewBounds=compose.onNodeWithTag("capture-preview").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithText("Next").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Import image").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Review page 1").fetchSemanticsNodes().isNotEmpty() }
            // Adding a page keeps the camera open; editing is optional, not a capture interruption.
            compose.onNodeWithTag("capture-screen").assertExists()
            assertEquals("Adding the first page must not resize the camera",emptyPreviewBounds,
                compose.onNodeWithTag("capture-preview").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("review-screen").assertDoesNotExist()
            File(context.getExternalFilesDir(null),"continuous-capture-large-font-dark.png").outputStream().use {
                compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
            }
            compose.onNodeWithContentDescription("Review page 1").performClick()
            compose.onNodeWithTag("review-screen").assertExists()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("zoomable-page").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("zoomable-page").performTouchInput {
                val a=center-androidx.compose.ui.geometry.Offset(30f,0f)
                val b=center+androidx.compose.ui.geometry.Offset(30f,0f)
                down(0,a);down(1,b)
                for(i in 1..10) { advanceEventTime(16);moveTo(0,a-androidx.compose.ui.geometry.Offset(i*8f,0f));moveTo(1,b+androidx.compose.ui.geometry.Offset(i*8f,0f)) }
                up(0);up(1)
            }
            compose.onNodeWithText("Fit page").assertExists().performClick()
            compose.onNodeWithTag("zoomable-page").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"100%"))
            compose.onNodeWithTag("review-back").performClick()
            compose.onNodeWithTag("capture-screen").assertExists()
            compose.onNodeWithContentDescription("Review page 1").assertExists()
            compose.onNodeWithText("Retake this page?").assertDoesNotExist()
            compose.onNodeWithTag("capture-mode-button").performClick()
            listOf("Document","Photograph","Receipt","Card").forEach { compose.onNodeWithText(it,substring=false).assertExists() }
            compose.onNodeWithTag("capture-mode-document").performClick()
            compose.onNodeWithTag("capture-mode-button").assert(hasText("Document",substring=true))
            compose.onNodeWithContentDescription("Review page 1").performClick()
            compose.onNodeWithText("Crop & rotate").performScrollTo().performClick()
            compose.onNodeWithText("Horizontal position").assertDoesNotExist()
            compose.onNodeWithContentDescription("Precise adjustment").performClick()
            compose.onNodeWithText("Horizontal position").assertExists()
            File(context.getExternalFilesDir(null),"crop-large-font-dark.png").outputStream().use {
                compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
            }
            compose.onNodeWithText("Done").performScrollTo().performClick()
            compose.onNodeWithText("Apply").performClick()
            compose.onNodeWithText("Filters").performScrollTo().performClick()
            compose.onNodeWithTag("appearance-presets").performScrollToNode(hasContentDescription("Photo"))
            compose.onNodeWithContentDescription("Photo").assertIsSelected()
            compose.onNodeWithText("Brightness: 0.00").assertDoesNotExist()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("zoomable-page").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("zoomable-page").performTouchInput { doubleClick() }
            compose.onNodeWithText("Fit page").assertExists()
            compose.onNodeWithTag("appearance-presets").performScrollToNode(hasContentDescription("Original"))
            compose.onNodeWithContentDescription("Original",substring=false).performClick()
            compose.onNodeWithText("Fit page").assertExists().performClick()
            compose.onNodeWithContentDescription("Adjustments").performClick()
            compose.onNodeWithText("Brightness: 0.00").assertExists()
            compose.onNodeWithText("Done").performScrollTo().performClick()
            compose.onNodeWithText("Apply").performClick()
            compose.onNodeWithContentDescription("Add page").performClick()
            val portrait=File(context.cacheDir,"host-portrait-${System.nanoTime()}.png")
            val second=Bitmap.createBitmap(480,640,Bitmap.Config.ARGB_8888)
            try { second.eraseColor(Color.rgb(170,90,60)); portrait.outputStream().use { second.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { second.recycle() }
            pickedSource.set(portrait)
            try {
                compose.onNodeWithContentDescription("Import image").performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Review page 2").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("capture-screen").assertExists()
                compose.onNodeWithText("Next").performClick()
                compose.onNodeWithText("Filters").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Auto").assertIsSelected()
                compose.onNodeWithText("Apply").performClick()
                compose.onNodeWithContentDescription("More page actions").performClick()
                compose.onNodeWithText("Move page left").performClick()
                compose.onNodeWithContentDescription("Show previous page").assertDoesNotExist()
                compose.onNodeWithContentDescription("Show next page").assertDoesNotExist()
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Page 1 of 2").fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(5_000) { (1..2).all { index -> compose.onAllNodesWithTag("review-thumbnail-$index",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() } }
                compose.onNodeWithTag("review-next-peek").assertExists()
                compose.onNodeWithTag("zoomable-page").performTouchInput { doubleClick() }
                compose.onNodeWithText("Fit page").assertExists()
                compose.onNodeWithContentDescription("Page 1 of 2").performTouchInput { swipeLeft() }
                compose.onNodeWithContentDescription("Page 1 of 2").assertExists()
                compose.onNodeWithText("Fit page").performClick()
                compose.onNodeWithTag("review-previous-peek").assertDoesNotExist()
                compose.onNodeWithContentDescription("Page 1 of 2").performTouchInput { swipeLeft() }
                compose.onNodeWithTag("review-thumbnail-1",useUnmergedTree=true).assertExists()
                compose.onNodeWithTag("review-thumbnail-2",useUnmergedTree=true).assertExists()
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Page 2 of 2").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("review-previous-peek").assertExists()
                compose.onNodeWithTag("review-next-peek").assertDoesNotExist()
                compose.onNodeWithContentDescription("Page 2 of 2").performTouchInput { swipeRight() }
                compose.onNodeWithTag("review-thumbnail-1",useUnmergedTree=true).assertExists()
                compose.onNodeWithTag("review-thumbnail-2",useUnmergedTree=true).assertExists()
                compose.onNodeWithText("Preview unavailable").assertDoesNotExist()
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Page 1 of 2").fetchSemanticsNodes().isNotEmpty() }
                // Asking and cancelling either action must leave both pages intact.
                compose.onNodeWithContentDescription("Delete page").performClick()
                compose.onNodeWithText("Delete this page?").assertExists()
                compose.onNodeWithText("Keep page").performClick()
                compose.onNodeWithContentDescription("Page 1 of 2").assertExists()
                compose.onNodeWithContentDescription("Retake page").performClick()
                compose.onNodeWithText("Retake this page?").assertExists()
                compose.onNodeWithText("Keep page").performClick()
                compose.onNodeWithContentDescription("Page 1 of 2").assertExists()
                // Confirmed retake discards only the selected page; replacement returns to its position.
                compose.onNodeWithContentDescription("Retake page").performClick()
                compose.onNodeWithText("Retake",substring=false).performClick()
                compose.onNodeWithTag("capture-screen").assertExists()
                compose.onNodeWithContentDescription("Review page 2").assertDoesNotExist()
                compose.onNodeWithContentDescription("Import image").performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Page 1 of 2").fetchSemanticsNodes().isNotEmpty() }
                // Deleting the other page leaves this retake; an ordinary add appends again.
                compose.onNodeWithContentDescription("Page 1 of 2").performTouchInput { swipeLeft() }
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Page 2 of 2").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Delete page").performClick()
                compose.onNodeWithText("Delete",substring=false).performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Page 1 of 1").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Add page").performClick()
                pickedSource.set(source)
                compose.onNodeWithContentDescription("Import image").performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Review page 2").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Next").performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Page 2 of 2").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Page 2 of 2").performTouchInput { swipeRight() }
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Page 1 of 2").fetchSemanticsNodes().isNotEmpty() }
                File(context.getExternalFilesDir(null),"review-large-font-dark.png").outputStream().use {
                    compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
                }
                compose.onNodeWithText("Next").performClick()
                compose.onNodeWithTag("save-screen").assertExists()
                compose.onNodeWithContentDescription("Show previous page").assertDoesNotExist()
                compose.onNodeWithContentDescription("Show next page").assertDoesNotExist()
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Preview of page 1").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("save-next-peek").assertExists()
                compose.onNodeWithTag("save-previous-peek").assertDoesNotExist()
                compose.onNodeWithContentDescription("Preview of page 1").performTouchInput { swipeLeft() }
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Preview of page 2").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("save-previous-peek").assertExists()
                compose.onNodeWithTag("save-next-peek").assertDoesNotExist()
                compose.onNodeWithContentDescription("Preview of page 2").performTouchInput { swipeRight() }
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Preview of page 1").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Preview of page 1").performTouchInput { swipeLeft() }
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Preview of page 2").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Back to page review").performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Page 2 of 2").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Next").performClick()
                compose.onNodeWithContentDescription("Close scan").performClick()
                compose.onNodeWithText("Discard scans?").assertExists()
                compose.onNodeWithText("Keep editing").performClick()
                compose.onNodeWithTag("save-screen").assertExists()
                compose.onNodeWithContentDescription("Preview of page 2").performTouchInput { swipeRight() }
                compose.onNodeWithText("PNG").assertDoesNotExist()
                compose.onNodeWithTag("document-name").performScrollTo().performTextReplacement("../outside")
                compose.onNodeWithText("Save").assertIsNotEnabled()
                compose.onNodeWithTag("document-name").performTextReplacement("Test scan")
                compose.onNodeWithText("JPEG").performScrollTo().performClick()
                compose.onNodeWithText(".jpg").assertExists()
                compose.onNodeWithText("PDF").performScrollTo().performClick()
                compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Save scan").assertIsDisplayed() }.isSuccess }
                File(context.getExternalFilesDir(null),"save-large-font-dark.png").outputStream().use {
                    compose.onNodeWithTag("save-screen").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
                }
                compose.onNodeWithText("Save").performClick()
            } finally { portrait.delete() }
            compose.waitUntil(30_000) { result.get()!=null }
            val value=result.get(); assertTrue("Expected Completed, got $value",value is ScanResult.Completed)
            val output=(value as ScanResult.Completed).output
            assertEquals(2,output.pageCount); assertTrue(output.pages.first().height>output.pages.first().width); assertTrue(output.pages.last().width>output.pages.last().height)
            assertEquals("application/pdf",output.mimeType); assertTrue(output.files.single().length()>0); assertTrue(source.exists())
            assertEquals("Test scan.pdf",output.files.single().name)
            compose.waitForIdle(); assertTrue(output.files.single().exists())
        } finally { source.delete(); destination.deleteRecursively() }
    }
    @Test fun cancelReturnsTypedCancellationOnce() {
        val result=AtomicReference<ScanResult?>(null); var callbacks=0
        compose.activity.runOnUiThread {
            compose.activity.setContentForTest {
                var show by remember { mutableStateOf(true) }
                MaterialTheme { if(show) ScannerFlow(ScanConfig(autoCapture=false),File(compose.activity.filesDir,"unused")) { callbacks++; result.set(it); show=false } }
            }
        }
        compose.onNodeWithContentDescription("Cancel").performClick()
        compose.waitUntil(5_000) { result.get()!=null }
        assertEquals(ScanResult.Cancelled,result.get()); assertEquals(1,callbacks)
    }
    @Test fun malformedImportReturnsTypedFailureWithoutDeletingHostSource() {
        val context=compose.activity; val source=File(context.cacheDir,"bad-host-${System.nanoTime()}.img").apply { writeText("synthetic invalid image") }
        val result=AtomicReference<ScanResult?>(null)
        val registry=object:ActivityResultRegistry() {
            override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
                Handler(Looper.getMainLooper()).post { dispatchResult(requestCode,Activity.RESULT_OK,Intent().setData(Uri.fromFile(source))) }
            }
        }
        val owner=object:ActivityResultRegistryOwner { override val activityResultRegistry=registry }
        try {
            compose.activity.runOnUiThread { compose.activity.setContentForTest { MaterialTheme { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ScannerFlow(ScanConfig(autoCapture=false),File(context.filesDir,"unused")) { result.set(it) }
            } } } }
            compose.onNodeWithContentDescription("Import image").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Return error").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Return error").performClick()
            compose.waitUntil(5_000) { result.get()!=null }
            assertTrue(result.get() is ScanResult.Failed); assertTrue(source.exists())
        } finally { source.delete() }
    }
    @Test fun emulatorCameraManualCaptureAndCancel() {
        val context=compose.activity
        android.os.ParcelFileDescriptor.AutoCloseInputStream(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant dev.offlinescan.sample android.permission.CAMERA")).use { it.readBytes() }
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.CAMERA))
        val result=AtomicReference<ScanResult?>(null)
        compose.activity.runOnUiThread { compose.activity.setContentForTest { MaterialTheme { ScannerFlow(ScanConfig(mode=ScanMode.PHOTO),File(context.filesDir,"unused-camera")) { result.set(it) } } } }
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("manual-shutter").assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("auto-scanning-indicator").assertDoesNotExist()
        compose.onNodeWithText("Position a Photo").assertExists()
        compose.onNodeWithTag("capture-mode-button").performClick()
        compose.onNodeWithTag("capture-mode-photo").assertIsSelected()
        compose.onNodeWithTag("capture-mode-document").performClick()
        compose.onNodeWithTag("auto-scanning-indicator").assertExists()
        compose.onNodeWithText("Position a Document").assertExists()
        compose.onNodeWithContentDescription("Pause automatic capture").performClick()
        for ((mode, label) in listOf("receipt" to "Position a Receipt", "card" to "Position a Card")) {
            compose.onNodeWithTag("capture-mode-button").performClick()
            compose.onNodeWithTag("capture-mode-$mode").performClick()
            compose.onNodeWithText(label).assertExists()
        }
        compose.onNodeWithTag("capture-mode-button").performClick()
        compose.onNodeWithTag("capture-mode-document").performClick()
        compose.onNodeWithTag("capture-preview").performTouchInput { doubleClick() }
        val focusSettleUntil = android.os.SystemClock.elapsedRealtime() + 1000
        compose.waitUntil(3000) { android.os.SystemClock.elapsedRealtime() >= focusSettleUntil }
        compose.onNodeWithText("Could not complete scan").assertDoesNotExist()
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("manual-shutter").assertIsEnabled() }.isSuccess }
        compose.onNodeWithContentDescription("Resume automatic capture").performClick()
        val cameraScreenshot=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull(cameraScreenshot)
        cameraScreenshot!!.let { frame ->
            try { File(context.getExternalFilesDir(null),"rc4-camera-fullscreen.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG,100,it) } }
            finally { frame.recycle() }
        }
        compose.onNodeWithContentDescription("Pause automatic capture").performClick()
        compose.onNodeWithTag("auto-scanning-indicator").assertDoesNotExist()
        compose.onNodeWithContentDescription("Resume automatic capture").assertExists()
        compose.onNodeWithContentDescription("Resume automatic capture").performClick()
        compose.onNodeWithTag("capture-mode-button").performClick()
        compose.onNodeWithTag("auto-scanning-indicator").assertDoesNotExist()
        compose.onNodeWithTag("capture-mode-photo").performClick()
        compose.onNodeWithContentDescription("Resume automatic capture").assertIsNotEnabled()
        compose.onNodeWithTag("manual-shutter").assertIsEnabled()
        compose.onNodeWithTag("capture-mode-button").performClick()
        compose.onNodeWithTag("capture-mode-document").performClick()
        compose.onNodeWithTag("auto-scanning-indicator").assertExists()
        compose.onNodeWithTag("manual-shutter").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Review page 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("capture-screen").assertExists()
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("manual-shutter").assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("manual-shutter").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Review page 2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("capture-screen").assertExists()
        compose.onNodeWithContentDescription("Cancel").performClick(); compose.onNodeWithText("Keep editing").performClick(); compose.onNodeWithTag("capture-screen").assertExists(); assertNull(result.get())
        compose.onNodeWithText("Next").performClick(); compose.onNodeWithText("Next").performClick()
        compose.onNodeWithContentDescription("Close scan").performClick(); compose.onNodeWithText("Discard").performClick(); compose.waitUntil(5_000) { result.get()!=null }
        assertEquals(ScanResult.Cancelled,result.get())
    }
}

private fun MainActivity.setContentForTest(content: @Composable ()->Unit) = setContent(content=content)



