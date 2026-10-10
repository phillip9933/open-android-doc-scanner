package dev.offlinescan.sample

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.activity.compose.setContent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.offlinescan.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SavedScanPanelTest {
    val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule val rules:org.junit.rules.RuleChain=org.junit.rules.RuleChain.outerRule(
        androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.CAMERA)).around(compose)
    @Test fun completedScanIsPublicAndOriginalDetailCanBeInspected() {
        Assume.assumeTrue(Build.VERSION.SDK_INT>=29)
        val context=compose.activity
        val root=File(context.filesDir,"scans/diagnostic-ui-${System.nanoTime()}").apply{mkdirs()}
        var output:ScanOutput?=null
        try {
            val source=File(root,"original.jpg");val raster=File(root,"page.jpg")
            val bitmap=Bitmap.createBitmap(1800,2400,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            android.graphics.Canvas(bitmap).drawText("Small text 123.45",600f,1200f,android.graphics.Paint().apply{color=Color.BLACK;textSize=40f})
            source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,98,it)};bitmap.recycle();source.copyTo(raster)
            val result=ScanOutput(listOf(raster),"image/jpeg",listOf(ExportedPage(1800,2400)),diagnostics=ScanDiagnostics(listOf(source),listOf(raster)))
            output=result
            rememberCompleted(context,result)
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15000){File(root,"public-copies.json").exists()}
            compose.onNodeWithText("Image folder: Pictures/Offline Scanner").assertExists()
            compose.onNodeWithText("Capture: 1800 × 2400 pixels").assertExists()
            compose.onNodeWithText("Export: 1800 × 2400 pixels").assertExists()
            compose.onNodeWithText("Inspect original").performScrollTo()
            compose.waitForIdle()
            screenshot("quality2-result-screen.png")
            compose.onNodeWithText("Inspect original").performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText("1800 × 2400 pixels").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("100% detail").performClick()
            compose.waitUntil(10000){compose.onAllNodesWithContentDescription("100 percent detail of original.jpg").fetchSemanticsNodes().isNotEmpty()}
            compose.waitForIdle()
            screenshot("quality2-native-detail.png")
            compose.onNodeWithText("Close").performClick()
            // Retrying a completed public copy must reuse its URI, not create another row.
            val first=publishOutput(context,result,NeverCancelled)
            assertEquals(first,publishOutput(context,result,NeverCancelled))
            val uri=first[raster.path]!!
            context.contentResolver.openInputStream(uri)!!.use{assertArrayEquals(raster.readBytes(),it.readBytes())}
            assertNotNull(androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",source))
        } finally {
            output?.let{runCatching{publishOutput(context,it,NeverCancelled).values.forEach{uri->context.contentResolver.delete(uri,null,null)}}}
            root.deleteRecursively()
        }
    }
    private fun screenshot(name:String) {
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        try {File(instrumentation.targetContext.getExternalFilesDir(null),name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {bitmap.recycle()}
    }
}
private fun MainActivity.setContentForDiagnosticsTest(content:@androidx.compose.runtime.Composable ()->Unit) = setContent(content=content)
