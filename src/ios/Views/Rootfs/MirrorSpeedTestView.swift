//
//  MirrorSpeedTestView.swift
//  MinisApp
//
//  Auto-detect fastest mirrors for Alpine APK, Python pip, and Node.js npm.
//

import SwiftUI

// MARK: - Data Model

enum MirrorCategory: String, CaseIterable, Identifiable {
    case alpine, pip, npm

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .alpine: return "Alpine APK"
        case .pip: return "Python pip"
        case .npm: return "Node.js npm"
        }
    }

    var systemImage: String {
        switch self {
        case .alpine: return "mountain.2"
        case .pip: return "shippingbox"
        case .npm: return "cube"
        }
    }

    var iconColor: Color {
        switch self {
        case .alpine: return .blue
        case .pip: return .green
        case .npm: return .red
        }
    }

    /// Relative path inside rootfs data/ for the config file.
    var configPath: String {
        switch self {
        case .alpine: return "etc/apk/repositories"
        case .pip: return "etc/pip/pip.conf"
        case .npm: return "root/.npmrc"
        }
    }
}

struct MirrorEntry: Identifiable, Hashable {
    let id: String          // unique key e.g. "alpine.tuna"
    let name: String
    let baseURL: String     // e.g. "https://mirrors.tuna.tsinghua.edu.cn/alpine/"
    let testURL: String     // full URL for HEAD request
    let category: MirrorCategory
    let region: String      // "China", "Europe", "Asia", "Global"
    let isOfficial: Bool

    func hash(into hasher: inout Hasher) { hasher.combine(id) }
    static func == (lhs: MirrorEntry, rhs: MirrorEntry) -> Bool { lhs.id == rhs.id }
}

struct MirrorTestResult: Identifiable {
    let id = UUID()
    let mirror: MirrorEntry
    let latencyMs: Int?     // nil = failed/timeout
    let error: String?

    var isSuccess: Bool { latencyMs != nil }
}

// MARK: - Mirror Definitions

extension MirrorEntry {
    static let allMirrors: [MirrorEntry] = alpineMirrors + pipMirrors + npmMirrors

    // MARK: Alpine APK
    private static let alpineMirrors: [MirrorEntry] = {
        let base = "v3.21/main/aarch64/APKINDEX.tar.gz"
        func m(_ id: String, _ name: String, _ url: String, _ region: String, official: Bool = false) -> MirrorEntry {
            MirrorEntry(id: "alpine.\(id)", name: name, baseURL: url, testURL: url + base, category: .alpine, region: region, isOfficial: official)
        }
        return [
            m("official", "Official CDN", "https://dl-cdn.alpinelinux.org/alpine/", "Global", official: true),
            m("tuna", "Tsinghua TUNA", "https://mirrors.tuna.tsinghua.edu.cn/alpine/", "China"),
            m("aliyun", "Alibaba", "https://mirrors.aliyun.com/alpine/", "China"),
            m("ustc", "USTC", "https://mirrors.ustc.edu.cn/alpine/", "China"),
            m("huawei", "Huawei", "https://repo.huaweicloud.com/alpine/", "China"),
            m("tencent", "Tencent", "https://mirrors.cloud.tencent.com/alpine/", "China"),
            m("leaseweb", "LEASEWEB UK", "https://mirror.leaseweb.com/alpine/", "Europe"),
            m("rwth", "RWTH Germany", "https://ftp.halifax.rwth-aachen.de/alpine/", "Europe"),
            m("jaist", "JAIST Japan", "https://ftp.jaist.ac.jp/pub/Linux/alpine/", "Asia"),
            m("kakao", "Kakao Korea", "https://mirror.kakao.com/alpine/", "Asia"),
        ]
    }()

    // MARK: Python pip
    private static let pipMirrors: [MirrorEntry] = {
        func m(_ id: String, _ name: String, _ url: String, _ region: String, official: Bool = false) -> MirrorEntry {
            MirrorEntry(id: "pip.\(id)", name: name, baseURL: url, testURL: url, category: .pip, region: region, isOfficial: official)
        }
        return [
            m("official", "Official PyPI", "https://pypi.org/simple/", "Global", official: true),
            m("tuna", "Tsinghua TUNA", "https://pypi.tuna.tsinghua.edu.cn/simple/", "China"),
            m("aliyun", "Alibaba", "https://mirrors.aliyun.com/pypi/simple/", "China"),
            m("ustc", "USTC", "https://mirrors.ustc.edu.cn/pypi/web/simple/", "China"),
            m("huawei", "Huawei", "https://repo.huaweicloud.com/repository/pypi/simple/", "China"),
            m("tencent", "Tencent", "https://mirrors.cloud.tencent.com/pypi/simple/", "China"),
        ]
    }()

