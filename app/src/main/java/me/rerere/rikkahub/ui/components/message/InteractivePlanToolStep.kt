package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Task01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.plan.InteractivePlanOperation
import me.rerere.rikkahub.data.ai.tools.local.plan.InteractivePlanUserAnswer
import me.rerere.rikkahub.data.ai.tools.local.plan.InteractivePlanView
import me.rerere.rikkahub.data.ai.tools.local.plan.PlanQuestion
import me.rerere.rikkahub.data.ai.tools.local.plan.PlanSelectionType
import me.rerere.rikkahub.data.ai.tools.local.plan.PlanStep
import me.rerere.rikkahub.data.ai.tools.local.plan.PlanStepStatus
import me.rerere.rikkahub.data.ai.tools.local.plan.UserPlanStep
import me.rerere.rikkahub.data.ai.tools.local.plan.buildPlanView
import me.rerere.rikkahub.data.ai.tools.local.plan.childrenOf
import me.rerere.rikkahub.data.ai.tools.local.plan.rootSteps
import me.rerere.rikkahub.data.ai.tools.local.plan.decodePlanSnapshot
import me.rerere.rikkahub.data.ai.tools.local.plan.toView
import me.rerere.rikkahub.data.ai.tools.local.plan.unmetDependencies
import me.rerere.rikkahub.data.ai.tools.local.plan.weightedProgress
import me.rerere.rikkahub.data.ai.tools.local.plan.encodePlanUserAnswer
import me.rerere.rikkahub.data.ai.tools.local.plan.parsePlanRequest
import me.rerere.rikkahub.data.ai.tools.local.plan.parsePlanUserAnswer
import androidx.compose.material3.IconButton
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.data.ai.tools.local.plan.buildStepReplayPrompt
import me.rerere.rikkahub.ui.components.message.plan.PlanAmbientGlow
import me.rerere.rikkahub.ui.components.message.plan.PlanStatusGlyph
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import androidx.compose.material3.OutlinedButton
import com.rerere.rikkahub.data.ai.tools.local.plan.subStepProgress
import com.rerere.rikkahub.data.ai.tools.local.plan.toMarkdownChecklist
import com.rerere.rikkahub.data.ai.tools.local.plan.canAutoRetry
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import me.rerere.rikkahub.ui.components.ui.DotLoading

/**
 * 交互式计划卡片的聊天流渲染。
 *
 * 和 ask_user 一样，不走注册式渲染框架，而是在 [ChatMessageToolStep] 里被直接分发。
 *
 * 生命周期：
 * - 未回答：渲染可编辑的计划卡（改目标/步骤状态/回答条件问题）。
 * - 已回答：把「模型原始请求」+「用户操作答案」合并成最终视图，**始终可展开回看**，
 *   并展示进度、各步骤耗时与用户回答。
 */
