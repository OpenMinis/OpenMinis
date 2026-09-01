import Foundation

private let logger = AppLogger(category: "VoiceCorrection")

// MARK: - Budgets

/// Character budgets for every evidence block of the correction prompt.
///
/// Design (2026-07-16 context-engineering revision): the prompt's evidence is
/// assembled under EXPLICIT per-block budgets, decided here BEFORE assembly, so
/// the prompt can never balloon and every block's spend is auditable in the
/// logs (`[CorrectionContext]` / `[CorrectionPrompt]` tags).
///
/// All budgets are in CHARACTERS, not tokens: the content is CJK-heavy, the
/// user-facing spec is stated in characters ("消息上下文预算不超过 5k 字符"),
/// and characters are what we can count deterministically without a tokenizer.
///
/// | block                        | budget | why                                   |
/// |------------------------------|--------|---------------------------------------|
/// | message context (total)      | 5000   | user-specified hard cap                |
/// | ├─ rare-content digest       | 200    | "top 200 字" of the rarest terms       |
/// | └─ message excerpts          | 4800   | expand message COUNT until spent       |
/// |    ├─ per-message cap        | 600    | one long message can't eat the budget  |
/// |    └─ grounding cap          | 500    | newest turns keep plain lead-in text   |
/// | confusion history block      | 800    | ~20 lines × ~40 chars                  |
/// | typed-vocabulary block       | 400    | ~30 terms × ~12 chars                  |
///
/// Scan guards (cost, not prompt size): at most `maxMessagesScanned` messages
/// are examined, and only the first `perMessageScanCap` characters of each are
/// segmented — segmentation runs on the correction path and jieba on a
/// pathological 50k-char paste would otherwise stall the tap.
enum CorrectionContextBudget {
    static let messageContextTotal = 5000
    static let rareDigest = 200
    static let messageExcerpts = 4800
    static let perMessageExcerpt = 600
    static let groundingExcerpt = 500
    static let confusionBlock = 800
    static let vocabBlock = 400

    static let maxMessagesScanned = 12
    static let perMessageScanCap = 2000
}

// MARK: - Rarity scoring (生僻内容算法)

/// Scores how "rare" (生僻) a segmented term is, 0–100. Higher = rarer = more
/// likely to be exactly the kind of content ASR mishears and the corrector
/// needs to see.
///
/// Priors available offline, combined in this order:
/// 1. The curated background word list (~200 entries) is a strong NEGATIVE
///    signal only: presence ⇒ ordinary word ⇒ score 0. Absence proves nothing
///    (the list is tiny), so OOV alone never makes a term rare.
/// 2. Character-level rarity against an inline ~900-char common-hanzi set —
///    this is the core of the algorithm, matching the spec's "每句中出现的
///    生僻字情况": the fraction of a term's CJK characters that fall OUTSIDE
///    the common set scales its score.
/// 3. Shape heuristics for technical tokens (camelCase, digits, mixed script,
///    ALL-CAPS, dashed/dotted identifiers) — `WGHome`, `dns-server`,
///    `LiveActivity` are prime correction targets regardless of script.
enum RareContentScorer {

    /// Score at or above which a term is eligible for the rare-content digest.
    /// 40 admits domain words built from common characters (闪退, 挂载) while
    /// the digest's 200-char cap still prefers genuinely rarer content above.
    static let digestThreshold = 40
    /// Score at or above which a sentence counts as "carrying rare content"
    /// for excerpt selection — a stricter bar so excerpts chase truly unusual
    /// content, not every OOV word.
    static let strongThreshold = 60

