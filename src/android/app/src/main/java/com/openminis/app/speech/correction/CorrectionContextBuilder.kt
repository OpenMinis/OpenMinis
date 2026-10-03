package com.openminis.app.speech.correction

import android.util.Log
import com.openminis.app.shared.SentenceSplitter

/**
 * [T-android-voice-correction] Scores how "rare" a term is, i.e. how likely a
 * speech recognizer is to get it wrong. Port of iOS `RareContentScorer`.
 *
 * The common-hanzi set is inlined rather than loaded from a resource — it is
 * needed on the correction critical path and this avoids any I/O. The last two
 * chunks are deliberately app-domain-tuned (网络服务器数据配置…、闪挂载屏幕键盘…):
 * treating our own technical vocabulary as COMMON stops it from flooding the
 * rare-content digest, where it would crowd out the user's actual proper nouns.
 */
object RareContentScorer {

    /** A term at or above this enters the rare-content digest. */
    const val DIGEST_THRESHOLD = 40

    /** A sentence containing a term at or above this "carries rare content". */
    const val STRONG_THRESHOLD = 60

    /** A plain Latin word (`Issue`, `commit`) on its own: the zh word list cannot judge it. */
    const val PLAIN_LATIN_SCORE = 20

    /**
     * [T-android-voice-mixed-term-restore] A plain Latin word that RECURS in a
     * Chinese conversation is a term the user is talking about, and exactly what
     * a Chinese recognizer transliterates into look-alike hanzi ("Issue" ->
     * "遗穴"). On iOS, "Issue" appeared 10+ times in the scanned turns and never
     * reached the digest, while ordinary words built from common hanzi (状态,
     * 彻底) did at 40. Promoted to this score: above those, below the 90 of
     * identifier-shaped tokens. Port of iOS 8622ea275.
     */
    const val RECURRING_LATIN_SCORE = 50

    /** Occurrences across the scanned messages before a plain Latin word is promoted. */
    const val RECURRING_LATIN_MIN_COUNT = 3

    private val COMMON_HANZI: Set<Char> = buildSet {
        val chunks = listOf(
            "的一是了我不人在他有这个上们来到时大地为子中你说生国年着就那和要她出也得里后自以会家可下而过天去能对小多然于心学么之都好看起发当没成只如事把还用第样道想作种开美总从无情己面最女但现前些所同日手又行意动方期它头经长儿回位分爱老因很给名法间知世什两次使身者被高已亲其进此话常与活正感",
            "见明问力理点文几定本公特做外孩相西果走将月十实向声车全信重三机工物气每并别真打太新比才便夫再书部水像眼等体却加电主界门利海受听表德少克代员许先口由死安写性马光白或住难望教命花结乐色更拉东神记处让母父应直字场平报友关放至张认接告入笑内英军候民岁往何度山觉路带万男边风解叫任金快原吃妈变通师立象数四失满战远格士音轻目条呢",
            "病始达深完今提求清王化空业思切怎非找片罗钱语元喜曾离飞科言干流欢约各即指合反题必该论交终林请医晚制球决传画保读运及则房早院量苦火布品近坐产答星精视五连司巴奇管类未朋且婚台夜青北队久乎越观落尽形影红爸百令周吧识步希亚术留市半热送兴造谈容极随演收首根讲整式取照办强石古华拿计您装似足双妻尼转诉米称丽客南领节衣站黑刻统断",
            "福城故历惊脸选包紧争另建维绝树系伤示愿持千史谁准联妇纪基买志静阿诗独复痛消社算义竟确酒需单治卡幸兰念举仅钟怕共毛句息功官待究跟穿室易游程号居考突皮哪费倒价图具刚脑永歌响商礼细专黄块脚线怪按顾泪央曲牌秀要迷夏漂坏烟余雪托兄弟斗智排供香吸尝奶推雨类顶层楼灯洗牛左右初旁鱼低店油器假隔简卖爷帮航草叶伸投纸叹听份妹沙软吹",
            "呀园杀汽冷弄绍导族桌纯攻误修复测试代码文件版本网络服务器数据配置环境系统功能问题项目模型语音识别浏览设备设置权限支持切换启录音频视频消播放暂停继续退出登录账户密码搜索下载安装更新升级删除添加编辑保存取消确认提交发送接收显示隐藏打开关闭",
            "闪挂载屏幕键盘触摸滑按钮菜单窗口标签页链接地址邮箱脑软硬件程序停止恢复崩溃错警告提示格式类型参数函数变量组列表典循环判断条件返调接口请求响应超缓存队列线程进内盘",
        )
        chunks.forEach { chunk -> chunk.forEach { add(it) } }
    }

    fun isCjk(c: Char): Boolean = c.code in 0x4E00..0x9FFF

    fun isRareCjkChar(c: Char): Boolean = isCjk(c) && c !in COMMON_HANZI

