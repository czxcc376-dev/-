package me.rerere.rikkahub.data.ai.tools.local.plan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 计划工具的输入输出模型。
 *
 * 设计目标：
 * - 让模型能够把复杂任务拆成一个「活计划」，而不是一次性提问；
 * - 让用户在聊天流里直接确认、修改、回答问题，再继续生成；
 * - 每一步之间模型都能拿到当前计划的最新状态。
 */

private val planJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

@Serializable
data class PlanStep(
    val id: String,
    val title: String,
    val detail: String = "",
    val status: PlanStepStatus = PlanStepStatus.PENDING,
    val dependsOn: List<String> = emptyList(),
    val needsConfirmation: Boolean = false,
    /** 步骤进入 in_progress 的时间戳（epoch 毫秒），由客户端记录。 */
    val startedAt: Long? = null,
    /** 步骤完成/跳过的时间戳（epoch 毫秒），由客户端记录。 */
    val finishedAt: Long? = null,
    /** 步骤被阻塞的原因（status == blocked 时展示）。 */
    val blockedReason: String = "",
    /** 父步骤 id；非空表示这是某个步骤的子步骤（子计划嵌套）。 */
    val parentId: String? = null,
    /** 负责人 / 承担者（自由文本，例如 "我"、"后端"、"ModelA"）。 */
    val owner: String = "",
    /** 标签（例如 "前端"、"测试"、"逆向"），用于分组与筛选。 */
    val labels: List<String> = emptyList(),
    /**
     * 多模型协作：建议执行该步骤的模型名（可选）。
     * 留空表示使用当前会话模型。
     */
    val modelHint: String = "",
    /** 该步骤的预计耗时（分钟），用于逾期提醒。 */
    val estimatedMinutes: Int = 0,
    /** 步骤执行证据：输出片段、命令结果、文件 diff 等（由 AI 或用户填入）。 */
    val evidence: String = "",
    /** 重试计数：被阻塞后自动重试的次数。 */
    val retryCount: Int = 0,
    /** 最大重试次数（默认 3，超过后需要用户介入）。 */
    val maxRetries: Int = 3,
    /** 上一次重试的原因（为什么要换方案）。 */
    val lastRetryReason: String = "",
) {
    /** 是否已经收尾（完成或跳过）。 */
    val isDone: Boolean get() = status == PlanStepStatus.COMPLETED || status == PlanStepStatus.SKIPPED

    /** 是否逾期：预计耗时已知、仍在进行中、且已超出预计。 */
    fun isOverdue(now: Long = System.currentTimeMillis()): Boolean {
        if (estimatedMinutes <= 0 || status != PlanStepStatus.IN_PROGRESS) return false
        val elapsed = elapsedMillis(now) ?: return false
        return elapsed > estimatedMinutes * 60_000L
    }

    /** 该步骤实际耗时（毫秒），无开始时间时返回 null。 */
    fun elapsedMillis(now: Long = System.currentTimeMillis()): Long? {
        val start = startedAt ?: return null
        return (finishedAt ?: now) - start
    }

    fun updatedFromUser(userStep: UserPlanStep): PlanStep {
        return copy(
            title = userStep.title.ifBlank { title },
            detail = userStep.detail.ifBlank { detail },
            status = userStep.status,
            needsConfirmation = userStep.needsConfirmation ?: needsConfirmation,
            startedAt = userStep.startedAt ?: startedAt,
            finishedAt = userStep.finishedAt ?: finishedAt,
            blockedReason = userStep.blockedReason ?: blockedReason,
            parentId = userStep.parentId ?: parentId,
            owner = userStep.owner ?: owner,
            labels = userStep.labels ?: labels,
            modelHint = userStep.modelHint ?: modelHint,
            evidence = userStep.evidence ?: evidence,
            retryCount = userStep.retryCount ?: retryCount,
            maxRetries = userStep.maxRetries ?: maxRetries,
            lastRetryReason = userStep.lastRetryReason ?: lastRetryReason,
        )
    }

    /**
     * 状态流转时自动维护时间戳。
     * - 进入 in_progress：补 startedAt，清空 finishedAt（允许重启）
     * - 进入 completed / skipped：补 finishedAt
     * - 回到 pending / blocked：保留既有时间，仅当从未开始时留空
     */
    fun withStatus(newStatus: PlanStepStatus, now: Long = System.currentTimeMillis()): PlanStep {
        val newStarted = when {
            newStatus == PlanStepStatus.IN_PROGRESS -> startedAt ?: now
            else -> startedAt
        }
        val newFinished = when (newStatus) {
            PlanStepStatus.COMPLETED, PlanStepStatus.SKIPPED -> finishedAt ?: now
            else -> null
        }
        return copy(status = newStatus, startedAt = newStarted, finishedAt = newFinished)
    }
}