    /// ≈900 highest-frequency modern Chinese characters (order irrelevant —
    /// membership prior only). Inlined so the scorer works with zero resource
    /// plumbing; a char outside this set is treated as a 生僻字.
    static let commonHanzi: Set<Character> = Set(
        "的一是了我不人在他有这个上们来到时大地为子中你说生国年着就那和要她出也得里后自以会家可下而过天去能对小多然于心学么之都好看起发当没成只如事把还用第样道想作种开美总从无情己面最女但现前些所同日手又行意动方期它头经长儿回位分爱老因很给名法间知世什两次使身者被高已亲其进此话常与活正感" +
        "见明问力理点文几定本公特做外孩相西果走将月十实向声车全信重三机工物气每并别真打太新比才便夫再书部水像眼等体却加电主界门利海受听表德少克代员许先口由死安写性马光白或住难望教命花结乐色更拉东神记处让母父应直字场平报友关放至张认接告入笑内英军候民岁往何度山觉路带万男边风解叫任金快原吃妈变通师立象数四失满战远格士音轻目条呢" +
        "病始达深完今提求清王化空业思切怎非找片罗钱语元喜曾离飞科言干流欢约各即指合反题必该论交终林请医晚制球决传画保读运及则房早院量苦火布品近坐产答星精视五连司巴奇管类未朋且婚台夜青北队久乎越观落尽形影红爸百令周吧识步希亚术留市半热送兴造谈容极随演收首根讲整式取照办强石古华拿计您装似足双妻尼转诉米称丽客南领节衣站黑刻统断" +
        "福城故历惊脸选包紧争另建维绝树系伤示愿持千史谁准联妇纪基买志静阿诗独复痛消社算义竟确酒需单治卡幸兰念举仅钟怕共毛句息功官待究跟穿室易游程号居考突皮哪费倒价图具刚脑永歌响商礼细专黄块脚线怪按顾泪央曲牌秀要迷夏漂坏烟余雪托兄弟斗智排供香吸尝奶推雨类顶层楼灯洗牛左右初旁鱼低店油器假隔简卖爷帮航草叶伸投纸叹听份妹沙软吹" +
        "呀园杀汽冷弄绍导族桌纯攻误修复测试代码文件版本网络服务器数据配置环境系统功能问题项目模型语音识别浏览设备设置权限支持切换启录音频视频消播放暂停继续退出登录账户密码搜索下载安装更新升级删除添加编辑保存取消确认提交发送接收显示隐藏打开关闭" +
        "闪挂载屏幕键盘触摸滑按钮菜单窗口标签页链接地址邮箱脑软硬件程序停止恢复崩溃错警告提示格式类型参数函数变量组列表典循环判断条件返调接口请求响应超缓存队列线程进内盘"
    )

    /// CJK Unified Ideographs (BMP block, matching TextSegmenter's routing).
    static func isCJK(_ c: Character) -> Bool {
        guard let scalar = c.unicodeScalars.first else { return false }
        return (0x4E00...0x9FFF).contains(scalar.value)
    }

    static func isRareCJKChar(_ c: Character) -> Bool {
        isCJK(c) && !commonHanzi.contains(c)
    }

    /// Score one segmented term. `rank` is the background-word-list lookup,
    /// injectable so unit tests don't depend on the app bundle resource.
    static func score(term: String, rank: (String) -> Int?) -> Int {
        let t = term.trimmingCharacters(in: .whitespacesAndNewlines)
        // Garbage guards: empty, absurdly long, or nothing alphanumeric in any
        // script (pure punctuation runs).
        guard !t.isEmpty, t.count <= 24 else { return 0 }
        guard t.contains(where: { $0.isLetter || isCJK($0) }) else { return 0 }
        // Known-common word: strongest negative evidence.
        if rank(t) != nil { return 0 }

        let cjkChars = t.filter { isCJK($0) }
        let hasCJK = !cjkChars.isEmpty
        let letters = t.filter { $0.isLetter && $0.isASCII }
        let hasLatin = !letters.isEmpty
        let hasDigit = t.contains { $0.isNumber }

        // Technical-token shapes (script-independent, checked before script buckets):
        // mixed CJK+Latin, letter+digit identifiers, camelCase / interior caps,
        // ALL-CAPS ≥2, or dashed/dotted/underscored identifiers.
        if hasCJK && hasLatin { return 90 }
        if hasLatin {
            if hasDigit { return 90 }
            let interiorUpper = letters.dropFirst().contains { $0.isUppercase }
            let allCaps = letters.count >= 2 && letters.allSatisfy { $0.isUppercase }
            let hasJoiner = t.contains { "-_./".contains($0) }
            if interiorUpper || allCaps || hasJoiner { return 90 }
            // Plain Latin word: the zh word list can't judge it; assume ordinary.
            return 20
        }
        guard hasCJK else {
            // Digits-only or other scripts — not correction-relevant.
            return 0
        }

        let rareCount = cjkChars.filter { !commonHanzi.contains($0) }.count
        if cjkChars.count == 1 {
            // A lone hanzi: only interesting if it IS a 生僻字.
            return rareCount == 1 ? 75 : 0
        }
        // Multi-char CJK word not in the common-word list: baseline 40 (domain
        // word made of common chars — 闪退), scaled up to 90 as its 生僻字
        // fraction rises.
        let ratio = Double(rareCount) / Double(cjkChars.count)
        return 40 + Int((ratio * 50.0).rounded())
    }
}

