package com.forestnote.core.ink

/**
 * One ingested ink point, already past [PageTransform] — virtual coordinates +
 * millipressure. This is the device-agnostic ingest unit: a MotionEvent feeder
 * (Viwoods/Generic) and a firmware raw-input feeder (Boox/Onyx, Phase 2) both produce
 * [InkSample]s via [from], so the downstream [StrokeSink] never sees device pixels or
 * device-specific pressure scales.
 *
 * @param vx Virtual x coordinate (short axis = [PageTransform.VIRTUAL_SHORT_AXIS])
 * @param vy Virtual y coordinate
 * @param millipressure Stylus pressure as millipressure (0..1000)
 * @param timestampMs Epoch milliseconds when this point was captured
 */
data class InkSample(
    val vx: Int,
    val vy: Int,
    val millipressure: Int,
    val timestampMs: Long,
) {
    companion object {
        /**
         * Build an [InkSample] from a captured screen point, normalizing through
         * [transform]. [pressure] is the 0.0–1.0 stylus pressure (a firmware feeder that
         * reports raw counts must divide by its device max before calling this). This is
         * the single screen-pixel→virtual+millipressure conversion shared by every feeder,
         * so the rounding is identical regardless of input source.
         */
        fun from(
            screenX: Float,
            screenY: Float,
            pressure: Float,
            timestampMs: Long,
            transform: PageTransform,
        ): InkSample = InkSample(
            vx = transform.toVirtualX(screenX),
            vy = transform.toVirtualY(screenY),
            millipressure = transform.toMillipressure(pressure),
            timestampMs = timestampMs,
        )
    }
}
