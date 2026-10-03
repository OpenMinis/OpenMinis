package com.openminis.app.ui.browser

import com.openminis.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.browser.BrowserHistoryStore
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.browser.UserAgentProfile
import com.openminis.app.ui.chat.StandardChatSheet
import kotlinx.coroutines.launch
import com.openminis.app.ui.components.DecorativeSpinner
import com.openminis.app.ui.components.rememberDecorativeTick
import com.openminis.app.ui.components.decorativePhase
import com.openminis.app.ui.components.decorativePingPong
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Bottom sheet presenting the browser tab pool with tab bar, URL bar,
 * WebView, and navigation controls. Mirrors iOS BrowserSheetView.
 */
@Composable
fun BrowserSheet(
    tabPool: BrowserTabPool,
    onDismiss: () -> Unit,
) {
    val tabs by tabPool.tabs.collectAsState()
    val selectedTabId by tabPool.selectedTabId.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val selectedTab = tabs.find { it.id == selectedTabId }
    val currentURL = selectedTab?.manager?.currentURL?.collectAsState()?.value ?: ""
    val pageTitle = selectedTab?.manager?.pageTitle?.collectAsState()?.value ?: ""
    val isLoading = selectedTab?.manager?.isLoading?.collectAsState()?.value ?: false
    val canGoBack = selectedTab?.manager?.canGoBack?.collectAsState()?.value ?: false
    val canGoForward = selectedTab?.manager?.canGoForward?.collectAsState()?.value ?: false
    val isAgentBusy = tabPool.isAgentBusy
    val userAgentProfile = tabPool.currentUserAgentProfile.collectAsState().value

    var urlInput by remember(currentURL) { mutableStateOf(currentURL) }
    var showHistory by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    // [T-android-browser-download-ux] Downloads panel + badge state.
    var showDownloads by remember { mutableStateOf(false) }
    val downloadEntries by tabPool.downloads.collectAsState()

    val accent = MaterialTheme.colorScheme.primary
    val secondaryBg = MaterialTheme.colorScheme.surfaceContainer
    val tertiaryBg = MaterialTheme.colorScheme.surfaceContainerHigh

    // Mirror iOS onAppear: ensure at least one tab exists when the sheet opens.
    // Callers should also call `ensureTabForUI()` before flipping the sheet
    // visible so the first composition sees a non-empty tab list; this is a
    // defensive fallback.
    LaunchedEffect(Unit) {
        if (tabs.isEmpty()) tabPool.ensureTabForUI()
    }

    // Prevent the bottom sheet from swallowing WebView scroll gestures.
    // Returning the full delta as "consumed" on pre-scroll keeps the sheet's
    // nested-scroll dispatcher from dragging the sheet down when the user
    // scrolls inside a webpage.
    val webViewScrollGuard = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = available
        }
    }

    StandardChatSheet(
        title = pageTitle.ifEmpty { stringResource(R.string.browser_title) },
        onDismiss = onDismiss,
        leadingAction = {
            // UA-profile icon doubles as the entry point to settings, matching
            // the prior centered-title-with-icon affordance.
            IconButton(onClick = { showSettings = true }) {
                Icon(
                    when (userAgentProfile) {
                        UserAgentProfile.MOBILE_CHROME -> Icons.Default.PhoneAndroid
                        UserAgentProfile.DESKTOP_CHROME -> Icons.Default.Computer
                        UserAgentProfile.CUSTOM -> Icons.Default.Edit
                    },
                    contentDescription = stringResource(R.string.browser_settings_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Tab Bar with leading "+" and trailing History icon ──
            // (Add/History flank the tabs row so all tab-related controls
            // sit on one line, leaving the URL bar uncluttered below.)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tertiaryBg)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { scope.launch { tabPool.newTabFromUI() } },
                    enabled = tabs.size < 3 && !isAgentBusy,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.browser_new_tab), modifier = Modifier.size(20.dp))
                }
                LazyRow(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        val title = tab.manager.pageTitle.collectAsState().value
                        val domain = tab.manager.currentURL.collectAsState().value
                            .let { url -> try { java.net.URI(url).host } catch (_: Exception) { null } }
                        val displayTitle = title.ifEmpty { domain ?: "Tab ${tab.id}" }
                            .take(20)
                        val isSelected = tab.id == selectedTabId

                        TabChip(
                            title = displayTitle,
                            isSelected = isSelected,
                            showClose = !isAgentBusy,
                            accent = accent,
                            onClick = { tabPool.selectTab(tab.id) },
                            onClose = { scope.launch { tabPool.closeTabFromUI(tab.id) } },
                        )
                    }
                    item {
                        Text(
                            "${tabs.size}/3",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                IconButton(
                    onClick = { showHistory = true },
                    enabled = !isAgentBusy,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.History, contentDescription = stringResource(R.string.browser_history_action), modifier = Modifier.size(20.dp))
                }
            }

            // ── URL Bar ── (compact 36dp pill — OutlinedTextField defaults to
            // ~56dp which dominates the sheet header; users want to spend the
            // space on the WebView.)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(secondaryBg)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(tertiaryBg, RoundedCornerShape(10.dp))
                        .border(
                            0.5.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrowserAddressBarIcon(isLoading = isLoading, accent = accent)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            singleLine = true,
                            enabled = !isAgentBusy,
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                val trimmed = urlInput.trim()
                                if (trimmed.isNotEmpty()) {
                                    val normalized = normalizeURLInput(trimmed)
                                    selectedTab?.manager?.loadURL(normalized)
                                    urlInput = normalized
                                }
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (urlInput.isEmpty()) {
                            Text(
                                stringResource(R.string.browser_search_placeholder),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (isLoading) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = { selectedTab?.manager?.stopLoading() },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.browser_stop),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            }

            // ── WebView + overlays ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(webViewScrollGuard),
            ) {
                if (selectedTab != null && currentURL.isNotEmpty()) {
                    // Only mount the WebView once there's something to display.
                    // Mounting an empty WebView (no URL loaded) corrupts the
                    // OpenGL swap behavior for the hosting ModalBottomSheet —
                    // sibling Compose UI (top nav, URL bar, tab chips) draws
                    // blank white on the first-open case until the WebView
                    // has real content. Keep it out of the hierarchy until
                    // the user navigates somewhere.
                    BrowserWebView(
                        webView = selectedTab.manager.webView,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (selectedTab == null) stringResource(R.string.browser_no_tab_open)
                            else stringResource(R.string.browser_enter_url_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (isAgentBusy) {
                    AgentBrowsingOverlay(
                        accent = accent,
                        // [T-android-browser-release-all-semantics] Takeover
                        // hands the wheel to the user — the page they are
                        // looking at must stay exactly as it is.
                        onTakeover = { tabPool.releaseAllTabsToUser() },
                    )
                }
            }

            // ── Download progress banner ──
            val activeDownload by tabPool.activeDownload.collectAsState()
            activeDownload?.let { dl ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(secondaryBg)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = accent,
                    )
                    Text(
                        dl.filename,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (dl.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { dl.progress },
                            modifier = Modifier.weight(1f).height(3.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.weight(1f).height(3.dp),
                        )
                    }
                }
            }

            // ── Bottom Toolbar ──
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(secondaryBg)
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarIcon(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDesc = stringResource(R.string.browser_nav_back),
                    enabled = canGoBack && !isAgentBusy,
                    onClick = { selectedTab?.manager?.goBack() },
                )
                ToolbarIcon(
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDesc = stringResource(R.string.browser_nav_forward),
                    enabled = canGoForward && !isAgentBusy,
                    onClick = { selectedTab?.manager?.goForward() },
                )
                // [T-android-browser-download-ux] Downloads entry: shows while
                // the session has ANY download records, disappears when the
                // user clears the last one; the badge counts in-flight +
                // unviewed terminal entries and hides at 0 (iOS v2 spec).
                if (downloadEntries.isNotEmpty()) {
                    val badgeCount = tabPool.downloadBadgeCount(downloadEntries)
                    androidx.compose.material3.BadgedBox(
                        badge = {
                            if (badgeCount > 0) {
                                androidx.compose.material3.Badge { Text("$badgeCount") }
                            }
                        },
                    ) {
                        ToolbarIcon(
                            icon = Icons.Default.Download,
                            contentDesc = stringResource(R.string.browser_downloads_title),
                            enabled = true,
                            tint = accent,
                            onClick = { showDownloads = true },
                        )
                    }
                }
                if (isLoading) {
                    ToolbarIcon(
                        icon = Icons.Default.Close,
                        contentDesc = stringResource(R.string.browser_stop),
                        enabled = !isAgentBusy,
                        tint = accent,
                        onClick = { selectedTab?.manager?.stopLoading() },
                    )
                } else {
                    ToolbarIcon(
                        icon = Icons.Default.Refresh,
                        contentDesc = stringResource(R.string.browser_reload),
                        enabled = !isAgentBusy,
                        tint = accent,
                        onClick = { selectedTab?.manager?.reload() },
                    )
                }
            }
        }
    }

    if (showDownloads) {
        BrowserDownloadsSheet(
            tabPool = tabPool,
            onDismiss = { showDownloads = false },
        )
    }

    if (showHistory) {
        BrowserHistorySheet(
            historyStore = BrowserHistoryStore.getInstance(context),
            // [T-android-browser-history-no-tab] C1: with no tabs open,
            // selectedTab is null and the old safe-call silently dropped the
            // navigation. Create a tab first (same path as the "+" button);
            // if the pool refuses (MAX_TABS), just dismiss the sheet.
            onNavigate = { url ->
                scope.launch {
                    val tab = selectedTab ?: tabPool.newTabFromUI()
                    tab?.manager?.loadURL(url)
                    showHistory = false
                }
            },
            onDismiss = { showHistory = false },
        )
    }

    if (showSettings) {
        BrowserSettingsSheet(
            tabPool = tabPool,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun TabChip(
    title: String,
    isSelected: Boolean,
    showClose: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val bg = if (isSelected) accent.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHighest
    val borderColor = if (isSelected) accent.copy(alpha = 0.4f) else Color.Transparent
    val textColor = if (isSelected) accent else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .background(bg, CircleShape)
            .border(1.dp, borderColor, CircleShape)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = if (showClose) 6.dp else 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showClose) {
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(16.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.browser_close_tab),
                    modifier = Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToolbarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDesc: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color? = null,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            icon,
            contentDescription = contentDesc,
            modifier = Modifier.size(22.dp),
            tint = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                else tint ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Globe icon with a spinning arc border when loading. */
@Composable
private fun BrowserAddressBarIcon(isLoading: Boolean, accent: Color) {
    // [T-android-decorative-anim-perf] Clock only while loading (nothing to
    // spin otherwise), and the rotation moves to the layer lambda below —
    // `.rotate(angle)` read the value in composition, recomposing the icon
    // every frame on top of the spinner's own animation.
    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Language,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (isLoading) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isLoading) {
            // [T-android-decorative-anim-perf] DecorativeSpinner, not Material's:
            // rotating Material's indicator from our clock still left its OWN
            // InfiniteTransition asking for every frame, which is the whole
            // reason the chat spinners were swapped. A page load during agent
            // browsing is long-lived, so this one matters.
            DecorativeSpinner(
                color = accent,
                modifier = Modifier.size(22.dp),
                strokeWidth = 1.5.dp,
                periodMs = 1000,
            )
        }
    }
}

/** Breathing-light overlay shown when the agent is controlling the browser. */
@Composable
private fun AgentBrowsingOverlay(accent: Color, onTakeover: () -> Unit) {
    // [T-android-decorative-anim-perf] The breathing dot used to rebuild
    // `accent.copy(alpha = …)` in composition every frame; now the alpha is
    // a layer property read from the shared tick and the background colour
    // is constant.
    val tick = rememberDecorativeTick()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        alpha = 0.3f + 0.7f * decorativePingPong(decorativePhase(tick.value, 3000))
                    }
                    .background(accent, CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.browser_minis_browsing),
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(14.dp)
                    .background(Color.White.copy(alpha = 0.3f)),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.browser_takeover),
                color = accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onTakeover),
            )
        }
    }
}

/** Normalize URL input: search terms → Google search, bare domains → https:// prefix. */
private fun normalizeURLInput(input: String): String {
    val trimmed = input.trim()
    if (trimmed.contains("://")) return trimmed
    if (trimmed.contains(' ') || !trimmed.contains('.')) {
        return "https://www.google.com/search?q=${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
    }
    return "https://$trimmed"
}