    // MARK: Node.js npm
    private static let npmMirrors: [MirrorEntry] = {
        func m(_ id: String, _ name: String, _ url: String, _ region: String, official: Bool = false) -> MirrorEntry {
            MirrorEntry(id: "npm.\(id)", name: name, baseURL: url, testURL: url, category: .npm, region: region, isOfficial: official)
        }
        return [
            m("official", "Official npm", "https://registry.npmjs.org/", "Global", official: true),
            m("npmmirror", "npmmirror", "https://registry.npmmirror.com/", "China"),
            m("huawei", "Huawei", "https://repo.huaweicloud.com/repository/npm/", "China"),
            m("tencent", "Tencent", "https://mirrors.cloud.tencent.com/npm/", "China"),
        ]
    }()

    static func mirrors(for category: MirrorCategory) -> [MirrorEntry] {
        allMirrors.filter { $0.category == category }
    }

    static func find(baseURL: String) -> MirrorEntry? {
        allMirrors.first { $0.baseURL == baseURL }
    }
}

// MARK: - ViewModel

private let logger = AppLogger(category: "MirrorSpeedTest")

@MainActor
final class MirrorSpeedTestViewModel: ObservableObject {
    static let shared = MirrorSpeedTestViewModel()

    // In-memory test results (not persisted)
    @Published var results: [MirrorCategory: [MirrorTestResult]] = [:]
    @Published var isTesting = false
    @Published var testProgress: Double = 0

    // Persisted selections
    @Published var selectedMirrorId: [MirrorCategory: String] = [:]   // category → mirror.id
    @Published var useCustomMirror: [MirrorCategory: Bool] = [:]      // category → enabled

    private var testTask: Task<Void, Never>?

    private init() {
        loadPersistedSelections()
    }

    // MARK: - Persistence

    private func loadPersistedSelections() {
        let ud = UserDefaults.standard
        for cat in MirrorCategory.allCases {
            if let url = ud.string(forKey: "mirror.selected.\(cat.rawValue)"),
               let entry = MirrorEntry.find(baseURL: url) {
                selectedMirrorId[cat] = entry.id
            }
            useCustomMirror[cat] = ud.bool(forKey: "mirror.useCustom.\(cat.rawValue)")
        }
    }

    private func persistSelection(for category: MirrorCategory) {
        let ud = UserDefaults.standard
        if let mirrorId = selectedMirrorId[category],
           let entry = MirrorEntry.allMirrors.first(where: { $0.id == mirrorId }) {
            ud.set(entry.baseURL, forKey: "mirror.selected.\(category.rawValue)")
        }
        ud.set(useCustomMirror[category] == true, forKey: "mirror.useCustom.\(category.rawValue)")
    }

    // MARK: - Auto-Detect on First Use

    /// Run mirror speed test once after a fresh rootfs install, pick the fastest, and apply.
    /// Triggers when `rootfs.freshInstall` flag is set (by RootfsManager.installIfNeeded).
    /// Safe to call multiple times — only fires once per fresh install.
    func autoDetectOnceIfNeeded() {
        let ud = UserDefaults.standard
        guard ud.bool(forKey: "rootfs.freshInstall") else { return }
        ud.removeObject(forKey: "rootfs.freshInstall")
        logger.info("Fresh rootfs detected — running auto mirror detection")

        Task {
            // Reuse the normal test flow
            await runAllTestsAndWait()

            // Enable custom mirror and apply for every category that got a successful result
            for cat in MirrorCategory.allCases {
                guard let fastest = results[cat]?.first(where: { $0.isSuccess }),
                      !fastest.mirror.isOfficial else { continue }
                // Only apply if the fastest is NOT the official — otherwise no benefit
                selectedMirrorId[cat] = fastest.mirror.id
                useCustomMirror[cat] = true
                persistSelection(for: cat)
                applyMirror(for: cat)
            }
            logger.info("Auto mirror detection applied")
        }
    }

