package com.forestnote.app.notes

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forestnote.app.notes.ui.EinkButton

/** One Compose root for shelf navigation/creation. No repository access or ink ownership. */
internal class LibraryChromeView(
    context: Context,
    initialShelf: SharedLibraryState.Shelf,
    private val canCreate: Boolean,
    private val onSelect: (SharedLibraryState.Shelf) -> Unit,
    private val onClose: () -> Unit,
    private val onNewNotebook: () -> Unit,
    private val onNewFolder: () -> Unit,
    private val onDensity: () -> Unit,
    private val onSettings: () -> Unit,
) : AbstractComposeView(context) {
    var shelf by mutableStateOf(initialShelf)
    var densityMode by mutableStateOf(UiDensity.AUTO)
    var compact by mutableStateOf(false)

    init {
        tag = "sharedLibraryChrome"
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
    }

    @Composable
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun Content() {
        val inset = dimensionResource(if (compact) R.dimen.library_compact_inset else R.dimen.library_surface_inset)
        val gap = dimensionResource(if (compact) R.dimen.library_compact_gap else R.dimen.library_surface_gap)
        Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = inset)
            .semantics { testTagsAsResourceId = true }) {
            Row(Modifier.fillMaxWidth().heightIn(min = dimensionResource(R.dimen.eink_ui_header_height)),
                verticalAlignment = Alignment.CenterVertically) {
                BasicText(stringResource(R.string.shared_library_title), Modifier.weight(1f),
                    style = TextStyle(color = Color.Black, fontWeight = FontWeight.Medium,
                            fontSize = with(LocalDensity.current) {resources.getDimension(if (compact) R.dimen.eink_ui_label_text else R.dimen.eink_ui_title_text).toSp()}))
                EinkButton(stringResource(R.string.library_density_choice, stringResource(densityLabel(densityMode))),
                    onDensity, Modifier.padding(end = gap).testTag("libraryDensity"), compact = compact,
                    description = stringResource(R.string.library_density))
                EinkButton("", onSettings, Modifier.padding(end = gap).testTag("sharedSettings"),
                    icon = R.drawable.ic_settings, description = stringResource(R.string.settings_notebook_defaults), compact = compact)
                EinkButton("×", onClose, Modifier.widthIn(min = 42.dp).testTag("closeSharedLibrary"),
                    description = stringResource(R.string.shared_library_close), compact = compact)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = gap)) {
                val hasActions = canCreate && shelf == SharedLibraryState.Shelf.NOTEBOOKS
                val stacked = maxWidth < 640.dp
                val actionWidth = maxWidth * 0.6f
                @Composable fun tabs(modifier: Modifier = Modifier) {
                    Row(modifier.horizontalScroll(rememberScrollState())
                        .border(dimensionResource(R.dimen.eink_ui_border), Color.Black,
                            RoundedCornerShape(dimensionResource(R.dimen.library_surface_radius)))
                        .padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (item in SharedLibraryState.Shelf.entries) {
                            EinkButton(stringResource(if (item == SharedLibraryState.Shelf.NOTEBOOKS)
                                R.string.shared_library_notebooks else R.string.shared_library_books),
                                onClick = { onSelect(item) }, selected = shelf == item,
                                outlined = false,
                                compact = compact,
                                modifier = Modifier.testTag("shelf:$item"))
                        }
                    }
                }
                @Composable fun actions(modifier: Modifier = Modifier) {
                    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        EinkButton(stringResource(R.string.shared_writer_new_notebook), onNewNotebook,
                            Modifier.testTag("newSharedNotebook"), primary = true, icon = R.drawable.ic_add, compact = compact)
                        EinkButton(stringResource(R.string.library_new_folder), onNewFolder,
                            Modifier.testTag("newSharedFolder"), icon = R.drawable.ic_create_folder, compact = compact)
                    }
                }
                if (stacked) Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    tabs(Modifier.fillMaxWidth())
                    if (hasActions) actions(Modifier.fillMaxWidth())
                } else Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(gap)) {
                    tabs()
                    Spacer(Modifier.weight(1f))
                    if (hasActions) actions(Modifier.widthIn(max = actionWidth))
                }
            }
        }
    }

    companion object {
        fun densityLabel(mode: UiDensity) = when (mode) {
            UiDensity.AUTO -> R.string.library_density_auto
            UiDensity.COMPACT -> R.string.library_density_compact
            UiDensity.COMFORTABLE -> R.string.library_density_comfortable
        }
    }
}
