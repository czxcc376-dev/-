package me.rerere.rikkahub.data.reverse

import android.content.Context
import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import java.io.File

/**
 * 内置逆向引擎封装。
 *
 * 纯 JVM 实现，直接内联 apktool / baksmali / smali / jadx，无需外部二进制：
 * - [SmaliEngine]   DEX -> smali 反编译，smali -> DEX 回编译
 * - [JadxEngine]    DEX/APK -> Java 源码反编译
 * - [ApktoolEngine] APK 解包 / 回编译（资源 + manifest + smali）
 *
 * 所有输出写入 App 私有目录，避免越权访问外部存储。
 */
object ReverseWorkspace {
    fun root(context: Context): File =
        File(context.filesDir, "reverse_work").apply { mkdirs() }

    fun freshDir(context: Context, name: String): File {
        val dir = File(root(context), name)
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }
}

object ReverseRuntime {
    /** 可用核数：反汇编/反编译/回编译并行度。 */
    val parallelism: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
}

object SmaliEngine {
    /** DEX 或 APK 内所有 dex -> smali 目录，返回生成的 smali 文件数量。 */
    fun disassemble(input: File, outputDir: File, apiLevel: Int = 30, jobs: Int = ReverseRuntime.parallelism): Int {
        outputDir.mkdirs()
        val opcodes = Opcodes.forApi(apiLevel)
        val container = DexFileFactory.loadDexContainer(input, opcodes)
        var count = 0
        for (entryName in container.dexEntryNames) {
            val entry = container.getEntry(entryName) ?: continue
            val options = BaksmaliOptions().apply {
                this.apiLevel = apiLevel
                this.debugInfo = true
            }
            val subDir = if (container.dexEntryNames.size > 1) {
                File(outputDir, entryName.removeSuffix(".dex"))
            } else outputDir
            subDir.mkdirs()
            if (Baksmali.disassembleDexFile(entry.dexFile, subDir, jobs.coerceAtLeast(1), options)) {
                count += subDir.walkTopDown().count { it.isFile && it.name.endsWith(".smali") }
            }
        }
        return count
    }

    /** smali 目录或单文件 -> 单个 DEX。 */
    fun assemble(input: File, outputDex: File, apiLevel: Int = 30, jobs: Int = ReverseRuntime.parallelism): Boolean {
        val options = SmaliOptions().apply {
            this.apiLevel = apiLevel
            this.outputDexFile = outputDex.absolutePath
            this.jobs = jobs.coerceAtLeast(1)
        }
        val targets = if (input.isDirectory) {
            input.walkTopDown().filter { it.isFile && it.name.endsWith(".smali") }
                .map { it.absolutePath }.toList().toTypedArray()
        } else {
            arrayOf(input.absolutePath)
        }
        if (targets.isEmpty()) return false
        outputDex.parentFile?.mkdirs()
        return Smali.assemble(options, *targets)
    }
}

object JadxEngine {
    /**
     * 使用 jadx 将 APK/DEX 并行反编译为 Java 源码到 outputDir/src。
     *
     * 之前是单线程逐类 `cls.code`，全量 APK 会非常慢；这里改用 jadx 内置的
     * `save(threads)`（内部按线程池并行反编译并落盘），再把源码挪到 outputDir/src。
     */
    fun decompile(input: File, outputDir: File, threads: Int = ReverseRuntime.parallelism): Int {
        val srcDir = File(outputDir, "src")
        srcDir.mkdirs()
        val work = File(outputDir, "jadx-out").apply { mkdirs() }
        val args = jadx.api.JadxArgs().apply {
            setInputFile(input)
            setOutDir(work)
            setOutDirSrc(File(work, "sources"))
            setOutDirRes(File(work, "resources"))
            setThreadsCount(threads.coerceAtLeast(1))
            setSkipResources(true)
            setSkipSources(false)
            setEscapeUnicode(false)
            setShowInconsistentCode(false)
            setCommentsLevel(jadx.api.CommentsLevel.ERROR)
            runCatching { setDecompilationMode(jadx.api.DecompilationMode.AUTO) }
        }
        val decompiler = jadx.api.JadxDecompiler(args)
        decompiler.load()
        return try {
            // 并行反编译并落盘（jadx 会使用 threads 个线程）
            decompiler.save(threads.coerceAtLeast(1))
            val generated = File(work, "sources")
            val javaFiles = if (generated.isDirectory) {
                generated.walkTopDown().filter { it.isFile && it.name.endsWith(".java") }.count()
            } else 0
            // 源目录搬回 outputDir/src，便于上层统一处理
            if (generated.isDirectory) {
                generated.copyRecursively(srcDir, overwrite = true)
            }
            javaFiles
        } finally {
            runCatching { decompiler.close() }
            runCatching { work.deleteRecursively() }
        }
    }
}

