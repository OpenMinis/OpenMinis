package com.openminis.app.ui.chat

// [T-android-split-chat] ModelPickerSheet (+ its private helpers fuzzyMatch /
// providerDotColor) extracted verbatim from ChatScreen.kt. Full import block
// copied from ChatScreen.kt (unused imports are warnings, not errors);
// ModelPickerSheet flipped private->internal so ChatScreen can call it.

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.automirrored.filled.Article
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openminis.app.offload.OffloadPermissionManager
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.browser.BrowserSheet
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.components.MinisTextButton

/**
 * Fuzzy match: substring first, then all query chars appear in order.
 * Matches iOS SessionModelPicker.fuzzyMatch.
 */
private fun fuzzyMatch(text: String, query: String): Boolean {
    if (query.isEmpty()) return true
    val q = query.lowercase()
    val t = text.lowercase()
    if (t.contains(q)) return true
    var idx = 0
    for (ch in q) {
        val found = t.indexOf(ch, idx)
        if (found < 0) return false
        idx = found + 1
    }
    return true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    groups: List<ModelGroup>,
    selectedGroupId: String?,
    activeEntryId: String?,
    defaultPrimaryGroupId: String?,
    config: ProviderConfig,
    providerRepository: ProviderRepository,
    onSelectGroup: (String) -> Unit,
    onSelectGroupEntry: (String, String) -> Unit,
    onSelectEntry: (String) -> Unit,
    onDismiss: () -> Unit,
    /** [T-android-modelpicker-group-edit] "Edit" affordance on the Model Groups
     *  section header — dismisses the sheet and navigates to the Model Groups
     *  management screen. Null hides the button (callers without a route). */
    onEditGroups: (() -> Unit)? = null,
) {
    val openTime = remember { System.nanoTime() }

    LaunchedEffect(Unit) {
        AppLogger.info("ModelPicker", "[ModelPicker] open triggered")
        withFrameNanos { frameTime ->
            val ms = (frameTime - openTime) / 1_000_000.0
            AppLogger.info("ModelPicker", "[ModelPicker] first frame rendered: ${"%.1f".format(ms)}ms (total since trigger)")
        }
    }

    var searchText by remember { mutableStateOf("") }
    var expandedGroupIds by remember { mutableStateOf(setOf<String>()) }
    // Note: the non-text-output "may not work as an Agent" confirmation lives
    // in ChatScreen's callback wrappers (ee828dba), NOT here — the sheet stays
    // a dumb list and the caller owns selection policy.
    val allInstanceIds = remember(config) {
        config.instances.filter { it.isEnabled }.map { it.id }.toSet()
    }
    var collapsedInstanceIds by remember(allInstanceIds) {
        mutableStateOf(allInstanceIds)
    }

    /**
     * [T-android-model-picker-polish] Model whose Quick Test sheet is open.
     *
     * Reuses the same [com.openminis.app.ui.components.QuickTestSheet] the
     * provider settings screens already use, as iOS reuses ModelQuickTestSheet
     * from its picker — a model that behaves one way when tested from Settings
     * and another from the picker would be worse than no button at all.
     */
    var quickTestEntry by remember { mutableStateOf<ModelEntry?>(null) }

    // Filtered groups
    val filteredGroups = remember(groups, searchText) {
        if (searchText.isEmpty()) groups
        else {
            val t0 = System.nanoTime()
            val result = groups.filter { fuzzyMatch(it.name, searchText) }
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            AppLogger.info("ModelPicker", "[ModelPicker] filter groups: ${result.size}/${groups.size}, ${"%.1f".format(ms)}ms")
            result
        }
    }

    // Filtered entries by instance
    val allInstancesWithEntries = remember(config, searchText) {
        val t0 = System.nanoTime()
        var totalCount = 0
        val result = config.instances
            .filter { it.isEnabled }
            .map { instance ->
                val pt = System.nanoTime()
                val entries = config.modelEntries.filter {
                    it.providerInstanceId == instance.id && !it.isHidden
                }
                val filtered = if (searchText.isEmpty()) entries
                else entries.filter {
                    fuzzyMatch(it.model.displayName, searchText) || fuzzyMatch(it.model.id, searchText)
                }
                val pms = (System.nanoTime() - pt) / 1_000_000.0
                if (filtered.isNotEmpty()) {
                    totalCount += filtered.size
                    AppLogger.info("ModelPicker", "[ModelPicker] provider \"${instance.label.ifEmpty { instance.providerType.displayName }}\" models loaded: ${filtered.size} items, ${"%.1f".format(pms)}ms")
                }
                instance to filtered
            }
            .filter { it.second.isNotEmpty() }
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        AppLogger.info("ModelPicker", "[ModelPicker] all providers loaded: total $totalCount items, ${"%.1f".format(ms)}ms")
        result
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Match the slim drag handle used by StandardChatSheet (6dp top / 4dp
        // bottom) so the title sits flush with the indicator instead of the
        // Material default's ~44dp whitespace gap above it.
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 4.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .background(
                            color = ChatColors.secondaryText.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(2.dp),
                        ),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                // [T-android-model-picker-polish] navigationBarsPadding ALONE.
                //
                // This also carried a fixed `padding(bottom = 32.dp)`, so the
                // gesture bar was accounted for twice: 32dp on top of the
                // system inset the modifier already reserves (~24-48dp
                // depending on device). The result was a dead band under the
                // sheet far deeper than the chat composer's, which uses the
                // inset on its own — the mismatch that reads as "the picker
                // floats too high off the home indicator".
                .navigationBarsPadding(),
        ) {
            // ── Title bar (iOS: "Choose Model" + Done) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.model_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    MinisTextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.model_picker_done))
                    }
                }
            }

            // ── Search bar (iOS: capsule rounded) ──
            //
            // [T-android-search-height] BasicTextField + DecorationBox rather
            // than a plain OutlinedTextField, so contentPadding is ours to set.
            //
            // The plain component cannot be made 42dp tall: its intrinsic
            // height is 56dp, so `heightIn(min=42)` never binds, and forcing
            // `height(42)` squeezes the frame while its own 16dp vertical
            // contentPadding stays put — which clipped the placeholder to its
            // top half on device. Owning contentPadding is the only way to
            // shrink the field without cutting the text; same pattern
            // SectionTextField already uses for this reason.
            val searchInteraction = remember { MutableInteractionSource() }
            BasicTextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .height(42.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                interactionSource = searchInteraction,
                decorationBox = { innerTextField ->
                    OutlinedTextFieldDefaults.DecorationBox(
                        value = searchText,
                        visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                        innerTextField = innerTextField,
                        placeholder = { Text(stringResource(R.string.model_picker_search_placeholder)) },
                        label = null,
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingIcon = {
                            if (searchText.isNotEmpty()) {
                                IconButton(onClick = { searchText = "" }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.model_picker_search_clear),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        enabled = true,
                        isError = false,
                        interactionSource = searchInteraction,
                        colors = OutlinedTextFieldDefaults.colors(),
                        // Zero vertical: the 42dp frame plus the icons already
                        // give the text room; any inset here re-clips it.
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        container = {
                            OutlinedTextFieldDefaults.Container(
                                enabled = true,
                                isError = false,
                                interactionSource = searchInteraction,
                                colors = OutlinedTextFieldDefaults.colors(),
                                shape = RoundedCornerShape(50),
                            )
                        },
                    )
                },
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                // ── Model Groups (grouped section card with embedded header) ──
                if (filteredGroups.isNotEmpty()) {
                    // Section card: header + rows live in the same surface so
                    // the visual unit is unambiguous. Header uses the
                    // titleSmall + onSurfaceVariant pair so it reads as a
                    // section label rather than another row.
                    item {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                    RoundedCornerShape(14.dp),
                                ),
                        ) {
                            // [T-android-modelpicker-group-edit] Section header
                            // row: label on the left, an "Edit" button on the
                            // right that jumps to the Model Groups management
                            // screen (mirrors the iOS picker's group-section edit
                            // affordance). Button hidden when no route is wired.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.model_picker_groups_section),
                                    // [T-android-model-picker-polish] Section
                                    // headers outrank the rows beneath them.
                                    // titleSmall is 14sp Medium while the group
                                    // and model names are bodyMedium SemiBold —
                                    // same size, HEAVIER weight — so the header
                                    // read as the weaker of the two and the
                                    // hierarchy inverted. titleMedium (16sp) +
                                    // SemiBold puts it a step above.
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
                                )
                                if (onEditGroups != null) {
                                    // Text button (not a pencil icon) matching the
                                    // sheet's other text actions like "Done".
                                    MinisTextButton(
                                        onClick = onEditGroups,
                                        modifier = Modifier.padding(end = 8.dp),
                                    ) {
                                        Text(stringResource(R.string.model_picker_groups_edit))
                                    }
                                }
                            }
                            filteredGroups.forEachIndexed { index, group ->
                                val isSelected = group.id == selectedGroupId
                                val isDefault = group.id == defaultPrimaryGroupId
                                val isExpanded = expandedGroupIds.contains(group.id)
                                val strategyLabel = when (group.strategy) {
                                    RoutingStrategy.fallback -> "FB"
                                    RoutingStrategy.loadBalance -> "LB"
                                }
                                // Resolve entry: try memberEntryIds first, fallback to activeEntryId ONLY if this group is selected
                                // SystemVoiceEntries fallback: voice groups are
                                // seeded with "__builtin_system_speech__/…"
                                // members that are synthesized on demand and
                                // never stored in config.modelEntries, so
                                // matching modelEntries alone counts them as 0.
                                val resolvedEntry = group.memberEntryIds.firstNotNullOfOrNull { entryId ->
                                    config.modelEntries.find { it.id == entryId }
                                        ?: com.openminis.app.data.model.SystemVoiceEntries.resolve(entryId)
                                } ?: if (isSelected && activeEntryId != null) config.modelEntries.find { it.id == activeEntryId } else null
                                // Count of resolved members for display
                                val resolvedCount = group.memberEntryIds.count { entryId ->
                                    config.modelEntries.any { it.id == entryId } ||
                                        com.openminis.app.data.model.SystemVoiceEntries.resolve(entryId) != null
                                }

                                // Header sits above the first row, so the
                                // first-row corners are no longer rounded
                                // (only the bottom row of the last group is).
                                val groupShape = when {
                                    index == filteredGroups.size - 1 -> RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
                                    else -> RoundedCornerShape(0.dp)
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(groupShape)
                                        .clickable { onSelectGroup(group.id) }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (isSelected) Color(0xFF34C759)
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    // [T-android-model-picker-polish] Group
                                    // glyph, matching iOS's blue "layers" mark
                                    // (SessionModelPicker). Without it a group
                                    // row and a provider row differ only by
                                    // their subtitle, which is easy to miss
                                    // when scrolling — and the two mean very
                                    // different things (a group can fail over
                                    // or load-balance; a model cannot).
                                    Icon(
                                        Icons.Default.Layers,
                                        contentDescription = null,
                                        tint = Color(0xFF007AFF),
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                group.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            // iOS: ⊕ FB / ⊕ LB badge with icon
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .background(
                                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                                        RoundedCornerShape(8.dp),
                                                    )
                                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                                            ) {
                                                Icon(
                                                    if (group.strategy == RoutingStrategy.fallback)
                                                        Icons.Default.ArrowCircleDown
                                                    else Icons.Default.AccountTree,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(9.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                )
                                                Spacer(Modifier.width(2.dp))
                                                Text(
                                                    strategyLabel,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                )
                                            }
                                        }
                                        // iOS: "→ ModelName" resolved entry
                                        if (resolvedEntry != null) {
                                            Text(
                                                "→ ${resolvedEntry.model.displayName}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            )
                                        } else if (resolvedCount > 0) {
                                            Text(
                                                pluralStringResource(R.plurals.model_picker_models_count, resolvedCount, resolvedCount),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            )
                                        } else {
                                            Text(
                                                stringResource(R.string.model_picker_models_count_unlinked, group.memberEntryIds.size),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            )
                                        }
                                    }

                                    // [T-android-model-picker-polish] "Default"
                                    // badge, matched to iOS
                                    // (UnifiedModelPicker.swift:669-674):
                                    // caption2 medium on a 10%-blue capsule with
                                    // 5/1 padding. Android was running 6/2 —
                                    // double the vertical inset — which made the
                                    // capsule noticeably taller than the group
                                    // name beside it and pulled the eye away from
                                    // the group's own label. Font drops 10sp -> 9sp
                                    // to match caption2's optical weight at this
                                    // density, and lineHeight is pinned so Compose
                                    // does not re-add the font's ascent/descent
                                    // slack on top of the 1.dp padding.
                                    if (isDefault) {
                                        Text(
                                            stringResource(R.string.model_picker_default_badge),
                                            fontSize = 9.sp,
                                            lineHeight = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF007AFF),
                                            modifier = Modifier
                                                .background(
                                                    Color(0xFF007AFF).copy(alpha = 0.1f),
                                                    RoundedCornerShape(50),
                                                )
                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }

                                    // [T-android-model-picker-polish]
                                    // Expand/collapse chevron, matched to iOS
                                    // (UnifiedModelPicker.swift:688-691): a
                                    // NEUTRAL tertiarySystemFill circle with a
                                    // .secondary glyph.
                                    //
                                    // T295 had pushed this to secondaryContainer
                                    // to escape surfaceContainerHigh, which on
                                    // the light theme is #F7F7FA and vanished
                                    // against the white card. That fixed the
                                    // visibility but overshot: a tinted
                                    // container reads as an accented ACTION,
                                    // competing with the group name beside it,
                                    // where this is only a disclosure control.
                                    // onSurface at low alpha separates from the
                                    // card without claiming that emphasis.
                                    //
                                    // 28dp -> 24dp: at 28 the circle stood
                                    // taller than the row's own text and drew
                                    // the eye first.
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .background(
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                                CircleShape,
                                            )
                                            .clip(CircleShape)
                                            .clickable {
                                                expandedGroupIds = if (isExpanded) {
                                                    expandedGroupIds - group.id
                                                } else {
                                                    expandedGroupIds + group.id
                                                }
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                // Expanded group member entries
                                if (isExpanded) {
                                    // Resolve actual entries from memberEntryIds
                                    val resolvedMembers = group.memberEntryIds.mapNotNull { entryId ->
                                        config.modelEntries.find { it.id == entryId }
                                            ?: com.openminis.app.data.model.SystemVoiceEntries.resolve(entryId)
                                    }
                                    // Fallback: show active entry ONLY if this group is selected
                                    val displayMembers = resolvedMembers.ifEmpty {
                                        if (isSelected && activeEntryId != null)
                                            listOfNotNull(config.modelEntries.find { it.id == activeEntryId })
                                        else emptyList()
                                    }
                                    if (displayMembers.isEmpty()) {
                                        Text(
                                            stringResource(R.string.model_picker_no_linked_models),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.padding(start = 48.dp, top = 4.dp, bottom = 8.dp),
                                        )
                                    }
                                    displayMembers.forEachIndexed { memberIndex, entry ->
                                            // [T-android-model-picker-polish]
                                            // Hairline between members. The group
                                            // CARDS had one and the provider rows
                                            // had one, but the members inside an
                                            // expanded group did not — so a group
                                            // with several models read as one
                                            // undifferentiated block, which is
                                            // exactly where separation matters
                                            // most (these rows are indented and
                                            // visually similar). Drawn BEFORE each
                                            // row except the first, so it never
                                            // collides with the divider that
                                            // follows the group card itself.
                                            if (memberIndex > 0) {
                                                HorizontalDivider(
                                                    modifier = Modifier.padding(start = 72.dp, end = 16.dp),
                                                    thickness = 0.5.dp,
                                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                                )
                                            }
                                            val isActive = activeEntryId == entry.id
                                            val instance = config.instances.find { it.id == entry.providerInstanceId }
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { onSelectGroupEntry(group.id, entry.id) }
                                                    .padding(start = 48.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    if (isActive) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                    contentDescription = null,
                                                    tint = if (isActive) Color(0xFF007AFF)
                                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                                                    modifier = Modifier.size(17.dp),
                                                )
                                                Spacer(Modifier.width(10.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .background(providerDotColor(instance?.providerType), CircleShape),
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(entry.model.displayName, style = MaterialTheme.typography.bodyMedium)
                                                    Row {
                                                        if (instance != null) {
                                                            Text(
                                                                instance.label.ifEmpty { instance.providerType.displayName },
                                                                style = MaterialTheme.typography.labelSmall,
                                                                fontWeight = FontWeight.Medium,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                            )
                                                            Text(
                                                                " · ",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                            )
                                                        }
                                                        Text(
                                                            entry.model.id,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                        )
                                                    }
                                                }
                                                if (isActive) {
                                                    Text(
                                                        stringResource(R.string.model_picker_active_badge),
                                                        fontSize = 9.sp,
                                                        lineHeight = 11.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = Color(0xFF34C759),
                                                        modifier = Modifier
                                                            .background(
                                                                Color(0xFF34C759).copy(alpha = 0.1f),
                                                                RoundedCornerShape(50),
                                                            )
                                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                                    )
                                                }
                                                QuickTestButton(onClick = { quickTestEntry = entry })
                                            }
                                    }
                                }

                                // Inset hairline between groups, matches MinisMenuDivider rhythm.
                                if (index < filteredGroups.size - 1) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 48.dp, end = 16.dp),
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    )
                                }
                            }
                        }
                    }

                    if (searchText.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.model_picker_groups_footer),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                // T236: hint sits between Model Groups card and
                                // first Provider card — top 8 hugs the group
                                // card, bottom 12 separates from the next card.
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
                            )
                        }
                    }
                }

                // ── Individual Models by Provider (one section card per provider) ──
                // Each provider becomes a single grouped card containing: an
                // embedded header row with the collapse chevron, then either
                // the collapsed summary row or the expanded entry list. Cards
                // are visually separated from each other by a 12dp gap, and
                // sit on a higher tonal surface so the boundary between
                // providers is unmistakable even on the dark sheet background.
                if (allInstancesWithEntries.isNotEmpty()) {
                    allInstancesWithEntries.forEach { (instance, entries) ->
                        val isCollapsed = collapsedInstanceIds.contains(instance.id)
                        item(key = "section_${instance.id}") {
                            Column(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                        RoundedCornerShape(14.dp),
                                    ),
                            ) {
                                // Header row, embedded in the card.
                                Row(
                                    // T236: tightened provider header padding
                                    // (top=10/bottom=8) so the gap to the first
                                    // model row reads as ~8dp rather than the
                                    // earlier 12dp+row-padding stack.
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        instance.label.ifEmpty { instance.providerType.displayName },
                                        // Same rank as the "Model Groups"
                                        // header above — see that comment.
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    // [T-android-model-picker-polish] Same
                                    // neutral treatment as the group chevron
                                    // above — see that comment for why the
                                    // tinted container was dropped.
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .background(
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                                CircleShape,
                                            )
                                            .clip(CircleShape)
                                            .clickable {
                                                collapsedInstanceIds = if (isCollapsed) {
                                                    collapsedInstanceIds - instance.id
                                                } else {
                                                    collapsedInstanceIds + instance.id
                                                }
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            if (isCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                // [T-android-model-picker-polish] Hairline under
                                // the provider name. The rows below it are
                                // models, not more provider chrome, and without
                                // a rule the header read as the first list item
                                // — the same separation the group card gets
                                // between its own header and its members.
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                )

                                if (isCollapsed) {
                                    // Collapsed summary — show selected or first entry + model count.
                                    val selectedEntry = entries.firstOrNull { it.id == activeEntryId && selectedGroupId == null }
                                    val displayEntry = selectedEntry ?: entries.firstOrNull()
                                    if (displayEntry != null) {
                                        val dotColor = providerDotColor(instance.providerType)
                                        Row(
                                            // T236: collapsed summary row —
                                            // vertical 14→8 + heightIn(min=48dp)
                                            // so tap target stays comfortable.
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                                                .clickable { onSelectEntry(displayEntry.id) }
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                if (selectedEntry != null) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (selectedEntry != null) Color(0xFF007AFF)
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                                modifier = Modifier.size(20.dp),
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .background(dotColor, CircleShape),
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            // [T-android-provider-voice] Modality chips
                                            // (iOS entryRow badges) — without them a
                                            // voice seed serving as the collapsed
                                            // representative is indistinguishable from
                                            // a chat model. FlowRow so overflow wraps
                                            // whole chips instead of shattering them.
                                            FlowRow(
                                                modifier = Modifier.weight(1f),
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                itemVerticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    displayEntry.model.displayName,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                                com.openminis.app.ui.components.modalityBadges(displayEntry.model).forEach { badge ->
                                                    com.openminis.app.ui.components.ModalityBadge(badge)
                                                }
                                            }
                                            // [T-android-model-picker-polish]
                                            // Quick Test, not a model count. The
                                            // count is already stated by the
                                            // "Show N models" row directly below,
                                            // so repeating it here spent the row's
                                            // trailing slot on a duplicate —
                                            // where every OTHER model row in this
                                            // sheet carries a bolt. iOS puts the
                                            // bolt here for the same reason.
                                            QuickTestButton(onClick = { quickTestEntry = displayEntry })
                                        }
                                        // Only when there is something to show:
                                        // a single-model provider's collapsed
                                        // preview IS its entire list, so
                                        // "Show 1 model" would expand to the
                                        // exact row already on screen.
                                        if (entries.size > 1) {
                                        // Hairline before the expand row: it is a
                                        // control, not another model, and butting
                                        // it against the summary row above made
                                        // the two read as one two-line entry.
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                                            thickness = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                        )
                                        // [T-android-model-picker-polish] Explicit
                                        // "Show N models" affordance, as iOS has.
                                        // The chevron in the header already
                                        // expands, but it is a small target in the
                                        // corner and reads as decoration — the
                                        // collapsed row gave no hint that the
                                        // other N-1 models were one tap away.
                                        // Tapping the summary row itself SELECTS
                                        // that model, so expanding needed its own
                                        // control rather than sharing that one.
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 40.dp)
                                                .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                                                .clickable { collapsedInstanceIds = collapsedInstanceIds - instance.id }
                                                .padding(horizontal = 16.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            // Chevron LEADS the label and the row
                                            // starts at the card's own inset: the
                                            // arrow is what signals "this expands",
                                            // so it has to be the first thing read,
                                            // and the earlier 30.dp indent left it
                                            // floating under the model names above
                                            // rather than aligned with the card.
                                            Icon(
                                                Icons.Default.KeyboardArrowDown,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = Color(0xFF007AFF),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                pluralStringResource(
                                                    R.plurals.model_picker_show_models,
                                                    entries.size,
                                                    entries.size,
                                                ),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = Color(0xFF007AFF),
                                            )
                                        }
                                        }
                                    }
                                } else {
                                    entries.forEachIndexed { index, entry ->
                                        val isSelected = activeEntryId == entry.id && selectedGroupId == null
                                        val dotColor = providerDotColor(instance.providerType)
                                        // Last row clips its own bottom so the
                                        // ripple respects the card corners.
                                        val rowShape = if (index == entries.size - 1) {
                                            RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
                                        } else {
                                            RoundedCornerShape(0.dp)
                                        }
                                        Row(
                                            // T236: expanded entry row —
                                            // vertical 14→8 + heightIn(min=48dp)
                                            // for accessible tap target.
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .clip(rowShape)
                                                .clickable { onSelectEntry(entry.id) }
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (isSelected) Color(0xFF007AFF)
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                                modifier = Modifier.size(20.dp),
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .background(dotColor, CircleShape),
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    entry.model.displayName,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                                // [T-android-provider-voice] Modality
                                                // chips (iOS entryRow badges). FlowRow so
                                                // an overflow wraps whole chips to the
                                                // next line instead of squeezing each
                                                // Text into a vertical letter column.
                                                FlowRow(
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                    itemVerticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        entry.model.id,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                    )
                                                    com.openminis.app.ui.components.modalityBadges(entry.model).forEach { badge ->
                                                        com.openminis.app.ui.components.ModalityBadge(badge)
                                                    }
                                                }
                                            }
                                            if (selectedGroupId != null && activeEntryId == entry.id) {
                                                Text(
                                                    stringResource(R.string.model_picker_active_badge),
                                                    fontSize = 9.sp,
                                                    lineHeight = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = Color(0xFF34C759),
                                                    modifier = Modifier
                                                        .background(
                                                            Color(0xFF34C759).copy(alpha = 0.1f),
                                                            RoundedCornerShape(50),
                                                        )
                                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                                )
                                            }
                                            QuickTestButton(onClick = { quickTestEntry = entry })
                                        }
                                        // Inset hairline between entries.
                                        if (index < entries.size - 1) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                                                thickness = 0.5.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Empty / No Results ──
                if (filteredGroups.isEmpty() && allInstancesWithEntries.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                if (searchText.isNotEmpty()) Icons.Default.Search else Icons.Default.Memory,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(if (searchText.isNotEmpty()) R.string.model_picker_no_results else R.string.model_picker_no_models),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (searchText.isEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.model_picker_configure_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    textAlign = TextAlign.Center,
                                )
                            } else {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.model_picker_try_different_search),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // [T-android-model-picker-polish] Quick Test host. Keyed on the entry id so
    // switching models rebuilds the sheet's state rather than reusing a run
    // from the previously tested model — the same identity trap iOS documents
    // at UnifiedModelPicker.swift:524.
    quickTestEntry?.let { entry ->
        key(entry.id) {
            com.openminis.app.ui.components.QuickTestSheet(
                entry = entry,
                providerRepository = providerRepository,
                onDismiss = { quickTestEntry = null },
            )
        }
    }
}

/**
 * [T-android-model-picker-polish] Per-row Quick Test affordance, mirroring
 * iOS's `bolt.badge.checkmark` button (UnifiedModelPicker.swift:542).
 *
 * Sized to a 32dp target and given its own click surface so it never competes
 * with the row's select gesture: tapping the row picks the model, tapping the
 * bolt tests it without changing the selection.
 */
@Composable
private fun QuickTestButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Bolt,
            contentDescription = stringResource(R.string.model_picker_quick_test),
            tint = Color(0xFF007AFF),
            modifier = Modifier.size(17.dp),
        )
    }
}

// iOS: provider color dot helper
private fun providerDotColor(providerType: ProviderType?): Color = when (providerType) {
    ProviderType.anthropic -> Color(0xFFAB47BC) // purple
    ProviderType.gemini -> Color(0xFF42A5F5)    // blue
    ProviderType.openAI -> Color(0xFF4CAF50)    // green
    ProviderType.openRouter -> Color(0xFF00BCD4) // cyan
    ProviderType.xAI -> Color(0xFFFF7043)        // orange — Grok brand
    ProviderType.kimiCode -> Color(0xFF5C6BC0)   // indigo — Kimi accent
    // [T-android-provider-type-parity] Responses API instances are
    // OpenAI under the hood — same green dot. Undrivable types share
    // the neutral gray used for "no provider".
    ProviderType.openAIResponses -> Color(0xFF4CAF50)
    ProviderType.antigravity,
    ProviderType.unsupported -> Color(0xFF8E8E93)
    null -> Color(0xFF8E8E93)                    // gray
}
