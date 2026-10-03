//
//  RemindersOffload.m
//  MinisApp
//
//  Native offload handler for `apple-reminders`.
//  Delegates to the shared reminder implementations in CalendarOffload.
//  Subcommands: list, create, update, complete, delete
//

#import <Foundation/Foundation.h>
#import "NativeOffloadUtils.h"
#import "CalendarOffload.h"
#include "kernel/native_offload.h"

static NSString *const TOOL_NAME = @"apple-reminders";

static NSString *const HELP_TEXT =
    @"apple-reminders - Query and manage iOS reminders\n"
     "\n"
     "USAGE:\n"
     "  apple-reminders <command> [options]\n"
     "\n"
     "COMMANDS:\n"
     "  list        List reminders\n"
     "  create      Create a new reminder\n"
     "  update      Update an existing reminder\n"
     "  complete    Mark a reminder as completed\n"
     "  delete      Delete a reminder\n"
     "\n"
     "COMMON OPTIONS:\n"
     "  --help, -h           Show this help message\n"
     "  --compact            Minimize JSON output\n"
     "  -q, --quiet          Output only data field\n"
     "\n"
     "LIST OPTIONS:\n"
     "  --completed          Show only completed reminders\n"
     "  --incomplete         Show only incomplete reminders\n"
     "  --list <name>        Filter by reminder list name\n"
     "  --limit <N>          Maximum number of results\n"
     "\n"
     "CREATE OPTIONS:\n"
     "  --title <title>      Reminder title (required)\n"
     "  --due <datetime>     Due date (ISO 8601 or relative). With a time of day\n"
     "                       (2026-02-25T18:00) this also schedules a system\n"
     "                       notification, like setting a time in the Reminders app.\n"
     "                       A date-only due (2026-02-25) makes an all-day reminder\n"
     "                       (no 12:00 AM time, no notification). Output reports\n"
     "                       is_all_day, and due as yyyy-MM-dd for all-day ones.\n"
     "  --notify <on|off>    Force the notification on or off, overriding that default.\n"
     "                       on requires a due date. On update, a --due change moves an\n"
     "                       existing notification with it. Independent of the location\n"
     "                       alerts below — a reminder can have both.\n"
     "  --list <name>        Reminder list name\n"
     "  --priority <0-9>     Priority level\n"
     "  --notes <text>       Reminder notes\n"
     "  --url <url>          Store a link in the reminder's URL field (absolute,\n"
     "                       with a scheme), e.g. --url https://example.com/doc.\n"
     "                       Returned by list/create/update as `url`. Note: the iOS\n"
     "                       Reminders app does not display this field — if the\n"
     "                       user must see the link there, also put it in --notes.\n"
     "  --clear-url          (update only) remove the link\n"
     "  --parent-id <id>     (Unsupported) Subtasks have no public EventKit API\n"
     "                       on iOS through 26.5; passing this returns an error.\n"
     "\n"
     "RECURRENCE OPTIONS (create/update) — a repeating reminder:\n"
     "  --recur <freq>       daily, weekly, monthly, or yearly.\n"
     "                       Requires a due date (--due, or an existing one).\n"
     "  --recur-interval <N> Repeat every N periods (default 1; 2 = every other week)\n"
     "  --recur-days <days>  Weekdays for weekly/monthly/yearly, comma-separated\n"
     "                       (e.g. fri or mon,wed,fri). Not valid with --recur daily.\n"
     "                       Defaults to the due date's weekday.\n"
     "  --recur-until <date> Repeat until this date (ISO 8601). Omit both this and\n"
     "                       --recur-count to repeat forever.\n"
     "  --recur-count <N>    Stop after N occurrences. Mutually exclusive with\n"
     "                       --recur-until.\n"
     "  --clear-recur        (update only) make the reminder non-repeating\n"
     "  Same flag names and semantics as `apple-calendar create`.\n"
     "\n"
     "LOCATION OPTIONS (create/update) — alert on arriving at / leaving a place:\n"
     "  --lat <deg>          Latitude, WGS-84 decimal degrees. Requires --lng.\n"
     "  --lng <deg>          Longitude, WGS-84 decimal degrees. Requires --lat.\n"
     "  --location-name <t>  Label for the place (default: \"lat,lng\")\n"
     "  --radius <meters>    Geofence radius; omit to use the system default\n"
     "  --proximity <p>      enter (default) = alert on arrival\n"
     "                       leave          = alert on departure\n"
     "  Needs Location Services access; a denial is reported as an error rather\n"
     "  than saving a reminder whose alarm would never fire. Works with or\n"
     "  without --due (combine to get both a time and a place trigger).\n"
     "  Tip: get coordinates with `apple-location forward --address \"...\"`.\n"
     "  Note: pass WGS-84 here (apple-location's `latitude`/`longitude`), NOT\n"
     "  the gcj02_* variant.\n"
     "\n"
     "UPDATE OPTIONS:\n"
     "  --id <reminder_id>   Reminder ID (required, from list output)\n"
     "  --title <title>      New title\n"
     "  --due <datetime>     New due date\n"
     "  --list <name>        Move to a different list\n"
     "  --priority <0-9>     New priority\n"
     "  --notes <text>       New notes\n"
     "  --url <url>          Set or replace the link\n"
     "  --clear-url          Remove the link\n"
     "  --clear-recur        Make the reminder non-repeating\n"
     "                       (recurrence flags above add or replace the rule)\n"
     "  --clear-location     Remove the geofence alarm from this reminder\n"
     "                       (location flags above add or replace it)\n"
     "\n"
     "COMPLETE OPTIONS:\n"
     "  --id <reminder_id>   Reminder ID (required, from list output)\n"
     "  --undo               Mark as incomplete instead\n"
     "\n"
     "DELETE OPTIONS:\n"
     "  --id <reminder_id>   Reminder ID (required, from list output)\n"
     "\n"
     "EXAMPLES:\n"
     "  apple-reminders list\n"
     "  apple-reminders list --incomplete --limit 10\n"
     "  apple-reminders list --list \"Shopping\"\n"
     "  apple-reminders create --title \"Buy groceries\" --due 2026-02-25T18:00\n"
     "  # All-day (no 12:00 AM, no notification), with a link:\n"
     "  apple-reminders create --title \"Pay rent\" --due 2026-02-25 \\\n"
     "      --url https://bank.example.com/rent\n"
     "  apple-reminders update --id <id> --clear-url\n"
     "  # Remind me when I ARRIVE at Shenzhen North station (200m geofence):\n"
     "  apple-reminders create --title \"Pick up parcel\" --location-name \"Shenzhen North\" \\\n"
     "      --lat 22.6099 --lng 114.0289 --radius 200 --proximity enter\n"
     "  # Remind me when I LEAVE the office:\n"
     "  apple-reminders create --title \"Buy milk\" --location-name \"Office\" \\\n"
     "      --lat 22.5431 --lng 114.0579 --proximity leave\n"
     "  # Every Monday at 09:00, forever — one repeating reminder, not many copies:\n"
     "  apple-reminders create --title \"Weekly report\" --due 2026-08-03T09:00 \\\n"
     "      --recur weekly --recur-days mon\n"
     "  # Every other day, 10 times:\n"
     "  apple-reminders create --title \"Water plants\" --due 2026-08-03T08:00 \\\n"
     "      --recur daily --recur-interval 2 --recur-count 10\n"
     "  # Due at a time → notifies at 18:00. Add --notify off to keep it silent:\n"
     "  apple-reminders create --title \"Call the bank\" --due 2026-02-25T18:00\n"
     "  # Date-only → no notification. Add --notify on to get one anyway (at 00:00):\n"
     "  apple-reminders create --title \"Renew passport\" --due 2026-02-25\n"
     "  apple-reminders update --id <reminder_id> --notify off\n"
     "  apple-reminders update --id <reminder_id> --clear-recur\n"
     "  apple-reminders update --id <reminder_id> --clear-location\n"
     "  apple-reminders update --id <reminder_id> --title \"New title\" --due 2026-03-01T10:00\n"
     "  apple-reminders complete --id <reminder_id>\n"
     "  apple-reminders complete --id <reminder_id> --undo\n"
     "  apple-reminders delete --id <reminder_id>\n";

