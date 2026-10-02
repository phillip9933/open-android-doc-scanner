package dev.offlinescan.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.offlinescan.core.*
import dev.offlinescan.ui.ScannerFlow
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme=if(isSystemInDarkTheme()) darkColorScheme(primary=Color(0xFFABC9F6),onPrimary=Color(0xFF163359),background=Color(0xFF111315),surface=Color(0xFF202327),surfaceVariant=Color(0xFF25292E),secondaryContainer=Color(0xFF303940)) else lightColorScheme(primary=Color(0xFF345F9C),background=Color(0xFFF8FAFD),surface=Color(0xFFF8FAFD),surfaceVariant=Color(0xFFE8EDF4))) {
                var scanning by remember { mutableStateOf(true) }; var result by remember { mutableStateOf<ScanResult?>(null) }
                var mode by remember { mutableStateOf(ScanMode.DOCUMENT) }
                var showModes by remember { mutableStateOf(false) }
                Surface(Modifier.fillMaxSize()) {
                    if(scanning) ScannerFlow(ScanConfig(mode=mode,cardFrontBack=mode==ScanMode.CARD,detectionMode=DetectionMode.AI),File(filesDir,"scans")) { result=it; scanning=false }
                    else Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.app_name),style=MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.mode))
                        TextButton(onClick={showModes=!showModes}) { Text(mode.name.lowercase().replaceFirstChar{it.uppercase()}) }
                        if(showModes) ScanMode.entries.forEach { option ->
                            val label=when(option) { ScanMode.DOCUMENT->R.string.document; ScanMode.RECEIPT->R.string.receipt; ScanMode.PHOTO->R.string.photo; ScanMode.CARD->R.string.card }
                            OutlinedButton(onClick={mode=option;showModes=false},modifier=Modifier.fillMaxWidth()) { Text((if(mode==option) "✓ " else "")+stringResource(label)) }
                        }
                        Button(onClick={result=null;scanning=true}) { Text(stringResource(R.string.start)) }
                        when(val value=result) {
                            is ScanResult.Completed -> Text(stringResource(R.string.completed,value.output.pageCount,value.output.mimeType))
                            is ScanResult.Cancelled -> Text(stringResource(R.string.cancelled))
                            is ScanResult.Failed -> Text(stringResource(R.string.failed,value.error.message))
                            null -> Unit
                        }
                    }
                }
            }
        }
    }
}
