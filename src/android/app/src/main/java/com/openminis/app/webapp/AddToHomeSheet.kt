package com.openminis.app.webapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AGColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.R
import com.openminis.app.data.repository.WebAppShortcutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Source of HTML bytes for the "Add to Home Screen" sheet. T-pwa-2 only
 * supported chat-attachment URIs; T-pwa-3 adds [HostFile] for FileBrowser
 * rows where we already know an absolute Linux path under
 * `/var/minis/shared/...` or `/var/minis/mounts/<name>/...` and don't
 * need to copy bytes — the shortcut links to the live file in place.
 */
sealed class WebAppSource {
    /** Chat attachment chip → copy bytes into per-session attachments dir. */
    data class ChatAttachment(
        val uri: Uri,
        val fileName: String,
        val sessionId: String,
        val sessionTitle: String?,
    ) : WebAppSource()

    /**
     * FileBrowser row → host file already on disk, link in place.
     * [linuxPath] is the absolute `/var/minis/...` path the shortcut
     * persists; [pathScope] is `shared` or `mount`; [scopeContext] is
     * null for shared, the mount name for mount.
     */
    data class HostFile(
        val file: File,
        val fileName: String,
        val pathScope: String,
        val scopeContext: String?,
        val linuxPath: String,
    ) : WebAppSource()
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
/**
 * Bottom-sheet for "Add to Home Screen". T-pwa-2 launched it from the
 * chat-attachment chip's long-press menu; T-pwa-3 also launches it from
 * the FileBrowser row long-press menu via [WebAppSource.HostFile]. The sheet
 * lets the user pick a title + icon, then calls
 * [WebAppShortcutRepository.create] + [ShortcutPinner.pin].
 */
@Composable
fun AddToHomeSheet(
    source: WebAppSource,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // shortcutId is decided up-front so the copied attachment lives at
    // <attachmentsDir>/<shortcutId>/<fileName> — stable for the life of
    // the shortcut even if the user later removes the chip. (Only used
    // by the ChatAttachment branch; the HostFile branch links in place.)
    val shortcutId = remember { UUID.randomUUID().toString() }

    val fileName: String = when (source) {
        is WebAppSource.ChatAttachment -> source.fileName
        is WebAppSource.HostFile -> source.fileName
    }

    // For ChatAttachment we copy the source URI into a stable host path
    // before the user commits; for HostFile the file already exists so
    // we just point [copiedFile] at it.
    var copiedFile by remember { mutableStateOf<File?>(null) }
    var copyFailed by remember { mutableStateOf(false) }
    var parsedTitle by remember { mutableStateOf<String?>(null) }
    var webpageIconPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(source, shortcutId) {
        withContext(Dispatchers.IO) {
            when (source) {
                is WebAppSource.ChatAttachment -> {
                    val attachmentsDir = File(
                        context.filesDir,
                        "sessions/${source.sessionId}/attachments/$shortcutId",
                    )
                    attachmentsDir.mkdirs()
                    val safeName = source.fileName.ifBlank { "page.html" }
                    val target = File(attachmentsDir, safeName)
                    runCatching {
                        context.contentResolver.openInputStream(source.uri)?.use { input ->
                            target.outputStream().use { out -> input.copyTo(out) }
                        } ?: error("openInputStream returned null")
                    }.onSuccess {
                        copiedFile = target
                        parsedTitle = parseHtmlTitle(target)
                        webpageIconPath = WebAppIconExtractor.extractFromHtml(target.absolutePath)?.localPath
                    }.onFailure {
                        copyFailed = true
                    }
                }
                is WebAppSource.HostFile -> {
                    if (!source.file.exists() || !source.file.isFile) {
                        copyFailed = true
                    } else {
                        copiedFile = source.file
                        parsedTitle = parseHtmlTitle(source.file)
                        webpageIconPath = WebAppIconExtractor.extractFromHtml(source.file.absolutePath)?.localPath
                    }
                }
            }
        }
    }

    var titleText by remember { mutableStateOf("") }
    LaunchedEffect(parsedTitle, fileName) {
        if (titleText.isBlank()) {
            titleText = parsedTitle?.takeIf { it.isNotBlank() }
                ?: fileName.removeSuffix(".html").removeSuffix(".htm")
        }
    }

    var selectedIcon by remember { mutableStateOf<IconChoice>(IconChoice.Preset(IconPreset.Globe)) }

    // Auto-promote to webpage icon once we discover one.
    LaunchedEffect(webpageIconPath) {
        if (webpageIconPath != null && selectedIcon is IconChoice.Preset) {
            selectedIcon = IconChoice.Webpage(webpageIconPath!!)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            selectedIcon = IconChoice.Gallery(uri)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.webapp_add_to_home),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            // Title input.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.webapp_sheet_title_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Source label (read-only). For chat attachments we show the
            // linked session id/title; for FileBrowser host files we show
            // the absolute Linux path so the user knows what's being linked.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.webapp_sheet_session_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                val sourceLabel = when (source) {
                    is WebAppSource.ChatAttachment ->
                        source.sessionTitle?.takeIf { it.isNotBlank() } ?: source.sessionId
                    is WebAppSource.HostFile -> source.linuxPath
                }
                Text(
                    text = sourceLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Icon selector.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.webapp_sheet_icon_label),
                    style = MaterialTheme.typography.labelLarge,
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Webpage icon (only if extractor found one).
                    webpageIconPath?.let { path ->
                        item {
                            IconOption(
                                selected = selectedIcon is IconChoice.Webpage,
                                onClick = { selectedIcon = IconChoice.Webpage(path) },
                                contentDescription = stringResource(R.string.webapp_sheet_icon_webpage),
                            ) {
                                val bmp = remember(path) {
                                    runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                                }
                                if (bmp != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp),
                                    )
                                } else {
                                    Icon(Icons.Default.Web, contentDescription = null)
                                }
                            }
                        }
                    }

                    // 8 presets.
                    items(IconPreset.values().toList()) { preset ->
                        IconOption(
                            selected = (selectedIcon as? IconChoice.Preset)?.preset == preset,
                            onClick = { selectedIcon = IconChoice.Preset(preset) },
                            contentDescription = preset.name,
                        ) {
                            Icon(preset.imageVector, contentDescription = null)
                        }
                    }
                }

                // Gallery picker entry.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val galleryUri = (selectedIcon as? IconChoice.Gallery)?.uri
                    IconOption(
                        selected = selectedIcon is IconChoice.Gallery,
                        onClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        contentDescription = stringResource(R.string.webapp_sheet_icon_pick),
                    ) {
                        if (galleryUri != null) {
                            val bmp = remember(galleryUri) { loadBitmapFromUri(context, galleryUri) }
                            if (bmp != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                )
                            } else {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                            }
                        } else {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                        }
                    }
                    Text(
                        text = stringResource(R.string.webapp_sheet_icon_pick),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (copyFailed) {
                Text(
                    text = stringResource(R.string.webapp_pin_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Buttons row.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MinisOutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
                MinisButton(
                    enabled = copiedFile != null && titleText.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val target = copiedFile ?: return@MinisButton
                        val finalTitle = titleText.trim().ifBlank {
                            fileName.removeSuffix(".html").removeSuffix(".htm")
                        }
                        scope.launch {
                            val iconBitmap = withContext(Dispatchers.IO) {
                                renderIconBitmap(context, selectedIcon, finalTitle)
                            }
                            val iconRef = when (val c = selectedIcon) {
                                is IconChoice.Webpage -> "html"
                                is IconChoice.Gallery -> "file:${c.uri}"
                                is IconChoice.Preset -> "preset:${c.preset.name.lowercase()}"
                            }
                            val app = context.applicationContext as MinisApp
                            val entity = when (source) {
                                is WebAppSource.ChatAttachment -> {
                                    // htmlPath stays relative under the
                                    // session attachments dir — WebAppPathResolver's
                                    // "relative path" branch resolves it back to
                                    // <filesDir>/sessions/<sid>/attachments/<htmlPath>.
                                    val htmlPath = "$shortcutId/${target.name}"
                                    app.webAppShortcutRepository.create(
                                        htmlPath = htmlPath,
                                        pathScope = WebAppShortcutRepository.SCOPE_SESSION_ATTACHMENT,
                                        scopeContext = source.sessionId,
                                        title = finalTitle,
                                        iconRef = iconRef,
                                        iconCachePath = null,
                                        sourceSessionId = source.sessionId,
                                    )
                                }
                                is WebAppSource.HostFile -> {
                                    // T-pwa-3: link in place — htmlPath is the
                                    // /var/minis/... linux path; WebAppPathResolver
                                    // routes through PRootKernel.resolveHostPath.
                                    app.webAppShortcutRepository.create(
                                        htmlPath = source.linuxPath,
                                        pathScope = source.pathScope,
                                        scopeContext = source.scopeContext,
                                        title = finalTitle,
                                        iconRef = iconRef,
                                        iconCachePath = null,
                                        sourceSessionId = null,
                                    )
                                }
                            }
                            val ok = ShortcutPinner.pin(context, entity, iconBitmap)
                            if (ok) {
                                Toast.makeText(
                                    context,
                                    R.string.webapp_pin_success,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            onDismiss()
                        }
                    },
                ) {
                    Text(stringResource(R.string.webapp_sheet_cta))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun IconOption(
    selected: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .size(48.dp)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp),
            )
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private enum class IconPreset(val imageVector: ImageVector) {
    Globe(Icons.Default.Public),
    Chart(Icons.Default.Insights),
    Book(Icons.Default.Book),
    App(Icons.Default.Apps),
    Video(Icons.Default.VideoLibrary),
    Game(Icons.Default.SportsEsports),
    Music(Icons.Default.MusicNote),
    Generic(Icons.Default.AppShortcut),
}

private sealed class IconChoice {
    data class Preset(val preset: IconPreset) : IconChoice()
    data class Webpage(val path: String) : IconChoice()
    data class Gallery(val uri: Uri) : IconChoice()
}

// ── helpers ─────────────────────────────────────────────────────────────────

private val TITLE_RE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

private fun parseHtmlTitle(file: File): String? {
    val head = runCatching {
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            val n = input.read(buf)
            if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
        }
    }.getOrNull() ?: return null
    val m = TITLE_RE.find(head) ?: return null
    return m.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
}

private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
}.getOrNull()

/**
 * Render a 192x192 adaptive-ready bitmap for the launcher pin. Adaptive
 * icons need 108dp safe-zone inside a 108dp shape; we go with 192x192px
 * which most launchers accept and crop down as needed.
 */
private fun renderIconBitmap(context: Context, choice: IconChoice, title: String): Bitmap {
    val size = 192
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    val bg = Paint().apply {
        isAntiAlias = true
        color = AGColor.parseColor("#FF3B82F6")
    }
    canvas.drawRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), bg)

    when (choice) {
        is IconChoice.Webpage -> {
            val src = runCatching { BitmapFactory.decodeFile(choice.path) }.getOrNull()
            if (src != null) {
                val scaled = Bitmap.createScaledBitmap(src, size, size, true)
                canvas.drawBitmap(scaled, 0f, 0f, null)
                return bmp
            }
            drawTitleGlyph(canvas, title, size)
        }
        is IconChoice.Gallery -> {
            val src = loadBitmapFromUri(context, choice.uri)
            if (src != null) {
                val scaled = Bitmap.createScaledBitmap(src, size, size, true)
                canvas.drawBitmap(scaled, 0f, 0f, null)
                return bmp
            }
            drawTitleGlyph(canvas, title, size)
        }
        is IconChoice.Preset -> {
            drawTitleGlyph(canvas, title, size)
        }
    }
    return bmp
}

private fun drawTitleGlyph(canvas: Canvas, title: String, size: Int) {
    val glyph = title.trim().firstOrNull()?.toString()?.uppercase() ?: "M"
    val paint = Paint().apply {
        isAntiAlias = true
        color = AGColor.WHITE
        textSize = size * 0.5f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(glyph, size / 2f, baseline, paint)
}
