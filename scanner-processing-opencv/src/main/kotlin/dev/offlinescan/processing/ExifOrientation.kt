package dev.offlinescan.processing

import android.graphics.Matrix
import androidx.annotation.RestrictTo
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** Transform decoded pixels to the upright coordinate system used for detection and editing. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
fun uprightExifMatrix(source: File): Matrix {
    val exif = ExifInterface(source)
    return Matrix().apply {
        if (exif.isFlipped) postScale(-1f, 1f)
        postRotate(exif.rotationDegrees.toFloat())
    }
}
