package com.openminis.app.ui.settings

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.ui.chat.ModelBrandIcon
import com.openminis.app.ui.chat.ModelBrandResolver

/**
 * The provider screen's model list, extracted from ProviderDetailScreen.
 *
 * Two user-facing behaviours live here, both from upstream 1.15-beta.5:
 *
 *  - **[T-android-hidden-models-group]** Hidden models used to stay inline,
 *    dimmed to 45% and marked " • Hidden". On a 200-model relay that buried the
 *    models the user actually uses under rows they had deliberately put away,
 *    and the dimming made an already-long list look broken. Hidden models now
 *    collapse into their own "已隐藏模型 (N)" section, closed by default — the
 *    same shape iOS uses. Hiding remains a one-long-press action and un-hiding
 *    is one tap inside the group.
 *
 *  - **[T-android-delete-api-model]** Delete is offered for EVERY row, not just
 *    custom ones. The old restriction assumed an API-listed model cannot be
 *    deleted because a refresh re-creates it; the refresh is handled in
 *    [com.openminis.app.data.repository.DeletedModelTombstones], so refusing the
 *    action was just an unexplained dead menu item.
 *
 * The row renders through [SettingsRow] with `onClick = null` and owns both
 * gestures on a wrapping [Box]: SettingsRow's own clickable would otherwise
 * consume the long-press before the menu could open.
 */

/** Input modalities that get a (muted) capability badge in the model list. */
private val modalityIconKeys = setOf("image", "pdf", "audio", "video")

/** Output modalities that get a (tinted, generate-style) badge in the model list. */
private val modalityOutputIconKeys = setOf("image", "audio", "video")

/**
 * One model row: brand logo + display name + wire id, capability badges, and a
 * long-press menu with Hide/Show and Delete.
 *
 * @param showDivider false on the last row of its section.
 * @param onDelete invoked with the entry the user chose to delete; the caller
 *   owns the confirmation dialog.
 */