@Serializable
enum class PlanStepStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("in_progress")
    IN_PROGRESS,

    @SerialName("completed")
    COMPLETED,

    @SerialName("skipped")
    SKIPPED,

    @SerialName("blocked")
    BLOCKED,
}

@Serializable
data class PlanQuestion(
    val id: String,
    val question: String,
    val options: List<String> = emptyList(),
    val selectionType: PlanSelectionType = PlanSelectionType.TEXT,
    val required: Boolean = true,
    /**
     * 条件提问：仅当所有条件都满足时，这个问题才会展示。
     * 为空表示无条件展示。
     */
    val dependsOn: List<QuestionCondition> = emptyList(),
    /**
     * 互斥选项：这些选项彼此互斥，也与其余所有选项互斥。
     * 例如 ["都不需要"] 表示选中它后不能再选别的。
     */
    val exclusiveOptions: List<String> = emptyList(),
) {
    /**
     * 根据当前已答内容判断该问题是否应该展示。
     */
    fun isVisible(
        answers: Map<String, String>,
        multiAnswers: Map<String, Set<String>> = emptyMap(),
    ): Boolean {
        if (dependsOn.isEmpty()) return true
        return dependsOn.all { condition ->
            val selected = buildSet {
                multiAnswers[condition.questionId]?.let { addAll(it) }
                // 用户答案可能是多选合并成的 "a, b" 字符串，这里拆开逐项匹配。
                answers[condition.questionId]
                    ?.takeIf { it.isNotBlank() }
                    ?.split(",", "|", "、", ";")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.let { addAll(it) }
            }
            if (condition.anyOf.isEmpty()) {
                selected.isNotEmpty()
            } else {
                condition.anyOf.any { it in selected }
            }
        }
    }
}

/**
 * 一个问题的展示条件：当 [questionId] 的答案命中 [anyOf] 中任意一项时满足。
 * [anyOf] 为空表示只要该问题有任意答案即可。
 */
@Serializable
data class QuestionCondition(
    val questionId: String,
    val anyOf: List<String> = emptyList(),
)

@Serializable
enum class PlanSelectionType {
    @SerialName("text")
    TEXT,

    @SerialName("single")
    SINGLE,

    @SerialName("multi")
    MULTI,
}

@Serializable
data class InteractivePlanRequest(
    val operation: InteractivePlanOperation = InteractivePlanOperation.CREATE_OR_UPDATE,

    val goal: String = "",
    val steps: List<PlanStep> = emptyList(),
    val questions: List<PlanQuestion> = emptyList(),

    /** 本次请求结束时希望保留的步骤顺序。 */
    val stepOrder: List<String> = emptyList(),

    /**
     * 模型对下一步的说明。展示在计划卡片顶部，帮助用户理解当前为什么暂停。
     */
    val message: String = "",

    /**
     * 已答问题。模型在更新计划时可以回传用户答案，保持状态可追踪。
     */
    val answers: Map<String, String> = emptyMap(),
)

@Serializable
enum class InteractivePlanOperation {
    @SerialName("create_or_update")
    CREATE_OR_UPDATE,

    @SerialName("confirm")
    CONFIRM,

    @SerialName("complete")
    COMPLETE,

    @SerialName("cancel")
    CANCEL,
}

@Serializable
data class UserPlanStep(
    val id: String,
    val title: String = "",
    val detail: String = "",
    val status: PlanStepStatus = PlanStepStatus.PENDING,
    val needsConfirmation: Boolean? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val blockedReason: String? = null,
    val parentId: String? = null,
    val owner: String? = null,
    val labels: List<String>? = null,
    val modelHint: String? = null,
    val evidence: String? = null,
    val retryCount: Int? = null,
    val maxRetries: Int? = null,
    val lastRetryReason: String? = null,
)

