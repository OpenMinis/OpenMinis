//
//  CalendarOffload.m
//  MinisApp
//
//  Native offload handler for `apple-calendar`.
//  Subcommands: list, reminders, freebusy, calendars, create, remind
//

#import <Foundation/Foundation.h>
#import <EventKit/EventKit.h>
#import <UIKit/UIKit.h>
#import <CoreLocation/CoreLocation.h>
#import "NativeOffloadUtils.h"
#include "kernel/native_offload.h"
#include <unistd.h>

static NSString *const TOOL_NAME = @"apple-calendar";
static const NSInteger DEFAULT_LIMIT = 100;

@class EKReminder;
/// [T-reminders-location-alarm] Defined next to the location-alarm helpers
/// below; forward-declared so the reminder LIST command (which appears earlier
/// in this file) can report an existing geofence too.
static NSDictionary *reminder_location_dict(EKReminder *reminder);
static void reminder_put_due(NSMutableDictionary *d, EKReminder *r);   // [T-reminders-date-only]

static NSString *const HELP_TEXT =
    @"apple-calendar - Query and manage iOS calendar events and reminders\n"
     "\n"
     "USAGE:\n"
     "  apple-calendar <command> [options]\n"
     "\n"
     "COMMANDS:\n"
     "  list              List calendar events\n"
     "  reminders         List reminders\n"
     "  freebusy          Show free/busy time slots\n"
     "  calendars         List all calendars\n"
     "  create            Create a new event\n"
     "  update            Update an existing event\n"
     "  delete            Delete an event\n"
     "  remind            Create a new reminder\n"
     "  update-reminder   Update an existing reminder\n"
     "  complete-reminder Complete or uncomplete a reminder\n"
     "  delete-reminder   Delete a reminder\n"
     "\n"
     "COMMON OPTIONS:\n"
     "  --help, -h           Show this help message\n"
     "  --compact            Minimize JSON output\n"
     "  -q, --quiet          Output only data field\n"
     "\n"
     "DATE OPTIONS:\n"
     "  --start <datetime>   Start time (ISO 8601 or relative: -7d, -2h)\n"
     "  --end <datetime>     End time\n"
     "  --days <N>           Last N days\n"
     "  --today              Equivalent to --days 1\n"
     "  --limit <N>          Maximum number of results\n"
     "\n"
     "LIST OPTIONS:\n"
     "  --calendar <name>    Filter by calendar name\n"
     "\n"
     "CREATE OPTIONS:\n"
     "  --title <title>      Event title (required)\n"
     "  --start <datetime>   Start time (required)\n"
     "  --end <datetime>     End time (required)\n"
     "  --all-day            Create an all-day event spanning the --start and --end\n"
     "                       days (times are ignored). Implied when both --start and\n"
     "                       --end are bare dates (YYYY-MM-DD).\n"
     "  --calendar <name>    Calendar to add to\n"
     "  --location <loc>     Event location\n"
     "  --notes <text>       Event notes\n"
     "  --alarm <minutes>    Alarm minutes before event\n"
     "  --recur <freq>       Repeat: daily, weekly, monthly, or yearly.\n"
     "                       Omit for a one-off event (default).\n"
     "  --recur-interval <N> Repeat every N periods (default 1; 2 = every other week)\n"
     "  --recur-days <days>  Weekdays for weekly/monthly/yearly rules, comma-separated\n"
     "                       (e.g. fri or mon,wed,fri). Defaults to the --start weekday.\n"
     "                       Not valid with --recur daily.\n"
     "  --recur-until <date> Repeat until this date (ISO 8601). Omit both this and\n"
     "                       --recur-count to repeat forever.\n"
     "  --recur-count <N>    Stop after N occurrences. Mutually exclusive with\n"
     "                       --recur-until.\n"
     "\n"
     "REMIND OPTIONS:\n"
     "  --title <title>      Reminder title (required)\n"
     "  --due <datetime>     Due date. With a time of day (2026-08-25T09:00) this also\n"
     "                       schedules a system notification, like setting a time in the\n"
     "                       Reminders app. A date-only due (2026-08-25) does not.\n"
     "  --notify <on|off>    Force the notification on or off, overriding that default.\n"
     "                       on requires --due. Independent of geofence alerts below —\n"
     "                       a reminder can have both.\n"
     "  --list <name>        Reminder list name\n"
     "  --priority <0-9>     Priority level\n"
     "  --notes <text>       Reminder notes\n"
     "  --lat <deg>          Geofence latitude (WGS-84). Requires --lng.\n"
     "  --lng <deg>          Geofence longitude (WGS-84). Requires --lat.\n"
     "  --location-name <t>  Label shown for the place (default: \"lat,lng\")\n"
     "  --radius <meters>    Geofence radius (omit to use the system default)\n"
     "  --proximity <p>      enter (default) = alert on arrival; leave = on departure\n"
     "  --recur <freq>       Repeat: daily, weekly, monthly, yearly (requires --due).\n"
     "                       Same --recur-interval / --recur-days / --recur-until /\n"
     "                       --recur-count flags as CREATE.\n"
     "\n"
     "UPDATE OPTIONS:\n"
     "  --id <event_id>      Event ID (required, from list output)\n"
     "  --title <title>      New title\n"
     "  --start <datetime>   New start time\n"
     "  --end <datetime>     New end time\n"
     "  --all-day            Make it an all-day event (times are ignored). Implied\n"
     "                       when the dates given are bare (YYYY-MM-DD). To go back\n"
     "                       to a timed event, pass --start/--end with a time.\n"
     "  --calendar <name>    Move to a different calendar\n"
     "  --location <loc>     New location\n"
     "  --notes <text>       New notes\n"
     "  --alarm <minutes>    New alarm (replaces existing)\n"
     "  --occurrence-date <datetime>  For recurring events: the start of the specific\n"
     "                       occurrence to edit (from list 'occurrence_date'). Without it,\n"
     "                       the series master is edited. Falls back to --start if omitted.\n"
     "  --span <this|future|all>  Scope of change for recurring events (default: this).\n"
     "                       this = only this occurrence; future/all = this and later.\n"
     "\n"
     "DELETE OPTIONS:\n"
     "  --id <event_id>      Event ID (required, from list output)\n"
     "  --occurrence-date <datetime>  For recurring events: the start of the specific\n"
     "                       occurrence to delete (from list 'occurrence_date').\n"
     "  --span <this|future|all>  Scope of deletion for recurring events (default: this).\n"
     "\n"
     "UPDATE-REMINDER OPTIONS:\n"
     "  --id <reminder_id>   Reminder ID (required)\n"
     "  --title <title>      New title\n"
     "  --due <datetime>     New due date. An existing notification moves with it. If\n"
     "                       there was none, one is added when the new due names a time\n"
     "                       of day (same rule as REMIND).\n"
     "  --notify <on|off>    Force the notification on or off. on requires a due date.\n"
     "                       Leaves any geofence alert untouched.\n"
     "  --list <name>        Move to a different list\n"
     "  --priority <0-9>     New priority\n"
     "  --notes <text>       New notes\n"
     "  --lat/--lng/--location-name/--radius/--proximity\n"
     "                       Add or replace the geofence (same as REMIND)\n"
     "  --recur/--recur-*    Add or replace the repeat rule (same as REMIND)\n"
     "  --clear-recur        Make the reminder non-repeating\n"
     "  --clear-location     Remove an existing geofence alarm\n"
     "\n"
     "COMPLETE-REMINDER OPTIONS:\n"
     "  --id <reminder_id>   Reminder ID (required)\n"
     "  --undo               Mark as incomplete instead\n"
     "\n"
     "DELETE-REMINDER OPTIONS:\n"
     "  --id <reminder_id>   Reminder ID (required)\n"
     "\n"
     "EXAMPLES:\n"
     "  apple-calendar list --today\n"
     "  apple-calendar list --days 7 --compact -q\n"
     "  apple-calendar freebusy --start 2026-02-24T09:00 --end 2026-02-24T18:00\n"
     "  apple-calendar create --title \"Meeting\" --start 2026-02-25T14:00 --end 2026-02-25T15:00\n"
     "  apple-calendar create --title \"Offsite\" --start 2026-12-01 --end 2026-12-02 --all-day\n"
     "  # Every Friday at 23:00, forever — one native recurring event, not many copies:\n"
     "  apple-calendar create --title \"Book train ticket home\" \\\n"
     "      --start 2026-02-27T23:00 --end 2026-02-27T23:15 --recur weekly --recur-days fri\n"
     "  # Every other Monday and Wednesday, 10 times:\n"
     "  apple-calendar create --title \"Standup\" --start 2026-03-02T09:30 --end 2026-03-02T09:45 \\\n"
     "      --recur weekly --recur-interval 2 --recur-days mon,wed --recur-count 10\n"
     "  apple-calendar remind --title \"Buy groceries\" --due 2026-02-25T18:00\n"
     "  # Alert on arrival at a place (geofence, no due date needed):\n"
     "  apple-calendar remind --title \"Pick up parcel\" --location-name \"Shenzhen North\" \\\n"
     "      --lat 22.6099 --lng 114.0289 --radius 200 --proximity enter\n"
     "  apple-calendar calendars\n";

// Shared EKEventStore (thread-safe, single instance is recommended)
static EKEventStore *_eventStore = nil;
static EKEventStore *eventStore(void) {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        _eventStore = [[EKEventStore alloc] init];
    });
    return _eventStore;
}

// [T-issue282-exact-list-match] Resolve a WRITE-target calendar/list by EXACT
// case-insensitive title. Substring matching is fine for the list-view
// filters ("show me lists containing Work") but was disastrous for write
// targets: "Work" could land in "Workout" depending on enumeration order,
// and a miss silently fell back to the default list while reporting ok:true.
// On no match this returns nil and fills *errorOut with an invalid_args
// error naming every candidate, so the caller aborts instead of guessing.
static EKCalendar *noff_resolve_calendar_exact(NSString *name,
                                               EKEntityType entityType,
                                               NSString *action,
                                               NSDictionary **errorOut) {
    NSArray<EKCalendar *> *cals = [eventStore() calendarsForEntityType:entityType];
    for (EKCalendar *cal in cals) {
        if ([cal.title localizedCaseInsensitiveCompare:name] == NSOrderedSame) {
            return cal;
        }
    }
    NSMutableArray<NSString *> *titles = [NSMutableArray array];
    for (EKCalendar *cal in cals) {
        if (cal.title.length > 0) [titles addObject:[NSString stringWithFormat:@"'%@'", cal.title]];
    }
    NSString *kind = (entityType == EKEntityTypeReminder) ? @"reminder list" : @"calendar";
    NSString *msg = [NSString stringWithFormat:
        @"No %@ named '%@' (exact match, case-insensitive). Available: %@",
        kind, name,
        titles.count > 0 ? [titles componentsJoinedByString:@", "] : @"(none)"];
    if (errorOut) {
        *errorOut = noff_json_error(TOOL_NAME, action, NOFF_ERR_INVALID_ARGS, msg);
    }
    return nil;
}

// Serial queue to prevent concurrent authorization dialogs
static dispatch_queue_t authQueue(void) {
    static dispatch_queue_t q = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        q = dispatch_queue_create("com.openminis.calendar.auth", DISPATCH_QUEUE_SERIAL);
    });
    return q;
}

// Request calendar access synchronously via semaphore
static BOOL requestCalendarAccess(NSString **outError) {
    __block BOOL granted = NO;
    __block NSError *authError = nil;

    dispatch_sync(authQueue(), ^{
        dispatch_semaphore_t sem = dispatch_semaphore_create(0);

        if (@available(iOS 17.0, *)) {
            [eventStore() requestFullAccessToEventsWithCompletion:^(BOOL g, NSError *e) {
                granted = g;
                authError = e;
                dispatch_semaphore_signal(sem);
            }];
        } else {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
            [eventStore() requestAccessToEntityType:EKEntityTypeEvent completion:^(BOOL g, NSError *e) {
                granted = g;
                authError = e;
                dispatch_semaphore_signal(sem);
            }];
#pragma clang diagnostic pop
        }
        dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 30 * NSEC_PER_SEC));
    });

    if (!granted && outError) {
        NSString *reason = authError.localizedDescription ?: @"Calendar access not granted";
        *outError = [NSString stringWithFormat:
            @"%@. To grant access, open Settings > Privacy & Security > Calendars "
             "and enable Minis.", reason];
    }
    return granted;
}

