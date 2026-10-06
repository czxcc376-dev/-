package me.rerere.rikkahub.data.reverse

import android.content.Context
import me.rerere.workspace.readResult
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/**
 * 内置 radare2 引擎。
 *
 * radare2 官方发布的 Android 包是一个 multi-call 二进制（r2blob）+ 一组共享模块库，
 * 它通过 argv[0] 的 basename 决定自己扮演哪个工具（r2 / rabin2 / rasm2 / rahash2 /
 * radiff2 / rafind2 / rax2 ...）。Android 只允许从应用 nativeLibraryDir 执行原生文件，
 * 且文件名必须以 "lib" 开头并以 ".so" 结尾，因此这里：
 *
 * - 把 r2blob 作为 `libmain_r2.so`、23 个 `libr_*.so` 模块库一并放进 jniLibs；
 * - 用一个 5KB 的静态 PIE 启动器 `libmain_r2_launcher.so` 修正 argv[0] 后再 exec
 *   （见仓库中 r2launcher/launcher.c 的说明）；
 * - 把 radare2 的 share 数据（magic/sdb/format/fcnsign 等）作为资产打包，首次使用时
 *   释放到私有目录，并通过 R2_PREFIX 指给 r2。
 *
 * 全程离线，输出落在 App 私有目录。
 */
object Radare2Engine {
    /** 多调用启动器（静态 PIE，5KB，无 libc 依赖）。 */
    private const val LAUNCHER = "libmain_r2_launcher.so"

    /** radare2 multi-call blob（真实文件，argv[0] 决定工具身份）。 */
    private const val BLOB = "libmain_r2.so"

    private const val SHARE_ASSET = "radare2-share.tar.gz"
    private const val R2_VERSION = "6.2.4"

    /** 暴露给模型的 radare2 子工具。 */
    val TOOLS = listOf("r2", "radare2", "rabin2", "rasm2", "rahash2", "radiff2", "rafind2", "rax2")

    private fun root(context: Context): File = File(context.filesDir, "radare2")

    fun shareDir(context: Context): File = File(root(context), "share")

    private fun workDir(context: Context): File = File(root(context), "work").apply { mkdirs() }

    private fun homeDir(context: Context): File = File(root(context), "home").apply { mkdirs() }

    private fun tmpDir(context: Context): File = File(root(context), "tmp").apply { mkdirs() }

    private fun nativeDir(context: Context): File = File(context.applicationInfo.nativeLibraryDir)

    fun isAvailable(context: Context): Boolean {
        val dir = nativeDir(context)
        return File(dir, LAUNCHER).isFile && File(dir, BLOB).isFile
    }

