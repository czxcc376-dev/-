package me.rerere.rikkahub.data.files

import android.content.Context
import me.rerere.workspace.WorkspaceBindMount
import java.io.File

/**
 * 工作区基础挂载表。
 *
 * 除内置的 skills / tool_outputs / upload 等私有目录外，直接把宿主机的共享存储目录
 * 挂载进 rootfs，让 AI 工具与终端能直接读写宿主目录（无需 SAF 授权，也无需镜像复制）。
 *
 * 注意：这会暴露宿主机真实文件，属于高权限挂载，仅用于本增强分支。
 */
fun baseWorkspaceBindMounts(context: Context): List<WorkspaceBindMount> = buildList {
    add(WorkspaceBindMount(File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }, "/skills"))
    add(WorkspaceBindMount(File(context.filesDir, FileFolders.BUILTIN_SKILLS).apply { mkdirs() }, "/builtin_skills"))
    add(WorkspaceBindMount(File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }, "/tool_outputs"))
    add(WorkspaceBindMount(File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() }, "/upload"))

    // 宿主根目录直接挂载到 rootfs 的 /sdcard（可读写宿主共享存储根目录）
    val hostRoot = listOfNotNull(
        File("/storage/emulated/0").takeIf { it.isDirectory },
        File("/sdcard").takeIf { it.isDirectory },
    ).firstOrNull()
    if (hostRoot != null) {
        add(WorkspaceBindMount(hostRoot, "/sdcard"))
    }
}
