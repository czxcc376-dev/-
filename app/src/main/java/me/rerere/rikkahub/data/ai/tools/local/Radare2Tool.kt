package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.reverse.Radare2Engine
import java.io.File

/**
 * radare2 本地逆向工具。
 *
 * 把内联的 radare2（r2 / rabin2 / rasm2 / rahash2 / radiff2 / rafind2 / rax2）封装成一个
 * 高层动作工具，覆盖二进制 / APK / DEX / SO 的常见静态分析：
 * - 文件信息、头、段、节区、入口点、依赖库、导入导出符号
 * - 字符串、类（DEX/Java）、哈希
 * - 反汇编、函数列表、单函数伪反汇编、交叉引用
 * - 字节搜索、字符串搜索、汇编/反汇编、二进制差异
 * - `raw` 逃生舱：直接跑任意 r2 命令，方便高级用法
 *
 * 全程离线，输出写入 App 私有目录。
 */
fun buildRadare2Tool(context: Context): Tool = Tool(
    name = "radare2",
    description = """
        Local radare2 (r2) reverse-engineering toolkit, bundled offline. Use it to statically
        analyze binaries, APKs, DEX and .so files: file info, sections, imports/exports, symbols,
        strings, classes, hashes, disassembly, function list, function pseudocode (pdf), xrefs,
        byte/string search, assemble/disassemble, and binary diff.
        Actions: info, headers, sections, imports, exports, symbols, entrypoints, libs, strings,
        classes, hash, functions, disasm, function, xrefs, search_strings, search_bytes, asm,
        disasm_bytes, diff, raw.
        Prefer specific actions; use 'raw' only when you need a r2 command not covered above.
        This tool is local and offline; it never sends data anywhere.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path of the target file (APK/DEX/SO/ELF/any binary) inside this app's sandbox.")
                })
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "Operation to perform.")
                    put("enum", buildJsonArray {
                        listOf(
                            "info", "headers", "sections", "imports", "exports", "symbols",
                            "entrypoints", "libs", "strings", "classes", "hash", "functions",
                            "disasm", "function", "xrefs", "search_strings", "search_bytes",
                            "asm", "disasm_bytes", "diff", "raw"
                        ).forEach { add(it) }
                    })
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Filter for strings/symbols, search term for search_strings, hex or text for search_bytes, or the r2 command(s) for 'raw'.")
                })
                put("offset", buildJsonObject {
                    put("type", "string")
                    put("description", "Address or offset for disasm/function/xrefs, e.g. 0x401000 or sym.main.")
                })
                put("count", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max results or instructions to return. Defaults to 60.")
                })
                put("arch", buildJsonObject {
                    put("type", "string")
                    put("description", "Architecture for asm/disasm_bytes, e.g. arm, arm64/aarch64, x86, dalvik. Defaults to arm.")
                })
                put("bits", buildJsonObject {
                    put("type", "integer")
                    put("description", "Bits for asm/disasm_bytes: 32 or 64 (16 for x86 real mode). Defaults to 64.")
                })
                put("path2", buildJsonObject {
                    put("type", "string")
                    put("description", "Second file for the 'diff' action.")
                })
            },
            required = listOf("path", "action")
        )
    },
    needsApproval = { false },
    execute = {
        val obj = it.jsonObject
        val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: error("path is required")
        val action = obj["action"]?.jsonPrimitive?.contentOrNull ?: "info"
        val query = obj["query"]?.jsonPrimitive?.contentOrNull ?: ""
        val offset = obj["offset"]?.jsonPrimitive?.contentOrNull ?: ""
        val count = obj["count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 60
        val arch = obj["arch"]?.jsonPrimitive?.contentOrNull ?: "arm"
        val bits = obj["bits"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 64
        val path2 = obj["path2"]?.jsonPrimitive?.contentOrNull

        val text = runCatching {
            executeRadare2(context, path, action, query, offset, count, arch, bits, path2)
        }.getOrElse { e -> mapOf("error" to (e.message ?: e.javaClass.simpleName)) }
            .entries.joinToString("\n") { (k, v) -> "$k: $v" }

        listOf(UIMessagePart.Text(text))
    }
)

private fun executeRadare2(
    context: Context,
    path: String,
    action: String,
    query: String,
    offset: String,
    count: Int,
    arch: String,
    bits: Int,
    path2: String?,
): Map<String, Any> {
    val file = resolveTargetFile(context, path)
    if (!file.exists()) return mapOf("error" to "file not found: ${file.absolutePath}")
    return when (action) {
        "info" -> single(context, "rabin2", listOf("-I", file.absolutePath))
        "headers" -> single(context, "rabin2", listOf("-H", file.absolutePath))
        "sections" -> single(context, "rabin2", listOf("-S", file.absolutePath))
        "imports" -> filterLines(single(context, "rabin2", listOf("-i", file.absolutePath)), query, count)
        "exports" -> filterLines(single(context, "rabin2", listOf("-E", file.absolutePath)), query, count)
        "symbols" -> filterLines(single(context, "rabin2", listOf("-s", file.absolutePath)), query, count)
        "libs" -> single(context, "rabin2", listOf("-l", file.absolutePath))
        "entrypoints" -> single(context, "rabin2", listOf("-e", file.absolutePath))
        "strings" -> strings(context, file, query, count)
        "classes" -> filterLines(single(context, "rabin2", listOf("-q", "-c", file.absolutePath)), query, count)
        "hash" -> single(context, "rahash2", listOf("-a", "sha256", "-a", "md5", file.absolutePath))
        "functions" -> filterLines(r2(context, file, "afl"), query, count)
        "disasm" -> disasm(context, file, offset, count)
        "function" -> function(context, file, offset, count)
        "xrefs" -> xrefs(context, file, offset, count)
        "search_strings" -> strings(context, file, query, count)
        "search_bytes" -> searchBytes(context, file, query, count)
        "asm" -> assemble(context, arch, bits, query)
        "disasm_bytes" -> disasmBytes(context, arch, bits, query)
        "diff" -> diff(context, file, path2)
        "raw" -> {
            val cmd = query.ifBlank { "ij" }
            filterLines(r2(context, file, cmd), "", count)
        }

        else -> mapOf("error" to "Unknown action: $action")
    }
}

// ---- 具体动作 ----

private fun single(context: Context, tool: String, args: List<String>): Map<String, Any> {
    val result = Radare2Engine.run(context, tool, args)
    return linkedMapOf(
        "tool" to tool,
        "args" to args.joinToString(" "),
        "exit_code" to result.exitCode,
        "output" to trimOutput(result.combined),
    )
}

private fun r2(context: Context, file: File, command: String): Map<String, Any> {
    val args = listOf(
        "-q",
        "-e", "scr.color=0",
        "-e", "scr.html=false",
        "-e", "anal.hasnext=true",
        "-c", command,
        file.absolutePath,
    )
    return single(context, "r2", args)
}

private fun disasm(context: Context, file: File, offset: String, count: Int): Map<String, Any> {
    val at = offset.ifBlank { "entry0" }
    val command = "e asm.bytes=false; pd $count @ $at"
    val base = r2(context, file, command)
    return filterLines(base, "", count + 4) + mapOf("at" to at, "instructions" to count)
}

private fun function(context: Context, file: File, offset: String, count: Int): Map<String, Any> {
    val at = offset.ifBlank { "entry0" }
    val command = "af @ $at; pdf @ $at"
    return filterLines(r2(context, file, command), "", count * 4)
}

private fun xrefs(context: Context, file: File, offset: String, count: Int): Map<String, Any> {
    if (offset.isBlank()) return mapOf("error" to "offset is required for xrefs")
    val command = "axt @ $offset"
    return filterLines(r2(context, file, command), "", count)
}

private fun strings(context: Context, file: File, query: String, count: Int): Map<String, Any> {
    val result = Radare2Engine.run(context, "rabin2", listOf("-q", "-z", file.absolutePath))
    val lines = result.combined.lines().filter { it.isNotBlank() }
    val filtered = if (query.isBlank()) lines else lines.filter { it.contains(query, ignoreCase = true) }
    return linkedMapOf(
        "tool" to "rabin2 -z",
        "total" to lines.size,
        "shown" to filtered.take(count).size,
        "strings" to filtered.take(count),
    )
}

private fun searchBytes(context: Context, file: File, query: String, count: Int): Map<String, Any> {
    if (query.isBlank()) return mapOf("error" to "query is required for search_bytes")
    // 允许 "hex:deadbeef"、"txt:hello" 或直接十六进制
    val (mode, value) = when {
        query.startsWith("txt:") -> "-s" to query.removePrefix("txt:")
        query.startsWith("hex:") -> "-x" to query.removePrefix("hex:")
        else -> "-x" to query
    }
    val result = Radare2Engine.run(context, "rafind2", listOf(mode, value, file.absolutePath), timeoutMillis = 60_000)
    val lines = result.combined.lines().filter { it.isNotBlank() }
    return linkedMapOf(
        "mode" to mode,
        "hits" to lines.size,
        "results" to lines.take(count),
    )
}

private fun assemble(context: Context, arch: String, bits: Int, source: String): Map<String, Any> {
    if (source.isBlank()) return mapOf("error" to "query is required for asm (assembly source)")
    val normalized = normalizeArch(arch)
    val result = Radare2Engine.run(
        context, "rasm2",
        listOf("-a", normalized, "-b", bits.toString(), source),
    )
    return mapOf(
        "arch" to normalized,
        "bits" to bits,
        "hex" to result.combined.trim(),
    )
}

private fun disasmBytes(context: Context, arch: String, bits: Int, hex: String): Map<String, Any> {
    if (hex.isBlank()) return mapOf("error" to "query is required for disasm_bytes (hex bytes)")
    val normalized = normalizeArch(arch)
    val result = Radare2Engine.run(
        context, "rasm2",
        listOf("-a", normalized, "-b", bits.toString(), "-d", hex),
    )
    return mapOf(
        "arch" to normalized,
        "bits" to bits,
        "asm" to result.combined.trim(),
    )
}

private fun diff(context: Context, file: File, other: String?): Map<String, Any> {
    if (other.isNullOrBlank()) return mapOf("error" to "path2 is required for diff")
    val otherFile = resolveTargetFile(context, other)
    val result = Radare2Engine.run(context, "radiff2", listOf(file.absolutePath, otherFile.absolutePath), timeoutMillis = 60_000)
    return mapOf(
        "exit_code" to result.exitCode,
        "output" to trimOutput(result.combined),
    )
}

// ---- 工具函数 ----

private fun normalizeArch(arch: String): String = when (arch.lowercase()) {
    "arm64", "aarch64" -> "arm"
    "dalvik", "dex" -> "dalvik"
    else -> arch.lowercase()
}

private fun filterLines(result: Map<String, Any>, query: String, count: Int): Map<String, Any> {
    val output = result["output"]?.toString().orEmpty()
    val lines = output.lines().filter { it.isNotBlank() }
    val filtered = if (query.isBlank()) lines else lines.filter { it.contains(query, ignoreCase = true) }
    val limited = filtered.take(count.coerceIn(1, 2000))
    return linkedMapOf(
        "tool" to result["tool"],
        "args" to result["args"],
        "exit_code" to result["exit_code"],
        "total_lines" to lines.size,
        "shown" to limited.size,
        "output" to limited.joinToString("\n"),
    )
}

private fun trimOutput(text: String, maxChars: Int = 24_000): String {
    val trimmed = text.trim()
    return if (trimmed.length <= maxChars) trimmed else trimmed.take(maxChars) + "\n… [truncated]"
}

private fun resolveTargetFile(context: Context, path: String): File {
    val direct = File(path)
    if (direct.isAbsolute && direct.exists()) {
        val filesRoot = context.filesDir.absolutePath
        val cacheRoot = context.cacheDir.absolutePath
        if (direct.absolutePath.startsWith(filesRoot) || direct.absolutePath.startsWith(cacheRoot)) return direct
    }
    val inside = File(context.filesDir, path)
    return if (inside.exists()) inside else direct
}
