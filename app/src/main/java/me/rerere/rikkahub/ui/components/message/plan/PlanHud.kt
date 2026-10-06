package me.rerere.rikkahub.ui.components.message.plan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.plan.InteractivePlanOperation
import me.rerere.rikkahub.data.ai.tools.local.plan.InteractivePlanView
import me.rerere.rikkahub.data.ai.tools.local.plan.PlanQuestion
import me.rerere.rikkahub.data.ai.tools.local.plan.PlanStep
import me.rerere.rikkahub.data.ai.tools.local.plan.PlanStepStatus
import me.rerere.rikkahub.data.ai.tools.local.plan.buildPlanView
import me.rerere.rikkahub.data.ai.tools.local.plan.decodePlanSnapshot
import me.rerere.rikkahub.data.ai.tools.local.plan.parsePlanRequest
import me.rerere.rikkahub.data.ai.tools.local.plan.parsePlanUserAnswer
import me.rerere.rikkahub.data.ai.tools.local.plan.toView
import me.rerere.rikkahub.data.ai.tools.local.plan.unmetDependencies
import me.rerere.rikkahub.data.ai.tools.local.plan.weightedProgress
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode

private const val PLAN_TOOL_NAME = "interactive_plan"

/** 输入框上方 HUD 所需的「当前活跃计划」快照。 */
data class ActivePlan(
    val view: InteractivePlanView,
    val toolCallId: String,
    /** messageNodes 中的下标，用于「点击跳转到聊天里那张卡」。 */
    val nodeIndex: Int,
    /** 工具仍处于等待用户处理的状态。 */
    val isPending: Boolean,
    /** 尚未回答的必答问题。 */
    val pendingQuestions: List<PlanQuestion>,
) {
    val awaitingUser: Boolean get() = isPending || pendingQuestions.isNotEmpty()
}

/**
 * 从会话中找出「当前活跃计划」。
 *
 * 规则：从最新消息往回找最后一张 interactive_plan 卡片；
 * 若它是 cancel 收尾，则认为没有活跃计划（HUD 不显示）。
 */
fun Conversation.findActivePlan(): ActivePlan? {
    for (nodeIndex in messageNodes.indices.reversed()) {
        val node: MessageNode = messageNodes[nodeIndex]
        val message: UIMessage = node.messages.getOrNull(node.selectIndex) ?: continue
        val tools = message.getTools().filter { it.toolName == PLAN_TOOL_NAME }
        for (tool in tools.asReversed()) {
            val request = parsePlanRequest(tool.inputAsJson())
            val state = tool.approvalState
            val view = when {
                state is ToolApprovalState.Answered ->
                    buildPlanView(request, parsePlanUserAnswer(state.answer))

                else -> tool.output
                    .filterIsInstance<UIMessagePart.Text>()
                    .firstOrNull()
                    ?.text
                    ?.let { decodePlanSnapshot(it) }
                    ?.toView()
                    ?: buildPlanView(request, null)
            }
            if (view.operation == InteractivePlanOperation.CANCEL) return null

            val pendingQuestions = view.questions.filter {
                it.required && it.isVisible(view.answers) && view.answers[it.id].isNullOrBlank()
            }
            return ActivePlan(
                view = view,
                toolCallId = tool.toolCallId,
                nodeIndex = nodeIndex,
                isPending = tool.isPending,
                pendingQuestions = pendingQuestions,
            )
        }
    }
    return null
}

/**
 * 输入框上方的计划 HUD。
 *
 * - 折叠态：当前步骤 + 进度 + 计数（一行）。
 * - 展开态：按 正在进行 / 待处理 / 已阻塞 / 已完成 分区列出全部步骤。
 * - 无活跃计划时完全不占用空间。
 */
