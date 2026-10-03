package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.scheduled.ScheduledTaskDetailModel
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.scheduled.ScheduledTaskMarker
import com.openminis.app.ui.theme.ChatColors
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

/** [T-android-scheduled-task-card] Amber — a timer, not an agent. */
internal val ScheduledAccent = Color(0xFFF59E0B)

/**
 * [T-android-scheduled-task-card / -next-fire / -cancel-from-card]
 * A fired scheduled task, rendered as a card instead of a user bubble.
 *
 * Three things the plain bubble could not say:
 *  - that a TASK wrote this, not the user;
 *  - WHEN it will run next, which until now was invisible after creation —
 *    the user had to go to Settings and read the list to find out;
 *  - that it can be stopped from right here, without going to find it.
 *
 * Cancelling asks first. A scheduled task is something the user deliberately
 * set up, so the cost of an accidental tap is not symmetric with the cost of
 * one extra confirmation.
 */
@Composable
internal fun ScheduledTaskCard(
    marker: ScheduledTaskMarker,
    modifier: Modifier = Modifier,
    // [T-scheduled-task-detail] The chat this card is shown in; the detail
    // page uses it for "this chat" wording and links to other chats.
    hostSessionId: String? = null,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    var confirming by remember { mutableStateOf(false) }
    // Local echo: the store is not observable here, and the card must stop
    // claiming a next run the moment the user cancels it.
    var cancelled by remember(marker.taskId) { mutableStateOf(false) }
    // [T-scheduled-task-detail] Tapping the card opens the task's detail page.
    var showingDetail by remember { mutableStateOf(false) }

    // [T-scheduled-task-detail] A passed next-fire time says nothing: the
    // envelope is a snapshot, and the detail page has the live state.
    // `now` is re-read once, when the next-fire time passes: read only at
    // composition, a card on screen kept "Next: <that time>" after the fire
    // had happened, until something else recomposed it.
    val now by produceState(System.currentTimeMillis(), marker.nextFireAtMs) {
        val next = marker.nextFireAtMs ?: return@produceState
        val wait = next - System.currentTimeMillis()
        if (wait > 0) {
            delay(wait + 1)
            value = System.currentTimeMillis()
        }
    }
    val nextLine: String? = when (
        val sub = ScheduledTaskDetailModel.cardSubtitle(marker, cancelled, now)
    ) {
        ScheduledTaskDetailModel.CardSubtitle.Cancelled -> stringResource(R.string.scheduled_card_cancelled)
        is ScheduledTaskDetailModel.CardSubtitle.Next -> stringResource(
            R.string.scheduled_card_next_fire,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(sub.atMs)),
        )
        // A one-shot task, or one past its end date: say so rather than
        // leaving the row blank, which reads as missing information.
        ScheduledTaskDetailModel.CardSubtitle.LastRun -> stringResource(R.string.scheduled_card_last_run)
        ScheduledTaskDetailModel.CardSubtitle.None -> null
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(ScheduledAccent.copy(alpha = 0.07f))
            .border(0.8.dp, ScheduledAccent.copy(alpha = 0.35f), shape)
            .clickable { showingDetail = true }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(34.dp).clip(CircleShape)
                .background(ScheduledAccent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Schedule,
                contentDescription = null,
                tint = ScheduledAccent,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Chip drawn inline rather than reusing HelperUi's private
                // AgentChip: widening that helper's visibility for one caller
                // would couple the scheduled card to the agent UI's styling.
                Text(
                    stringResource(R.string.scheduled_card_label),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ScheduledAccent,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(ScheduledAccent.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
                Text(
                    marker.label.ifEmpty { marker.prompt.take(40) },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = ChatColors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (nextLine != null) {
                Text(
                    nextLine,
                    fontSize = 11.sp,
                    color = ChatColors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!cancelled) {
            TextButton(onClick = { confirming = true }) {
                Text(
                    stringResource(R.string.scheduled_card_cancel),
                    fontSize = 11.sp,
                    color = ScheduledAccent,
                    maxLines = 1,
                )
            }
        }
    }

    if (showingDetail) {
        ScheduledTaskDetailSheet(
            marker = marker,
            hostSessionId = hostSessionId,
            onDismiss = { showingDetail = false },
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.scheduled_card_cancel_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.scheduled_card_cancel_confirm_body,
                        marker.label.ifEmpty { marker.prompt.take(40) },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // Disable rather than delete: the run history stays
                    // readable, and the task is recoverable from Settings.
                    ScheduledTaskManager(context.applicationContext as android.app.Application)
                        .setEnabled(marker.taskId, false)
                    cancelled = true
                    confirming = false
                }) { Text(stringResource(R.string.scheduled_card_cancel_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.scheduled_card_cancel_confirm_keep))
                }
            },
        )
    }
}
