package dev.offlinescan.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.selected
import dev.offlinescan.core.Preset
import dev.offlinescan.core.ExportFormat
import dev.offlinescan.core.ScanPage

@Composable
internal fun ScannerReview(
    pages: List<ScanPage>,
    selectedId: String?,
    pageLimit: Int,
    format: ExportFormat,
    onFormat: (ExportFormat) -> Unit,
    onSelect: (String) -> Unit,
    onEdit: () -> Unit,
    onAppearance: () -> Unit,
    onRotate: () -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onAdd: () -> Unit,
    onRetake: () -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
    onEnhance: () -> Unit,
    busy: Boolean,
    progress: Float,
    onCancelExport: () -> Unit,
    onBack: () -> Unit,
    pageBitmap: @Composable (ScanPage) -> Bitmap?
) {
    val selectedIndex = pages.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: pages.lastIndex
    val selected = pages.getOrNull(selectedIndex)
    val selectedBitmap = selected?.let { pageBitmap(it) }
    val orderedPageIds = pages.map { it.id }
    val thumbnailState = rememberLazyListState()
    var moreExpanded by remember { mutableStateOf(false) }
    var zoomed by remember(selected?.id,selected?.edits?.corners,selected?.edits?.rotationQuarterTurns) { mutableStateOf(false) }

    LaunchedEffect(selected?.id, pages.size) {
        if (selectedIndex >= 0) thumbnailState.animateScrollToItem(selectedIndex)
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("review-screen")
    ) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                // Reordering can change this index without changing the selected page ID.
                .pointerInput(selectedIndex, orderedPageIds, busy, zoomed) {
                    if(zoomed) return@pointerInput
                    val threshold=64.dp.toPx()
                    awaitEachGesture {
                        val down=awaitFirstDown(requireUnconsumed=false)
                        var delta=androidx.compose.ui.geometry.Offset.Zero
                        var valid=true
                        do {
                            val event=awaitPointerEvent()
                            if(event.changes.count { it.pressed || it.previousPressed }>1) valid=false
                            val change=event.changes.firstOrNull { it.id==down.id }
                            if(change!=null && change.pressed) {
                                if(change.isConsumed) valid=false
                                delta+=change.position-change.previousPosition
                                if(valid && kotlin.math.abs(delta.x)>viewConfiguration.touchSlop && kotlin.math.abs(delta.x)>kotlin.math.abs(delta.y)) change.consume()
                            }
                        } while(event.changes.any { it.pressed })
                        if(valid && !busy && kotlin.math.abs(delta.x)>kotlin.math.abs(delta.y)) {
                            if(delta.x<=-threshold && selectedIndex<pages.lastIndex) onSelect(pages[selectedIndex+1].id)
                            else if(delta.x>=threshold && selectedIndex>0) onSelect(pages[selectedIndex-1].id)
                        }
                    }
                }
                .testTag("review-page"),
            contentAlignment = Alignment.Center
        ) {
            if(!zoomed) NeighborPagePeeks(pages, selectedIndex, "review", pageBitmap)
            if (selected != null && selectedBitmap != null) {
                ZoomablePageImage(selectedBitmap,
                    stringResource(R.string.review_page_position, selectedIndex + 1, pages.size),
                    Triple(selected.id, selected.edits.corners, selected.edits.rotationQuarterTurns),
                    Modifier.fillMaxSize().padding(horizontal = if (pages.size > 1) 32.dp else 12.dp, vertical = 12.dp),
                    onZoomChanged={zoomed=it})
            } else if (selected != null) {
                Text(stringResource(R.string.review_preview_loading), style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.review_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.review_empty_hint), style = MaterialTheme.typography.bodyMedium)
                }
            }

            ScannerIconButton(
                glyph = ScannerGlyph.BACK,
                label = stringResource(R.string.review_back),
                onClick = onBack,
                enabled = selected != null && !busy,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).testTag("review-back")
                    .clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f))
            )

            Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                ScannerIconButton(
                    glyph = ScannerGlyph.MORE,
                    label = stringResource(R.string.review_more),
                    onClick = { moreExpanded = true },
                    enabled = selected != null && !busy,
                    modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f))
                )
                DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.review_move_up)) },
                        onClick = { moreExpanded = false; selected?.let { onMove(it.id, (selectedIndex - 1).coerceAtLeast(0)) } },
                        enabled = selectedIndex > 0 && !busy,
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.review_move_down)) },
                        onClick = { moreExpanded = false; selected?.let { onMove(it.id, (selectedIndex + 1).coerceAtMost(pages.lastIndex)) } },
                        enabled = selectedIndex in 0 until pages.lastIndex && !busy,
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = .92f)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScannerIconButton(
                    glyph = ScannerGlyph.ROTATE,
                    label = stringResource(R.string.review_retake),
                    onClick = onRetake,
                    enabled = selected != null && !busy
                )
                ScannerIconButton(
                    glyph = ScannerGlyph.DELETE,
                    label = stringResource(R.string.review_delete_page),
                    onClick = { selected?.let { onRemove(it.id) } },
                    enabled = selected != null && !busy
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
                .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(onClick = onEnhance, enabled = selected != null && !busy, modifier = Modifier.heightIn(min = 48.dp).semantics { this.selected = selected?.edits?.preset == Preset.AUTO }) {
                Text((if(selected?.edits?.preset==Preset.AUTO) "\u2713 " else "")+stringResource(R.string.review_enhance))
            }
            FilledTonalButton(onClick = onAppearance, enabled = selected != null && !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.review_filters))
            }
            FilledTonalButton(onClick = onEdit, enabled = selected != null && !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.review_crop_rotate))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(start = 10.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                .testTag("review-thumbnails"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                state = thumbnailState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
                    // Keep each thumbnail's renderer mounted regardless of which page is selected.
                    val thumbnail = pageBitmap(page)
                    val label = stringResource(R.string.review_thumbnail_description, index + 1)
                    Box(
                        modifier = Modifier.size(width = 60.dp, height = 78.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                width = if (page.id == selected?.id) 2.dp else 1.dp,
                                color = if (page.id == selected?.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable(enabled = !busy, onClickLabel = label) { onSelect(page.id) }
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center
                    ) {
                        if (thumbnail != null) {
                            Image(thumbnail.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize().padding(4.dp).testTag("review-thumbnail-${index+1}"), contentScale = ContentScale.Fit)
                        } else {
                            CircularProgressIndicator(modifier=Modifier.size(16.dp),strokeWidth=1.dp)
                        }
                        Box(
                            modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp)
                                .clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text("${index + 1}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            ScannerIconButton(
                glyph = ScannerGlyph.ADD,
                label = stringResource(R.string.review_add_page),
                onClick = onAdd,
                enabled = !busy && pages.size < pageLimit,
                modifier = Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
            )
        }

        if (busy) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.export_progress, (progress * 100).toInt()), modifier = Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium)
                ScannerIconButton(ScannerGlyph.CLOSE, stringResource(R.string.action_cancel_export), onCancelExport, enabled = true)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onDiscard,
                enabled = !busy,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
            ) { Text(stringResource(R.string.review_discard), style = MaterialTheme.typography.labelLarge) }
            Button(
                onClick = onFinish,
                enabled = pages.isNotEmpty() && !busy,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
            ) { Text(stringResource(R.string.review_next), style = MaterialTheme.typography.labelLarge) }
        }
    }
}
