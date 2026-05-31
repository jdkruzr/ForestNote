package com.forestnote.core.ink

/**
 * The single ingest collaborator that turns a stream of [InkSample]s into a rendered +
 * persisted stroke. It is fed by EITHER input feeder:
 *
 *  - the MotionEvent feeder (Viwoods/Generic): `DrawView.onTouchEvent` → sink,
 *  - the firmware raw-input feeder (Boox/Onyx, Phase 2): `RawInputCallback` → sink.
 *
 * Extracting this seam is what lets a backend OWN input (see [InkBackend.ownsInput]) and
 * still reuse the app's exact stroke accumulation / bitmap rendering / persistence path.
 *
 * Lifecycle of one stroke: [begin] once, then [accept] for each sample (DOWN, then MOVE…,
 * then UP), or [cancel] to abandon an in-progress stroke (e.g. a tool switch mid-gesture).
 *
 * The concrete app-side implementation (`DrawViewStrokeSink`) lives in `app:notes` because
 * it touches the offscreen bitmap, the in-memory stroke list, and `NotebookStore`; only
 * this contract and the value types live in `core:ink`, preserving the module boundary.
 */
interface StrokeSink {
    /**
     * Open a new stroke with the active [tool] and resolved [penParams] (colour + width
     * range + behind-flag). Must be called before the DOWN [accept] of each gesture.
     */
    fun begin(tool: Tool, penParams: PenParams)

    /**
     * Ingest one [sample] at the given [phase]:
     *  - DOWN seeds the stroke (no segment drawn),
     *  - MOVE appends the point and draws the segment into the offscreen bitmap,
     *  - UP appends the final point, finalizes the immutable stroke, and persists it.
     *
     * Pushing the drawn region to the display is deferred to the implementation's own
     * flush step so a feeder can coalesce a batch of samples into a single display update.
     */
    fun accept(sample: InkSample, phase: InkPhase)

    /** Abandon the in-progress stroke without finalizing or persisting it. */
    fun cancel()
}