/**
 * 纯 Java APK 重新打包器。
 *
 * apktool 的回编译依赖原生 aapt2 二进制，在 Android 上无法运行；这里用 ZIP 级重打包实现
 * 可用的「改代码 -> 打包」闭环：保留原 APK 所有条目（资源/清单/so 原样不动），
 * 只替换 classes*.dex。适用于 smali 修改、插桩、去广告等常见场景。
 */
object ApkRepacker {
    /**
     * @param originalApk 原始 APK
     * @param dexFiles    要写入的 DEX 列表，第一个将成为 classes.dex，其余为 classes2.dex...
     * @param outputApk   输出 APK
     * @return 替换与保留的条目数量统计
     */
    fun repack(originalApk: File, dexFiles: List<File>, outputApk: File): Map<String, Int> {
        require(dexFiles.isNotEmpty()) { "至少要有一个 DEX 文件" }
        outputApk.parentFile?.mkdirs()
        var copied = 0
        var replaced = 0
        java.util.zip.ZipFile(originalApk).use { zip ->
            java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(outputApk.outputStream())).use { out ->
                val dexEntryNames = dexFiles.indices.map { i ->
                    if (i == 0) "classes.dex" else "classes${i + 1}.dex"
                }.toSet()
                val seen = mutableSetOf<String>()

                // 1) 复制原 APK 中除 dex 外的所有条目
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.startsWith("classes") && entry.name.endsWith(".dex")) continue
                    if (entry.name.startsWith("META-INF/") &&
                        (entry.name.endsWith(".RSA") || entry.name.endsWith(".SF") || entry.name.endsWith(".DSA"))
                    ) {
                        // 移除旧签名，签名需在打包后重新进行
                        continue
                    }
                    seen.add(entry.name)
                    val newEntry = java.util.zip.ZipEntry(entry.name)
                    newEntry.time = entry.time
                    if (entry.method == java.util.zip.ZipEntry.STORED) {
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        newEntry.method = java.util.zip.ZipEntry.STORED
                        newEntry.size = bytes.size.toLong()
                        newEntry.compressedSize = bytes.size.toLong()
                        newEntry.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                        out.putNextEntry(newEntry)
                        out.write(bytes)
                        out.closeEntry()
                    } else {
                        out.putNextEntry(newEntry)
                        zip.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                    copied++
                }

                // 2) 写入新的 dex
                dexFiles.forEachIndexed { index, dex ->
                    val name = if (index == 0) "classes.dex" else "classes${index + 1}.dex"
                    if (name in seen) replaced++ else copied++
                    val newEntry = java.util.zip.ZipEntry(name)
                    newEntry.method = java.util.zip.ZipEntry.DEFLATED
                    out.putNextEntry(newEntry)
                    dex.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
        return mapOf("copied_entries" to copied, "replaced_dex" to replaced, "dex_written" to dexFiles.size)
    }
}

object ApktoolEngine {
    /**
     * apktool 需要一个已安装的 framework（1.apk）才能解包资源。
     * 这里在运行时从 assets 释放内置 framework 到私有目录，避免依赖外部文件。
     */
    private fun ensureFramework(context: Context): File {
        val dir = File(ReverseWorkspace.root(context), "framework")
        dir.mkdirs()
        val target = File(dir, "1.apk")
        if (!target.exists() || target.length() == 0L) {
            context.assets.open("android-framework.jar").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return dir
    }

    private fun config(context: Context) = brut.androlib.Config().apply {
        setFrameworkDirectory(ensureFramework(context).absolutePath)
        setDecodeSources(brut.androlib.Config.DECODE_SOURCES_SMALI)
        setDecodeResources(brut.androlib.Config.DECODE_RESOURCES_FULL)
        setDecodeAssets(brut.androlib.Config.DECODE_ASSETS_FULL)
        setNoCrunch(true)
        setCopyOriginalFiles(true)
        setApiLevel(30)
    }

    /** APK -> 解包目录（含 smali 与资源）。 */
    fun decode(context: Context, apk: File, outputDir: File): File {
        outputDir.mkdirs()
        val decoder = brut.androlib.ApkDecoder(brut.directory.ExtFile(apk), config(context))
        decoder.decode(outputDir)
        return outputDir
    }

    /** 解包目录 -> 重新打包 APK。 */
    fun build(context: Context, decodedDir: File, outApk: File): File {
        outApk.parentFile?.mkdirs()
        val builder = brut.androlib.ApkBuilder(brut.directory.ExtFile(decodedDir), config(context))
        builder.build(outApk)
        return outApk
    }
}
