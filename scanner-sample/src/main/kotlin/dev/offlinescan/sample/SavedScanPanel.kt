package dev.offlinescan.sample

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import dev.offlinescan.core.*
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Sample-only result UI; SDK callers continue to own storage and sharing. */
@Composable
internal fun SavedScanPanel(output: ScanOutput) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var ocrImage by remember { mutableStateOf<File?>(null) }
    var inspection by remember { mutableStateOf<File?>(null) }
    var published by remember(output) { mutableStateOf<Map<String,Uri>>(emptyMap()) }
    var busy by remember(output) { mutableStateOf(false) }
    var error by remember(output) { mutableStateOf<String?>(null) }
    var retry by remember(output) { mutableIntStateOf(0) }
    var pendingSave by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val createDocument=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val file=pendingSave; pendingSave=null
        val uri=result.data?.data
        if(result.resultCode==Activity.RESULT_OK && uri!=null && file!=null) scope.launch {
            busy=true
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri,"w")?.use { dest -> file.inputStream().use { it.copyTo(dest) } }
                        ?: error("Cannot open the selected document")
                }
                message="Saved ${file.name} to your selected folder."
            } catch(e:Exception) {
                runCatching {android.provider.DocumentsContract.deleteDocument(context.contentResolver,uri)}
                if(e is CancellationException) throw e
                error="Save failed: ${e.message}"
            }
            finally { busy=false }
        }
    }
    LaunchedEffect(output,retry) {
        if(Build.VERSION.SDK_INT>=29) {
            busy=true;error=null
            try {
                val rows=withContext(Dispatchers.IO) {
                    val coroutine=currentCoroutineContext()
                    publishOutput(context,output,Cancellation { coroutine.ensureActive() })
                }
                published=rows
            } catch(e:Exception) { if(e is CancellationException) throw e; error="Could not finish saving to the public folder: ${e.message}. Your private scan is retained; retry or share it." }
            finally {busy=false}
        }
    }
    fun share(file:File,view:Boolean=false) {
        try {
            val uri=published[file.path] ?: FileProvider.getUriForFile(context,"${context.packageName}.files",file)
            val mime=mimeFor(file)
            val intent=if(view) Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime)
                else Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData=ClipData.newRawUri(file.name,uri)
            context.startActivity(if(view) intent else Intent.createChooser(intent,"Share ${file.name}"))
        } catch(e:Exception) {error="No compatible app could open this file: ${e.message}"}
    }
    Text("Saved scan",style=MaterialTheme.typography.titleLarge)
    Text(if(Build.VERSION.SDK_INT>=29) {
        if(output.mimeType=="application/pdf") "PDF folder: Download/Offline Scanner" else "Image folder: Pictures/Offline Scanner"
    } else "Use Save to folder to choose Pictures or Downloads.")
    if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Saving files…")}
    error?.let { Text(it,color=MaterialTheme.colorScheme.error); if(Build.VERSION.SDK_INT>=29) TextButton(onClick={retry++},enabled=!busy){Text("Retry saving")}}
    message?.let {Text(it)}
    output.files.forEach { file ->
        Text(file.name,style=MaterialTheme.typography.titleMedium)
        if(published.containsKey(file.path)) Text("Available in Files${if(output.mimeType.startsWith("image/")) " and your gallery’s Offline Scanner album" else ""}.")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={share(file,true)}) {Text("Open")}
            OutlinedButton(onClick={share(file)}) {Text("Share")}
        }
        if(Build.VERSION.SDK_INT<29) OutlinedButton(onClick={
            pendingSave=file
            createDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mimeFor(file)).putExtra(Intent.EXTRA_TITLE,file.name))
        },enabled=!busy){Text("Save to folder")}
    }
    Text("Compare capture and export",style=MaterialTheme.typography.titleLarge)
    Text("Originals are untouched private copies. Use Share original to send one, or inspect the actual pixels below.")
    output.pages.forEachIndexed { index,page ->
        val original=output.diagnostics?.originals?.getOrNull(index)
        val rendered=output.diagnostics?.renderedPages?.getOrNull(index) ?: output.files.getOrNull(index)?.takeIf { output.mimeType.startsWith("image/") }
        val originalSize by produceState<Pair<Int,Int>?>(null,original) {
            value=withContext(Dispatchers.IO) {original?.let {imageDimensions(it)}}
        }
        Text("Page ${index+1}",style=MaterialTheme.typography.titleMedium)
        originalSize?.let { Text("Capture: ${it.first} × ${it.second} pixels") }
        Text("Export: ${page.width} × ${page.height} pixels")
        page.warnings.forEach {Text(it)}
        original?.let { file ->
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={inspection=file}) {Text("Inspect original")}
                OutlinedButton(onClick={share(file)}) {Text("Share original")}
            }
        }
        rendered?.let { file ->
            OutlinedButton(onClick={inspection=file}) {Text("Inspect export")}
            OutlinedButton(onClick={ocrImage=file}) {Text("Recognize text · page ${index+1}")}
        }
    }
    inspection?.let { DiagnosticViewer(it) {inspection=null} }
    ocrImage?.let { OcrTestScreen(it) {ocrImage=null} }
}