// MARK: - Builder inputs / outputs

/// Conversation evidence injected as the third class of evidence (design §6,
/// revised 2026-07-16 to the budgeted builder). Lives here (not in
/// CorrectionStrategy.swift) so the unit-test target — which compiles files
/// individually — gets producer and type together.
///
/// Preferred path: `CorrectionContextBuilder` fills `recentExcerpts` (multiple
/// role-labeled, rarity-selected message excerpts, oldest→newest) and
/// `rareTermsDigest` (the rarest terms across those messages, ≤200 chars).
/// The legacy two-field form is kept as a fallback so hand-built contexts
/// (debug RPC, older tests) keep working unchanged.
struct ConversationContext: Sendable, Equatable {
    var lastUserMessage: String?
    var lastAgentReply: String?
    /// Rare-content digest ("top 200 字") from `CorrectionContextBuilder`.
    var rareTermsDigest: String? = nil
    /// Budgeted per-message excerpts, oldest→newest, already role-labeled.
    var recentExcerpts: [String] = []

    /// [T-voice-correction-debug-capture] Mining telemetry for the DEBUG trace: the
    /// per-term rarity scores behind `rareTermsDigest`, and the shape of each excerpt.
    ///
    /// Held in one `Mining` box that is deliberately EXCLUDED from `==` (the custom
    /// operator below) and from `isEmpty`: it observes how the context was built, and is
    /// never part of what the context IS. Folding it into equality would make two
    /// identical prompts compare unequal because one was built from more scanned
    /// messages — and `ConversationContext: Equatable` is what the unit tests assert on.
    struct Mining: Sendable {
        struct DigestTerm: Sendable { let term: String; let score: Int; let count: Int }
        struct ExcerptMeta: Sendable {
            let role: String; let newestIndex: Int; let kind: String; let chars: Int
        }
        var digestScores: [DigestTerm] = []
        var excerpts: [ExcerptMeta] = []
        var scannedMessageCount = 0
        var buildMs = 0
    }
    var mining = Mining()

    var isEmpty: Bool {
        (lastUserMessage?.isEmpty ?? true) && (lastAgentReply?.isEmpty ?? true)
            && (rareTermsDigest?.isEmpty ?? true) && recentExcerpts.isEmpty
    }
    static let empty = ConversationContext(lastUserMessage: nil, lastAgentReply: nil)

    /// Equality over the CONTENT fields only — `mining` is observational (see above), and
    /// synthesised equality would additionally require `Mining: Equatable`.
    static func == (lhs: ConversationContext, rhs: ConversationContext) -> Bool {
        lhs.lastUserMessage == rhs.lastUserMessage
            && lhs.lastAgentReply == rhs.lastAgentReply
            && lhs.rareTermsDigest == rhs.rareTermsDigest
            && lhs.recentExcerpts == rhs.recentExcerpts
    }
}

/// UI-framework-free projection of a chat turn, so the builder is unit-testable
/// without ChatStore types. `text` is expected to be ALREADY stripped of
/// attachment markup — the caller owns that (it has TypedVocabularyBuilder in
/// scope; this file deliberately doesn't, for test-target independence).
struct CorrectionSourceMessage: Sendable {
    enum Role: String, Sendable { case user = "用户", assistant = "AI" }
    let role: Role
    let text: String
}

// MARK: - Builder

