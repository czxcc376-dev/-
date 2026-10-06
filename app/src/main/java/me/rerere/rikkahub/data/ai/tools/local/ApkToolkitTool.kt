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
 * - 原生库识别：列出 lib/ 下的 .so 并解析 ELF 架构/位宽/端序
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
        native_libs, so_info, elf_symbols, so_strings, harden_detect (packer identification),
        dex_string_offsets, dex_patch_string, extract_text. Packed reverse engines (no external binaries):
        decompile_java (jadx DEX->Java, multi-threaded), disassemble_smali (baksmali DEX->smali, multi-threaded),
        assemble_dex (smali->DEX, multi-threaded), decode_apk (apktool unpack), build_apk (apktool repack),
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
                            "dex_strings", "dex_classes", "dex_string_offsets", "dex_patch_string",
                            "native_libs", "so_info", "elf_symbols", "so_strings", "harden_detect",
                            "extract_text", "decompile_java", "disassemble_smali", "assemble_dex",
                            "decode_apk", "build_apk", "repack_apk"
                        ).forEach { add(it) }
                    })
                })
                put("entry", buildJsonObject {
                    put("type", "string")
                    put("description", "Zip entry path for extract_text / so_info / elf_symbols / so_strings, or the dex name (e.g. classes.dex) for dex_string_offsets / dex_patch_string.")
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Filter for dex_strings / dex_classes / so_strings, the string to locate for dex_string_offsets, or the replacement value for dex_patch_string (see 'replace').")
                })
                put("replace", buildJsonObject {
                    put("type", "string")
                    put("description", "dex_patch_string: the exact new string. Must be same length or shorter than the original (pads with NUL) to preserve offsets.")
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
        val replace = obj["replace"]?.jsonPrimitive?.content ?: ""
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
                "dex_string_offsets" -> dexStringOffsets(file, query, entry, limit)
                "dex_patch_string" -> dexPatchString(context, file, query, replace, entry)
                "native_libs" -> nativeLibs(file, limit)
                "so_info" -> soInfo(file, entry)
                "elf_symbols" -> elfSymbols(file, entry)
                "so_strings" -> soStrings(file, entry, query, limit)
                "harden_detect" -> hardenDetect(file)
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
    replace: String = "",
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
            "dex_string_offsets" -> dexStringOffsets(file, query, entry, limit)
            "dex_patch_string" -> dexPatchString(context, file, query, replace, entry)
            "native_libs" -> nativeLibs(file, limit)
            "so_info" -> soInfo(file, entry)
            "elf_symbols" -> elfSymbols(file, entry)
            "so_strings" -> soStrings(file, entry, query, limit)
            "harden_detect" -> hardenDetect(file)
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

// ==================== 自研扩展：DEX 字符串改写 / ELF 符号 / 壳识别 ====================

/** 扫描 DEX 中的字符串数据项，返回匹配 [needle] 的 (字节偏移, 字符串)。 */
private fun parseDexStringOffsets(data: ByteArray, needle: String, limit: Int): List<Pair<Int, String>> {
    if (data.size < 112) return emptyList()
    val count = readU32(data, 56).toInt().coerceAtMost(500_000)
    val off = readU32(data, 60).toInt()
    val out = mutableListOf<Pair<Int, String>>()
    for (i in 0 until count) {
        val pos = off + i * 4
        if (pos + 4 > data.size) break
        val dataOff = readU32(data, pos).toInt()
        if (dataOff <= 0 || dataOff >= data.size) continue
        val value = decodeDexString(data, dataOff) ?: continue
        if (value.contains(needle, ignoreCase = true)) {
            // 内容起始处（跳过 uleb128 长度前缀）
            var p = dataOff
            var shift = 0
            while (p < data.size) {
                val b = data[p].toInt() and 0xFF
                p++
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 35) break
            }
            out.add(p to value)
            if (out.size >= limit) break
        }
    }
    return out
}

/**
 * 定位 DEX 字符串数据项在文件中的字节偏移（用于导出后可原地改写）。
 * 返回每个 dex 中匹配字符串的 (dex, offset, original)。
 */
private fun dexStringOffsets(file: File, needle: String, entry: String?, limit: Int): Map<String, Any> {
    if (needle.isBlank()) return mapOf("error" to "query (string to locate) is required")
    val results = mutableListOf<Map<String, Any>>()
    readDexEntries(file).forEach { (name, bytes) ->
        if (entry.isNullOrBlank() || name == entry) {
            parseDexStringOffsets(bytes, needle, limit).forEach { (off, value) ->
                results.add(mapOf("dex" to name, "offset" to off, "offset_hex" to "0x%06x".format(off), "string" to value))
            }
        }
    }
    return mapOf(
        "query" to needle,
        "matches" to results.size,
        "results" to results.take(limit),
    )
}

/**
 * 原地改写 DEX 中的字符串（同名同长度或更短，用 NUL 补齐，保持所有偏移不变）。
 * 直接产出新的 APK / DEX 到私有目录，不改动原文件。
 */
private fun dexPatchString(context: Context, input: File, find: String, replace: String, entry: String?): Map<String, Any> {
    if (find.isBlank() || replace.isEmpty()) return mapOf("error" to "find (query) and replace are required")
    val findBytes = find.toByteArray(Charsets.UTF_8)
    val replaceBytes = replace.toByteArray(Charsets.UTF_8)
    if (replaceBytes.size > findBytes.size) {
        return mapOf("error" to "replacement must be the same length or shorter to preserve DEX offsets (find=${findBytes.size}B, replace=${replaceBytes.size}B)")
    }

    val match = Regex("""classes\d*\.dex""")
    val outApk = File(context.filesDir, "reverse_work/${input.nameWithoutExtension}_patched.apk")
    outApk.parentFile?.mkdirs()
    var patchedDex = 0
    var replacedCount = 0

    java.util.zip.ZipFile(input).use { zip ->
        java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(outApk.outputStream())).use { out ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.name.startsWith("META-INF/") &&
                    (e.name.endsWith(".RSA") || e.name.endsWith(".SF") || e.name.endsWith(".DSA"))
                ) continue

                var bytes = zip.getInputStream(e).use { it.readBytes() }
                if (match.matches(e.name) && (entry.isNullOrBlank() || e.name == entry)) {
                    val result = patchDexBytes(bytes, findBytes, replaceBytes)
                    if (result.second > 0) {
                        bytes = result.first
                        patchedDex++
                        replacedCount += result.second
                    }
                }

                val ne = java.util.zip.ZipEntry(e.name)
                ne.time = e.time
                ne.method = java.util.zip.ZipEntry.DEFLATED
                out.putNextEntry(ne)
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    return linkedMapOf(
        "find" to find,
        "replace" to replace,
        "patched_dex" to patchedDex,
        "replacements" to replacedCount,
        "output_apk" to outApk.absolutePath,
        "note" to "输出未签名；如需安装请重新签名",
    )
}

/** 在 DEX 字节中把字符串数据项里的 [find] 原地替换为 [replace]（NUL 补齐）。返回 (新字节, 替换次数)。 */
private fun patchDexBytes(data: ByteArray, find: ByteArray, replace: ByteArray): Pair<ByteArray, Int> {
    if (data.size < 112) return data to 0
    val stringIdsSize = readU32(data, 56).toInt()
    val stringIdsOff = readU32(data, 60).toInt()
    if (stringIdsOff <= 0 || stringIdsSize <= 0 || stringIdsOff + stringIdsSize * 4 > data.size) return data to 0
    val out = data.copyOf()
    var count = 0
    val pad = ByteArray(find.size - replace.size)
    for (i in 0 until stringIdsSize) {
        val pos = stringIdsOff + i * 4
        if (pos + 4 > out.size) break
        val dataOff = readU32(out, pos).toInt()
        if (dataOff <= 0 || dataOff >= out.size) continue
        // dataOff 指向 uleb128 utf16-length，其后是 MUTF-8 内容
        var p = dataOff
        var shift = 0
        while (p < out.size) {
            val b = out[p].toInt() and 0xFF
            p++
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 35) break
        }
        val contentStart = p
        if (contentStart + find.size > out.size) continue
        var matches = true
        for (j in find.indices) {
            if (out[contentStart + j] != find[j]) { matches = false; break }
        }
        if (!matches) continue
        // 后续必须紧跟 NUL 终止符，避免误伤前缀
        if (contentStart + find.size < out.size && out[contentStart + find.size] != 0.toByte()) continue
        System.arraycopy(replace, 0, out, contentStart, replace.size)
        System.arraycopy(pad, 0, out, contentStart + replace.size, pad.size)
        count++
    }
    return out to count
}