    /**
     * Rarity score for [term].
     *
     * [rank] is a NEGATIVE signal only: a term present in the background list is
     * ordinary and scores 0. Absence proves nothing (the list is short by
     * design), so being out-of-vocabulary never *by itself* makes a term rare.
     */
    fun score(term: String, rank: (String) -> Int?): Int {
        val t = term.trim()
        if (t.isEmpty() || t.length > 24) return 0
        if (!t.any { it.isLetter() || isCjk(it) }) return 0
        if (rank(t) != null) return 0

        val cjkChars = t.filter { isCjk(it) }
        val hasCjk = cjkChars.isNotEmpty()
        val letters = t.filter { it.isAsciiLetter() }
        val hasLatin = letters.isNotEmpty()
        val hasDigit = t.any { it.isAsciiDigit() }

        if (hasCjk && hasLatin) return 90
        if (hasLatin) {
            if (hasDigit) return 90
            val interiorUpper = letters.drop(1).any { it.isUpperCase() }
            val allCaps = letters.length >= 2 && letters.all { it.isUpperCase() }
            val hasJoiner = t.any { it in "-_./" }
            if (interiorUpper || allCaps || hasJoiner) return 90
            // A plain word scores below DIGEST_THRESHOLD on purpose: only
            // technically-shaped Latin belongs in the digest, unless it recurs
            // (RECURRING_LATIN_SCORE, applied by the builder).
            return PLAIN_LATIN_SCORE
        }
        if (!hasCjk) return 0

        val rareCount = cjkChars.count { it !in COMMON_HANZI }
        if (cjkChars.length == 1) return if (rareCount == 1) 75 else 0
        val ratio = rareCount.toDouble() / cjkChars.length.toDouble()
        return 40 + Math.round(ratio * 50.0).toInt()
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}

/** Conversation evidence handed to the correction prompt. */
data class ConversationContext(
    val rareTermsDigest: String? = null,
    val recentExcerpts: List<String> = emptyList(),
    /** [T-android-voice-viewport-context] What is on screen; null when unavailable. */
    val screen: ScreenContext? = null,
) {
    val isEmpty: Boolean
        get() = rareTermsDigest.isNullOrEmpty() && recentExcerpts.isEmpty() && screen == null

    companion object {
        val EMPTY = ConversationContext()
    }
}

/**
 * A message the builder may draw context from. `text` is expected to already
 * have attachment markup stripped by the caller.
 */
data class CorrectionSourceMessage(val role: Role, val text: String) {
    /** Raw values are rendered directly into the prompt. */
    enum class Role(val label: String) { USER("用户"), ASSISTANT("AI") }
}

/**
 * [T-android-voice-correction] Builds the budgeted conversation context.
 * Port of iOS `Agent/Speech/CorrectionContextBuilder.swift`.
 */
object CorrectionContextBuilder {

    private const val TAG = "CorrectionContext"

    private data class ScoredSentence(val text: String, val strongScore: Int)
    private data class ScannedMessage(
        val role: CorrectionSourceMessage.Role,
        val newestIndex: Int,
        val sentences: List<ScoredSentence>,
    )

    fun build(
        messages: List<CorrectionSourceMessage>,
        segment: (String) -> List<String>,
        rank: (String) -> Int?,
    ): ConversationContext {
        if (messages.isEmpty()) return ConversationContext.EMPTY
        val started = System.currentTimeMillis()

        // Newest first; index 0 is the newest message.
        val scanned = messages.takeLast(CorrectionContextBudget.MAX_MESSAGES_SCANNED).reversed()

        // term.lowercase() -> (score, count, display) so WGHome/wghome merge
        // while the first-seen casing is what gets shown.
        val rareTerms = HashMap<String, Triple<Int, Int, String>>()
        val scannedMessages = mutableListOf<ScannedMessage>()
        // [T-android-voice-mixed-term-restore] Plain Latin words, counted for the
        // recurring-term promotion below. Promotion only makes sense where Latin
        // words stand out: in an English chat "file" and "code" recur too and are
        // not terms, so it is gated on CJK terms outnumbering plain Latin ones.
        val plainLatin = HashMap<String, kotlin.Pair<Int, String>>()
        var cjkTermCount = 0
        var plainLatinTotal = 0

        for ((idx, m) in scanned.withIndex()) {
            val stripped = m.text.trim()
            if (stripped.isEmpty()) continue
            val capped = stripped.take(CorrectionContextBudget.PER_MESSAGE_SCAN_CAP)
            val sentences = SentenceSplitter.split(capped).map { sentence ->
                var strongest = 0
                for (term in runCatching { segment(sentence) }.getOrElse { emptyList() }) {
                    if (term.any { RareContentScorer.isCjk(it) }) cjkTermCount++
                    val s = RareContentScorer.score(term, rank)
                    if (s == RareContentScorer.PLAIN_LATIN_SCORE) {
                        plainLatinTotal++
                        if (!StopWords.contains(term)) {
                            val key = term.lowercase()
                            val prev = plainLatin[key]
                            plainLatin[key] = (prev?.first ?: 0) + 1 to (prev?.second ?: term)
                        }
                        continue
                    }
                    if (s >= RareContentScorer.DIGEST_THRESHOLD) {
                        val key = term.lowercase()
                        val prev = rareTerms[key]
                        rareTerms[key] = if (prev == null) {
                            Triple(s, 1, term)
                        } else {
                            Triple(maxOf(prev.first, s), prev.second + 1, prev.third)
                        }
                    }
                    if (s >= RareContentScorer.STRONG_THRESHOLD) strongest = maxOf(strongest, s)
                }
                ScoredSentence(sentence, strongest)
            }
            scannedMessages.add(ScannedMessage(m.role, idx, sentences))
        }

        var promotedLatin = 0
        if (cjkTermCount > plainLatinTotal) {
            for ((key, entry) in plainLatin) {
                if (entry.first >= RareContentScorer.RECURRING_LATIN_MIN_COUNT && rareTerms[key] == null) {
                    rareTerms[key] = Triple(RareContentScorer.RECURRING_LATIN_SCORE, entry.first, entry.second)
                    promotedLatin++
                }
            }
        }

        val digest = buildDigest(rareTerms)
        val excerpts = buildExcerpts(scannedMessages)

        Log.i(
            TAG,
            "[Context] messages=${scannedMessages.size}/${messages.size} " +
                "excerptChars=${excerpts.sumOf { it.length }}/${CorrectionContextBudget.MESSAGE_EXCERPTS} " +
                "digestChars=${digest?.length ?: 0}/${CorrectionContextBudget.RARE_DIGEST} " +
                "recurringLatin=$promotedLatin " +
                "durationMs=${System.currentTimeMillis() - started}",
        )
        return ConversationContext(rareTermsDigest = digest, recentExcerpts = excerpts)
    }

