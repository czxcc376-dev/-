package me.rerere.rikkahub.data.files

import android.content.Context
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.EXTERNAL_MOUNT_PREFIX
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import java.io.File

/** 固定的内置挂载点（skills / 工具输出 / 上传目录）。 */
fun baseWorkspaceBindMounts(context: Context): List<WorkspaceBindMount> = listOf(
    WorkspaceBindMount(File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }, "/skills"),
    WorkspaceBindMount(File(context.filesDir, FileFolders.BUILTIN_SKILLS).apply { mkdirs() }, "/builtin_skills"),
    WorkspaceBindMount(File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }, "/tool_outputs"),
    WorkspaceBindMount(File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() }, "/upload"),
)

/**
 * 把「外部目录挂载」的当前配置同步到工作区的挂载表。
 *
 * 挂载点形如 `/mnt/<name>`，source 为私有镜像目录，因此：
 * - AI 的 workspace 文件工具可以直接用路径读写；
 * - PRoot shell 通过 -b 也能访问 `/mnt/<name>`。
 */
class ExternalMountCoordinator(
    private val context: Context,
    private val workspaceManager: WorkspaceManager,
) {
    fun refresh(settings: Settings) {
        val mounts = buildList {
            addAll(baseWorkspaceBindMounts(context))
            settings.externalMounts.forEach { mount ->
                add(
                    WorkspaceBindMount(
                        source = ExternalMountManager.mirrorDir(context, mount),
                        target = "$EXTERNAL_MOUNT_PREFIX/${mount.mirrorDirName}",
                    )
                )
            }
        }
        workspaceManager.setBindMounts(mounts)
    }
}