/**
 * 用户在 UI 上提交的答案。
 *
 * 它同时承载：
 * - 对问题的回答
 * - 对计划的修改/确认
 * - 对整个计划的操作
 */
@Serializable
data class InteractivePlanUserAnswer(
    val operation: InteractivePlanOperation = InteractivePlanOperation.CONFIRM,
    val message: String = "",
    val answers: Map<String, String> = emptyMap(),
    val updatedSteps: List<UserPlanStep> = emptyList(),
    val stepOrder: List<String> = emptyList(),
    val editedGoal: String = "",
)

/**
 * 工具的稳定输出。模型据此知道用户刚刚做了什么，从而继续执行。
 */
@Serializable
data class InteractivePlanResult(
    val operation: InteractivePlanOperation,
    val goal: String,
    val steps: List<PlanStep>,
    val questions: List<PlanQuestion>,
    val message: String,
    val answers: Map<String, String> = emptyMap(),
)

@Serializable
data class InteractivePlanSnapshot(
    val version: Int = 1,
    val goal: String = "",
    val steps: List<PlanStep> = emptyList(),
    val questions: List<PlanQuestion> = emptyList(),
    val message: String = "",
    val answers: Map<String, String> = emptyMap(),
    val lastUserOperation: InteractivePlanOperation = InteractivePlanOperation.CREATE_OR_UPDATE,
)

/**
 * 从工具 JSON 入参解析计划请求。
 */
fun parsePlanRequest(jsonElement: JsonElement?): InteractivePlanRequest {
    if (jsonElement == null) return InteractivePlanRequest()
    return runCatching {
        planJson.decodeFromJsonElement<InteractivePlanRequest>(jsonElement)
    }.getOrElse {
        // 兜底：入参异常时返回一个安全空计划，避免整次生成崩溃。
        InteractivePlanRequest()
    }
}

fun parsePlanUserAnswer(text: String): InteractivePlanUserAnswer {
    return runCatching {
        planJson.decodeFromString<InteractivePlanUserAnswer>(text)
    }.getOrElse {
        InteractivePlanUserAnswer()
    }
}

fun encodePlanResult(result: InteractivePlanResult): String =
    planJson.encodeToString(InteractivePlanResult.serializer(), result)

fun encodePlanUserAnswer(answer: InteractivePlanUserAnswer): String =
    planJson.encodeToString(InteractivePlanUserAnswer.serializer(), answer)

fun encodePlanSnapshot(snapshot: InteractivePlanSnapshot): String =
    planJson.encodeToString(InteractivePlanSnapshot.serializer(), snapshot)

fun decodePlanSnapshot(text: String): InteractivePlanSnapshot? =
    runCatching {
        planJson.decodeFromString<InteractivePlanSnapshot>(text)
    }.getOrNull()

/**
 * 计划卡片当前应该渲染的最终视图。
 *
 * 由「模型原始请求」+「用户操作答案」合并而来，因此即使工具没有真正的执行输出，
 * 也能在用户确认/调整之后完整回看整份计划。
 */
data class InteractivePlanView(
    val goal: String,
    val steps: List<PlanStep>,
    val questions: List<PlanQuestion>,
    val answers: Map<String, String>,
    val message: String,
    val operation: InteractivePlanOperation,
) {
    val totalCount: Int get() = steps.size
    val doneCount: Int get() = steps.count { it.isDone }
    val blockedCount: Int get() = steps.count { it.status == PlanStepStatus.BLOCKED }

    /** 完成率 0f..1f，无步骤时返回 0。 */
    val progress: Float
        get() = if (totalCount == 0) 0f else doneCount.toFloat() / totalCount.toFloat()

    /** 进行中的步骤数。 */
    val inProgressCount: Int get() = steps.count { it.status == PlanStepStatus.IN_PROGRESS }
}

/**
 * 加权进度：完成/跳过记 1，进行中记 0.5。
 * 这样进度条在步骤真正推进时就会动起来，而不是只有完成才跳变。
 */
