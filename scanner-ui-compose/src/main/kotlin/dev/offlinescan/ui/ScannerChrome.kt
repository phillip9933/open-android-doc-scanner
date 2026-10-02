package dev.offlinescan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class ScannerGlyph { CLOSE, FLASH, IMPORT, SHUTTER, PAUSE, STOP, PLAY, CROP, ROTATE, FILTER, MORE, ADD, CHECK, DELETE, BACK, FORWARD, SETTINGS }

/** Small original line icons keep the scanner independent of an extra icon/runtime dependency. */
@Composable internal fun ScannerIconButton(glyph: ScannerGlyph, label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    val color = LocalContentColor.current.copy(alpha = if (enabled) 1f else .38f)
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp).semantics { contentDescription = label }) {
        Canvas(Modifier.size(24.dp)) {
            fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(color, Offset(x * size.width / 24, y * size.height / 24), Offset(a * size.width / 24, b * size.height / 24), 2.dp.toPx(), StrokeCap.Round)
            fun path(vararg points: Float) {
                val p = Path().apply { moveTo(points[0]*size.width/24, points[1]*size.height/24); for(i in 2 until points.size step 2) lineTo(points[i]*size.width/24, points[i+1]*size.height/24) }
                drawPath(p, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
            when(glyph) {
                ScannerGlyph.CLOSE -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
                ScannerGlyph.BACK -> { path(14f,5f,7f,12f,14f,19f) }
                ScannerGlyph.FORWARD -> { path(10f,5f,17f,12f,10f,19f) }
                ScannerGlyph.STOP -> path(6f,6f,18f,6f,18f,18f,6f,18f,6f,6f)
                ScannerGlyph.CHECK -> path(4f,12f,10f,18f,20f,6f)
                ScannerGlyph.ADD -> { line(12f,5f,12f,19f); line(5f,12f,19f,12f) }
                ScannerGlyph.PAUSE -> { line(8f,6f,8f,18f); line(16f,6f,16f,18f) }
                ScannerGlyph.PLAY -> path(8f,5f,19f,12f,8f,19f,8f,5f)
                ScannerGlyph.SHUTTER -> drawCircle(color, radius = size.width*.38f, style = Stroke(2.dp.toPx()))
                ScannerGlyph.FLASH -> path(13f,2f,5f,14f,11f,14f,10f,22f,19f,10f,13f,10f,13f,2f)
                ScannerGlyph.IMPORT -> { path(3f,5f,21f,5f,21f,19f,3f,19f,3f,5f); path(4f,17f,10f,11f,14f,15f,17f,12f,21f,16f); drawCircle(color, size.width*.07f, Offset(size.width*.7f,size.height*.37f)) }
                ScannerGlyph.CROP -> { path(7f,3f,7f,17f,21f,17f); path(3f,7f,17f,7f,17f,21f) }
                ScannerGlyph.ROTATE -> { drawArc(color,-160f,280f,false,Offset(size.width*.2f,size.height*.2f),androidx.compose.ui.geometry.Size(size.width*.6f,size.height*.6f),style=Stroke(2.dp.toPx())); path(3f,7f,3f,13f,9f,13f) }
                ScannerGlyph.FILTER -> { drawCircle(color,size.width*.27f,Offset(size.width*.4f,size.height*.4f),style=Stroke(2.dp.toPx())); drawCircle(color,size.width*.27f,Offset(size.width*.65f,size.height*.65f),style=Stroke(2.dp.toPx())) }
                ScannerGlyph.MORE -> listOf(5f,12f,19f).forEach { drawCircle(color,1.5.dp.toPx(),Offset(it*size.width/24,size.height/2)) }
                ScannerGlyph.DELETE -> { path(6f,7f,7f,21f,17f,21f,18f,7f); line(4f,6f,20f,6f); path(9f,6f,9f,3f,15f,3f,15f,6f) }
                ScannerGlyph.SETTINGS -> { for(y in listOf(6f,12f,18f)) line(3f,y,21f,y); drawCircle(color,2.dp.toPx(),Offset(size.width*.35f,size.height*.25f)); drawCircle(color,2.dp.toPx(),Offset(size.width*.65f,size.height*.5f)); drawCircle(color,2.dp.toPx(),Offset(size.width*.4f,size.height*.75f)) }
            }
        }
    }
}
