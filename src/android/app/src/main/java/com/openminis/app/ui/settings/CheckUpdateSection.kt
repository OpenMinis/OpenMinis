package com.openminis.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.ClickableText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.UpdateChecker
import kotlinx.coroutines.launch
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.i18n.uppercaseForDisplay

/**
 * Settings section that talks to [UpdateChecker] to surface a "Check for
 * Updates" affordance. Drop in anywhere — typically the bottom of an About
 * screen — and it owns its own state, dialogs, and download UI.
 *
 * The section is no-op visible: a button + transient status text. When an
 * update is found we open a modal AlertDialog showing the changelog and a
 * Download button; the dialog stays open through the download so the user
 * can watch progress.
 */
@Composable
fun CheckUpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    // When the GitHub API returns 403 / 451 we surface a dedicated row with a
    // tappable "Open GitHub Releases" link beneath the row, so users behind a
    // geo-block know what to do without hunting for the URL themselves.
    var showReleasesLink by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateChecker.CheckResult.UpdateAvailable?>(null) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var awaitingInstallPerm by remember { mutableStateOf(false) }

    // Resume the install flow on every ON_RESUME. There are two cases:
    //
    //  1. Composable state survived — `update` and `awaitingInstallPerm` are
    //     still set. We just need to flip awaitingInstallPerm off (so the
    //     dialog stops showing the "permission required" message) and, if a
    //     persisted APK is intact, fire the installer directly.
    //
    //  2. Activity recreate happened — every `remember{}` slot above is back
    //     to its default. The only thing that knows we were mid-flow is
    //     PendingUpdateStore. We rehydrate by calling resumablePendingFile()
    //     and, when permission is granted, fire the installer. We do NOT
    //     re-open the update dialog in this case because there's no
    //     CheckResult to populate it; the install intent is enough.
    //
    // Either way: if permission is still denied we leave the pending record
    // alone so the next resume can pick it up.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (!UpdateChecker.canInstall(context)) return@LifecycleEventObserver
            awaitingInstallPerm = false
            val pendingFile = UpdateChecker.resumablePendingFile(context) ?: return@LifecycleEventObserver
            val launched = UpdateChecker.installApk(context, pendingFile)
            if (launched) {
                // Dismiss any leftover dialog state; the system installer is
                // now in charge.
                update = null
                downloadProgress = null
                downloadError = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    SettingsSection(
        header = stringResource(R.string.check_update_section_header),
        footer = stringResource(R.string.check_update_current_version, BuildConfig.VERSION_NAME),
    ) {
        SettingsRow(
            icon = Icons.Outlined.SystemUpdate,
            iconColor = Color(0xFF007AFF),
            title = stringResource(
                if (checking) R.string.check_update_checking
                else R.string.check_update_check_button
            ),
            subtitle = statusMessage,
            showDivider = false,
            onClick = if (checking) null else {
                {
                    checking = true
                    statusMessage = null
                    showReleasesLink = false
                    scope.launch {
                        when (val r = UpdateChecker.check()) {
                            is UpdateChecker.CheckResult.UpdateAvailable -> {
                                update = r
                                statusMessage = null
                            }
                            UpdateChecker.CheckResult.UpToDate ->
                                statusMessage = context.getString(R.string.check_update_up_to_date)
                            UpdateChecker.CheckResult.NoReleaseAvailable ->
                                statusMessage = context.getString(R.string.check_update_no_release)
                            is UpdateChecker.CheckResult.NoApkAsset ->
                                statusMessage = context.getString(R.string.check_update_no_apk_asset, r.tagName)
                            UpdateChecker.CheckResult.Forbidden -> {
                                statusMessage = context.getString(R.string.update_error_forbidden_with_link)
                                showReleasesLink = true
                            }
                            UpdateChecker.CheckResult.NetworkUnreachable ->
                                statusMessage = context.getString(R.string.update_error_network_unreachable)
                            is UpdateChecker.CheckResult.Error ->
                                statusMessage = context.getString(R.string.check_update_error, r.message)
                        }
                        checking = false
                    }
                }
            },
        )
        if (showReleasesLink) {
            val linkLabel = stringResource(R.string.update_error_open_releases)
            val annotated = buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    )
                ) {
                    append(linkLabel)
                }
                addStringAnnotation(
                    tag = "URL",
                    annotation = UpdateChecker.RELEASES_URL,
                    start = 0,
                    end = length,
                )
            }
            ClickableText(
                text = annotated,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                onClick = { offset ->
                    annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()
                        ?.let { ann ->
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(ann.item))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                },
            )
        }
    }

    update?.let { u ->
        UpdateDialog(
            update = u,
            downloadProgress = downloadProgress,
            downloadError = downloadError,
            needsInstallPerm = awaitingInstallPerm,
            onDownload = {
                downloadError = null
                downloadProgress = 0f
                scope.launch {
                    val result = UpdateChecker.download(
                        context = context,
                        url = u.apkUrl,
                        versionName = u.versionName,
                    ) { p -> downloadProgress = p }
                    when (result) {
                        is UpdateChecker.DownloadResult.Success -> {
                            downloadProgress = null
                            if (UpdateChecker.canInstall(context)) {
                                val ok = UpdateChecker.installApk(context, result.file)
                                if (ok) {
                                    update = null
                                } else {
                                    downloadError = context.getString(R.string.check_update_install_launch_failed)
                                }
                            } else {
                                awaitingInstallPerm = true
                            }
                        }
                        is UpdateChecker.DownloadResult.Error -> {
                            downloadProgress = null
                            downloadError = result.message
                        }
                    }
                }
            },
            onOpenSettings = { UpdateChecker.openInstallPermissionSettings(context) },
            onDismiss = {
                if (downloadProgress == null) {
                    update = null
                    downloadError = null
                    awaitingInstallPerm = false
                }
            },
        )
    }
}

@Composable
private fun UpdateDialog(
    update: UpdateChecker.CheckResult.UpdateAvailable,
    downloadProgress: Float?,
    downloadError: String?,
    needsInstallPerm: Boolean,
    onDownload: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.check_update_available_title))
                Text(
                    stringResource(
                        R.string.check_update_available_subtitle,
                        BuildConfig.VERSION_NAME,
                        update.versionName,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (update.changelog.isNotBlank()) {
                    Text(
                        stringResource(R.string.check_update_changelog_header).uppercaseForDisplay(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = update.changelog,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                    )
                }
                Text(
                    stringResource(R.string.check_update_install_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (downloadProgress != null) {
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.check_update_downloading, (downloadProgress * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (downloadError != null) {
                    Text(
                        stringResource(R.string.check_update_download_failed, downloadError),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (needsInstallPerm) {
                    Text(
                        stringResource(R.string.check_update_install_perm_required),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (needsInstallPerm) {
                MinisButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.check_update_open_install_settings))
                }
            } else {
                MinisButton(
                    onClick = onDownload,
                    enabled = downloadProgress == null,
                ) {
                    if (downloadProgress != null && downloadProgress < 1f) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp,
                            )
                            Text(stringResource(R.string.check_update_downloading, (downloadProgress * 100).toInt()))
                        }
                    } else {
                        Text(stringResource(R.string.check_update_download_button))
                    }
                }
            }
        },
        dismissButton = {
            MinisTextButton(onClick = onDismiss, enabled = downloadProgress == null) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