static BOOL requestRemindersAccess(NSString **outError) {
    __block BOOL granted = NO;
    __block NSError *authError = nil;

    dispatch_sync(authQueue(), ^{
        dispatch_semaphore_t sem = dispatch_semaphore_create(0);

        if (@available(iOS 17.0, *)) {
            [eventStore() requestFullAccessToRemindersWithCompletion:^(BOOL g, NSError *e) {
                granted = g;
                authError = e;
                dispatch_semaphore_signal(sem);
            }];
        } else {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
            [eventStore() requestAccessToEntityType:EKEntityTypeReminder completion:^(BOOL g, NSError *e) {
                granted = g;
                authError = e;
                dispatch_semaphore_signal(sem);
            }];
#pragma clang diagnostic pop
        }
        dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 30 * NSEC_PER_SEC));
    });

    if (!granted && outError) {
        NSString *reason = authError.localizedDescription ?: @"Reminders access not granted";
        *outError = [NSString stringWithFormat:
            @"%@. To grant access, open Settings > Privacy & Security > Reminders "
             "and enable Minis.", reason];
    }
    return granted;
}

// Compute date range from args
static void resolve_date_range(int argc, char **argv, NSDate **start, NSDate **end) {
    if (noff_has_flag(argc, argv, "--today")) {
        NSCalendar *cal = [NSCalendar currentCalendar];
        *start = [cal startOfDayForDate:[NSDate date]];
        *end = [cal dateByAddingUnit:NSCalendarUnitDay value:1 toDate:*start options:0];
        return;
    }

    NSString *daysStr = noff_find_arg(argc, argv, "--days");
    if (daysStr) {
        NSInteger days = [daysStr integerValue];
        if (days <= 0) days = 7;
        NSCalendar *cal = [NSCalendar currentCalendar];
        *start = [cal startOfDayForDate:[NSDate dateWithTimeIntervalSinceNow:-days * 86400]];
        *end = [NSDate date];
        return;
    }

    NSString *startStr = noff_find_arg(argc, argv, "--start");
    NSString *endStr = noff_find_arg(argc, argv, "--end");
    *start = startStr ? noff_parse_date(startStr) : [NSDate dateWithTimeIntervalSinceNow:-86400];
    *end = endStr ? noff_parse_date(endStr) : [NSDate date];
}

// Defined with the other recurrence helpers near cmd_create; declared here
// because event_to_dict (below) reports the rule on listed events too.
static NSDictionary *recurrence_to_dict(EKRecurrenceRule *rule);

static NSDictionary *event_to_dict(EKEvent *event) {
    NSMutableDictionary *d = [NSMutableDictionary dictionary];
    d[@"id"] = event.eventIdentifier ?: @"";
    d[@"title"] = event.title ?: @"";
    // For recurring events, `id` (eventIdentifier) is a series-level ID shared by every
    // occurrence. `occurrence_date` (the start of THIS instance) is what disambiguates a
    // single occurrence so update/delete can target it instead of the series master.
    d[@"is_recurring"] = @(event.hasRecurrenceRules);
    // Mirror the shape `create` returns for a new recurring event, so the rule
    // is readable without a second call. Only present when there is a rule.
    if (event.hasRecurrenceRules && event.recurrenceRules.count > 0) {
        d[@"recurrence"] = recurrence_to_dict(event.recurrenceRules.firstObject);
    }
    d[@"occurrence_date"] = event.startDate ? noff_format_date(event.startDate) : [NSNull null];
    d[@"start"] = event.startDate ? noff_format_date(event.startDate) : [NSNull null];
    d[@"end"] = event.endDate ? noff_format_date(event.endDate) : [NSNull null];
    d[@"location"] = event.location ?: [NSNull null];
    d[@"calendar"] = event.calendar.title ?: @"";
    d[@"is_all_day"] = @(event.isAllDay);
    d[@"notes"] = event.notes ?: [NSNull null];
    d[@"url"] = event.URL.absoluteString ?: [NSNull null];

    if (event.hasAlarms && event.alarms.count > 0) {
        NSMutableArray *alarms = [NSMutableArray array];
        for (EKAlarm *alarm in event.alarms) {
            [alarms addObject:@{@"minutes_before": @(-alarm.relativeOffset / 60.0)}];
        }
        d[@"alarms"] = alarms;
    }

    if (event.attendees.count > 0) {
        NSMutableArray *attendees = [NSMutableArray array];
        for (EKParticipant *p in event.attendees) {
            NSString *status;
            switch (p.participantStatus) {
                case EKParticipantStatusAccepted: status = @"accepted"; break;
                case EKParticipantStatusDeclined: status = @"declined"; break;
                case EKParticipantStatusTentative: status = @"tentative"; break;
                default: status = @"pending"; break;
            }
            [attendees addObject:@{
                @"name": p.name ?: @"",
                @"status": status,
            }];
        }
        d[@"attendees"] = attendees;
    }

    return d;
}

static int cmd_list(int argc, char **argv, int stdout_fd, BOOL compact, BOOL quiet) {
    NSString *authErr = nil;
    if (!requestCalendarAccess(&authErr)) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"list",
                                             NOFF_ERR_AUTHORIZATION_DENIED, authErr);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_AUTH_DENIED;
    }

    NSDate *start, *end;
    resolve_date_range(argc, argv, &start, &end);

    if (!start || !end) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"list",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"Invalid or missing date range. Check --start/--end format (ISO 8601 or relative like -7d).");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }

    // EKEventStore throws NSException if start > end
    if ([start compare:end] == NSOrderedDescending) {
        NSDate *tmp = start;
        start = end;
        end = tmp;
    }

    NSString *limitStr = noff_find_arg(argc, argv, "--limit");
    NSInteger limit = limitStr ? [limitStr integerValue] : DEFAULT_LIMIT;

    NSString *calFilter = noff_find_arg(argc, argv, "--calendar");

    NSPredicate *pred;
    @try {
        pred = [eventStore() predicateForEventsWithStartDate:start
                                                     endDate:end
                                                   calendars:nil];
    } @catch (NSException *e) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"list",
                                             NOFF_ERR_INVALID_ARGS,
                                             [NSString stringWithFormat:@"Failed to create event predicate: %@", e.reason]);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    NSArray<EKEvent *> *events = [eventStore() eventsMatchingPredicate:pred];
    events = [events sortedArrayUsingSelector:@selector(compareStartDateWithEvent:)];

    if (calFilter) {
        events = [events filteredArrayUsingPredicate:
            [NSPredicate predicateWithBlock:^BOOL(EKEvent *e, NSDictionary *b) {
                return [e.calendar.title localizedCaseInsensitiveContainsString:calFilter];
            }]];
    }

    NSInteger totalFiltered = (NSInteger)events.count;
    if (totalFiltered > limit) {
        events = [events subarrayWithRange:NSMakeRange(0, limit)];
    }

    NSMutableArray *eventDicts = [NSMutableArray array];
    for (EKEvent *e in events) {
        [eventDicts addObject:event_to_dict(e)];
    }

    NSMutableDictionary *data = [@{
        @"events": eventDicts,
        @"range": @{
            @"start": noff_format_date(start),
            @"end": noff_format_date(end),
        },
        @"count": @(eventDicts.count),
    } mutableCopy];
    if (totalFiltered > limit) {
        data[@"_warning"] = [NSString stringWithFormat:
            @"Results truncated by --limit. Returned %ld of %ld total records. "
             "Use a larger --limit to retrieve more data.",
            (long)limit, (long)totalFiltered];
        data[@"total_available"] = @(totalFiltered);
    }
    noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, @"list", data), compact, quiet);
    return NOFF_EXIT_SUCCESS;
}

int calendar_cmd_reminders(int argc, char **argv, int stdout_fd, BOOL compact, BOOL quiet) {
    NSString *authErr = nil;
    if (!requestRemindersAccess(&authErr)) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"reminders",
                                             NOFF_ERR_AUTHORIZATION_DENIED, authErr);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_AUTH_DENIED;
    }

    BOOL showCompleted = noff_has_flag(argc, argv, "--completed");
    BOOL showIncomplete = noff_has_flag(argc, argv, "--incomplete");
    NSString *limitStr = noff_find_arg(argc, argv, "--limit");
    NSInteger limit = limitStr ? [limitStr integerValue] : DEFAULT_LIMIT;
    NSString *listName = noff_find_arg(argc, argv, "--list");

    NSArray<EKCalendar *> *calendars = nil;
    if (listName) {
        NSMutableArray *filtered = [NSMutableArray array];
        for (EKCalendar *cal in [eventStore() calendarsForEntityType:EKEntityTypeReminder]) {
            if ([cal.title localizedCaseInsensitiveContainsString:listName]) {
                [filtered addObject:cal];
            }
        }
        calendars = filtered;
    }

    NSPredicate *pred;
    if (showCompleted) {
        pred = [eventStore() predicateForCompletedRemindersWithCompletionDateStarting:nil
                              ending:nil calendars:calendars];
    } else if (showIncomplete) {
        pred = [eventStore() predicateForIncompleteRemindersWithDueDateStarting:nil
                              ending:nil calendars:calendars];
    } else {
        pred = [eventStore() predicateForRemindersInCalendars:calendars];
    }

    __block NSArray<EKReminder *> *reminders = nil;
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    [eventStore() fetchRemindersMatchingPredicate:pred completion:^(NSArray *r) {
        reminders = r;
        dispatch_semaphore_signal(sem);
    }];
    dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC));

    NSInteger totalCount = (NSInteger)(reminders ?: @[]).count;
    NSMutableArray *items = [NSMutableArray array];
    NSInteger count = 0;
    for (EKReminder *r in reminders) {
        if (count >= limit) break;
        NSMutableDictionary *d = [NSMutableDictionary dictionary];
        d[@"id"] = r.calendarItemIdentifier ?: @"";
        d[@"title"] = r.title ?: @"";
        d[@"completed"] = @(r.isCompleted);
        d[@"list"] = r.calendar.title ?: @"";
        d[@"priority"] = @(r.priority);
        d[@"notes"] = r.notes ?: [NSNull null];
        // [T-reminders-date-only] due + is_all_day; [T-reminders-url] url.
        reminder_put_due(d, r);
        d[@"url"] = r.URL.absoluteString ?: [NSNull null];
        // [T-reminders-location-alarm] Present only for geofenced reminders, so
        // existing time-only output is unchanged.
        NSDictionary *rLoc = reminder_location_dict(r);
        if (rLoc) d[@"location"] = rLoc;
        // [T-reminders-recurrence] Likewise present only for repeating
        // reminders, matching how events report their rule.
        if (r.hasRecurrenceRules && r.recurrenceRules.count > 0) {
            d[@"recurrence"] = recurrence_to_dict(r.recurrenceRules.firstObject);
        }
        [items addObject:d];
        count++;
    }

    NSMutableDictionary *data = [@{
        @"reminders": items,
        @"count": @(items.count),
    } mutableCopy];
    if (totalCount > limit) {
        data[@"_warning"] = [NSString stringWithFormat:
            @"Results truncated by --limit. Returned %ld of %ld total records. "
             "Use a larger --limit to retrieve more data.",
            (long)limit, (long)totalCount];
        data[@"total_available"] = @(totalCount);
    }
    noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, @"reminders", data), compact, quiet);
    return NOFF_EXIT_SUCCESS;
}

