package me.rerere.rikkahub.data.ai.tools.local.plan

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 交互式任务计划工具。
 *
 * 与原版 ask_user 的差异：
 * - 不只是一次性问问题，而是提交/更新一个带步骤状态的可演进计划；
 * - 每一步之间模型都能拿到当前计划的最新状态；
 * - 用户可以在聊天卡片里直接确认、修改步骤、回答问题。
 *
 * 执行契约：
 * - [needsApproval] 对 create_or_update/confirm 为 true（进入 HITL），对 complete/cancel 为 false（直接收尾）；
 * - [execute] 在 complete/cancel 或用户回答后的兜底路径中会把当前计划快照写入输出。
 */
private const val PLAN_TOOL_NAME = "interactive_plan"

fun buildInteractivePlanTool(): Tool = Tool(
    name = PLAN_TOOL_NAME,
    description = """
        Create, update, confirm, or cancel a structured task plan and ask the user for clarification
        or confirmation while the plan is executed. Use this for complex, multi-step tasks that
        benefit from user oversight. The plan is rendered as an interactive card; the user can edit
        steps, answer questions, and choose to continue, adjust, or cancel before generation resumes.
    """.trimIndent().replace("\n", " "),
    systemPrompt = { _, messages ->
        val live = buildLivePlanSection(messages)
        val handoff = messages
            .findLatestPlanView()
            ?.buildModelHandoffNote(currentModelName = null)
        buildString {
            append(INTERACTIVE_PLAN_SYSTEM_PROMPT)
            if (!live.isNullOrBlank()) append("\n\n").append(live)
            if (!handoff.isNullOrBlank()) append("\n\n").append(handoff)
        }
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("operation", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("create_or_update")
                        add("confirm")
                        add("complete")
                        add("cancel")
                    })
                    put(
                        "description",
                        "create_or_update to create or update the plan, confirm to ask the user " +
                            "to confirm a plan, complete when the task is finished, cancel to abandon the plan."
                    )
                })
                put("goal", buildJsonObject {
                    put("type", "string")
                    put("description", "The overall goal of the plan.")
                })
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "A short message to the user explaining the current state and what is needed next.")
                })
                put("steps", buildJsonObject {
                    put("type", "array")
                    put("description", "Ordered plan steps.")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject { put("type", "string") })
                            put("title", buildJsonObject { put("type", "string") })
                            put("detail", buildJsonObject { put("type", "string") })
                            put("status", buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add("pending")
                                    add("in_progress")
                                    add("completed")
                                    add("skipped")
                                    add("blocked")
                                })
                            })
                            put("dependsOn", buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "string") })
                            })
                            put("needsConfirmation", buildJsonObject {
                                put("type", "boolean")
                            })
                            put("startedAt", buildJsonObject {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Epoch milliseconds when the step started. Set by the client; only echo it back if you already know it."
                                )
                            })
                            put("finishedAt", buildJsonObject {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Epoch milliseconds when the step finished. Set by the client; only echo it back if you already know it."
                                )
                            })
                            put("blockedReason", buildJsonObject {
                                put("type", "string")
                                put("description", "Why the step is blocked (only when status is blocked).")
                            })
                            put("parentId", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "Parent step id to nest this step as a sub-step. Use for decomposing a big step into concrete sub-tasks."
                                )
                            })
                            put("owner", buildJsonObject {
                                put("type", "string")
                                put("description", "Who is responsible for this step, e.g. the user, a person, or a component.")
                            })
                            put("labels", buildJsonObject {
                                put("type", "array")
                                put(
                                    "description",
                                    "Free-form labels for grouping, e.g. [\"frontend\", \"testing\", \"reverse\"]."
                                )
                                put("items", buildJsonObject { put("type", "string") })
                            })
                            put("modelHint", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "Optional model name best suited to execute this step (multi-model collaboration)."
                                )
                            })
                            put("estimatedMinutes", buildJsonObject {
                                put("type", "integer")
                                put("description", "Estimated minutes for this step, used for overdue reminders.")
                            })
                        })
                        put("required", buildJsonArray {
                            add("id")
                            add("title")
                        })
                    })
                })
                put("questions", buildJsonObject {
                    put("type", "array")
                    put("description", "Questions for the user. Each question can be text, single, or multi selection.")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject { put("type", "string") })
                            put("question", buildJsonObject { put("type", "string") })
                            put("options", buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "string") })
                            })
                            put("selectionType", buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add("text")
                                    add("single")
                                    add("multi")
                                })
                            })
                            put("required", buildJsonObject { put("type", "boolean") })
                            put("dependsOn", buildJsonObject {
                                put("type", "array")
                                put(
                                    "description",
                                    "Show this question only when the referenced answers match. " +
                                        "Each item: {questionId, anyOf: [answers]}. Empty anyOf means " +
                                        "'any answer at all'."
                                )
                                put("items", buildJsonObject {
                                    put("type", "object")
                                    put("properties", buildJsonObject {
                                        put("questionId", buildJsonObject { put("type", "string") })
                                        put("anyOf", buildJsonObject {
                                            put("type", "array")
                                            put("items", buildJsonObject { put("type", "string") })
                                        })
                                    })
                                    put("required", buildJsonArray { add("questionId") })
                                })
                            })
                            put("exclusiveOptions", buildJsonObject {
                                put("type", "array")
                                put("description", "Options that are mutually exclusive with every other option (e.g. \"None of the above\").")
                                put("items", buildJsonObject { put("type", "string") })
                            })
                        })
                        put("required", buildJsonArray {
                            add("id")
                            add("question")
                        })
                    })
                })
                put("stepOrder", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("answers", buildJsonObject {
                    put("type", "object")
                    put("description", "Mapping of question id to user answer for tracking state.")
                })
            },
            required = listOf("operation", "goal", "steps")
        )
    },
    // create_or_update / confirm 需要用户交互（进入 HITL 流程）；
    // complete / cancel 是收尾动作，直接执行并把最终快照作为输出，无需再等用户点击。
    needsApproval = { element ->
        parsePlanRequest(element).operation !in setOf(
            InteractivePlanOperation.COMPLETE,
            InteractivePlanOperation.CANCEL,
        )
    },
    execute = {
        // complete / cancel 会走到这里；create_or_update / confirm 在用户回答后也可能走兜底路径。
        // 统一把当前计划编码成快照作为工具输出，保证卡片始终能回看完整计划。
        val args = parsePlanRequest(it)
        listOf(
            UIMessagePart.Text(
                encodePlanSnapshot(
                    InteractivePlanSnapshot(
                        goal = args.goal,
                        steps = args.steps,
                        questions = args.questions,
                        message = args.message,
                        answers = args.answers,
                        lastUserOperation = args.operation,
                    )
                )
            )
        )
    }
)