@Composable
internal fun ProviderModelEntryRow(
    entry: ModelEntry,
    showDivider: Boolean,
    onOpen: () -> Unit,
    onToggleHidden: () -> Unit,
    onDelete: () -> Unit,
) {
    // Local to each row: a shared "which row's menu is open" state in the parent
    // forced every row to recompose when any menu opened.
    var menuOpen by remember { mutableStateOf(false) }
    // [T-android-model-capability-output-tags] (XIN msg 38847) The list
    // previously read INPUT modalities only, so a generator like gpt-image-2
    // (image_output) or an audio_output model showed no capability badge at all.
    // Surface both directions — input badges are muted, output badges use a
    // tinted "generate"-style glyph so they read distinctly. Mirrors iOS #669.
    val inputModalities = entry.model.inputModalities.orEmpty()
    val outputModalities = entry.model.outputModalities.orEmpty()
    val hasBadge = inputModalities.any { it in modalityIconKeys } ||
        outputModalities.any { it in modalityOutputIconKeys }
    // [T-usage-topbar-brand-icon] Vendor logo ahead of the model name, resolved
    // by keyword scoring over id + displayName (the instance type is a relay,
    // not a brand).
    val brandSlug = remember(entry.model.id, entry.model.displayName) {
        ModelBrandResolver.resolve(entry.model.id, entry.model.displayName)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { menuOpen = true },
            ),
    ) {
        SettingsRow(
            title = entry.model.displayName,
            subtitle = entry.model.id,
            leading = if (brandSlug != null) {
                {
                    ModelBrandIcon(slug = brandSlug, sizeDp = 24.dp)
                }
            } else null,
            // onClick = null so SettingsRow doesn't add a second clickable that
            // would swallow the long-press. The wrapping Box owns both gestures.
            onClick = null,
            showChevron = true,
            showDivider = showDivider,
            // [T-android-settings-ui-md3] #8 two-line row (name + id) uses the
            // MD3 double-line height (72dp).
            minHeight = 72.dp,
            // #9 The capability-icon area has a FIXED width so the trailing
            // chevron lands at the same x on every row, regardless of how many
            // badges (0–4) a model has — previously the chevron slid left/right
            // per row and the column looked ragged. Icons right-align in the slot.
            trailing = {
                Box(
                    modifier = Modifier.width(72.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    if (hasBadge) {
                        ModalityIconsRow(inputModalities, outputModalities)
                    }
                }
            },
        )

        // [T-android-model-row-hide-action] Row context menu, anchored inside the
        // row's Box so it opens over the entry the user pressed.
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (entry.isHidden) R.string.provider_detail_show_model
                            else R.string.provider_detail_hide_model,
                        ),
                    )
                },
                leadingIcon = {
                    Icon(
                        if (entry.isHidden) Icons.Filled.Visibility
                        else Icons.Filled.VisibilityOff,
                        contentDescription = null,
                    )
                },
                onClick = {
                    menuOpen = false
                    onToggleHidden()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.common_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

/**
 * [T-android-hidden-models-group] Collapsible "已隐藏模型 (N)" section.
 *
 * Renders nothing when [hidden] is empty, so callers can place it
 * unconditionally. Collapsed by default: the point of hiding a model is to stop
 * seeing it, and a group that auto-expanded would only move the clutter.
 *
 * Expansion state is deliberately NOT remembered across visits — reopening the
 * provider screen is a fresh look at the active models. It IS remembered across
 * recompositions (including while the user hides another model from inside the
 * group), which is what `remember` gives us.
 */
@Composable
internal fun ProviderHiddenModelsSection(
    hidden: List<ModelEntry>,
    onOpen: (ModelEntry) -> Unit,
    onToggleHidden: (ModelEntry) -> Unit,
    onDelete: (ModelEntry) -> Unit,
) {
    if (hidden.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    SettingsSection {
        SettingsRow(
            title = stringResource(R.string.provider_detail_hidden_models, hidden.size),
            subtitle = stringResource(R.string.provider_detail_hidden_models_hint),
            onClick = { expanded = !expanded },
            showChevron = false,
            showDivider = expanded,
            trailing = {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.provider_detail_hidden_models_collapse
                        else R.string.provider_detail_hidden_models_expand,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        if (expanded) {
            hidden.forEachIndexed { idx, entry ->
                ProviderModelEntryRow(
                    entry = entry,
                    showDivider = idx != hidden.lastIndex,
                    onOpen = { onOpen(entry) },
                    onToggleHidden = { onToggleHidden(entry) },
                    onDelete = { onDelete(entry) },
                )
            }
        }
    }
}

/**
 * [T-android-model-capability-output-tags] Capability badges for one model row.
 * Input modalities render in the muted onSurfaceVariant tint; output modalities
 * render in the primary tint with "generate"-style glyphs so a generator (e.g.
 * gpt-image-2 image_output) is visibly distinct from a model that merely accepts
 * that modality as input. Mirrors iOS ProviderInstanceDetailView.modalityIcons.
 */
@Composable
private fun ModalityIconsRow(
    inputModalities: List<String>,
    outputModalities: List<String>,
) {
    val inputTint = MaterialTheme.colorScheme.onSurfaceVariant
    val outputTint = MaterialTheme.colorScheme.primary
    val size = Modifier.size(14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Input badges (muted).
        if ("image" in inputModalities) Icon(Icons.Default.Image, contentDescription = stringResource(R.string.modeldetail_image_input), tint = inputTint, modifier = size)
        if ("pdf" in inputModalities) Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = stringResource(R.string.modeldetail_pdf_input), tint = inputTint, modifier = size)
        if ("audio" in inputModalities) Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.modeldetail_audio_input), tint = inputTint, modifier = size)
        if ("video" in inputModalities) Icon(Icons.Default.Videocam, contentDescription = stringResource(R.string.modeldetail_video_input), tint = inputTint, modifier = size)
        // Output badges (tinted, generate-style glyphs).
        if ("image" in outputModalities) Icon(Icons.Default.AddPhotoAlternate, contentDescription = stringResource(R.string.modeldetail_image_output), tint = outputTint, modifier = size)
        if ("audio" in outputModalities) Icon(Icons.Default.VolumeUp, contentDescription = stringResource(R.string.modeldetail_audio_output), tint = outputTint, modifier = size)
        if ("video" in outputModalities) Icon(Icons.Default.MovieCreation, contentDescription = stringResource(R.string.modeldetail_video_output), tint = outputTint, modifier = size)
    }
}
