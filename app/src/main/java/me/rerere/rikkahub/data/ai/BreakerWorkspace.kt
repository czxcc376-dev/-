package me.rerere.rikkahub.data.ai

import android.content.Context
import java.io.File

/**
 * BreakerWorkspace —— 工作区文件通道
 *
 * 把运行配置写成工作区里的项目约定文件（AGENTS.md），并在请求中以
 * 「真实读取该文件」的工具调用结果形态回传。文件真实存在，内容与工具
 * 结果一致，因此不依赖伪造内容。
 */
object BreakerWorkspace {

    const val FILE_NAME = "AGENTS.md"

    private const val DIR_NAME = "workspace"

    fun configFile(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) runCatching { dir.mkdirs() }
        return File(dir, FILE_NAME)
    }

    /** 写入配置并返回 (文件, 实际落盘内容) */
    fun ensure(context: Context, content: String): Pair<File, String> {
        val file = configFile(context)
        runCatching {
            val old = if (file.exists()) file.readText() else null
            if (old != content) file.writeText(content)
        }
        val actual = runCatching { file.readText() }.getOrNull() ?: content
        return file to actual
    }

    fun readExisting(context: Context): String? =
        runCatching { configFile(context).takeIf { it.exists() }?.readText() }.getOrNull()

    /** 工作区内的相对路径（供工具参数展示） */
    fun relativePath(): String = FILE_NAME
}
