import SwiftUI

// MARK: - Alarm Item

struct AlarmItem: Identifiable {
    let id: String
    let label: String
    let scheduleType: String  // "fixed", "relative", "timer"
    let fireDate: Date?
    let relativeTime: String? // "HH:mm" for relative
    let countdownSeconds: Int?
    let state: String
    let repeatDays: [String]? // e.g. ["Mon", "Tue", ...]

    init(dict: [String: Any]) {
        self.id = dict["id"] as? String ?? UUID().uuidString
        self.label = dict["label"] as? String ?? ""
        let rawType = dict["schedule_type"] as? String
        // Infer type from available fields if schedule_type is missing
        if let rawType {
            self.scheduleType = rawType
        } else if dict["countdown_seconds"] != nil {
            self.scheduleType = "timer"
        } else {
            self.scheduleType = "fixed"
        }
        if let iso = dict["fire_date"] as? String {
            let f = ISO8601DateFormatter()
            f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            self.fireDate = f.date(from: iso) ?? ISO8601DateFormatter().date(from: iso)
        } else {
            self.fireDate = nil
        }
        self.relativeTime = dict["time"] as? String
        self.countdownSeconds = dict["countdown_seconds"] as? Int
        self.state = dict["state"] as? String ?? "unknown"
        self.repeatDays = dict["repeat_days"] as? [String]
    }

    var displayTime: String {
        switch scheduleType {
        case "fixed":
            guard let d = fireDate else { return "--:--" }
            let f = DateFormatter()
            f.dateFormat = "HH:mm"
            return f.string(from: d)
        case "relative":
            return relativeTime ?? "--:--"
        case "timer":
            guard let secs = countdownSeconds, secs >= 0 else { return "0:00" }
            let h = secs / 3600
            let m = (secs % 3600) / 60
            let s = secs % 60
            if h > 0 {
                return String(format: "%d:%02d:%02d", h, m, s)
            }
            return String(format: "%02d:%02d", m, s)
        default:
            return "--:--"
        }
    }

    var subtitle: String {
        switch scheduleType {
        case "fixed":
            guard let d = fireDate else { return "" }
            let f = DateFormatter()
            f.dateStyle = .medium
            f.timeStyle = .none
            return f.string(from: d)
        case "relative":
            return "Daily"
        case "timer":
            return "Timer"
        default:
            return ""
        }
    }

    /// Short weekday label for display on the right side of the row.
    var weekdayLabel: String? {
        let allDays = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]
        let weekdays = ["Mon", "Tue", "Wed", "Thu", "Fri"]

        if scheduleType == "relative", let days = repeatDays, !days.isEmpty {
            if days.count == 7 {
                return "Everyday"
            } else if days == weekdays {
                return "Weekdays"
            } else if days == ["Sat", "Sun"] || days == ["Sun", "Sat"] {
                return "Weekends"
            } else if days.count == 1 {
                return days[0]
            } else {
                // Show abbreviated: Mon, Wed, Fri
                let ordered = allDays.filter { days.contains($0) }
                return ordered.joined(separator: ", ")
            }
        } else if scheduleType == "fixed", let d = fireDate {
            let f = DateFormatter()
            f.dateFormat = "EEE"
            return f.string(from: d)
        }
        return nil
    }
}

// MARK: - View Model

class AlarmListViewModel: ObservableObject {
    @Published var alarms: [AlarmItem] = []
    @Published var isLoading = false
    @Published var error: String?

    func load() {
        // [T-visionos-alarmkit-launch] See ContentView.fetchAlarmsIfNeeded:
        // @available passes on visionOS, so the framework probe must gate
        // every entry into AlarmOffloadBridge, not only the shell command.
        guard #available(iOS 26.0, *), alarmkit_is_usable() else { return }
        isLoading = true
        _loadImpl()
    }

    @available(iOS 26.0, *)
    private func _loadImpl() {
        AlarmOffloadBridge.listAlarms { [weak self] arr, err in
            DispatchQueue.main.async {
                self?.isLoading = false
                if let err {
                    self?.error = err.localizedDescription
                    return
                }
                guard let dicts = arr as? [[String: Any]] else {
                    self?.alarms = []
                    return
                }
                self?.alarms = dicts.map { AlarmItem(dict: $0) }
            }
        }
    }

    func delete(id: String) {
        alarms.removeAll { $0.id == id }
        guard #available(iOS 26.0, *), alarmkit_is_usable() else { return }
        _cancelImpl(id: id)
    }

    @available(iOS 26.0, *)
    private func _cancelImpl(id: String) {
        AlarmOffloadBridge.cancelAlarm(withId: id) { _, _ in }
    }

    func clearAll() {
        let ids = alarms.map { $0.id }
        alarms.removeAll()
        guard #available(iOS 26.0, *), alarmkit_is_usable() else { return }
        _clearAllImpl(ids: ids)
    }