static int cmd_freebusy(int argc, char **argv, int stdout_fd, BOOL compact, BOOL quiet) {
    NSString *authErr = nil;
    if (!requestCalendarAccess(&authErr)) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"freebusy",
                                             NOFF_ERR_AUTHORIZATION_DENIED, authErr);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_AUTH_DENIED;
    }

    NSDate *start, *end;
    resolve_date_range(argc, argv, &start, &end);

    if (!start || !end) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"freebusy",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"Invalid or missing date range. Check --start/--end format (ISO 8601 or relative like -7d).");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }

    NSPredicate *pred = [eventStore() predicateForEventsWithStartDate:start
                                                              endDate:end
                                                            calendars:nil];
    NSArray<EKEvent *> *events = [eventStore() eventsMatchingPredicate:pred];
    events = [events sortedArrayUsingSelector:@selector(compareStartDateWithEvent:)];

    NSMutableArray *busy = [NSMutableArray array];
    for (EKEvent *e in events) {
        if (e.isAllDay) continue;
        [busy addObject:@{
            @"start": noff_format_date(e.startDate),
            @"end": noff_format_date(e.endDate),
            @"title": e.title ?: @"",
        }];
    }

    // Compute free slots
    NSMutableArray *free = [NSMutableArray array];
    NSDate *cursor = start;
    for (NSDictionary *b in busy) {
        NSDate *busyStart = noff_parse_date(b[@"start"]);
        if ([cursor compare:busyStart] == NSOrderedAscending) {
            [free addObject:@{
                @"start": noff_format_date(cursor),
                @"end": b[@"start"],
            }];
        }
        NSDate *busyEnd = noff_parse_date(b[@"end"]);
        if ([busyEnd compare:cursor] == NSOrderedDescending) {
            cursor = busyEnd;
        }
    }
    if ([cursor compare:end] == NSOrderedAscending) {
        [free addObject:@{
            @"start": noff_format_date(cursor),
            @"end": noff_format_date(end),
        }];
    }

    NSDictionary *data = @{@"busy": busy, @"free": free};
    noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, @"freebusy", data), compact, quiet);
    return NOFF_EXIT_SUCCESS;
}

static int cmd_calendars(int argc, char **argv, int stdout_fd, BOOL compact, BOOL quiet) {
    NSString *authErr = nil;
    if (!requestCalendarAccess(&authErr)) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"calendars",
                                             NOFF_ERR_AUTHORIZATION_DENIED, authErr);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_AUTH_DENIED;
    }

    NSArray<EKCalendar *> *cals = [eventStore() calendarsForEntityType:EKEntityTypeEvent];
    NSMutableArray *items = [NSMutableArray array];
    for (EKCalendar *cal in cals) {
        CGFloat r, g, b, a;
        [[UIColor colorWithCGColor:cal.CGColor] getRed:&r green:&g blue:&b alpha:&a];
        [items addObject:@{
            @"id": cal.calendarIdentifier,
            @"title": cal.title,
            @"source": cal.source.title ?: @"",
            @"type": cal.isSubscribed ? @"subscription" : @"local",
            @"color": [NSString stringWithFormat:@"#%02X%02X%02X",
                        (int)(r * 255), (int)(g * 255), (int)(b * 255)],
            @"is_default": @([cal.calendarIdentifier isEqualToString:
                              [eventStore() defaultCalendarForNewEvents].calendarIdentifier]),
        }];
    }

    NSDictionary *data = @{@"calendars": items, @"count": @(items.count)};
    noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, @"calendars", data), compact, quiet);
    return NOFF_EXIT_SUCCESS;
}

// Map a --recur-days token to an EKWeekday. Accepts the 3-letter abbreviation
// ("mon"), the full name ("monday"), and the 2-letter iCalendar/RFC 5545 code
// ("mo") — an LLM writing this flag may reach for any of the three, and all are
// unambiguous, so accept all rather than make the caller guess ours.
// Returns 0 when the token matches nothing.
static EKWeekday weekday_from_token(NSString *token) {
    NSString *t = [[token stringByTrimmingCharactersInSet:
                    [NSCharacterSet whitespaceCharacterSet]] lowercaseString];
    if (t.length == 0) return 0;
    if ([t hasPrefix:@"su"]) return EKWeekdaySunday;
    if ([t hasPrefix:@"mo"]) return EKWeekdayMonday;
    if ([t hasPrefix:@"tu"]) return EKWeekdayTuesday;
    if ([t hasPrefix:@"we"]) return EKWeekdayWednesday;
    if ([t hasPrefix:@"th"]) return EKWeekdayThursday;
    if ([t hasPrefix:@"fr"]) return EKWeekdayFriday;
    if ([t hasPrefix:@"sa"]) return EKWeekdaySaturday;
    return 0;
}

// Build an EKRecurrenceRule from the --recur* flags, or return nil when --recur
// was not given (the single-event path, unchanged).
//
// On a malformed value this writes a message into *errOut and returns nil, so
// the caller can reject the whole command rather than silently create a
// non-repeating event — a wrong recurrence is worse than a refused one, since
// the user would only discover it weeks later when the event failed to repeat.
//
// [T-reminders-recurrence] Shared by events AND reminders: EKReminder inherits
// recurrenceRules from EKCalendarItem, so both get identical flag names,
// parsing and error text from this one builder. `startDate` is the anchor the
// rule repeats from — --start for events, --due for reminders — and may be nil
// (see the --recur-until check below).
static EKRecurrenceRule *build_recurrence_rule(int argc, char **argv,
                                               NSDate *startDate,
                                               NSString **errOut) {
    NSString *freqStr = noff_find_arg(argc, argv, "--recur");
    if (!freqStr) return nil;   // not a recurring create

    EKRecurrenceFrequency freq;
    NSString *f = [freqStr lowercaseString];
    if ([f isEqualToString:@"daily"] || [f isEqualToString:@"day"]) {
        freq = EKRecurrenceFrequencyDaily;
    } else if ([f isEqualToString:@"weekly"] || [f isEqualToString:@"week"]) {
        freq = EKRecurrenceFrequencyWeekly;
    } else if ([f isEqualToString:@"monthly"] || [f isEqualToString:@"month"]) {
        freq = EKRecurrenceFrequencyMonthly;
    } else if ([f isEqualToString:@"yearly"] || [f isEqualToString:@"year"] ||
               [f isEqualToString:@"annually"]) {
        freq = EKRecurrenceFrequencyYearly;
    } else {
        *errOut = [NSString stringWithFormat:
                   @"Invalid --recur value '%@'. Expected: daily, weekly, monthly, or yearly",
                   freqStr];
        return nil;
    }

    NSInteger interval = 1;
    NSString *intervalStr = noff_find_arg(argc, argv, "--recur-interval");
    if (intervalStr) {
        interval = [intervalStr integerValue];
        if (interval < 1) {
            *errOut = [NSString stringWithFormat:
                       @"Invalid --recur-interval '%@'. Must be a positive integer (1 = every occurrence)",
                       intervalStr];
            return nil;
        }
    }

    // Days of the week. EventKit ignores this for daily rules, so reject it
    // there instead of accepting a flag that would silently do nothing.
    NSArray<EKRecurrenceDayOfWeek *> *days = nil;
    NSString *daysStr = noff_find_arg(argc, argv, "--recur-days");
    if (daysStr) {
        if (freq == EKRecurrenceFrequencyDaily) {
            *errOut = @"--recur-days cannot be combined with --recur daily. "
                      @"Use --recur weekly --recur-days <days> for specific weekdays";
            return nil;
        }
        NSMutableArray *parsed = [NSMutableArray array];
        for (NSString *tok in [daysStr componentsSeparatedByString:@","]) {
            EKWeekday wd = weekday_from_token(tok);
            if (wd == 0) {
                *errOut = [NSString stringWithFormat:
                           @"Invalid --recur-days token '%@'. Expected day names like: mon, tue, wed, thu, fri, sat, sun",
                           [tok stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]]];
                return nil;
            }
            [parsed addObject:[EKRecurrenceDayOfWeek dayOfWeek:wd]];
        }
        if (parsed.count > 0) days = parsed;
    }
    // For a weekly rule with no explicit days, EventKit already repeats on the
    // start date's weekday, so we deliberately leave `days` nil rather than
    // computing it — same result, and it keeps the rule as the user expressed it.

    // End condition. --recur-count and --recur-until are mutually exclusive;
    // rejecting both-at-once beats silently honoring one, since the caller
    // clearly held two different intentions about when this should stop.
    NSString *untilStr = noff_find_arg(argc, argv, "--recur-until");
    NSString *countStr = noff_find_arg(argc, argv, "--recur-count");
    if (untilStr && countStr) {
        *errOut = @"--recur-until and --recur-count are mutually exclusive. Pass only one";
        return nil;
    }

    EKRecurrenceEnd *end = nil;   // nil = repeats forever
    if (untilStr) {
        NSDate *until = noff_parse_date(untilStr);
        if (!until) {
            *errOut = [NSString stringWithFormat:
                       @"Invalid --recur-until date '%@'. Expected ISO 8601 (e.g. 2026-12-31T23:59)",
                       untilStr];
            return nil;
        }
        // [T-reminders-recurrence] `startDate` is the anchor the rule repeats
        // from — --start for events, --due for reminders. It may be nil when a
        // reminder omits --due; skip the ordering check then rather than
        // comparing against nil (which reports NSOrderedDescending and would
        // wave an invalid date through). The caller rejects the missing-anchor
        // case separately, with a message naming the right flag.
        if (startDate && [until compare:startDate] != NSOrderedDescending) {
            *errOut = @"--recur-until must be later than the reminder's due date / the event's --start";
            return nil;
        }
        end = [EKRecurrenceEnd recurrenceEndWithEndDate:until];
    } else if (countStr) {
        NSInteger count = [countStr integerValue];
        if (count < 1) {
            *errOut = [NSString stringWithFormat:
                       @"Invalid --recur-count '%@'. Must be a positive integer",
                       countStr];
            return nil;
        }
        end = [EKRecurrenceEnd recurrenceEndWithOccurrenceCount:count];
    }

    return [[EKRecurrenceRule alloc] initRecurrenceWithFrequency:freq
                                                        interval:interval
                                                   daysOfTheWeek:days
                                                  daysOfTheMonth:nil
                                                 monthsOfTheYear:nil
                                                  weeksOfTheYear:nil
                                                   daysOfTheYear:nil
                                                    setPositions:nil
                                                             end:end];
}

// Human-readable summary of a recurrence rule for the create response, so the
// caller can confirm what was actually stored without a follow-up list call.
static NSDictionary *recurrence_to_dict(EKRecurrenceRule *rule) {
    NSString *freq;
    switch (rule.frequency) {
        case EKRecurrenceFrequencyDaily:   freq = @"daily"; break;
        case EKRecurrenceFrequencyWeekly:  freq = @"weekly"; break;
        case EKRecurrenceFrequencyMonthly: freq = @"monthly"; break;
        case EKRecurrenceFrequencyYearly:  freq = @"yearly"; break;
        default:                           freq = @"unknown"; break;
    }
    NSMutableDictionary *d = [NSMutableDictionary dictionary];
    d[@"frequency"] = freq;
    d[@"interval"] = @(rule.interval);

    if (rule.daysOfTheWeek.count > 0) {
        NSArray *names = @[@"", @"sun", @"mon", @"tue", @"wed", @"thu", @"fri", @"sat"];
        NSMutableArray *days = [NSMutableArray array];
        for (EKRecurrenceDayOfWeek *dw in rule.daysOfTheWeek) {
            NSInteger idx = dw.dayOfTheWeek;
            if (idx >= 1 && idx <= 7) [days addObject:names[idx]];
        }
        d[@"days_of_week"] = days;
    }

    if (rule.recurrenceEnd.endDate) {
        d[@"until"] = noff_format_date(rule.recurrenceEnd.endDate);
    } else if (rule.recurrenceEnd.occurrenceCount > 0) {
        d[@"count"] = @(rule.recurrenceEnd.occurrenceCount);
    } else {
        d[@"never_ends"] = @YES;
    }
    return d;
}

