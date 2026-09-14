package com.forestnote.app.notes

import android.content.Context
import androidx.compose.foundation.background
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
) : AbstractComposeView(context) {
    var shelf by mutableStateOf(initialShelf)

    init {
        tag = "sharedLibraryChrome"
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
    }

    @Composable
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun Content() {
        Column(Modifier.fillMaxWidth().background(Color.White).semantics { testTagsAsResourceId = true }) {
            Row(Modifier.fillMaxWidth().heightIn(min = dimensionResource(R.dimen.eink_ui_header_height)),
                verticalAlignment = Alignment.CenterVertically) {
                // Overflow stays reachable at narrow widths or large accessibility font scales.
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(stringResource(R.string.shared_library_title),
                        Modifier.padding(start = 8.dp, end = 8.dp),
                        style = TextStyle(color = Color.Black, fontWeight = FontWeight.Medium,
                            fontSize = with(LocalDensity.current) {resources.getDimension(R.dimen.eink_ui_title_text).toSp()}))
                    for (item in SharedLibraryState.Shelf.entries) {
                        EinkButton(stringResource(if (item == SharedLibraryState.Shelf.NOTEBOOKS)
                            R.string.shared_library_notebooks else R.string.shared_library_books),
                            onClick = { onSelect(item) }, selected = shelf == item,
                            modifier = Modifier.testTag("shelf:$item"))
                    }
                }
                EinkButton("×", onClose, Modifier.padding(start = 2.dp).widthIn(min = 40.dp).testTag("closeSharedLibrary"),
                    description = stringResource(R.string.shared_library_close))
            }
            if (canCreate && shelf == SharedLibraryState.Shelf.NOTEBOOKS) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    EinkButton(stringResource(R.string.shared_writer_new_notebook), onNewNotebook,
                        Modifier.testTag("newSharedNotebook"))
                    EinkButton(stringResource(R.string.library_new_folder), onNewFolder,
                        Modifier.testTag("newSharedFolder"))
                }
            }
        }
    }
}
