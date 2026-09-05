import SwiftUI

/// Team orchestration uses the existing native Minis sessions and providers.
/// The enclosing ContentView dismisses this sheet before navigating to a session.
struct MinisTeamView: View {
    let onOpenSession: (String) -> Void

    @ObservedObject private var store = MinisTeamStore.shared
    @ObservedObject private var providers = ProviderConfigStore.shared
    @ObservedObject private var locks = SessionLockStore.shared
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var activeSheet: TeamSheet?
    @State private var botToDelete: MinisBotProfile?
    @State private var errorMessage: String?
    @State private var activeTasksOnly = false

    private enum TeamSheet: Identifiable {
        case newBot
        case editBot(MinisBotProfile)
        case dispatch(String?)

        var id: String {
            switch self {
            case .newBot: return "new-bot"
            case .editBot(let bot): return "edit-\(bot.id)"
            case .dispatch(let botID): return "dispatch-\(botID ?? "choose")"
            }
        }
    }

    private var visibleTasks: [MinisTeamTask] {
        store.tasks
            .filter { !activeTasksOnly || !$0.state.isTerminal }
            .sorted { $0.createdAt > $1.createdAt }
    }

    var body: some View {
        NavigationStack {
            List {
                overviewSection
                if let error = store.storageError {
                    Section {
                        Label(error, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                            .font(.footnote)
                            .textSelection(.enabled)
                    }
                }
                botsSection
                tasksSection
            }
            .navigationTitle("Bot 团队")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: String.self) { taskID in
                MinisTeamTaskDetail(taskID: taskID, onOpenSession: onOpenSession)
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("完成") { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button {
                            activeSheet = .newBot
                        } label: {
                            Label("新建 Bot", systemImage: "person.badge.plus")
                        }
                        Button {
                            activeSheet = .dispatch(nil)
                        } label: {
                            Label("派发任务", systemImage: "paperplane")
                        }
                        .disabled(store.bots.isEmpty)
                    } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel("添加 Bot 或任务")
                }
            }
            .refreshable { await store.refresh() }
            .sheet(item: $activeSheet) { sheet in
                switch sheet {
                case .newBot:
                    MinisBotEditor(bot: nil)
                case .editBot(let bot):
                    MinisBotEditor(bot: bot)
                case .dispatch(let botID):
                    MinisTeamTaskComposer(initialBotID: botID)
                }
            }
            .confirmationDialog(
                "删除这个 Bot？",
                isPresented: Binding(
                    get: { botToDelete != nil },
                    set: { if !$0 { botToDelete = nil } }
                ),
                titleVisibility: .visible,
                presenting: botToDelete
            ) { bot in
                Button("删除 Bot", role: .destructive) {
                    do {
                        try store.deleteBot(id: bot.id)
                    } catch {
                        errorMessage = error.localizedDescription
                    }
                    botToDelete = nil
                }
                .disabled(bot.sessionID.map { locks.isVisuallyLocked($0) } ?? false)
                Button("取消", role: .cancel) { botToDelete = nil }
            } message: { _ in
                Text("已创建的会话和任务记录会保留。")
            }
            .alert("操作未完成", isPresented: errorBinding) {
                Button("好", role: .cancel) { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
        }
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            await store.refresh()
            // Expiry must mask content even if no task changes while this sheet
            // is open. No model requests or scheduler polling occur in this loop.
            while !Task.isCancelled {
                locks.evictIdleExpired()
                do { try await Task.sleep(nanoseconds: 2_000_000_000) }
                catch { return }
            }
        }
    }

