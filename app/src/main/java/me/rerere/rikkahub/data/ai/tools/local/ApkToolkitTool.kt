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
import java.io.File
import java.util.zip.ZipFile

class ApkToolkitTool(val context: Context) {
    val tool: Tool by lazy { buildApkToolkitTool(context) }
}

/**
 * APK / DEX / SO 静态分析本地工具。
 *
 * 纯 Kotlin + JDK 实现，不依赖 apktool/jadx 等外部二进制：
 * - 解包预览：列出 APK 内部条目、压缩前后大小
 * - Manifest 信息：从二进制 AXML 提取字符串池，识别包名与权限
 * - DEX 信息：解析 DEX 头、统计类/方法/字段/字符串数量
 * - DEX 字符串/类名提取：供搜索加密密钥、URL、接口名等
 * - 原生库识别：列出 lib/*.so 并解析 ELF 架构/位宽/端序
 * - 单条目解包：安全返回文本或 hex 摘要
 */
fun buildApkToolkitTool(context: Context): Tool = Tool(
    name = "apk_toolkit",
    description = """
        Local APK/DEX/SO static analysis toolkit. Use it to inspect APK packages, Android
        manifests, DEX headers/strings/classes, native libraries and individual zip entries.
        It also bundles apktool/baksmali/smali/jadx engines so it can unpack APKs, disassemble
        DEX to smali, decompile DEX to Java, assemble smali back to DEX and repack APKs. No
        external binaries are required. All output is written under the app private directory.
        Actions: list_entries, zip_info, manifest_meta, dex_info, dex_strings, dex_classes,
        native_libs, so_info, extract_text. Packed reverse engines (no external binaries):
        decompile_java (jadx DEX->Java), disassemble_smali (baksmali DEX->smali),
        assemble_dex (smali->DEX), decode_apk (apktool unpack), build_apk (apktool repack),
        repack_apk (pure-java: replace dex in the original apk, keeps resources intact).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path of the APK/ZIP/DEX/SO file inside this app's sandbox. Use the path provided by the enhanced-services page.")
                })
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "Operation to perform.")
                    put("enum", buildJsonArray {
                        listOf(
                            "list_entries", "zip_info", "manifest_meta", "dex_info",
                            "dex_strings", "dex_classes", "native_libs", "so_info", "extract_text",
                            "decompile_java", "disassemble_smali", "assemble_dex",
                            "decode_apk", "build_apk", "repack_apk"
                        ).forEach { add(it) }
                    })
                })
                put("entry", buildJsonObject {
                    put("type", "string")
                    put("description", "Zip entry path for extract_text or so_info.")
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional case-insensitive filter for dex_strings / dex_classes.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max number of results. Defaults to 50.")
                })
            },
            required = listOf("path", "action")
        )
    },
    needsApproval = { false },
    execute = {
        val obj = it.jsonObject
        val path = obj["path"]?.jsonPrimitive?.content ?: error("path is required")
        val action = obj["action"]?.jsonPrimitive?.content ?: "zip_info"
        val entry = obj["entry"]?.jsonPrimitive?.content
        val query = obj["query"]?.jsonPrimitive?.content ?: ""
        val limit = obj["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 50

        val result = runCatching {
            val file = resolveApkFile(context, path)
            when (action) {
                "list_entries" -> listEntries(file, limit)
                "zip_info" -> zipInfo(file)
                "manifest_meta" -> manifestMeta(file)
                "dex_info" -> dexInfo(file)
                "dex_strings" -> dexStrings(file, query, limit)
                "dex_classes" -> dexClasses(file, query, limit)
                "native_libs" -> nativeLibs(file, limit)
                "so_info" -> soInfo(file, entry)
                "extract_text" -> extractText(file, entry)
                "decompile_java" -> decompileJava(context, file)
                "disassemble_smali" -> disassembleSmali(context, file)
                "assemble_dex" -> assembleDex(context, file, entry)
                "decode_apk" -> decodeApk(context, file)
                "build_apk" -> buildApk(context, file, entry)
                "repack_apk" -> repackApk(context, file, entry)
                else -> mapOf("error" to "Unknown action: $action")
            }
        }.getOrElse { e -> mapOf("error" to (e.message ?: e.javaClass.simpleName)) }

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("action", action)
                    put("path", path)
                    result.forEach { (k, v) ->
                        when (v) {
                            is Int -> put(k, v)
                            is Long -> put(k, v)
                            is Boolean -> put(k, v)
                            is List<*> -> put(k, JsonPrimitive(v.joinToString("\n")))
                            else -> put(k, JsonPrimitive(v.toString()))
                        }
                    }
                }.toString()
            )
        )
    }
)

fun ApkToolkitTool.executeForAction(
    path: String,
    action: String,
    entry: String? = null,
    query: String = "",
    limit: Int = 50,
): String = run {
    val file = resolveApkFile(context, path)
    val result = runCatching {
        when (action) {
            "list_entries" -> listEntries(file, limit)
            "zip_info" -> zipInfo(file)
            "manifest_meta" -> manifestMeta(file)
            "dex_info" -> dexInfo(file)
            "dex_strings" -> dexStrings(file, query, limit)
            "dex_classes" -> dexClasses(file, query, limit)
            "native_libs" -> nativeLibs(file, limit)
            "so_info" -> soInfo(file, entry)
            "extract_text" -> extractText(file, entry)
            "decompile_java" -> decompileJava(context, file)
            "disassemble_smali" -> disassembleSmali(context, file)
            "assemble_dex" -> assembleDex(context, file, entry)
            "decode_apk" -> decodeApk(context, file)
            "build_apk" -> buildApk(context, file, entry)
            "repack_apk" -> repackApk(context, file, entry)
            else -> mapOf("error" to "Unknown action: $action")
        }
    }.getOrElse { e -> mapOf("error" to (e.message ?: e.javaClass.simpleName)) }

    result.entries.joinToString("\n") { (k, v) ->
        "$k: $v"
    }
}

private fun decompileJava(context: Context, input: File): Map<String, Any> {
    val out = me.rerere.rikkahub.data.reverse.ReverseWorkspace.freshDir(context, "java_${input.nameWithoutExtension}")
    val count = me.rerere.rikkahub.data.reverse.JadxEngine.decompile(input, out)
    return mapOf("output_dir" to out.absolutePath, "java_files" to count)
}

private fun disassembleSmali(context: Context, input: File): Map<String, Any> {
    val out = me.rerere.rikkahub.data.reverse.ReverseWorkspace.freshDir(context, "smali_${input.nameWithoutExtension}")
    val count = me.rerere.rikkahub.data.reverse.SmaliEngine.disassemble(input, out)
    return mapOf("output_dir" to out.absolutePath, "smali_files" to count)
}

private fun assembleDex(context: Context, input: File, entry: String?): Map<String, Any> {
    val smaliDir = entry?.takeIf { it.isNotBlank() }?.let { File(it) }
        ?: File(context.filesDir, "reverse_work/smali_${input.nameWithoutExtension}")
    val outDex = File(context.filesDir, "reverse_work/${input.nameWithoutExtension}_rebuilt.dex")
    val ok = me.rerere.rikkahub.data.reverse.SmaliEngine.assemble(smaliDir, outDex)
    return mapOf("ok" to ok, "input_dir" to smaliDir.absolutePath, "output_dex" to outDex.absolutePath)
}

private fun decodeApk(context: Context, input: File): Map<String, Any> {
    val out = me.rerere.rikkahub.data.reverse.ReverseWorkspace.freshDir(context, "decoded_${input.nameWithoutExtension}")
    me.rerere.rikkahub.data.reverse.ApktoolEngine.decode(context, input, out)
    return mapOf(
        "output_dir" to out.absolutePath,
        "smali_files" to out.walkTopDown().count { it.isFile && it.name.endsWith(".smali") },
        "apktool_yml" to File(out, "apktool.yml").exists(),
    )
}

private fun buildApk(context: Context, input: File, entry: String?): Map<String, Any> {
    val decodedDir = entry?.takeIf { it.isNotBlank() }?.let { File(it) }
        ?: File(context.filesDir, "reverse_work/decoded_${input.nameWithoutExtension}")
    val outApk = File(context.filesDir, "reverse_work/${input.nameWithoutExtension}_rebuilt.apk")
    me.rerere.rikkahub.data.reverse.ApktoolEngine.build(context, decodedDir, outApk)
    return mapOf("ok" to outApk.exists(), "output_apk" to outApk.absolutePath)
}

private fun repackApk(context: Context, input: File, entry: String?): Map<String, Any> {
    // entry 指向 smali 目录（可选，逗号分隔多个），先回编译成 dex 再替换原 APK 内的 dex
    val smaliSpec = entry?.takeIf { it.isNotBlank() }
        ?: File(context.filesDir, "reverse_work/smali_${input.nameWithoutExtension}").absolutePath
    val smaliDirs = smaliSpec.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { File(it) }
    if (smaliDirs.none { it.exists() }) {
        return mapOf("error" to "smali directory not found: $smaliSpec")
    }
    val match = Regex("""classes\d*\.dex""")
    val originalDexNames = java.util.zip.ZipFile(input).use { zip ->
        zip.entries().asSequence().map { it.name }.filter { match.matches(it) }.sorted().toList()
    }
    val builtDex = mutableListOf<File>()
    smaliDirs.forEachIndexed { index, dir ->
        val dexName = originalDexNames.getOrElse(index) { if (index == 0) "classes.dex" else "classes${index + 1}.dex" }
        val outDex = File(context.filesDir, "reverse_work/rebuilt_${input.nameWithoutExtension}_$dexName")
        if (me.rerere.rikkahub.data.reverse.SmaliEngine.assemble(dir, outDex)) {
            builtDex += outDex
        }
    }
    if (builtDex.isEmpty()) return mapOf("error" to "smali assemble produced no dex")
    val outApk = File(context.filesDir, "reverse_work/${input.nameWithoutExtension}_repacked.apk")
    val stats = me.rerere.rikkahub.data.reverse.ApkRepacker.repack(input, builtDex, outApk)
    return linkedMapOf(
        "ok" to outApk.exists(),
        "output_apk" to outApk.absolutePath,
        "smali_dirs" to smaliDirs.size,
        "dex_built" to builtDex.size,
        "copied_entries" to (stats["copied_entries"] ?: 0),
    )
}

private fun resolveApkFile(context: Context, path: String): File {
    val direct = File(path)
    if (direct.isAbsolute && direct.exists() && direct.absolutePath.startsWith(context.filesDir.absolutePath)) {
        return direct
    }
    val inside = File(context.filesDir, path)
    if (inside.exists()) return inside
    return direct
}

private fun listEntries(file: File, limit: Int): Map<String, Any> {
    ZipFile(file).use { zip ->
        val names = zip.entries().asSequence().map { it.name }.take(limit).toList()
        return mapOf(
            "total" to zip.entries().asSequence().count(),
            "shown" to names.size,
            "entries" to names,
        )
    }
}

private fun zipInfo(file: File): Map<String, Any> {
    var entries = 0L
    var compressed = 0L
    var uncompressed = 0L
    ZipFile(file).use { zip ->
        for (e in zip.entries()) {
            entries++
            compressed += e.compressedSize.coerceAtLeast(0)
            uncompressed += e.size.coerceAtLeast(0)
        }
    }
    return mapOf(
        "file_size" to file.length(),
        "entries" to entries,
        "compressed_size" to compressed,
        "uncompressed_size" to uncompressed,
        "ratio" to if (uncompressed > 0) "%.1f%%".format(compressed * 100.0 / uncompressed) else "n/a",
    )
}

private fun manifestMeta(file: File): Map<String, Any> {
    ZipFile(file).use { zip ->
        val entry = zip.getEntry("AndroidManifest.xml") ?: return mapOf("error" to "AndroidManifest.xml not found")
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        val strings = parseAxStringPool(bytes)
        val packageName = strings.firstOrNull { s ->
            s.contains(".") && !s.startsWith("android.") && s.matches(Regex("^[A-Za-z][A-Za-z0-9_.-]*$"))
        } ?: ""
        val permissions = strings.filter { it.startsWith("android.permission.") }.distinct()
        val activities = strings.filter { it.endsWith("Activity") }
        val services = strings.filter { it.endsWith("Service") }
        val receivers = strings.filter { it.endsWith("Receiver") }
        val providers = strings.filter { it.endsWith("Provider") }
        return linkedMapOf(
            "package_name" to packageName,
            "permissions" to permissions,
            "activities" to activities.take(50),
            "services" to services.take(50),
            "receivers" to receivers.take(50),
            "providers" to providers.take(50),
            "string_pool_count" to strings.size,
            "strings" to strings.take(200),
        )
    }
}

private fun dexInfo(file: File): Map<String, Any> {
    val entries = readDexEntries(file)
    if (entries.isEmpty()) return mapOf("error" to "No classes.dex found")
    val infos = entries.map { (name, bytes) -> parseDexHeader(name, bytes) }
    return mapOf(
        "dex_files" to infos.size,
        "info" to infos,
    )
}

private fun dexStrings(file: File, query: String, limit: Int): Map<String, Any> {
    val entries = readDexEntries(file)
    val out = mutableListOf<String>()
    entries.forEach { (_, bytes) -> out.addAll(parseDexStrings(bytes)) }
    val filtered = if (query.isBlank()) out else out.filter { it.contains(query, ignoreCase = true) }
    return mapOf(
        "total_strings" to out.size,
        "shown" to filtered.take(limit).size,
        "strings" to filtered.take(limit),
    )
}

private fun dexClasses(file: File, query: String, limit: Int): Map<String, Any> {
    val entries = readDexEntries(file)
    val out = mutableListOf<String>()
    entries.forEach { (_, bytes) -> out.addAll(parseDexClasses(bytes)) }
    val filtered = if (query.isBlank()) out else out.filter { it.contains(query, ignoreCase = true) }
    return mapOf(
        "total_classes" to out.size,
        "shown" to filtered.take(limit).size,
        "classes" to filtered.take(limit),
    )
}

private fun nativeLibs(file: File, limit: Int): Map<String, Any> {
    ZipFile(file).use { zip ->
        val libs = zip.entries().asSequence()
            .map { it.name }
            .filter { it.startsWith("lib/") && it.endsWith(".so") }
            .take(limit)
            .toList()
        val total = zip.entries().asSequence().count { it.name.startsWith("lib/") && it.name.endsWith(".so") }
        return mapOf("total_libs" to total, "shown" to libs.size, "libs" to libs)
    }
}

private fun soInfo(file: File, entryName: String?): Map<String, Any> {
    if (entryName.isNullOrBlank()) return mapOf("error" to "entry is required for so_info")
    ZipFile(file).use { zip ->
        val entry = zip.getEntry(entryName) ?: return mapOf("error" to "Entry not found: $entryName")
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        return parseElf(bytes)
    }
}

private fun extractText(file: File, entryName: String?): Map<String, Any> {
    if (entryName.isNullOrBlank()) return mapOf("error" to "entry is required for extract_text")
    ZipFile(file).use { zip ->
        val entry = zip.getEntry(entryName) ?: return mapOf("error" to "Entry not found: $entryName")
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        return if (bytes.size > 1024 * 1024) {
            mapOf("bytes" to bytes.size, "preview_hex" to toHex(bytes.copyOfRange(0, 256)))
        } else {
            val text = String(bytes, Charsets.UTF_8)
            if (text.all { ch -> ch.isISOControl().not() || ch == '\n' || ch == '\r' || ch == '\t' }) {
                mapOf("entry" to entryName, "bytes" to bytes.size, "text" to text)
            } else {
                mapOf("entry" to entryName, "bytes" to bytes.size, "preview_hex" to toHex(bytes.copyOfRange(0, minOf(bytes.size, 512))))
            }
        }
    }
}

private fun readDexEntries(file: File): List<Pair<String, ByteArray>> {
    ZipFile(file).use { zip ->
        return zip.entries().asSequence()
            .filter { it.name == "classes.dex" || it.name.matches(Regex("classes\\d*\\.dex")) }
            .sortedBy { it.name }
            .map { it.name to zip.getInputStream(it).use { s -> s.readBytes() } }
            .toList()
    }
}

private fun parseDexHeader(name: String, data: ByteArray): Map<String, Any> {
    if (data.size < 112) return mapOf("file" to name, "error" to "DEX too small")
    val magic = String(data, 0, 8, Charsets.US_ASCII)
    val fileSize = readU32(data, 32)
    val endianTag = readU32(data, 40)
    val stringIdsSize = readU32(data, 56)
    val stringIdsOff = readU32(data, 60)
    val typeIdsSize = readU32(data, 64)
    val typeIdsOff = readU32(data, 68)
    val protoIdsSize = readU32(data, 72)
    val protoIdsOff = readU32(data, 76)
    val fieldIdsSize = readU32(data, 80)
    val fieldIdsOff = readU32(data, 84)
    val methodIdsSize = readU32(data, 88)
    val methodIdsOff = readU32(data, 92)
    val classDefsSize = readU32(data, 96)
    val classDefsOff = readU32(data, 100)
    return linkedMapOf(
        "file" to name,
        "magic" to magic,
        "file_size" to fileSize,
        "endian_tag" to "0x%08x".format(endianTag),
        "string_ids" to stringIdsSize,
        "type_ids" to typeIdsSize,
        "proto_ids" to protoIdsSize,
        "field_ids" to fieldIdsSize,
        "method_ids" to methodIdsSize,
        "class_defs" to classDefsSize,
        "class_defs_off" to classDefsOff,
        "data_off" to readU32(data, 108),
    )
}

private fun parseDexStrings(data: ByteArray): List<String> {
    if (data.size < 112) return emptyList()
    val count = readU32(data, 56).toInt().coerceAtMost(200_000)
    val off = readU32(data, 60).toInt()
    val out = mutableListOf<String>()
    for (i in 0 until count) {
        val pos = off + i * 4
        if (pos + 4 > data.size) break
        val dataOff = readU32(data, pos).toInt()
        if (dataOff < 0 || dataOff >= data.size) continue
        val s = decodeDexString(data, dataOff) ?: continue
        out.add(s)
    }
    return out
}

private fun parseDexClasses(data: ByteArray): List<String> {
    if (data.size < 112) return emptyList()
    val typeIdsSize = readU32(data, 64).toInt()
    val typeIdsOff = readU32(data, 68).toInt()
    val classDefsSize = readU32(data, 96).toInt().coerceAtMost(200_000)
    val classDefsOff = readU32(data, 100).toInt()
    val stringIds = parseDexStrings(data)
    val out = mutableListOf<String>()
    for (i in 0 until classDefsSize) {
        val item = classDefsOff + i * 32
        if (item + 4 > data.size) break
        val classIdx = readU32(data, item).toInt()
        val typePos = typeIdsOff + classIdx * 4
        if (classIdx < 0 || classIdx >= typeIdsSize || typePos + 4 > data.size) continue
        val descriptorIdx = readU32(data, typePos).toInt()
        val descriptor = stringIds.getOrNull(descriptorIdx) ?: continue
        out.add(descriptor)
    }
    return out
}

private fun decodeDexString(data: ByteArray, start: Int): String? {
    var p = start
    // skip uleb128 utf16 length
    var shift = 0
    while (true) {
        if (p >= data.size) return null
        val b = data[p].toInt() and 0xFF
        p++
        if (b and 0x80 == 0) break
        shift += 7
        if (shift > 35) return null
    }
    val bytes = mutableListOf<Byte>()
    while (p < data.size) {
        val b = data[p]
        if (b == 0.toByte()) break
        bytes.add(b)
        p++
    }
    return runCatching { String(bytes.toByteArray(), Charsets.UTF_8) }.getOrNull()
}

private fun parseElf(data: ByteArray): Map<String, Any> {
    if (data.size < 20) return mapOf("error" to "ELF too small")
    if (data[0] != 0x7f.toByte() || data[1] != 'E'.code.toByte() || data[2] != 'L'.code.toByte() || data[3] != 'F'.code.toByte()) {
        return mapOf("error" to "Not an ELF file")
    }
    val clazz = when (data[4].toInt() and 0xFF) {
        1 -> "ELF32"
        2 -> "ELF64"
        else -> "unknown"
    }
    val dataEncoding = when (data[5].toInt() and 0xFF) {
        1 -> "little-endian"
        2 -> "big-endian"
        else -> "unknown"
    }
    val type = readU16le(data, 16)
    val machine = readU16le(data, 18)
    return linkedMapOf(
        "class" to clazz,
        "endianness" to dataEncoding,
        "type" to "0x%04x".format(type),
        "machine" to "0x%04x".format(machine),
        "arch" to elfMachineName(machine),
    )
}

private fun elfMachineName(machine: Int): String = when (machine) {
    62 -> "x86_64"
    3 -> "x86"
    40 -> "ARM"
    183 -> "AArch64"
    243 -> "RISC-V"
    else -> "unknown"
}

private fun parseAxStringPool(data: ByteArray): List<String> {
    if (data.size < 28) return emptyList()
    val headerSize = readU16le(data, 2).toInt()
    val sp = headerSize
    if (sp + 28 > data.size) return emptyList()
    if (readU16le(data, sp) != 0x0001) return emptyList()
    val stringCount = readU32le(data, sp + 8).toInt().coerceAtMost(100_000)
    val flags = readU32le(data, sp + 16)
    val stringsStart = readU32le(data, sp + 20).toInt()
    val utf8 = (flags and 0x100) != 0
    val out = mutableListOf<String>()
    for (i in 0 until stringCount) {
        val pos = sp + 28 + i * 4
        if (pos + 4 > data.size) break
        val rel = readU32le(data, pos).toInt()
        val abs = sp + stringsStart + rel
        if (abs < 0 || abs >= data.size) continue
        val s = runCatching {
            if (utf8) decodeAxUtf8(data, abs) else decodeAxUtf16(data, abs)
        }.getOrNull() ?: continue
        out.add(s)
    }
    return out
}

private fun decodeAxUtf16(data: ByteArray, start: Int): String {
    var p = start
    if (p + 2 > data.size) return ""
    val lenLow = readU16le(data, p); p += 2
    var len = lenLow
    if (lenLow and 0x8000 != 0) {
        if (p + 2 > data.size) return ""
        val lenHigh = readU16le(data, p); p += 2
        len = ((lenLow and 0x7FFF) shl 16) or lenHigh
    }
    val chars = CharArray(minOf(len, (data.size - p) / 2))
    for (i in chars.indices) {
        chars[i] = readU16le(data, p).toChar()
        p += 2
    }
    return String(chars)
}

private fun decodeAxUtf8(data: ByteArray, start: Int): String {
    var p = start
    if (p >= data.size) return ""
    val lenLow = data[p].toInt() and 0xFF; p++
    var len = lenLow
    if (lenLow and 0x80 != 0) {
        if (p >= data.size) return ""
        val lenHigh = data[p].toInt() and 0xFF; p++
        len = ((lenLow and 0x7F) shl 8) or lenHigh
    }
    val n = minOf(len, data.size - p)
    return String(data, p, n, Charsets.UTF_8)
}

private fun readU32(data: ByteArray, off: Int): Long = readU32le(data, off).toLong() and 0xFFFFFFFFL

private fun readU32le(data: ByteArray, off: Int): Int {
    return (data[off].toInt() and 0xFF) or
        ((data[off + 1].toInt() and 0xFF) shl 8) or
        ((data[off + 2].toInt() and 0xFF) shl 16) or
        ((data[off + 3].toInt() and 0xFF) shl 24)
}

private fun readU16le(data: ByteArray, off: Int): Int {
    return (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
}

private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
