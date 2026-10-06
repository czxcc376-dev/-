package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
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
        Actions: base64_decode, base64_encode, base32_decode, base32_encode, hex_decode, hex_encode,
        binary_decode, binary_encode, url_decode, url_encode, url_parse, html_decode, hash_info,
        digest, hmac_sha256, aes_encrypt, aes_decrypt, jwt_decode, crc32, extract_indicators,
        xor_bruteforce, entropy, timestamp_convert, hexdump, endian_convert, uuid_info,
        rot13, caesar, gzip_decode, gzip_encode, zlib_decode, zlib_encode, json_pretty.
        Notes: hex_decode requires an even number of hex digits and accepts optional 0x prefixes,
        whitespace, ':', ',' or '-' separators. base64_decode is lenient (accepts URL-safe alphabet
        and missing padding). extract_indicators reports IPv4/IPv6, URLs, domains, emails, JWTs and
        suspected secrets (JWT tokens are never double-counted as domains).
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
                            "base64_decode", "base64_encode", "base32_decode", "base32_encode",
                            "hex_decode", "hex_encode", "binary_decode", "binary_encode",
                            "url_decode", "url_encode", "url_parse", "html_decode", "hash_info",
                            "digest", "hmac_sha256", "aes_encrypt", "aes_decrypt", "jwt_decode",
                            "crc32", "extract_indicators", "xor_bruteforce", "entropy",
                            "timestamp_convert", "hexdump", "endian_convert", "uuid_info",
                            "rot13", "caesar", "gzip_decode", "gzip_encode",
                            "zlib_decode", "zlib_encode", "json_pretty"
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

    "base32_decode" -> {
        val bytes = base32Decode(data)
        linkedMapOf("decoded_text" to String(bytes, Charsets.UTF_8), "bytes" to bytes.size)
    }

    "base32_encode" -> mapOf("encoded" to base32Encode(data.toByteArray()))

    "hex_decode" -> {
        val bytes = hexToBytes(data)
        mapOf("decoded_text" to String(bytes, Charsets.UTF_8), "bytes" to bytes.size)
    }

    "hex_encode" -> mapOf("encoded" to toHex(data.toByteArray()))

    "binary_decode" -> {
        val bits = data.filter { it == '0' || it == '1' }
        if (bits.length % 8 != 0) mapOf("error" to "binary input length must be a multiple of 8 bits")
        else mapOf(
            "decoded_text" to bits.chunked(8).map { it.toInt(2).toByte() }.toByteArray().toString(Charsets.UTF_8),
            "bytes" to bits.length / 8,
        )
    }

    "binary_encode" -> mapOf(
        "encoded" to data.toByteArray().joinToString(" ") { b -> "%8s".format(Integer.toBinaryString(b.toInt() and 0xFF)).replace(' ', '0') }
    )

    "url_decode" -> mapOf("decoded" to java.net.URLDecoder.decode(data, "UTF-8"))

    "url_encode" -> mapOf("encoded" to java.net.URLEncoder.encode(data, "UTF-8"))

    "url_parse" -> urlParse(data)

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

    "rot13" -> mapOf("decoded" to rotN(data, 13), "encoded" to rotN(data, 13))
    "caesar" -> {
        val shift = key?.trim()?.toIntOrNull() ?: (bytesArg.trim().toIntOrNull() ?: 13)
        mapOf(
            "shift" to shift,
            "encoded" to rotN(data, shift),
            "decoded" to rotN(data, -shift),
        )
    }

    "gzip_decode" -> inflateAction(data, gzip = true)
    "gzip_encode" -> deflateAction(data, gzip = true)
    "zlib_decode" -> inflateAction(data, gzip = false)
    "zlib_encode" -> deflateAction(data, gzip = false)

    "json_pretty" -> jsonPretty(data)

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

private val BASE64URL_SEG = Regex("""^[A-Za-z0-9_-]+$""")

private fun isBase64UrlSegment(seg: String): Boolean = seg.isNotEmpty() && BASE64URL_SEG.matches(seg)