    private var overviewSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 12) {
                Label("把工作分给不同的 Bot", systemImage: "person.3.fill")
                    .font(.headline)
                Text("为每个 Bot 设定职责和模型，在各自的会话中持续处理任务。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                HStack(spacing: 20) {
                    countLabel("Bot", count: store.bots.count)
                    countLabel("运行中", count: store.tasks.filter { $0.state == .running }.count)
                    countLabel("排队中", count: store.tasks.filter { $0.state == .queued }.count)
                }
            }
            .padding(.vertical, 5)
        } footer: {
            Text("任务由这台设备执行，最多同时处理 3 个；同一个 Bot 按顺序处理。锁屏或切到后台后，运行时间受 iOS 限制；应用重启后，未完成任务会标记为已中断。")
        }
    }

    private func countLabel(_ title: String, count: Int) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("\(count)")
                .font(.title3.weight(.semibold))
                .monospacedDigit()
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var botsSection: some View {
        Section("我的 Bot") {
            if store.bots.isEmpty {
                Text("还没有 Bot。先创建一个，并告诉它负责什么。")
                    .foregroundStyle(.secondary)
            }
            ForEach(store.bots) { bot in
                botRow(bot)
            }
            Button {
                activeSheet = .newBot
            } label: {
                Label("新建 Bot", systemImage: "plus.circle")
            }
        }
    }

    private func botRow(_ bot: MinisBotProfile) -> some View {
        let isLocked = bot.sessionID.map { locks.isVisuallyLocked($0) } ?? false
        return HStack(spacing: 12) {
            Image(systemName: isLocked ? "lock" : "person.crop.square")
                .font(.title2)
                .foregroundStyle(Color.accentColor)
            Button {
                if isLocked, let sessionID = bot.sessionID {
                    onOpenSession(sessionID)
                } else {
                    activeSheet = .editBot(bot)
                }
            } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(isLocked ? "已锁定的 Bot" : bot.name)
                        .font(.headline)
                        .foregroundStyle(.primary)
                    Text(isLocked ? "打开会话以解锁" : bot.role)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                    if !isLocked {
                        Text(modelName(bot.modelEntryID))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(isLocked ? "打开 Bot 会话解锁" : "编辑 \(bot.name)")
            Button {
                activeSheet = .dispatch(bot.id)
            } label: {
                Image(systemName: "paperplane")
                    .padding(8)
            }
            .buttonStyle(.borderless)
            .disabled(isLocked)
            .accessibilityLabel(isLocked ? "Bot 已锁定" : "给 \(bot.name) 派发任务")
        }
        .padding(.vertical, 3)
        .contextMenu {
            if !isLocked {
                Button {
                    activeSheet = .editBot(bot)
                } label: {
                    Label("编辑 Bot", systemImage: "pencil")
                }
                Button {
                    activeSheet = .dispatch(bot.id)
                } label: {
                    Label("派发任务", systemImage: "paperplane")
                }
            }
            if let sessionID = bot.sessionID {
                Button {
                    onOpenSession(sessionID)
                } label: {
                    Label("打开会话", systemImage: "bubble.left.and.bubble.right")
                }
            }
            if !isLocked {
                Button(role: .destructive) {
                    botToDelete = bot
                } label: {
                    Label("删除 Bot", systemImage: "trash")
                }
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if !isLocked {
                Button("删除", role: .destructive) { botToDelete = bot }
                Button("编辑") { activeSheet = .editBot(bot) }
                    .tint(.blue)
            }
        }
    }

    private var tasksSection: some View {
        Section {
            Toggle("只看进行中的任务", isOn: $activeTasksOnly)
                .font(.subheadline)
            if visibleTasks.isEmpty {
                Text(activeTasksOnly ? "没有排队或运行中的任务。" : "还没有任务。点 Bot 旁的发送按钮开始。")
                    .foregroundStyle(.secondary)
                    .font(.subheadline)
            }
            ForEach(visibleTasks) { task in
                let isLocked = store.isTaskContentLocked(task)
                NavigationLink(value: task.id) {
                    VStack(alignment: .leading, spacing: 7) {
                        HStack {
                            Text(isLocked ? "已锁定的任务" : (store.bot(id: task.botID)?.name ?? "已删除的 Bot"))
                                .font(.subheadline.weight(.semibold))
                            Spacer()
                            MinisTeamTaskStatus(state: task.state)
                        }
                        Text(isLocked ? "打开相关会话解锁后查看内容。" : task.prompt)
                            .font(.subheadline)
                            .lineLimit(2)
                        Text(task.createdAt, style: .date)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                }
            }
            if !store.bots.isEmpty {
                Button {
                    activeSheet = .dispatch(nil)
                } label: {
                    Label("派发任务", systemImage: "paperplane")
                }
            }
        } header: {
            Text("任务")
        }
    }

    private func modelName(_ entryID: String?) -> String {
        guard let entryID else { return "沿用会话模型" }
        return providers.entry(for: entryID)?.model.displayName ?? "模型不可用，请编辑 Bot"
    }

    private var errorBinding: Binding<Bool> {
        Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })
    }
}

private struct MinisBotEditor: View {
    let bot: MinisBotProfile?
    @ObservedObject private var store = MinisTeamStore.shared
    @ObservedObject private var providers = ProviderConfigStore.shared
    @ObservedObject private var locks = SessionLockStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var role: String
    @State private var modelEntryID: String?
    @State private var showModelPicker = false
    @State private var errorMessage: String?

    init(bot: MinisBotProfile?) {
        self.bot = bot
        _name = State(initialValue: bot?.name ?? "")
        _role = State(initialValue: bot?.role ?? "")
        _modelEntryID = State(initialValue: bot?.modelEntryID)
    }

    private var isBusy: Bool {
        guard let bot else { return false }
        return store.tasks.contains { $0.botID == bot.id && !$0.state.isTerminal }
    }