    /// Run all tests and wait for completion (used by autoDetect).
    private func runAllTestsAndWait() async {
        await withCheckedContinuation { continuation in
            runAllTests()
            // Observe isTesting to know when done
            Task {
                // Wait for the test task to finish
                await testTask?.value
                continuation.resume()
            }
        }
    }

    // MARK: - Speed Test

    func runAllTests() {
        testTask?.cancel()
        testTask = Task {
            isTesting = true
            testProgress = 0
            results = [:]

            let allMirrors = MirrorEntry.allMirrors
            let total = allMirrors.count
            var completed = 0
            var categoryResults: [MirrorCategory: [MirrorTestResult]] = [:]

            await withTaskGroup(of: MirrorTestResult.self) { group in
                for mirror in allMirrors {
                    group.addTask { [weak self] in
                        await self?.testMirror(mirror) ?? MirrorTestResult(mirror: mirror, latencyMs: nil, error: "Cancelled")
                    }
                }
                for await result in group {
                    guard !Task.isCancelled else { break }
                    completed += 1
                    categoryResults[result.mirror.category, default: []].append(result)
                    testProgress = Double(completed) / Double(total)
                }
            }

            guard !Task.isCancelled else {
                isTesting = false
                return
            }

            // Sort: successful by latency, then failed at end
            for (cat, list) in categoryResults {
                categoryResults[cat] = list.sorted {
                    switch ($0.latencyMs, $1.latencyMs) {
                    case let (a?, b?): return a < b
                    case (_?, nil): return true
                    case (nil, _?): return false
                    case (nil, nil): return false
                    }
                }
            }

            results = categoryResults

            // Auto-select fastest for categories without prior selection
            for cat in MirrorCategory.allCases {
                if selectedMirrorId[cat] == nil,
                   let fastest = categoryResults[cat]?.first(where: { $0.isSuccess }) {
                    selectedMirrorId[cat] = fastest.mirror.id
                    persistSelection(for: cat)
                }
            }

            isTesting = false
            logger.info("Mirror speed test complete: \(categoryResults.mapValues { $0.count })")
        }
    }

    func runTest(for category: MirrorCategory) {
        testTask?.cancel()
        testTask = Task {
            isTesting = true
            testProgress = 0

            let mirrors = MirrorEntry.mirrors(for: category)
            let total = mirrors.count
            var completed = 0
            var list: [MirrorTestResult] = []

            await withTaskGroup(of: MirrorTestResult.self) { group in
                for mirror in mirrors {
                    group.addTask { [weak self] in
                        await self?.testMirror(mirror) ?? MirrorTestResult(mirror: mirror, latencyMs: nil, error: "Cancelled")
                    }
                }
                for await result in group {
                    guard !Task.isCancelled else { break }
                    completed += 1
                    list.append(result)
                    testProgress = Double(completed) / Double(total)
                }
            }

            guard !Task.isCancelled else {
                isTesting = false
                return
            }

            list.sort {
                switch ($0.latencyMs, $1.latencyMs) {
                case let (a?, b?): return a < b
                case (_?, nil): return true
                case (nil, _?): return false
                case (nil, nil): return false
                }
            }

            results[category] = list
            isTesting = false
        }
    }

    private nonisolated func testMirror(_ mirror: MirrorEntry) async -> MirrorTestResult {
        guard let url = URL(string: mirror.testURL) else {
            return MirrorTestResult(mirror: mirror, latencyMs: nil, error: "Invalid URL")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "HEAD"
        request.timeoutInterval = 8

        let start = CFAbsoluteTimeGetCurrent()
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            let elapsed = CFAbsoluteTimeGetCurrent() - start
            let ms = Int(elapsed * 1000)
            let httpStatus = (response as? HTTPURLResponse)?.statusCode ?? 0
            if httpStatus >= 400 {
                return MirrorTestResult(mirror: mirror, latencyMs: nil, error: "HTTP \(httpStatus)")
            }
            return MirrorTestResult(mirror: mirror, latencyMs: ms, error: nil)
        } catch {
            return MirrorTestResult(mirror: mirror, latencyMs: nil, error: error.localizedDescription)
        }
    }

    // MARK: - Mirror Selection & Application

    func selectMirror(_ mirror: MirrorEntry) {
        selectedMirrorId[mirror.category] = mirror.id
        persistSelection(for: mirror.category)
    }