@Composable
fun PlanHudPanel(
    activePlan: ActivePlan?,
    typing: Boolean,
    onJumpToPlan: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (activePlan == null) return

    val view = activePlan.view
    var expanded by rememberSaveable(activePlan.toolCallId) { mutableStateOf(false) }

    val signature = buildString {
        append(view.operation)
        append('-')
        append(view.doneCount)
        append('/')
        append(view.totalCount)
        view.steps.forEach { append(it.status.name.first()) }
    }
    var lastSignature by remember(activePlan.toolCallId) { mutableStateOf(signature) }

    // 计划有推进 -> 自动展开一小会儿；等待用户处理 -> 保持展开；用户开始打字 -> 收起。
    LaunchedEffect(signature, activePlan.awaitingUser) {
        if (signature != lastSignature) {
            lastSignature = signature
            expanded = true
            if (!activePlan.awaitingUser) {
                delay(3000)
                expanded = false
            }
        }
    }
    LaunchedEffect(activePlan.awaitingUser) {
        // 需要用户处理时自动展开；处理完后自动收起，保持输入区清爽。
        expanded = activePlan.awaitingUser
    }
    LaunchedEffect(typing) {
        if (typing) expanded = false
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        // 水平内边距由 ChatInput 的 header 容器统一提供，这里只保证占满宽度，避免双重缩进。
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            PlanHudHeader(
                activePlan = activePlan,
                expanded = expanded,
                onToggle = { expanded = !expanded },
                onJumpToPlan = onJumpToPlan,
            )

            AnimatedVisibility(visible = expanded) {
                PlanHudBody(
                    view = view,
                    onJumpToPlan = onJumpToPlan,
                    nodeIndex = activePlan.nodeIndex,
                )
            }
        }
    }
}

@Composable
private fun PlanHudHeader(
    activePlan: ActivePlan,
    expanded: Boolean,
    onToggle: () -> Unit,
    onJumpToPlan: (Int) -> Unit,
) {
    val view = activePlan.view
    val progress = view.steps.weightedProgress()
    val done = view.doneCount
    val total = view.totalCount

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (activePlan.pendingQuestions.isNotEmpty()) {
                val breath = rememberInfiniteTransition(label = "planAsk")
                val alpha by breath.animateFloat(
                    initialValue = 0.55f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 900, easing = LinearEasing),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                    ),
                    label = "alpha",
                )
                Icon(
                    imageVector = HugeIcons.BubbleChatQuestion,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                )
            } else {
                PlanStatusGlyph(
                    status = leadingStatus(view),
                    size = 16.dp,
                )
            }

            Text(
                text = collapsedHeadline(activePlan),
                style = MaterialTheme.typography.labelLarge,
                color = if (activePlan.awaitingUser) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            Text(
                text = stringResource(R.string.chat_message_tool_interactive_plan_progress, done, total),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Icon(
                imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                contentDescription = stringResource(
                    if (expanded) R.string.plan_hud_collapse else R.string.plan_hud_expand
                ),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
        )
    }
}

@Composable
private fun collapsedHeadline(activePlan: ActivePlan): String {
    val view = activePlan.view
    if (activePlan.pendingQuestions.isNotEmpty()) {
        return stringResource(R.string.plan_hud_required_unanswered, activePlan.pendingQuestions.size)
    }
    if (activePlan.isPending) {
        return stringResource(R.string.plan_hud_waiting)
    }
    if (view.totalCount > 0 && view.doneCount >= view.totalCount) {
        return stringResource(R.string.plan_hud_all_done)
    }
    val current = view.steps.firstOrNull { it.status == PlanStepStatus.IN_PROGRESS }
        ?: view.steps.firstOrNull { it.status == PlanStepStatus.PENDING }
    return if (current != null) {
        stringResource(R.string.plan_hud_current_step, current.title)
    } else {
        stringResource(R.string.plan_hud_waiting)
    }
}

@Composable
private fun PlanHudBody(
    view: InteractivePlanView,
    nodeIndex: Int,
    onJumpToPlan: (Int) -> Unit,
) {
    val active = view.steps.filter { it.status == PlanStepStatus.IN_PROGRESS }
    val pending = view.steps.filter { it.status == PlanStepStatus.PENDING }
    val blocked = view.steps.filter { it.status == PlanStepStatus.BLOCKED }
    val completed = view.steps.filter { it.status == PlanStepStatus.COMPLETED || it.status == PlanStepStatus.SKIPPED }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlanHudSection(
            title = stringResource(R.string.plan_hud_section_active),
            steps = active,
            allSteps = view.steps,
            onJumpToPlan = { onJumpToPlan(nodeIndex) },
        )
        PlanHudSection(
            title = stringResource(R.string.plan_hud_section_pending),
            steps = pending,
            allSteps = view.steps,
            onJumpToPlan = { onJumpToPlan(nodeIndex) },
        )
        PlanHudSection(
            title = stringResource(R.string.plan_hud_section_blocked),
            steps = blocked,
            allSteps = view.steps,
            onJumpToPlan = { onJumpToPlan(nodeIndex) },
            emphasize = true,
        )
        PlanHudSection(
            title = stringResource(R.string.plan_hud_section_completed),
            steps = completed,
            allSteps = view.steps,
            onJumpToPlan = { onJumpToPlan(nodeIndex) },
        )
    }
}

