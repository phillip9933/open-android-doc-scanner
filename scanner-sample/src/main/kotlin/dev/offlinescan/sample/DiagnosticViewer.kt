package dev.offlinescan.sample

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Read-only overview and one-screen-pixel detail viewer for an original scan. */
@Composable
fun DiagnosticViewer(file: File, onClose: () -> Unit) {
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        DiagnosticViewerContent(file,onClose)
    }
}

@Composable
private fun DiagnosticViewerContent(file:File,onClose:()->Unit) {
    val bitmapStore=remember(file.absolutePath) { DiagnosticBitmapStore() }
    var overview by remember(file.absolutePath) { mutableStateOf<Bitmap?>(null) }
    var sourceInfo by remember(file.absolutePath) { mutableStateOf<DiagnosticSourceInfo?>(null) }
    var overviewError by remember(file.absolutePath) { mutableStateOf<String?>(null) }
    var detailError by remember(file.absolutePath) { mutableStateOf<String?>(null) }
    var detailBitmap by remember(file.absolutePath) { mutableStateOf<Bitmap?>(null) }
    var detailMode by remember(file.absolutePath) { mutableStateOf(false) }
    var centerX by remember(file.absolutePath) { mutableFloatStateOf(50f) }
    var centerY by remember(file.absolutePath) { mutableFloatStateOf(50f) }

    DisposableEffect(bitmapStore) { onDispose { bitmapStore.dispose() } }

    LaunchedEffect(file.absolutePath,bitmapStore) {
        val produced=AtomicReference<Bitmap?>(null)
        try {
            val loaded=withContext(Dispatchers.IO) {
                val result=loadDiagnosticOverview(file)
                produced.set(result.bitmap)
                currentCoroutineContext().ensureActive()
                result
            }
            currentCoroutineContext().ensureActive()
            if(!bitmapStore.track(loaded.bitmap)) throw CancellationException("Viewer closed")
            produced.compareAndSet(loaded.bitmap,null)
            sourceInfo=loaded.info
            overview=loaded.bitmap
            overviewError=null
        } catch(error:CancellationException) {
            throw error
        } catch(error:Exception) {
            overviewError=error.message ?: "Cannot open this image"
        } finally {
            produced.getAndSet(null)?.let(bitmapStore::recycle)
        }
    }

    Surface(Modifier.fillMaxSize().systemBarsPadding(),color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(file.name,style=MaterialTheme.typography.titleMedium,maxLines=1)
                    sourceInfo?.let { Text("${it.uprightWidth} × ${it.uprightHeight} pixels",style=MaterialTheme.typography.bodySmall) }
                }
                Button(onClick=onClose) { Text("Close") }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={detailMode=false}) { Text("Overview") }
                Button(onClick={detailMode=true},enabled=sourceInfo!=null) { Text("100% detail") }
            }
            if(overviewError!=null) {
                Text(overviewError!!,color=MaterialTheme.colorScheme.error)
            } else if(overview==null) {
                Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) { CircularProgressIndicator() }
            } else if(!detailMode) {
                Image(overview!!.asImageBitmap(),contentDescription="Overview of ${file.name}",
                    modifier=Modifier.weight(1f).fillMaxWidth(),contentScale=ContentScale.Fit)
            } else {
                DetailViewport(file,sourceInfo!!,centerX,centerY,detailBitmap,detailError,
                    bitmapStore,Modifier.weight(1f).fillMaxWidth(),
                    onBitmap={ detailBitmap=it; detailError=null },onError={ detailError=it })
            }
            if(detailMode && sourceInfo!=null) {
                Text("Horizontal position  ${centerX.toInt()}%",style=MaterialTheme.typography.labelMedium)
                Slider(value=centerX,onValueChange={centerX=it},valueRange=0f..100f)
                Text("Vertical position  ${centerY.toInt()}%",style=MaterialTheme.typography.labelMedium)
                Slider(value=centerY,onValueChange={centerY=it},valueRange=0f..100f)
                Text("Detail is shown at 1 image pixel per device pixel. Sliders select the upright image center.",
                    style=MaterialTheme.typography.bodySmall)
            }
        }
    }

    LaunchedEffect(detailMode) {
        if(!detailMode) {
            val old=detailBitmap
            if(old!=null) {
                detailBitmap=null
                bitmapStore.retire(old)
            }
        }
    }
}

