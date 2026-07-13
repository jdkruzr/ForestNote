package com.forestnote.app.notes

import com.forestnote.core.ink.TextBox
import com.forestnote.core.ink.ZBand

/** Pure layer partition shared by the preview renderer and its behavioral tests. */
object PagePreviewRenderOrder {
    fun bottom(boxes: List<TextBox>): List<TextBox> = boxes.filter { it.zBand == ZBand.BOTTOM }
    fun top(boxes: List<TextBox>): List<TextBox> = boxes.filter { it.zBand == ZBand.TOP }
}
