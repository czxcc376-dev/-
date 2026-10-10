package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * BreakerKeyword —— 关键词替换
 *
 * 只作用于**助手侧**消息（正文与推理内容），不触碰用户输入。
 * 正文替换会跳过 ``` 代码围栏，避免破坏代码；推理内容整体替换。
 *
 * 规则格式：每行一条 `原词=>替换词`（也支持 `→` 分隔），以 # 开头为注释。
 */
object BreakerKeyword {

    /** 内置默认规则（自定义规则为空时使用） */
    val DEFAULTS: String = """
外挂=>扩展模块
作弊=>辅助逻辑
破解=>授权分析
注入=>调用
透视=>视图计算
自瞄=>目标跟踪
脱壳=>结构还原
卡密=>授权码
越狱=>解锁
爆破=>穷举
窃取=>读取
攻击=>压力测试
绕过=>适配
免杀=>兼容
后门=>入口模块
木马=>样本
病毒=>样本
键盘记录=>输入采集
挖矿=>算力任务
洗钱=>资金流转
钓鱼=>页面仿真
假证=>证件模板
毒=>违禁物
枪=>器械
cheat=>assist
hack=>modify
crack=>analyze
inject=>call
bypass=>adapt
exploit=>probe
malware=>sample
backdoor=>entry
steal=>read
attack=>stress-test
jailbreak=>unlock
keygen=>codegen
phishing=>page-sim
launder=>route
""".trim()

    /** 解析规则文本为有序键值对；为空时回落到默认 */
    fun parseRules(raw: String): List<Pair<String, String>> {
        val source = raw.trim().ifBlank { DEFAULTS }
        val out = ArrayList<Pair<String, String>>()
        for (rawLine in source.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val sep = when {
                line.contains("=>") -> "=>"
                line.contains("→") -> "→"
                else -> continue
            }
            val i = line.indexOf(sep)
            if (i <= 0) continue
            val k = line.substring(0, i).trim()
            val v = line.substring(i + sep.length).trim()
            if (k.isNotEmpty()) out.add(k to v)
        }
        return out
    }

    private fun replaceAll(text: String, rules: List<Pair<String, String>>): String {
        if (text.isEmpty()) return text
        var s = text
        for (rule in rules) {
            val k = rule.first
            if (k.isNotEmpty() && s.contains(k)) s = s.replace(k, rule.second)
        }
        return s
    }

    /**
     * 替换单段文本。
     * @param skipCodeFence 为 true 时跳过 ``` 围栏内的行（用于正文）
     */
    fun apply(text: String, rules: List<Pair<String, String>>, skipCodeFence: Boolean): String {
        if (text.isEmpty() || rules.isEmpty()) return text
        if (!skipCodeFence) return replaceAll(text, rules)
        val out = StringBuilder(text.length)
        var inFence = false
        val lines = text.split("\n")
        for (i in lines.indices) {
            if (i > 0) out.append('\n')
            val line = lines[i]
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                out.append(line)
            } else if (inFence) {
                out.append(line)
            } else {
                out.append(replaceAll(line, rules))
            }
        }
        return out.toString()
    }

    /**
     * 只改写助手消息：正文（跳过代码围栏）+ 推理内容。
     * 用户与工具消息保持原样。
     */
    fun applyToMessages(
        messages: List<UIMessage>,
        rules: List<Pair<String, String>>,
    ): List<UIMessage> {
        if (rules.isEmpty()) return messages
        return messages.map { message ->
            if (message.role != MessageRole.ASSISTANT) {
                message
            } else {
                message.copy(
                    parts = message.parts.map { part ->
                        when (part) {
                            is UIMessagePart.Text ->
                                part.copy(text = apply(part.text, rules, true))

                            is UIMessagePart.Reasoning ->
                                part.copy(reasoning = apply(part.reasoning, rules, false))

                            else -> part
                        }
                    }
                )
            }
        }
    }

    /** 规则条数（用于 UI 显示） */
    fun ruleCount(raw: String): Int = parseRules(raw).size
}
