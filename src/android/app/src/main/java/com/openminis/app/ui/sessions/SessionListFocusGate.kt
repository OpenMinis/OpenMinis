package com.openminis.app.ui.sessions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * [T-android-entry-ime-guard] Focus is released HERE, while the list is still on
 * screen, before the pane navigator swaps in the chat. The search field outlives
 * the tap that picks a session (its ViewModel state is kept on purpose), so
 * hiding the IME only from ChatScreen raced a text field that still held focus
 * and the platform re-opened the keyboard.
 *
 * Also carries the blank-query search collapse: when the user opens search but
 * types nothing (or only whitespace) and then navigates into a chat, the
 * VM-backed search state survives, so on return the search bar is still open and
 * focused. Collapsing flips isSearchActive false, which removes the search
 * TextField from composition and releases its focus; a real query is kept so
 * returning lands back on the same search.
 */
internal class SessionListFocusGate(
    private val collapseBlankSearch: () -> Unit,
    private val releaseFocus: () -> Unit,
) {
    fun prepareNavigation() {
        collapseBlankSearch()
        releaseFocus()
    }
}

@Composable
internal fun rememberSessionListFocusGate(viewModel: SessionListViewModel): SessionListFocusGate {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    return remember(viewModel, focusManager, keyboardController) {
        SessionListFocusGate(
            collapseBlankSearch = {
                if (viewModel.isSearchActive.value && viewModel.searchQuery.value.isBlank()) {
                    viewModel.searchQuery.value = ""
                    viewModel.isSearchActive.value = false
                }
            },
            releaseFocus = {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
            },
        )
    }
}