static int cmd_create(int argc, char **argv, int stdout_fd, int stderr_fd, BOOL compact, BOOL quiet) {
    NSString *authErr = nil;
    if (!requestCalendarAccess(&authErr)) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"create",
                                             NOFF_ERR_AUTHORIZATION_DENIED, authErr);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_AUTH_DENIED;
    }

    NSString *title = noff_find_arg(argc, argv, "--title");
    NSString *startStr = noff_find_arg(argc, argv, "--start");
    NSString *endStr = noff_find_arg(argc, argv, "--end");

    if (!title || !startStr || !endStr) {
        noff_emit_help(stderr_fd, HELP_TEXT);
        NSDictionary *err = noff_json_error(TOOL_NAME, @"create",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"Required: --title, --start, --end");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }

    NSDate *startDate = noff_parse_date(startStr);
    NSDate *endDate = noff_parse_date(endStr);
    if (!startDate || !endDate) {
        noff_emit_help(stderr_fd, HELP_TEXT);
        NSDictionary *err = noff_json_error(TOOL_NAME, @"create",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"Invalid date format for --start or --end");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }

    // [T-calendar-all-day] All-day when asked explicitly, or when both bounds
    // are bare dates: a date without a time cannot describe a timed event, so
    // treating "2026-12-01" as midnight-to-midnight-of-the-same-instant (a zero
    // length event at 00:00) was never what the caller meant. Normalised BEFORE
    // the recurrence rule is built so a weekly rule anchors on the day, not on
    // whatever time-of-day the parser defaulted to.
    BOOL allDay = noff_has_flag(argc, argv, "--all-day")
               || (noff_is_date_only_string(startStr) && noff_is_date_only_string(endStr));
    if (allDay) {
        noff_all_day_bounds(startDate, endDate, &startDate, &endDate);
    }

    // Parse the recurrence flags BEFORE building the event, so a malformed rule
    // fails the command outright instead of quietly saving a one-off event that
    // the user believes repeats.
    NSString *recurErr = nil;
    EKRecurrenceRule *recurRule = build_recurrence_rule(argc, argv, startDate, &recurErr);
    if (recurErr) {
        noff_emit_help(stderr_fd, HELP_TEXT);
        NSDictionary *err = noff_json_error(TOOL_NAME, @"create",
                                             NOFF_ERR_INVALID_ARGS, recurErr);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }

    EKEvent *event = [EKEvent eventWithEventStore:eventStore()];
    event.title = title;
    event.startDate = startDate;
    event.endDate = endDate;
    // Set AFTER the dates: EventKit keeps the flag and the range independent,
    // and the range above is already day-aligned when this is YES.
    event.allDay = allDay;
    if (recurRule) {
        [event addRecurrenceRule:recurRule];
    }

    NSString *location = noff_find_arg(argc, argv, "--location");
    if (location) event.location = location;

    NSString *notes = noff_find_arg(argc, argv, "--notes");
    if (notes) event.notes = notes;

    NSString *alarmStr = noff_find_arg(argc, argv, "--alarm");
    if (alarmStr) {
        EKAlarm *alarm = [EKAlarm alarmWithRelativeOffset:-[alarmStr doubleValue] * 60];
        [event addAlarm:alarm];
    }

    // Find calendar by EXACT name if specified — a miss is an error, never a
    // silent fall-through to the default. [T-issue282-exact-list-match]
    NSString *calName = noff_find_arg(argc, argv, "--calendar");
    if (calName) {
        NSDictionary *matchErr = nil;
        EKCalendar *target = noff_resolve_calendar_exact(calName, EKEntityTypeEvent,
                                                         @"create", &matchErr);
        if (!target) {
            noff_emit_json(stdout_fd, matchErr, compact, quiet);
            return NOFF_EXIT_INVALID_ARGS;
        }
        event.calendar = target;
    }
    if (!event.calendar) {
        event.calendar = [eventStore() defaultCalendarForNewEvents];
    }

    NSError *saveErr = nil;
    // A recurring event must be saved with EKSpanFutureEvents: EKSpanThisEvent
    // scopes the write to the single occurrence being edited, which on a brand
    // new series would persist only the first instance and drop the rule.
    EKSpan saveSpan = recurRule ? EKSpanFutureEvents : EKSpanThisEvent;
    BOOL saved = [eventStore() saveEvent:event span:saveSpan commit:YES error:&saveErr];
    if (!saved) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"create",
                                             NOFF_ERR_INTERNAL_ERROR,
                                             saveErr.localizedDescription ?: @"Failed to save event");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_ERROR;
    }

    NSMutableDictionary *data = [@{
        @"id": event.eventIdentifier ?: @"",
        @"title": title,
        @"start": noff_format_date(startDate),
        @"end": noff_format_date(endDate),
        @"calendar": event.calendar.title ?: @"",
        // [T-issue282-return-list-id] calendarIdentifier + source uniquely
        // identify the target even when two calendars share a title (two
        // "Work" calendars, iCloud vs Local, exist in the field) — the title
        // string alone cannot tell the caller where the record landed.
        @"calendar_id": event.calendar.calendarIdentifier ?: @"",
        @"calendar_source": event.calendar.source.title ?: @"",
        @"is_recurring": @(event.hasRecurrenceRules),
        // Read back from the saved event, not the flag we set, so the caller
        // sees what EventKit actually kept (it can reject the flag on some
        // calendar sources). Same key `list` emits, so both shapes agree.
        @"is_all_day": @(event.isAllDay),
    } mutableCopy];
    // Echo the stored rule back (read from the saved event, not the object we
    // built) so the caller can confirm what EventKit actually kept.
    if (event.hasRecurrenceRules && event.recurrenceRules.count > 0) {
        data[@"recurrence"] = recurrence_to_dict(event.recurrenceRules.firstObject);
    }
    noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, @"create", data), compact, quiet);
    return NOFF_EXIT_SUCCESS;
}

// Map --span value to an EKSpan. EventKit has no "entire series" span; `future` from the
// first occurrence (the master) covers the whole series, so we treat all/future as
// EKSpanFutureEvents and this (default) as EKSpanThisEvent.
static EKSpan resolve_event_span(int argc, char **argv) {
    NSString *spanStr = noff_find_arg(argc, argv, "--span");
    if (!spanStr) return EKSpanThisEvent;
    NSString *s = [spanStr lowercaseString];
    if ([s isEqualToString:@"future"] || [s isEqualToString:@"all"]) {
        return EKSpanFutureEvents;
    }
    return EKSpanThisEvent;
}

// Resolve the specific EKEvent to operate on.
//
// `eventWithIdentifier:` always returns the series master (first occurrence) for a recurring
// event, so an --id alone cannot target a later occurrence. When the caller supplies an
// occurrence anchor (--occurrence-date, falling back to --start), we enumerate events around
// that instant and return the occurrence whose eventIdentifier matches `eventId` and whose
// startDate is closest to the anchor. Without an anchor we fall back to the legacy
// eventWithIdentifier: behavior.
static EKEvent *resolve_event(int argc, char **argv, NSString *eventId) {
    NSString *anchorStr = noff_find_arg(argc, argv, "--occurrence-date");
    if (!anchorStr) anchorStr = noff_find_arg(argc, argv, "--start");

    NSDate *anchor = anchorStr ? noff_parse_date(anchorStr) : nil;
    if (!anchor) {
        return [eventStore() eventWithIdentifier:eventId];
    }

    // Search a tight window around the anchor (±1 day) and pick the matching occurrence
    // whose start is nearest the anchor.
    NSDate *winStart = [anchor dateByAddingTimeInterval:-86400];
    NSDate *winEnd = [anchor dateByAddingTimeInterval:86400];
    NSPredicate *pred = [eventStore() predicateForEventsWithStartDate:winStart
                                                              endDate:winEnd
                                                            calendars:nil];
    NSArray<EKEvent *> *events = [eventStore() eventsMatchingPredicate:pred];

    EKEvent *best = nil;
    NSTimeInterval bestDelta = DBL_MAX;
    for (EKEvent *e in events) {
        if (![e.eventIdentifier isEqualToString:eventId]) continue;
        NSTimeInterval delta = fabs([e.startDate timeIntervalSinceDate:anchor]);
        if (delta < bestDelta) {
            bestDelta = delta;
            best = e;
        }
    }

    // If no occurrence matched in the window, fall back to the master rather than failing.
    return best ?: [eventStore() eventWithIdentifier:eventId];
}

static int cmd_update(int argc, char **argv, int stdout_fd, int stderr_fd, BOOL compact, BOOL quiet) {
    NSString *authErr = nil;
    if (!requestCalendarAccess(&authErr)) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"update",
                                             NOFF_ERR_AUTHORIZATION_DENIED, authErr);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_AUTH_DENIED;
    }

    NSString *eventId = noff_find_arg(argc, argv, "--id");
    if (!eventId) {
        noff_emit_help(stderr_fd, HELP_TEXT);
        NSDictionary *err = noff_json_error(TOOL_NAME, @"update",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"Required: --id <event_id>");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }

    EKEvent *event = resolve_event(argc, argv, eventId);
    if (!event) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"update",
                                             NOFF_ERR_NO_DATA,
                                             [NSString stringWithFormat:@"Event not found with id '%@'", eventId]);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_ERROR;
    }

    NSString *title = noff_find_arg(argc, argv, "--title");
    if (title) event.title = title;

    NSString *startStr = noff_find_arg(argc, argv, "--start");
    NSString *endStr = noff_find_arg(argc, argv, "--end");

    // [T-calendar-all-day-to-timed] Clear `allDay` BEFORE writing the dates.
    //
    // Device-measured (iPhone 11, iOS 27): while `allDay` is still YES,
    // EventKit silently DISCARDS writes to startDate/endDate — the save
    // succeeds, reports allDay:NO, and keeps 00:00:00–23:59:59. Passing a
    // timed `--start`/`--end` to an all-day event therefore cleared the flag
    // but left the times untouched, which is not what the caller asked for and
    // not what the command claimed to do.
    //
    // Deliberately asymmetric with the all-day direction below, where the flag
    // is set AFTER the dates (`create` documents that order and it is correct
    // there — the range must already be day-aligned when the flag goes on).
    // Turning all-day OFF has the opposite requirement: the flag must be down
    // before the writes land, or they are dropped.
    //
    // Gated on an actual timed argument so an all-day event whose title is
    // being edited is untouched, and so the all-day branch below still sees the
    // original state.
    BOOL clearsAllDay = (startStr && !noff_is_date_only_string(startStr))
                     || (endStr && !noff_is_date_only_string(endStr));
    if (clearsAllDay && event.isAllDay && !noff_has_flag(argc, argv, "--all-day")) {
        event.allDay = NO;
    }

    // Assign end FIRST when it moves earlier than the current start, and start
    // first otherwise. EventKit rejects (or silently corrects) a state where
    // end < start, and an all-day event sitting at 23:59:59 makes that easy to
    // hit: writing `--start 14:00` alone is fine, but moving a whole event
    // BACKWARDS (start 09:00 while end is still 23:59) then writing end 10:30
    // passes through a valid state either way. The risky order is the one that
    // would leave end < start in between, so pick the other one.
    NSDate *newStart = startStr ? noff_parse_date(startStr) : nil;
    NSDate *newEnd = endStr ? noff_parse_date(endStr) : nil;
    if (newStart && newEnd) {
        // Both supplied: no transient state can be wrong if we write the one
        // that widens the range first.
        if ([newStart compare:event.endDate] == NSOrderedDescending) {
            event.endDate = newEnd;
            event.startDate = newStart;
        } else {
            event.startDate = newStart;
            event.endDate = newEnd;
        }
    } else {
        if (newStart) event.startDate = newStart;
        if (newEnd) event.endDate = newEnd;
    }

    // [T-calendar-all-day] Same rule `create` applies, against the event's
    // CURRENT bounds: all-day when asked explicitly, or when every date the
    // caller supplied is a bare date. A date without a time cannot describe a
    // timed event (see cmd_create).
    //
    // Two differences from create, both forced by update's shape:
    //  - `--start` / `--end` are optional here, so the bare-date inference only
    //    fires on the arguments actually PASSED. Inferring from an absent
    //    argument would silently convert a timed event into an all-day one on
    //    an unrelated `--title` edit.
    //  - Normalisation runs over `event.startDate` / `event.endDate` after the
    //    assignments above, so a lone `--all-day` (no dates) still day-aligns
    //    the bounds the event already has.
    //
    // Converting back to timed is `--start` / `--end` with a real time, exactly
    // as the issue specifies — no `--not-all-day`: passing a time already says
    // it, and a second flag meaning the same thing is one more way to disagree
    // with itself.
    BOOL sawDateArg = (startStr != nil || endStr != nil);
    BOOL allBareDates = sawDateArg
        && (!startStr || noff_is_date_only_string(startStr))
        && (!endStr || noff_is_date_only_string(endStr));
    if (noff_has_flag(argc, argv, "--all-day") || allBareDates) {
        NSDate *s = event.startDate, *e = event.endDate;
        if (s && e) {
            noff_all_day_bounds(s, e, &s, &e);
            event.startDate = s;
            event.endDate = e;
        }
        // Set AFTER the dates, matching create: EventKit keeps the flag and the
        // range independent.
        event.allDay = YES;
    } else if (sawDateArg) {
        // An explicit timed range on an all-day event turns it back into a
        // timed one — otherwise the flag would survive and EventKit would keep
        // rendering it across the whole day regardless of the hours given.
        event.allDay = NO;
    }

    NSString *location = noff_find_arg(argc, argv, "--location");
    if (location) event.location = location;

    NSString *notes = noff_find_arg(argc, argv, "--notes");
    if (notes) event.notes = notes;

    NSString *alarmStr = noff_find_arg(argc, argv, "--alarm");
    if (alarmStr) {
        // Remove existing alarms and set the new one
        for (EKAlarm *a in event.alarms) {
            [event removeAlarm:a];
        }
        EKAlarm *alarm = [EKAlarm alarmWithRelativeOffset:-[alarmStr doubleValue] * 60];
        [event addAlarm:alarm];
    }

    NSString *calName = noff_find_arg(argc, argv, "--calendar");
    if (calName) {
        // [T-issue282-exact-list-match] Exact match or error — the old
        // substring miss silently kept the event's previous calendar.
        NSDictionary *matchErr = nil;
        EKCalendar *target = noff_resolve_calendar_exact(calName, EKEntityTypeEvent,
                                                         @"update", &matchErr);
        if (!target) {
            noff_emit_json(stdout_fd, matchErr, compact, quiet);
            return NOFF_EXIT_INVALID_ARGS;
        }
        event.calendar = target;
    }

    NSError *saveErr = nil;
    BOOL saved = [eventStore() saveEvent:event span:resolve_event_span(argc, argv) commit:YES error:&saveErr];
    if (!saved) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"update",
                                             NOFF_ERR_INTERNAL_ERROR,
                                             saveErr.localizedDescription ?: @"Failed to update event");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_ERROR;
    }

    NSDictionary *data = @{
        @"id": event.eventIdentifier ?: @"",
        @"title": event.title ?: @"",
        @"start": event.startDate ? noff_format_date(event.startDate) : @"",
        @"end": event.endDate ? noff_format_date(event.endDate) : @"",
        @"calendar": event.calendar.title ?: @"",
        @"calendar_id": event.calendar.calendarIdentifier ?: @"",       // [T-issue282-return-list-id]
        @"calendar_source": event.calendar.source.title ?: @"",
        // [T-calendar-all-day] Read back from the SAVED event, not the flag we
        // set — EventKit can reject it on some calendar sources. Same key
        // `create` and `list` emit, so all three shapes agree.
        @"is_all_day": @(event.isAllDay),
    };
    noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, @"update", data), compact, quiet);
    return NOFF_EXIT_SUCCESS;
}

