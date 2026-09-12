package com.forestnote.readerlab

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import com.forestnote.core.ink.*

/** Lab-only A/B switch. Production Boox routing and saved brush semantics are untouched. */
internal class LabPreviewBackend(
    private val native: InkBackend,
    private val canSwitch: Boolean = native is BooxInkBackend,
    private val vendorDirect: Boolean = native is ViwoodsBackend,
) : InkBackend by native {
    init {
        if (vendorDirect) {
            native.setInputSuspended(true)
            native.setVendorNativePreviewEnabled(true)
        }
    }
    enum class Mode { AUTO, MATCHED, NATIVE }
    var mode = Mode.AUTO
        private set
    private var brush = BrushKind.FOUNTAIN
    private var tool: Tool = Tool.Pen
    private var suspended = true
    // Erasing must leave TouchHelper entirely, just like matched drawing. Disabling its
    // firmware switches alone can leave the listener holding the Android stylus stream.
    val matched: Boolean get() = canSwitch && (tool == Tool.StrokeEraser || when (mode) {
        Mode.AUTO -> CalligraphyNib.fallbackAngle(brush) != null
        Mode.MATCHED -> true
        Mode.NATIVE -> false
    })
    val description: String get() = when {
        canSwitch && tool == Tool.StrokeEraser -> "Stroke Eraser · Android input"
        matched -> "Matched preview · Android input"
        canSwitch -> "Native preview · approximate brush"
        vendorDirect -> "Viwoods direct ink"
        else -> "${native.javaClass.simpleName} preview"
    }
    fun setMode(value: Mode) { mode = value; applySuspension(); if (matched) native.detachInput() }
    override fun ownsInput() = !matched && native.ownsInput()
    val ownsHardwareErase get() = vendorDirect && ownsInput()
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
    override fun setActiveTool(tool: Tool) {
        this.tool = tool
        native.setActiveTool(tool); applySuspension()
        if (matched) native.detachInput()
    }
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
        if (!matched) {
            // Direct input must have a bitmap BEFORE the first pen-down. Otherwise
            // ownsInput() consumes Android events while ENote has no canvas to bind.
            if (vendorDirect) native.pushBackgroundBitmap(bitmap, viewLocation)
            native.reconcileRepaint(bitmap, viewLocation, dirtyRect)
        }
    }
}
