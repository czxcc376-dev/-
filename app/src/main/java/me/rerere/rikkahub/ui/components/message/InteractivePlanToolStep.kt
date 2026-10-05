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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Task01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.plan.InteractivePlanOperation
import me.rerere.rikkahub.data.ai.tools.local.plan.InteractivePlanUserAnswer
import me.rerere.rikkahub.data.ai.tools.local.plan.PlanQuestion
import me.rerere.rikkahub.data.ai.tools.local.plan.PlanSelectionType
import me.rerere.rikkahub.data.ai.tools.local.plan.PlanStep
import me.rerere.rikkahub.data.ai.tools.local.plan.PlanStepStatus
import me.rerere.rikkahub.data.ai.tools.local.plan.UserPlanStep
import me.rerere.rikkahub.data.ai.tools.local.plan.decodePlanSnapshot
import me.rerere.rikkahub.data.ai.tools.local.plan.encodePlanUserAnswer
import me.rerere.rikkahub.data.ai.tools.local.plan.parsePlanRequest
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.DotLoading

/**
 * 交互式计划卡片的聊天流渲染。
 *
 * 和 ask_user 一样，不走注册式渲染框架，而是在 [ChatMessageToolStep] 里被直接分发。
 */
@Composable
fun ChainOfThoughtScope.InteractivePlanToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    val isPending = tool.isPending
    val isAnswered = tool.approvalState is ToolApprovalState.Answered
    val arguments = tool.inputAsJson()

    val initialRequest = remember(arguments) { parsePlanRequest(arguments) }

    var goal by remember(tool.toolCallId) { mutableStateOf(initialRequest.goal) }
    val steps = remember(tool.toolCallId) {
        mutableStateMapOf<String, PlanStep>().apply {
            initialRequest.steps.forEach { put(it.id, it) }
        }
    }
    val questions = initialRequest.questions

    var stepOrder by remember(tool.toolCallId) {
        mutableStateOf(initialRequest.stepOrder.ifEmpty { initialRequest.steps.map { it.id } })
    }

    val answers = remember(tool.toolCallId) { mutableStateMapOf<String, String>() }
    val multiAnswers = remember(tool.toolCallId) { mutableStateMapOf<String, Set<String>>() }

    var expanded by remember(tool.toolCallId) { mutableStateOf(true) }

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
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isAnswered) {
                    AnsweredPlanContent(tool = tool)
                } else {
                    PlanEditorContent(
                        goal = goal,
                        onGoalChange = { goal = it },
                        message = initialRequest.message,
                        steps = steps.values.filter { it.id in stepOrder }.sortedBy { stepOrder.indexOf(it.id) },
                        onStepTitleChange = { id, title ->
                            steps[id]?.let { steps[id] = it.copy(title = title) }
                        },
                        onStepStatusChange = { id, status ->
                            steps[id]?.let { steps[id] = it.copy(status = status) }
                        },
                        questions = questions,
                        answers = answers,
                        multiAnswers = multiAnswers,
                    )

                    PlanActions(
                        enabled = true,
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
                                updatedSteps = steps.values.map {
                                    UserPlanStep(it.id, it.title, it.detail, it.status, it.needsConfirmation)
                                },
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
        },
    )
}

@Composable
private fun AnsweredPlanContent(tool: UIMessagePart.Tool) {
    val snapshot = tool.output
        .filterIsInstance<UIMessagePart.Text>()
        .firstOrNull()
        ?.text
        ?.let { decodePlanSnapshot(it) }

    if (snapshot != null) {
        if (snapshot.goal.isNotBlank()) {
            Text(
                text = snapshot.goal,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (snapshot.steps.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                snapshot.steps.forEach { step ->
                    Text(
                        text = "${statusLabel(step.status)} · ${step.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (snapshot.message.isNotBlank()) {
            Text(
                text = snapshot.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
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

    if (questions.isNotEmpty()) {
        HorizontalDivider()
        Text(
            text = stringResource(R.string.chat_message_tool_interactive_plan_questions),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        questions.forEach { q ->
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
                            val selected = if (q.selectionType == PlanSelectionType.MULTI) {
                                option in (multiAnswers[q.id] ?: emptySet())
                            } else {
                                answers[q.id] == option
                            }
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    if (q.selectionType == PlanSelectionType.MULTI) {
                                        val current = multiAnswers[q.id] ?: emptySet()
                                        multiAnswers[q.id] = if (option in current) current - option else current + option
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

@Composable
private fun StepEditor(
    step: PlanStep,
    onTitleChange: (String) -> Unit,
    onStatusChange: (PlanStepStatus) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusToggle(status = step.status, onChange = onStatusChange)
        OutlinedTextField(
            value = step.title,
            onValueChange = onTitleChange,
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = false,
            minLines = 1,
            maxLines = 2,
        )
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
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalButton(
            onClick = onConfirm,
            enabled = enabled,
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
        questions.forEach { q ->
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
        updatedSteps = steps.map {
            UserPlanStep(it.id, it.title, it.detail, it.status, it.needsConfirmation)
        },
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
