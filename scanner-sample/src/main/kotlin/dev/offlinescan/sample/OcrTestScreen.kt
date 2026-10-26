package dev.offlinescan.sample

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.offlinescan.core.*
import dev.offlinescan.ocr.TesseractTextRecognizer
import java.io.File
import kotlinx.coroutines.*

/** Sample-only inspection of OCR on the exact rendered scan, including PDF diagnostic pages. */
@Composable
internal fun OcrTestScreen(image: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var languages by remember { mutableStateOf(listOf("eng", "jpn")) }
    var layout by remember { mutableStateOf(OcrLayout.DOCUMENT) }
    var result by remember { mutableStateOf<OcrPage?>(null) }
    var elapsed by remember { mutableLongStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var inspect by remember { mutableStateOf(false) }
    var region by remember { mutableStateOf<OcrRegion?>(null) }
    var selectRegion by remember { mutableStateOf(false) }
    var numberField by remember { mutableStateOf(false) }
    var highlights by remember { mutableStateOf(true) }
    Dialog(onDismissRequest=onClose, properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("Test text recognition", style=MaterialTheme.typography.titleLarge)
                    TextButton(onClick=onClose) { Text("Close") }
                }
                Text("Runs offline on this exported page. Check names and numbers against the image before using them.")
                listOf("English + Japanese" to listOf("eng", "jpn"), "English" to listOf("eng"), "Japanese" to listOf("jpn")).forEach { (label, ids) ->
                    FilterChip(selected=languages==ids, enabled=!busy, onClick={languages=ids; result=null}, label={Text(label)})
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("Document" to OcrLayout.DOCUMENT, "Card / scattered text" to OcrLayout.SPARSE).forEach { (label, value) ->
                        FilterChip(selected=layout==value, enabled=!busy, onClick={layout=value; result=null}, label={Text(label)})
                    }
                }
                OutlinedButton(enabled=!busy, onClick={selectRegion=true}) {
                    Text(if(region == null) "Select text or number region" else "Change selected region")
                }
                if(region != null) {
                    Text("Recognition is limited to your selected rectangle.")
                    TextButton(enabled=!busy, onClick={region=null; numberField=false; result=null}) { Text("Use whole page") }
                    Row {
                        Switch(checked=numberField, enabled=!busy, onCheckedChange={numberField=it; result=null})
                        Text("Digits only · single number line")
                    }
                    Text("Use digits only for a printed numeric field. Verify every digit; this does not validate a card number.")
                }
                Button(enabled=!busy, onClick={
                    busy=true; error=null; result=null
                    val config=OcrConfig(languages=if(numberField) listOf("eng") else languages,
                        layout=if(numberField) OcrLayout.SINGLE_LINE else layout,
                        region=region, characterWhitelist=if(numberField) "0123456789" else null)
                    scope.launch {
                        try {
                            val recognized=withContext(Dispatchers.IO) {
                                val job=currentCoroutineContext()
                                val cancellation=Cancellation { job.ensureActive() }
                                val directory=installSampleOcrModels(context, cancellation)
                                val start=SystemClock.elapsedRealtime()
                                val page=TesseractTextRecognizer(directory).recognize(image, image.name, config, cancellation)
                                page to (SystemClock.elapsedRealtime()-start)
                            }
                            result=recognized.first; elapsed=recognized.second
                        } catch(e: CancellationException) { throw e }
                        catch(e: Exception) { error=e.message ?: "Text recognition failed" }
                        finally { busy=false }
                    }
                }) { Text(if(busy) "Recognizing…" else "Recognize text") }
                if(busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("You can close this screen; an active native recognition call finishes before cleanup.")
                }
                error?.let { Text(it, color=MaterialTheme.colorScheme.error) }
                result?.let { page ->
                    Text("${page.words.size} words · ${elapsed} ms · ${page.width} × ${page.height}")
                    Text(if(numberField) "English digits · single line · selected region"
                        else "${languages.joinToString(" + ")} · ${layout.name.lowercase()} · ${if(region == null) "whole page" else "selected region"}")
                    Row {
                        Switch(checked=highlights, onCheckedChange={highlights=it})
                        Text("Highlight recognized words")
                    }
                    OcrPagePreview(image, page, highlights)
                    OutlinedButton(onClick={inspect=true}) { Text("Inspect image at 100%") }
                    Text("Recognized text — long press to select and copy", style=MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text(page.text.ifBlank { "No text found. Try the other layout or retake the scan." }) }
                }
            }
        }
    }
    if(selectRegion) OcrRegionSelector(image, region, onClose={selectRegion=false}) {
        region=it; result=null; selectRegion=false
    }
    if(inspect) DiagnosticViewer(image) { inspect=false }
}