/** 列出 ELF 的符号表（.dynsym / .symtab），识别导入/导出函数。 */
private fun elfSymbols(file: File, entryName: String?): Map<String, Any> {
    val bytes = readElfBytes(file, entryName) ?: return mapOf("error" to "ELF entry not found or not a zip entry")
    val parsed = parseElfFull(bytes)
    return mapOf(
        "arch" to parsed["arch"],
        "bits" to parsed["bits"],
        "symbols" to parsed["symbols"],
        "dynsym_count" to parsed["dynsym_count"],
        "symtab_count" to parsed["symtab_count"],
    )
}

/** 提取 ELF 中可读字符串（.rodata / 全文件扫描），用于找密钥、URL、JNI 名称。 */
private fun soStrings(file: File, entryName: String?, query: String, limit: Int): Map<String, Any> {
    val bytes = readElfBytes(file, entryName) ?: return mapOf("error" to "ELF entry not found or not a zip entry")
    val strings = extractAsciiStrings(bytes, minLength = 5)
    val filtered = if (query.isBlank()) strings else strings.filter { it.contains(query, ignoreCase = true) }
    return linkedMapOf(
        "entry" to (entryName ?: ""),
        "total" to strings.size,
        "shown" to filtered.take(limit).size,
        "strings" to filtered.take(limit),
    )
}

