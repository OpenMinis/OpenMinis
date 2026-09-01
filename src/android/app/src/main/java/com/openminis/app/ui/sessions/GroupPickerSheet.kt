package com.openminis.app.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.ui.components.MinisSmallButton
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionTextField

/**
 * [T-android-session-grouping] What the user chose in the group picker.
 *
 * Ported from iOS `FolderPickerSheet.Choice`. Modelled as a sealed type rather
 * than a nullable folder id so "file into nothing" is a distinct, named
 * decision instead of an ambiguous null.
 */
sealed interface GroupChoice {
    data class Existing(val folderId: String) : GroupChoice

    data class Create(val name: String, val description: String?) : GroupChoice

    /** "No Group" — detach the session(s) from whatever group they are in. */
    data object RemoveFromGroup : GroupChoice
}

/**
 * The group picker. ONE sheet serves both the single-session context menu and
 * the multi-select toolbar, deliberately — iOS keeps them unified so the two
 * flows cannot drift apart, and the same applies here.
 *
 * @param sessionCount how many sessions are being filed; drives the title.
 * @param anyFiled true when at least ONE of them currently has a group. Gates
 *   the "No Group" row: offering it for an already-ungrouped session is a
 *   no-op control, which reads as broken. "Any" rather than "all" so a mixed
 *   multi-selection still gets the option.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPickerSheet(
    folders: List<FolderEntity>,
    memberCounts: Map<String, Int>,
    sessionCount: Int,
    anyFiled: Boolean,
    onChoose: (GroupChoice) -> Unit,
    onDismiss: () -> Unit,
    // [T-android-group-ai-suggest] AI Suggest wiring. Defaulted so the sheet
    // still composes without it (previews / any future caller that has no VM).
    suggesting: Boolean = false,
    suggestFailed: Boolean = false,
    suggestion: SessionListViewModel.GroupSuggestion? = null,
    onSuggest: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newName by remember { mutableStateOf("") }
    var newDesc by remember { mutableStateOf("") }

    // A create suggestion PREFILLS the fields and waits for the user's Create
    // tap — it never files anything on its own. Keyed on the suggestion so a
    // re-run overwrites, while ordinary typing in between is left alone.
    val createSuggestion = suggestion as? SessionListViewModel.GroupSuggestion.Create
    androidx.compose.runtime.LaunchedEffect(createSuggestion) {
        if (createSuggestion != null) {
            newName = createSuggestion.name
            createSuggestion.description?.let { newDesc = it.take(FolderEntity.DESC_MAX_CHARS) }
        }
    }

    val trimmedName = newName.trim()
    // Case- and whitespace-insensitive, so "Work" and "work " collide. Names
    // are not unique in the schema, but silently minting a second identical
    // group is never what the user meant — we offer the existing one instead.
    val duplicate = remember(trimmedName, folders) {
        if (trimmedName.isEmpty()) null
        else folders.firstOrNull { it.name.trim().equals(trimmedName, ignoreCase = true) }
    }

    val title = when {
        sessionCount > 1 && anyFiled -> stringResource(R.string.group_picker_title_change_n, sessionCount)
        sessionCount > 1 -> stringResource(R.string.group_picker_title_move_n, sessionCount)
        anyFiled -> stringResource(R.string.group_picker_title_change)
        else -> stringResource(R.string.group_picker_title_move)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )

            // ── Create a new group inline ──────────────────────────────────
            SectionTextField(
                value = newName,
                onValueChange = { newName = it },
                placeholder = stringResource(R.string.group_new_name_hint),
                modifier = Modifier.padding(horizontal = 20.dp),
                containerColor = SectionDesign.screenBackgroundColor(),
                // [T-android-sheet-textfield-inset] The modifier padding above
                // insets the CARD; without this the glyphs still sit flush
                // against the card's own left edge (user-reported).
                contentHorizontalPadding = 16.dp,
            )
            Spacer(Modifier.height(8.dp))
            SectionTextField(
                value = newDesc,
                // Hard-cap at the storage limit as the user types, so the field
                // can never hold text the repository would silently truncate.
                onValueChange = { newDesc = it.take(FolderEntity.DESC_MAX_CHARS) },
                placeholder = stringResource(R.string.group_desc_hint),
                modifier = Modifier.padding(horizontal = 20.dp),
                containerColor = SectionDesign.screenBackgroundColor(),
                contentHorizontalPadding = 16.dp,
            )

            if (duplicate != null) {
                // Not a hard block: the warning doubles as a shortcut into the
                // group that already exists, which is what the user almost
                // always wanted.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onChoose(GroupChoice.Existing(duplicate.id)) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text(
                            stringResource(R.string.group_duplicate_exists, duplicate.name),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.group_duplicate_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── ✨ AI Suggest merge proposal ────────────────────────────────
            // A merge is NOT prefilled into the fields (there is nothing to
            // create), so it gets its own confirm row — one tap files into the
            // proposed group, ignoring it costs nothing.
            (suggestion as? SessionListViewModel.GroupSuggestion.Merge)?.let { merge ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onChoose(GroupChoice.Existing(merge.folderId)) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        // Group name is user data — interpolated, not a key.
                        stringResource(R.string.group_suggest_merge, merge.folderName),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // Bottom row of the create area: AI Suggest leading, Create
            // trailing — same layout and same two independent tap targets as
            // iOS FolderPickerSheet.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onSuggest != null) {
                    Row(
                        modifier = Modifier
                            .clickable(enabled = !suggesting) { onSuggest() }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (suggesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(
                            // Failure degrades to the manual flow (this sheet
                            // already IS the manual flow) — the label just
                            // invites a retry rather than blocking anything.
                            stringResource(
                                if (suggestFailed) R.string.group_suggest_failed
                                else R.string.group_suggest,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                MinisSmallButton(
                    onClick = {
                        onChoose(
                            GroupChoice.Create(
                                name = trimmedName,
                                description = newDesc.trim().ifBlank { null },
                            ),
                        )
                    },
                    // Disabled on duplicate: the warning row above is the way
                    // forward, so Create never produces a confusing twin.
                    enabled = trimmedName.isNotEmpty() && duplicate == null,
                ) { Text(stringResource(R.string.group_create)) }
            }

            // ── Existing groups ────────────────────────────────────────────
            // "No Group" leads the list as its FIRST row (user request): it is
            // a peer filing choice, not a destructive action, and trailing the
            // whole group list buried it below the fold once the list grew.
            if (folders.isNotEmpty() || anyFiled) {
                Text(
                    text = stringResource(R.string.group_section_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    if (anyFiled) {
                        item(key = "no_group") {
                            val label = stringResource(R.string.group_none)
                            val hint = stringResource(R.string.group_none_subtitle)
                            GroupRow(
                                title = label,
                                subtitle = hint,
                                icon = Icons.Default.FolderOff,
                                onClick = { onChoose(GroupChoice.RemoveFromGroup) },
                                // Merge the two lines for screen readers;
                                // announced separately they read as unrelated
                                // fragments.
                                modifier = Modifier.semantics(mergeDescendants = true) {
                                    contentDescription = label
                                    stateDescription = hint
                                },
                            )
                        }
                    }
                    items(folders, key = { it.id }) { folder ->
                        val count = memberCounts[folder.id] ?: 0
                        GroupRow(
                            // Group names are user data — rendered verbatim,
                            // never through a string lookup.
                            title = folder.name,
                            subtitle = folder.description?.takeIf { it.isNotBlank() }
                                ?: if (count > 0) stringResource(R.string.group_n_chats, count)
                                else stringResource(R.string.group_empty),
                            icon = Icons.Default.Folder,
                            onClick = { onChoose(GroupChoice.Existing(folder.id)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.size(16.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
