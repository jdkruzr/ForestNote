package com.forestnote.readerlab

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import com.forestnote.core.ink.*

/** Lab-only A/B switch. Production Boox routing and saved brush semantics are untouched. */
internal class LabPreviewBackend(
    private val native: InkBackend,
    private val canSwitch: Boolean = native is BooxInkBackend,
) : InkBackend by native {
    enum class Mode { AUTO, MATCHED, NATIVE }
    var mode = Mode.AUTO
        private set
    private var brush = BrushKind.FOUNTAIN
    private var suspended = true
    val matched: Boolean get() = canSwitch && when (mode) {
        Mode.AUTO -> CalligraphyNib.fallbackAngle(brush) != null
        Mode.MATCHED -> true
        Mode.NATIVE -> false
    }
    val description: String get() = when {
        matched -> "Matched preview · Android input"
        canSwitch -> "Native preview · approximate brush"
        else -> "${native.javaClass.simpleName} preview"
    }
    fun setMode(value: Mode) { mode = value; applySuspension(); if (matched) native.detachInput() }
    override fun ownsInput() = !matched && native.ownsInput()
    override fun usesFirmwareInk() = !matched && native.usesFirmwareInk()
    override fun setInputSuspended(suspended: Boolean) { this.suspended = suspended; applySuspension() }
    private fun applySuspension() = native.setInputSuspended(suspended || matched)
    override fun updatePen(penParams: PenParams) {
        brush = penParams.brushKind
        applySuspension()
        if (matched) native.detachInput()
        native.updatePen(penParams)
        applySuspension() // SDK style setters can silently turn raw ink back on.
    }
    override fun setActiveTool(tool: Tool) { native.setActiveTool(tool); applySuspension() }
    override fun attachInput(host: View, sink: StrokeSink, toolbarExcludeRects: List<Rect>) {
        applySuspension()
        if (matched) native.detachInput() else native.attachInput(host, sink, toolbarExcludeRects)
        applySuspension()
    }
    override fun onResumeReacquire() { native.onResumeReacquire(); applySuspension() }
    override fun startStroke(bitmap: Bitmap, viewLocation: IntArray) {
        if (!matched) native.startStroke(bitmap, viewLocation)
    }
    override fun renderSegment(dirtyRect: Rect) { if (!matched) native.renderSegment(dirtyRect) }
    override fun endStroke() { if (!matched) native.endStroke() }
    override fun commitInkStroke(bitmap: Bitmap, viewLocation: IntArray, dirtyRect: Rect) {
        if (!matched) native.commitInkStroke(bitmap, viewLocation, dirtyRect)
    }
    override fun reconcileRepaint(bitmap: Bitmap, viewLocation: IntArray, dirtyRect: Rect?) {
        if (!matched) native.reconcileRepaint(bitmap, viewLocation, dirtyRect)
    }
}