fun List<PlanStep>.weightedProgress(): Float {
    if (isEmpty()) return 0f
    val score = sumOf {
        when (it.status) {
            PlanStepStatus.COMPLETED, PlanStepStatus.SKIPPED -> 1.0
            PlanStepStatus.IN_PROGRESS -> 0.5
            else -> 0.0
        }
    }
    return (score / size).toFloat().coerceIn(0f, 1f)
}

/** 找出某步骤尚未满足的依赖步骤（依赖未完成/未跳过）。 */
fun PlanStep.unmetDependencies(allSteps: List<PlanStep>): List<PlanStep> =
    dependsOn
        .mapNotNull { id -> allSteps.firstOrNull { it.id == id } }
        .filter { !it.isDone }

/** 顶层步骤（没有父步骤）。 */
fun List<PlanStep>.rootSteps(): List<PlanStep> = filter { it.parentId.isNullOrBlank() }

/** 某步骤的直接子步骤。 */
fun List<PlanStep>.childrenOf(parentId: String): List<PlanStep> =
    filter { it.parentId == parentId }

/**
 * 加权进度（含子步骤）：按树形展开计算，父步骤的进度由自身状态与其子步骤共同决定。
 * 保证父步骤完成度不会超过其未完成子步骤的进度。
 */
/** 计算某步骤的子步骤进度（0f..1f）。无子步骤返回 null。 */
fun List<PlanStep>.subStepProgress(parentId: String): Float? {
    val children = childrenOf(parentId)
    if (children.isEmpty()) return null
    val score = children.sumOf { child ->
        val subProgress = subStepProgress(child.id)
        when {
            subProgress != null -> subProgress.toDouble()
            child.isDone -> 1.0
            child.status == PlanStepStatus.IN_PROGRESS -> 0.5
            else -> 0.0
        }
    }
    return (score / children.size).toFloat().coerceIn(0f, 1f)
}

/** 找到下一个应该执行的步骤（依赖已满足的第一个 pending 顶层/子步骤）。 */
fun List<PlanStep>.nextExecutableStep(): PlanStep? {
    val roots = rootSteps()
    for (root in roots) {
        if (root.status != PlanStepStatus.PENDING) continue
        if (root.unmetDependencies(this).isNotEmpty()) continue
        // Check if it has children that need to run first
        val children = childrenOf(root.id)
        if (children.isEmpty()) return root
        // Find first executable child
        val nextChild = children.filter { it.status == PlanStepStatus.PENDING }
            .firstOrNull { it.unmetDependencies(this).isEmpty() }
        return nextChild ?: root
    }
    return null
}

/** 自动推进：完成当前步骤后，把下一个可执行的步骤标记为 in_progress。 */
fun List<PlanStep>.autoAdvance(justCompletedId: String, now: Long = System.currentTimeMillis()): List<PlanStep> {
    val next = nextExecutableStep()
    if (next == null) return this
    return map { step ->
        if (step.id == next.id && step.status == PlanStepStatus.PENDING) {
            step.withStatus(PlanStepStatus.IN_PROGRESS, now)
        } else {
            step
        }
    }
}

/** 判断是否可以自动重试：步骤被阻塞且重试次数未超限。 */
fun PlanStep.canAutoRetry(): Boolean =
    status == PlanStepStatus.BLOCKED && retryCount < maxRetries

/** 生成 Markdown 导出格式的计划清单。 */
fun List<PlanStep>.toMarkdownChecklist(goal: String, elapsed: Long? = null): String {
    val sb = StringBuilder()
    sb.appendLine("# $goal")
    if (elapsed != null) {
        val minutes = elapsed / 60000
        val seconds = (elapsed % 60000) / 1000
        sb.appendLine("> Total time: ${minutes}m ${seconds}s")
    }
    sb.appendLine()
    val roots = rootSteps()
    fun renderStep(step: PlanStep, depth: Int) {
        val indent = "  ".repeat(depth)
        val check = when (step.status) {
            PlanStepStatus.COMPLETED -> "x"
            PlanStepStatus.SKIPPED -> "-"
            PlanStepStatus.IN_PROGRESS -> "~"
            PlanStepStatus.BLOCKED -> "!"
            PlanStepStatus.PENDING -> " "
        }
        sb.appendLine("$indent- [$check] ${step.title}")
        if (step.detail.isNotBlank()) {
            sb.appendLine("$indent  ${step.detail}")
        }
        if (step.evidence.isNotBlank()) {
            sb.appendLine("$indent  > Evidence: ${step.evidence}")
        }
        if (step.blockedReason.isNotBlank()) {
            sb.appendLine("$indent  > Blocked: ${step.blockedReason}")
        }
        val elapsedStr = step.elapsedMillis()
        if (elapsedStr != null && elapsedStr > 0) {
            val m = elapsedStr / 60000
            val sec = (elapsedStr % 60000) / 1000
            sb.appendLine("$indent  > Time: ${m}m ${sec}s")
        }
        childrenOf(step.id).forEach { child -> renderStep(child, depth + 1) }
    }
    roots.forEach { renderStep(it, 0) }
    return sb.toString()
}

