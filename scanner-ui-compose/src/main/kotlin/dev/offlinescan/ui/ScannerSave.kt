package dev.offlinescan.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.offlinescan.core.ExportFormat
import dev.offlinescan.core.ScanPage
import dev.offlinescan.export.isValidDocumentName

/** Local output options. The parent owns the filename and performs the actual export. */
@Composable
internal fun ScannerSave(
    pages: List<ScanPage>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    format: ExportFormat,
    onFormat: (ExportFormat) -> Unit,
    documentName: String,
    onDocumentNameChange: (String) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onFinish: () -> Unit,
    busy: Boolean,
    progress: Float,
    onCancel: () -> Unit,
    saveDestination: (@Composable (enabled: Boolean) -> Unit)? = null,
    pageBitmap: @Composable (ScanPage) -> Bitmap?
) {
    val selectedIndex = pages.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0
    val selectedPage = pages.getOrNull(selectedIndex)
    val selectedBitmap = selectedPage?.let { pageBitmap(it) }
    val orderedPageIds = pages.map { it.id }
    val chosenFormat = if (format == ExportFormat.PDF) ExportFormat.PDF else ExportFormat.JPEG
    val validName = isValidDocumentName(documentName)
    val focusManager = LocalFocusManager.current
    val filenameAccessibility = stringResource(R.string.save_filename_accessibility)
    val largeText = LocalDensity.current.fontScale > 1.3f

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("save-screen")
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScannerIconButton(
                glyph = ScannerGlyph.BACK,
                label = stringResource(R.string.save_back),
                onClick = onBack,
                enabled = !busy
            )
            ScannerIconButton(
                glyph = ScannerGlyph.CLOSE,
                label = stringResource(R.string.save_close_scan),
                onClick = onClose,
                enabled = !busy
            )
            if (largeText) Spacer(Modifier.weight(1f)) else Text(
                stringResource(R.string.save_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = { focusManager.clearFocus(); onFinish() },
                enabled = !busy && pages.isNotEmpty() && validName,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
            ) { Text(stringResource(R.string.save_action)) }
        }
        if (largeText) Text(stringResource(R.string.save_title),style=MaterialTheme.typography.titleLarge,
            modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=4.dp))

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                // Reordering can change this index without changing the selected page ID.
                Modifier.fillMaxWidth().height(224.dp).pointerInput(selectedIndex, orderedPageIds, busy) {
                    var dragDistance = 0f
                    val threshold = 64.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { dragDistance = 0f },
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            dragDistance += delta
                        },
                        onDragEnd = {
                            if (!busy && dragDistance <= -threshold && selectedIndex < pages.lastIndex) {
                                onSelect(pages[selectedIndex + 1].id)
                            } else if (!busy && dragDistance >= threshold && selectedIndex > 0) {
                                onSelect(pages[selectedIndex - 1].id)
                            }
                            dragDistance = 0f
                        },
                        onDragCancel = { dragDistance = 0f }
                    )
                },
                contentAlignment = Alignment.Center
            ) {
            NeighborPagePeeks(pages, selectedIndex, "save", pageBitmap)
                if (selectedBitmap != null) {
                    Image(
                        bitmap = selectedBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.save_page_preview, selectedIndex + 1),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp).size(width = 220.dp, height = 224.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else if (pages.isEmpty()) {
                    Text(stringResource(R.string.save_empty_preview), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                pluralStringResource(R.plurals.save_page_position, pages.size, selectedIndex + 1, pages.size),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = documentName,
                onValueChange = onDocumentNameChange,
                label = { Text(stringResource(R.string.save_filename_label)) },
                isError = documentName.isNotEmpty() && !validName,
                modifier = Modifier.fillMaxWidth().testTag("document-name").semantics {
                    contentDescription = filenameAccessibility
                },
                singleLine = true,
                enabled = !busy,
                supportingText = if (documentName.isNotEmpty() && !validName) {
                    { Text(stringResource(R.string.save_invalid_filename)) }
                } else null,
                leadingIcon = { SaveDocumentBadge(chosenFormat) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (chosenFormat == ExportFormat.PDF) ".pdf" else ".jpg",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        ScannerIconButton(
                            glyph = ScannerGlyph.CLOSE,
                            label = stringResource(R.string.save_clear_filename),
                            onClick = { onDocumentNameChange("") },
                            enabled = !busy && documentName.isNotEmpty()
                        )
                    }
                }
            )

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.save_format),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 4.dp)
                )
                Row(
                    Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SaveFormatChoice(
                        label = stringResource(R.string.save_pdf),
                        selected = chosenFormat == ExportFormat.PDF,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        onClick = { focusManager.clearFocus(); onFormat(ExportFormat.PDF) }
                    )
                    SaveFormatChoice(
                        label = stringResource(R.string.save_jpeg),
                        selected = chosenFormat == ExportFormat.JPEG,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        onClick = { focusManager.clearFocus(); onFormat(ExportFormat.JPEG) }
                    )
                }
            }

            if (saveDestination != null) {
                saveDestination(!busy)
            } else {
                OutlinedTextField(
                    value = stringResource(R.string.save_on_device), onValueChange = {},
                    label = { Text(stringResource(R.string.save_location_label)) }, readOnly = true,
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }

            if (busy) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.export_progress, (progress * 100).toInt()),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.action_cancel_export))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveFormatChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    val colors = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier.heightIn(min = 56.dp).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = shape,
        color = colors,
        contentColor = content,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected) {
                Text("✓", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 10.dp))
            }
            Text(label, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SaveDocumentBadge(format: ExportFormat) {
    val fill = if (format == ExportFormat.PDF) Color(0xFFD44949) else Color(0xFF345F9C)
    Box(Modifier.size(width = 34.dp, height = 40.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val page = Path().apply {
                moveTo(4.dp.toPx(), 1.dp.toPx())
                lineTo(w - 11.dp.toPx(), 1.dp.toPx())
                lineTo(w - 1.dp.toPx(), 11.dp.toPx())
                lineTo(w - 1.dp.toPx(), h - 1.dp.toPx())
                lineTo(4.dp.toPx(), h - 1.dp.toPx())
                close()
            }
            drawPath(page, fill)
            val fold = Path().apply {
                moveTo(w - 11.dp.toPx(), 1.dp.toPx())
                lineTo(w - 11.dp.toPx(), 11.dp.toPx())
                lineTo(w - 1.dp.toPx(), 11.dp.toPx())
                close()
            }
            drawPath(fold, Color.White.copy(alpha = .34f))
            drawPath(page, Color.White.copy(alpha = .85f), style = Stroke(width = 1.dp.toPx()))
        }
        // This label is part of the decorative file image, whose dimensions stay fixed like an icon.
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density,1f)) {
            Text(if (format == ExportFormat.PDF) "PDF" else "JPG", color = Color.White, fontSize = 9.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp))
        }
    }
}
