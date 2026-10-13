package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.service.SubAgentActivityTracker
import com.openminis.app.tools.SubAgentRunner
import com.openminis.app.ui.markdown.MarkdownText
import com.openminis.app.ui.theme.ChatColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubAgentLiveBar(
    sessionId: String,
    modifier: Modifier = Modifier,
    onStop: (String) -> Unit = {},
) {
    val members by SubAgentActivityTracker.members.collectAsState()
    val mine = members.filter { it.parentSessionId == sessionId }
    var detailMemberId by remember { mutableStateOf<String?>(null) }

    if (mine.isNotEmpty()) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ChatColors.background.copy(alpha = 0.94f))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        mine.forEach { m ->
            key(m.id) {
            val bg = phaseColor(m.phase.ifBlank { "思考中" })
            val label = buildString {
                if (m.index > 0 && m.total > 0) append("子代理 ${m.index}/${m.total}")
                else append(m.title.take(12))
                append(" · ").append(m.phase.ifBlank { "思考中" })
                m.currentTool.takeIf { it.isNotBlank() }?.let { append(" · ").append(subAgentToolTitle(it, "")) }
                if (m.turnCap > 0) append(" · ${m.turnIndex.coerceAtLeast(1)}/${m.turnCap}")
            }
            Row(
                modifier = Modifier
                    .background(bg, RoundedCornerShape(999.dp))
                    .padding(start = 10.dp, end = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .clickable { detailMemberId = m.id }
                        .padding(vertical = 4.dp),
                )
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = "Stop ${m.title}",
                    tint = Color.White,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onStop(m.id) }
                        .padding(3.dp),
                )
            }
            }
        }
    }
    }
    val detailMember = mine.firstOrNull { it.id == detailMemberId }
    if (detailMember != null) {
        SubAgentDetailSheet(
            member = detailMember,
            onDismiss = { detailMemberId = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubAgentDetailSheet(
    member: SubAgentActivityTracker.Member,
    onDismiss: () -> Unit,
) {
    val phase = member.phase.ifBlank { "思考中" }
    var expandedToolId by remember(member.id) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val tail = member.steps.lastOrNull()
    val liveToolId = member.steps.lastOrNull {
        it.kind == "tool" && (it.status == "running" || it.status == "streaming" || it.status == "pending")
    }?.id
    LaunchedEffect(liveToolId) {
        if (liveToolId != null) expandedToolId = liveToolId
    }
    LaunchedEffect(member.steps.size, tail?.body?.length, tail?.status, phase) {
        val total = listState.layoutInfo.totalItemsCount
        if (total <= 0) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= total - 2) listState.scrollToItem(total - 1)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ChatColors.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = memberLabel(member),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ChatColors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(
                        member.role?.takeIf { it.isNotBlank() },
                        member.model?.takeIf { it.isNotBlank() },
                        if (member.turnCap > 0) "第 ${member.turnIndex.coerceAtLeast(1)}/${member.turnCap} 轮" else null,
                    ).joinToString(" · "),
                    fontSize = 12.sp,
                    color = ChatColors.primaryText.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(8.dp))
                PhaseBanner(phase = phase, tool = member.currentTool)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "task") {
                    UserTaskBubble(member.title)
                }
                if (member.steps.isEmpty()) {
                    item(key = "waiting") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TypingIndicator()
                            Text(
                                text = "思考中，还没有输出",
                                fontSize = 13.sp,
                                color = ChatColors.primaryText.copy(alpha = 0.6f),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
                items(member.steps, key = { it.id }) { step ->
                    when (step.kind) {
                        "thinking" -> ThinkingBlock(
                            block = step.toAssistantBlock(),
                            isStreaming = step.status == "streaming",
                            isLast = step.id == member.steps.lastOrNull { it.kind == "thinking" }?.id,
                        )
                        "tool" -> Column {
                            ToolCallPill(
                                block = step.toAssistantBlock(),
                                onOpenDetail = { id ->
                                    expandedToolId = if (expandedToolId == id) null else id
                                },
                            )
                            if (expandedToolId == step.id) {
                                ToolStepBody(step)
                            }
                        }
                        else -> MarkdownText(
                            markdown = step.body,
                            color = ChatColors.primaryText,
                            style = TextStyle(
                                fontSize = 15.sp,
                                lineHeight = 22.sp,
                                color = ChatColors.primaryText,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseBanner(phase: String, tool: String) {
    val color = phaseColor(phase)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .border(0.5.dp, color.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .background(color, RoundedCornerShape(999.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(text = phase, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (phase == "思考中" || phase == "回复中" || phase == "执行中") {
            Spacer(modifier = Modifier.padding(start = 4.dp))
            StreamingDotsText()
        }
        if (tool.isNotBlank()) {
            Text(
                text = subAgentToolTitle(tool, ""),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = ChatColors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun ToolStepBody(step: SubAgentActivityTracker.Step) {
    val status = when (step.status) {
        "running" -> "执行中"
        "streaming", "pending" -> "调用工具"
        "success" -> "已完成"
        "failed" -> "失败"
        else -> step.status
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
            .background(ChatColors.secondaryBg, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Text(
            text = status,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = phaseColor(
                when (step.status) {
                    "running" -> "执行中"
                    "success" -> "执行中"
                    "failed" -> "调用工具"
                    else -> "调用工具"
                },
            ),
        )
        if (step.toolArgs.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = "参数", fontSize = 11.sp, color = ChatColors.primaryText.copy(alpha = 0.5f))
            Text(
                text = step.toolArgs,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = ChatColors.primaryText,
                modifier = Modifier
                    .heightIn(max = 120.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        if (step.body.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = "结果", fontSize = 11.sp, color = ChatColors.primaryText.copy(alpha = 0.5f))
            Text(
                text = step.body,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = ChatColors.primaryText,
                modifier = Modifier
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun UserTaskBubble(title: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text = title.ifBlank { "子代理任务" },
            color = ChatColors.primaryText,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(ChatColors.userBubble, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

private fun memberLabel(member: SubAgentActivityTracker.Member): String =
    if (member.index > 0 && member.total > 0) "子代理 ${member.index}/${member.total}"
    else member.title.ifBlank { "子代理" }

private fun phaseColor(phase: String): Color = when (phase) {
    "调用工具" -> Color(0xFFFF9500)
    "执行中" -> Color(0xFF34C759)
    "回复中" -> Color(0xFF5856D6)
    else -> Color(0xFF007AFF)
}

internal fun subAgentToolTitle(name: String, args: String): String {
    val label = when (name) {
        "shell_execute", "shell_exec", "env_exec" -> "执行命令"
        "file_read" -> "读取文件"
        "file_write" -> "写入文件"
        "file_edit", "multi_edit" -> "编辑文件"
        "grep_source", "grep" -> "搜索代码"
        "web_search", "clientfn_web_search" -> "搜索网页"
        "web_fetch" -> "抓取网页"
        "browser_use" -> "浏览器"
        "memory_get", "memory_write" -> "记忆"
        "spawn_agent", "dispatch_agents" -> "子代理"
        else -> name
    }
    val preview = if (args.isBlank()) "" else SubAgentRunner.previewToolArgs(args).take(42)
    return if (preview.isBlank()) label else "$label · $preview"
}

private fun SubAgentActivityTracker.Step.toAssistantBlock(): AssistantBlock {
    val toolStatus = when (status) {
        "streaming" -> ToolBlockStatus.STREAMING
        "pending" -> ToolBlockStatus.PENDING
        "running" -> ToolBlockStatus.RUNNING
        "success" -> ToolBlockStatus.SUCCESS
        "failed" -> ToolBlockStatus.FAILED
        else -> null
    }
    return AssistantBlock(
        id = id,
        kind = if (kind == "thinking" || kind == "text") kind else "tool_use",
        content = body,
        toolStatus = toolStatus,
        toolTitle = if (kind == "tool") subAgentToolTitle(toolName, toolArgs) else title,
        toolName = toolName,
        toolArgs = toolArgs,
        durationMs = durationMs,
        startTimeMs = startedAt,
    )
}
