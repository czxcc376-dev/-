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
) {
    fun updatedFromUser(userStep: UserPlanStep): PlanStep {
        return copy(
            title = userStep.title.ifBlank { title },
            detail = userStep.detail.ifBlank { detail },
            status = userStep.status,
            needsConfirmation = userStep.needsConfirmation ?: needsConfirmation,
        )
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