fun List<PlanStep>.treeWeightedProgress(): Float {
    if (isEmpty()) return 0f
    val byParent = groupBy { it.parentId }
    fun scoreOf(step: PlanStep): Double {
        val children = byParent[step.id].orEmpty()
        return if (children.isEmpty()) {
            when (step.status) {
                PlanStepStatus.COMPLETED, PlanStepStatus.SKIPPED -> 1.0
                PlanStepStatus.IN_PROGRESS -> 0.5
                else -> 0.0
            }
        } else {
            children.sumOf { scoreOf(it) } / children.size
        }
    }
    val roots = rootSteps()
    if (roots.isEmpty()) return weightedProgress()
    return (roots.sumOf { scoreOf(it) } / roots.size).toFloat().coerceIn(0f, 1f)
}

/** 收集某步骤的所有后代 id。 */
fun List<PlanStep>.descendantIdsOf(rootId: String): Set<String> {
    val result = mutableSetOf<String>()
    fun walk(id: String) {
        childrenOf(id).forEach { child ->
            if (result.add(child.id)) walk(child.id)
        }
    }
    walk(rootId)
    return result
}

/**
 * 把模型请求与用户答案合并成最终视图。
 *
 * - 步骤按 [InteractivePlanRequest.stepOrder] / [InteractivePlanUserAnswer.stepOrder] 排序；
 * - 用户在卡片里编辑过的步骤（标题/详情/状态/时间戳）覆盖模型版本；
 * - 用户答案里的步骤若模型没给过，也会被补进来（用户可新增体验更平滑）。
 */
fun buildPlanView(
    request: InteractivePlanRequest,
    answer: InteractivePlanUserAnswer?,
): InteractivePlanView {
    val order = (answer?.stepOrder?.takeIf { it.isNotEmpty() }
        ?: request.stepOrder.takeIf { it.isNotEmpty() }
        ?: request.steps.map { it.id })

    val base = LinkedHashMap<String, PlanStep>()
    request.steps.forEach { base[it.id] = it }

    answer?.updatedSteps?.forEach { userStep ->
        val existing = base[userStep.id]
        base[userStep.id] = existing?.updatedFromUser(userStep) ?: PlanStep(
            id = userStep.id,
            title = userStep.title,
            detail = userStep.detail,
            status = userStep.status,
            needsConfirmation = userStep.needsConfirmation ?: false,
            startedAt = userStep.startedAt,
            finishedAt = userStep.finishedAt,
            blockedReason = userStep.blockedReason.orEmpty(),
        )
    }

    val orderedIds = buildList {
        addAll(order.filter { it in base })
        addAll(base.keys.filter { it !in order })
    }

    val answers = buildMap {
        putAll(request.answers)
        answer?.answers?.forEach { (k, v) -> if (v.isNotBlank()) put(k, v) }
    }

    return InteractivePlanView(
        goal = answer?.editedGoal?.takeIf { it.isNotBlank() } ?: request.goal,
        steps = orderedIds.mapNotNull { base[it] },
        questions = request.questions,
        answers = answers,
        message = answer?.message?.takeIf { it.isNotBlank() } ?: request.message,
        operation = answer?.operation ?: request.operation,
    )
}

/** 把工具执行输出的快照转换为只读视图（用于 complete / cancel 等无需用户确认的收尾）。 */
fun InteractivePlanSnapshot.toView(): InteractivePlanView = InteractivePlanView(
    goal = goal,
    steps = steps,
    questions = questions,
    answers = answers,
    message = message,
    operation = lastUserOperation,
)
