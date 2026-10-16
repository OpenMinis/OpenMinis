package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ModelRefreshResult

@Composable
internal fun ProviderInstanceRow(
    instance: ProviderInstance,
    modelCount: Int,
    apiKey: String?,
    isConfigured: Boolean,
    needsReauth: Boolean = false,
    pinned: Boolean,
    onTogglePinned: () -> Unit,
    onClick: () -> Unit,
    syncResult: ModelRefreshResult? = null,
    isLoading: Boolean = false,
) {
    val isActive = isConfigured && instance.isEnabled

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = when {
                        needsReauth -> Color(0xFFFF3B30)
                        isLoading -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
                        isActive -> Color(0xFF34C759)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                    },
                    shape = CircleShape,
                ),
        )

        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = instance.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.provider_list_api_key), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("·", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                Text(
                    text = when {
                        isLoading -> stringResource(R.string.loading)
                        !apiKey.isNullOrBlank() -> maskKey(apiKey)
                        instance.allowsEmptyAPIKey -> stringResource(R.string.provider_no_key_required)
                        else -> stringResource(R.string.provider_list_no_api_key)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (syncResult != null && syncResult != ModelRefreshResult.SUCCESS_API) {
                val mark = when (syncResult) {
                    ModelRefreshResult.NO_KEY -> stringResource(R.string.provider_sync_mark_nokey)
                    ModelRefreshResult.PRESERVED -> stringResource(R.string.provider_sync_mark_unchanged)
                    else -> stringResource(R.string.provider_sync_mark_failed)
                }
                Text(mark, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp))
            }
            if (modelCount > 0) {
                Text(stringResource(R.string.provider_list_models_count, modelCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
        if (!instance.isEnabled) {
            Text(stringResource(R.string.provider_list_disabled),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 2.dp))
            Spacer(Modifier.width(8.dp))
        }
        IconButton(onClick = onTogglePinned) {
            Icon(
                imageVector = if (pinned) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = stringResource(
                    if (pinned) R.string.provider_unset_favorite else R.string.provider_set_favorite,
                ),
                tint = if (pinned) Color(0xFFFFCC00)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp))
    }
}

private fun maskKey(key: String): String =
    if (key.length <= 8) "****" else key.take(6) + "..." + key.takeLast(4)
