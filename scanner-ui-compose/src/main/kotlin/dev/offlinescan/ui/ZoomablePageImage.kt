package dev.offlinescan.ui

import android.graphics.Bitmap
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Fit-to-page gestures leave horizontal drags to the page carousel; zoomed drags pan. */
@Composable internal fun ZoomablePageImage(bitmap: Bitmap, description: String, resetKey: Any,
    modifier: Modifier = Modifier, onZoomChanged: (Boolean) -> Unit = {}) {
    var scale by remember(resetKey) { mutableFloatStateOf(1f) }
    var pan by remember(resetKey) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val latestZoomCallback by rememberUpdatedState(onZoomChanged)
    fun clamp(value: Offset, zoom: Float): Offset {
        if (viewport.width == 0 || viewport.height == 0) return Offset.Zero
        val fit = minOf(viewport.width.toFloat()/bitmap.width, viewport.height.toFloat()/bitmap.height)
        val x = ((bitmap.width*fit*zoom-viewport.width)/2).coerceAtLeast(0f)
        val y = ((bitmap.height*fit*zoom-viewport.height)/2).coerceAtLeast(0f)
        return Offset(value.x.coerceIn(-x,x),value.y.coerceIn(-y,y))
    }
    val transform = rememberTransformableState { zoom, delta, _ ->
        scale = (scale*zoom).coerceIn(1f,4f)
        pan = clamp(pan+delta,scale)
    }
    LaunchedEffect(scale > 1.01f, resetKey) { latestZoomCallback(scale > 1.01f) }
    val fitLabel = stringResource(R.string.zoom_fit)
    val zoomLabel = stringResource(R.string.zoom_in)
    Box(modifier.clipToBounds().onSizeChanged { viewport=it;pan=clamp(pan,scale) }
        .testTag("zoomable-page").semantics {
            stateDescription="${(scale*100).roundToInt()}%"
            customActions=listOf(CustomAccessibilityAction(zoomLabel) { scale=2.5f;true },
                CustomAccessibilityAction(fitLabel) { scale=1f;pan=Offset.Zero;true })
        }.transformable(transform, canPan={scale>1.01f})
        .pointerInput(resetKey) { detectTapGestures(onDoubleTap={
            if(scale>1.01f) { scale=1f;pan=Offset.Zero }
            else { scale=2.5f;pan=clamp((Offset(size.width/2f,size.height/2f)-it)*(scale-1f),scale) }
        }) }) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().semantics { contentDescription=description }) {
            val fit=minOf(size.width/bitmap.width,size.height/bitmap.height)
            val width=(bitmap.width*fit*scale).roundToInt().coerceAtLeast(1)
            val height=(bitmap.height*fit*scale).roundToInt().coerceAtLeast(1)
            drawImage(bitmap.asImageBitmap(),
                dstOffset=androidx.compose.ui.unit.IntOffset(((size.width-width)/2+pan.x).roundToInt(),((size.height-height)/2+pan.y).roundToInt()),
                dstSize=IntSize(width,height),filterQuality=androidx.compose.ui.graphics.FilterQuality.Medium)
        }
        if(scale>1.01f) TextButton(onClick={scale=1f;pan=Offset.Zero},
            modifier=Modifier.align(Alignment.BottomStart).padding(8.dp)) { Text(fitLabel) }
    }
}
