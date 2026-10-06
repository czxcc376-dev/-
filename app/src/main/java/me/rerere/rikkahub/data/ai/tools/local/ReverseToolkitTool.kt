package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.util.Base64
import java.util.zip.CRC32

/**
 * 逆向 / 开发辅助工具箱。
 *
 * 纯 Kotlin 实现，无原生依赖，模型传入内容即可做常见编解码与特征分析：
 * - base64 / hex 编解码（hex 同时给出 ascii 旁注，便于看字符串表）
 * - URL / HTML 实体编解码
 * - 哈希识别（长度启发式）与 CRC32
 * - 文本里提取 IP / 域名 / URL / 邮箱 / JWT / 可疑密钥
 * - 对一段文本 / 已知密钥尝试常见 XOR 单字节爆破（CTF / 简单混淆常用）
 * - 简单熵值估算，用于判断是否加密/压缩数据
 */
fun buildReverseToolkitTool(): Tool = Tool(
    name = "reverse_toolkit",
    description = """
        Offline reverse-engineering and developer toolkit. Use it to decode/encode data and to
        analyze strings when working with APKs, binaries, protocols or captured traffic.
        Actions: base64_decode, base64_encode, hex_decode, hex_encode, url_decode, url_encode,
        html_decode, hash_info, crc32, extract_indicators, xor_bruteforce, entropy.
        This tool is local and offline; it never sends data anywhere.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "Operation to perform.")
                    put("enum", buildJsonArray {
                        listOf(
                            "base64_decode", "base64_encode", "hex_decode", "hex_encode",
                            "url_decode", "url_encode", "html_decode", "hash_info",
                            "crc32", "extract_indicators", "xor_bruteforce", "entropy"
                        ).forEach { add(it) }
                    })
                })
                put("data", buildJsonObject {
                    put("type", "string")
                    put("description", "Input data (text or hex/base64 payload depending on action).")
                })
                put("key", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional key for xor_bruteforce (single byte 0-255 or a short string).")
                })
            },
            required = listOf("action", "data")
        )
    },
    needsApproval = { false },
    execute = {
        val obj = it.jsonObject
        val action = obj["action"]?.jsonPrimitive?.content ?: "extract_indicators"
        val data = obj["data"]?.jsonPrimitive?.content ?: ""
        val key = obj["key"]?.jsonPrimitive?.content

        val result = runCatching { dispatch(action, data, key) }
            .getOrElse { e -> mapOf("error" to (e.message ?: e.javaClass.simpleName)) }

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("action", action)
                    result.forEach { (k, v) ->
                        when (v) {
                            is Int -> put(k, v)
                            is Double -> put(k, v)
                            is Boolean -> put(k, v)
                            else -> put(k, JsonPrimitive(v.toString()))
                        }
                    }
                }.toString()
            )
        )
    }
)

private fun dispatch(action: String, data: String, key: String?): Map<String, Any> = when (action) {
    "base64_decode" -> {
        val bytes = decodeBase64Flexible(data)
        mapOf("decoded_text" to String(bytes, Charsets.UTF_8), "bytes" to bytes.size)
    }

    "base64_encode" -> mapOf("encoded" to Base64.getEncoder().encodeToString(data.toByteArray()))

    "hex_decode" -> {
        val bytes = hexToBytes(data)
        mapOf("decoded_text" to String(bytes, Charsets.UTF_8), "bytes" to bytes.size)
    }

    "hex_encode" -> mapOf("encoded" to toHex(data.toByteArray()))

    "url_decode" -> mapOf("decoded" to java.net.URLDecoder.decode(data, "UTF-8"))

    "url_encode" -> mapOf("encoded" to java.net.URLEncoder.encode(data, "UTF-8"))

    "html_decode" -> mapOf("decoded" to decodeHtmlEntities(data))

    "hash_info" -> {
        val hex = data.filter { it.isLetterOrDigit() }
        val kind = when (hex.length) {
            8 -> "CRC32 / not a secure hash"
            32 -> "MD5"
            40 -> "SHA-1"
            56 -> "SHA-224"
            64 -> "SHA-256"
            96 -> "SHA-384"
            128 -> "SHA-512"
            else -> "unknown"
        }
        mapOf("length" to hex.length, "likely_hash" to kind)
    }

    "crc32" -> {
        val crc = CRC32().apply { update(data.toByteArray()) }
        mapOf("crc32" to crc.value.toString(16), "crc32_dec" to crc.value)
    }

    "extract_indicators" -> extractIndicators(data)

    "xor_bruteforce" -> xorBruteforce(data, key)

    "entropy" -> {
        val bytes = data.toByteArray()
        if (bytes.isEmpty()) mapOf("entropy" to 0.0)
        else {
            val counts = IntArray(256)
            bytes.forEach { counts[it.toInt() and 0xFF]++ }
            val len = bytes.size.toDouble()
            var h = 0.0
            counts.forEach { c ->
                if (c > 0) {
                    val p = c / len
                    h -= p * (kotlin.math.ln(p) / kotlin.math.ln(2.0))
                }
            }
            mapOf("entropy" to (Math.round(h * 1000) / 1000.0), "bytes" to bytes.size)
        }
    }

    else -> mapOf("error" to "Unknown action: $action")
}

private val REGEX_IP = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
private val REGEX_URL = Regex("""https?://[^\s"'<>]+""")
private val REGEX_DOMAIN = Regex("""\b(?:[a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}\b""")
private val REGEX_EMAIL = Regex("""\b[\w.+-]+@[\w-]+\.[\w.-]+\b""")
private val REGEX_JWT = Regex("""\beyJ[\w-]+\.[\w-]+\.[\w-]+\b""")
private val REGEX_SECRET = Regex("""(?i)(?:api[_-]?key|secret|token|passwd|password|bearer)\s*[:=]\s*["']?([\w\-./+]{6,})["']?""")

private fun extractIndicators(data: String): Map<String, Any> {
    val ips = REGEX_IP.findAll(data).map { it.value }.toSet()
    val urls = REGEX_URL.findAll(data).map { it.value }.toSet()
    val domains = REGEX_DOMAIN.findAll(data).map { it.value }
        .filterNot { it in ips || urls.any { u -> u.contains(it) } }
        .toSet()
    val emails = REGEX_EMAIL.findAll(data).map { it.value }.toSet()
    val jwt = REGEX_JWT.findAll(data).map { it.value }.toSet()
    val secrets = REGEX_SECRET.findAll(data).mapNotNull { it.groupValues.getOrNull(1) }.toSet()

    return linkedMapOf(
        "ips" to ips.joinToString(", "),
        "urls" to urls.joinToString(", "),
        "domains" to domains.joinToString(", "),
        "emails" to emails.joinToString(", "),
        "jwt_tokens" to jwt.joinToString(", "),
        "suspected_secrets" to secrets.joinToString(", "),
        "counts" to "ips=${ips.size}, urls=${urls.size}, domains=${domains.size}, emails=${emails.size}, jwt=${jwt.size}, secrets=${secrets.size}",
    )
}

private fun xorBruteforce(data: String, key: String?): Map<String, Any> {
    val bytes = if (data.matches(Regex("""^(?:[0-9a-fA-F]{2})+$"""))) {
        hexToBytes(data)
    } else {
        data.toByteArray()
    }
    val fullKey = key?.takeIf { it.isNotBlank() }?.let { k ->
        if (k.length <= 2 && k.toIntOrNull()?.let { it in 0..255 } == true) {
            byteArrayOf((k.toInt() and 0xFF).toByte())
        } else {
            k.toByteArray()
        }
    }

    fun score(text: String): Double =
        text.count { it.isLetter() || it.isDigit() || it == ' ' }.toDouble() / text.length.coerceAtLeast(1)

    val candidates = mutableListOf<Triple<String, String, Double>>()
    if (fullKey != null) {
        val dec = String(ByteArray(bytes.size) { bytes[it].toInt().xor(fullKey[it % fullKey.size].toInt()).toByte() })
        candidates += Triple("provided", dec, score(dec))
    } else {
        for (b in 0..255) {
            val dec = String(ByteArray(bytes.size) { bytes[it].toInt().xor(b).toByte() })
            candidates += Triple("0x%02x".format(b), dec, score(dec))
        }
    }
    val best = candidates.sortedByDescending { it.third }.take(5)
    return linkedMapOf(
        "best" to best.joinToString("\n") { "${it.first}: ${it.second.take(80)}" },
        "key_used" to (fullKey?.size ?: 1).toString(),
    )
}

private fun decodeBase64Flexible(data: String): ByteArray {
    val cleaned = data.trim()
        .replace("\n", "")
        .replace("\r", "")
        .replace('-', '+')
        .replace('_', '/')
    val padded = when (cleaned.length % 4) {
        2 -> "$cleaned=="
        3 -> "$cleaned="
        else -> cleaned
    }
    return Base64.getDecoder().decode(padded)
}

private fun hexToBytes(data: String): ByteArray {
    val clean = data.replace(Regex("""[\s,:-]"""), "").removePrefix("0x")
        .let { if (it.length % 2 == 1) "0$it" else it }
    return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

private fun toHex(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it) }

private fun decodeHtmlEntities(data: String): String {
    var out = data
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
    out = Regex("""&#x([0-9a-fA-F]+);""").replace(out) { m ->
        m.groupValues[1].toInt(16).toChar().toString()
    }
    out = Regex("""&#(\d+);""").replace(out) { m ->
        m.groupValues[1].toInt().toChar().toString()
    }
    return out
}