/// Builds the message-context portion of the correction prompt under the
/// budgets above: sentence-split each recent message (punctuation rules shared
/// with the rest of the feature via `SentenceSplitter`), segment each sentence
/// (same jieba/NLTokenizer strategy as typed-vocabulary), score every term's
/// rarity, then emit
///   1. a rare-content digest — the rarest terms across all scanned messages,
///      capped at `rareDigest` characters ("top 200 字"), and
///   2. per-message excerpts — sentences that carry rare content (plus plain
///      lead-in grounding for the newest turns), expanding the number of
///      messages included until the excerpt budget is spent.
/// Every stage logs its decisions for audit.
enum CorrectionContextBuilder {

    static func build(
        messages: [CorrectionSourceMessage],
        rankProvider: @Sendable (String) -> Int? = { BackgroundWordFrequency.shared.rank(of: $0) }
    ) -> ConversationContext {
        let started = Date()
        guard !messages.isEmpty else {
            logger.info("[CorrectionContext] no messages — empty context")
            return .empty
        }

        // Newest → oldest, bounded scan.
        let scanned = Array(messages.suffix(CorrectionContextBudget.maxMessagesScanned).reversed())

        struct ScoredSentence {
            let text: String
            let strongScore: Int   // max term score ≥ strongThreshold, else 0
        }
        struct ScannedMessage {
            let role: CorrectionSourceMessage.Role
            let sentences: [ScoredSentence]
            let newestIndex: Int   // 0 = newest
        }

        // Global rare-term aggregation: term → (bestScore, occurrences).
        // Latin terms dedupe case-insensitively (WGHome / wghome are one term).
        var rareTerms: [String: (score: Int, count: Int, display: String)] = [:]
        var scannedMessages: [ScannedMessage] = []

        for (idx, msg) in scanned.enumerated() {
            let stripped = msg.text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !stripped.isEmpty else { continue }
            let capped = String(stripped.prefix(CorrectionContextBudget.perMessageScanCap))
            let sentences = SentenceSplitter.split(capped)

            var scoredSentences: [ScoredSentence] = []
            var termTotal = 0, rareInMsg = 0
            for sentence in sentences {
                let terms = TextSegmenter.shared.segment(sentence)
                termTotal += terms.count
                var strongest = 0
                for term in terms {
                    let s = RareContentScorer.score(term: term, rank: rankProvider)
                    guard s >= RareContentScorer.digestThreshold else { continue }
                    rareInMsg += 1
                    let key = term.lowercased()
                    if var existing = rareTerms[key] {
                        existing.count += 1
                        existing.score = max(existing.score, s)
                        rareTerms[key] = existing
                    } else {
                        rareTerms[key] = (score: s, count: 1, display: term)
                    }
                    if s >= RareContentScorer.strongThreshold { strongest = max(strongest, s) }
                }
                scoredSentences.append(ScoredSentence(text: sentence, strongScore: strongest))
            }
            scannedMessages.append(ScannedMessage(role: msg.role, sentences: scoredSentences, newestIndex: idx))
            // [T-log-noise-privacy 2026-07-18] info → debug: one line per
            // scanned message per correction run; the assembled summary below
            // stays at info.
            logger.debug("[CorrectionContext] scan msg#\(idx)(\(msg.role.rawValue)) chars=\(capped.count)\(stripped.count > capped.count ? "(capped)" : "") sentences=\(sentences.count) terms=\(termTotal) rareHits=\(rareInMsg)")
        }

        // ---- 1. Rare-content digest: rarest first, budget rareDigest chars.
        let ranked = rareTerms.values.sorted {
            if $0.score != $1.score { return $0.score > $1.score }
            if $0.count != $1.count { return $0.count > $1.count }
            return $0.display.count > $1.display.count
        }
        var digestTerms: [String] = []
        var digestChars = 0
        var digestScores: [ConversationContext.Mining.DigestTerm] = []
        for entry in ranked {
            let cost = entry.display.count + (digestTerms.isEmpty ? 0 : 1) // +1 for "、"
            guard digestChars + cost <= CorrectionContextBudget.rareDigest else { continue }
            digestTerms.append(entry.display)
            digestScores.append(.init(term: entry.display, score: entry.score, count: entry.count))
            digestChars += cost
        }
        let digest = digestTerms.isEmpty ? nil : digestTerms.joined(separator: "、")
        logger.info("[CorrectionContext] digest candidates=\(ranked.count) kept=\(digestTerms.count) chars=\(digestChars)/\(CorrectionContextBudget.rareDigest)")

        // ---- 2. Message excerpts: newest→oldest until the budget is spent.
        // The two newest turns always contribute plain lead-in grounding (the
        // immediate conversational context the old design existed for); older
        // messages only earn a slot with rare-content sentences.
        var excerptBudget = CorrectionContextBudget.messageExcerpts
        var rendered: [(newestIndex: Int, line: String)] = []
        var excerptMeta: [ConversationContext.Mining.ExcerptMeta] = []

        for m in scannedMessages {
            guard excerptBudget > 80 else {
                logger.info("[CorrectionContext] excerpt budget exhausted at msg#\(m.newestIndex) — stopping expansion")
                break
            }
            let isGrounding = m.newestIndex < 2
            let perMessageCap = min(
                isGrounding ? CorrectionContextBudget.groundingExcerpt
                            : CorrectionContextBudget.perMessageExcerpt,
                excerptBudget
            )

            var picked: [String] = []
            var used = 0
            func take(_ sentence: String) -> Bool {
                var s = sentence
                if s.count > perMessageCap - used {
                    guard picked.isEmpty else { return false } // no room for another whole sentence
                    s = String(s.prefix(max(0, perMessageCap - used - 1))) + "…"
                }
                guard !s.isEmpty else { return false }
                picked.append(s); used += s.count
                return used < perMessageCap
            }

            if isGrounding {
                // Leading sentences, old-truncator style, but never mid-sentence.
                for s in m.sentences { if !take(s.text) { break } }
            } else {
                // Rare-bearing sentences only, in original order.
                for s in m.sentences where s.strongScore > 0 { if !take(s.text) { break } }
            }
            guard !picked.isEmpty else { continue }

            let line = "【\(m.role.rawValue)·\(m.newestIndex == 0 ? "最新" : "前第\(m.newestIndex + 1)条")】\(picked.joined(separator: ""))"
            rendered.append((m.newestIndex, line))
            excerptMeta.append(.init(role: m.role.rawValue, newestIndex: m.newestIndex,
                                     kind: isGrounding ? "grounding" : "rare", chars: line.count))
            excerptBudget -= line.count
            // [T-log-noise-privacy 2026-07-18] info → debug (per-excerpt line).
            logger.debug("[CorrectionContext] excerpt msg#\(m.newestIndex)(\(m.role.rawValue)) kind=\(isGrounding ? "grounding" : "rare") sentences=\(picked.count)/\(m.sentences.count) chars=\(line.count) budget_left=\(excerptBudget)")
        }

        // Render oldest→newest so the model reads chronologically.
        let excerpts = rendered.sorted { $0.newestIndex > $1.newestIndex }.map(\.line)
        let excerptChars = excerpts.reduce(0) { $0 + $1.count }
        let totalMsgCtx = excerptChars + (digest?.count ?? 0)
        let ms = Int(Date().timeIntervalSince(started) * 1000)
        logger.info("[CorrectionContext] assembled messages=\(excerpts.count)/\(scannedMessages.count) excerpt_chars=\(excerptChars)/\(CorrectionContextBudget.messageExcerpts) digest_chars=\(digest?.count ?? 0)/\(CorrectionContextBudget.rareDigest) msg_ctx_total=\(totalMsgCtx)/\(CorrectionContextBudget.messageContextTotal) took=\(ms)ms")

        var ctx = ConversationContext.empty
        ctx.rareTermsDigest = digest
        ctx.recentExcerpts = excerpts
        ctx.mining = ConversationContext.Mining(
            digestScores: digestScores,
            excerpts: excerptMeta.sorted { $0.newestIndex > $1.newestIndex },
            scannedMessageCount: scannedMessages.count,
            buildMs: ms)
        return ctx
    }
}