    @available(iOS 26.0, *)
    private func _clearAllImpl(ids: [String]) {
        for id in ids {
            AlarmOffloadBridge.cancelAlarm(withId: id) { _, _ in }
        }
    }

    /// Group alarms by week for sectioned display.
    var groupedByWeek: [(title: String, alarms: [AlarmItem])] {
        let calendar = Calendar.current
        let now = Date()
        let startOfThisWeek = calendar.dateInterval(of: .weekOfYear, for: now)?.start ?? now

        // Partition into alarms with a date and those without
        var dated: [AlarmItem] = []
        var undated: [AlarmItem] = []
        for alarm in alarms {
            if alarm.fireDate != nil {
                dated.append(alarm)
            } else {
                undated.append(alarm)
            }
        }

        // Sort dated alarms by fire date
        dated.sort { ($0.fireDate ?? .distantFuture) < ($1.fireDate ?? .distantFuture) }

        // Group by week
        var weekBuckets: [Date: [AlarmItem]] = [:]
        for alarm in dated {
            guard let date = alarm.fireDate,
                  let weekStart = calendar.dateInterval(of: .weekOfYear, for: date)?.start else { continue }
            weekBuckets[weekStart, default: []].append(alarm)
        }

        // Build sorted sections
        let sortedWeeks = weekBuckets.keys.sorted()
        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "M/d"

        var sections: [(title: String, alarms: [AlarmItem])] = []

        // Repeating / timer alarms first
        if !undated.isEmpty {
            sections.append((title: "Repeating & Timers", alarms: undated))
        }

        for weekStart in sortedWeeks {
            guard let items = weekBuckets[weekStart] else { continue }
            let weekEnd = calendar.date(byAdding: .day, value: 6, to: weekStart) ?? weekStart
            let title: String
            if weekStart == startOfThisWeek {
                title = "This Week"
            } else if weekStart == calendar.date(byAdding: .weekOfYear, value: 1, to: startOfThisWeek) {
                title = "Next Week"
            } else {
                title = "\(dateFormatter.string(from: weekStart)) – \(dateFormatter.string(from: weekEnd))"
            }
            sections.append((title: title, alarms: items))
        }

        return sections
    }
}

// MARK: - Alarm List View

struct AlarmListView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = AlarmListViewModel()
    @State private var showClearConfirm = false

    var body: some View {
        NavigationStack {
            Group {
                if vm.alarms.isEmpty && !vm.isLoading {
                    VStack(spacing: 12) {
                        Image(systemName: "alarm")
                            .font(.system(size: 48, weight: .thin))
                            .foregroundStyle(.secondary)
                        Text("No Alarms")
                            .font(.title3)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        let sections = vm.groupedByWeek
                        ForEach(Array(sections.enumerated()), id: \.offset) { _, section in
                            Section(section.title) {
                                ForEach(section.alarms) { alarm in
                                    AlarmRowView(alarm: alarm)
                                }
                                .onDelete { indexSet in
                                    for idx in indexSet {
                                        let alarm = section.alarms[idx]
                                        vm.delete(id: alarm.id)
                                    }
                                }
                            }
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("Alarms")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Done") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(role: .destructive) {
                        showClearConfirm = true
                    } label: {
                        Text("Clear All")
                    }
                    .disabled(vm.alarms.isEmpty)
                    .opacity(vm.alarms.isEmpty ? 0 : 1)
                }
            }
            .alert("Clear All Alarms?", isPresented: $showClearConfirm) {
                Button("Clear All", role: .destructive) {
                    vm.clearAll()
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("All \(vm.alarms.count) alarm(s) will be removed. This cannot be undone.")
            }
            .onAppear { vm.load() }
        }
    }
}

// MARK: - Alarm Row

private struct AlarmRowView: View {
    let alarm: AlarmItem

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(alarm.displayTime)
                    .font(.system(size: 34, weight: .thin, design: .default))
                    .monospacedDigit()
                if !alarm.label.isEmpty {
                    Text(alarm.label)
                        .font(.subheadline)
                        .foregroundStyle(.primary)
                }
                if !alarm.subtitle.isEmpty {
                    Text(alarm.subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                stateIndicator
                if let day = alarm.weekdayLabel {
                    Text(day)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private var stateIndicator: some View {
        switch alarm.state {
        case "alerting":
            if #available(iOS 17.0, *) {
                Image(systemName: "bell.fill")
                    .foregroundStyle(.orange)
                    .symbolEffect(.pulse)
            } else {
                Image(systemName: "bell.fill")
                    .foregroundStyle(.orange)
            }
        case "countdown":
            Image(systemName: "timer")
                .foregroundStyle(.blue)
        default:
            EmptyView()
        }
    }
}
