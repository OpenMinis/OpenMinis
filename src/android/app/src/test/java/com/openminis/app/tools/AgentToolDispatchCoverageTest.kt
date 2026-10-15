package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-tool-dispatch-coverage] 注册表 ↔ 分发表对账守卫。
 *
 * ui_skill 曾在 AgentTools 注册（工具描述甚至引导模型"优先用 ui_skill
 * 而不是手动 ui_read+ui_action"），但 ChatViewModelExecuteToolExt 的 when
 * 分发表没有它的分支 —— 模型一调用就落进 "Unknown tool"，整条
 * 录制→匹配→回放管线（A11ySkillStore + SkillReplayEngine）在生产里是死代码。
 *
 * 这个守卫把这类"注册了却没接线"的漂移变成 CI 失败：以运行时注册表
 * （makeAgentTools 全开关）为真值，逐名字核对分发文件里存在对应分支
 * （字面量分支或 `X.NAME` 符号分支解析出的常量值）。新增工具时忘了
 * 接线，这里先红。
 *
 * 已知边界（刻意不收紧）：
 * - 分发文件里出现过的任意字符串都算"已分发"——本守卫抓的是整条分支
 *   缺失（新工具忘接线），不做形式化证明。
 * - 分发侧多出来的分支（注册了又移除）不在守卫范围内。
 */
class AgentToolDispatchCoverageTest {

    @Test
    fun `every registered agent tool has a dispatch branch`() {
        val registry = AgentTools.makeAgentTools(
            supportsImageInput = true,
            visionGroupConfigured = true,
            memoryEnabled = true,
            subAgentEnabled = true,
            codeGraphEnabled = true,
            mcpNativeEnabled = true,
            includeLongTail = true,
        )
        assertTrue("registry must not be empty — parsing broke?", registry.isNotEmpty())

        val dispatch = ProductionSources.read("ui/chat/ChatViewModelExecuteToolExt.kt")
        val dispatched = dispatchedToolNames(dispatch)

        val missing = registry.map { it.name }.filter { it !in dispatched }
        assertTrue(
            "注册表里这些工具在 ChatViewModelExecuteToolExt 没有分发分支（模型调用会得到 " +
                "\"Unknown tool\"）：$missing\n" +
                "要么补 when 分支，要么从 AgentTools 移除注册。",
            missing.isEmpty(),
        )
    }

    /** 分发文件里出现过的工具名集合：字面量分支 + 符号常量分支。 */
    private fun dispatchedToolNames(dispatch: String): Set<String> {
        // 一次性读入全部生产源码文本（符号解析要按 object 名找声明文件）。
        val sources = ProductionSources.allKotlinFiles().map { it.readText() }
        val names = mutableSetOf<String>()

        // 1) 字面量分支："shell_execute", "browser_use", "su_exec", …
        Regex(""""([a-z][a-z0-9_]{2,})"""").findAll(dispatch).forEach {
            names.add(it.groupValues[1])
        }

        // 2) 符号分支：UiSkillTool.NAME / ProductMediaTools.GENERATE_IMAGE /
        //    SubAgentKind.SPAWN_AGENT / AskUserQuestion.ALIAS / …
        //    解析符号 → 找到声明该 object 的生产文件 → 读 const val 值。
        val symbolRefs = Regex("""\b([A-Z][A-Za-z0-9_]*)\.([A-Z][A-Z0-9_]+)\b""")
            .findAll(dispatch)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toSet()
        for ((obj, const) in symbolRefs) {
            val decl = Regex("""\b(object|class) $obj\b""")
            val text = sources.firstOrNull { decl.containsMatchIn(it) } ?: continue
            val value = Regex("""const val $const = "([^"]+)"""")
                .find(text)?.groupValues?.get(1) ?: continue
            names.add(value)
        }
        return names
    }
}
