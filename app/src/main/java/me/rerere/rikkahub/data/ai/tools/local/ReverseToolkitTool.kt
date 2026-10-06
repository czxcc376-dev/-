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
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 逆向 / 开发辅助工具箱（增强版）。
 *
 * 纯 Kotlin/JDK 实现，无原生依赖，模型传入内容即可做常见编解码、加密、协议数据解析：
 * - base64 / hex 编解码
 * - URL / HTML 实体编解码
 * - 哈希识别与实际摘要（md5 / sha1 / sha224 / sha256 / sha384 / sha512）
 * - HMAC-SHA256
 * - AES-128/256 加解密（CBC/ECB，PKCS5）
 * - JWT 解包与签名算法识别
 * - CRC32
 * - 文本里提取 IP / 域名 / URL / 邮箱 / JWT / 可疑密钥
 * - 对一段文本 / 已知密钥尝试常见 XOR 单字节爆破
 * - 熵值估算，用于判断是否加密/压缩数据
 * - 时间戳/Hexdump/字节序/UUID 解析
 */
fun buildReverseToolkitTool(): Tool = Tool(
    name = "reverse_toolkit",
    description = """
        Offline reverse-engineering and developer toolkit. Use it to decode/encode data, inspect
        hashes, crypto, JWTs, timestamps, byte order and captured strings when working with APKs,
        binaries, protocols or traffic.
        Actions: base64_decode, base64_encode, hex_decode, hex_encode, url_decode, url_encode,
        html_decode, hash_info, digest, hmac_sha256, aes_encrypt, aes_decrypt, jwt_decode,
        crc32, extract_indicators, xor_bruteforce, entropy, timestamp_convert, hexdump,
        endian_convert, uuid_info.
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
                            "digest", "hmac_sha256", "aes_encrypt", "aes_decrypt", "jwt_decode",
                            "crc32", "extract_indicators", "xor_bruteforce", "entropy",
                            "timestamp_convert", "hexdump", "endian_convert", "uuid_info"
                        ).forEach { add(it) }
                    })
                })
                put("data", buildJsonObject {
                    put("type", "string")
                    put("description", "Input data (text, hex, base64 or raw payload depending on action).")
                })
                put("key", buildJsonObject {
                    put("type", "string")
                    put("description", "Key for hmac_sha256 or xor_bruteforce (single byte 0-255 or short string). For AES, hex-encoded 16/24/32 byte key.")
                })
                put("iv", buildJsonObject {
                    put("type", "string")
                    put("description", "Hex-encoded 16-byte IV for aes_encrypt/aes_decrypt CBC mode.")
                })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("description", "AES block mode: CBC or ECB. Defaults to CBC.")
                })
                put("digest", buildJsonObject {
                    put("type", "string")
                    put("description", "Digest algorithm for 'digest' action: MD5, SHA-1, SHA-224, SHA-256, SHA-384, SHA-512.")
                })
                put("bytes", buildJsonObject {
                    put("type", "string")
                    put("description", "For hexdump: hex-encoded bytes. For endian_convert: hex bytes to reorder.")
                })
                put("endian", buildJsonObject {
                    put("type", "string")
                    put("description", "Endian target for endian_convert: little or big.")
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
        val iv = obj["iv"]?.jsonPrimitive?.content
        val mode = obj["mode"]?.jsonPrimitive?.content ?: "CBC"
        val digestAlg = obj["digest"]?.jsonPrimitive?.content
        val bytesArg = obj["bytes"]?.jsonPrimitive?.content ?: data
        val endian = obj["endian"]?.jsonPrimitive?.content ?: "little"

        val result = runCatching {
            dispatch(action, data, key, iv, mode, digestAlg, bytesArg, endian)
        }.getOrElse { e -> mapOf("error" to (e.message ?: e.javaClass.simpleName)) }

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("action", action)
                    result.forEach { (k, v) ->
                        when (v) {
                            is Int -> put(k, v)
                            is Long -> put(k, v)
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

private fun dispatch(
    action: String,
    data: String,
    key: String?,
    iv: String?,
    mode: String,
    digestAlg: String?,
    bytesArg: String,
    endian: String,
): Map<String, Any> = when (action) {
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

    "digest" -> digestAction(data, digestAlg)

    "hmac_sha256" -> {
        val k = hexToBytesOrUtf8(key ?: "")
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(k, "HmacSHA256"))
        }
        mapOf("hmac_sha256" to toHex(mac.doFinal(data.toByteArray())))
    }

    "aes_encrypt" -> aesAction(data, key, iv, mode, encrypt = true)
    "aes_decrypt" -> aesAction(data, key, iv, mode, encrypt = false)

    "jwt_decode" -> jwtDecode(data)

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

    "timestamp_convert" -> timestampConvert(data)
    "hexdump" -> hexdump(bytesArg)
    "endian_convert" -> endianConvert(bytesArg, endian)
    "uuid_info" -> uuidInfo(data)

    else -> mapOf("error" to "Unknown action: $action")
}

private fun digestAction(data: String, alg: String?): Map<String, Any> {
    val normalized = (alg ?: "").uppercase().replace("-", "").let {
        when (it) {
            "", "AUTO" -> inferDigest(data)
            "SHA224" -> "SHA-224"
            "SHA256" -> "SHA-256"
            "SHA384" -> "SHA-384"
            "SHA512" -> "SHA-512"
            else -> it
        }
    }
    return try {
        val bytes = if (data.matches(Regex("""^(?:[0-9a-fA-F]{2})+$"""))) hexToBytes(data) else data.toByteArray()
        val digest = MessageDigest.getInstance(normalized).digest(bytes)
        mapOf("algorithm" to normalized, "hex" to toHex(digest))
    } catch (e: Exception) {
        mapOf("error" to "Unsupported digest '$normalized'", "detail" to (e.message ?: ""))
    }
}

private fun inferDigest(data: String): String {
    val hex = data.filter { it.isLetterOrDigit() }
    return when (hex.length) {
        32 -> "MD5"
        40 -> "SHA-1"
        56 -> "SHA-224"
        64 -> "SHA-256"
        96 -> "SHA-384"
        128 -> "SHA-512"
        else -> "SHA-256"
    }
}

private fun aesAction(data: String, key: String?, iv: String?, mode: String, encrypt: Boolean): Map<String, Any> {
    val keyBytes = hexToBytesOrUtf8(key ?: "")
    if (keyBytes.size != 16 && keyBytes.size != 24 && keyBytes.size != 32) {
        return mapOf("error" to "AES key must decode to 16, 24 or 32 bytes")
    }
    val transformation = if (mode.equals("ECB", true)) "AES/ECB/PKCS5Padding" else "AES/CBC/PKCS5Padding"
    val cipher = Cipher.getInstance(transformation)
    val keySpec = SecretKeySpec(keyBytes, "AES")
    if (mode.equals("ECB", true)) {
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, keySpec)
    } else {
        val ivBytes = if (iv.isNullOrBlank()) ByteArray(16) else hexToBytesOrUtf8(iv)
        if (ivBytes.size != 16) return mapOf("error" to "CBC IV must be 16 bytes")
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(ivBytes))
    }
    val out = if (encrypt) cipher.doFinal(data.toByteArray()) else cipher.doFinal(
        if (data.matches(Regex("""^(?:[0-9a-fA-F]{2})+$"""))) hexToBytes(data) else data.toByteArray()
    )
    return if (encrypt) mapOf("mode" to mode, "ciphertext_hex" to toHex(out))
    else mapOf("mode" to mode, "plaintext" to String(out, Charsets.UTF_8))
}

private fun jwtDecode(data: String): Map<String, Any> {
    val parts = data.trim().split('.')
    if (parts.size < 2) return mapOf("error" to "Not a compact JWT")
    val decode = { s: String -> decodeBase64Flexible(s) }
    val header = runCatching { String(decode(parts[0]), Charsets.UTF_8) }.getOrDefault("")
    val payload = runCatching { String(decode(parts[1]), Charsets.UTF_8) }.getOrDefault("")
    return linkedMapOf(
        "header" to header,
        "payload" to payload,
        "signature_b64" to parts.getOrElse(2) { "" },
        "segments" to parts.size,
    )
}

private fun timestampConvert(data: String): Map<String, Any> {
    val trimmed = data.trim()
    val num = trimmed.toLongOrNull()
    if (num == null) {
        return try {
            val instant = java.time.Instant.parse(trimmed)
            mapOf(
                "epoch_seconds" to instant.epochSecond,
                "epoch_millis" to instant.toEpochMilli(),
                "iso8601_utc" to instant.toString(),
            )
        } catch (e: Exception) {
            mapOf("error" to "Not a valid epoch or ISO-8601 timestamp")
        }
    }
    val instant = if (num > 100_000_000_000L) java.time.Instant.ofEpochMilli(num) else java.time.Instant.ofEpochSecond(num)
    return mapOf(
        "epoch_seconds" to instant.epochSecond,
        "epoch_millis" to instant.toEpochMilli(),
        "iso8601_utc" to instant.toString(),
        "unix_gmt" to java.util.Date.from(instant).toGMTString(),
    )
}

private fun hexdump(data: String): Map<String, Any> {
    val bytes = hexToBytes(data)
    val out = StringBuilder()
    val width = 16
    for (i in bytes.indices step width) {
        out.append("%08x  ".format(i))
        val chunk = bytes.copyOfRange(i, minOf(i + width, bytes.size))
        chunk.forEach { out.append("%02x ".format(it)) }
        repeat(width - chunk.size) { out.append("   ") }
        out.append(" |")
        chunk.forEach { out.append(if (it in 32..126) it.toInt().toChar() else '.') }
        out.append("|\n")
    }
    return mapOf("bytes" to bytes.size, "hexdump" to out.toString())
}

private fun endianConvert(data: String, endian: String): Map<String, Any> {
    val bytes = hexToBytes(data)
    val reversed = bytes.reversedArray()
    val little = if (endian.equals("big", true)) reversed else bytes
    val big = if (endian.equals("big", true)) bytes else reversed
    return mapOf(
        "input_hex" to toHex(bytes),
        "little_endian" to toHex(little),
        "big_endian" to toHex(big),
    )
}

private fun uuidInfo(data: String): Map<String, Any> {
    return try {
        val uuid = UUID.fromString(data.trim())
        mapOf(
            "version" to uuid.version(),
            "variant" to uuid.variant(),
            "most_significant_bits" to uuid.mostSignificantBits,
            "least_significant_bits" to uuid.leastSignificantBits,
            "timestamp_ok" to (uuid.version() == 1),
            "raw_hex" to toHex(ByteArray(16) { i ->
                if (i < 8) ((uuid.mostSignificantBits ushr (8 * (7 - i))) and 0xFF).toByte()
                else ((uuid.leastSignificantBits ushr (8 * (15 - i))) and 0xFF).toByte()
            }),
        )
    } catch (e: Exception) {
        mapOf("error" to "Not a valid UUID", "detail" to (e.message ?: ""))
    }
}

private fun hexToBytesOrUtf8(s: String): ByteArray =
    if (s.matches(Regex("""^(?:[0-9a-fA-F]{2})+$"""))) hexToBytes(s) else s.toByteArray()

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