/**
 * 加固 / 壳识别。
 * 通过类名、SO 名与字符串特征，识别常见加固方案（360、梆梆、爱加密、腾讯乐固、娜迦等）。
 */
private fun hardenDetect(file: File): Map<String, Any> {
    val dexStrings = mutableSetOf<String>()
    readDexEntries(file).forEach { (_, bytes) ->
        dexStrings.addAll(parseDexStrings(bytes).take(200_000))
    }
    val libs = mutableListOf<String>()
    java.util.zip.ZipFile(file).use { zip ->
        zip.entries().asSequence()
            .map { it.name }
            .filter { it.startsWith("lib/") && it.endsWith(".so") }
            .forEach { libs.add(it.substringAfterLast('/')) }
    }
    val haystack = (dexStrings + libs).joinToString("\n")

    val signatures = listOf(
        Triple("360 加固", listOf("libjiagu", "libjiagu_art", "libprotectClass", "com.stub.StubApp", "libjgdtc", "libnsighlpr"), "360 加固（libjiagu / StubApp）"),
        Triple("梆梆加固", listOf("libDexHelper", "libSecShell", "com.secneo.apkwrapper", "libsecexe", "libbangcle"), "梆梆加固（SecNeo / DexHelper）"),
        Triple("爱加密", listOf("libexec.so", "libexecmain.so", "s.h.e.l.l", "com.shell.SuperApplication"), "爱加密（libexec / shell）"),
        Triple("腾讯乐固", listOf("libshella", "libshellx", "com.tencent.StubShell", "libtosprotection"), "腾讯乐固（StubShell / libshellx）"),
        Triple("娜迦", listOf("libchaosvmp", "libddog", "libfdog", "libnqshield"), "娜迦（libchaosvmp / libfdog）"),
        Triple("顶象", listOf("libDexHelper", "com.dingxiang"), "顶象加固"),
        Triple("通付盾", listOf("libtup", "com.tfshell"), "通付盾加固"),
        Triple("阿里聚安全", listOf("libmobisec", "com.ali.mobisecenhance"), "阿里聚安全"),
        Triple("加固通用特征", listOf("libjiagu", "libprotect", "libshell", "libexec", "libDexHelper", "StubApp", "ProxyApplication", "libvmp"), "存在加固/壳通用特征"),
        Triple("VMP 特征", listOf("libchaosvmp", "libvmp", "libvm", "libattack"), "疑似 VMP 虚拟化保护"),
    )

    val hits = mutableListOf<String>()
    signatures.forEach { (_, needles, label) ->
        if (needles.any { haystack.contains(it, ignoreCase = true) }) hits.add(label)
    }

    // 可疑加壳迹象：Application 类被替换、dex 数量异常、存在 assets 里的加密 dex
    val suspicious = mutableListOf<String>()
    java.util.zip.ZipFile(file).use { zip ->
        val names = zip.entries().asSequence().map { it.name }.toList()
        val dexCount = names.count { it.matches(Regex("""classes\d*\.dex""")) }
        if (dexCount <= 1) suspicious.add("仅 $dexCount 个 dex（可能为壳的 loader）")
        names.filter { it.startsWith("assets/") && (it.endsWith(".dex") || it.endsWith(".jar") || it.endsWith(".bin")) }
            .forEach { suspicious.add("assets 中存在可疑载荷: $it") }
    }

    return linkedMapOf(
        "likely_packed" to hits.isNotEmpty(),
        "matches" to hits.distinct(),
        "suspicious" to suspicious,
        "native_libs" to libs,
        "dex_string_samples" to dexStrings.size,
    )
}

private fun readElfBytes(file: File, entryName: String?): ByteArray? {
    if (entryName.isNullOrBlank()) return null
    java.util.zip.ZipFile(file).use { zip ->
        val entry = zip.getEntry(entryName) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }
}

