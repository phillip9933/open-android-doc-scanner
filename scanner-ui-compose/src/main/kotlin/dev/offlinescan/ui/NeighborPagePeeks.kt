package dev.offlinescan.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.offlinescan.core.ScanPage

/** Real adjacent-page slivers indicate swipe direction without adding buttons. */
@Composable
internal fun BoxScope.NeighborPagePeeks(
    pages: List<ScanPage>, selectedIndex: Int, tagPrefix: String,
    pageBitmap: @Composable (ScanPage) -> Bitmap?
) {
    listOf(-1, 1).forEach { direction ->
        pages.getOrNull(selectedIndex + direction)?.let { page ->
            key(page.id) {
                val bitmap = pageBitmap(page)
                val previous = direction < 0
                val shape = RoundedCornerShape(6.dp)
                Box(Modifier.align(if (previous) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 4.dp).width(16.dp).fillMaxHeight(.82f)
                    .clip(shape).background(Color.White.copy(alpha = .65f))
                    .border(1.dp, Color.White.copy(alpha = .45f), shape)
                    .testTag("$tagPrefix-${if (previous) "previous" else "next"}-peek")) {
                    bitmap?.let {
                        Image(it.asImageBitmap(), null, Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            alignment = if (previous) Alignment.CenterEnd else Alignment.CenterStart)
                    }
                }
            }
        }
    }
}
