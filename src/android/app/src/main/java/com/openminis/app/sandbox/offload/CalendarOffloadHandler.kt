package com.openminis.app.sandbox.offload

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * android-calendar — list, create, update, delete events; query free/busy.
 *
 * Mirrors apple-calendar (NativeOffloads/CalendarOffload.m) — same flag
 * names and subcommands so prompts and the agent loop are platform-
 * agnostic. Aliases are preserved for backwards compatibility with older
 * prompts (--max ↔ --limit, --description ↔ --notes).
 *
 * Usage:
 *   android-calendar list [--today | --days N | --start S --end E]
 *                          [--limit N] [--calendar NAME]
 *   android-calendar create --title T --start S [--end E]
 *                          [--notes N] [--location L] [--all-day]
 *                          [--alarm <minutes>] [--calendar NAME | --calendar-id ID]
 *   android-calendar update --id <event_id> [--title ...] [--start ...] [--end ...]
 *                          [--all-day] [--notes ...] [--location ...] [--alarm <minutes>]
 *                          [--calendar NAME | --calendar-id ID]
 *   android-calendar delete --id <event_id>
 *   android-calendar freebusy --start <ISO> --end <ISO>
 *   android-calendar calendars                List writable calendars
 */
class CalendarOffloadHandler(private val context: Context) : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1), booleanFlags = setOf("today", "all-day"))
        // [T-offload-defaults-batch-android] Help only on explicit
        // --help/-h; no subcommand defaults to `list` (resolveDateRange
        // already defaults to the last-24h..now window with no flags).
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, HELP)
        }
        // T330: tri-state agent gate before any work.
        OffloadGate.enforce("calendar", "android-calendar", args, request)?.let { return it }

        return try {
            when (val sub = args.positional.firstOrNull() ?: "list") {
                "list" -> doList(args)
                "create" -> doCreate(args)
                "update" -> doUpdate(args)
                "delete" -> doDelete(args)
                "freebusy" -> doFreebusy(args)
                "calendars" -> doListCalendars(args)
                else -> NativeOffloadResult(2, "android-calendar: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            NativeOffloadResult(
                1,
                OffloadOutput.formatBody(JSONObject().put("error", "internal").put("message", e.message ?: "unknown").toString(), args) + "\n",
            )
        }
    }

    private fun hasRead() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED
    private fun hasWrite() = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

    /**
     * Request one or more calendar permissions if not yet granted, blocking
     * the tool invocation until the user responds (or permanently denies).
     * Mirrors the location handler's pattern: system dialog → settings gate.
     * Returns null if permission ends up granted; otherwise a ready-to-return
     * error [NativeOffloadResult] describing why we gave up.
     */
    private fun ensurePermission(
        permissions: List<String>,
        satisfied: () -> Boolean,
        humanLabel: String,
        settingsId: String,
        args: OffloadArgs,
    ): NativeOffloadResult? {
        if (satisfied()) return null
        AppLogger.warning(TAG, "$humanLabel not granted — routing through permission flow")
        val result = runBlocking {
            var r = OffloadPermissionManager.requestAndroidPermission(permissions)
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
                OffloadPermissionManager.pollForPermissionGrant(satisfied)
            ) {
                AppLogger.info(TAG, "Calendar $humanLabel permission granted during post-DENY poll")
                r = OffloadPermissionManager.AndroidPermissionResult.GRANTED
            }
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
                r = OffloadPermissionManager.requestSettingsGate(
                    OffloadPermissionManager.SettingsGateRequest(
                        id = settingsId,
                        title = "Calendar permission needed",
                        message = "Minis needs $humanLabel permission to $humanLabel your calendar. Open Settings to allow it.",
                        settingsAction = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        requiresPackageUri = true,
                        positiveLabel = "Open Settings",
                    ),
                    check = { satisfied() },
                )
            }
            r
        }
        return when (result) {
            OffloadPermissionManager.AndroidPermissionResult.GRANTED -> null
            OffloadPermissionManager.AndroidPermissionResult.DENIED -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject()
                        .put("error", "permission_denied")
                        .put("message", "The user declined the $humanLabel permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
            OffloadPermissionManager.AndroidPermissionResult.TIMEOUT -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject()
                        .put("error", "timeout")
                        .put("message", "Timed out waiting for the user to grant the $humanLabel permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
        }
    }

    private fun doList(args: OffloadArgs): NativeOffloadResult {
        ensurePermission(
            permissions = listOf(Manifest.permission.READ_CALENDAR),
            satisfied = { hasRead() },
            humanLabel = "read",
            settingsId = Manifest.permission.READ_CALENDAR,
            args = args,
        )?.let { return it }

        val (startMs, endMs) = resolveDateRange(args)
            ?: return NativeOffloadResult(
                2,
                "android-calendar list: invalid or missing date range. Use --today, --days N, or --start/--end (ISO 8601 or YYYY-MM-DD).\n",
            )
        // --limit is the apple-calendar name; --max kept as alias.
        val limit = args.getInt("limit") ?: args.getInt("max") ?: DEFAULT_LIMIT
        val calFilter = args.get("calendar")

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        // [T-android-calendar-update-all-day] One day of slack each side: an
        // all-day event's DTSTART is a UTC midnight, which is up to a day away
        // from its local date. The rows are filtered on their LOCAL start below.
        val selArgs = arrayOf((startMs - DAY_MS).toString(), (endMs + DAY_MS).toString())
        val sort = "${CalendarContract.Events.DTSTART} ASC"

        val cursor = try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, projection, selection, selArgs, sort,
            )
        } catch (e: SecurityException) {
            return NativeOffloadResult(77, providerBlockedJson("READ_CALENDAR", e, args))
        } catch (e: Throwable) {
            return NativeOffloadResult(1, providerErrorJson(e, args))
        } ?: return NativeOffloadResult(1, providerErrorJson(null, args))

        // Two-pass: gather all matches (so we can compute total_available
        // before truncating to --limit, matching apple-calendar's
        // _warning + total_available shape).
        val rows = ArrayList<Pair<Long, JSONObject>>()
        cursor.use {
            while (it.moveToNext()) {
                val calName = it.getString(8) ?: ""
                if (calFilter != null && !calName.contains(calFilter, ignoreCase = true)) continue
                // [T-android-calendar-update-all-day] All-day rows are stored as
                // UTC midnights; report and filter them as the local days they
                // are (00:00:00 .. 23:59:59), as create/update and iOS do.
                // Formatting the raw value printed 08:00 -> 08:00 next day in
                // UTC+8, and west of UTC filed the event under the day before.
                val allDay = it.getInt(6) == 1
                val rawEnd = if (it.isNull(3)) null else it.getLong(3)
                val (s0, e0) = if (allDay) allDayLocalRange(it.getLong(2), rawEnd)
                    else it.getLong(2) to (rawEnd ?: 0L)
                if (s0 < startMs || s0 > endMs) continue
                rows += s0 to
                    JSONObject()
                        .put("id", it.getLong(0))
                        .put("title", it.getString(1) ?: "")
                        .put("start", formatIso(s0))
                        .put("start_ms", s0)
                        .put("end", formatIso(e0))
                        .put("end_ms", e0)
                        .put("location", it.getString(4) ?: "")
                        .put("notes", it.getString(5) ?: "")
                        .put("description", it.getString(5) ?: "")
                        // iOS emits `is_all_day`; `all_day` kept as an alias so
                        // an existing script reading the old key still works.
                        .put("is_all_day", it.getInt(6) == 1)
                        .put("all_day", it.getInt(6) == 1)
                        .put("calendar_id", it.getLong(7))
                        .put("calendar", calName)
            }
        }
        // Sorted by LOCAL start (the SQL order is by raw DTSTART).
        val matches = JSONArray()
        rows.sortedBy { it.first }.forEach { matches.put(it.second) }

        val total = matches.length()
        val truncated = total > limit
        val events = JSONArray()
        for (i in 0 until minOf(total, limit)) events.put(matches.getJSONObject(i))

        if (events.length() == 0) {
            // Check whether the user actually has any writable calendar account —
            // if not, the "no events" answer is misleading. Report the real reason.
            val accounts = listWritableCalendars()
            if (accounts.length() == 0) {
                return NativeOffloadResult(
                    0,
                    OffloadOutput.formatBody(
                        JSONObject()
                            .put("events", JSONArray())
                            .put("warning", "calendar_no_account")
                            .put("message", "No calendar account is configured on this device. Ask the user to add a Google or Exchange account in system Settings → Accounts.")
                            .toString(),
                        args,
                    ) + "\n",
                )
            }
        }
        val data = JSONObject()
            .put("events", events)
            .put("range", JSONObject().put("start", formatIso(startMs)).put("end", formatIso(endMs)))
            .put("count", events.length())
        if (truncated) {
            data.put("_warning", "Results truncated by --limit. Returned $limit of $total total records. Use a larger --limit to retrieve more data.")
            data.put("total_available", total)
        }
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }

    private fun doCreate(args: OffloadArgs): NativeOffloadResult {
        ensurePermission(
            permissions = listOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR),
            satisfied = { hasWrite() },
            humanLabel = "write",
            settingsId = Manifest.permission.WRITE_CALENDAR,
            args = args,
        )?.let { return it }

        val title = args.get("title")
            ?: return NativeOffloadResult(2, "android-calendar create: --title is required\n")
        val startStr = args.get("start")
            ?: return NativeOffloadResult(2, "android-calendar create: --start is required\n")
        val startMs = parseDate(startStr)
            ?: return NativeOffloadResult(2, "android-calendar: invalid --start '$startStr'\n")
        // Reject a malformed --end outright rather than silently substituting
        // start+1h: the caller asked for a specific range, and quietly filing
        // an hour-long event is the same class of silent-wrong-answer as the
        // lenient parsing above.
        val endArg = args.get("end")
        val endParsed = endArg?.let { parseDate(it) }
        if (endArg != null && endParsed == null) {
            return NativeOffloadResult(2, "android-calendar: invalid --end '$endArg'\n")
        }
        // [T-android-calendar-all-day] All-day when asked explicitly, or when
        // BOTH bounds are bare dates — a date with no time cannot describe a
        // timed event, so treating "2026-12-01" as a zero-length event at
        // midnight was never what the caller meant. Matches iOS
        // CalendarOffload.m's rule exactly, including that a bare start with a
        // timed end stays a timed event.
        val allDay = args.hasFlag("all-day") ||
            (isDateOnly(startStr) && isDateOnly(endArg))
        val startMsFinal: Long
        val endMs: Long
        if (allDay) {
            val (s0, e0) = allDayBounds(startMs, endParsed)
            startMsFinal = s0
            endMs = e0
        } else {
            startMsFinal = startMs
            endMs = endParsed ?: (startMs + 60 * 60 * 1000L)
        }
        // Notes — apple-calendar uses --notes; --description kept as alias.
        val notes = args.get("notes") ?: args.get("description")
        val alarmMinutes = args.getInt("alarm")

        val target = resolveTarget(args, "create")
        if (target is Target.Failed) return target.result
        val calendarId = (target as? Target.Found)?.cal?.id
            ?: pickWritableCalendar()
            ?: return missingCalendarError(args)

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMsFinal)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            notes?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            args.get("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            if (allDay) {
                putAllDay(this, startMsFinal, endMs)
            }
        }

        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri == null) {
                NativeOffloadResult(1, OffloadOutput.formatBody(JSONObject().put("error", "insert_failed").put("message", "ContentResolver.insert returned null").toString(), args) + "\n")
            } else {
                val eventId = uri.lastPathSegment?.toLongOrNull()
                if (eventId != null && alarmMinutes != null && alarmMinutes >= 0) {
                    insertReminder(eventId, alarmMinutes)
                }
                AppLogger.info(TAG, "create: id=$eventId title='$title' alarm=$alarmMinutes")
                val data = JSONObject()
                    .put("id", eventId ?: -1L)
                    .put("title", title)
                    .put("start", formatIso(startMsFinal))
                    .put("end", formatIso(endMs))
                    .putCalendar((target as? Target.Found)?.cal ?: calendarById(calendarId))
                    // Same key `list` emits, so both shapes agree — iOS puts
                    // is_all_day on both too.
                    .put("is_all_day", allDay)
                if (notes != null) data.put("notes", notes)
                if (alarmMinutes != null && alarmMinutes >= 0) data.put("alarm_minutes_before", alarmMinutes)
                NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
            }
        } catch (e: SecurityException) {
            NativeOffloadResult(77, providerBlockedJson("WRITE_CALENDAR", e, args))
        } catch (e: Throwable) {
            NativeOffloadResult(1, providerErrorJson(e, args))
        }
    }

    // ── update ──────────────────────────────────────────────────────────

    private fun doUpdate(args: OffloadArgs): NativeOffloadResult {
        ensurePermission(
            permissions = listOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR),
            satisfied = { hasWrite() },
            humanLabel = "write",
            settingsId = Manifest.permission.WRITE_CALENDAR,
            args = args,
        )?.let { return it }

        val id = args.getLong("id")
            ?: return NativeOffloadResult(2, "android-calendar update: --id <event_id> is required\n")

        // Build a sparse ContentValues with only the fields the caller supplied.
        val values = ContentValues()
        args.get("title")?.let { values.put(CalendarContract.Events.TITLE, it) }
        val startStr = args.get("start")
        val endStr = args.get("end")
        val newStart = startStr?.let { s ->
            parseDate(s) ?: return NativeOffloadResult(2, "android-calendar update: invalid --start '$s'\n")
        }
        val newEnd = endStr?.let { e ->
            parseDate(e) ?: return NativeOffloadResult(2, "android-calendar update: invalid --end '$e'\n")
        }
        // [T-android-calendar-update-all-day] iOS parity (fe7a01bc5,
        // 86e91526e). Same rule create applies, over the arguments actually
        // PASSED: all-day when asked (--all-day) or when every date given is a
        // bare YYYY-MM-DD — inferring from an absent argument would turn a
        // timed event all-day on an unrelated --title edit. Back to timed is
        // --start/--end with a real time; no --not-all-day flag.
        val sawDate = startStr != null || endStr != null
        val allBare = sawDate &&
            (startStr == null || isDateOnly(startStr)) && (endStr == null || isDateOnly(endStr))
        val makeAllDay = args.hasFlag("all-day") || allBare
        val carriesTime = (startStr != null && !isDateOnly(startStr)) ||
            (endStr != null && !isDateOnly(endStr))
        if (makeAllDay || sawDate) {
            val cur = readTiming(id)
                ?: return NativeOffloadResult(1, "android-calendar update: no event with --id $id\n")
            val switching = makeAllDay != cur.allDay
            if (switching && cur.rrule != null) {
                return NativeOffloadResult(
                    2,
                    "android-calendar update: event $id repeats; switching a recurring event between all-day and timed is not supported\n",
                )
            }
            // The event's CURRENT range in local wall-clock terms, so a lone
            // --all-day (no dates) snaps the bounds the event already has, and a
            // lone --start keeps the end it has.
            val (curStart, curEnd) = if (cur.allDay) {
                allDayLocalRange(cur.dtStart, cur.dtEnd)
            } else {
                cur.dtStart to (cur.dtEnd ?: (cur.dtStart + HOUR_MS))
            }
            when {
                makeAllDay -> {
                    val (s0, e0) = allDayBounds(newStart ?: curStart, newEnd ?: curEnd)
                    putAllDay(values, s0, e0)
                }
                cur.allDay && carriesTime -> {
                    // Back to timed. The stored all-day range is UTC midnights,
                    // so every field moves together: flag off, local zone back,
                    // real times — a DTSTART write alone under ALL_DAY=1 would
                    // be snapped back to a midnight by the provider.
                    val s0 = newStart ?: curStart
                    var e0 = newEnd ?: curEnd
                    if (e0 <= s0) e0 = s0 + HOUR_MS
                    values.put(CalendarContract.Events.ALL_DAY, 0)
                    values.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    values.put(CalendarContract.Events.DTSTART, s0)
                    values.put(CalendarContract.Events.DTEND, e0)
                }
                else -> {
                    newStart?.let { values.put(CalendarContract.Events.DTSTART, it) }
                    newEnd?.let { values.put(CalendarContract.Events.DTEND, it) }
                }
            }
        }
        args.get("location")?.let { values.put(CalendarContract.Events.EVENT_LOCATION, it) }
        (args.get("notes") ?: args.get("description"))?.let {
            values.put(CalendarContract.Events.DESCRIPTION, it)
        }
        // --calendar / --calendar-id: move the event. No flag = stay put; a
        // target that does not resolve refuses the whole update instead of
        // moving the event to the default calendar
        // ([T-android-calendar-exact-target]).
        when (val target = resolveTarget(args, "update")) {
            is Target.Failed -> return target.result
            is Target.Found -> values.put(CalendarContract.Events.CALENDAR_ID, target.cal.id)
            Target.NotRequested -> {}
        }
        val alarmMinutes = args.getInt("alarm")

        if (values.size() == 0 && alarmMinutes == null) {
            return NativeOffloadResult(2, "android-calendar update: nothing to update — supply at least one field flag\n")
        }

        return try {
            val updated = if (values.size() > 0) {
                val uri = CalendarContract.Events.CONTENT_URI.buildUpon()
                    .appendPath(id.toString()).build()
                context.contentResolver.update(uri, values, null, null)
            } else 0
            if (alarmMinutes != null && alarmMinutes >= 0) {
                replaceReminder(id, alarmMinutes)
            }
            AppLogger.info(TAG, "update: id=$id rows=$updated alarm=$alarmMinutes")
            val data = JSONObject()
                .put("id", id)
                .put("updated", updated > 0 || alarmMinutes != null)
                .put("rows", updated)
                .putCalendar(calendarOfEvent(id))
            // [T-android-calendar-update-all-day] Echo the event as stored, so
            // the caller sees whether it is all-day and the range it landed on
            // (all-day as local 00:00:00 - 23:59:59, the shape create and iOS
            // report).
            readTiming(id)?.let { t ->
                val (s0, e0) = if (t.allDay) allDayLocalRange(t.dtStart, t.dtEnd)
                    else t.dtStart to (t.dtEnd ?: t.dtStart)
                data.put("start", formatIso(s0)).put("end", formatIso(e0)).put("is_all_day", t.allDay)
            }
            if (alarmMinutes != null && alarmMinutes >= 0) data.put("alarm_minutes_before", alarmMinutes)
            NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
        } catch (e: SecurityException) {
            NativeOffloadResult(77, providerBlockedJson("WRITE_CALENDAR", e, args))
        } catch (e: Throwable) {
            NativeOffloadResult(1, providerErrorJson(e, args))
        }
    }

    // ── delete ──────────────────────────────────────────────────────────

    private fun doDelete(args: OffloadArgs): NativeOffloadResult {
        ensurePermission(
            permissions = listOf(Manifest.permission.WRITE_CALENDAR),
            satisfied = { hasWrite() },
            humanLabel = "write",
            settingsId = Manifest.permission.WRITE_CALENDAR,
            args = args,
        )?.let { return it }

        val id = args.getLong("id")
            ?: return NativeOffloadResult(2, "android-calendar delete: --id <event_id> is required\n")

        return try {
            val uri = CalendarContract.Events.CONTENT_URI.buildUpon()
                .appendPath(id.toString()).build()
            // Captured before the row is gone.
            val cal = calendarOfEvent(id)
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows <= 0) {
                val body = JSONObject().put("error", "not_found")
                    .put("id", id)
                    .put("message", "No event with id=$id (already deleted, or not visible to this app).")
                    .toString()
                return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
            }
            AppLogger.info(TAG, "delete: id=$id rows=$rows")
            val data = JSONObject().put("id", id).put("deleted", true).put("rows", rows).putCalendar(cal)
            NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
        } catch (e: SecurityException) {
            NativeOffloadResult(77, providerBlockedJson("WRITE_CALENDAR", e, args))
        } catch (e: Throwable) {
            NativeOffloadResult(1, providerErrorJson(e, args))
        }
    }

    // ── freebusy ────────────────────────────────────────────────────────

    private fun doFreebusy(args: OffloadArgs): NativeOffloadResult {
        ensurePermission(
            permissions = listOf(Manifest.permission.READ_CALENDAR),
            satisfied = { hasRead() },
            humanLabel = "read",
            settingsId = Manifest.permission.READ_CALENDAR,
            args = args,
        )?.let { return it }

        val startStr = args.get("start") ?: return NativeOffloadResult(2, "android-calendar freebusy: --start is required\n")
        val endStr = args.get("end") ?: return NativeOffloadResult(2, "android-calendar freebusy: --end is required\n")
        val startMs = parseDate(startStr) ?: return NativeOffloadResult(2, "android-calendar: invalid --start '$startStr'\n")
        val endMs = parseDate(endStr) ?: return NativeOffloadResult(2, "android-calendar: invalid --end '$endStr'\n")
        if (endMs <= startMs) {
            return NativeOffloadResult(2, "android-calendar freebusy: --end must be after --start\n")
        }

        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
        )
        val selection = "${CalendarContract.Events.DTSTART} < ? AND ${CalendarContract.Events.DTEND} > ?"
        val selArgs = arrayOf(endMs.toString(), startMs.toString())
        val sort = "${CalendarContract.Events.DTSTART} ASC"

        val cursor = try {
            context.contentResolver.query(CalendarContract.Events.CONTENT_URI, projection, selection, selArgs, sort)
        } catch (e: SecurityException) {
            return NativeOffloadResult(77, providerBlockedJson("READ_CALENDAR", e, args))
        } catch (e: Throwable) {
            return NativeOffloadResult(1, providerErrorJson(e, args))
        } ?: return NativeOffloadResult(1, providerErrorJson(null, args))

        val busy = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                val isAllDay = it.getInt(3) == 1
                if (isAllDay) continue // skip all-day per apple-calendar
                busy.put(
                    JSONObject()
                        .put("title", it.getString(0) ?: "")
                        .put("start", formatIso(it.getLong(1)))
                        .put("end", formatIso(it.getLong(2))),
                )
            }
        }

        // Compute free slots in [startMs, endMs] from the busy list.
        // The busy list is already sorted by start; we still cap each busy
        // segment to the query window so an event that straddles the window
        // doesn't poison the cursor walk.
        val free = JSONArray()
        var cursorMs = startMs
        for (i in 0 until busy.length()) {
            val b = busy.getJSONObject(i)
            val bStart = parseDate(b.getString("start")) ?: continue
            val bEnd = parseDate(b.getString("end")) ?: continue
            val segStart = maxOf(bStart, startMs)
            val segEnd = minOf(bEnd, endMs)
            if (cursorMs < segStart) {
                free.put(JSONObject().put("start", formatIso(cursorMs)).put("end", formatIso(segStart)))
            }
            if (segEnd > cursorMs) cursorMs = segEnd
        }
        if (cursorMs < endMs) {
            free.put(JSONObject().put("start", formatIso(cursorMs)).put("end", formatIso(endMs)))
        }

        AppLogger.info(TAG, "freebusy: busy=${busy.length()} free=${free.length()}")
        val data = JSONObject().put("busy", busy).put("free", free)
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }

    // ── Reminders helpers (calendar alarms) ─────────────────────────────

    /**
     * Insert a single reminder row for [eventId] at [minutesBefore]. Mirrors
     * iOS EKAlarm.alarmWithRelativeOffset(-N * 60). Reminders.METHOD_ALERT
     * is the standard reminder type; ContentResolver.insert returns null on
     * provider failure, which we silently swallow — the event itself is
     * already created/updated.
     */
    private fun insertReminder(eventId: Long, minutesBefore: Int) {
        val values = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutesBefore)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        try {
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "insertReminder failed for event=$eventId: ${e.message}")
        }
    }

    /** Remove existing reminders for [eventId] then insert one at [minutesBefore]. */
    private fun replaceReminder(eventId: Long, minutesBefore: Int) {
        try {
            context.contentResolver.delete(
                CalendarContract.Reminders.CONTENT_URI,
                "${CalendarContract.Reminders.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
            )
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "replaceReminder delete failed for event=$eventId: ${e.message}")
        }
        insertReminder(eventId, minutesBefore)
    }

    private fun doListCalendars(args: OffloadArgs): NativeOffloadResult {
        ensurePermission(
            permissions = listOf(Manifest.permission.READ_CALENDAR),
            satisfied = { hasRead() },
            humanLabel = "read",
            settingsId = Manifest.permission.READ_CALENDAR,
            args = args,
        )?.let { return it }
        val arr = listWritableCalendars()
        return NativeOffloadResult(0, OffloadOutput.formatBody(arr.toString(2), args) + "\n")
    }

    // ── Range / calendar resolution ─────────────────────────────────────

    /**
     * Mirror apple-calendar resolve_date_range: --today (today only, [00:00,
     * +24h)) → --days N (last N days through now, with start at 00:00) →
     * --start/--end (explicit, defaults to last 24h on either side). Returns
     * null only when --start/--end are supplied but unparseable.
     */
    private fun resolveDateRange(args: OffloadArgs): Pair<Long, Long>? {
        val now = System.currentTimeMillis()
        if (args.hasFlag("today")) {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val s = cal.timeInMillis
            return s to (s + 24 * 60 * 60 * 1000L)
        }
        args.getInt("days")?.let { days ->
            val n = if (days <= 0) 7 else days
            val cal = Calendar.getInstance().apply {
                timeInMillis = now - n * 24 * 60 * 60 * 1000L
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis to now
        }
        val startStr = args.get("start")
        val endStr = args.get("end")
        val startMs = if (startStr != null) parseDate(startStr) ?: return null
                      else now - 24 * 60 * 60 * 1000L
        val endMs = if (endStr != null) parseDate(endStr) ?: return null
                    else now
        // EventStore predicates don't tolerate start > end on iOS; mirror that.
        return if (startMs > endMs) endMs to startMs else startMs to endMs
    }

    /**
     * [T-android-calendar-exact-target] The calendar a write targets, from
     * --calendar-id or --calendar. Port of iOS 8200eeb81 (issue #282).
     *
     * --calendar used to match by substring ("Work" could land in "Workout")
     * and, on a miss, silently fell back to the auto-picked calendar while
     * still reporting success - for `update` that meant MOVING the event to
     * the default calendar because of a typo. Now a name is an exact,
     * case-insensitive match against the writable calendars; a miss, or two
     * calendars with the same name, fails with every candidate listed; and
     * --calendar-id must name a writable calendar. The `list --calendar`
     * filter keeps substring matching - "show things in calendars containing
     * Work" is a legitimate query, as on iOS.
     */
    private sealed class Target {
        object NotRequested : Target()
        class Found(val cal: CalRef) : Target()
        class Failed(val result: NativeOffloadResult) : Target()
    }

    private fun resolveTarget(args: OffloadArgs, verb: String): Target {
        val idArg = args.get("calendar-id")
        val name = args.get("calendar")
        if (idArg == null && name == null) return Target.NotRequested
        val cals = writableCalendarRefs()
        val pick = if (idArg != null) {
            val id = idArg.trim().toLongOrNull()
                ?: return Target.Failed(targetError(args, verb, "invalid --calendar-id '$idArg' (expected a number from `android-calendar calendars`).", cals))
            pickCalendarById(id, cals)
        } else {
            pickCalendarByName(name!!, cals)
        }
        return when (pick) {
            is CalPick.Found -> Target.Found(pick.cal)
            is CalPick.Missing -> Target.Failed(targetError(args, verb, pick.message, cals))
            is CalPick.Ambiguous -> Target.Failed(targetError(args, verb, pick.message, pick.matches))
        }
    }

    private fun targetError(args: OffloadArgs, verb: String, message: String, candidates: List<CalRef>): NativeOffloadResult {
        val list = JSONArray()
        for (c in candidates) list.put(c.toJson())
        val body = JSONObject()
            .put("error", "invalid_args")
            .put("message", "android-calendar $verb: $message Nothing was written.")
            .put("candidates", list)
        AppLogger.warning(TAG, "$verb: calendar target refused — $message")
        return NativeOffloadResult(2, OffloadOutput.formatBody(body.toString(2), args) + "\n")
    }

    private fun writableCalendarRefs(): List<CalRef> {
        val arr = listWritableCalendars()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CalRef(o.optLong("id"), o.optString("display_name"), o.optString("account_name"))
        }
    }

    /** The calendar an event is in, for the write echo; null when unreadable. */
    /** What update needs to know about an event's current timing. */
    private data class EventTiming(val dtStart: Long, val dtEnd: Long?, val allDay: Boolean, val rrule: String?)

    private fun readTiming(eventId: Long): EventTiming? = try {
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI.buildUpon().appendPath(eventId.toString()).build(),
            arrayOf(
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.RRULE,
            ),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) null
            else EventTiming(
                dtStart = c.getLong(0),
                dtEnd = if (c.isNull(1)) null else c.getLong(1),
                allDay = c.getInt(2) == 1,
                rrule = c.getString(3)?.takeIf { it.isNotBlank() },
            )
        }
    } catch (e: SecurityException) {
        null
    }

    private fun calendarOfEvent(eventId: Long): CalRef? = try {
        val eventCalId = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI.buildUpon().appendPath(eventId.toString()).build(),
            arrayOf(CalendarContract.Events.CALENDAR_ID), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        eventCalId?.let { calendarById(it) }
    } catch (e: Throwable) {
        null
    }

    private fun calendarById(id: Long): CalRef? = try {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI.buildUpon().appendPath(id.toString()).build(),
            arrayOf(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) CalRef(id, c.getString(0) ?: "", c.getString(1) ?: "") else null }
    } catch (e: Throwable) {
        null
    }

    /**
     * [T-android-calendar-exact-target] Every write names WHERE it landed,
     * not only a title: calendar titles are not unique (the reporter of iOS
     * issue #282 had two calendars called "Work"). Mirrors iOS 381e59dc7's
     * calendar_id / calendar_source.
     */
    private fun JSONObject.putCalendar(cal: CalRef?): JSONObject {
        if (cal == null) return this
        put("calendar_id", cal.id)
        put("calendar", cal.name)
        put("calendar_source", cal.account)
        return this
    }

    private fun missingCalendarError(args: OffloadArgs): NativeOffloadResult =
        NativeOffloadResult(
            1,
            OffloadOutput.formatBody(
                JSONObject()
                    .put("error", "calendar_no_account")
                    .put("message", "No writable calendar found. Ask the user to add a Google, Exchange, or local calendar account, or pass --calendar <name> / --calendar-id <id>. Run `android-calendar calendars` to list available calendars.")
                    .toString(),
                args,
            ) + "\n",
        )

    /** ISO 8601 with offset (e.g. 2026-04-26T13:51:00+08:00). Matches
     *  apple-calendar noff_format_date so cross-platform consumers see
     *  identical strings. */
    private fun formatIso(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        return sdf.format(java.util.Date(ms))
    }

    // ── Calendar account selection ───────────────────────────────────────

    /**
     * Enumerate writable calendars (CALENDAR_ACCESS_LEVEL >= 500 = OWNER/CONTRIBUTOR).
     * Used by `create` for pick-best and by `calendars` subcommand for debugging.
     */
    private fun listWritableCalendars(): JSONArray {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val arr = JSONArray()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    arr.put(
                        JSONObject()
                            .put("id", c.getLong(0))
                            .put("display_name", c.getString(1) ?: "")
                            .put("account_name", c.getString(2) ?: "")
                            .put("account_type", c.getString(3) ?: "")
                            .put("access_level", c.getInt(4))
                            .put("is_primary", c.getInt(5) == 1),
                    )
                }
            }
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "listWritableCalendars: ${e.message}")
        }
        return arr
    }

    /**
     * Pick the best calendar for new events:
     *   1. IS_PRIMARY=1 on a com.google account (avoids Samsung-Calendar-only inserts on One UI)
     *   2. Any com.google account
     *   3. Any IS_PRIMARY=1
     *   4. First writable calendar
     */
    private fun pickWritableCalendar(): Long? {
        val cals = listWritableCalendars()
        if (cals.length() == 0) return null
        fun score(obj: JSONObject): Int {
            var s = 0
            if (obj.optString("account_type") == "com.google") s += 100
            if (obj.optBoolean("is_primary")) s += 50
            // Prefer owner over contributor
            if (obj.optInt("access_level") >= CalendarContract.Calendars.CAL_ACCESS_OWNER) s += 10
            return s
        }
        var bestIdx = 0
        var bestScore = Int.MIN_VALUE
        for (i in 0 until cals.length()) {
            val s = score(cals.getJSONObject(i))
            if (s > bestScore) { bestScore = s; bestIdx = i }
        }
        return cals.getJSONObject(bestIdx).optLong("id", -1L).takeIf { it > 0 }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun providerBlockedJson(perm: String, e: SecurityException, args: OffloadArgs): String {
        val body = JSONObject().put("error", "provider_blocked")
            .put("message", "$perm is granted but the calendar provider refused the query (likely an OEM privacy layer like MIUI 'Other Permissions'). Ask the user to open app info and allow calendar access there. Underlying: ${e.message}")
            .toString()
        return OffloadOutput.formatBody(body, args) + "\n"
    }

    private fun providerErrorJson(e: Throwable?, args: OffloadArgs): String {
        val body = JSONObject().put("error", "provider_error")
            .put("message", e?.message ?: "Calendar provider failed or missing on this device.")
            .toString()
        return OffloadOutput.formatBody(body, args) + "\n"
    }


    companion object {
        /**
         * [T-android-calendar-all-day] True for a bare `YYYY-MM-DD` with no time.
         *
         * Positional rather than formatter-based, mirroring iOS
         * `noff_is_date_only_string`: a formatter is lenient about trailing text
         * and would also accept the date PREFIX of a datetime, which is exactly
         * the case this has to reject.
         */
        internal fun isDateOnly(s: String?): Boolean {
            val t = s?.trim() ?: return false
            if (t.length != 10) return false
            for (i in 0 until 10) {
                val c = t[i]
                if (i == 4 || i == 7) { if (c != '-') return false } else if (!c.isDigit()) return false
            }
            return parseDate(t) != null
        }

        /**
         * [T-android-calendar-all-day] Snap a range onto whole local days:
         * 00:00:00.000 of the start day through 23:59:59 of the end day.
         *
         * Mirrors iOS `noff_all_day_bounds`, including the two edge rules: an end
         * before the start collapses to a single day, and the last second is
         * computed as (next midnight - 1s) via calendar arithmetic so a 23h or 25h
         * DST day still lands on its own last second rather than an hour adrift.
         */
        private const val HOUR_MS = 60 * 60 * 1000L
        private const val DAY_MS = 24 * HOUR_MS

        /**
         * [T-android-calendar-update-all-day] Put an all-day range into
         * [values] in the form the calendar provider requires: ALL_DAY=1,
         * EVENT_TIMEZONE=UTC, DTSTART the UTC midnight of the first day and
         * DTEND the UTC midnight after the last day (exclusive). [localStart]
         * / [localEnd] are the local day bounds from [allDayBounds].
         *
         * Storing LOCAL midnight (what create did) is not a midnight in UTC:
         * in Asia/Shanghai it is 16:00Z of the previous day, which the
         * provider then snaps down to that previous day's midnight — the event
         * landed a day early. See allDayStoredRange.
         */
        internal fun putAllDay(values: ContentValues, localStart: Long, localEnd: Long) {
            val (s, e) = allDayStoredRange(localStart, localEnd)
            values.put(CalendarContract.Events.ALL_DAY, 1)
            values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            values.put(CalendarContract.Events.DTSTART, s)
            values.put(CalendarContract.Events.DTEND, e)
        }

        /**
         * The provider's all-day storage for the LOCAL days containing
         * [localStartMs] .. [localEndMs]: UTC midnight of the first local date,
         * and UTC midnight of the day after the last (exclusive end). An end
         * before the start collapses to one day, as in [allDayBounds].
         */
        internal fun allDayStoredRange(
            localStartMs: Long,
            localEndMs: Long?,
            local: TimeZone = TimeZone.getDefault(),
        ): Pair<Long, Long> {
            fun utcMidnightOfLocalDay(ms: Long): Long {
                val l = Calendar.getInstance(local).apply { timeInMillis = ms }
                return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    clear()
                    set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH))
                }.timeInMillis
            }
            val s = utcMidnightOfLocalDay(localStartMs)
            var e = utcMidnightOfLocalDay(localEndMs ?: localStartMs)
            if (e < s) e = s
            // UTC has no DST, so a day is always 24 h here.
            return s to (e + DAY_MS)
        }

        /**
         * Inverse of [allDayStoredRange]: a stored all-day range back to local
         * day bounds (00:00:00 of the first day .. 23:59:59 of the last), the
         * shape create's output and iOS report.
         */
        internal fun allDayLocalRange(
            dtStartUtc: Long,
            dtEndUtc: Long?,
            local: TimeZone = TimeZone.getDefault(),
        ): Pair<Long, Long> {
            fun localMidnightOfUtcDay(ms: Long): Long {
                val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
                return Calendar.getInstance(local).apply {
                    clear()
                    set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH))
                }.timeInMillis
            }
            val start = localMidnightOfUtcDay(dtStartUtc)
            val lastDayUtc = maxOf(dtStartUtc, (dtEndUtc ?: (dtStartUtc + DAY_MS)) - DAY_MS)
            val nextMidnight = Calendar.getInstance(local).apply {
                timeInMillis = localMidnightOfUtcDay(lastDayUtc)
                add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
            return start to (nextMidnight - 1000L)
        }

        internal fun allDayBounds(startMs: Long, endMs: Long?): Pair<Long, Long> {
            fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
                timeInMillis = ms
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val startDay = startOfDay(startMs)
            var endDay = startOfDay(endMs ?: startMs)
            if (endDay < startDay) endDay = startDay
            val nextMidnight = Calendar.getInstance().apply {
                timeInMillis = endDay
                add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
            return startDay to (nextMidnight - 1000L)
        }

        internal fun parseDate(s: String): Long? {
            // Relative offsets (apple-calendar parity): "-7d", "-2h", "+30m".
            val rel = Regex("""^([+-]?)(\d+)([dhm])$""").matchEntire(s.trim())
            if (rel != null) {
                val sign = if (rel.groupValues[1] == "-") -1L else 1L
                val n = rel.groupValues[2].toLong()
                val unitMs = when (rel.groupValues[3]) {
                    "d" -> 24 * 60 * 60 * 1000L
                    "h" -> 60 * 60 * 1000L
                    "m" -> 60 * 1000L
                    else -> return null
                }
                return System.currentTimeMillis() + sign * n * unitMs
            }
            val formats = listOf(
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm",
                "yyyy-MM-dd",
            )
            // [T-android-calendar-strict-date] isLenient=false + a full-length parse.
            //
            // SimpleDateFormat is lenient by default and rolls out-of-range fields
            // over instead of rejecting them: "2026-13-45" parsed as 2027-02-14 and
            // "…T99:99:99" as four days later at 04:40. Both then created a real
            // event, silently, at a time the caller never asked for — worse than an
            // error, because nothing surfaces. Lenient parsing also stops at the
            // first unparseable character, so a trailing-garbage string matched on
            // its prefix; ParsePosition makes the whole string load-bearing.
            // iOS gets this from NSISO8601DateFormatter, which is strict already.
            val trimmed = s.trim()
            for (f in formats) {
                try {
                    val sdf = SimpleDateFormat(f, Locale.US).apply {
                        isLenient = false
                        timeZone = if (f.endsWith("'Z'")) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
                    }
                    val pos = java.text.ParsePosition(0)
                    val parsed = sdf.parse(trimmed, pos)
                    if (parsed != null && pos.index == trimmed.length) return parsed.time
                } catch (_: Throwable) {}
            }
            return null
        }
        /** A writable calendar as the target matcher sees it. */
        internal data class CalRef(val id: Long, val name: String, val account: String) {
            fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("account", account)
        }

        internal sealed class CalPick {
            data class Found(val cal: CalRef) : CalPick()
            data class Missing(val message: String) : CalPick()
            data class Ambiguous(val message: String, val matches: List<CalRef>) : CalPick()
        }

        /** [T-android-calendar-exact-target] Exact, case-insensitive, trimmed. */
        internal fun pickCalendarByName(name: String, cals: List<CalRef>): CalPick {
            val want = name.trim()
            val matches = cals.filter { it.name.trim().equals(want, ignoreCase = true) }
            return when {
                matches.size == 1 -> CalPick.Found(matches.single())
                matches.isEmpty() -> CalPick.Missing(
                    "no writable calendar is named '$name' (exact match, case-insensitive). " +
                        "Pass one of the candidate names, or --calendar-id <id>.",
                )
                else -> CalPick.Ambiguous(
                    "${matches.size} writable calendars are named '$name'. " +
                        "Pass --calendar-id <id> with one of the candidates.",
                    matches,
                )
            }
        }

        internal fun pickCalendarById(id: Long, cals: List<CalRef>): CalPick =
            cals.firstOrNull { it.id == id }?.let { CalPick.Found(it) }
                ?: CalPick.Missing(
                    "no writable calendar has id $id. Pass one of the candidate ids, or --calendar <name>.",
                )

        private const val TAG = "CalendarOffload"
        private const val DEFAULT_LIMIT = 50
        private const val HELP = """android-calendar — list, create, update, delete events; query free/busy
                                    (mirrors apple-calendar)

Usage:
  android-calendar                  Same as `list` (default subcommand)
  android-calendar list [--today | --days N | --start S --end E]
                        [--limit N] [--calendar NAME]
  android-calendar create --title T --start S [--end E]
                         [--notes N] [--location L] [--all-day]
                         [--alarm <minutes>] [--calendar NAME | --calendar-id ID]
  android-calendar update --id <event_id> [--title ...] [--start ...] [--end ...]
                         [--all-day] [--notes ...] [--location ...] [--alarm <minutes>]
                         [--calendar NAME | --calendar-id ID]
      --all-day, or bare dates (YYYY-MM-DD), make it an all-day event; pass
      --start/--end with a time to make an all-day event timed again.
  android-calendar delete --id <event_id>
  android-calendar freebusy --start <ISO> --end <ISO>
  android-calendar calendars             List writable calendars (debugging)

On create/update, --calendar is an EXACT name (case-insensitive) and
--calendar-id must be a writable calendar's id; anything else fails with
the candidates listed and writes nothing. On list, --calendar filters by
substring. Writes echo calendar_id, calendar and calendar_source.

Dates accept ISO 8601 (YYYY-MM-DDThh:mm[:ss][Z|±HH:MM]) or relative
shorthand (-7d, -2h, +30m). YYYY-MM-DD is treated as midnight local.

Aliases for backwards compatibility:
  --max ↔ --limit
  --description ↔ --notes

Errors return JSON: {"error":"...","message":"..."}.
"""
    }
}