    func setUseCustom(_ enabled: Bool, for category: MirrorCategory) {
        useCustomMirror[category] = enabled
        persistSelection(for: category)
        if enabled {
            applyMirror(for: category)
        } else {
            restoreOfficial(for: category)
        }
    }

    /// Apply selected mirror to the rootfs config file. Backs up original as .bak first.
    func applyMirror(for category: MirrorCategory) {
        guard let mirrorId = selectedMirrorId[category],
              let mirror = MirrorEntry.allMirrors.first(where: { $0.id == mirrorId }) else { return }

        let dataPath = RootfsManager.shared.dataPath
        let configURL = dataPath.appendingPathComponent(category.configPath)
        let bakURL = dataPath.appendingPathComponent(category.configPath + ".bak")

        let fm = FileManager.default

        // Backup original if .bak doesn't exist yet
        if !fm.fileExists(atPath: bakURL.path), fm.fileExists(atPath: configURL.path) {
            do {
                try fm.copyItem(at: configURL, to: bakURL)
                logger.info("Backed up \(category.configPath) → .bak")
            } catch {
                logger.error("Failed to backup \(category.configPath): \(error)")
            }
        }

        // Write new config
        let content: String
        switch category {
        case .alpine:
            content = "\(mirror.baseURL)v3.21/main\n\(mirror.baseURL)v3.21/community\n"
        case .pip:
            let host = URL(string: mirror.baseURL)?.host ?? ""
            content = """
            [global]
            break-system-packages = true
            index-url = \(mirror.baseURL)
            trusted-host = \(host)

            """
        case .npm:
            content = "registry=\(mirror.baseURL)\n"
        }

        do {
            try fm.createDirectory(at: configURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try content.write(to: configURL, atomically: true, encoding: .utf8)
            logger.info("Applied mirror \(mirror.name) to \(category.configPath)")
        } catch {
            logger.error("Failed to write \(category.configPath): \(error)")
        }
    }

    /// Restore original config from .bak file.
    func restoreOfficial(for category: MirrorCategory) {
        let dataPath = RootfsManager.shared.dataPath
        let configURL = dataPath.appendingPathComponent(category.configPath)
        let bakURL = dataPath.appendingPathComponent(category.configPath + ".bak")

        let fm = FileManager.default
        guard fm.fileExists(atPath: bakURL.path) else {
            logger.info("No .bak for \(category.configPath), skipping restore")
            return
        }

        do {
            if fm.fileExists(atPath: configURL.path) {
                try fm.removeItem(at: configURL)
            }
            try fm.copyItem(at: bakURL, to: configURL)
            logger.info("Restored \(category.configPath) from .bak")
        } catch {
            logger.error("Failed to restore \(category.configPath): \(error)")
        }
    }

    // MARK: - Helpers

    func selectedMirror(for category: MirrorCategory) -> MirrorEntry? {
        guard let id = selectedMirrorId[category] else { return nil }
        return MirrorEntry.allMirrors.first { $0.id == id }
    }

    func fastestResult(for category: MirrorCategory) -> MirrorTestResult? {
        results[category]?.first { $0.isSuccess }
    }

    func isActive(for category: MirrorCategory) -> Bool {
        useCustomMirror[category] == true && selectedMirrorId[category] != nil
    }
}

// MARK: - Views

/// Section content to embed in RootfsManagementView.
struct MirrorsSectionView: View {
    @ObservedObject private var vm = MirrorSpeedTestViewModel.shared

    var body: some View {
        Section(AppLocalized("Mirrors")) {
            Button {
                vm.runAllTests()
            } label: {
                Label {
                    Text(AppLocalized("Detect Fast Mirrors"))
                } icon: {
                    Image(systemName: "bolt.fill")
                        .font(.system(size: 9))
                        .foregroundStyle(.white)
                        .frame(width: 21, height: 21)
                        .background(.orange, in: Circle())
                }
            }
            .disabled(vm.isTesting)

            if vm.isTesting {
                ProgressView(value: vm.testProgress)
                    .animation(.default, value: vm.testProgress)
            }

            ForEach(MirrorCategory.allCases) { category in
                NavigationLink {
                    MirrorCategoryDetailView(category: category)
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: category.systemImage)
                            .font(.system(size: 9))
                            .foregroundStyle(.white)
                            .frame(width: 21, height: 21)
                            .background(category.iconColor, in: Circle())
                        Text(category.displayName)
                        Spacer()
                        if vm.isActive(for: category),
                           let selected = vm.selectedMirror(for: category) {
                            Text(selected.name)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        if let fastest = vm.fastestResult(for: category) {
                            latencyBadge(fastest.latencyMs)
                        }
                    }
                }
            }
        }
    }

