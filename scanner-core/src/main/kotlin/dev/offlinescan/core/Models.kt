package dev.offlinescan.core

import java.io.File

enum class DetectionMode { STANDARD, AI }

enum class ScanMode { DOCUMENT, RECEIPT, PHOTO, CARD }
enum class Preset { ORIGINAL, COLOR_DOCUMENT, GRAYSCALE, BLACK_WHITE, PHOTO, AUTO }
enum class ExportFormat(val mimeType: String, val extension: String) {
    JPEG("image/jpeg", "jpg"), PNG("image/png", "png"), PDF("application/pdf", "pdf")
}
data class ScanConfig(
    val mode: ScanMode = ScanMode.DOCUMENT,
    val maxPages: Int = 50,
    val maxInputPixels: Long = 32_000_000,
    val maxOutputDimension: Int = 4096,
    val jpegQuality: Int = 92,
    val autoCapture: Boolean = true,
    val cardFrontBack: Boolean = false,
    val detectionMode: DetectionMode = DetectionMode.STANDARD
) {
    init { require(maxPages in 1..100); require(maxInputPixels in 1..64_000_000); require(maxOutputDimension in 256..8192); require(jpegQuality in 1..100) }
    val defaultPreset: Preset get() = if (mode == ScanMode.PHOTO) Preset.PHOTO else Preset.AUTO
    val pageLimit: Int get() = if (mode == ScanMode.CARD && cardFrontBack) minOf(2, maxPages) else maxPages
}
data class Point(val x: Double, val y: Double)
/** Clockwise corners in visually upright normalized image coordinates: TL, TR, BR, BL. */
data class Quad(val topLeft: Point, val topRight: Point, val bottomRight: Point, val bottomLeft: Point) {
    val points: List<Point> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)
    companion object { val FULL = Quad(Point(0.0,0.0),Point(1.0,0.0),Point(1.0,1.0),Point(0.0,1.0)) }
}
data class Edits(val corners: Quad = Quad.FULL, val rotationQuarterTurns: Int = 0,
    val preset: Preset = Preset.ORIGINAL, val brightness: Double = 0.0,
    val contrast: Double = 1.0, val illumination: Double = 0.0) {
    init { require(brightness in -0.25..0.25); require(contrast in 0.75..1.5); require(illumination in 0.0..0.5) }
}
data class ScanPage(val id: String, val original: File, val width: Int, val height: Int, val edits: Edits)
data class Detection(val corners: Quad?, val confidence: Double, val brightness: Double, val sharpness: Double, val sceneSignature: List<Double>, val documentDetailTiles: Int? = null)
data class ExportedPage(val width: Int, val height: Int, val warnings: List<String> = emptyList())
data class ScanOutput(val files: List<File>, val mimeType: String, val pages: List<ExportedPage>, val warnings: List<String> = emptyList()) {
    val pageCount: Int get() = pages.size
}
enum class ErrorCode { PERMISSION_DENIED, INVALID_IMAGE, INVALID_GEOMETRY, RESOURCE_LIMIT, PROCESSING, CAMERA, EXPORT, STORAGE }
data class ScanError(val code: ErrorCode, val message: String)
sealed interface ScanResult {
    data class Completed(val output: ScanOutput) : ScanResult
    data object Cancelled : ScanResult
    data class Failed(val error: ScanError) : ScanResult
}
class ScanException(val error: ScanError, cause: Throwable? = null) : Exception(error.message, cause)
fun interface Cancellation { fun check() }
val NeverCancelled = Cancellation { }
fun interface Progress { fun update(completed: Int, total: Int) }
/** Platform-free processing contract. Implementations own internal native/bitmap allocations. */
interface ImageProcessor {
    fun inspect(source: File, config: ScanConfig): Pair<Int, Int>
    fun detect(source: File, config: ScanConfig, cancellation: Cancellation = NeverCancelled): Detection
    fun render(source: File, edits: Edits, destination: File, format: ExportFormat, config: ScanConfig, cancellation: Cancellation = NeverCancelled): ExportedPage
}
