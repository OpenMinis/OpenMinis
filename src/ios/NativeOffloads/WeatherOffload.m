//
//  WeatherOffload.m
//  MinisApp
//
//  Native offload handler for `apple-weather`.
//  Subcommands: current, hourly, daily, alerts, report
//
//  WeatherKit is a Swift-only framework. This ObjC handler bridges to it
//  via a minimal Swift helper class (WeatherOffloadBridge) that performs
//  the async WeatherKit calls and returns NSDictionary results.
//
//  Since WeatherKit has no ObjC API, we use the CLLocationManager to get
//  location, then call the Swift bridge for weather data.
//

#import <Foundation/Foundation.h>
#import <CoreLocation/CoreLocation.h>
#import "NativeOffloadUtils.h"
#import "Minis-Swift.h"
#include "kernel/native_offload.h"
#include <unistd.h>

static NSString *const TOOL_NAME = @"apple-weather";

static NSString *const HELP_TEXT =
    @"apple-weather - Query weather data via WeatherKit\n"
     "\n"
     "USAGE:\n"
     "  apple-weather [command] [options]\n"
     "\n"
     "COMMANDS:\n"
     "  current    Current weather conditions (default when no command given)\n"
     "  hourly     Hourly forecast\n"
     "  minute     Minute-by-minute precipitation for the next hour\n"
     "  daily      Daily forecast\n"
     "  alerts     Weather alerts\n"
     "  report     Combined report (current + hourly + daily + alerts)\n"
     "\n"
     "OPTIONS:\n"
     "  --help, -h          Show this help message\n"
     "  --compact           Minimize JSON output\n"
     "  -q, --quiet         Output only data field\n"
     "  --lat <latitude>    Location latitude (default: current location)\n"
     "  --lng <longitude>   Location longitude\n"
     "  --hours <N>         Number of hourly forecasts (default: 24)\n"
     "  --minutes <N>       Number of minute entries for `minute` (default: 60)\n"
     "  --days <N>          Number of daily forecasts (default: 7)\n"
     "\n"
     "FIELD NOTES:\n"
     "  precip_chance is a PROBABILITY (0-1), not an amount. For how much rain\n"
     "  is expected, use precip_amount_mm (per forecast interval), present on\n"
     "  `hourly` and `daily`. wind_gust_kmh is the peak gust and is omitted\n"
     "  when WeatherKit reports no gust data for that interval.\n"
     "\n"
     "MINUTE NOTES:\n"
     "  Returns `available: false` with a `reason` when WeatherKit has no\n"
     "  minute-by-minute data — it covers only some regions (NOT mainland\n"
     "  China) and is also omitted when no precipitation is expected. That is\n"
     "  distinct from an empty list, so \"no coverage\" is never reported as\n"
     "  \"no rain\". Each entry has precip_chance (0-1) and\n"
     "  precip_intensity_mmh (mm/hour).\n"
     "\n"
     "EXAMPLES:\n"
     "  apple-weather                  (same as: apple-weather current)\n"
     "  apple-weather hourly --hours 12 --compact -q\n"
     "  apple-weather minute                 (is it about to rain?)\n"
     "  apple-weather minute --minutes 15 --lat 51.5074 --lng -0.1278\n"
     "  apple-weather report --lat 37.3349 --lng -122.0090\n"
     "  apple-weather daily --days 10\n"
     "  apple-weather alerts\n";

// ── Get location for weather (reuse pattern from LocationOffload) ──

@interface NoffWeatherLocationDelegate : NSObject <CLLocationManagerDelegate>
@property (nonatomic, strong) CLLocation *location;
@property (nonatomic, strong) NSError *error;
@property (nonatomic, strong) dispatch_semaphore_t semaphore;
@end

@implementation NoffWeatherLocationDelegate
- (instancetype)init {
    self = [super init];
    if (self) _semaphore = dispatch_semaphore_create(0);
    return self;
}
- (void)locationManager:(CLLocationManager *)manager didUpdateLocations:(NSArray<CLLocation *> *)locations {
    self.location = locations.lastObject;
    dispatch_semaphore_signal(self.semaphore);
}
- (void)locationManager:(CLLocationManager *)manager didFailWithError:(NSError *)error {
    self.error = error;
    dispatch_semaphore_signal(self.semaphore);
}
@end

