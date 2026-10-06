package me.rerere.rikkahub.ui.pages.setting

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bug01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.PackageOpen
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Cpu
import me.rerere.hugeicons.stroke.Bolt
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.local.ApkToolkitTool
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromUri
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.base64Encode
import org.koin.compose.koinInject
import java.io.File

@Composable
fun SettingEnhancedPage(
    settingsStore: SettingsStore = koinInject(),
    conversationRepository: ConversationRepository = koinInject(),
    filesManager: FilesManager = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val reverseTool = remember { ApkToolkitTool(context) }
    var apkPath by remember { mutableStateOf("") }
    var analyzing by remember { mutableStateOf(false) }
    var resultJson by remember { mutableStateOf("") }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    filesManager.saveUploadFromUri(uri)
                }.let { managed ->
                    apkPath = File(context.filesDir, managed.relativePath).absolutePath
                    toaster.show("APK 已导入: ${managed.displayName}", type = ToastType.Success)
                }
            }
        }
    }

    fun runApkAction(action: String, entry: String? = null, query: String = "", limit: Int = 60) {
        if (apkPath.isBlank()) {
            toaster.show("请先选择 APK", type = ToastType.Warning)
            return
        }
        scope.launch {
            analyzing = true
            try {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    reverseTool.executeForAction(apkPath, action, entry, query, limit)
                }
                resultJson = result
            } catch (e: Exception) {
                resultJson = "分析失败: ${e.message}"
            } finally {
                analyzing = false
            }
        }
    }

    fun createAnalysisConversation() {
        if (apkPath.isBlank()) {
            toaster.show("请先选择 APK", type = ToastType.Warning)
            return
        }
        scope.launch {
            val settings = settingsStore.settingsFlow.value
            val assistant = settings.getCurrentAssistant()
            val id = kotlin.uuid.Uuid.random()
            val prompt = buildString {
                appendLine("请帮我分析这个 APK：")
                appendLine("路径：$apkPath")
                appendLine()
                appendLine("内置了 apk_toolkit 本地工具（apktool/baksmali/smali/jadx 已内联，无需外部二进制），可用动作：")
                appendLine("- zip_info / list_entries：包概览、条目")
                appendLine("- manifest_meta：包名、权限、组件（二进制 AXML 解析）")
                appendLine("- dex_info / dex_strings / dex_classes：DEX 头与字符串、类名（找密钥/URL/接口）")
                appendLine("- native_libs / so_info：so 列表与 ELF 架构")
                appendLine("- decode_apk：apktool 解包（得到 smali + 资源）")
                appendLine("- disassemble_smali：DEX 反汇编为 smali")
                appendLine("- decompile_java：jadx 反编译为 Java 源码")
                appendLine("- assemble_dex：smali 回编译为 DEX")
                appendLine("- repack_apk：把 smali 回编译并替换进原 APK（纯 Java，资源不动，输出未签名）")
                appendLine()
                appendLine("建议流程：先 zip_info + manifest_meta 摸清结构，再 dex_strings 搜密钥与端点，")
                appendLine("然后对可疑类做 decompile_java 或 disassemble_smali 深入。")
                appendLine("最后给出：包结构、入口组件、加固/混淆特征、可疑密钥与网络端点、攻击面与结论。")
            }
            conversationRepository.insertConversation(
                Conversation(
                    id = id,
                    assistantId = assistant.id,
                    title = "APK 分析",
                    messageNodes = emptyList(),
                    newConversation = true,
                )
            )
            navController.clearAndNavigate(
                Screen.Chat(
                    id = id.toString(),
                    text = prompt.base64Encode(),
                )
            )
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_enhanced_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardGroup(
                title = { Text(stringResource(R.string.setting_enhanced_apk_section)) },
            ) {
                item(
                    leadingContent = { Icon(HugeIcons.Folder01, null) },
                    headlineContent = { Text("APK 文件") },
                    supportingContent = {
                        Text(if (apkPath.isBlank()) "尚未选择 APK" else apkPath)
                    },
                )
                item(
                    onClick = { apkPicker.launch(arrayOf("application/vnd.android.package-archive")) },
                    leadingContent = { Icon(HugeIcons.PackageOpen, null) },
                    headlineContent = { Text(stringResource(R.string.setting_enhanced_choose_apk)) },
                    supportingContent = { Text(stringResource(R.string.setting_enhanced_choose_apk_desc)) },
                )
                item(
                    onClick = { runApkAction("zip_info") },
                    leadingContent = { Icon(HugeIcons.Search01, null) },
                    headlineContent = { Text(stringResource(R.string.setting_enhanced_apk_overview)) },
                    supportingContent = { Text(stringResource(R.string.setting_enhanced_apk_overview_desc)) },
                )
                item(
                    onClick = { runApkAction("manifest_meta") },
                    leadingContent = { Icon(HugeIcons.Bug01, null) },
                    headlineContent = { Text(stringResource(R.string.setting_enhanced_manifest)) },
                    supportingContent = { Text(stringResource(R.string.setting_enhanced_manifest_desc)) },
                )
                item(
                    onClick = { runApkAction("dex_info") },
                    leadingContent = { Icon(HugeIcons.Bolt, null) },
                    headlineContent = { Text(stringResource(R.string.setting_enhanced_dex_info)) },
                    supportingContent = { Text(stringResource(R.string.setting_enhanced_dex_info_desc)) },
                )
                item(
                    onClick = { runApkAction("native_libs") },
                    leadingContent = { Icon(HugeIcons.PackageOpen, null) },
                    headlineContent = { Text(stringResource(R.string.setting_enhanced_native_libs)) },
                    supportingContent = { Text(stringResource(R.string.setting_enhanced_native_libs_desc)) },
                )
            }

            CardGroup(
                title = { Text(stringResource(R.string.setting_enhanced_reverse_section)) },
            ) {
                item(
                    onClick = { runApkAction("decode_apk") },
                    leadingContent = { Icon(HugeIcons.PackageOpen, null) },
                    headlineContent = { Text(stringResource(R.string.setting_enhanced_decode_apk)) },
                    supportingContent = { Text(stringResource(R.string.setting_enhanced_decode_apk_desc)) },
                )
                item(
                    onClick = { runApkAction("disassemble_smali") },
                    leadingContent = { Icon(HugeIcons.Code, null) },
                    headlineContent = { Text(stringResource(R.string.setting_enhanced_smali)) },
                    supportingContent = { Text(stringResource(R.string.setting_enhanced_smali_desc)) },
                )
                item(
                    onClick = { runApkAction("decompile_java") },
                    leadingContent = { Icon(HugeIcons.Code, null) },
                    headlineContent = { Text(stringResource(R.string.setting_enhanced_java)) },
                    supportingContent = { Text(stringResource(R.string.setting_enhanced_java_desc)) },
                )
                item(
                    onClick = { runApkAction("assemble_dex") },
                    leadingContent = { Icon(HugeIcons.Cpu, null) },
                    headlineContent = { Text(stringResource(R.string.setting_enhanced_assemble_dex)) },
                    supportingContent = { Text(stringResource(R.string.setting_enhanced_assemble_dex_desc)) },
                )
                item(
                    onClick = { runApkAction("repack_apk") },
                    leadingContent = { Icon(HugeIcons.Cpu, null) },
                    headlineContent = { Text(stringResource(R.string.setting_enhanced_repack_apk)) },
                    supportingContent = { Text(stringResource(R.string.setting_enhanced_repack_apk_desc)) },
                )
                item(
                    onClick = { runApkAction("build_apk") },
                    leadingContent = { Icon(HugeIcons.Cpu, null) },
                    headlineContent = { Text(stringResource(R.string.setting_enhanced_build_apk)) },
                    supportingContent = { Text(stringResource(R.string.setting_enhanced_build_apk_desc)) },
                )
            }

            CardGroup(
                title = { Text(stringResource(R.string.setting_enhanced_analysis_section)) },
            ) {
                item(
                    onClick = { createAnalysisConversation() },
                    leadingContent = { Icon(HugeIcons.Bolt, null) },
                    headlineContent = { Text(stringResource(R.string.setting_enhanced_create_session)) },
                    supportingContent = { Text(stringResource(R.string.setting_enhanced_create_session_desc)) },
                )
            }

            if (analyzing) {
                Text(
                    text = "分析中...",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(8.dp),
                )
            }

            if (resultJson.isNotBlank()) {
                OutlinedButton(
                    onClick = { resultJson = "" },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("清除分析结果")
                }
                Text(
                    text = resultJson,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                )
            }
        }
    }
}
