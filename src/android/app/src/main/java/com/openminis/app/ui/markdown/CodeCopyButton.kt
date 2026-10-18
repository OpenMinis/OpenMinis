package com.openminis.app.ui.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import kotlinx.coroutines.delay

/**
 * Copy button for a markdown code block.
 *
 * [T-android-copy-label-l10n] Extracted from `MarkdownText.CodeBlockView` for
 * two reasons:
 *
 *  1. The label was the hardcoded English `"Copy code"`, so TalkBack read
 *     English in every locale. It now resolves `R.string.common_copy`, the
 *     same translated key the rest of the app uses for copy affordances.
 *  2. Copying had no feedback at all. The icon swaps to a check for
 *     [COPIED_FEEDBACK_MS] so the tap is visibly acknowledged (iOS parity).
 *
 * No new string keys: `common_copy` already ships in every `values-*` bundle,
 * so this needed no translation sweep.
 */
private const val COPIED_FEEDBACK_MS = 1_200L

@Composable
internal fun CodeCopyButton(code: String) {
    val context = LocalContext.current
    // Keyed on the code text: reusing the composable across blocks must not
    // inherit a stale "just copied" state from the previous block.
    var copied by remember(code) { mutableStateOf(false) }

    if (copied) {
        LaunchedEffect(code) {
            delay(COPIED_FEEDBACK_MS)
            copied = false
        }
    }

    IconButton(
        onClick = {
            copyCodeToClipboard(context, code)
            copied = true
        },
        modifier = Modifier.height(28.dp),
    ) {
        Icon(
            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = stringResource(R.string.common_copy),
            modifier = Modifier.height(16.dp),
            tint = if (copied) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun copyCodeToClipboard(context: Context, code: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("code", code))
}
