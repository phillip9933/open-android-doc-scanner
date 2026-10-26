package dev.offlinescan.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OcrTestScreenTest {
    private val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule val rules:org.junit.rules.RuleChain=org.junit.rules.RuleChain.outerRule(
        androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.CAMERA)).around(compose)

    @Test fun recognizesRenderedPageAndShowsTextAndInspection() {
        val image=File(compose.activity.cacheDir, "ocr-ui-test.png")
        val bitmap=Bitmap.createBitmap(1400,600,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        Canvas(bitmap).drawText("MEMBER 001234",80f,240f,Paint().apply {color=Color.BLACK;textSize=90f;isAntiAlias=true})
        image.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
        try {
            compose.activity.runOnUiThread {
                compose.activity.setContent {MaterialTheme {OcrTestScreen(image) {}}}
            }
            compose.onNodeWithText("English").performClick()
            compose.onNodeWithText("Card / scattered text").performClick()
            compose.onNodeWithText("Recognize text").performScrollTo().performClick()
            compose.waitUntil(60000) {
                compose.onAllNodesWithText("MEMBER 001234", substring=true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Highlight recognized words").assertExists()
            compose.onNodeWithText("Inspect image at 100%").performScrollTo().performClick()
            compose.onNodeWithText("100% detail").assertExists()
            compose.onAllNodesWithText("Close").onLast().performClick()
            compose.onNodeWithText("Japanese").performScrollTo().performClick()
            compose.onNodeWithText("Highlight recognized words").assertDoesNotExist()
        } finally {image.delete()}
    }
}