private fun jwtDecode(data: String): Map<String, Any> {
    val parts = data.trim().split('.')
    if (parts.size != 3) {
        return mapOf("error" to "Not a compact JWT (expected 3 dot-separated segments, got ${parts.size})")
    }
    if (!parts.all { isBase64UrlSegment(it) }) {
        return mapOf("error" to "Not a compact JWT (segments must be base64url)")
    }
    val headerBytes = runCatching { decodeBase64Flexible(parts[0]) }.getOrNull()
        ?: return mapOf("error" to "Invalid JWT header (bad base64url)")
    val payloadBytes = runCatching { decodeBase64Flexible(parts[1]) }.getOrNull()
        ?: return mapOf("error" to "Invalid JWT payload (bad base64url)")
    val header = String(headerBytes, Charsets.UTF_8).trim()
    val payload = String(payloadBytes, Charsets.UTF_8).trim()
    val headerJson = runCatching { Json.parseToJsonElement(header).jsonObject }.getOrNull()
        ?: return mapOf("error" to "JWT header is not valid JSON")
    runCatching { Json.parseToJsonElement(payload) }.getOrNull()
        ?: return mapOf("error" to "JWT payload is not valid JSON")
    return linkedMapOf(
        "header" to header,
        "payload" to payload,
        "signature_b64" to parts[2],
        "segments" to 3,
        "alg" to (headerJson["alg"]?.jsonPrimitive?.contentOrNull ?: ""),
        "typ" to (headerJson["typ"]?.jsonPrimitive?.contentOrNull ?: ""),
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

private const val B32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

private fun base32Encode(bytes: ByteArray): String {
    val out = StringBuilder()
    var buffer = 0
    var bits = 0
    for (b in bytes) {
        buffer = (buffer shl 8) or (b.toInt() and 0xFF)
        bits += 8
        while (bits >= 5) {
            out.append(B32_ALPHABET[(buffer shr (bits - 5)) and 0x1F])
            bits -= 5
        }
    }
    if (bits > 0) out.append(B32_ALPHABET[(buffer shl (5 - bits)) and 0x1F])
    while (out.length % 8 != 0) out.append('=')
    return out.toString()
}

private fun base32Decode(input: String): ByteArray {
    val clean = input.trim().uppercase().replace("=", "").replace("\s".toRegex(), "")
    val out = ByteArrayOutputStream()
    var buffer = 0
    var bits = 0
    for (ch in clean) {
        val v = B32_ALPHABET.indexOf(ch)
        if (v < 0) continue
        buffer = (buffer shl 5) or v
        bits += 5
        if (bits >= 8) {
            out.write((buffer shr (bits - 8)) and 0xFF)
            bits -= 8
        }
    }
    return out.toByteArray()
}

private fun rotN(text: String, shift: Int): String {
    val n = ((shift % 26) + 26) % 26
    return text.map { ch ->
        when (ch) {
            in 'a'..'z' -> 'a' + ((ch - 'a' + n) % 26)
            in 'A'..'Z' -> 'A' + ((ch - 'A' + n) % 26)
            else -> ch
        }
    }.joinToString("")
}

private fun inflateAction(data: String, gzip: Boolean): Map<String, Any> {
    val raw = runCatching { hexToBytes(data) }.getOrElse { decodeBase64Flexible(data) }
    val bytes = if (gzip) {
        GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
    } else {
        InflaterInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
    }
    return linkedMapOf(
        "decoded_bytes" to bytes.size,
        "decoded_text" to String(bytes, Charsets.UTF_8),
        "hex" to toHex(bytes),
    )
}

private fun deflateAction(data: String, gzip: Boolean): Map<String, Any> {
    val input = data.toByteArray()
    val out = ByteArrayOutputStream()
    if (gzip) {
        GZIPOutputStream(out).use { it.write(input) }
    } else {
        DeflaterOutputStream(out).use { it.write(input) }
    }
    val bytes = out.toByteArray()
    return linkedMapOf(
        "bytes" to bytes.size,
        "hex" to toHex(bytes),
        "base64" to Base64.getEncoder().encodeToString(bytes),
    )
}

private fun jsonPretty(data: String): Map<String, Any> {
    val element = runCatching { Json.parseToJsonElement(data) }.getOrNull()
        ?: return mapOf("error" to "not valid JSON")
    return mapOf("pretty" to Json { prettyPrint = true }.encodeToString(element))
}

private fun urlParse(data: String): Map<String, Any> {
    val uri = runCatching { java.net.URI(data.trim()) }.getOrNull()
        ?: return mapOf("error" to "not a valid URI")
    val query = linkedMapOf<String, String>()
    uri.rawQuery?.split('&')?.forEach { pair ->
        if (pair.isNotEmpty()) {
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            query[runCatching { java.net.URLDecoder.decode(k, "UTF-8") }.getOrDefault(k)] =
                runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        }
    }
    return linkedMapOf(
        "scheme" to (uri.scheme ?: ""),
        "host" to (uri.host ?: ""),
        "port" to (if (uri.port == -1) "" else uri.port.toString()),
        "path" to (uri.path ?: ""),
        "query" to query.entries.joinToString("&") { "${it.key}=${it.value}" },
        "query_map" to query,
        "fragment" to (uri.fragment ?: ""),
        "userinfo" to (uri.userInfo ?: ""),
    )
}

private fun hexToBytesOrUtf8(s: String): ByteArray =
    if (s.matches(Regex("""^(?:[0-9a-fA-F]{2})+$"""))) hexToBytes(s) else s.toByteArray()

private val REGEX_IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
private val REGEX_IPV6 = Regex("""(?ix)
    (?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}                       # full
  | (?:[0-9a-f]{1,4}:){1,7}:                                  # trailing ::
  | (?:[0-9a-f]{1,4}:){1,6}:[0-9a-f]{1,4}
  | (?:[0-9a-f]{1,4}:){1,5}(?::[0-9a-f]{1,4}){1,2}
  | (?:[0-9a-f]{1,4}:){1,4}(?::[0-9a-f]{1,4}){1,3}
  | (?:[0-9a-f]{1,4}:){1,3}(?::[0-9a-f]{1,4}){1,4}
  | (?:[0-9a-f]{1,4}:){1,2}(?::[0-9a-f]{1,4}){1,5}
  | [0-9a-f]{1,4}:(?::[0-9a-f]{1,4}){1,6}
  | :(?:(?::[0-9a-f]{1,4}){1,7}|:)                           # leading ::
  | ::(?:ffff(?::0{1,4})?:)?(?:\d{1,3}\.){3}\d{1,3}
  | (?:[0-9a-f]{1,4}:){1,4}:(?:\d{1,3}\.){3}\d{1,3}
""".trimIndent())
private val REGEX_URL = Regex("""https?://[^\s"'<>]+""")
private val REGEX_DOMAIN = Regex("""\b(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,24}\b""")
private val REGEX_EMAIL = Regex("""\b[\w.+-]+@[\w-]+\.[\w.-]+\b""")
private val REGEX_JWT = Regex("""\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{2,}\.[A-Za-z0-9_-]*\b""")
private val REGEX_SECRET = Regex("""(?i)(?:api[_-]?key|secret|token|passwd|password|bearer|access[_-]?key|private[_-]?key)\s*[:=]\s*["']?([\w\-./+=]{6,})["']?""")
private val REGEX_INTERNAL_HOST = Regex("""\b(?:localhost|[a-zA-Z0-9_-]+\.(?:local|internal|lan|corp|home|intranet))\b""")

private fun ipv4Valid(value: String): Boolean =
    value.split('.').all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }

private fun extractIndicators(data: String): Map<String, Any> {
    val ipv4 = REGEX_IPV4.findAll(data).map { it.value }.filter { ipv4Valid(it) }.toSet()
    val ipv6 = REGEX_IPV6.findAll(data).map { it.value.trim() }.filter { it.contains(':') }.toSet()
    val urls = REGEX_URL.findAll(data).map { it.value.trimEnd('.', ',', ')', ']', ';') }.toSet()
    val emails = REGEX_EMAIL.findAll(data).map { it.value }.toSet()
    val jwt = REGEX_JWT.findAll(data).map { it.value }.toSet()
    val internalHosts = REGEX_INTERNAL_HOST.findAll(data).map { it.value }.toSet()

    // 域名：排除 IP、URL 内部、邮箱域名、JWT 片段与内部主机名
    val domains = REGEX_DOMAIN.findAll(data).map { it.value.lowercase() }
        .filterNot { it in ipv4 || it in ipv6 }
        .filterNot { it in internalHosts }
        .filterNot { candidate -> urls.any { it.contains(candidate, ignoreCase = true) } }
        .filterNot { candidate -> emails.any { it.substringAfter('@').equals(candidate, ignoreCase = true) } }
        .filterNot { candidate -> jwt.any { it.contains(candidate, ignoreCase = true) } }
        .filterNot { candidate -> candidate.none { ch -> ch == '.' } }
        .toSet()

    // 密钥：排除本身是 JWT 或与 JWT 重叠的串
    val secrets = REGEX_SECRET.findAll(data).mapNotNull { it.groupValues.getOrNull(1) }
        .filterNot { value -> jwt.any { it.contains(value) || value.contains(it) } }
        .toSet()

    return linkedMapOf(
        "ips" to ipv4.joinToString(", "),
        "ipv6" to ipv6.joinToString(", "),
        "urls" to urls.joinToString(", "),
        "domains" to domains.joinToString(", "),
        "emails" to emails.joinToString(", "),
        "internal_hosts" to internalHosts.joinToString(", "),
        "jwt_tokens" to jwt.joinToString(", "),
        "suspected_secrets" to secrets.joinToString(", "),
        "counts" to "ipv4=${ipv4.size}, ipv6=${ipv6.size}, urls=${urls.size}, domains=${domains.size}, emails=${emails.size}, internal=${internalHosts.size}, jwt=${jwt.size}, secrets=${secrets.size}",
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
    val clean = data.trim()
        .replace(Regex("""[\s,:-]"""), "")
        .removePrefix("0x").removePrefix("0X")
    if (clean.isEmpty()) return ByteArray(0)
    if (clean.length % 2 == 1) {
        throw IllegalArgumentException("invalid hex: length must be even (got ${clean.length} digits)")
    }
    if (!clean.matches(Regex("""[0-9a-fA-F]*"""))) {
        throw IllegalArgumentException("invalid hex: contains non-hex characters")
    }
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
