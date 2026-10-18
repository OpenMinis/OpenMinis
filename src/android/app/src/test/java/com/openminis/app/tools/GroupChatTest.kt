package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupChatTest {
    @Test
    fun transcriptAttributesEverySpeaker() {
        val text = GroupChat.transcript(
            listOf(
                GroupChat.Line("Gemini", "先看需求"),
                GroupChat.Line("DeepSeek", "还要看风险"),
            ),
        )
        assertTrue(text.contains("Gemini：先看需求"))
        assertTrue(text.contains("DeepSeek：还要看风险"))
    }

    @Test
    fun passRepliesAreNotShown() {
        assertTrue(GroupChat.isPass("PASS"))
        assertTrue(GroupChat.isPass("pass。"))
        assertTrue(GroupChat.isPass("无补充"))
        assertFalse(GroupChat.isPass("我补充一个风险：样本太少。"))
    }

    @Test
    fun groupSpeakersAreNotMerged() {
        assertFalse(GroupChat.shouldMergeAssistantTurns("Gemini", "DeepSeek"))
        assertFalse(GroupChat.shouldMergeAssistantTurns(null, "Gemini"))
        assertTrue(GroupChat.shouldMergeAssistantTurns(null, null))
        assertTrue(GroupChat.shouldMergeAssistantTurns("", " "))
    }

    @Test
    fun vendorMatchIgnoresDirtyModelFields() {
        assertEquals("deepseek", GroupChat.vendorKey("models/DeepSeek-V3:latest", "DeepSeek（官方）"))
        assertEquals("deepseek", GroupChat.vendorKey("deep-seek_chat", null))
        assertEquals("openai", GroupChat.vendorKey("gpt4o-mini", "【自定义】GPT-4o"))
        assertEquals("openai", GroupChat.vendorKey("o1preview", null))
        assertEquals("doubao", GroupChat.vendorKey("Doubao-pro-32k", "【豆包】"))
        assertEquals("gemini", GroupChat.vendorKey("gemini2.5pro", "models/gemini-2.5-flash"))
        assertEquals("claude", GroupChat.vendorKey("claude-3.5-sonnet（官方）", null))
        assertEquals("grok", GroupChat.vendorKey("x.ai/grok-3", null))
        assertEquals("qwen", GroupChat.vendorKey("qwen2.5-72b", "通义千问"))
        // [T-brand-model-name-first] The provider TYPE is no longer an input
        // (PR70): an unrecognized id must degrade to unknown — the avatar shows
        // the initial letter, never a guessed vendor mark.
        assertEquals("unknown", GroupChat.vendorKey("custom-model", null))
        assertEquals("unknown", GroupChat.vendorKey("metadata-exporter", null))
    }

    /**
     * [T-brand-cjk-aliases] Chinese speaker names are the group chat's most
     * common form ("智谱清言", "月之暗面"). These used to resolve only through
     * the retired GroupChat alias table; they must keep resolving after the
     * merge into ModelBrandResolver's keyword table.
     */
    @Test
    fun vendorKeyResolvesChineseSpeakerNames() {
        assertEquals("zhipu", GroupChat.vendorKey(null, "智谱清言"))
        assertEquals("kimi", GroupChat.vendorKey(null, "月之暗面 Kimi"))
        assertEquals("qwen", GroupChat.vendorKey(null, "通义千问"))
        assertEquals("deepseek", GroupChat.vendorKey(null, "深度求索"))
        assertEquals("doubao", GroupChat.vendorKey(null, "豆包大模型"))
        assertEquals("hunyuan", GroupChat.vendorKey(null, "腾讯混元"))
        assertEquals("wenxin", GroupChat.vendorKey(null, "文心一言"))
        assertEquals("baichuan", GroupChat.vendorKey(null, "百川智能"))
        assertEquals("hailuo", GroupChat.vendorKey(null, "海螺 AI"))
        assertEquals("unknown", GroupChat.vendorKey(null, "小助手"))
    }

    @Test
    fun promptsKeepTheUserQuestionAndPriorSpeakers() {
        val opinion = GroupChat.opinionPrompt("Gemini", "质疑", "分析这份财报", "DeepSeek：收入口径不一致", "")
        assertTrue(opinion.contains("分析这份财报"))
        assertTrue(opinion.contains("DeepSeek：收入口径不一致"))
        assertTrue(opinion.contains("质疑"))
        val summary = GroupChat.summaryPrompt("分析这份财报", opinion)
        assertTrue(summary.contains("共识"))
        assertTrue(summary.contains("这是讨论的结束汇报"))
        assertEquals("speaker", GroupChat.SPEAKER_PART)
    }

    @Test
    fun mentionAddressesOneSpeakerAndCloseIsExplicit() {
        assertEquals("DeepSeek V3", GroupChat.addressedName("@deepseek 你怎么看", listOf("Gemini", "DeepSeek V3")))
        assertEquals(null, GroupChat.addressedName("邮件是 a@b.com", listOf("Gemini")))
        assertTrue(GroupChat.isCloseRequest("总结一下"))
        assertTrue(GroupChat.isCloseRequest("请总结这场讨论"))
        assertFalse(GroupChat.isCloseRequest("先别总结，继续讨论风险"))
        assertEquals("主张", GroupChat.stance(0))
        assertEquals("质疑", GroupChat.stance(1))
        assertEquals("主张", GroupChat.stance(4))
    }

    @Test
    fun mentionUsesModelIdAndIgnoresSkillPaths() {
        val targets = listOf(
            GroupChat.Addressable("苏苏", listOf("苏苏", "mimo-v2.6-pro")),
            GroupChat.Addressable("DeepSeek V3", listOf("DeepSeek V3", "deepseek-chat")),
        )
        assertEquals("苏苏", GroupChat.resolveAddress("@mimo-v2.6-pro 你先说", targets))
        assertEquals("苏苏", GroupChat.resolveAddress("＠苏苏 继续", targets))
        assertEquals("DeepSeek V3", GroupChat.resolveAddress("先问一下 @DeepSeek V3 这个风险", targets))
        assertEquals(null, GroupChat.resolveAddress("@skills/逆向技能路由 看看", targets))
        assertEquals(null, GroupChat.resolveAddress("邮件是 a@b.com", targets))
    }

    @Test
    fun mentionPickerListsModelsNotSkills() {
        val roster = GroupChat.mentionCandidates(
            GroupChat.MentionCandidate("mimo-v2.6-pro", "mimo-v2.6-pro", "unknown", host = true),
            listOf(
                GroupChat.MentionCandidate("DeepSeek V3", "deepseek-chat", "deepseek", host = false),
                GroupChat.MentionCandidate("DeepSeek V3", "deepseek-reasoner", "deepseek", host = false),
            ),
        )
        assertEquals(listOf("mimo-v2.6-pro", "DeepSeek V3", "DeepSeek V3"), roster.map { it.name })
        assertEquals(emptyList<String>(), GroupChat.filterMentions(roster, "逆向").map { it.name })
        assertEquals(listOf("mimo-v2.6-pro"), GroupChat.filterMentions(roster, "mimo").map { it.name })
        val withSoul = roster.map { if (it.host) it.copy(extra = "苏苏") else it }
        assertEquals(listOf("mimo-v2.6-pro"), GroupChat.filterMentions(withSoul, "苏苏").map { it.name })
        assertEquals("mimo-v2.6-pro", GroupChat.mentionInsertToken(roster[0], roster))
        assertEquals("deepseek-reasoner", GroupChat.mentionInsertToken(roster[2], roster))
    }

    @Test
    fun newRoundDoesNotReuseThePreviousGroupChat() {
        val history = listOf(
            GroupChat.ContextMessage("u1", "user", "上一场：怎么改 deps"),
            GroupChat.ContextMessage("m1", "assistant", "上一场主张：先改 prompt", speakerName = "glm-5.3"),
            GroupChat.ContextMessage("h1", "assistant", "共识\n上一场结论", speakerName = "mimo · 主持", sourceIds = listOf("db-h1")),
            GroupChat.ContextMessage("u2", "user", "当前问题：这场构建为什么失败"),
            GroupChat.ContextMessage("a2", "assistant", "当前分析：缺的是本地模块，不是 deps"),
            GroupChat.ContextMessage("pass", "assistant", "本轮不补充。", speakerName = "deepseek"),
        )
        val speeches = GroupChat.currentSpeeches(
            history,
            markers = listOf("ui-only-id", "h1"),
            hostSuffix = "主持",
            hiddenTexts = setOf("本轮不补充。"),
        )
        assertEquals(emptyList<GroupChat.Line>(), speeches)
        val context = GroupChat.currentConversation(history)
        assertTrue(context.contains("当前问题：这场构建为什么失败"))
        assertTrue(context.contains("当前分析：缺的是本地模块，不是 deps"))
        assertFalse(context.contains("上一场主张"))
        assertFalse(context.contains("上一场结论"))
        assertFalse(context.contains("本轮不补充"))
    }

    @Test
    fun staleCloseIdStillStopsAtTheHostReport() {
        val history = listOf(
            GroupChat.ContextMessage("old", "assistant", "上一场发言", speakerName = "glm"),
            GroupChat.ContextMessage("host", "assistant", "共识", speakerName = "mimo · 主持", sourceIds = listOf("db-host")),
            GroupChat.ContextMessage("now", "assistant", "这一轮才说的", speakerName = "deepseek"),
        )
        val speeches = GroupChat.currentSpeeches(
            history,
            markers = listOf("missing-ui-id"),
            hostSuffix = "主持",
        )
        assertEquals(listOf(GroupChat.Line("deepseek", "这一轮才说的")), speeches)
        assertEquals(
            2,
            GroupChat.roundStartIndex(history, listOf("db-host"), "主持"),
        )
    }

    @Test
    fun openAnchorDropsAnUnfinishedPreviousRound() {
        val history = listOf(
            GroupChat.ContextMessage("old", "assistant", "没结束的上一场", speakerName = "glm"),
            GroupChat.ContextMessage("tail", "assistant", "当前对话", sourceIds = listOf("db-tail")),
            GroupChat.ContextMessage("now", "assistant", "新一轮发言", speakerName = "deepseek"),
        )
        val speeches = GroupChat.currentSpeeches(history, markers = listOf("db-tail"), hostSuffix = "主持")
        assertEquals(listOf(GroupChat.Line("deepseek", "新一轮发言")), speeches)
    }

    @Test
    fun collidingDisplayNamesStillAddressOneModel() {
        val targets = listOf(
            GroupChat.Addressable(
                "DeepSeek V3",
                aliases = listOf("DeepSeek V3", "deepseek-chat"),
                key = "deepseek-chat",
            ),
            GroupChat.Addressable(
                "DeepSeek V3",
                aliases = listOf("DeepSeek V3", "deepseek-reasoner"),
                key = "deepseek-reasoner",
            ),
        )
        assertEquals("deepseek-reasoner", GroupChat.resolveTarget("@deepseek-reasoner 你说", targets)?.key)
        assertEquals("deepseek-chat", GroupChat.resolveTarget("@DeepSeek V3 你说", targets)?.key)
    }

    @Test
    fun oneCharacterReplyIsNotASpeech() {
        assertFalse(GroupChat.isSubstantive("对"))
        assertFalse(GroupChat.isSubstantive("同意"))
        assertEquals("", GroupChat.recoverUtterance("对", ""))
    }

    @Test
    fun shortTextRecoversTheThinkingConclusion() {
        val thinking = "先核对进程是否还在。\n\n真正要看的是 pid 和容器命名空间，不能只杀宿主机上的同名进程。"
        val recovered = GroupChat.recoverUtterance("对", thinking)
        assertTrue(recovered.contains("pid"))
        assertFalse(recovered == "对")
    }

    @Test
    fun substantiveTextIsKept() {
        val text = "先确认容器里的 pid，再决定要不要杀进程。宿主机同名进程不一定是目标。"
        assertTrue(GroupChat.isSubstantive(text))
        assertEquals(text, GroupChat.recoverUtterance(text, "很长的思考过程"))
    }

    @Test
    fun openingPromptDoesNotAskTheHostToConclude() {
        val prompt = GroupChat.openingPrompt("这个报错怎么看？", "")
        assertTrue(prompt.contains("不要给结论"))
        assertTrue(prompt.contains("不要参与"))
        assertFalse(prompt.contains("你的立场"))
    }
}