static int reminders_handler(int argc, char **argv,
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

    if ([subcmd isEqualToString:@"list"])     return calendar_cmd_reminders(argc, argv, stdout_fd, compact, quiet);
    if ([subcmd isEqualToString:@"create"])   return calendar_cmd_remind(argc, argv, stdout_fd, stderr_fd, compact, quiet);
    if ([subcmd isEqualToString:@"update"])   return calendar_cmd_update_reminder(argc, argv, stdout_fd, compact, quiet);
    if ([subcmd isEqualToString:@"complete"]) return calendar_cmd_complete_reminder(argc, argv, stdout_fd, compact, quiet);
    if ([subcmd isEqualToString:@"delete"])   return calendar_cmd_delete_reminder(argc, argv, stdout_fd, compact, quiet);

    noff_emit_help(stderr_fd, HELP_TEXT);
    NSDictionary *err = noff_json_error(TOOL_NAME, subcmd,
                                         NOFF_ERR_INVALID_ARGS,
                                         [NSString stringWithFormat:@"Unknown command '%@'. Valid commands: list, create, update, complete, delete. Use --help for details.", subcmd]);
    noff_emit_json(stdout_fd, err, compact, quiet);
    return NOFF_EXIT_INVALID_ARGS;
}

void reminders_offload_register(void) {
    int err = native_offload_add_handler("apple-reminders", reminders_handler);
    if (err == 0) {
        noff_ensure_guest_stub("/usr/local/bin/apple-reminders");
        NSLog(@"NativeOffloads: apple-reminders handler registered");
    } else {
        NSLog(@"NativeOffloads: failed to register apple-reminders handler (err=%d)", err);
    }
}
