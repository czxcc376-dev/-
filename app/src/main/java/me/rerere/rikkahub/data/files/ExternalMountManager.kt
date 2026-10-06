package me.rerere.rikkahub.data.files

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.ExternalMount
import java.io.File

/**
 * 外部目录挂载的读写实现。
 *
 * 外部目录（SAF tree uri）无法直接以路径访问，所以这里采用「镜像」策略：
 * - `syncIn`：把外部目录里的文件复制到私有镜像目录（先清空再复制），使 AI 工具
 *   可以用普通路径 / PRoot 直接读取；
 * - `syncOut`：把私有镜像目录里的文件回写到外部目录（覆盖同名文件、按需创建目录），
 *   让 AI 的修改能落到真实文件夹。
 *
 * 出于安全与性能考虑：限制递归深度、单文件大小与文件总数，避免误授权根目录后卡死。
 */
object ExternalMountManager {
    private const val MAX_DEPTH = 24
    private const val MAX_FILE_BYTES = 256L * 1024 * 1024
    private const val MAX_FILES = 20000

    fun mirrorRoot(context: Context): File =
        File(context.filesDir, "external_mounts").apply { mkdirs() }

    fun mirrorDir(context: Context, mount: ExternalMount): File =
        File(mirrorRoot(context), mount.mirrorDirName).apply { mkdirs() }

    private fun tree(context: Context, mount: ExternalMount): DocumentFile? =
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(mount.treeUri)) }.getOrNull()
            ?.takeIf { it.isDirectory }

    fun isAccessible(context: Context, mount: ExternalMount): Boolean = tree(context, mount) != null

    data class SyncStats(
        val files: Int,
        val dirs: Int,
        val skipped: Int,
        val bytes: Long,
    )

    /** 外部目录 -> 私有镜像（先清空镜像，保证与外部一致）。 */
    suspend fun syncIn(context: Context, mount: ExternalMount): SyncStats = withContext(Dispatchers.IO) {
        val root = tree(context, mount) ?: return@withContext SyncStats(0, 0, 0, 0)
        val dst = mirrorDir(context, mount)
        dst.deleteRecursively()
        dst.mkdirs()

        var files = 0
        var dirs = 0
        var skipped = 0
        var bytes = 0L

        fun walk(dir: DocumentFile, out: File, depth: Int) {
            if (depth > MAX_DEPTH || files >= MAX_FILES) { skipped++; return }
            for (child in dir.listFiles()) {
                if (files >= MAX_FILES) { skipped++; continue }
                val name = child.name ?: continue
                val target = File(out, name)
                if (child.isDirectory) {
                    target.mkdirs()
                    dirs++
                    walk(child, target, depth + 1)
                } else {
                    val size = child.length()
                    if (size > MAX_FILE_BYTES) { skipped++; continue }
                    runCatching {
                        context.contentResolver.openInputStream(child.uri)?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        } ?: run { skipped++ }
                        bytes += size
                        files++
                    }.onFailure { skipped++ }
                }
            }
        }

        walk(root, dst, 0)
        SyncStats(files, dirs, skipped, bytes)
    }

    /** 私有镜像 -> 外部目录（覆盖同名文件，按需建目录）。 */
    suspend fun syncOut(context: Context, mount: ExternalMount): SyncStats = withContext(Dispatchers.IO) {
        val root = tree(context, mount) ?: return@withContext SyncStats(0, 0, 0, 0)
        val src = mirrorDir(context, mount)
        if (!src.exists()) return@withContext SyncStats(0, 0, 0, 0)

        var files = 0
        var dirs = 0
        var skipped = 0
        var bytes = 0L

        fun ensureDir(parent: DocumentFile, name: String): DocumentFile? =
            parent.findFile(name)?.takeIf { it.isDirectory }
                ?: parent.createDirectory(name)

        fun walk(dir: File, out: DocumentFile, depth: Int) {
            if (depth > MAX_DEPTH || files >= MAX_FILES) { skipped++; return }
            for (child in dir.listFiles().orEmpty()) {
                if (files >= MAX_FILES) { skipped++; continue }
                if (child.isDirectory) {
                    val sub = ensureDir(out, child.name)
                    if (sub == null) { skipped++ } else {
                        dirs++
                        walk(child, sub, depth + 1)
                    }
                } else {
                    val existing = out.findFile(child.name)
                    val doc = existing ?: out.createFile("application/octet-stream", child.name)
                    if (doc == null) { skipped++; continue }
                    runCatching {
                        context.contentResolver.openOutputStream(doc.uri, "wt")?.use { output ->
                            child.inputStream().use { input -> input.copyTo(output) }
                        } ?: run { skipped++ }
                        bytes += child.length()
                        files++
                    }.onFailure { skipped++ }
                }
            }
        }

        walk(src, root, 0)
        SyncStats(files, dirs, skipped, bytes)
    }

    /** 删除镜像目录（卸载时清理）。 */
    suspend fun deleteMirror(context: Context, mount: ExternalMount) = withContext(Dispatchers.IO) {
        mirrorDir(context, mount).deleteRecursively()
    }

    fun mirrorPath(context: Context, mount: ExternalMount): String =
        mirrorDir(context, mount).absolutePath
}
