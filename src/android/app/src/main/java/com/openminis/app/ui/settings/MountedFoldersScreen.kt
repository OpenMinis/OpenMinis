package com.openminis.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import com.openminis.app.ui.components.MinisTextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.data.SafMountHelper
import com.openminis.app.ui.components.DialogTextField
import kotlinx.coroutines.launch
import com.openminis.app.i18n.uppercaseForDisplay

/**
 * Settings → Mount External Folders. Mirrors iOS MountedFoldersSettingsView.
 *
 * Lists user-picked SAF tree URIs that map to a real POSIX path on the host
 * (only `com.android.externalstorage.documents` is accepted at picker time).
 * Each entry shows an R/W / Locked / Read-only badge, the linux mount path,
 * and the resolved host path. Tap → MountDetailScreen. Swipe → remove.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MountedFoldersScreen(
    store: MountedFoldersStore,
    onBack: () -> Unit,
    onMountClick: (mountId: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by store.entries.collectAsState()
    val isAtCapacity = entries.size >= MountedFoldersStore.MAX_MOUNTS

    var pendingPickedUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDefaultName by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }

    // [T-android-mount-picker-landing] Shown once before handing off to the
    // system picker, explaining that Android forbids mounting the storage root.
    // See showPicker() below for why this is needed.
    var showPickerIntro by remember { mutableStateOf(false) }

    // T219 follow-up: re-read all-files-access state on every ON_RESUME so
    // returning from the system Settings page (where the user toggles the
    // permission) clears the banner without an app restart.
    var hasAllFilesAccess by remember { mutableStateOf(checkAllFilesAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAllFilesAccess = checkAllFilesAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Android 10 (Q) base ROMs grant storage via the normal runtime flow — request
    // READ + WRITE directly rather than pushing the user to a Settings page that
    // (on HarmonyOS/EMUI) may not exist. WRITE is required for two things the read
    // grant alone doesn't cover: probeWritable's write test (drives the R/W vs
    // read-only badge) and the agent's raw proot writes into the bound folder —
    // without it those writes report success but land in a shadow FUSE view instead
    // of shared storage. Re-check on grant so the banner clears.
    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) {
            hasAllFilesAccess = checkAllFilesAccess(context)
            scope.launch { store.refreshWritability() }
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Only on-device storage maps to a POSIX path PRoot can bind. Reject
        // cloud providers (Drive, Dropbox, OneDrive) with a clear toast —
        // the spec defers Option B mirror-copy to a follow-up.
        if (uri.authority != "com.android.externalstorage.documents") {
            Toast.makeText(
                context,
                context.getString(R.string.mount_only_on_device_supported),
                Toast.LENGTH_LONG,
            ).show()
            return@rememberLauncherForActivityResult
        }
        if (!SafMountHelper.handlePickerResult(context, uri)) {
            return@rememberLauncherForActivityResult
        }
        pendingPickedUri = uri
        pendingDefaultName = defaultMountName(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_mount_external_folders)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            // On Android 10, a folder can readdir but still EACCES on
                            // write unless WRITE_EXTERNAL_STORAGE is granted at runtime
                            // (the ROM's single storage toggle often only grants READ).
                            // Request it up front so the mount probes as writable and
                            // the "Allow writes" toggle isn't stuck disabled.
                            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q &&
                                context.checkSelfPermission(
                                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                legacyStorageLauncher.launch(
                                    arrayOf(
                                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                    ),
                                )
                            } else {
                                showPickerIntro = true
                            }
                        },
                        enabled = !isAtCapacity,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            InfoBanner()

            if (!hasAllFilesAccess) {
                AllFilesAccessBanner(onClick = {
                    when {
                        // Android 11+: special All Files Access page.
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }.onFailure {
                            // Some OEMs (HarmonyOS/EMUI) don't host the per-app page —
                            // fall back to the app details screen.
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                        }
                        // Android 10: request the legacy storage runtime permissions
                        // (READ for readdir, WRITE for the badge + agent writes).
                        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
                            legacyStorageLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                ),
                            )
                    }
                })
            }

            if (entries.isEmpty()) {
                EmptyState()
            } else {
                ListHeader(count = entries.size, atCapacity = isAtCapacity)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        val dismissState = rememberSwipeToDismissBoxState()
                        LaunchedEffect(dismissState.currentValue) {
                            if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
                                scope.launch { store.remove(entry.id) }
                            }
                        }
                        SwipeToDismissBox(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            state = dismissState,
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(horizontal = 16.dp),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Text(
                                        text = stringResource(R.string.delete),
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            },
                            enableDismissFromStartToEnd = false,
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                MountRow(entry = entry, onClick = { onMountClick(entry.id) })
                            }
                        }
                    }
                    if (isAtCapacity) {
                        item {
                            Text(
                                text = stringResource(R.string.mount_folders_limit_reached),
                                color = Color(0xFFFF9500),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    val pickedUri = pendingPickedUri
    if (pickedUri != null) {
        AddMountSheet(
            sourceUri = pickedUri,
            initialName = pendingDefaultName,
            onDismiss = { pendingPickedUri = null },
            onConfirm = { name, allowWrite ->
                scope.launch {
                    val added = store.add(pickedUri, name, allowWrite)
                    if (added == null) {
                        addError = context.getString(R.string.mount_add_failed)
                    }
                    pendingPickedUri = null
                }
            },
        )
    }

    addError?.let { msg ->
        AlertDialog(
            onDismissRequest = { addError = null },
            confirmButton = {
                MinisTextButton(onClick = { addError = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            text = { Text(msg) },
        )
    }

    // [T-android-mount-picker-landing] GH#93: the system picker used to open at
    // whatever location it liked — on the reporter's Xiaomi that was the shared
    // storage ROOT, where Android shows "无法使用此文件夹 / can't use this folder",
    // greys out "Use this folder", and (being already the top level) offers no
    // way back. The user reads that as being trapped.
    //
    // Two halves to the fix; this is the half we control from our side of the
    // hand-off. The picker's own chrome belongs to the system + OEM, so we
    // cannot add a back button to it — what we CAN do is (a) explain the
    // restriction in our own words before the user leaves our UI, and
    // (b) land them somewhere selectable (see initialPickerUri()).
    if (showPickerIntro) {
        AlertDialog(
            onDismissRequest = { showPickerIntro = false },
            title = { Text(stringResource(R.string.mount_picker_intro_title)) },
            text = { Text(stringResource(R.string.mount_picker_intro_message)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    showPickerIntro = false
                    pickerLauncher.launch(initialPickerUri())
                }) {
                    Text(stringResource(R.string.mount_picker_intro_continue))
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showPickerIntro = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * [T-android-mount-picker-landing] Where the system folder picker should open.
 *
 * Returning null (the old behaviour) lets the picker choose, and on some ROMs
 * that is the shared-storage ROOT — the one place Android refuses to grant
 * (blocked since Android 11, alongside Android/data and Android/obb). The user
 * lands on a greyed-out "Use this folder" with no parent left to go back to,
 * which is exactly what GH#93 reported.
 *
 * `Documents` is chosen because it satisfies all three constraints at once:
 *  • NOT on Android's blocked list, so it is actually selectable;
 *  • always present on a real device (a non-existent initial URI is silently
 *    ignored, which would drop the user back at the broken default);
 *  • one level below the root, so the breadcrumb still lets them navigate
 *    anywhere else — this positions the user without deciding for them.
 *
 * Media dirs (DCIM/Pictures/Music/Movies) were rejected as too narrow in
 * meaning, and Download as more "transient scratch" than the long-lived folder
 * a mount implies.
 */
