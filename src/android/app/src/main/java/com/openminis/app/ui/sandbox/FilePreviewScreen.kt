package com.openminis.app.ui.sandbox

import com.openminis.app.R
import androidx.compose.ui.res.stringResource
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.rememberIosBounceOverscrollEffect
import com.openminis.app.ui.markdown.MarkdownText
import com.openminis.app.ui.chat.StreamingMarkdownText
import com.openminis.app.ui.media.InlineAudioPlayer
import com.openminis.app.ui.media.InlineVideoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import com.openminis.app.ui.components.MinisTextButton

private const val MAX_TEXT_PREVIEW_BYTES = 512_000 // 500 KB

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewScreen(
    item: FileItem,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // T-android-preview-title-toggle-path: tap the title to swap between
    // file name and absolute path. Mirrors the iOS preview's tap-to-toggle
    // behavior so the user can read the source location without leaving
    // the preview. Long paths use TextOverflow.Ellipsis (the TopAppBar
    // title slot is single-line by spec).
    var showFullPath by remember(item.file) { mutableStateOf(false) }

    // T144: SAF Save-As for non-image files (image keeps T142 MediaStore).
    val mimeType = remember(item.file) {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            item.file.extension.lowercase(),
        ) ?: "application/octet-stream"
    }
    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(mimeType),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val ok = try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    item.file.inputStream().use { it.copyTo(out) }
                }
                true
            } catch (e: Exception) {
                AppLogger.warning("FilePreview", "Save-As failed: ${e.message}")
                false
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(if (ok) R.string.file_saved_toast else R.string.file_save_failed_toast),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // T-imgswipe-4f446d83: image files take the swipe-able gallery route
    // (siblings from the same directory) instead of the in-place
    // ImagePreview column. Gallery owns its own close / save / share /
    // copy chrome, so we skip the Scaffold + TopAppBar entirely.
    if (item.isImageFile) {
        val galleryState = remember(item.file.absolutePath) {
            collectImageGallery(item.file)
        }
        com.openminis.app.ui.components.ImageGalleryViewer(
            items = galleryState.first,
            startIndex = galleryState.second,
            onDismiss = onBack,
        )
        return
    }

    // T279: mirror FileBrowserScreen — vanilla Scaffold + vanilla TopAppBar.
    // Earlier attempts (custom containerColor, contentWindowInsets=0,
    // windowInsets=statusBars on TopAppBar, body windowInsetsPadding +
    // background, DisposableEffect setting statusBarColor / isAppearanceLight)
    // all left visible gray bands above the bar / below the gesture bar
    // because they fought the Activity's edge-to-edge transparent-scrim
    // setup instead of cooperating with it. The "Browse Chat Files" screen
    // (FileBrowserScreen) renders correctly with zero overrides; do the same.
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (showFullPath) item.file.absolutePath else item.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.clickable { showFullPath = !showFullPath },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // T142: Share works for any file — FileProvider URI +
                    // ACTION_SEND + FLAG_GRANT_READ_URI_PERMISSION. iOS parity.
                    IconButton(onClick = { shareFile(context, item) }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.filepreview_share))
                    }
                    // Print: HTML renders via WebView; markdown / plain text /
                    // json / csv print their raw text wrapped in a WebView so we
                    // reuse the single createPrintDocumentAdapter path (Android
                    // has no UISimpleTextPrintFormatter equivalent). Image files
                    // take the gallery route above and never reach this bar.
                    if (item.isHtmlFile || item.isMarkdownFile || item.isTextFile ||
                        item.isJsonFile || item.isCsvFile
                    ) {
                        IconButton(onClick = { printFile(context, item) }) {
                            Icon(Icons.Default.Print, contentDescription = stringResource(R.string.action_print))
                        }
                    }
                    if (item.isImageFile) {
                        // T142 image → MediaStore Save to Gallery.
                        IconButton(onClick = {
                            scope.launch {
                                val ok = saveImageToGallery(context, item.file)
                                Toast.makeText(
                                    context,
                                    context.getString(if (ok) R.string.image_saved_to_album_toast else R.string.image_save_failed_toast),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }) {
                            Icon(Icons.Default.Download, contentDescription = stringResource(R.string.filepreview_save_to_gallery))
                        }
                    } else {
                        // T144 non-image → SAF Save-As (user picks location).
                        IconButton(onClick = { saveAsLauncher.launch(item.name) }) {
                            Icon(Icons.Default.Download, contentDescription = stringResource(R.string.filepreview_save_as))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Order matters: markdown / html before generic text — both are
            // technically text but warrant richer renderers.
            when {
                item.isMarkdownFile -> MarkdownPreview(item)
                item.isHtmlFile -> HtmlPreview(item)
                item.isImageFile -> ImagePreview(item)
                item.isAudioFile -> AudioPreview(item)
                item.isVideoFile -> VideoPreview(item)
                item.isPdfFile -> PdfPreview(item)
                item.isCsvFile -> CsvPreview(item)
                item.isJsonFile -> JsonPreview(item)
                // [T-android-apk-preview] BEFORE isArchiveFile — an APK is a
                // ZIP, and the generic archive branch would win and dump its
                // entry list.
                item.isApkFile -> ApkPreview(item)
                item.isArchiveFile -> ArchivePreview(item)
                item.isOfficeFile -> OfficeOpenExternal(item)
                item.isTextFile -> TextPreview(item)
                else -> FileInfoView(item)
            }
        }
    }
}

// ==================== Image Preview ====================

@Composable
private fun ImagePreview(item: FileItem) {
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(item.file) {
        withContext(Dispatchers.IO) {
            try {
                val bmp = BitmapFactory.decodeFile(item.file.absolutePath)
                if (bmp != null) {
                    bitmap = bmp
                } else {
                    error = ctx.getString(R.string.filepreview_image_decode_error)
                }
            } catch (e: Exception) {
                error = e.message ?: ctx.getString(R.string.filepreview_image_load_error)
            }
        }
    }

    when {
        error != null -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        error!!,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        bitmap != null -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Image info
                Text(
                    text = "${bitmap!!.width} x ${bitmap!!.height} px  |  ${item.formattedSize}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        else -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.filepreview_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ==================== Text/Code Preview ====================

@Composable
private fun TextPreview(item: FileItem) {
    var content by remember { mutableStateOf<String?>(null) }
    var truncated by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.file) {
        withContext(Dispatchers.IO) {
            try {
                val bytes = item.file.readBytes()
                if (bytes.size > MAX_TEXT_PREVIEW_BYTES) {
                    content = String(bytes, 0, MAX_TEXT_PREVIEW_BYTES, Charsets.UTF_8)
                    truncated = true
                } else {
                    content = String(bytes, Charsets.UTF_8)
                    truncated = false
                }
            } catch (e: Exception) {
                error = e.message ?: "Failed to read file"
            }
        }
    }

    when {
        error != null -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
        }

        content != null -> {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                if (truncated) {
                    Text(
                        text = "Showing first ${MAX_TEXT_PREVIEW_BYTES / 1000} KB of ${item.formattedSize}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    HorizontalDivider()
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Box(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = content!!,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                            ),
                            softWrap = false,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }

        else -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.filepreview_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ==================== Markdown Preview ====================

@Composable
private fun MarkdownPreview(item: FileItem) {
    var content by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.file) {
        withContext(Dispatchers.IO) {
            try {
                val bytes = item.file.readBytes()
                val cap = if (bytes.size > MAX_TEXT_PREVIEW_BYTES) MAX_TEXT_PREVIEW_BYTES else bytes.size
                content = String(bytes, 0, cap, Charsets.UTF_8)
            } catch (e: Exception) {
                error = e.message ?: "Failed to read file"
                AppLogger.warning("FilePreview", "markdown read failed for ${item.name}: ${e.message}")
            }
        }
    }

    when {
        error != null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        content != null -> com.openminis.app.ui.chat.MarkdownDocument(
            // T285-md: was StreamingMarkdownText inside verticalScroll{}, which
            // composed the full document up-front and stalled the chat-tap →
            // preview transition with main-thread parseInline scans across
            // all blocks (~150-300ms for a multi-KB markdown). MarkdownDocument
            // uses LazyColumn so only viewport-visible blocks compose on the
            // first frame — transition completes before the off-screen blocks
            // are touched.
            content = content!!,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp, vertical = 12.dp,
            ),
        )
        else -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ==================== HTML Preview (WebView) ====================

@Composable
private fun HtmlPreview(item: FileItem) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = true
                // T-webview-popup-d3c6e10f: mirror ffc85ad's WebPreviewBottomSheet
                // fix. Pages using `height: 100vh` + `overflow: hidden` were
                // collapsing to a 0-height clipped box (white screen) on first
                // compose because Blink resolved CSS viewport units against a
                // 0×0 measured container. useWideViewPort + loadWithOverviewMode
                // decouple the CSS viewport from initial measured size, and
                // deferring loadUrl via `post {}` guarantees the WebView has
                // been laid out (positive width/height) before Blink resolves
                // viewport units.
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                webViewClient = WebViewClient()
                val targetUrl = "file://${item.file.absolutePath}"
                post { loadUrl(targetUrl) }
            }
        },
    )
}

// ==================== Audio Preview ====================

@Composable
private fun AudioPreview(item: FileItem) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Top,
    ) {
        InlineAudioPlayer(filePath = item.file.absolutePath)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = item.formattedSize,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ==================== Video Preview ====================

@Composable
private fun VideoPreview(item: FileItem) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        InlineVideoPlayer(filePath = item.file.absolutePath)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = item.formattedSize,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ==================== PDF (native PdfRenderer) ====================

/**
 * T144: in-app PDF viewer via Android's stock [PdfRenderer]. Each page is
 * rendered to a Bitmap once, scaled to the page's natural aspect at the
 * card width, and shown in a LazyColumn so 100-page PDFs don't OOM.
 * Capped at 50 pages — full doc available via Save-As / external open.
 */
@Composable
private fun PdfPreview(item: FileItem) {
    var pages by remember(item.file) { mutableStateOf<List<Bitmap>?>(null) }
    var error by remember(item.file) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.file) {
        withContext(Dispatchers.IO) {
            try {
                ParcelFileDescriptor.open(item.file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        val out = ArrayList<Bitmap>(renderer.pageCount)
                        val limit = minOf(renderer.pageCount, 50)
                        for (i in 0 until limit) {
                            renderer.openPage(i).use { page ->
                                val targetW = 1600
                                val targetH = (targetW.toFloat() * page.height / page.width).toInt()
                                val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                out.add(bmp)
                            }
                        }
                        pages = out
                    }
                }
            } catch (e: Exception) {
                AppLogger.warning("FilePreview", "PdfRenderer failed for ${item.name}: ${e.message}")
                error = e.message ?: "Failed to render PDF"
            }
        }
    }

    when {
        error != null -> PdfOpenExternalFallback(item, error!!)
        pages == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.filepreview_loading_pdf), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        pages!!.isEmpty() -> PdfOpenExternalFallback(item, stringResource(R.string.filepreview_pdf_empty))
        else -> {
            val rendered = pages!!
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(rendered.size) { idx ->
                    Image(
                        bitmap = rendered[idx].asImageBitmap(),
                        contentDescription = stringResource(R.string.filepreview_page_n, idx + 1),
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfOpenExternalFallback(item: FileItem, reason: String) {
    val context = LocalContext.current
    // T149: same regression as Office — fallback page now reuses the shared
    // metadata block under the placeholder so an unrenderable PDF still shows
    // file basics rather than just a button.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.filepreview_pdf_render_failed, reason),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            MinisTextButton(onClick = { openExternally(context, item, "application/pdf") }) {
                Text(stringResource(R.string.filepreview_open_externally))
            }
        }
        FileMetadataBlock(item)
    }
}

// ==================== CSV / TSV ====================

@Composable
private fun CsvPreview(item: FileItem) {
    var rows by remember(item.file) { mutableStateOf<List<List<String>>?>(null) }
    var truncated by remember(item.file) { mutableStateOf(false) }
    var error by remember(item.file) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.file) {
        withContext(Dispatchers.IO) {
            try {
                val sep = if (item.file.extension.equals("tsv", true)) '\t' else ','
                val parsed = mutableListOf<List<String>>()
                item.file.bufferedReader(Charsets.UTF_8).use { br ->
                    var read = 0
                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        if (read >= 200) { truncated = true; break }
                        parsed.add(parseCsvLine(line!!, sep))
                        read++
                    }
                }
                rows = parsed
            } catch (e: Exception) {
                error = e.message ?: "Failed to parse CSV"
            }
        }
    }

    when {
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        rows == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.filepreview_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> {
            val table = rows!!
            Column(Modifier.fillMaxSize()) {
                if (truncated) {
                    Text(
                        text = "Showing first 200 rows of ${item.formattedSize}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    HorizontalDivider()
                }
                LazyColumn(modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                    items(table.size) { rowIdx ->
                        val cols = table[rowIdx]
                        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            cols.forEach { cell ->
                                Text(
                                    text = cell,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        fontWeight = if (rowIdx == 0) FontWeight.Bold else FontWeight.Normal,
                                    ),
                                    modifier = Modifier.padding(end = 16.dp).width(140.dp),
                                    maxLines = 1,
                                )
                            }
                        }
                        if (rowIdx == 0) HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun parseCsvLine(line: String, sep: Char): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                cur.append('"'); i += 2; continue
            }
            c == '"' -> inQuotes = !inQuotes
            c == sep && !inQuotes -> { out.add(cur.toString()); cur.clear() }
            else -> cur.append(c)
        }
        i++
    }
    out.add(cur.toString())
    return out
}

