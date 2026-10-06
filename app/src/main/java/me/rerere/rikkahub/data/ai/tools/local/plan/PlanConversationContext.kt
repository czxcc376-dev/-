package me.rerere.rikkahub.data.ai.tools.local.plan

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

private const val PLAN_TOOL_NAME = "interactive_plan"

/**
 * 从一组消息里找出最新的 interactive_plan 计划视图。
 *
 * 与 UI 层的 findActivePlan 不同，这里只依赖数据层，供压缩/持久化等非 UI 场景复用。
 */
fun List<UIMessage>.findLatestPlanView(): InteractivePlanView? {
    val tool = flatMap { it.getTools() }
        .lastOrNull { it.toolName == PLAN_TOOL_NAME } ?: return null
    val request = parsePlanRequest(tool.inputAsJson())
    return when (val state = tool.approvalState) {
        is ToolApprovalState.Answered -> buildPlanView(request, parsePlanUserAnswer(state.answer))
        else -> tool.output
            .filterIsInstance<UIMessagePart.Text>()
            .firstOrNull()
            ?.text
            ?.let { decodePlanSnapshot(it) }
            ?.toView()
            ?: buildPlanView(request, null)
    }
}

/**
 * 把计划视图压缩成一段紧凑的上下文文本。
 *
 * 用于：
 * - 压缩对话历史时保留计划状态，避免被摘要「吃掉」；
 * - 需要跨轮次保持计划一致性的场景。
 */
fun InteractivePlanView.toContextText(): String = buildString {
    appendLine("[Active plan]")
    appendLine("Goal: $goal")
    appendLine("Status: ${operation.name.lowercase()}  Progress: $doneCount/$totalCount")
    val roots = steps.rootSteps().ifEmpty { steps }
    roots.forEach { root ->
        appendLine(stepLine(root, depth = 0))
        steps.childrenOf(root.id).forEach { child -> appendLine(stepLine(child, depth = 1)) }
    }
    if (answers.isNotEmpty()) {
        appendLine("Answers: " + answers.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }
}

private fun InteractivePlanView.stepLine(step: PlanStep, depth: Int): String {
    val indent = "  ".repeat(depth)
    return buildString {
        append("$indent- [${step.status.name.lowercase()}] ${step.id}: ${step.title}")
        if (step.owner.isNotBlank()) append(" (owner: ${step.owner})")
        if (step.labels.isNotEmpty()) append(" #${step.labels.joinToString(" #")}")
        if (step.status == PlanStepStatus.BLOCKED && step.blockedReason.isNotBlank()) {
            append(" (blocked: ${step.blockedReason})")
        }
    }
}

/**
 * 多模型协作：当计划由「非当前模型」推进时，生成一段交接提示注入上下文，
 * 让当前模型理解之前是由别的模型（steps.modelHint）完成的工作，从而无缝接手。
 *
 * 只有当计划里确实存在 modelHint、且与当前模型不同的时候才返回内容。
 */
fun InteractivePlanView.buildModelHandoffNote(currentModelName: String?): String? {
    val hinted = steps.filter { it.modelHint.isNotBlank() }
    if (hinted.isEmpty()) return null

    val completedByOthers = hinted.filter { it.isDone && !it.modelHint.equals(currentModelName, ignoreCase = true) }
    val upcoming = hinted.filter {
        !it.isDone && !it.modelHint.equals(currentModelName, ignoreCase = true)
    }
    if (completedByOthers.isEmpty() && upcoming.isEmpty()) return null

    return buildString {
        appendLine("[Multi-model handoff]")
        if (currentModelName != null) appendLine("Current model: $currentModelName")
        if (completedByOthers.isNotEmpty()) {
            appendLine("Steps already completed (by other models):")
            completedByOthers.forEach { appendLine("- ${it.title} (done by ${it.modelHint})") }
        }
        if (upcoming.isNotEmpty()) {
            appendLine("Steps best handled by other models (coordinate, or produce a clean handoff brief):")
            upcoming.forEach { appendLine("- ${it.title} -> suggested model: ${it.modelHint}") }
        }
        append("Keep the shared plan in sync when you take over a step.")
    }
}