static CLLocation *get_location_sync(int argc, char **argv) {
    NSString *latStr = noff_find_arg(argc, argv, "--lat");
    NSString *lngStr = noff_find_arg(argc, argv, "--lng");

    if (latStr && lngStr) {
        return [[CLLocation alloc] initWithLatitude:[latStr doubleValue]
                                          longitude:[lngStr doubleValue]];
    }

    // Get current location
    __block NoffWeatherLocationDelegate *delegate = [[NoffWeatherLocationDelegate alloc] init];

    dispatch_async(dispatch_get_main_queue(), ^{
        CLLocationManager *manager = [[CLLocationManager alloc] init];
        manager.delegate = delegate;
        manager.desiredAccuracy = kCLLocationAccuracyKilometer;

        CLAuthorizationStatus status = manager.authorizationStatus;
        if (status == kCLAuthorizationStatusNotDetermined) {
            [manager requestWhenInUseAuthorization];
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC),
                           dispatch_get_main_queue(), ^{
                [manager requestLocation];
            });
        } else if (status == kCLAuthorizationStatusAuthorizedWhenInUse ||
                   status == kCLAuthorizationStatusAuthorizedAlways) {
            [manager requestLocation];
        } else {
            delegate.error = [NSError errorWithDomain:@"NativeOffload" code:3
                               userInfo:@{NSLocalizedDescriptionKey:
                                   @"Location access denied. To grant access, open "
                                    "Settings > Privacy & Security > Location Services "
                                    "and enable Minis."}];
            dispatch_semaphore_signal(delegate.semaphore);
        }
    });

    dispatch_semaphore_wait(delegate.semaphore,
                            dispatch_time(DISPATCH_TIME_NOW, 15 * NSEC_PER_SEC));
    return delegate.location;
}