@Composable
fun ChainOfThoughtScope.InteractivePlanToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
    onRerunStep: ((prompt: String) -> Unit)? = null,
) {
    val arguments = tool.inputAsJson()
    val initialRequest = remember(arguments) { parsePlanRequest(arguments) }

    var goal by remember(tool.toolCallId) { mutableStateOf(initialRequest.goal) }
    val steps = remember(tool.toolCallId) {
        mutableStateMapOf<String, PlanStep>().apply {
            initialRequest.steps.forEach { put(it.id, it) }
        }
    }
    val questions = initialRequest.questions

    val stepOrder by remember(tool.toolCallId) {
        mutableStateOf(initialRequest.stepOrder.ifEmpty { initialRequest.steps.map { it.id } })
    }

    val answers = remember(tool.toolCallId) { mutableStateMapOf<String, String>() }
    val multiAnswers = remember(tool.toolCallId) { mutableStateMapOf<String, Set<String>>() }

    // 默认折叠，避免长计划挤占聊天流；用户点标题即可展开。
    var expanded by remember(tool.toolCallId) { mutableStateOf(false) }

    // 只读结果视图，优先级：
    // 1) 工具已执行且有输出快照 -> 用最新快照（包含模型更新后的最新状态）；
    // 2) 用户已回答 -> 用「原始入参 + 用户答案」重建；
    // 3) 否则为 null，渲染可编辑的计划卡。
    // 关键修复：工具输出快照优先于用户答案，因为模型在用户确认后
    // 可能已经通过 create_or_update 更新了步骤状态，快照才是最新数据。
    val resultView = remember(tool.toolCallId, tool.approvalState, tool.output, arguments) {
        val state = tool.approvalState
        val snapshotView = tool.output
            .filterIsInstance<UIMessagePart.Text>()
            .firstOrNull()
            ?.text
            ?.let { decodePlanSnapshot(it) }
            ?.toView()
        val answeredView = if (state is ToolApprovalState.Answered) {
            buildPlanView(initialRequest, parsePlanUserAnswer(state.answer))
        } else null

        when {
            // Snapshot has steps: use it (most recent data)
            snapshotView != null && snapshotView.steps.isNotEmpty() -> snapshotView
            // Snapshot exists but empty steps: fall back to answered view
            snapshotView != null && answeredView != null && answeredView.steps.isNotEmpty() -> answeredView
            // Answered view has steps
            answeredView != null && answeredView.steps.isNotEmpty() -> answeredView
            // Snapshot exists but no fallback has steps: still use snapshot (it has at least a goal)
            snapshotView != null -> snapshotView
            // Answered view exists but might have empty steps: use it if it has a goal
            answeredView != null -> answeredView
            else -> null
        }
    }

    val editorSteps = steps.values
        .filter { it.id in stepOrder }
        .sortedBy { stepOrder.indexOf(it.id) }

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.Task01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = stringResource(R.string.chat_message_tool_interactive_plan_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        },
        extra = {
            val progressSteps = resultView?.steps ?: editorSteps
            if (progressSteps.isNotEmpty()) {
                ProgressChip(
                    done = progressSteps.count { it.isDone },
                    total = progressSteps.size,
                )
            }
        },
        content = {
            PlanAmbientGlow(
                active = resultView == null || resultView.operation != InteractivePlanOperation.CANCEL,
                colors = listOf(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.tertiary,
                    MaterialTheme.colorScheme.secondary,
                    MaterialTheme.colorScheme.primary,
                ),
            ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (resultView != null) {
                    PlanResultContent(view = resultView, onRerunStep = onRerunStep)
                } else {
                    PlanProgressHeader(steps = editorSteps)

                    PlanEditorContent(
                        goal = goal,
                        onGoalChange = { goal = it },
                        message = initialRequest.message,
                        steps = editorSteps,
                        onStepTitleChange = { id, title ->
                            steps[id]?.let { steps[id] = it.copy(title = title) }
                        },
                        onStepStatusChange = { id, status ->
                            steps[id]?.let { steps[id] = it.withStatus(status) }
                        },
                        questions = questions,
                        answers = answers,
                        multiAnswers = multiAnswers,
                    )

                    val visibleQuestions = questions.filter { it.isVisible(answers, multiAnswers) }
                    val hasUnansweredRequired = visibleQuestions.any { q ->
                        q.required && answers[q.id].isNullOrBlank() && multiAnswers[q.id].isNullOrEmpty()
                    }

                    if (hasUnansweredRequired) {
                        Text(
                            text = stringResource(R.string.chat_message_tool_interactive_plan_required_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    PlanActions(
                        enabled = true,
                        confirmEnabled = !hasUnansweredRequired,
                        onConfirm = {
                            val userAnswer = buildUserAnswer(
                                operation = InteractivePlanOperation.CONFIRM,
                                goal = goal,
                                steps = steps.values.toList(),
                                stepOrder = stepOrder,
                                questions = questions,
                                answers = answers,
                                multiAnswers = multiAnswers,
                            )
                            onToolAnswer?.invoke(tool.toolCallId, encodePlanUserAnswer(userAnswer))
                        },
                        onAdjust = {
                            val userAnswer = buildUserAnswer(
                                operation = InteractivePlanOperation.CREATE_OR_UPDATE,
                                goal = goal,
                                steps = steps.values.toList(),
                                stepOrder = stepOrder,
                                questions = questions,
                                answers = answers,
                                multiAnswers = multiAnswers,
                            )
                            onToolAnswer?.invoke(tool.toolCallId, encodePlanUserAnswer(userAnswer))
                        },
                        onComplete = {
                            val userAnswer = InteractivePlanUserAnswer(
                                operation = InteractivePlanOperation.COMPLETE,
                                editedGoal = goal,
                                updatedSteps = steps.values.map { it.toUserStep() },
                                stepOrder = stepOrder,
                            )
                            onToolAnswer?.invoke(tool.toolCallId, encodePlanUserAnswer(userAnswer))
                        },
                        onCancel = {
                            val userAnswer = InteractivePlanUserAnswer(operation = InteractivePlanOperation.CANCEL)
                            onToolAnswer?.invoke(tool.toolCallId, encodePlanUserAnswer(userAnswer))
                        },
                    )
                }
            }
            }
        },
    )
}

private fun PlanStep.toUserStep(): UserPlanStep = UserPlanStep(
    id = id,
    title = title,
    detail = detail,
    status = status,
    needsConfirmation = needsConfirmation,
    startedAt = startedAt,
    finishedAt = finishedAt,
    blockedReason = blockedReason,
)

/** 紧凑的进度角标，显示在标题行右侧。 */
@Composable
private fun ProgressChip(done: Int, total: Int) {
    val complete = total > 0 && done >= total
    val container = if (complete) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val onContainer = if (complete) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        color = container,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = stringResource(R.string.chat_message_tool_interactive_plan_progress, done, total),
            style = MaterialTheme.typography.labelSmall,
            color = onContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** 进度条 + 完成率（进行中的步骤按半程计入，进度更跟手）。 */
@Composable
private fun PlanProgressHeader(steps: List<PlanStep>, modifier: Modifier = Modifier) {
    if (steps.isEmpty()) return
    val done = steps.count { it.isDone }
    val total = steps.size
    val progress = steps.weightedProgress()

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.chat_message_tool_interactive_plan_progress, done, total),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val blocked = steps.count { it.status == PlanStepStatus.BLOCKED }
        if (blocked > 0) {
            Text(
                text = stringResource(R.string.chat_message_tool_interactive_plan_blocked_count, blocked),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 已回答后的只读结果视图：完整展示目标、进度、步骤（含耗时/阻塞原因）与用户回答。
 */
@Composable
private fun PlanResultContent(
    view: InteractivePlanView,
    onRerunStep: ((prompt: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    if (view.goal.isNotBlank()) {
        Text(
            text = view.goal,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    if (view.message.isNotBlank()) {
        Text(
            text = view.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    if (view.steps.isNotEmpty()) {
        PlanProgressHeader(steps = view.steps)

        // Compact dependency graph: nodes + edges, phone-friendly
        PlanDependencyGraph(
            steps = view.steps,
            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.chat_message_tool_interactive_plan_steps),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            // 树形渲染：顶层步骤（含子步骤进度条）+ 缩进的子步骤
            val roots = view.steps.rootSteps().ifEmpty { view.steps }
            roots.forEach { root ->
                val subProgress = view.steps.subStepProgress(root.id)
                if (subProgress != null) {
                    // Parent step with sub-steps: show mini progress bar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = { subProgress },
                            modifier = Modifier.weight(1f).height(4.dp),
                        )
                        Text(
                            text = "${(subProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                PlanStepResultRow(
                    step = root,
                    allSteps = view.steps,
                    depth = 0,
                    onRerun = onRerunStep?.let { cb -> { cb(view.buildStepReplayPrompt(root)) } },
                )
                view.steps.childrenOf(root.id).forEach { child ->
                    PlanStepResultRow(
                        step = child,
                        allSteps = view.steps,
                        depth = 1,
                        onRerun = onRerunStep?.let { cb -> { cb(view.buildStepReplayPrompt(child)) } },
                    )
                }
            }
        }
    }

    // Export to Markdown button (after all steps are done)
    if (view.steps.isNotEmpty() && view.doneCount >= view.totalCount && view.operation == InteractivePlanOperation.COMPLETE) {
        OutlinedButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val markdown = view.steps.toMarkdownChecklist(view.goal)
                clipboard?.setPrimaryClip(ClipData.newPlainText("plan", markdown))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Copy as Markdown checklist")
        }
    }

    val visibleQuestions = view.questions.filter { it.isVisible(view.answers) }
    if (visibleQuestions.isNotEmpty()) {
        HorizontalDivider()
        Text(
            text = stringResource(R.string.chat_message_tool_interactive_plan_answers),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        visibleQuestions.forEach { q ->
            val answer = view.answers[q.id].orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = q.question,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = answer.ifBlank {
                        stringResource(R.string.chat_message_tool_interactive_plan_no_answer)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (answer.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}

/** 单条步骤的只读结果行。 */
@Composable
private fun PlanStepResultRow(
    step: PlanStep,
    allSteps: List<PlanStep>,
    depth: Int = 0,
    onRerun: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp),
    ) {
        PlanStatusGlyph(status = step.status, size = 12.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (step.detail.isNotBlank()) {
                Text(
                    text = step.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val meta = buildMetaText(step, allSteps)
            if (meta != null) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (step.evidence.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = step.evidence,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            if (step.retryCount > 0 && step.lastRetryReason.isNotBlank()) {
                Text(
                    text = "Retry ${step.retryCount}/${step.maxRetries}: ${step.lastRetryReason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (step.owner.isNotBlank() || step.labels.isNotEmpty() || step.isOverdue()) {
                PlanStepChips(step = step)
            }
        }
        Text(
            text = statusLabel(step.status),
            style = MaterialTheme.typography.labelSmall,
            color = statusColor(step.status),
        )
        if (onRerun != null) {
            IconButton(onClick = onRerun, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = HugeIcons.Refresh01,
                    contentDescription = stringResource(R.string.chat_message_tool_interactive_plan_rerun),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 负责人 / 标签 / 逾期提示的小徽章行。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanStepChips(step: PlanStep) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (step.isOverdue()) {
            PlanChip(
                text = stringResource(R.string.chat_message_tool_interactive_plan_overdue),
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        if (step.owner.isNotBlank()) {
            PlanChip(text = step.owner)
        }
        step.labels.forEach { label ->
            PlanChip(text = "#$label")
        }
    }
}

@Composable
private fun PlanChip(
    text: String,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(color = container, shape = MaterialTheme.shapes.extraSmall) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun buildMetaText(step: PlanStep, allSteps: List<PlanStep>): String? {
    val parts = buildList {
        val elapsed = step.elapsedMillis()
        if (elapsed != null && elapsed > 0) {
            add(stringResource(R.string.chat_message_tool_interactive_plan_elapsed, formatDuration(elapsed)))
        }
        if (step.status == PlanStepStatus.BLOCKED && step.blockedReason.isNotBlank()) {
            add(step.blockedReason)
        }
        val unmet = step.unmetDependencies(allSteps)
        if (unmet.isNotEmpty() && step.status != PlanStepStatus.COMPLETED) {
            add(
                stringResource(
                    R.string.chat_message_tool_interactive_plan_waiting_on,
                    unmet.joinToString(", ") { it.title },
                )
            )
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

@Composable
private fun statusColor(status: PlanStepStatus) = when (status) {
    PlanStepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    PlanStepStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
    PlanStepStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
    PlanStepStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    PlanStepStatus.BLOCKED -> MaterialTheme.colorScheme.error
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanEditorContent(
    goal: String,
    onGoalChange: (String) -> Unit,
    message: String,
    steps: List<PlanStep>,
    onStepTitleChange: (String, String) -> Unit,
    onStepStatusChange: (String, PlanStepStatus) -> Unit,
    questions: List<PlanQuestion>,
    answers: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>,
    multiAnswers: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Set<String>>,
) {
    if (message.isNotBlank()) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    OutlinedTextField(
        value = goal,
        onValueChange = onGoalChange,
        label = { Text(stringResource(R.string.chat_message_tool_interactive_plan_goal)) },
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodySmall,
        singleLine = false,
        minLines = 1,
        maxLines = 4,
    )

    if (steps.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.chat_message_tool_interactive_plan_steps),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            steps.forEach { step ->
                StepEditor(
                    step = step,
                    onTitleChange = { onStepTitleChange(step.id, it) },
                    onStatusChange = { onStepStatusChange(step.id, it) },
                )
            }
        }
    }

    // 条件提问：只渲染当前条件下应该出现的问题。
    val visibleQuestions = questions.filter { it.isVisible(answers, multiAnswers) }
    if (visibleQuestions.isNotEmpty()) {
        HorizontalDivider()
        Text(
            text = stringResource(R.string.chat_message_tool_interactive_plan_questions),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        visibleQuestions.forEach { q ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = q.question,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (q.options.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        q.options.forEach { option ->
                            val isMulti = q.selectionType == PlanSelectionType.MULTI
                            val selected = if (isMulti) {
                                option in (multiAnswers[q.id] ?: emptySet())
                            } else {
                                answers[q.id] == option
                            }
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    if (isMulti) {
                                        val current = multiAnswers[q.id] ?: emptySet()
                                        multiAnswers[q.id] = toggleOption(
                                            current = current,
                                            option = option,
                                            allOptions = q.options,
                                            exclusiveOptions = q.exclusiveOptions,
                                        )
                                    } else {
                                        answers[q.id] = option
                                    }
                                },
                                label = { Text(text = option, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = answers[q.id] ?: "",
                    onValueChange = { answers[q.id] = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = false,
                    minLines = 1,
                    maxLines = 3,
                )
            }
        }
    }
}

/**
 * 多选场景下的互斥选择：从源头阻止互相矛盾的答案组合。
 * - 选中互斥项 -> 清空其余所有选项；
 * - 选中普通项 -> 移除所有互斥项；
 * - 再次点击已选项 -> 取消。
 */
private fun toggleOption(
    current: Set<String>,
    option: String,
    allOptions: List<String>,
    exclusiveOptions: List<String>,
): Set<String> {
    if (option in current) return current - option
    val isExclusive = option in exclusiveOptions
    val remaining = if (isExclusive) {
        emptySet()
    } else {
        current - exclusiveOptions.toSet()
    }
    return remaining + option
}

@Composable
private fun StepEditor(
    step: PlanStep,
    onTitleChange: (String) -> Unit,
    onStatusChange: (PlanStepStatus) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = step.title,
            onValueChange = onTitleChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = false,
            minLines = 1,
            maxLines = 2,
        )
        StatusToggle(status = step.status, onChange = onStatusChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusToggle(status: PlanStepStatus, onChange: (PlanStepStatus) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PlanStepStatus.entries.forEach { candidate ->
            FilterChip(
                selected = status == candidate,
                onClick = { onChange(candidate) },
                label = { Text(text = statusLabel(candidate), style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

@Composable
private fun PlanActions(
    enabled: Boolean,
    onConfirm: () -> Unit,
    onAdjust: () -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    confirmEnabled: Boolean = enabled,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalButton(
            onClick = onConfirm,
            enabled = confirmEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.chat_message_tool_interactive_plan_confirm_and_start))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = onAdjust, enabled = enabled) {
                Text(stringResource(R.string.chat_message_tool_interactive_plan_adjust))
            }
            TextButton(onClick = onComplete, enabled = enabled) {
                Text(stringResource(R.string.chat_message_tool_interactive_plan_complete))
            }
            TextButton(onClick = onCancel, enabled = enabled) {
                Text(stringResource(R.string.chat_message_tool_interactive_plan_cancel))
            }
        }
    }
}

private fun buildUserAnswer(
    operation: InteractivePlanOperation,
    goal: String,
    steps: List<PlanStep>,
    stepOrder: List<String>,
    questions: List<PlanQuestion>,
    answers: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>,
    multiAnswers: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Set<String>>,
): InteractivePlanUserAnswer {
    val answerMap = buildMap {
        questions
            .filter { it.isVisible(answers, multiAnswers) }
            .forEach { q ->
                val freeText = answers[q.id]?.takeIf { it.isNotBlank() }
                val selected = multiAnswers[q.id].orEmpty()
                val combined = when (q.selectionType) {
                    PlanSelectionType.MULTI -> (selected.toList() + listOfNotNull(freeText)).joinToString(", ")
                    else -> freeText ?: answers[q.id].orEmpty()
                }
                put(q.id, combined)
            }
    }
    return InteractivePlanUserAnswer(
        operation = operation,
        editedGoal = goal,
        answers = answerMap,
        updatedSteps = steps.map { it.toUserStep() },
        stepOrder = stepOrder,
    )
}

@Composable
private fun statusLabel(status: PlanStepStatus): String = when (status) {
    PlanStepStatus.PENDING -> stringResource(R.string.chat_message_tool_interactive_plan_status_pending)
    PlanStepStatus.IN_PROGRESS -> stringResource(R.string.chat_message_tool_interactive_plan_status_in_progress)
    PlanStepStatus.COMPLETED -> stringResource(R.string.chat_message_tool_interactive_plan_status_completed)
    PlanStepStatus.SKIPPED -> stringResource(R.string.chat_message_tool_interactive_plan_status_skipped)
    PlanStepStatus.BLOCKED -> stringResource(R.string.chat_message_tool_interactive_plan_status_blocked)
}


/**
 * 紧凑的步骤依赖图：手机竖屏友好。
 * 节点按依赖深度分层排列，用简单连线表示依赖关系。
 */
@Composable
private fun PlanDependencyGraph(
    steps: List<PlanStep>,
    modifier: Modifier = Modifier,
) {
    if (steps.size < 2) return

    // Group by depth (longest dependency chain)
    data class Node(val step: PlanStep, val depth: Int, val x: Float, val y: Float)

    val depthCache = mutableMapOf<String, Int>()
    fun depthOf(step: PlanStep): Int {
        depthCache[step.id]?.let { return it }
        if (step.dependsOn.isEmpty()) { depthCache[step.id] = 0; return 0 }
        val d = 1 + (step.dependsOn.mapNotNull { depId -> steps.firstOrNull { it.id == depId } }.maxOfOrNull { depthOf(it) } ?: 0)
        depthCache[step.id] = d
        return d
    }
    steps.forEach { depthOf(it) }
    val maxDepth = depthCache.values.maxOrNull() ?: 0

    // Layout: depth as row, siblings spread horizontally
    val nodes = steps.map { step ->
        val depth = depthCache[step.id] ?: 0
        val siblings = steps.filter { (depthCache[it.id] ?: 0) == depth }
        val idx = siblings.indexOfFirst { it.id == step.id }.coerceAtLeast(0)
        val x = (idx + 0.5f) / siblings.size.coerceAtLeast(1)
        val y = if (maxDepth == 0) 0.5f else depth.toFloat() / maxDepth
        Node(step, depth, x, y)
    }

    Canvas(modifier = modifier) {
        val nodeRadius = 8.dp.toPx()
        val padding = nodeRadius + 4.dp.toPx()
        val drawWidth = size.width - 2 * padding
        val drawHeight = size.height - 2 * padding

        // Draw edges first
        val nodePositions = nodes.associate { it.step.id to it }
        for (node in nodes) {
            for (depId in node.step.dependsOn) {
                val dep = nodePositions[depId] ?: continue
                val startX = padding + dep.x * drawWidth
                val startY = padding + dep.y * drawHeight
                val endX = padding + node.x * drawWidth
                val endY = padding + node.y * drawHeight
                drawLine(
                    color = Color.Gray.copy(alpha = 0.4f),
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = 2f,
                )
            }
        }

        // Draw nodes
        for (node in nodes) {
            val cx = padding + node.x * drawWidth
            val cy = padding + node.y * drawHeight
            val color = when (node.step.status) {
                PlanStepStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
                PlanStepStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
                PlanStepStatus.BLOCKED -> MaterialTheme.colorScheme.error
                PlanStepStatus.SKIPPED -> Color.Gray
                PlanStepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            }
            drawCircle(color = color, radius = nodeRadius, center = Offset(cx, cy))
            if (node.step.status == PlanStepStatus.IN_PROGRESS) {
                drawCircle(
                    color = color,
                    radius = nodeRadius + 3f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2f),
                )
            }
        }
    }
}