// ==================== JSON pretty-print ====================

@Composable
private fun JsonPreview(item: FileItem) {
    var pretty by remember(item.file) { mutableStateOf<String?>(null) }
    var truncated by remember(item.file) { mutableStateOf(false) }
    var error by remember(item.file) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.file) {
        withContext(Dispatchers.IO) {
            try {
                val bytes = item.file.readBytes()
                val cap = if (bytes.size > MAX_TEXT_PREVIEW_BYTES) {
                    truncated = true; MAX_TEXT_PREVIEW_BYTES
                } else bytes.size
                val raw = String(bytes, 0, cap, Charsets.UTF_8)
                pretty = try {
                    when (raw.trimStart().firstOrNull()) {
                        '{' -> org.json.JSONObject(raw).toString(2)
                        '[' -> org.json.JSONArray(raw).toString(2)
                        else -> raw
                    }
                } catch (_: Exception) { raw }
            } catch (e: Exception) {
                error = e.message ?: "Failed to read file"
            }
        }
    }

    when {
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        pretty == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.filepreview_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> Column(Modifier.fillMaxSize()) {
            if (truncated) {
                Text(
                    text = "Showing first ${MAX_TEXT_PREVIEW_BYTES / 1000} KB of ${item.formattedSize}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                HorizontalDivider()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Box(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = pretty!!,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        ),
                        softWrap = false,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

// ==================== APK ====================

/**
 * [T-android-apk-preview] Package card for an .apk, instead of the ZIP entry
 * list the generic archive renderer used to show.
 *
 * An APK *is* a ZIP, so it fell into [ArchivePreview] and rendered 282 rows of
 * `META-INF/androidx.*.version` — accurate and useless. Someone who taps an APK
 * in a chat wants to know what app it is and then install it, so this shows the
 * package identity (icon, label, version, package name, min/target SDK) and
 * puts "Install" front and centre.
 *
 * Metadata is read with [PackageManager.getPackageArchiveInfo], which parses the
 * manifest WITHOUT installing anything. Its `applicationInfo` comes back with
 * `sourceDir`/`publicSourceDir` unset — they must be pointed at the file by hand
 * or `loadIcon`/`loadLabel` resolve against the wrong package and return this
 * app's own icon.
 *
 * Parsing can fail (a corrupt download, or an .xapk which is a ZIP OF apks and
 * has no manifest of its own). That is not an error state worth blocking on:
 * the file still exists and can still be opened or shared, so the card falls
 * back to filename + size and keeps the actions live.
 */
@Composable
private fun ApkPreview(item: FileItem) {
    val context = androidx.compose.ui.platform.LocalContext.current
    data class ApkInfo(
        val label: String?,
        val packageName: String?,
        val versionName: String?,
        val versionCode: Long?,
        val minSdk: Int?,
        val targetSdk: Int?,
        val icon: android.graphics.drawable.Drawable?,
    )

    var info by remember(item.file) { mutableStateOf<ApkInfo?>(null) }
    var parsed by remember(item.file) { mutableStateOf(false) }

    LaunchedEffect(item.file) {
        withContext(Dispatchers.IO) {
            val result = try {
                val pm = context.packageManager
                val pkg = pm.getPackageArchiveInfo(item.file.absolutePath, 0)
                if (pkg == null) {
                    null
                } else {
                    // Without these the icon/label lookups resolve against the
                    // HOST package and silently return Minis' own assets.
                    pkg.applicationInfo?.let { app ->
                        app.sourceDir = item.file.absolutePath
                        app.publicSourceDir = item.file.absolutePath
                    }
                    ApkInfo(
                        label = pkg.applicationInfo?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() },
                        packageName = pkg.packageName,
                        versionName = pkg.versionName,
                        versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            pkg.longVersionCode
                        } else {
                            @Suppress("DEPRECATION") pkg.versionCode.toLong()
                        },
                        minSdk = pkg.applicationInfo?.minSdkVersion,
                        targetSdk = pkg.applicationInfo?.targetSdkVersion,
                        icon = pkg.applicationInfo?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() },
                    )
                }
            } catch (e: Exception) {
                AppLogger.warning("FilePreview", "APK parse failed: ${e.message}")
                null
            }
            info = result
            parsed = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val icon = info?.icon
            if (icon != null) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.graphics.painter.BitmapPainter(
                        icon.toBitmap().asImageBitmap(),
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                )
            } else {
                Icon(
                    Icons.Default.Android,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                // Falls back to the filename when the manifest could not be
                // read — still the most useful thing we can name it.
                text = info?.label ?: item.name,
                style = MaterialTheme.typography.titleMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            info?.packageName?.let { pkg ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = pkg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            val version = info?.versionName
            if (version != null) {
                Spacer(Modifier.height(2.dp))
                val code = info?.versionCode
                Text(
                    text = if (code != null) "$version ($code)" else version,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = item.formattedSize,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (parsed && info == null) {
                // Say so rather than showing a bare filename and letting the
                // user wonder whether the file is broken.
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.filepreview_apk_unreadable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
            // ACTION_VIEW on an application/vnd.android.package-archive URI is
            // what reaches the system installer. It is offered even when the
            // manifest failed to parse: the installer does its own, stricter
            // validation and will report a bad package better than we can.
            MinisTextButton(onClick = {
                openExternally(context, item, "application/vnd.android.package-archive")
            }) {
                Text(stringResource(R.string.filepreview_apk_install))
            }
        }
        FileMetadataBlock(item)
    }
}

// ==================== Archive (ZIP/JAR/APK) ====================

@Composable
private fun ArchivePreview(item: FileItem) {
    data class Entry(val name: String, val size: Long, val isDir: Boolean)
    var entries by remember(item.file) { mutableStateOf<List<Entry>?>(null) }
    var error by remember(item.file) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.file) {
        withContext(Dispatchers.IO) {
            try {
                val out = mutableListOf<Entry>()
                java.util.zip.ZipFile(item.file).use { zf ->
                    val it = zf.entries()
                    while (it.hasMoreElements()) {
                        val e = it.nextElement()
                        out.add(Entry(e.name, e.size, e.isDirectory))
                        if (out.size >= 2000) break
                    }
                }
                entries = out.sortedBy { it.name }
            } catch (e: Exception) {
                error = e.message ?: "Failed to read archive"
            }
        }
    }

    when {
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        entries == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.filepreview_loading_archive), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> {
            val list = entries!!
            Column(Modifier.fillMaxSize()) {
                Text(
                    text = "${list.size} entries  •  ${item.formattedSize}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(list.size) { i ->
                        val e = list[i]
                        ListItem(
                            headlineContent = {
                                Text(
                                    e.name,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                    ),
                                    maxLines = 1,
                                )
                            },
                            supportingContent = if (e.isDir) null else {
                                { Text(android.text.format.Formatter.formatFileSize(null, e.size)) }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// ==================== Office (xlsx/docx/pptx → external) ====================

@Composable
private fun OfficeOpenExternal(item: FileItem) {
    val context = LocalContext.current
    // T149: was full-screen-centered placeholder + button only — restored
    // metadata block beneath. Top half = preview hint + Open externally,
    // bottom = Name/Size/Type/Modified/Path so the user always sees the
    // basic file info that existed pre-T144.
    // T164: outer scrollable wrapper pumps overscroll deltas into our
    // IosBounceOverscrollEffect — `Modifier.verticalScroll` doesn't
    // expose an overscrollEffect slot, so we mirror the
    // AlwaysStretchOverscrollBox trick (wrap with a noop scrollable
    // that consumes 0 deltas, pipe the same effect through both
    // Modifier.scrollable's overscrollEffect param and Modifier.overscroll
    // for the visual rubber-band). The inner verticalScroll still owns
    // the actual scroll position; only the over-edge component gets
    // routed through the spring bounce.
    val bounce = rememberIosBounceOverscrollEffect()
    val noopState = rememberScrollableState { 0f }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .scrollable(
                state = noopState,
                orientation = Orientation.Vertical,
                overscrollEffect = bounce,
            )
            .overscroll(bounce)
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.filepreview_office_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            MinisTextButton(onClick = {
                val mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(item.file.extension.lowercase())
                    ?: "application/octet-stream"
                openExternally(context, item, mime)
            }) {
                Text(stringResource(R.string.filepreview_open_externally))
            }
        }
        FileMetadataBlock(item)
    }
}

private fun openExternally(context: Context, item: FileItem, mime: String) {
    try {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, item.file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Open with…"))
    } catch (e: Exception) {
        AppLogger.warning("FilePreview", "openExternally failed: ${e.message}")
        Toast.makeText(context, "No app available to open this file.", Toast.LENGTH_SHORT).show()
    }
}

// ==================== File Info (fallback for unsupported types) ====================

@Composable
private fun FileInfoView(item: FileItem) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Icon header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Preview not available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        FileMetadataBlock(item)
    }
}

/**
 * T149: shared metadata list — Name / Size / Type / Modified / Path (+ symlink
 * target). Pre-T142 this lived inline in FileInfoView; T144 only wired it for
 * the unknown-type fallback, so Office/PDF-fallback regressed to "just a
 * button". Extracted so any preview path that doesn't render bytes inline can
 * still surface the basics.
 */
@Composable
private fun FileMetadataBlock(item: FileItem) {
    HorizontalDivider()
    val attrs = remember(item) { buildFileAttributes(item) }
    attrs.forEach { (key, value) ->
        ListItem(
            headlineContent = { Text(value) },
            overlineContent = { Text(key) },
        )
    }
}

private fun buildFileAttributes(item: FileItem): List<Pair<String, String>> {
    val attrs = mutableListOf<Pair<String, String>>()
    attrs.add("Name" to item.name)
    attrs.add("Size" to item.formattedSize)

    val ext = item.file.extension
    if (ext.isNotEmpty()) {
        attrs.add("Type" to ext.uppercase())
    }

    val modified = item.file.lastModified()
    if (modified > 0) {
        attrs.add("Modified" to FileBrowserViewModel.formatDate(modified))
    }

    attrs.add("Path" to item.file.absolutePath)

    if (item.isSymlink) {
        try {
            val target = java.nio.file.Files.readSymbolicLink(item.file.toPath())
            attrs.add("Link Target" to target.toString())
        } catch (_: Exception) { }
    }

    return attrs
}

// ==================== Share / Save helpers (T142) ====================

/**
 * Share any file via the system share sheet. Mirrors iOS UIActivityViewController.
 * Uses our FileProvider authority so the receiving app can read the bytes,
 * with FLAG_GRANT_READ_URI_PERMISSION attached so the grant follows the chooser
 * pick (the chooser itself launches a separate activity that wouldn't otherwise
 * be in the granted set).
 */
private fun shareFile(context: Context, item: FileItem) {
    try {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, item.file)
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(item.file.extension.lowercase())
            ?: "*/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.file_share_chooser_title)))
    } catch (e: Exception) {
        AppLogger.warning("FilePreview", "share failed for ${item.name}: ${e.message}")
        Toast.makeText(context, context.getString(R.string.file_share_failed_toast, e.message ?: ""), Toast.LENGTH_SHORT).show()
    }
}

/**
 * Print a previewable file via the Android print framework. HTML loads
 * directly; text-based files (markdown / plain text / json / csv) are wrapped
 * in an HTML `<pre>` block so a single `WebView.createPrintDocumentAdapter`
 * path covers every case. Mirrors iOS where every preview surface funnels
 * through one print controller.
 *
 * The off-screen WebView must outlive this function: print is async (we kick it
 * off only after `onPageFinished`), so we hold the instance in a captured var
 * and clear it once the adapter is handed to PrintManager.
 */
private fun printFile(context: Context, item: FileItem) {
    try {
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = true
        }
        // Keep a reference alive until the print job is dispatched.
        var holder: WebView? = webView
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val printManager =
                    context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val jobName = "${context.getString(R.string.app_name)} - ${item.name}"
                val adapter = view.createPrintDocumentAdapter(jobName)
                printManager.print(
                    jobName,
                    adapter,
                    PrintAttributes.Builder().build(),
                )
                holder = null
            }
        }
        if (item.isHtmlFile) {
            webView.loadUrl("file://${item.file.absolutePath}")
        } else {
            val raw = item.file.readBytes().let { bytes ->
                val cap = if (bytes.size > MAX_TEXT_PREVIEW_BYTES) MAX_TEXT_PREVIEW_BYTES else bytes.size
                String(bytes, 0, cap, Charsets.UTF_8)
            }
            val html = buildString {
                append("<html><head><meta charset=\"utf-8\">")
                append("<style>body{font-family:monospace;font-size:12px;white-space:pre-wrap;word-wrap:break-word;}</style>")
                append("</head><body><pre>")
                append(escapeHtml(raw))
                append("</pre></body></html>")
            }
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
        // Silence the unused-assignment warning while documenting intent: the
        // holder keeps `webView` reachable across the async page load.
        @Suppress("UNUSED_VALUE")
        holder = webView
    } catch (e: Exception) {
        AppLogger.warning("FilePreview", "print failed for ${item.name}: ${e.message}")
        Toast.makeText(context, context.getString(R.string.file_print_failed_toast, e.message ?: ""), Toast.LENGTH_SHORT).show()
    }
}

private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

/**
 * Save an image file from the rootfs to the user's gallery. Q+ uses
 * MediaStore (scoped storage); pre-Q falls back to public Pictures dir.
 * Mirrors FullscreenImageViewer.saveToGallery so the two save paths stay
 * consistent — read bytes off the source file (which may be a multi-MB
 * PNG/JPEG) on Dispatchers.IO.
 */
private suspend fun saveImageToGallery(context: Context, src: File): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val ext = src.extension.lowercase().ifEmpty { "png" }
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/png"
            val filename = "minis_${System.currentTimeMillis()}.$ext"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/Minis",
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
                ) ?: return@withContext false
                context.contentResolver.openOutputStream(uri)?.use { out: OutputStream ->
                    src.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES,
                )
                val minisDir = File(dir, "Minis").also { it.mkdirs() }
                val dest = File(minisDir, filename)
                src.inputStream().use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
            }
            true
        } catch (e: Exception) {
            AppLogger.warning("FilePreview", "saveImageToGallery failed: ${e.message}")
            false
        }
    }