@Composable
private fun DetailViewport(
    file:File,
    info:DiagnosticSourceInfo,
    centerX:Float,
    centerY:Float,
    bitmap:Bitmap?,
    error:String?,
    store:DiagnosticBitmapStore,
    modifier:Modifier,
    onBitmap:(Bitmap)->Unit,
    onError:(String?)->Unit
) {
    BoxWithConstraints(modifier,contentAlignment=Alignment.Center) {
        val density=LocalDensity.current
        val widthPx=with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val heightPx=with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
        DetailDecodeEffect(file,info,centerX,centerY,widthPx,heightPx,bitmap,store,onBitmap,onError)
        if(error!=null) Text(error,color=MaterialTheme.colorScheme.error)
        else if(bitmap==null) CircularProgressIndicator()
        else Image(bitmap.asImageBitmap(),contentDescription="100 percent detail of ${file.name}",
            modifier=Modifier.align(Alignment.Center),contentScale=ContentScale.None)
    }
}

@Composable
private fun DetailDecodeEffect(
    file:File,
    info:DiagnosticSourceInfo,
    centerX:Float,
    centerY:Float,
    widthPx:Int,
    heightPx:Int,
    currentBitmap:Bitmap?,
    store:DiagnosticBitmapStore,
    onBitmap:(Bitmap)->Unit,
    onError:(String?)->Unit
) {
    LaunchedEffect(file.absolutePath,info,centerX,centerY,widthPx,heightPx,store) {
        var produced:Bitmap?=null
        var retired:Bitmap?=null
        try {
            delay(100)
            val old=currentBitmap
            val decoded=withContext(Dispatchers.IO) {
                decodeDiagnosticRegion(file,info,centerX/100f,centerY/100f,widthPx,heightPx).also { produced=it }
            }
            currentCoroutineContext().ensureActive()
            if(!store.track(decoded)) throw CancellationException("Viewer closed")
            produced=null
            onBitmap(decoded)
            retired=old?.takeIf { it!==decoded }
            onError(null)
        } catch(error:CancellationException) {
            throw error
        } catch(error:Exception) {
            onError(error.message ?: "Cannot decode detail region")
        } finally {
            retired?.let { old ->
                store.retire(old)
            }
            produced?.let(store::recycle)
        }
    }
}

private data class DiagnosticSourceInfo(val rawWidth:Int,val rawHeight:Int,val orientation:Int) {
    val uprightWidth:Int get()=DiagnosticImageGeometry.uprightSize(rawWidth,rawHeight,orientation).first
    val uprightHeight:Int get()=DiagnosticImageGeometry.uprightSize(rawWidth,rawHeight,orientation).second
}

private data class DiagnosticOverview(val info:DiagnosticSourceInfo,val bitmap:Bitmap)

private suspend fun loadDiagnosticOverview(file:File):DiagnosticOverview {
    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
    BitmapFactory.decodeFile(file.absolutePath,bounds)
    if(bounds.outWidth<=0 || bounds.outHeight<=0) error("The selected file is not a readable image")
    val orientation=runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL) }
        .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val info=DiagnosticSourceInfo(bounds.outWidth,bounds.outHeight,orientation)
    var sample=1
    while(bounds.outWidth/sample>OVERVIEW_EDGE || bounds.outHeight/sample>OVERVIEW_EDGE ||
        bounds.outWidth.toLong()/sample*(bounds.outHeight.toLong()/sample)>OVERVIEW_PIXELS) sample*=2
    val decoded=BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply { inSampleSize=sample; inPreferredConfig=Bitmap.Config.ARGB_8888 })
        ?: error("The selected file is not a readable image")
    return DiagnosticOverview(info,DiagnosticImageGeometry.orient(decoded,orientation))
}