    /** 确保 share 数据已释放；幂等且线程安全。 */
    @Synchronized
    fun ensure(context: Context) {
        val marker = File(root(context), ".share-$R2_VERSION")
        val dst = shareDir(context)
        if (marker.isFile && File(dst, "radare2/$R2_VERSION").isDirectory) return

        val staging = File(root(context), "share.staging")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            context.assets.open(SHARE_ASSET).use { raw -> extractTarGz(raw, staging) }
            val extracted = File(staging, "share")
            val source = if (extracted.isDirectory) extracted else staging
            dst.deleteRecursively()
            dst.mkdirs()
            if (!source.renameTo(dst)) {
                source.copyRecursively(dst, overwrite = true)
            }
            marker.writeText(R2_VERSION)
        } finally {
            staging.deleteRecursively()
        }
    }

    data class R2Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val truncated: Boolean,
    ) {
        val ok: Boolean get() = exitCode == 0 && !timedOut
        val combined: String get() = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
    }

    /**
     * 同步执行一个 radare2 子工具。调用方负责放到 IO 线程。
     *
     * @param tool r2 / radare2 / rabin2 / rasm2 / rahash2 / radiff2 / rafind2 / rax2
     * @param arguments 该工具的参数（不含程序名）
     * @param timeoutMillis 超时；超时会强杀进程
     * @param stdin 可选标准输入（例如 `r2 -q -` 的脚本）
     */
    fun run(
        context: Context,
        tool: String,
        arguments: List<String>,
        timeoutMillis: Long = 30_000,
        stdin: ByteArray? = null,
    ): R2Result {
        if (!isAvailable(context)) {
            return R2Result(127, "", "radare2 native payload missing (arm64 only)", false, false)
        }
        ensure(context)

        val dir = nativeDir(context)
        val launcher = File(dir, LAUNCHER)
        val command = mutableListOf(launcher.absolutePath, tool)
        command += arguments

        val process = ProcessBuilder(command)
            .directory(workDir(context))
            .redirectErrorStream(false)
            .apply {
                val env = environment()
                // 让加载器能在 nativeLibraryDir 找到 libr_*.so 模块
                env["LD_LIBRARY_PATH"] = buildString {
                    append(dir.absolutePath)
                    env["LD_LIBRARY_PATH"]?.let { append(':').append(it) }
                }
                // radare2 的前缀：share/ 与 lib/ 都相对它解析
                env["R2_PREFIX"] = root(context).absolutePath
                env["HOME"] = homeDir(context).absolutePath
                env["TMPDIR"] = tmpDir(context).absolutePath
                env["TERM"] = "dumb"
                env["R2_COLOR"] = "0"
                // 抑制交互（分页器/编辑器/终端控制）
                env["PAGER"] = "cat"
                env["NO_COLOR"] = "1"
                env["CI"] = "true"
            }
            .start()

        val result = process.readResult(timeoutMillis, stdin)
        return R2Result(
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            timedOut = result.timedOut,
            truncated = result.truncated,
        )
    }

    fun version(context: Context): String =
        run(context, "rabin2", listOf("-v"), timeoutMillis = 15_000).combined.trim()

    // ---- 内置 tar.gz 解包（仅用于释放 share 资产） ----

    private fun extractTarGz(input: InputStream, targetDir: File) {
        GZIPInputStream(BufferedInputStream(input)).use { gz ->
            val header = ByteArray(512)
            var pendingName: String? = null
            var pendingLink: String? = null

            while (true) {
                if (!readFully(gz, header)) break
                if (header.all { it == 0.toByte() }) break

                var name = readString(header, 0, 100)
                val size = readOctal(header, 124, 12)
                val type = header[156].toInt().toChar()
                val link = readString(header, 157, 100)
                val prefix = readString(header, 345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"

                when (type) {
                    'L' -> {
                        pendingName = readN(gz, size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                        skipPadding(gz, size)
                        continue
                    }

                    'x' -> {
                        val pax = readN(gz, size).toString(Charsets.UTF_8)
                        parsePax(pax).let { map ->
                            map["path"]?.let { pendingName = it }
                            map["linkpath"]?.let { pendingLink = it }
                        }
                        skipPadding(gz, size)
                        continue
                    }

                    'g' -> {
                        skipN(gz, size)
                        skipPadding(gz, size)
                        continue
                    }
                }

                val entryName = pendingName ?: name
                val entryLink = pendingLink ?: link
                pendingName = null
                pendingLink = null

                if (entryName.isBlank()) {
                    skipN(gz, size)
                    skipPadding(gz, size)
                    continue
                }

                val out = safeResolve(targetDir, entryName)
                when (type) {
                    '5' -> {
                        out.mkdirs()
                        skipN(gz, size)
                    }

                    '2' -> {
                        out.parentFile?.mkdirs()
                        out.delete()
                        runCatching { Files.createSymbolicLink(out.toPath(), File(entryLink).toPath()) }
                        skipN(gz, size)
                    }

                    '0', '\u0000' -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { output -> copyN(gz, output, size) }
                    }

                    else -> skipN(gz, size)
                }
                skipPadding(gz, size)
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) return read != 0
            read += n
        }
        return true
    }

    private fun readString(buffer: ByteArray, offset: Int, length: Int): String {
        val end = (offset until minOf(offset + length, buffer.size))
            .firstOrNull { buffer[it] == 0.toByte() } ?: minOf(offset + length, buffer.size)
        return String(buffer, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun readOctal(buffer: ByteArray, offset: Int, length: Int): Long {
        val text = readString(buffer, offset, length).trim()
        if (text.isEmpty()) return 0
        return runCatching { text.toLong(8) }.getOrDefault(0)
    }

    private fun readN(input: InputStream, count: Long): ByteArray {
        val out = ByteArray(count.toInt())
        var read = 0
        while (read < out.size) {
            val n = input.read(out, read, out.size - read)
            if (n < 0) break
            read += n
        }
        return if (read == out.size) out else out.copyOf(read)
    }

    private fun copyN(input: InputStream, output: java.io.OutputStream, count: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (n < 0) break
            output.write(buffer, 0, n)
            remaining -= n
        }
    }

    private fun skipN(input: InputStream, count: Long) {
        var remaining = count
        val scratch = ByteArray(16 * 1024)
        while (remaining > 0) {
            val n = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (n < 0) break
            remaining -= n
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val padding = (512 - (size % 512)) % 512
        if (padding > 0) skipN(input, padding)
    }

    private fun parsePax(text: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        text.split('\n').forEach { line ->
            val space = line.indexOf(' ')
            if (space <= 0) return@forEach
            val key = line.substring(space + 1).substringBefore('=')
            val value = line.substringAfter('=', "")
            if (key.isNotEmpty()) map[key] = value
        }
        return map
    }

    private fun safeResolve(base: File, name: String): File {
        val cleaned = name.replace('\\', '/').trimStart('/')
        val file = File(base, cleaned)
        val basePath = base.canonicalFile
        val target = file.canonicalFile
        require(target.path == basePath.path || target.path.startsWith(basePath.path + File.separator)) {
            "Archive entry escapes target dir: $name"
        }
        return file
    }
}
