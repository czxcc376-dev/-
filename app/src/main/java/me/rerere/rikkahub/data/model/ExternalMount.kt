package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

/**
 * 外部目录挂载。
 *
 * 用户通过 SAF（系统文件选择器）授权一个外部目录（如 /storage/emulated/0/Download），
 * 应用会把该目录镜像到私有目录 `files/external_mounts/<id>/`，并挂载到 AI 工作区
 * `/mnt/<name>`（同时 PRoot shell 也能访问），从而让 AI 工具「可授权目录并挂载」。
 *
 * @param id            稳定 id（同时作为镜像目录名与挂载点名，避免重名冲突）
 * @param displayName   用户可读名称
 * @param treeUri       授权的 SAF tree uri 字符串（持久权限）
 * @param mirrorDirName 私有镜像目录名
 * @param writable      是否允许 AI 写入并回写到外部目录
 */
@Serializable
data class ExternalMount(
    val id: String,
    val displayName: String,
    val treeUri: String,
    val mirrorDirName: String,
    val writable: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 挂载点在 AI 工作区中的路径前缀。 */
const val EXTERNAL_MOUNT_PREFIX = "/mnt"