static int cmd_delete(int argc, char **argv, int stdout_fd, BOOL compact, BOOL quiet) {
    NSString *authErr = nil;
    if (!requestCalendarAccess(&authErr)) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"delete",
                                             NOFF_ERR_AUTHORIZATION_DENIED, authErr);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_AUTH_DENIED;
    }

    NSString *eventId = noff_find_arg(argc, argv, "--id");
    if (!eventId) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"delete",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"Required: --id <event_id>");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }

    EKEvent *event = resolve_event(argc, argv, eventId);
    if (!event) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"delete",
                                             NOFF_ERR_NO_DATA,
                                             [NSString stringWithFormat:@"Event not found with id '%@'", eventId]);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_ERROR;
    }

    NSString *eventTitle = event.title ?: @"";
    NSString *calTitle = event.calendar.title ?: @"";
    // [T-issue282-return-list-id] Captured before the delete — the event
    // object is gone by the time the echo dict is built.
    NSString *calId = event.calendar.calendarIdentifier ?: @"";
    NSString *calSource = event.calendar.source.title ?: @"";
    // Capture the resolved occurrence's start before removal so we can verify the right
    // instance is gone (the series master may still exist after a single-occurrence delete).
    NSDate *targetStart = event.startDate;
    EKSpan span = resolve_event_span(argc, argv);

    NSError *removeErr = nil;
    BOOL removed = [eventStore() removeEvent:event span:span commit:YES error:&removeErr];
    if (!removed) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"delete",
                                             NOFF_ERR_INTERNAL_ERROR,
                                             removeErr.localizedDescription ?: @"Failed to delete event");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_ERROR;
    }

    // Verify: re-query the occurrence window and confirm the targeted instance is gone.
    // EventKit can report success while the occurrence persists (e.g. detached-instance
    // edge cases); surfacing a false `deleted:true` is exactly the bug we're fixing.
    if (targetStart) {
        NSDate *winStart = [targetStart dateByAddingTimeInterval:-1];
        NSDate *winEnd = [targetStart dateByAddingTimeInterval:1];
        NSPredicate *vpred = [eventStore() predicateForEventsWithStartDate:winStart
                                                                   endDate:winEnd
                                                                 calendars:nil];
        for (EKEvent *e in [eventStore() eventsMatchingPredicate:vpred]) {
            if ([e.eventIdentifier isEqualToString:eventId] &&
                fabs([e.startDate timeIntervalSinceDate:targetStart]) < 1.0) {
                NSDictionary *err = noff_json_error(TOOL_NAME, @"delete",
                                                     NOFF_ERR_INTERNAL_ERROR,
                                                     @"Delete reported success but the occurrence still exists. "
                                                      "For recurring events pass --occurrence-date and/or --span (this|future|all).");
                noff_emit_json(stdout_fd, err, compact, quiet);
                return NOFF_EXIT_ERROR;
            }
        }
    }

    NSDictionary *data = @{
        @"id": eventId,
        @"title": eventTitle,
        @"calendar": calTitle,
        @"calendar_id": calId,
        @"calendar_source": calSource,
        @"deleted": @YES,
    };
    noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, @"delete", data), compact, quiet);
    return NOFF_EXIT_SUCCESS;
}

// ── Location-based reminder alarms (EKAlarm.structuredLocation) ──────────────
//
// [T-reminders-location-alarm] `apple-reminders create/update` accept
// --location-name/--lat/--lng/--radius/--proximity and attach a geofence alarm
// (EKStructuredLocation + EKAlarmProximityEnter/Leave) — the same API the
// system Reminders app uses for "remind me when I arrive/leave".

/// Authorization observer for the one-shot location permission request below.
/// CLLocationManager delivers the user's answer via a delegate callback, so a
/// synchronous CLI handler needs this tiny bridge to wait for it.
@interface NoffRemindersLocAuthDelegate : NSObject <CLLocationManagerDelegate>
@property (nonatomic, strong) dispatch_semaphore_t semaphore;
@end
@implementation NoffRemindersLocAuthDelegate
- (instancetype)init {
    if ((self = [super init])) _semaphore = dispatch_semaphore_create(0);
    return self;
}
- (void)locationManagerDidChangeAuthorization:(CLLocationManager *)manager {
    // Fires once on delegate assignment (current status) and again when the
    // user answers the dialog. Signal both; the caller re-reads the status.
    dispatch_semaphore_signal(self.semaphore);
}
@end

/// Check (and if undetermined, request) location authorization for geofence
/// alarms. Mirrors apple-location's flow: notDetermined → pop the system
/// dialog and wait for the user's answer; denied/restricted → clear error.
/// Returns YES when usable (whenInUse or always). On NO, *errMsg is set.
///
/// The geofence itself is monitored by the system Reminders service once the
/// EKReminder is saved, but the permission gate here is what makes a denied
/// state surface as an actionable CLI error instead of an alarm that silently
/// never fires.
static BOOL reminders_location_auth(NSString **errMsg) {
    __block CLLocationManager *manager = nil;
    NoffRemindersLocAuthDelegate *delegate = [[NoffRemindersLocAuthDelegate alloc] init];

    // CLLocationManager wants a runloop thread; main queue matches the
    // apple-location precedent.
    dispatch_sync(dispatch_get_main_queue(), ^{
        manager = [[CLLocationManager alloc] init];
    });
    CLAuthorizationStatus status = manager.authorizationStatus;

    if (status == kCLAuthorizationStatusNotDetermined) {
        dispatch_async(dispatch_get_main_queue(), ^{
            manager.delegate = delegate;   // fires the initial callback
            [manager requestWhenInUseAuthorization];
        });
        // First signal = initial status callback; wait again for the answer.
        // 60s cap mirrors the reminders-access dialog wait: the user is
        // looking at a system permission alert.
        dispatch_semaphore_wait(delegate.semaphore, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC));
        dispatch_semaphore_wait(delegate.semaphore, dispatch_time(DISPATCH_TIME_NOW, 60 * NSEC_PER_SEC));
        status = manager.authorizationStatus;
    }

    if (status == kCLAuthorizationStatusAuthorizedWhenInUse ||
        status == kCLAuthorizationStatusAuthorizedAlways) {
        return YES;
    }
    if (errMsg) {
        *errMsg = @"Location access is required for location-based reminders. "
                   "To grant access, open Settings > Privacy & Security > "
                   "Location Services and enable Minis. "
                   "Time-based reminders (--due) work without location access.";
    }
    return NO;
}

/// Serialize a reminder's location alarm (if any) for list/create/update
/// output, so a created geofence is immediately verifiable from the CLI.
static NSDictionary *reminder_location_dict(EKReminder *reminder) {
    for (EKAlarm *alarm in reminder.alarms) {
        EKStructuredLocation *loc = alarm.structuredLocation;
        if (!loc || alarm.proximity == EKAlarmProximityNone) continue;
        NSMutableDictionary *d = [NSMutableDictionary dictionary];
        d[@"name"] = loc.title ?: @"";
        if (loc.geoLocation) {
            d[@"latitude"] = @(loc.geoLocation.coordinate.latitude);
            d[@"longitude"] = @(loc.geoLocation.coordinate.longitude);
        }
        d[@"radius_m"] = @(loc.radius);
        d[@"proximity"] = alarm.proximity == EKAlarmProximityLeave ? @"leave" : @"enter";
        return d;
    }
    return nil;
}

