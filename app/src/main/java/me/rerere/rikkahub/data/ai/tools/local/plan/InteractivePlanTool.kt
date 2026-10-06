package me.rerere.rikkahub.data.ai.tools.local.plan

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
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
 * - [needsApproval] 恒为 true，从而进入现有 HITL 流程；
 * - [execute] 不会被 Answered 分支调用，这里保持为兜底实现即可。
 */
fun buildInteractivePlanTool(): Tool = Tool(
    name = "interactive_plan",
    description = """
        Create, update, confirm, or cancel a structured task plan and ask the user for clarification
        or confirmation while the plan is executed. Use this for complex, multi-step tasks that
        benefit from user oversight. The plan is rendered as an interactive card; the user can edit
        steps, answer questions, and choose to continue, adjust, or cancel before generation resumes.
    """.trimIndent().replace("\n", " "),
    systemPrompt = { _, _ ->
        INTERACTIVE_PLAN_SYSTEM_PROMPT
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
    needsApproval = { true },
    execute = {
        // HITL 流程中不会走到这里。兜底返回一个空快照，保证极端情况下不崩溃。
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

Keep plan updates concise; do not call the tool after every tiny action, only at meaningful checkpoints.

Language:
- Write the goal, step titles, messages and questions in the SAME language the user is using in the conversation (e.g. reply in Chinese when the user writes in Chinese). Never mix languages.
""".trimIndent()