    private var canSave: Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !role.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !isBusy
            && !isLocked
            && (modelEntryID.map { providers.entry(for: $0) != nil } ?? true)
    }

    private var isLocked: Bool {
        guard let bot,
              let sessionID = store.bot(id: bot.id)?.sessionID ?? bot.sessionID else { return false }
        return locks.isVisuallyLocked(sessionID)
    }

    var body: some View {
        NavigationStack {
            Form {
                if isLocked {
                    Label("Bot 会话已锁定，请先在聊天列表解锁。", systemImage: "lock")
                } else {
                    Section("名称") {
                        TextField("例如：代码审查", text: $name)
                    }
                    Section {
                        TextEditor(text: $role)
                            .frame(minHeight: 150)
                            .accessibilityLabel("Bot 的职责和工作要求")
                    } header: {
                        Text("职责")
                    } footer: {
                        Text("写明它负责什么、工作要求和输出格式。这些要求会用于这个 Bot 的任务。")
                    }
                    Section {
                        Button {
                            showModelPicker = true
                        } label: {
                            HStack {
                                Text("模型")
                                    .foregroundStyle(.primary)
                                Spacer()
                                Text(modelName)
                                    .foregroundStyle(.secondary)
                                    .multilineTextAlignment(.trailing)
                                Image(systemName: "chevron.right")
                                    .font(.caption)
                                    .foregroundStyle(.tertiary)
                            }
                        }
                        if modelEntryID != nil {
                            Button("沿用会话模型") { modelEntryID = nil }
                        }
                    } header: {
                        Text("模型选择")
                    } footer: {
                        Text("使用 Minis 中已配置的模型和账户。未指定时，新 Bot 使用默认模型，已有 Bot 沿用会话当前选择。")
                    }
                    if isBusy {
                        Section {
                            Label("这个 Bot 还有未完成任务，完成或取消后才能修改。", systemImage: "clock")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle(bot == nil ? "新建 Bot" : "编辑 Bot")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存", action: save)
                        .disabled(!canSave)
                }
            }
            .sheet(isPresented: $showModelPicker) {
                NavigationStack {
                    UnifiedModelPicker(config: .init(
                        title: "选择 Bot 模型",
                        groupScope: .none,
                        candidateFilter: {
                            $0.model.capabilities.supportedModalities.contains([.textInput, .textOutput])
                        },
                        showGroups: false,
                        currentEntryId: { modelEntryID },
                        onSelect: { modelEntryID = $0.id }
                    ))
                }
            }
            .onChange(of: isLocked) { locked in
                if locked { showModelPicker = false }
            }
            .alert("无法保存 Bot", isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )) {
                Button("好", role: .cancel) { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }

    private var modelName: String {
        guard let modelEntryID else { return "沿用会话模型" }
        return providers.entry(for: modelEntryID)?.model.displayName ?? "模型不可用，请重新选择"
    }

    private func save() {
        guard !isLocked else {
            errorMessage = "Bot 会话已锁定，请先解锁。"
            return
        }
        do {
            if let bot {
                try store.updateBot(id: bot.id, name: name, role: role, modelEntryID: modelEntryID)
            } else {
                _ = try store.createBot(name: name, role: role, modelEntryID: modelEntryID)
            }
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private struct MinisTeamTaskComposer: View {
    @ObservedObject private var store = MinisTeamStore.shared
    @ObservedObject private var locks = SessionLockStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var selectedBotID: String
    @State private var prompt = ""
    @State private var errorMessage: String?

    init(initialBotID: String?) {
        _selectedBotID = State(initialValue: initialBotID ?? "")
    }

    private func isLocked(_ bot: MinisBotProfile) -> Bool {
        bot.sessionID.map { locks.isVisuallyLocked($0) } ?? false
    }

    private var selectedBotIsLocked: Bool {
        store.bot(id: selectedBotID).map { isLocked($0) } ?? false
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("负责的 Bot") {
                    Picker("Bot", selection: $selectedBotID) {
                        Text("请选择 Bot").tag("")
                        ForEach(store.bots) { bot in
                            Text(isLocked(bot) ? "已锁定的 Bot" : bot.name).tag(bot.id)
                        }
                    }
                    if let bot = store.bot(id: selectedBotID) {
                        Text(isLocked(bot) ? "请先在聊天列表解锁这个 Bot 的会话。" : bot.role)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Section {
                    if selectedBotIsLocked {
                        Label("解锁 Bot 会话后可继续派发。", systemImage: "lock")
                    } else {
                        TextEditor(text: $prompt)
                            .frame(minHeight: 200)
                            .accessibilityLabel("任务内容")
                    }
                } header: {
                    Text("任务内容")
                } footer: {
                    Text("描述目标、必要的背景和预期产出。任务会进入队列；同一个 Bot 会沿用已有会话。")
                }
            }
            .navigationTitle("派发任务")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("派发", action: dispatch)
                        .disabled(store.bot(id: selectedBotID) == nil
                                  || selectedBotIsLocked
                                  || prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .onAppear {
                if selectedBotID.isEmpty, let first = store.bots.first {
                    selectedBotID = first.id
                }
            }
            .alert("无法派发任务", isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )) {
                Button("好", role: .cancel) { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }

    private func dispatch() {
        guard !selectedBotIsLocked else {
            errorMessage = "Bot 会话已锁定，请先解锁。"
            return
        }
        do {
            _ = try store.dispatch(botID: selectedBotID, prompt: prompt)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private struct MinisTeamTaskDetail: View {
    let taskID: String
    let onOpenSession: (String) -> Void
    @ObservedObject private var store = MinisTeamStore.shared
    @ObservedObject private var locks = SessionLockStore.shared
    @State private var cancelling = false

    var body: some View {
        List {
            if let task = store.task(id: taskID) {
                let isLocked = store.isTaskContentLocked(task)
                Section("任务状态") {
                    HStack {
                        Text(isLocked ? "已锁定的任务" : (store.bot(id: task.botID)?.name ?? "已删除的 Bot"))
                            .font(.headline)
                        Spacer()
                        MinisTeamTaskStatus(state: task.state)
                    }
                    dateRow("创建时间", date: task.createdAt)
                    if let startedAt = task.startedAt {
                        dateRow("开始时间", date: startedAt)
                    }
                    if let finishedAt = task.finishedAt {
                        dateRow("结束时间", date: finishedAt)
                    }
                }
                if isLocked {
                    Section {
                        Label("相关会话已锁定，解锁后可查看任务内容。", systemImage: "lock")
                            .foregroundStyle(.secondary)
                    }
                } else {
                    Section("任务内容") {
                        Text(task.prompt)
                            .textSelection(.enabled)
                    }
                    if let result = task.result, !result.isEmpty {
                        Section("结果") {
                            Text(result)
                                .textSelection(.enabled)
                        }
                    }
                    if let error = task.error, !error.isEmpty {
                        Section("说明") {
                            Text(error)
                                .foregroundStyle(task.state == .failed ? Color.red : Color.secondary)
                                .textSelection(.enabled)
                        }
                    }
                }
                if task.state == .running {
                    Section {
                        Label("任务执行中，可打开会话查看进度或处理权限请求。", systemImage: "bubble.left.and.bubble.right")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                Section {
                    if let sessionID = task.sessionID {
                        Button {
                            onOpenSession(sessionID)
                        } label: {
                            Label("打开 Bot 会话", systemImage: "bubble.left.and.bubble.right")
                        }
                    }
                    if let sourceSessionID = task.sourceSessionID,
                       sourceSessionID != task.sessionID,
                       locks.isVisuallyLocked(sourceSessionID) {
                        Button {
                            onOpenSession(sourceSessionID)
                        } label: {
                            Label("打开来源会话解锁", systemImage: "lock.open")
                        }
                    }
                    if !task.state.isTerminal {
                        Button(role: .destructive) {
                            cancelling = true
                            Task {
                                await store.cancel(taskID: taskID)
                                cancelling = false
                            }
                        } label: {
                            Label(cancelling ? "正在取消…" : "取消任务", systemImage: "stop.circle")
                        }
                        .disabled(cancelling || isLocked)
                    }
                }
            } else {
                Text("找不到这条任务记录。")
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("任务详情")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await store.refresh() }
    }

    private func dateRow(_ title: String, date: Date) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text(date.formatted(date: .abbreviated, time: .shortened))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.trailing)
        }
        .font(.caption)
    }
}

private struct MinisTeamTaskStatus: View {
    let state: MinisTeamTaskState

    var body: some View {
        Label(title, systemImage: symbol)
            .font(.caption.weight(.medium))
            .foregroundStyle(color)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(color.opacity(0.10), in: Capsule())
            .fixedSize(horizontal: true, vertical: false)
    }

    private var title: String {
        switch state {
        case .queued: return "排队中"
        case .running: return "运行中"
        case .completed: return "已完成"
        case .failed: return "失败"
        case .cancelled: return "已取消"
        case .interrupted: return "已中断"
        }
    }

    private var symbol: String {
        switch state {
        case .queued: return "clock"
        case .running: return "arrow.triangle.2.circlepath"
        case .completed: return "checkmark.circle"
        case .failed: return "exclamationmark.circle"
        case .cancelled: return "stop.circle"
        case .interrupted: return "pause.circle"
        }
    }

    private var color: Color {
        switch state {
        case .queued: return .orange
        case .running: return .blue
        case .completed: return .green
        case .failed: return .red
        case .cancelled: return .secondary
        case .interrupted: return .orange
        }
    }
}
