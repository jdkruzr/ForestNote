package com.forestnote.core.ink

/**
 * The off-page pen policy as a tiny per-stroke state machine, shared by every pen feeder
 * (the DrawView MotionEvent path and the Boox firmware raw-input path both terminate in the same
 * [StrokeSink], so putting the policy here keeps stored stroke data identical across platforms).
 *
 * Policy: a stroke whose DOWN sample is off-page is dropped entirely — [admit] returns null for the
 * DOWN and for every following MOVE/UP until the next [begin] (so no partial stroke materializes).
 * A stroke that starts on-page but wanders into the letterbox margin has its off-page samples
 * clamped to the page edge, so the stroke stays continuous (like writing into a ruler). Clamping is
 * pure [PageBounds]; this class only owns the DOWN-rejection latch.
 */
class PageBoundsGate {
    private var rejected = false

    /** Start a new stroke: clear the rejection latch. */
    fun begin() {
        rejected = false
    }

    /**
     * Admit one sample for the given [phase], returning the sample to record (possibly edge-clamped)
     * or null if it must be dropped. A DOWN off-page latches the whole stroke off.
     */
    fun admit(sample: InkSample, phase: InkPhase, longAxis: Int): InkSample? {
        if (rejected) return null
        if (phase == InkPhase.DOWN && !PageBounds.contains(sample.vx, sample.vy, longAxis)) {
            rejected = true
            return null
        }
        return PageBounds.clamp(sample, longAxis)
    }
}