@Composable
private fun OcrPagePreview(image: File, page: OcrPage, highlights: Boolean) {
    val preview by produceState<android.graphics.Bitmap?>(null, image) {
        value=withContext(Dispatchers.IO) {
            var sample=1
            while(maxOf(page.width, page.height)/sample>1600) sample*=2
            BitmapFactory.decodeFile(image.path, BitmapFactory.Options().apply { inSampleSize=sample })
        }
    }
    preview?.let { bitmap ->
        val pixels=remember(bitmap) { bitmap.asImageBitmap() }
        Canvas(Modifier.fillMaxWidth().aspectRatio(page.width.toFloat()/page.height)) {
            drawImage(pixels, dstSize=IntSize(size.width.toInt(), size.height.toInt()))
            if(highlights) page.words.forEach { word ->
                val sx=size.width/page.width; val sy=size.height/page.height
                drawRect(Color(0xFF006E90), Offset(word.left*sx, word.top*sy),
                    Size((word.right-word.left)*sx, (word.bottom-word.top)*sy), style=Stroke(1.dp.toPx()))
            }
        }
    }
}

/** Models are debug assets, installed in private storage and verified again by the SDK. */
internal fun installSampleOcrModels(context: Context, cancellation: Cancellation): File {
    val directory=File(context.filesDir, "ocr-models")
    val data=File(directory, "tessdata")
    check(data.isDirectory || data.mkdirs()) { "Cannot create OCR model folder" }
    for(language in listOf("eng", "jpn")) {
        cancellation.check()
        val target=File(data, "$language.traineddata")
        if(target.isFile) continue
        val temporary=File.createTempFile("model-", ".tmp", data)
        try {
            try {
                context.assets.open("$language.traineddata").use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
            } catch(e: java.io.FileNotFoundException) {
                throw IllegalStateException("This test build has no OCR models. Run tools/prepare_ocr_test_models.py and rebuild the debug app.", e)
            }
            cancellation.check()
            check(temporary.renameTo(target)) { "Cannot install OCR model" }
        } finally { temporary.delete() }
    }
    return directory
}

/** Selection uses normalized coordinates in the exact upright OCR raster. */
@Composable
private fun OcrRegionSelector(image: File, initial: OcrRegion?, onClose: () -> Unit, onSelect: (OcrRegion) -> Unit) {
    var selected by remember { mutableStateOf(initial) }
    val preview by produceState<android.graphics.Bitmap?>(null, image) {
        value=withContext(Dispatchers.IO) {
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeFile(image.path, bounds)
            var sample=1
            while(maxOf(bounds.outWidth, bounds.outHeight)/sample>1600) sample*=2
            BitmapFactory.decodeFile(image.path, BitmapFactory.Options().apply { inSampleSize=sample })
        }
    }
    Dialog(onDismissRequest=onClose, properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Select a region", style=MaterialTheme.typography.titleLarge)
                Text("Drag a rectangle around the text. Include all digits and a little blank space. The saved scan is unchanged.")
                Row {
                    TextButton(onClick=onClose) { Text("Cancel") }
                    Button(enabled=selected != null, onClick={selected?.let(onSelect)}) { Text("Use selected region") }
                }
                preview?.let { bitmap ->
                    val pixels=remember(bitmap) { bitmap.asImageBitmap() }
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                        val ratio=bitmap.width.toFloat()/bitmap.height
                        val displayWidth=minOf(maxWidth, maxHeight*ratio)
                        Canvas(Modifier.width(displayWidth).aspectRatio(ratio).testTag("ocr-region-image").pointerInput(image) {
                            var start=Offset.Zero
                            var end=Offset.Zero
                            detectDragGestures(onDragStart={start=it; end=it; selected=null},
                                onDragCancel={selected=null}) { change, delta ->
                                change.consume()
                                end+=delta
                                val left=(minOf(start.x,end.x)/size.width).coerceIn(0f,1f).toDouble()
                                val right=(maxOf(start.x,end.x)/size.width).coerceIn(0f,1f).toDouble()
                                val top=(minOf(start.y,end.y)/size.height).coerceIn(0f,1f).toDouble()
                                val bottom=(maxOf(start.y,end.y)/size.height).coerceIn(0f,1f).toDouble()
                                selected=if(right-left>0.005 && bottom-top>0.005) OcrRegion(left,top,right,bottom) else null
                            }
                        }) {
                            drawImage(pixels,dstSize=IntSize(size.width.toInt(),size.height.toInt()))
                            selected?.let { r ->
                                drawRect(Color(0xFF006E90),Offset((r.left*size.width).toFloat(),(r.top*size.height).toFloat()),
                                    Size(((r.right-r.left)*size.width).toFloat(),((r.bottom-r.top)*size.height).toFloat()),style=Stroke(3.dp.toPx()))
                            }
                        }
                    }
                } ?: Text("Loading image…")
            }
        }
    }
}
