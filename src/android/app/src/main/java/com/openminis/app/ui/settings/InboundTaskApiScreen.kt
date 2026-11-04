package com.openminis.app.ui.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.inbound.InboundTaskApi
import com.openminis.app.inbound.InboundTaskProtocol
import com.openminis.app.inbound.InboundTaskSettings
import kotlinx.coroutines.delay

/**
 * [inbound-task-api] Settings → Network → Inbound Task API.
 * The token is shown here so the user can copy it. It is marked sensitive
 * on the clipboard (API 33+) and is not written to the log.
 */
@Composable
fun InboundTaskApiScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { InboundTaskSettings(context) }
    var enabled by remember { mutableStateOf(settings.isEnabled()) }
    var token by remember { mutableStateOf(settings.token()) }
    var copied by remember { mutableStateOf(false) }
    var confirmRegenerate by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.inbound_api_title),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.inbound_api_section),
            footer = stringResource(R.string.inbound_api_enable_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.inbound_api_enable),
                subtitle = if (enabled) {
                    stringResource(R.string.inbound_api_listening, InboundTaskProtocol.PORT)
                } else {
                    stringResource(R.string.inbound_api_off)
                },
                checked = enabled,
                onCheckedChange = { turnOn ->
                    if (turnOn) {
                        settings.setEnabled(true)
                        val ok = InboundTaskApi.applyEnabled(context)
                        if (!ok) {
                            settings.setEnabled(false)
                            InboundTaskApi.applyEnabled(context)
                            enabled = false
                        } else {
                            enabled = true
                        }
                    } else {
                        settings.setEnabled(false)
                        InboundTaskApi.applyEnabled(context)
                        enabled = false
                    }
                },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.inbound_api_token),
            footer = stringResource(R.string.inbound_api_token_footer),
        ) {
            SelectionContainer {
                Text(
                    text = token,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            SettingsRow(
                title = stringResource(if (copied) R.string.inbound_api_copied else R.string.inbound_api_copy),
                onClick = {
                    copySensitive(context, token)
                    copied = true
                },
            )
            SettingsRow(
                title = stringResource(R.string.inbound_api_regenerate),
                onClick = { confirmRegenerate = true },
                showDivider = false,
            )
        }

        SettingsSection(
            footer = stringResource(R.string.inbound_api_bind_footer),
        ) {
            SettingsValueRow(
                title = stringResource(R.string.inbound_api_port_label),
                value = InboundTaskProtocol.PORT.toString(),
                showDivider = false,
            )
        }
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text(stringResource(R.string.inbound_api_regenerate_title)) },
            text = { Text(stringResource(R.string.inbound_api_regenerate_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRegenerate = false
                        token = settings.regenerateToken()
                        copied = false
                    },
                ) {
                    Text(stringResource(R.string.inbound_api_regenerate_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegenerate = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private fun copySensitive(context: Context, token: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Minis inbound token", token)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
}