private fun initialPickerUri(): Uri? = runCatching {
    DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:Documents",
    )
}.getOrNull()

@Composable
private fun InfoBanner() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(12.dp)),
    ) {
        Text(
            text = stringResource(R.string.mount_folders_info_banner),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun AllFilesAccessBanner(onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.mount_all_files_access_required),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.mount_all_files_access_required_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * Whether the app currently has enough storage access for PRoot to readdir a
 * mounted external folder's real contents.
 *
 * - Android 11+ (R): needs MANAGE_EXTERNAL_STORAGE. It's a special permission
 *   granted only via a system Settings page, never the runtime flow.
 * - Android 10 (Q) base ROMs — including HarmonyOS 2.x / EMUI 11 (Mate 40
 *   ELS-AN00 etc.) that never expose the "All Files Access" page: legacy
 *   storage is what feeds proot the /mnt/runtime/write FUSE view, and that
 *   requires READ_EXTERNAL_STORAGE to actually be granted. Returning true
 *   unconditionally here (the old behaviour) hid the banner even when the
 *   user hadn't granted "允许访问文件", leaving them stuck on an empty mount.
 * - Below Q: legacy storage is always on, no opt-in needed.
 */
private fun checkAllFilesAccess(context: android.content.Context): Boolean {
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        else -> true
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.FolderShared,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.mount_folders_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.mount_folders_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ListHeader(count: Int, atCapacity: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.mount_folders_title).uppercaseForDisplay(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "$count / ${MountedFoldersStore.MAX_MOUNTS}",
            style = MaterialTheme.typography.labelSmall,
            color = if (atCapacity) Color(0xFFFF9500) else MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun MountRow(
    entry: MountedFoldersStore.Entry,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = Color(0xFF007AFF),
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                AccessBadge(entry = entry)
            }
            Text(
                text = "/var/minis/mounts/${entry.name}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Source line: prefer spec-contract `resolvedHostPath` (T219-1
            // adds it). Until that lands, fall back to `sourceDisplayName`
            // — same rendering shape, just less precise.
            val source = entry.sourceDisplayName.ifEmpty { stringResource(R.string.mount_path_unavailable) }
            Text(
                text = "← $source",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Access pill: R/W (green) when fully writable, Locked (purple) when the OS
 * permits writes but the user toggled them off, Read-only (orange) when the
 * grant itself is read-only.
 */
@Composable
private fun AccessBadge(entry: MountedFoldersStore.Entry) {
    val (text, color) = when {
        !entry.isWritable -> stringResource(R.string.mount_badge_readonly) to Color(0xFFFF9500)
        !entry.userAllowWrite -> stringResource(R.string.mount_badge_locked) to Color(0xFFAF52DE)
        else -> stringResource(R.string.mount_badge_rw) to Color(0xFF34C759)
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMountSheet(
    sourceUri: Uri,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, allowWrite: Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(initialName) }
    var allowWrite by remember { mutableStateOf(true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.mount_add_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.mount_add_source_path),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = SafMountHelper.treeDisplayPath(sourceUri),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.mount_add_name_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            DialogTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.mount_add_name_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.mount_add_allow_writes),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.mount_add_allow_writes_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                SettingsSwitch(checked = allowWrite, onCheckedChange = { allowWrite = it })
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                MinisTextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(Modifier.width(8.dp))
                MinisTextButton(
                    onClick = { onConfirm(name.trim(), allowWrite) },
                    enabled = isValidMountName(name),
                ) {
                    Text(stringResource(R.string.mount_add_confirm))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

internal fun isValidMountName(raw: String): Boolean {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return false
    if (trimmed.contains('/') || trimmed.contains(' ')) return false
    return true
}

/**
 * Default mount name from a SAF tree URI. Special-cases the
 * `Android/data/<pkg>/files` shape so the suggested name is the owning
 * package rather than the meaningless `files`. Otherwise the leaf segment
 * after the volume's `:` separator is used.
 */
internal fun defaultMountName(uri: Uri): String {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull().orEmpty()
    val rel = docId.substringAfter(':', docId)
    val segments = rel.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return "mount"

    // Android/data/<pkg>/files → <pkg>
    val androidIdx = segments.indexOf("Android")
    if (androidIdx >= 0 && androidIdx + 2 < segments.size && segments[androidIdx + 1] == "data") {
        return sanitize(segments[androidIdx + 2])
    }
    return sanitize(segments.last())
}

private fun sanitize(raw: String): String {
    val cleaned = raw.trim().replace('/', '-').replace(' ', '_')
    return cleaned.ifEmpty { "mount" }
}
