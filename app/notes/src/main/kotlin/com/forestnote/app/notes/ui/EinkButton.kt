/*
 * Copyright (c) 2026 Composable Horizons
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.forestnote.app.notes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.forestnote.app.notes.R
import com.forestnote.app.notes.ui.upstream.UnstyledButton

/** Adapted Composables UI outlined/primary button. See upstream/README.md for provenance. */
@Composable
internal fun EinkButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean? = null,
    description: String? = null,
    primary: Boolean = false,
    outlined: Boolean = true,
    icon: Int? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(dimensionResource(if (selected == null)
        R.dimen.library_surface_radius else R.dimen.eink_ui_corner_radius))
    val inverse = enabled && (primary || selected == true || pressed || focused)
    val backgroundColor = if (inverse) Color.Black else Color.White
    val contentColor = if (inverse) Color.White else Color.Black
    val borderWidth = dimensionResource(R.dimen.eink_ui_border)
    val semanticRole = if (selected == null) Role.Button else Role.Tab
    val fontSize = with(LocalDensity.current) {
        LocalContext.current.resources.getDimension(R.dimen.eink_ui_label_text).toSp()
    }
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        role = semanticRole,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
        modifier = modifier
            .heightIn(min = dimensionResource(R.dimen.eink_ui_control_height))
            .clip(shape)
            .background(backgroundColor, shape)
            .then(if (outlined) Modifier.border(borderWidth, Color.Black, shape) else Modifier)
            .semantics(mergeDescendants = true) {
                role = semanticRole
                if (selected != null) this.selected = selected
                if (description != null) contentDescription = description
            },
        interactionSource = interactionSource,
        indication = null,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Image(painterResource(icon), null,
                Modifier.size(dimensionResource(R.dimen.eink_ui_small_icon)), colorFilter = ColorFilter.tint(contentColor))
            BasicText(label, style = TextStyle(color = contentColor, fontSize = fontSize,
                fontWeight = FontWeight.Medium, textAlign = TextAlign.Center))
        }
    }
}