/// Parse --location-name/--lat/--lng/--radius/--proximity and, when present,
/// attach a geofence alarm to `reminder` (replacing any existing location
/// alarm, so update is idempotent).
///
/// Returns: 0 = no location args given (no-op) or alarm attached successfully;
/// nonzero = a NOFF exit code, with *errOut filled with the error envelope.
/// Strict parsing on purpose: `doubleValue` silently reads garbage as 0, and
/// 0 is a VALID coordinate (Gulf of Guinea), so numbers are validated with
/// NSScanner instead.
static int apply_location_alarm_args(int argc, char **argv, EKReminder *reminder,
                                      NSString *subcmd, NSDictionary **errOut) {
    NSString *latStr  = noff_find_arg(argc, argv, "--lat");
    NSString *lngStr  = noff_find_arg(argc, argv, "--lng");
    NSString *name    = noff_find_arg(argc, argv, "--location-name");
    NSString *radStr  = noff_find_arg(argc, argv, "--radius");
    NSString *proxStr = noff_find_arg(argc, argv, "--proximity");

    if (!latStr && !lngStr && !name && !radStr && !proxStr) return 0;  // no-op

    // A location alarm needs coordinates; anything else present without them
    // is a mistake worth failing loudly on rather than half-applying.
    if (!latStr || !lngStr) {
        *errOut = noff_json_error(TOOL_NAME, subcmd, NOFF_ERR_INVALID_ARGS,
            @"Location reminders need both --lat and --lng (WGS-84 decimal degrees). "
             "Optional: --location-name <text>, --radius <meters>, --proximity enter|leave.");
        return NOFF_EXIT_INVALID_ARGS;
    }

    double lat = 0, lng = 0;
    NSScanner *latScan = [NSScanner scannerWithString:latStr];
    NSScanner *lngScan = [NSScanner scannerWithString:lngStr];
    if (![latScan scanDouble:&lat] || !latScan.isAtEnd || lat < -90.0 || lat > 90.0 ||
        ![lngScan scanDouble:&lng] || !lngScan.isAtEnd || lng < -180.0 || lng > 180.0) {
        *errOut = noff_json_error(TOOL_NAME, subcmd, NOFF_ERR_INVALID_ARGS,
            [NSString stringWithFormat:
                @"Invalid coordinates: --lat '%@' --lng '%@'. Expect WGS-84 decimal "
                 "degrees, lat in [-90,90], lng in [-180,180].", latStr, lngStr]);
        return NOFF_EXIT_INVALID_ARGS;
    }

    double radius = 0;  // 0 = let the system apply its default geofence radius
    if (radStr) {
        NSScanner *radScan = [NSScanner scannerWithString:radStr];
        if (![radScan scanDouble:&radius] || !radScan.isAtEnd || radius < 0) {
            *errOut = noff_json_error(TOOL_NAME, subcmd, NOFF_ERR_INVALID_ARGS,
                [NSString stringWithFormat:
                    @"Invalid --radius '%@'. Expect a non-negative number of meters.", radStr]);
            return NOFF_EXIT_INVALID_ARGS;
        }
    }

    EKAlarmProximity proximity = EKAlarmProximityEnter;
    if (proxStr) {
        if ([proxStr caseInsensitiveCompare:@"enter"] == NSOrderedSame ||
            [proxStr caseInsensitiveCompare:@"arrive"] == NSOrderedSame) {
            proximity = EKAlarmProximityEnter;
        } else if ([proxStr caseInsensitiveCompare:@"leave"] == NSOrderedSame ||
                   [proxStr caseInsensitiveCompare:@"exit"] == NSOrderedSame) {
            proximity = EKAlarmProximityLeave;
        } else {
            *errOut = noff_json_error(TOOL_NAME, subcmd, NOFF_ERR_INVALID_ARGS,
                [NSString stringWithFormat:
                    @"Invalid --proximity '%@'. Use 'enter' (arrive) or 'leave'.", proxStr]);
            return NOFF_EXIT_INVALID_ARGS;
        }
    }

    NSString *authErr = nil;
    if (!reminders_location_auth(&authErr)) {
        *errOut = noff_json_error(TOOL_NAME, subcmd, NOFF_ERR_AUTHORIZATION_DENIED, authErr);
        return NOFF_EXIT_AUTH_DENIED;
    }

    // Replace any existing location alarm so repeated updates don't stack
    // geofences. Time-based alarms are untouched.
    for (EKAlarm *alarm in [reminder.alarms copy] ?: @[]) {
        if (alarm.structuredLocation && alarm.proximity != EKAlarmProximityNone) {
            [reminder removeAlarm:alarm];
        }
    }

    EKStructuredLocation *loc = [EKStructuredLocation locationWithTitle:
        (name.length ? name : [NSString stringWithFormat:@"%.4f,%.4f", lat, lng])];
    loc.geoLocation = [[CLLocation alloc] initWithLatitude:lat longitude:lng];
    loc.radius = radius;
    EKAlarm *alarm = [[EKAlarm alloc] init];
    alarm.structuredLocation = loc;
    alarm.proximity = proximity;
    [reminder addAlarm:alarm];
    return 0;
}

// ── [T-reminders-due-alarm] Time alarms for reminders (GH#251) ──
//
// `dueDateComponents` is metadata only. Apple's "Setting an Alarm" is explicit
// that a notification comes from an EKAlarm, not from the due date — so a
// reminder created with just `--due` sat silently past its time, while one
// given a time in the Reminders app banner-notified as expected. The Reminders
// app itself adds an alarm when you set a due TIME; this mirrors that.

/// Whether a `--due` string carries a time of day, not just a calendar date.
///
/// `noff_parse_date` returns an NSDate, which cannot distinguish "2026-08-25"
/// from "2026-08-25T00:00" after the fact — both land on midnight. The
/// distinction only survives in the INPUT, so this inspects the string.
///
/// Matching the parser's accepted forms (NativeOffloadUtils.m):
///   * relative offsets (-2h, -30m, -7d) resolve to "now minus X", which always
///     has a real time of day;
///   * ISO 8601 / "yyyy-MM-dd'T'HH:mm[:ss]" carry an explicit clock time;
///   * bare "yyyy-MM-dd" does not.
/// A 'T' (or a space, for tolerance) followed by a digit is what separates the
/// last case from the others.
static BOOL due_string_has_time_of_day(NSString *dueStr) {
    if (dueStr.length == 0) return NO;
    if ([dueStr hasPrefix:@"-"]) return YES;              // relative → now-based
    NSRange sep = [dueStr rangeOfCharacterFromSet:
                   [NSCharacterSet characterSetWithCharactersInString:@"T "]];
    if (sep.location == NSNotFound) return NO;            // date-only
    NSUInteger next = sep.location + sep.length;
    if (next >= dueStr.length) return NO;                 // trailing separator
    return [[NSCharacterSet decimalDigitCharacterSet]
            characterIsMember:[dueStr characterAtIndex:next]];
}

// ── [T-reminders-date-only] Date-only due dates (GH#286) ──
//
// A reminder due "on a day" and one due "at 00:00 that day" are different
// things to EventKit: the first has dueDateComponents WITHOUT hour/minute and
// the Reminders app shows it as an all-day item; the second shows a jarring
// "12:00 AM". `--due 2026-02-25` used to produce the second, because the
// components always included Hour|Minute of the parsed midnight.

/// dueDateComponents for `due`: date units only when `dateOnly`, otherwise
/// date + hour + minute (the previous behaviour, unchanged for timed dues).
static NSDateComponents *reminder_due_components(NSDate *due, BOOL dateOnly) {
    NSCalendarUnit units = NSCalendarUnitYear | NSCalendarUnitMonth | NSCalendarUnitDay;
    if (!dateOnly) units |= NSCalendarUnitHour | NSCalendarUnitMinute;
    return [[NSCalendar currentCalendar] components:units fromDate:due];
}

/// True when the reminder's due carries no time of day (an all-day reminder,
/// whether created here or in the Reminders app).
static BOOL reminder_is_all_day(EKReminder *r) {
    NSDateComponents *c = r.dueDateComponents;
    return c != nil && c.hour == NSDateComponentUndefined;
}

/// Write `due` and `is_all_day` into an output dict, shared by list, create
/// and update. An all-day due is reported as "yyyy-MM-dd", built from the
/// stored components rather than through a date + formatter, so no time zone
/// shift can move it to the neighbouring day. A timed due keeps the existing
/// ISO 8601 form.
static void reminder_put_due(NSMutableDictionary *d, EKReminder *r) {
    NSDateComponents *c = r.dueDateComponents;
    if (!c) return;
    BOOL allDay = reminder_is_all_day(r);
    d[@"is_all_day"] = @(allDay);
    if (allDay && c.year != NSDateComponentUndefined
        && c.month != NSDateComponentUndefined && c.day != NSDateComponentUndefined) {
        d[@"due"] = [NSString stringWithFormat:@"%04ld-%02ld-%02ld",
                     (long)c.year, (long)c.month, (long)c.day];
        return;
    }
    NSDate *due = [[NSCalendar currentCalendar] dateFromComponents:c];
    d[@"due"] = due ? noff_format_date(due) : [NSNull null];
}

// ── [T-reminders-url] Native URL field (GH#286) ──
//
// EKReminder has a first-class `URL` (EKCalendarItem.URL). The CLI never wired
// it, so a link could only live in --notes and could not be read back as a
// field. Verified on device (iOS 26, iPhone 11): the value is stored and
// round-trips through EventKit, but the system Reminders app does NOT display
// EKReminder.URL — its own "Notes and URL" link lives in storage EventKit does
// not expose. The help text says so, and suggests --notes when the user needs
// to see the link in the Reminders app.

/// Parse `--url`: must be an absolute URL with a scheme. nil + *err on failure.
static NSURL *parse_reminder_url(NSString *raw, NSString **err) {
    NSString *t = [raw stringByTrimmingCharactersInSet:
                   [NSCharacterSet whitespaceAndNewlineCharacterSet]];
    NSURL *url = t.length ? [NSURL URLWithString:t] : nil;
    if (!url || url.scheme.length == 0) {
        if (err) *err = [NSString stringWithFormat:
            @"--url must be an absolute URL with a scheme (e.g. https://example.com/page); got '%@'.", raw];
        return nil;
    }
    return url;
}

/// Resolve `--notify on|off` (also accepting true/false/1/0/yes/no).
/// Returns YES if the flag was present and understood, writing the value to
/// `*out`; NO if absent or malformed, with `*malformed` telling the two apart.
static BOOL parse_notify_flag(int argc, char **argv, BOOL *out, BOOL *malformed) {
    *malformed = NO;
    NSString *s = noff_find_arg(argc, argv, "--notify");
    if (!s) return NO;
    NSString *v = [s lowercaseString];
    if ([v isEqualToString:@"on"] || [v isEqualToString:@"true"] ||
        [v isEqualToString:@"1"] || [v isEqualToString:@"yes"]) { *out = YES; return YES; }
    if ([v isEqualToString:@"off"] || [v isEqualToString:@"false"] ||
        [v isEqualToString:@"0"] || [v isEqualToString:@"no"]) { *out = NO; return YES; }
    *malformed = YES;
    return NO;
}

/// Remove every TIME alarm from a reminder, leaving geofence alarms alone.
///
/// The two kinds coexist on one reminder — a task can fire at 9am AND on
/// arrival — so this filters on `absoluteDate` rather than clearing `alarms`
/// wholesale the way the event `--alarm` path does. `apply_location_alarm_args`
/// is the owner of the geofence side and is deliberately untouched here.
static void remove_time_alarms(EKReminder *reminder) {
    for (EKAlarm *a in [reminder.alarms copy] ?: @[]) {
        if (a.absoluteDate && a.proximity == EKAlarmProximityNone) {
            [reminder removeAlarm:a];
        }
    }
}

/// True if the reminder already carries a time alarm.
static BOOL has_time_alarm(EKReminder *reminder) {
    for (EKAlarm *a in reminder.alarms ?: @[]) {
        if (a.absoluteDate && a.proximity == EKAlarmProximityNone) return YES;
    }
    return NO;
}

