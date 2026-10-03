package com.openminis.app.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.openminis.app.AppBuildInfo
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.ui.components.openExternalUrl

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val tileBlue = Color(0xFF007AFF)

    SettingsScaffold(title = stringResource(R.string.about_title), onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val iconPainter = remember(context) {
                // painterResource() can't load adaptive-icon XML drawables (mipmap-anydpi-v26),
                // so fetch the launcher icon as a Drawable and convert to a Bitmap.
                val drawable = context.packageManager.getApplicationIcon(context.packageName)
                BitmapPainter(drawable.toBitmap(width = 192, height = 192).asImageBitmap())
            }
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = iconPainter,
                    contentDescription = null,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape),
                )
            }
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(
                    R.string.about_version_format,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE.toString(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // [T-android-about-build-date] Selectable so it can be copied
            // into a bug report.
            val buildDate = remember(context) { AppBuildInfo.buildDate(context) }
            SelectionContainer {
                Text(
                    stringResource(R.string.about_built_format, buildDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Text(
                stringResource(R.string.about_minis_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }

        SettingsSection(header = stringResource(R.string.about_links)) {
            SettingsRow(
                icon = Icons.Outlined.Code,
                iconColor = tileBlue,
                title = stringResource(R.string.about_github_repository),
                // Settings → ABOUT siblings (Privacy Policy / Submit GitHub
                // Issues) all use openExternalUrl directly. The
                // LocalInAppBrowserLauncher ambient defaults to a no-op when
                // no InAppBrowserHost is in the tree above this screen — and
                // nothing wraps Settings, so the row used to be a dead tap.
                onClick = { openExternalUrl(context, "https://github.com/OpenMinis/OpenMinis") },
                trailing = { ExternalLinkIcon() },
                showDivider = false,
            )
        }

        // T122: surface the existing UpdateChecker entry on the About screen.
        // The composable was already implemented but never wired anywhere, so
        // users had no way to trigger a check.
        CheckUpdateSection()

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ExternalLinkIcon() {
    Text(
        "↗",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Back-compat shim for legacy call sites that still call [openUrl] with a
 * Context. Dispatches as a system Intent — the in-app preview path is the new
 * `LocalInAppBrowserLauncher` ambient; prefer that at the call site.
 */
internal fun openUrl(context: android.content.Context, url: String) {
    openExternalUrl(context, url)
}
