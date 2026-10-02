package dev.offlinescan.camera

/** Decision only; values come from the shared, fixed-resolution document-interior metric. */
internal object CaptureQualityPolicy {
    data class Sample(val sharpness: Double, val brightness: Double, val detailTiles: Int,
        val consistentDocument: Boolean)

    fun acceptable(sample: Sample, referenceSharpness: Double, referenceDetailTiles: Int?): Boolean {
        if (!sample.consistentDocument || !sample.sharpness.isFinite() || sample.sharpness < 0.0 || !sample.brightness.isFinite() ||
            sample.brightness !in 0.18..0.98 || sample.detailTiles !in 0..9) return false
        // Low-detail paper has too little texture for a blur score. Do not mistake vanished text for blank paper.
        if (sample.detailTiles < 2) return referenceDetailTiles in 0..1
        return sample.sharpness >= 60.0 && (!referenceSharpness.isFinite() || sample.sharpness >= referenceSharpness * 0.70)
    }

    fun better(candidate: Sample, incumbent: Sample?): Boolean =
        incumbent == null || candidate.sharpness > incumbent.sharpness
}