int calendar_cmd_remind(int argc, char **argv, int stdout_fd, int stderr_fd, BOOL compact, BOOL quiet) {
    NSString *authErr = nil;
    if (!requestRemindersAccess(&authErr)) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"remind",
                                             NOFF_ERR_AUTHORIZATION_DENIED, authErr);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_AUTH_DENIED;
    }

    NSString *title = noff_find_arg(argc, argv, "--title");
    if (!title) {
        noff_emit_help(stderr_fd, HELP_TEXT);
        NSDictionary *err = noff_json_error(TOOL_NAME, @"remind",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"Required: --title");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }

    EKReminder *reminder = [EKReminder reminderWithEventStore:eventStore()];
    reminder.title = title;

    NSDate *dueDate = nil;
    NSString *dueStr = noff_find_arg(argc, argv, "--due");
    if (dueStr) {
        NSDate *due = noff_parse_date(dueStr);
        if (due) {
            dueDate = due;
            // [T-reminders-date-only] "2026-02-25" → an all-day reminder, not
            // one due at 12:00 AM.
            reminder.dueDateComponents = reminder_due_components(due, noff_is_date_only_string(dueStr));
            // [T-issue282-sync-start-anchor] Keep the hidden start anchor in
            // lockstep with due. EventKit uses startDateComponents as the
            // recurrence anchor internally; leaving it stale after a due
            // change is a latent divergence (CalDAV DTSTART, other system
            // consumers) even though completion-rescheduling reads due.
            reminder.startDateComponents = reminder.dueDateComponents;
        }
    }

    // [T-reminders-recurrence] EKReminder inherits recurrenceRules from
    // EKCalendarItem, so the SAME builder (and therefore the same flag names,
    // parsing and error messages) as `apple-calendar create` applies here.
    // Parsed BEFORE saving so a malformed rule fails the command instead of
    // quietly creating a one-off reminder the user believes repeats.
    //
    // The recurrence anchor is the due date: EventKit requires a repeating
    // reminder to have one, otherwise there is no first occurrence to repeat
    // from and the rule is meaningless (the system Reminders app enforces the
    // same coupling in its UI).
    NSString *recurErr = nil;
    EKRecurrenceRule *recurRule = build_recurrence_rule(argc, argv, dueDate, &recurErr);
    if (recurErr) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"remind",
                                             NOFF_ERR_INVALID_ARGS, recurErr);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    if (recurRule && !dueDate) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"remind",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"--recur requires --due: a repeating reminder needs a due date to repeat from.");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    if (recurRule) reminder.recurrenceRules = @[recurRule];

    NSString *notes = noff_find_arg(argc, argv, "--notes");
    if (notes) reminder.notes = notes;

    // [T-reminders-url] Validated before save, so a malformed link fails the
    // command instead of creating a reminder without it.
    NSString *urlStr = noff_find_arg(argc, argv, "--url");
    if (urlStr) {
        NSString *urlErr = nil;
        NSURL *url = parse_reminder_url(urlStr, &urlErr);
        if (!url) {
            NSDictionary *err = noff_json_error(TOOL_NAME, @"remind",
                                                 NOFF_ERR_INVALID_ARGS, urlErr);
            noff_emit_json(stdout_fd, err, compact, quiet);
            return NOFF_EXIT_INVALID_ARGS;
        }
        reminder.URL = url;
    }

    NSString *priorityStr = noff_find_arg(argc, argv, "--priority");
    if (priorityStr) reminder.priority = (NSUInteger)[priorityStr integerValue];

    // --parent-id: iOS Reminders has a subtask feature, but as of iOS 26.5
    // EventKit / EventKitUI expose NO public API to create or set a
    // parent/child relationship — EKReminder and EKCalendarItem have no
    // parent/subtask property, and the public .tbd ABI carries no such symbol.
    // We deliberately do NOT reach for the private setParent:/setParentID:
    // selectors (App-Store-unsafe and version-fragile — and respondsToSelector
    // already returned NO for setParent: on this device). So when --parent-id
    // is given, fail fast with a clear, honest message BEFORE creating an
    // orphan reminder, rather than silently creating a non-nested one.
    NSString *parentId = noff_find_arg(argc, argv, "--parent-id");
    if (parentId) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"remind",
                                             NOFF_ERR_NOT_AVAILABLE,
                                             @"Reminder subtasks are not supported: iOS (through 26.5) provides no public EventKit API to set a parent/child relationship, so --parent-id cannot be honored. Create the reminder without --parent-id, or nest it manually in the Reminders app.");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_NOT_AVAILABLE;
    }

    // Find reminder list by EXACT name — a miss is an error with the
    // candidate lists, never a silent default (issue #282: batch-created
    // reminders all landed in the wrong list while reporting ok:true).
    // [T-issue282-exact-list-match]
    NSString *listName = noff_find_arg(argc, argv, "--list");
    if (listName) {
        NSDictionary *matchErr = nil;
        EKCalendar *target = noff_resolve_calendar_exact(listName, EKEntityTypeReminder,
                                                         @"remind", &matchErr);
        if (!target) {
            noff_emit_json(stdout_fd, matchErr, compact, quiet);
            return NOFF_EXIT_INVALID_ARGS;
        }
        reminder.calendar = target;
    }
    if (!reminder.calendar) {
        reminder.calendar = [eventStore() defaultCalendarForNewReminders];
    }

    // [T-reminders-due-alarm] Attach the time alarm that actually produces the
    // notification (GH#251). Default: on when --due names a time of day, off
    // for a date-only due — which is what the Reminders app does, and avoids
    // banner-ing someone at midnight for a "sometime Tuesday" task.
    // --notify on|off overrides either way. Explicit `--notify on` without a
    // due date is a mistake worth naming: there is no instant to fire at.
    BOOL notifyFlag = NO, notifyMalformed = NO;
    BOOL notifyGiven = parse_notify_flag(argc, argv, &notifyFlag, &notifyMalformed);
    if (notifyMalformed) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"remind",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"--notify expects on or off.");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    if (notifyGiven && notifyFlag && !dueDate) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"remind",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"--notify on requires --due: a notification needs a time to fire at.");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    BOOL wantNotify = notifyGiven ? notifyFlag
                                  : (dueDate != nil && due_string_has_time_of_day(dueStr));
    if (wantNotify && dueDate) {
        [reminder addAlarm:[EKAlarm alarmWithAbsoluteDate:dueDate]];
    }

    // [T-reminders-location-alarm] Attach a geofence alarm when location args
    // are present. Runs BEFORE save so a permission denial or a bad coordinate
    // fails without leaving a half-configured reminder behind.
    NSDictionary *locErr = nil;
    int locRc = apply_location_alarm_args(argc, argv, reminder, @"remind", &locErr);
    if (locRc != 0) {
        noff_emit_json(stdout_fd, locErr, compact, quiet);
        return locRc;
    }

    NSError *saveErr = nil;
    BOOL saved = [eventStore() saveReminder:reminder commit:YES error:&saveErr];
    if (!saved) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"remind",
                                             NOFF_ERR_INTERNAL_ERROR,
                                             saveErr.localizedDescription ?: @"Failed to save reminder");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_ERROR;
    }

    NSMutableDictionary *data = [@{
        @"id": reminder.calendarItemIdentifier ?: @"",
        @"title": title,
        @"list": reminder.calendar.title ?: @"",
        // [T-issue282-return-list-id] Unique list identity — titles collide
        // across sources (two "Work" lists, iCloud vs Local).
        @"list_id": reminder.calendar.calendarIdentifier ?: @"",
        @"list_source": reminder.calendar.source.title ?: @"",
    } mutableCopy];
    NSDictionary *locInfo = reminder_location_dict(reminder);
    if (locInfo) data[@"location"] = locInfo;
    // [T-reminders-date-only] Echo the stored due (date-only as yyyy-MM-dd)
    // and whether it is all-day; [T-reminders-url] and the link.
    reminder_put_due(data, reminder);
    data[@"url"] = reminder.URL.absoluteString ?: [NSNull null];
    // [T-reminders-due-alarm] Echo whether a notification was attached, and
    // when. The default is inferred from the shape of --due, so the caller
    // should not have to guess which way it went — this is the field that
    // answers "will this actually buzz?" without a follow-up list call.
    if (dueDate) {
        data[@"notify"] = @(has_time_alarm(reminder));
        if (has_time_alarm(reminder)) data[@"notify_at"] = noff_format_date(dueDate);
    }
    // [T-reminders-recurrence] Echo the stored rule so the caller can confirm
    // what was actually saved without a follow-up list call (same rationale as
    // the event create path).
    if (reminder.hasRecurrenceRules && reminder.recurrenceRules.count > 0) {
        data[@"recurrence"] = recurrence_to_dict(reminder.recurrenceRules.firstObject);
    }
    noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, @"remind", data), compact, quiet);
    return NOFF_EXIT_SUCCESS;
}

// Helper: fetch a single reminder by calendarItemIdentifier
static EKReminder *fetch_reminder_by_id(NSString *reminderId) {
    NSPredicate *pred = [eventStore() predicateForRemindersInCalendars:nil];
    __block NSArray<EKReminder *> *reminders = nil;
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    [eventStore() fetchRemindersMatchingPredicate:pred completion:^(NSArray *r) {
        reminders = r;
        dispatch_semaphore_signal(sem);
    }];
    dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC));

    for (EKReminder *r in reminders) {
        if ([r.calendarItemIdentifier isEqualToString:reminderId]) {
            return r;
        }
    }
    return nil;
}