/**
 * 把当前会话里最新的 interactive_plan 计划提取成一段纯文本，注入系统提示。
 *
 * 目的：让模型每一轮都能看到计划的最新状态，从而继续推进/更新计划，
 * 而不是几轮之后「忘记」计划导致卡片进度假死。
 */
private fun buildLivePlanSection(messages: List<UIMessage>): String? {
    val tool = messages
        .flatMap { it.getTools() }
        .lastOrNull { it.toolName == PLAN_TOOL_NAME } ?: return null

    val request = parsePlanRequest(tool.inputAsJson())
    val state = tool.approvalState
    val view = when {
        state is me.rerere.ai.ui.ToolApprovalState.Answered ->
            buildPlanView(request, parsePlanUserAnswer(state.answer))

        else -> tool.output
            .filterIsInstance<UIMessagePart.Text>()
            .firstOrNull()
            ?.text
            ?.let { decodePlanSnapshot(it) }
            ?.toView()
            ?: buildPlanView(request, null)
    }
    if (view.steps.isEmpty() && view.goal.isBlank()) return null

    val done = view.steps.count { it.isDone }
    return buildString {
        appendLine("**Current plan state (live)**")
        appendLine("Goal: ${view.goal}")
        appendLine("Progress: $done/${view.steps.size}")
        view.steps.forEach { step ->
            val indent = if (step.parentId.isNullOrBlank()) "" else "  "
            append("- $indent[${step.status.name.lowercase()}] ${step.id}: ${step.title}")
            if (step.owner.isNotBlank()) append(" (owner: ${step.owner})")
            if (step.labels.isNotEmpty()) append(" #${step.labels.joinToString(" #")}")
            if (step.detail.isNotBlank()) append(" — ${step.detail}")
            appendLine()
        }
        if (view.answers.isNotEmpty()) {
            appendLine("User answers: " + view.answers.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        append("Continue from the current state. Keep statuses honest and update the plan at meaningful checkpoints.")
    }
}

private val INTERACTIVE_PLAN_SYSTEM_PROMPT = """
**Interactive Plan Tool**

You have access to the `interactive_plan` tool for complex, multi-step tasks that benefit from user oversight.

When to use it:
- Before starting a task with multiple non-trivial steps, especially when requirements are ambiguous.
- When you need the user to make a decision (scope, approach, priority, environment, credentials, or approval).
- When the user asks you to plan, prepare, or lay out a roadmap.

How to use it:
- Call `interactive_plan` with operation `create_or_update` and provide:
  - `goal`: one clear sentence describing the final outcome.
  - `steps`: ordered steps with stable ids (e.g. "s1", "s2"). Mark the first actionable step as `in_progress`.
  - `message`: what you need from the user right now.
  - `questions`: only the questions you actually need answered to continue. Prefer options when choices are clear.
- After the user responds, read the tool result (it contains the user's operation, edited steps, and answers).
  Then continue executing step by step. Update the plan with `create_or_update` whenever status changes meaningfully.
- Mark steps `completed` as you finish them. Keep only one step `in_progress` at a time when possible.
- Use `confirm` when you want explicit go-ahead before a destructive, expensive, or irreversible action.
- Use `complete` when the whole goal is achieved. Use `cancel` only if the user abandons the task.
- Never fabricate user answers. If a required answer is missing, ask again.

Questions:
- Only ask questions you genuinely need answered. Prefer `single`/`multi` with concrete options over free text.
- Use `dependsOn` to make a question conditional: it only shows when a previous question was answered a certain way. This avoids asking irrelevant follow-ups.
- Use `exclusiveOptions` for options that must not be combined with others (e.g. "None of the above", "Not sure"). The UI then prevents contradictory answers.

Steps & progress:
- Give every step a stable id and a clear title. Add `detail` for anything non-obvious.
- Use `dependsOn` on a step to declare prerequisites. The UI shows dependency state and progress.
- Keep the plan honest: exactly one step `in_progress` at a time, and mark steps `completed` as you truly finish them so the progress bar reflects reality.
- Set `blockedReason` when a step is blocked so the user can help unblock it.

Structure & collaboration:
- Decompose big steps with `parentId` instead of making a flat, overwhelming list. Keep nesting to 2 levels.
- Use `labels` to group work (e.g. frontend/backend/testing/reverse) and `owner` when responsibility matters.
- Use `estimatedMinutes` so the UI can warn about overdue steps.
- For multi-model workflows, set `modelHint` on steps that a different model handles better (e.g. a fast model for boilerplate, a strong model for hard reasoning).

Reverse engineering playbook (use when the task involves APKs, binaries, protocols, or APIs):
- Typical plan: recon (manifest/permissions/entry points) -> locate logic (strings, JNI, crypto) -> trace data flow (network/DB) -> reproduce (frida/hook, capture) -> document findings.
- Prefer evidence-based steps: capture traffic, dump strings, decompile, then verify. Mark a step blocked with `blockedReason` when you lack a tool or credential.
- Keep the user in the loop before anything destructive or network-facing.

Keep plan updates concise; do not call the tool after every tiny action, only at meaningful checkpoints.

Language:
- Write the goal, step titles, messages and questions in the SAME language the user is using in the conversation (e.g. reply in Chinese when the user writes in Chinese). Never mix languages.
""".trimIndent()