/** 轻量 ELF 解析：读取节区表、符号表与字符串表。 */
private fun parseElfFull(data: ByteArray): Map<String, Any> {
    if (data.size < 52) return mapOf("error" to "ELF too small")
    val is64 = data[4].toInt() == 2
    val little = data[5].toInt() == 1
    fun u16(off: Int) = if (little) (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
    else ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
    fun u32(off: Int): Long {
        var v = 0L
        for (i in 0 until 4) {
            val b = data[off + i].toInt() and 0xFF
            v = if (little) v or (b.toLong() shl (8 * i)) else (v shl 8) or b.toLong()
        }
        return v
    }
    fun u64(off: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            val b = data[off + i].toLong() and 0xFF
            v = if (little) v or (b shl (8 * i)) else (v shl 8) or b
        }
        return v
    }

    val machine = u16(18)
    val arch = when (machine) { 183 -> "aarch64"; 40 -> "arm"; 62 -> "x86_64"; 3 -> "x86"; 243 -> "riscv"; else -> "0x%x".format(machine) }

    val (shoff, shentsize, shnum, shstrndx) = if (is64) {
        Quad(u64(40).toInt(), u16(58), u16(60), u16(62))
    } else {
        Quad(u32(32).toInt(), u16(46), u16(48), u16(50))
    }
    if (shoff <= 0 || shnum <= 0 || shoff + shnum * shentsize > data.size) {
        return mapOf("arch" to arch, "bits" to if (is64) 64 else 32, "symbols" to emptyList<String>(), "dynsym_count" to 0, "symtab_count" to 0)
    }

    fun shName(off: Int): Long = if (is64) u32(off).toLong() else u32(off).toLong()
    fun shType(off: Int): Long = if (is64) u32(off + 4) else u32(off + 4)
    fun shOffset(off: Int): Long = if (is64) u64(off + 24) else u32(off + 16)
    fun shSize(off: Int): Long = if (is64) u64(off + 32) else u32(off + 20)
    fun shLink(off: Int): Long = if (is64) u32(off + 40) else u32(off + 24)
    fun shEntSize(off: Int): Long = if (is64) u64(off + 56) else u32(off + 36)

    var namesOff = 0L
    if (shstrndx < shnum) {
        val off = shoff + shstrndx * shentsize
        namesOff = shOffset(off)
    }

    fun sectionName(nameIdx: Long): String {
        if (namesOff <= 0 || namesOff + nameIdx >= data.size) return ""
        var p = (namesOff + nameIdx).toInt()
        val sb = StringBuilder()
        while (p < data.size && data[p] != 0.toByte()) sb.append((data[p].toInt() and 0xFF).toChar()).also { p++ }
        return sb.toString()
    }

    val symbols = mutableListOf<String>()
    var dynsym = 0
    var symtab = 0

    for (i in 0 until shnum) {
        val off = shoff + i * shentsize
        val type = shType(off).toInt()
        if (type != 2 && type != 11) continue // SHT_SYMTAB / SHT_DYNSYM
        val isDyn = type == 11
        val symOff = shOffset(off)
        val symSize = shSize(off)
        val entSize = shEntSize(off).toInt().takeIf { it > 0 } ?: if (is64) 24 else 16
        val strTabIdx = shLink(off).toInt()
        if (strTabIdx >= shnum) continue
        val strOff = shOffset(shoff + strTabIdx * shentsize)
        val count = (symSize / entSize).toInt()
        if (isDyn) dynsym += count else symtab += count
        for (s in 0 until count) {
            val sOff = (symOff + s * entSize).toInt()
            if (sOff + entSize > data.size) break
            val nameIdx = if (is64) u32(sOff) else u32(sOff)
            var p = (strOff + nameIdx).toInt()
            if (p < 0 || p >= data.size) continue
            val sb = StringBuilder()
            var guard = 0
            while (p < data.size && data[p] != 0.toByte() && guard < 256) {
                sb.append((data[p].toInt() and 0xFF).toChar()); p++; guard++
            }
            val nm = sb.toString()
            if (nm.isNotBlank()) symbols.add(nm)
        }
    }

    return mapOf(
        "arch" to arch,
        "bits" to if (is64) 64 else 32,
        "symbols" to symbols.distinct().take(1200),
        "dynsym_count" to dynsym,
        "symtab_count" to symtab,
    )
}

private data class Quad(val shoff: Int, val shentsize: Int, val shnum: Int, val shstrndx: Int)

/** 扫描 byte 数组提取可打印 ASCII 字符串。 */
private fun extractAsciiStrings(data: ByteArray, minLength: Int): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    for (b in data) {
        val c = b.toInt() and 0xFF
        if (c in 32..126) {
            sb.append(c.toChar())
        } else {
            if (sb.length >= minLength) out.add(sb.toString())
            sb.setLength(0)
        }
        if (out.size >= 50000) break
    }
    if (sb.length >= minLength) out.add(sb.toString())
    return out.distinct()
}