@Composable
private fun PlanHudSection(
    title: String,
    steps: List<PlanStep>,
    allSteps: List<PlanStep>,
    onJumpToPlan: () -> Unit,
    emphasize: Boolean = false,
) {
    if (steps.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasize) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
        )
        steps.forEach { step ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onJumpToPlan)
                    .padding(vertical = 2.dp),
            ) {
                PlanStatusGlyph(status = step.status, size = 14.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = stepHudMeta(step, allSteps)
                    if (meta != null) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun stepHudMeta(step: PlanStep, allSteps: List<PlanStep>): String? {
    val parts = buildList {
        val elapsed = step.elapsedMillis()
        if (elapsed != null && elapsed > 0) {
            add(stringResource(R.string.chat_message_tool_interactive_plan_elapsed, formatElapsed(elapsed)))
        }
        if (step.status == PlanStepStatus.BLOCKED && step.blockedReason.isNotBlank()) {
            add(step.blockedReason)
        }
        val unmet = step.unmetDependencies(allSteps)
        if (unmet.isNotEmpty() && step.status != PlanStepStatus.COMPLETED) {
            add(stringResource(R.string.chat_message_tool_interactive_plan_waiting_on, unmet.joinToString(", ") { it.title }))
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun formatElapsed(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

private fun leadingStatus(view: InteractivePlanView): PlanStepStatus = when {
    view.steps.any { it.status == PlanStepStatus.IN_PROGRESS } -> PlanStepStatus.IN_PROGRESS
    view.steps.any { it.status == PlanStepStatus.BLOCKED } -> PlanStepStatus.BLOCKED
    view.totalCount > 0 && view.doneCount >= view.totalCount -> PlanStepStatus.COMPLETED
    else -> PlanStepStatus.PENDING
}

/**
 * 计划步骤的状态图形。
 *
 * 几何状态（待处理的圆环 / 进行中的圆弧 / 已跳过的短横）用 Canvas 实时绘制，
 * 具备完成 / 阻塞语义的两态使用 HugeIcons（非 Material 图标集）。
 */
@Composable
fun PlanStatusGlyph(
    status: PlanStepStatus,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val activeColor = MaterialTheme.colorScheme.primary

    when (status) {
        PlanStepStatus.COMPLETED -> Icon(
            imageVector = HugeIcons.Tick02,
            contentDescription = null,
            modifier = modifier.size(size),
            tint = MaterialTheme.colorScheme.tertiary,
        )

        PlanStepStatus.BLOCKED -> Icon(
            imageVector = HugeIcons.Alert01,
            contentDescription = null,
            modifier = modifier.size(size),
            tint = MaterialTheme.colorScheme.error,
        )

        PlanStepStatus.PENDING -> Canvas(modifier = modifier.size(size)) {
            val sw = 1.5.dp.toPx()
            drawCircle(
                color = trackColor,
                radius = this.size.minDimension / 2 - sw,
                style = Stroke(width = sw),
            )
        }

        PlanStepStatus.SKIPPED -> Canvas(modifier = modifier.size(size)) {
            drawLine(
                color = trackColor,
                start = Offset(this.size.width * 0.22f, this.size.height / 2),
                end = Offset(this.size.width * 0.78f, this.size.height / 2),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        PlanStepStatus.IN_PROGRESS -> {
            val transition = rememberInfiniteTransition(label = "planGlyph")
            val angle by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 1100, easing = LinearEasing)),
                label = "angle",
            )
            Canvas(modifier = modifier.size(size)) {
                val sw = 2.dp.toPx()
                drawCircle(
                    color = trackColor,
                    radius = this.size.minDimension / 2 - sw / 2,
                    style = Stroke(width = sw),
                )
                drawArc(
                    color = activeColor,
                    startAngle = angle,
                    sweepAngle = 280f,
                    useCenter = false,
                    topLeft = Offset(sw / 2, sw / 2),
                    size = Size(this.size.width - sw, this.size.height - sw),
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
            }
        }
    }
}