    private func latencyBadge(_ ms: Int?) -> some View {
        Group {
            if let ms {
                Text("\(ms)ms")
                    .font(.caption.monospaced())
                    .foregroundStyle(ms < 200 ? .green : ms < 500 ? .orange : .red)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background((ms < 200 ? Color.green : ms < 500 ? Color.orange : Color.red).opacity(0.12))
                    .clipShape(Capsule())
            }
        }
    }
}

/// Detail view for a single mirror category — shows all test results, allows selection.
struct MirrorCategoryDetailView: View {
    let category: MirrorCategory
    @ObservedObject private var vm = MirrorSpeedTestViewModel.shared

    private var sortedResults: [MirrorTestResult] {
        vm.results[category] ?? []
    }

    var body: some View {
        List {
            Section(AppLocalized("Current")) {
                Toggle(AppLocalized("Use Mirror"), isOn: Binding(
                    get: { vm.useCustomMirror[category] == true },
                    set: { vm.setUseCustom($0, for: category) }
                ))
                .disabled(vm.selectedMirrorId[category] == nil)

                if vm.isActive(for: category), let mirror = vm.selectedMirror(for: category) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(mirror.name).font(.body.weight(.medium))
                        Text(mirror.baseURL)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                } else {
                    Text(AppLocalized("Official (default)"))
                        .foregroundStyle(.secondary)
                }
            }

            Section(sortedResults.isEmpty ? AppLocalized("Mirrors") : AppLocalized("Speed Test Results")) {
                if sortedResults.isEmpty {
                    // Show all mirrors for this category (no test results yet)
                    ForEach(MirrorEntry.mirrors(for: category), id: \.id) { mirror in
                        Button {
                            vm.selectMirror(mirror)
                        } label: {
                            mirrorRowStatic(mirror)
                        }
                        .buttonStyle(.plain)
                    }
                } else {
                    ForEach(sortedResults) { result in
                        Button {
                            vm.selectMirror(result.mirror)
                        } label: {
                            mirrorRow(result)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            Section {
                Button {
                    vm.runTest(for: category)
                } label: {
                    Label {
                        Text(AppLocalized("Test Speed"))
                    } icon: {
                        Image(systemName: "bolt.fill")
                            .font(.system(size: 9))
                            .foregroundStyle(.white)
                            .frame(width: 21, height: 21)
                            .background(.orange, in: Circle())
                    }
                }
                .disabled(vm.isTesting)

                if vm.isTesting {
                    ProgressView(value: vm.testProgress)
                }
            }
        }
        .navigationTitle(category.displayName)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func mirrorRowStatic(_ mirror: MirrorEntry) -> some View {
        HStack(spacing: 10) {
            Image(systemName: vm.selectedMirrorId[category] == mirror.id ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(vm.selectedMirrorId[category] == mirror.id ? .blue : .secondary)
                .frame(width: 20)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(mirror.name)
                        .font(.body)
                    if mirror.isOfficial {
                        Text(AppLocalized("Official"))
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(Color.secondary.opacity(0.12))
                            .clipShape(Capsule())
                    }
                    Text(mirror.region)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
                Text(mirror.baseURL)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer()
        }
        .contentShape(Rectangle())
    }

    private func mirrorRow(_ result: MirrorTestResult) -> some View {
        HStack(spacing: 10) {
            Image(systemName: vm.selectedMirrorId[category] == result.mirror.id ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(vm.selectedMirrorId[category] == result.mirror.id ? .blue : .secondary)
                .frame(width: 20)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(result.mirror.name)
                        .font(.body)
                    if result.mirror.isOfficial {
                        Text(AppLocalized("Official"))
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(Color.secondary.opacity(0.12))
                            .clipShape(Capsule())
                    }
                    Text(result.mirror.region)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
                Text(result.mirror.baseURL)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer()

            if let ms = result.latencyMs {
                Text("\(ms)ms")
                    .font(.caption.monospaced())
                    .foregroundStyle(ms < 200 ? .green : ms < 500 ? .orange : .red)
            } else {
                Text(AppLocalized("Timeout"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .contentShape(Rectangle())
    }
}