/**
 * Build the swipe-able gallery's (items, startIndex) for a tapped image
 * file: enumerate all image siblings in its parent directory (sorted by
 * name, case-insensitive), find the tapped one's index, and wrap them as
 * [com.openminis.app.ui.components.ImageGalleryItem]. Non-image files are
 * filtered by extension here — matches [FileItem.isImageFile].
 *
 * Falls back to a 1-item list (just the tapped file) when the parent dir
 * can't be listed.
 */
private val IMAGE_GALLERY_EXTENSIONS =
    setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "ico")

private fun collectImageGallery(
    file: java.io.File,
): Pair<List<com.openminis.app.ui.components.ImageGalleryItem>, Int> {
    val parent = file.parentFile
    val siblings = parent?.listFiles()
        ?.filter {
            it.isFile && it.extension.lowercase() in IMAGE_GALLERY_EXTENSIONS
        }
        ?.sortedBy { it.name.lowercase() }
        .orEmpty()
    val list = if (siblings.isEmpty()) listOf(file) else siblings
    val startIdx = list.indexOfFirst { it.absolutePath == file.absolutePath }
        .coerceAtLeast(0)
    val items = list.map {
        com.openminis.app.ui.components.ImageGalleryItem(
            model = it,
            caption = it.name,
        )
    }
    return items to startIdx
}