internal fun mimeFor(file:File):String {
    if(file.extension.lowercase()=="pdf")return "application/pdf"
    val options=BitmapFactory.Options().apply {inJustDecodeBounds=true}
    BitmapFactory.decodeFile(file.path,options)
    return options.outMimeType ?: "application/octet-stream"
}
internal fun imageDimensions(file:File):Pair<Int,Int>? = runCatching {
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds)
    require(bounds.outWidth>0 && bounds.outHeight>0)
    val orientation=ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)
    if(orientation in 5..8) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
}.getOrNull()

/** Journal each successful public copy so rotation/retry does not create duplicate images. */
internal fun publishOutput(context:Context,output:ScanOutput,cancellation:Cancellation):Map<String,Uri> {
    val journal=File(output.files.first().parentFile,"public-copies.json")
    val saved=if(journal.isFile) JSONObject(journal.readText()) else JSONObject()
    val results=linkedMapOf<String,Uri>()
    for(file in output.files) {
        cancellation.check()
        val key=file.name
        val previous=saved.optString(key).takeIf {it.isNotBlank()}?.let(Uri::parse)
        val exists=previous?.let {uri->runCatching {context.contentResolver.query(uri,arrayOf("_id"),null,null,null)?.use{it.moveToFirst()}==true}.getOrDefault(false)} ?: false
        val uri=if(exists) previous!! else {
            val unique="${file.nameWithoutExtension}-${file.parentFile!!.name.takeLast(8)}.${file.extension}"
            val published=ScanMediaPublisher.publish(context,file,output.mimeType,unique,cancellation)
            try {
                saved.put(key,published.uri.toString())
                val temporary=File(journal.parentFile,"public-copies.tmp")
                temporary.writeText(saved.toString())
                if(!temporary.renameTo(journal))throw java.io.IOException("Cannot record public copy")
            } catch(failure:Throwable) {context.contentResolver.delete(published.uri,null,null);throw failure}
            published.uri
        }
        results[file.path]=uri
    }
    return results
}

internal fun rememberCompleted(context:Context,output:ScanOutput) {
    val json=JSONObject().put("files",JSONArray(output.files.map {it.path})).put("mime",output.mimeType)
        .put("pages",JSONArray(output.pages.map {JSONObject().put("w",it.width).put("h",it.height).put("warnings",JSONArray(it.warnings))}))
    output.diagnostics?.let {json.put("originals",JSONArray(it.originals.map {f->f.path}));json.put("renders",JSONArray(it.renderedPages.map {f->f.path}))}
    context.getSharedPreferences("last-scan",Context.MODE_PRIVATE).edit().putString("output",json.toString()).apply()
}
internal fun lastCompleted(context:Context):ScanOutput?=runCatching {
    val json=JSONObject(context.getSharedPreferences("last-scan",Context.MODE_PRIVATE).getString("output",null) ?: return null)
    fun files(key:String)=json.optJSONArray(key)?.let {array->(0 until array.length()).map{File(array.getString(it))}} ?: emptyList()
    val files=files("files");require(files.isNotEmpty() && files.all{it.isFile})
    val pages=json.getJSONArray("pages");val dims=(0 until pages.length()).map { i->pages.getJSONObject(i).let{p->val w=p.optJSONArray("warnings");ExportedPage(p.getInt("w"),p.getInt("h"),w?.let{a->(0 until a.length()).map{a.getString(it)}} ?: emptyList())}}
    ScanOutput(files,json.getString("mime"),dims,diagnostics=if(json.has("originals"))ScanDiagnostics(files("originals"),files("renders")) else null)
}.getOrNull()