    /** Rarest first, then most frequent, then longest. */
    private fun buildDigest(rareTerms: Map<String, Triple<Int, Int, String>>): String? {
        val ranked = rareTerms.values.sortedWith(
            compareByDescending<Triple<Int, Int, String>> { it.first }
                .thenByDescending { it.second }
                .thenByDescending { it.third.length },
        )
        val terms = mutableListOf<String>()
        var chars = 0
        for (entry in ranked) {
            val cost = entry.third.length + if (terms.isEmpty()) 0 else 1 // +1 for "、"
            // `continue`, NOT `break`: a term too long to fit is skipped so
            // shorter later terms can still be packed in.
            if (chars + cost > CorrectionContextBudget.RARE_DIGEST) continue
            terms.add(entry.third)
            chars += cost
        }
        return if (terms.isEmpty()) null else terms.joinToString("、")
    }

    /**
     * Walk newest → oldest. The two newest turns always contribute plain
     * lead-in grounding; older messages earn a slot ONLY via sentences that
     * carry rare content. Output is re-sorted oldest → newest so the model
     * reads chronologically.
     */
    private fun buildExcerpts(scannedMessages: List<ScannedMessage>): List<String> {
        var budget = CorrectionContextBudget.MESSAGE_EXCERPTS
        val rendered = mutableListOf<Pair<Int, String>>()

        for (m in scannedMessages) {
            if (budget <= 80) break
            val isGrounding = m.newestIndex < 2
            val perMessageCap = minOf(
                if (isGrounding) CorrectionContextBudget.GROUNDING_EXCERPT
                else CorrectionContextBudget.PER_MESSAGE_EXCERPT,
                budget,
            )

            val picked = mutableListOf<String>()
            var used = 0
            // Returns false to stop taking from this message.
            fun take(sentence: String): Boolean {
                var s = sentence
                if (s.length > perMessageCap - used) {
                    // Truncation is allowed only for the FIRST sentence taken;
                    // otherwise we'd cut mid-thought with room already spent.
                    if (picked.isNotEmpty()) return false
                    s = s.take(maxOf(0, perMessageCap - used - 1)) + "…"
                }
                if (s.isEmpty()) return false
                picked.add(s)
                used += s.length
                return used < perMessageCap
            }

            if (isGrounding) {
                for (s in m.sentences) if (!take(s.text)) break
            } else {
                for (s in m.sentences) if (s.strongScore > 0 && !take(s.text)) break
            }
            if (picked.isEmpty()) continue

            val position = if (m.newestIndex == 0) "最新" else "前第${m.newestIndex + 1}条"
            val line = "【${m.role.label}·$position】${picked.joinToString("")}"
            // The per-message cap bounds the SENTENCES, but the rendered line
            // also carries the role label, so a line can still overshoot the
            // remaining budget. Drop it rather than exceed the total — the
            // budget is a promise to the prompt assembler, not a suggestion.
            if (line.length > budget) continue
            rendered.add(m.newestIndex to line)
            budget -= line.length
        }
        return rendered.sortedByDescending { it.first }.map { it.second }
    }
}