private fun decodeDiagnosticRegion(file:File,info:DiagnosticSourceInfo,cx:Float,cy:Float,viewportWidth:Int,viewportHeight:Int):Bitmap {
    val uprightCropWidth=minOf(viewportWidth,info.uprightWidth).coerceAtLeast(1)
    val uprightCropHeight=minOf(viewportHeight,info.uprightHeight).coerceAtLeast(1)
    val centerU=(cx.coerceIn(0f,1f)*info.uprightWidth).toInt()
    val centerV=(cy.coerceIn(0f,1f)*info.uprightHeight).toInt()
    val left=(centerU-uprightCropWidth/2).coerceIn(0,info.uprightWidth-uprightCropWidth)
    val top=(centerV-uprightCropHeight/2).coerceIn(0,info.uprightHeight-uprightCropHeight)
    val uprightRect=Rect(left,top,left+uprightCropWidth,top+uprightCropHeight)
    val rawRect=DiagnosticImageGeometry.rawBoundsForUprightCrop(uprightRect,info.rawWidth,info.rawHeight,info.orientation)
    val decoder=BitmapRegionDecoder.newInstance(file.absolutePath,false) ?: error("Cannot open a detail decoder")
    try {
        val decoded=decoder.decodeRegion(rawRect,BitmapFactory.Options().apply { inPreferredConfig=Bitmap.Config.ARGB_8888 })
            ?: error("Cannot decode the selected detail region")
        return DiagnosticImageGeometry.orient(decoded,info.orientation)
    } finally {
        decoder.recycle()
    }
}

/** Pure EXIF geometry helpers shared with instrumentation regression tests. */
internal object DiagnosticImageGeometry {
    fun uprightSize(rawWidth:Int,rawHeight:Int,orientation:Int):Pair<Int,Int> =
        if(orientation in 5..8) rawHeight to rawWidth else rawWidth to rawHeight

    fun rawBoundsForUprightCrop(upright:Rect,rawWidth:Int,rawHeight:Int,orientation:Int):Rect {
        val uprightSize=uprightSize(rawWidth,rawHeight,orientation)
        require(upright.left>=0 && upright.top>=0 && upright.right<=uprightSize.first && upright.bottom<=uprightSize.second)
        return when(orientation) {
            2 -> Rect(rawWidth-upright.right,upright.top,rawWidth-upright.left,upright.bottom)
            3 -> Rect(rawWidth-upright.right,rawHeight-upright.bottom,rawWidth-upright.left,rawHeight-upright.top)
            4 -> Rect(upright.left,rawHeight-upright.bottom,upright.right,rawHeight-upright.top)
            5 -> Rect(upright.top,upright.left,upright.bottom,upright.right)
            6 -> Rect(upright.top,rawHeight-upright.right,upright.bottom,rawHeight-upright.left)
            7 -> Rect(rawWidth-upright.bottom,rawHeight-upright.right,rawWidth-upright.top,rawHeight-upright.left)
            8 -> Rect(rawWidth-upright.bottom,upright.left,rawWidth-upright.top,upright.right)
            else -> Rect(upright)
        }
    }

    fun orient(bitmap:Bitmap,orientation:Int):Bitmap {
        if(orientation !in 2..8) return bitmap
        val w=bitmap.width.toFloat(); val h=bitmap.height.toFloat()
        val matrix=Matrix().apply {
            setValues(when(orientation) {
                2 -> floatArrayOf(-1f,0f,w, 0f,1f,0f, 0f,0f,1f)
                3 -> floatArrayOf(-1f,0f,w, 0f,-1f,h, 0f,0f,1f)
                4 -> floatArrayOf(1f,0f,0f, 0f,-1f,h, 0f,0f,1f)
                5 -> floatArrayOf(0f,1f,0f, 1f,0f,0f, 0f,0f,1f)
                6 -> floatArrayOf(0f,-1f,h, 1f,0f,0f, 0f,0f,1f)
                7 -> floatArrayOf(0f,-1f,h, -1f,0f,w, 0f,0f,1f)
                else -> floatArrayOf(0f,1f,0f, -1f,0f,w, 0f,0f,1f)
            })
        }
        val result=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,false)
        if(result!==bitmap) bitmap.recycle()
        return result
    }
}

private class DiagnosticBitmapStore {
    private val bitmaps=mutableSetOf<Bitmap>()
    private var disposed=false
    @Synchronized fun track(bitmap:Bitmap):Boolean {
        if(disposed) { bitmap.recycle(); return false }
        bitmaps.add(bitmap)
        return true
    }
    @Synchronized fun recycle(bitmap:Bitmap) {
        bitmaps.remove(bitmap)
        if(!bitmap.isRecycled) bitmap.recycle()
    }
    // A displayed bitmap may still be referenced by a RenderThread display list. Drop our
    // ownership instead of recycling it underneath Canvas; the runtime frees it when safe.
    @Synchronized fun retire(bitmap:Bitmap) { bitmaps.remove(bitmap) }
    @Synchronized fun dispose() {
        disposed=true
        bitmaps.clear()
    }
}

private const val OVERVIEW_EDGE=1024
private const val OVERVIEW_PIXELS=1_048_576L