static int weather_handler(int argc, char **argv,
                            int stdin_fd, int stdout_fd, int stderr_fd) {
    if (noff_has_flag(argc, argv, "--help") || noff_has_flag(argc, argv, "-h")) {
        noff_emit_help(stderr_fd, HELP_TEXT);
        return NOFF_EXIT_SUCCESS;
    }

    BOOL compact = noff_has_flag(argc, argv, "--compact");
    BOOL quiet = noff_has_flag(argc, argv, "-q") || noff_has_flag(argc, argv, "--quiet");

    NSString *subcmd = noff_get_subcommand(argc, argv);
    if (!subcmd) {
        // [T-offload-defaults-batch-ios] Bare `apple-weather` (including
        // flags-only invocations) defaults to `current` — same pattern as
        // apple-location: the common intent is "what's the weather now".
        subcmd = @"current";
    }

    // Validate subcommand
    NSSet *validCmds = [NSSet setWithArray:@[@"current", @"hourly", @"minute", @"daily", @"alerts", @"report"]];
    if (![validCmds containsObject:subcmd]) {
        noff_emit_help(stderr_fd, HELP_TEXT);
        NSDictionary *err = noff_json_error(TOOL_NAME, subcmd,
                                             NOFF_ERR_INVALID_ARGS,
                                             [NSString stringWithFormat:@"Unknown command '%@'. Valid commands: current, hourly, minute, daily, alerts, report. Use --help for details.", subcmd]);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }

    // Get location
    CLLocation *loc = get_location_sync(argc, argv);
    if (!loc) {
        NSDictionary *err = noff_json_error(TOOL_NAME, subcmd,
                                             NOFF_ERR_NOT_AVAILABLE,
                                             @"Could not determine location. Use --lat/--lng or enable location services.");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_NOT_AVAILABLE;
    }

    // Call Swift bridge for weather data
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    __block NSDictionary *weatherData = nil;
    __block NSError *weatherError = nil;

    [WeatherOffloadBridge fetchWeatherForLatitude:loc.coordinate.latitude
                                       longitude:loc.coordinate.longitude
                                      completion:^(NSDictionary *result, NSError *error) {
        weatherData = result;
        weatherError = error;
        dispatch_semaphore_signal(sem);
    }];

    dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 30 * NSEC_PER_SEC));

    if (weatherError || !weatherData) {
        NSDictionary *err = noff_json_error(TOOL_NAME, subcmd,
                                             NOFF_ERR_NOT_AVAILABLE,
                                             weatherError.localizedDescription ?: @"WeatherKit request failed or timed out");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_NOT_AVAILABLE;
    }

    // Extract requested section
    id data;
    NSString *hoursStr = noff_find_arg(argc, argv, "--hours");
    NSString *daysStr = noff_find_arg(argc, argv, "--days");
    NSInteger maxHours = hoursStr ? [hoursStr integerValue] : 24;
    NSInteger maxDays = daysStr ? [daysStr integerValue] : 7;

    if ([subcmd isEqualToString:@"current"]) {
        data = weatherData[@"current"] ?: @{};
    } else if ([subcmd isEqualToString:@"hourly"]) {
        NSArray *hourly = weatherData[@"hourly"];
        if (hourly && (NSInteger)hourly.count > maxHours) {
            hourly = [hourly subarrayWithRange:NSMakeRange(0, maxHours)];
        }
        data = @{@"hourly": hourly ?: @[], @"count": @([(hourly ?: @[]) count])};
    } else if ([subcmd isEqualToString:@"minute"]) {
        // [T-weather-minute-precip] Passed through as the bridge built it —
        // including `available: false` + `reason` where WeatherKit has no
        // minute data — so the caller can tell "no rain" from "no coverage".
        // --minutes trims the list the way --hours/--days do elsewhere.
        NSDictionary *minute = weatherData[@"minute"];
        NSString *minsStr = noff_find_arg(argc, argv, "--minutes");
        NSInteger maxMinutes = minsStr ? [minsStr integerValue] : 60;
        if (maxMinutes < 1) maxMinutes = 60;
        NSMutableDictionary *m = [(minute ?: @{}) mutableCopy];
        NSArray *minutes = m[@"minutes"];
        if (minutes && (NSInteger)minutes.count > maxMinutes) {
            minutes = [minutes subarrayWithRange:NSMakeRange(0, maxMinutes)];
            m[@"minutes"] = minutes;
        }
        m[@"count"] = @([(minutes ?: @[]) count]);
        data = m;
    } else if ([subcmd isEqualToString:@"daily"]) {
        NSArray *daily = weatherData[@"daily"];
        if (daily && (NSInteger)daily.count > maxDays) {
            daily = [daily subarrayWithRange:NSMakeRange(0, maxDays)];
        }
        data = @{@"daily": daily ?: @[], @"count": @([(daily ?: @[]) count])};
    } else if ([subcmd isEqualToString:@"alerts"]) {
        data = @{@"alerts": weatherData[@"alerts"] ?: @[],
                 @"count": @([(weatherData[@"alerts"] ?: @[]) count])};
    } else { // report
        NSMutableDictionary *report = [NSMutableDictionary dictionary];
        report[@"current"] = weatherData[@"current"] ?: @{};

        NSArray *hourly = weatherData[@"hourly"];
        if (hourly && (NSInteger)hourly.count > maxHours) {
            hourly = [hourly subarrayWithRange:NSMakeRange(0, maxHours)];
        }
        report[@"hourly"] = hourly ?: @[];

        NSArray *daily = weatherData[@"daily"];
        if (daily && (NSInteger)daily.count > maxDays) {
            daily = [daily subarrayWithRange:NSMakeRange(0, maxDays)];
        }
        report[@"daily"] = daily ?: @[];
        report[@"alerts"] = weatherData[@"alerts"] ?: @[];
        report[@"location"] = @{
            @"latitude": @(loc.coordinate.latitude),
            @"longitude": @(loc.coordinate.longitude),
        };
        data = report;
    }

    noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, subcmd, data), compact, quiet);
    return NOFF_EXIT_SUCCESS;
}

void weather_offload_register(void) {
    int err = native_offload_add_handler("apple-weather", weather_handler);
    if (err == 0) {
        noff_ensure_guest_stub("/usr/local/bin/apple-weather");
        NSLog(@"NativeOffloads: apple-weather handler registered");
    } else {
        NSLog(@"NativeOffloads: failed to register apple-weather handler (err=%d)", err);
    }
}
