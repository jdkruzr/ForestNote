package com.forestnote.core.ink

/**
 * The phase of a single ingested ink sample within a stroke gesture.
 *
 * Device-agnostic: a MotionEvent feeder maps ACTION_DOWN/MOVE/UP onto these, and a
 * firmware raw-input feeder (Boox/Onyx, Phase 2) maps its begin/move/end callbacks the
 * same way. The [StrokeSink] uses the phase to decide whether to seed the stroke (DOWN),
 * extend it (MOVE), or finalize + persist it (UP).
 */
enum class InkPhase { DOWN, MOVE, UP }
