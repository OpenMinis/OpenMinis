package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import com.openminis.app.R
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Bottom sheet listing all chat sessions except the current one. Tapping
 * a row hands the chosen session id back to the caller. Mirrors iOS
 * MoveToSessionSheet (Views/Chat/AIChatView.swift:3413) — same flow
 * (current input + attachments stash via ChatViewModelStore.pendingTransfer
 * → navigate → target session drains the cache).
 *
 * T167: rows now show a category icon + relative timestamp matching
 * SessionListScreen's main list, and the LazyColumn is wrapped in a
 * 16dp rounded surface card so the picker reads as a discrete control
 * inside the sheet rather than naked list items.
 *
 * [T-android-moveto-new-chat] A "New Chat" row sits above the list, as on
 * iOS (AIChatView.swift:5500). Without it the picker could only ever hand
 * content to a session that already existed, so content arriving by share
 * — which lands in whatever session happened to be open — had no route
 * into a fresh conversation; the user had to create one first and share
 * again. The row emits a `__new__<uuid>` draft id through the SAME
 * [onSelect] callback as a real row, which is the id shape the chat route
 * already understands (AppNavigation's own New Chat action builds one the
 * same way), so nothing downstream needs to special-case it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveToSessionSheet(
    currentSessionId: String,
    chatRepository: ChatRepository,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var sessions by remember { mutableStateOf<List<ChatSessionEntity>>(emptyList()) }

    LaunchedEffect(Unit) {
        sessions = withContext(Dispatchers.IO) {
            chatRepository.dao.listSessions().filter { it.id != currentSessionId }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.move_to_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            // [T-android-moveto-new-chat] Deliberately OUTSIDE the
            // `sessions.isEmpty()` branch below: "no other sessions" is
            // precisely when the user most needs a new one, and the old
            // layout showed only a dead-end message there.
            NewChatRow(
                onClick = { onSelect("__new__${java.util.UUID.randomUUID()}") },
            )
            if (sessions.isEmpty()) {
                Text(
                    stringResource(R.string.move_to_sheet_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        // [T-android-moveto-hover-clip] `clip` BEFORE the
                        // background, so the rounded shape bounds the rows too.
                        //
                        // A `background(shape = …)` only paints a rounded rect;
                        // it does not constrain what children draw. Each row's
                        // `clickable` indication is a full-width square
                        // rectangle, so hovering the first or last row painted
                        // over the container's corners and the highlight looked
                        // detached from the card (reported from a GEM-W09
                        // screenshot with a mouse attached — hover makes it
                        // obvious, but a touch ripple has the same overhang).
                        //
                        // Clipping at the container rather than rounding each
                        // row is what keeps the MIDDLE rows square-edged: per-row
                        // rounding would carve notches between adjacent rows.
                        .clip(RoundedCornerShape(16.dp))
                        .background(color = MaterialTheme.colorScheme.surface),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        MoveToPickerRow(
                            session = session,
                            onClick = { onSelect(session.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * [T-android-moveto-new-chat] The "New Chat" entry, above the session list.
 *
 * Geometry is copied from [MoveToPickerRow] — same 40dp tinted circle, same
 * 16/12dp padding, same 15sp semibold title — so the two read as one list
 * rather than a button bolted on top. It carries no second line because
 * there is no timestamp to show; the row is correspondingly shorter, which
 * also helps it read as the distinct action it is.
 *
 * Wrapped in its own 16dp rounded card, matching the card the session list
 * below sits in (iOS separates them as a plain row above a Section; the
 * card is the Android equivalent of that visual break).
 */
@Composable
private fun NewChatRow(onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            // [T-android-moveto-hover-clip] Same ordering as the session list:
            // clip first, so this row's own click indication stays inside its
            // rounded shape instead of squaring off the corners.
            .clip(RoundedCornerShape(16.dp))
            .background(color = MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(color = accent.copy(alpha = 0.18f), shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = stringResource(R.string.new_chat),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
}

/**
 * Single row in the Move-to picker. Visual parity with SessionRow in
 * SessionListScreen.kt (44dp tinted-circle category icon + title +
 * relative timestamp), trimmed of the active-session spinning ring and
 * the second "last message" line so the row stays compact inside a
 * bottom sheet.
 */
@Composable
private fun MoveToPickerRow(
    session: ChatSessionEntity,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val style = remember(session.category) { categoryStyle(session.category) }
    val timeText = remember(session.updatedAt, context) { relativeDate(context, session.updatedAt) }
    val untitled = stringResource(R.string.move_to_sheet_untitled)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(color = style.color.copy(alpha = 0.18f), shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = style.icon,
                contentDescription = null,
                tint = style.color,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = session.title ?: untitled,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = timeText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
        }
    }
}

// ─── Local copies of SessionListScreen helpers ──────────────────────────────
//
// `categoryStyle` and `relativeDate` are file-private inside
// SessionListScreen.kt (and small enough that extracting a shared module
// would be more disruption than it's worth here). Mirror the same colour
// + icon table so the picker visually matches the main list.

private data class CategoryStyle(val icon: ImageVector, val color: Color)

private fun categoryStyle(category: String?): CategoryStyle {
    return when (category?.lowercase()) {
        "code"         -> CategoryStyle(Icons.Outlined.Code, Color(0xFFF09A37))
        "writing"      -> CategoryStyle(Icons.Outlined.Description, Color(0xFF3478F6))
        "research"     -> CategoryStyle(Icons.Outlined.Language, Color(0xFF30B0C7))
        "analysis"     -> CategoryStyle(Icons.Outlined.BarChart, Color(0xFF5856D6))
        "creative"     -> CategoryStyle(Icons.Outlined.Brush, Color(0xFFFF2D55))
        "chat"         -> CategoryStyle(Icons.Outlined.Forum, Color(0xFF34C759))
        "math"         -> CategoryStyle(Icons.Outlined.Calculate, Color(0xFF9B59B6))
        "translation"  -> CategoryStyle(Icons.Outlined.Translate, Color(0xFF00BCD4))
        "health"       -> CategoryStyle(Icons.Outlined.Favorite, Color(0xFFFF3B30))
        "finance"      -> CategoryStyle(Icons.Outlined.Payments, Color(0xFF00C7BE))
        "travel"       -> CategoryStyle(Icons.Outlined.Map, Color(0xFFF09A37))
        "education"    -> CategoryStyle(Icons.Outlined.Book, Color(0xFF3478F6))
        "design"       -> CategoryStyle(Icons.Outlined.Palette, Color(0xFFFF2D55))
        "productivity" -> CategoryStyle(Icons.Outlined.CalendarMonth, Color(0xFFFFCC00))
        "support"      -> CategoryStyle(Icons.Outlined.Settings, Color(0xFF8B6914))
        "other"        -> CategoryStyle(Icons.Outlined.GridView, Color(0xFF8E8E93))
        else           -> CategoryStyle(Icons.Outlined.Forum, Color(0xFF8E8E93))
    }
}

private fun relativeDate(context: Context, timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val seconds = TimeUnit.MILLISECONDS.toSeconds(diff)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    if (seconds < 60) return context.getString(R.string.time_just_now)
    if (minutes < 60) return context.getString(R.string.time_minutes_ago, minutes.toInt())
    if (hours < 24) return context.getString(R.string.time_hours_ago, hours.toInt())
    val dateCal = Calendar.getInstance().apply { time = Date(timestamp) }
    val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (dateCal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
        dateCal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)
    ) return context.getString(R.string.time_yesterday)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    if (days < 7) {
        // T172: device-locale weekday name via java.text.DateFormatSymbols.
        val dayNames = java.text.DateFormatSymbols(java.util.Locale.getDefault()).weekdays
        return dayNames[dateCal.get(Calendar.DAY_OF_WEEK) - 1]
    }
    val month = dateCal.get(Calendar.MONTH) + 1
    val day = dateCal.get(Calendar.DAY_OF_MONTH)
    return "$month/$day"
}