int calendar_cmd_update_reminder(int argc, char **argv, int stdout_fd, BOOL compact, BOOL quiet) {
    NSString *authErr = nil;
    if (!requestRemindersAccess(&authErr)) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"update",
                                             NOFF_ERR_AUTHORIZATION_DENIED, authErr);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_AUTH_DENIED;
    }

    NSString *reminderId = noff_find_arg(argc, argv, "--id");
    if (!reminderId) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"update",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"Required: --id <reminder_id>");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }

    EKReminder *reminder = fetch_reminder_by_id(reminderId);
    if (!reminder) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"update",
                                             NOFF_ERR_NO_DATA,
                                             [NSString stringWithFormat:@"Reminder not found with id '%@'", reminderId]);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_ERROR;
    }

    NSString *title = noff_find_arg(argc, argv, "--title");
    if (title) reminder.title = title;

    NSString *dueStr = noff_find_arg(argc, argv, "--due");
    NSDate *newDueDate = nil;
    if (dueStr) {
        NSDate *due = noff_parse_date(dueStr);
        if (due) {
            newDueDate = due;
            // [T-reminders-date-only] Converts either way: a date-only --due
            // makes the reminder all-day, a timed one gives it a time again.
            reminder.dueDateComponents = reminder_due_components(due, noff_is_date_only_string(dueStr));
            // [T-issue282-sync-start-anchor] Same lockstep as create — an
            // update that moves due must move the recurrence anchor with it,
            // or the stored start keeps pointing at the ORIGINAL create date.
            reminder.startDateComponents = reminder.dueDateComponents;
        }
    }

    NSString *notes = noff_find_arg(argc, argv, "--notes");
    if (notes) reminder.notes = notes;

    // [T-reminders-url] --url sets/replaces the link, --clear-url removes it.
    NSString *updUrlStr = noff_find_arg(argc, argv, "--url");
    BOOL clearUrl = noff_has_flag(argc, argv, "--clear-url");
    if (updUrlStr && clearUrl) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"update",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"--url and --clear-url are mutually exclusive.");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    if (updUrlStr) {
        NSString *urlErr = nil;
        NSURL *url = parse_reminder_url(updUrlStr, &urlErr);
        if (!url) {
            NSDictionary *err = noff_json_error(TOOL_NAME, @"update",
                                                 NOFF_ERR_INVALID_ARGS, urlErr);
            noff_emit_json(stdout_fd, err, compact, quiet);
            return NOFF_EXIT_INVALID_ARGS;
        }
        reminder.URL = url;
    } else if (clearUrl) {
        reminder.URL = nil;
    }

    NSString *priorityStr = noff_find_arg(argc, argv, "--priority");
    if (priorityStr) reminder.priority = (NSUInteger)[priorityStr integerValue];

    NSString *listName = noff_find_arg(argc, argv, "--list");
    if (listName) {
        // [T-issue282-exact-list-match] Exact match or error — the old
        // substring miss silently kept the reminder's previous list.
        NSDictionary *matchErr = nil;
        EKCalendar *target = noff_resolve_calendar_exact(listName, EKEntityTypeReminder,
                                                         @"update", &matchErr);
        if (!target) {
            noff_emit_json(stdout_fd, matchErr, compact, quiet);
            return NOFF_EXIT_INVALID_ARGS;
        }
        reminder.calendar = target;
    }

    // [T-reminders-recurrence] Same builder / flags as create and as
    // `apple-calendar`. The anchor is the reminder's EFFECTIVE due date — the
    // one just set by --due if present, otherwise the stored one — so
    // --recur-until is validated against the date the rule will really repeat
    // from. --clear-recur removes an existing rule (no other way to undo one).
    NSDate *effectiveDue = reminder.dueDateComponents
        ? [[NSCalendar currentCalendar] dateFromComponents:reminder.dueDateComponents]
        : nil;
    NSString *updRecurErr = nil;
    EKRecurrenceRule *updRecurRule = build_recurrence_rule(argc, argv, effectiveDue, &updRecurErr);
    if (updRecurErr) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"update",
                                             NOFF_ERR_INVALID_ARGS, updRecurErr);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    if (updRecurRule && !effectiveDue) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"update",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"--recur requires the reminder to have a due date: pass --due, or set one first.");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    if (noff_has_flag(argc, argv, "--clear-recur")) {
        reminder.recurrenceRules = nil;
    }
    if (updRecurRule) reminder.recurrenceRules = @[updRecurRule];

    // [T-reminders-due-alarm] Keep the notification in step with the due date
    // (GH#251). Four cases, in the order they are decided:
    //
    //   --notify off        → drop any time alarm, keep the due date.
    //   --notify on         → (re)attach one at the effective due date.
    //   --due, no --notify  → an EXISTING time alarm is re-pointed at the new
    //                         date (a stale alarm firing at the old time is a
    //                         bug, not a feature); if there was none, apply the
    //                         same default as `remind` — attach when the new
    //                         --due names a time of day.
    //   neither             → leave alarms exactly as they are.
    //
    // Time alarms only: `remove_time_alarms` filters on absoluteDate so a
    // geofence set by --lat/--lng survives, and vice versa.
    BOOL updNotify = NO, updNotifyMalformed = NO;
    BOOL updNotifyGiven = parse_notify_flag(argc, argv, &updNotify, &updNotifyMalformed);
    if (updNotifyMalformed) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"update",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"--notify expects on or off.");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    if (updNotifyGiven && updNotify && !effectiveDue) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"update",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"--notify on requires the reminder to have a due date: pass --due, or set one first.");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
    if (updNotifyGiven) {
        remove_time_alarms(reminder);
        if (updNotify && effectiveDue) {
            [reminder addAlarm:[EKAlarm alarmWithAbsoluteDate:effectiveDue]];
        }
    } else if (newDueDate) {
        BOOL had = has_time_alarm(reminder);
        remove_time_alarms(reminder);
        // [T-reminders-date-only] Moving to a date-only due must NOT re-point
        // an existing alarm to the new day's midnight — that is the 00:00
        // notification this fix removes. Pass --notify on to keep one.
        if (!noff_is_date_only_string(dueStr)
            && (had || due_string_has_time_of_day(dueStr))) {
            [reminder addAlarm:[EKAlarm alarmWithAbsoluteDate:newDueDate]];
        }
    }

    // [T-reminders-location-alarm] --clear-location removes an existing
    // geofence (there is otherwise no way to undo one from the CLI); the
    // location args add or replace it.
    if (noff_has_flag(argc, argv, "--clear-location")) {
        for (EKAlarm *alarm in [reminder.alarms copy] ?: @[]) {
            if (alarm.structuredLocation && alarm.proximity != EKAlarmProximityNone) {
                [reminder removeAlarm:alarm];
            }
        }
    }
    NSDictionary *locErr = nil;
    int locRc = apply_location_alarm_args(argc, argv, reminder, @"update", &locErr);
    if (locRc != 0) {
        noff_emit_json(stdout_fd, locErr, compact, quiet);
        return locRc;
    }

    NSError *saveErr = nil;
    BOOL saved = [eventStore() saveReminder:reminder commit:YES error:&saveErr];
    if (!saved) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"update",
                                             NOFF_ERR_INTERNAL_ERROR,
                                             saveErr.localizedDescription ?: @"Failed to update reminder");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_ERROR;
    }

    NSMutableDictionary *data = [@{
        @"id": reminder.calendarItemIdentifier ?: @"",
        @"title": reminder.title ?: @"",
        @"completed": @(reminder.isCompleted),
        @"list": reminder.calendar.title ?: @"",
        @"list_id": reminder.calendar.calendarIdentifier ?: @"",        // [T-issue282-return-list-id]
        @"list_source": reminder.calendar.source.title ?: @"",
        @"priority": @(reminder.priority),
        @"notes": reminder.notes ?: [NSNull null],
        @"url": reminder.URL.absoluteString ?: [NSNull null],       // [T-reminders-url]
    } mutableCopy];
    reminder_put_due(data, reminder);                               // [T-reminders-date-only]
    NSDictionary *updLoc = reminder_location_dict(reminder);
    if (updLoc) data[@"location"] = updLoc;
    // [T-reminders-due-alarm] Always reported here, unconditionally: after an
    // update the caller's question is "does this still notify?", and the answer
    // may have changed without --notify being passed at all (a --due edit
    // re-points an existing alarm). Reporting only when a flag was given would
    // hide exactly the case worth confirming.
    data[@"notify"] = @(has_time_alarm(reminder));
    if (reminder.hasRecurrenceRules && reminder.recurrenceRules.count > 0) {
        data[@"recurrence"] = recurrence_to_dict(reminder.recurrenceRules.firstObject);
    }
    noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, @"update", data), compact, quiet);
    return NOFF_EXIT_SUCCESS;
}

int calendar_cmd_complete_reminder(int argc, char **argv, int stdout_fd, BOOL compact, BOOL quiet) {
    NSString *authErr = nil;
    if (!requestRemindersAccess(&authErr)) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"complete",
                                             NOFF_ERR_AUTHORIZATION_DENIED, authErr);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_AUTH_DENIED;
    }

    NSString *reminderId = noff_find_arg(argc, argv, "--id");
    if (!reminderId) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"complete",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"Required: --id <reminder_id>");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }

    BOOL uncomplete = noff_has_flag(argc, argv, "--undo");

    EKReminder *reminder = fetch_reminder_by_id(reminderId);
    if (!reminder) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"complete",
                                             NOFF_ERR_NO_DATA,
                                             [NSString stringWithFormat:@"Reminder not found with id '%@'", reminderId]);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_ERROR;
    }

    reminder.completed = !uncomplete;

    NSError *saveErr = nil;
    BOOL saved = [eventStore() saveReminder:reminder commit:YES error:&saveErr];
    if (!saved) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"complete",
                                             NOFF_ERR_INTERNAL_ERROR,
                                             saveErr.localizedDescription ?: @"Failed to update reminder");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_ERROR;
    }

    NSDictionary *data = @{
        @"id": reminder.calendarItemIdentifier ?: @"",
        @"title": reminder.title ?: @"",
        @"completed": @(reminder.isCompleted),
        @"list": reminder.calendar.title ?: @"",
        @"list_id": reminder.calendar.calendarIdentifier ?: @"",        // [T-issue282-return-list-id]
        @"list_source": reminder.calendar.source.title ?: @"",
    };
    noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, @"complete", data), compact, quiet);
    return NOFF_EXIT_SUCCESS;
}

int calendar_cmd_delete_reminder(int argc, char **argv, int stdout_fd, BOOL compact, BOOL quiet) {
    NSString *authErr = nil;
    if (!requestRemindersAccess(&authErr)) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"delete",
                                             NOFF_ERR_AUTHORIZATION_DENIED, authErr);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_AUTH_DENIED;
    }

    NSString *reminderId = noff_find_arg(argc, argv, "--id");
    if (!reminderId) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"delete",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"Required: --id <reminder_id>");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }

    EKReminder *reminder = fetch_reminder_by_id(reminderId);
    if (!reminder) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"delete",
                                             NOFF_ERR_NO_DATA,
                                             [NSString stringWithFormat:@"Reminder not found with id '%@'", reminderId]);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_ERROR;
    }

    NSString *title = reminder.title ?: @"";
    NSString *list = reminder.calendar.title ?: @"";
    // [T-issue282-return-list-id] Captured before the delete.
    NSString *listId = reminder.calendar.calendarIdentifier ?: @"";
    NSString *listSource = reminder.calendar.source.title ?: @"";

    NSError *removeErr = nil;
    BOOL removed = [eventStore() removeReminder:reminder commit:YES error:&removeErr];
    if (!removed) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"delete",
                                             NOFF_ERR_INTERNAL_ERROR,
                                             removeErr.localizedDescription ?: @"Failed to delete reminder");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_ERROR;
    }

    NSDictionary *data = @{
        @"id": reminderId,
        @"title": title,
        @"list": list,
        @"list_id": listId,
        @"list_source": listSource,
        @"deleted": @YES,
    };
    noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, @"delete", data), compact, quiet);
    return NOFF_EXIT_SUCCESS;
}

static int calendar_handler(int argc, char **argv,
                             int stdin_fd, int stdout_fd, int stderr_fd) {
    if (noff_has_flag(argc, argv, "--help") || noff_has_flag(argc, argv, "-h")) {
        noff_emit_help(stderr_fd, HELP_TEXT);
        return NOFF_EXIT_SUCCESS;
    }

    BOOL compact = noff_has_flag(argc, argv, "--compact");
    BOOL quiet = noff_has_flag(argc, argv, "-q") || noff_has_flag(argc, argv, "--quiet");

    NSString *subcmd = noff_get_subcommand(argc, argv);
    if (!subcmd) {
        noff_emit_help(stderr_fd, HELP_TEXT);
        NSDictionary *err = noff_json_error(TOOL_NAME, @"unknown",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"No command specified. Use --help for usage.");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }

    if ([subcmd isEqualToString:@"list"])              return cmd_list(argc, argv, stdout_fd, compact, quiet);
    if ([subcmd isEqualToString:@"reminders"])         return calendar_cmd_reminders(argc, argv, stdout_fd, compact, quiet);
    if ([subcmd isEqualToString:@"freebusy"])          return cmd_freebusy(argc, argv, stdout_fd, compact, quiet);
    if ([subcmd isEqualToString:@"calendars"])         return cmd_calendars(argc, argv, stdout_fd, compact, quiet);
    if ([subcmd isEqualToString:@"create"])            return cmd_create(argc, argv, stdout_fd, stderr_fd, compact, quiet);
    if ([subcmd isEqualToString:@"update"])            return cmd_update(argc, argv, stdout_fd, stderr_fd, compact, quiet);
    if ([subcmd isEqualToString:@"delete"])            return cmd_delete(argc, argv, stdout_fd, compact, quiet);
    if ([subcmd isEqualToString:@"remind"])            return calendar_cmd_remind(argc, argv, stdout_fd, stderr_fd, compact, quiet);
    if ([subcmd isEqualToString:@"update-reminder"])   return calendar_cmd_update_reminder(argc, argv, stdout_fd, compact, quiet);
    if ([subcmd isEqualToString:@"complete-reminder"]) return calendar_cmd_complete_reminder(argc, argv, stdout_fd, compact, quiet);
    if ([subcmd isEqualToString:@"delete-reminder"])   return calendar_cmd_delete_reminder(argc, argv, stdout_fd, compact, quiet);

    noff_emit_help(stderr_fd, HELP_TEXT);
    NSDictionary *err = noff_json_error(TOOL_NAME, subcmd,
                                         NOFF_ERR_INVALID_ARGS,
                                         [NSString stringWithFormat:@"Unknown command '%@'. Valid commands: list, reminders, freebusy, calendars, create, update, delete, remind, update-reminder, complete-reminder, delete-reminder. Use --help for details.", subcmd]);
    noff_emit_json(stdout_fd, err, compact, quiet);
    return NOFF_EXIT_INVALID_ARGS;
}

void calendar_offload_register(void) {
    int err = native_offload_add_handler("apple-calendar", calendar_handler);
    if (err == 0) {
        noff_ensure_guest_stub("/usr/local/bin/apple-calendar");
        NSLog(@"NativeOffloads: apple-calendar handler registered");
    } else {
        NSLog(@"NativeOffloads: failed to register apple-calendar handler (err=%d)", err);
    }
}
