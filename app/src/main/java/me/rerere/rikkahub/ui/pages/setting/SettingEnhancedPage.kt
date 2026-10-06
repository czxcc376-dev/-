package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bolt
import me.rerere.hugeicons.stroke.Bug01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Cpu
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.PackageOpen
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.local.ApkToolkitTool
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.executeForAction
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromUri
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.reverse.Radare2Engine
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.base64Encode
import org.koin.compose.koinInject
import java.io.File

/** 会话（工作会话）标题：由本页创建的 APK 分析会话，用于自动清理。 */
private const val ANALYSIS_SESSION_TITLE = "APK 分析"


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
    var radare2Version by remember { mutableStateOf("检测中…") }

    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle(
        initialValue = settingsStore.settingsFlow.value,
    )
    val assistant = settings.getCurrentAssistant()
    val enabledTools = assistant.localTools

    LaunchedEffect(Unit) {
        radare2Version = withContext(Dispatchers.IO) {
            if (!Radare2Engine.isAvailable(context)) {
                "不可用（当前仅内置 arm64）"
            } else {
                runCatching { Radare2Engine.version(context) }.getOrDefault("检测失败")
            }
        }
    }

    fun setFeature(option: LocalToolOption, enabled: Boolean) {
        val current = settingsStore.settingsFlow.value.getCurrentAssistant()
        val newTools = if (enabled) current.localTools + option else current.localTools - option
        scope.launch {
            settingsStore.update { s ->
                s.copy(assistants = s.assistants.map { a ->
                    if (a.id == current.id) a.copy(localTools = newTools) else a
                })
            }
        }
    }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                withContext(Dispatchers.IO) { filesManager.saveUploadFromUri(uri) }.let { managed ->
                    apkPath = File(context.filesDir, managed.relativePath).absolutePath
                    toaster.show("文件已导入: ${managed.displayName}", type = ToastType.Success)
                }
            }
        }
    }

    fun runApkAction(action: String, entry: String? = null, query: String = "", limit: Int = 60) {
        if (apkPath.isBlank()) {
            toaster.show("请先选择文件", type = ToastType.Warning)
            return
        }
        scope.launch {
            analyzing = true
            try {
                resultJson = withContext(Dispatchers.IO) {
                    reverseTool.executeForAction(apkPath, action, entry, query, limit)
                }
            } catch (e: Exception) {
                resultJson = "分析失败: ${e.message}"
            } finally {
                analyzing = false
            }
        }
    }

    fun runRadare2(args: List<String>, tool: String = "rabin2") {
        if (apkPath.isBlank()) {
            toaster.show("请先选择文件", type = ToastType.Warning)
            return
        }
        scope.launch {
            analyzing = true
            try {
                resultJson = withContext(Dispatchers.IO) {
                    val file = File(apkPath)
                    val r = Radare2Engine.run(context, tool, args + file.absolutePath, timeoutMillis = 90_000)
                    "exit=${r.exitCode}\n" + r.combined
                }
            } catch (e: Exception) {
                resultJson = "radare2 失败: ${e.message}"
            } finally {
                analyzing = false
            }
        }
    }

    /** 清理「工作会话」：把空的分析会话从会话列表移除，保持整洁。 */
    fun cleanAnalysisSessions(notify: Boolean) {
        scope.launch {
            val removed = withContext(Dispatchers.IO) {
                var count = 0
                settingsStore.settingsFlow.value.assistants.forEach { a ->
                    runCatching {
                        conversationRepository.getConversationsOfAssistant(a.id).first()
                    }.getOrDefault(emptyList()).forEach { conv ->
                        val isAnalysis = conv.title == ANALYSIS_SESSION_TITLE
                        val isEmpty = conv.messageNodes.isEmpty()
                        if (isAnalysis && isEmpty) {
                            runCatching { conversationRepository.deleteConversation(conv) }
                            count++
                        }
                    }
                }
                count
            }
            if (notify) {
                toaster.show("已清理 $removed 个空工作会话", type = ToastType.Success)
            }
        }
    }

    val apkSessionsFlow = remember(assistant.id) {
        conversationRepository.getConversationsOfAssistant(assistant.id)
            .map { list -> list.count { it.title == ANALYSIS_SESSION_TITLE } }
    }
    val apkSessions by apkSessionsFlow.collectAsStateWithLifecycle(initialValue = 0)

    fun createAnalysisConversation() {
        if (apkPath.isBlank()) {
            toaster.show("请先选择文件", type = ToastType.Warning)
            return
        }
        // 新会话前先清理遗留的空会话，避免堆积。
        cleanAnalysisSessions(notify = false)
        scope.launch {
            val assistantNow = settingsStore.settingsFlow.value.getCurrentAssistant()
            val id = kotlin.uuid.Uuid.random()
            val prompt = buildString {
                appendLine("请帮我分析这个文件：")
                appendLine("路径：$apkPath")
                appendLine()
                appendLine("可用本地工具：")
                appendLine("- radare2：rabin2 文件信息/导入/导出/字符串；r2 反汇编、函数、交叉引用、搜索、差异")
                appendLine("- apk_toolkit：zip_info、manifest_meta、dex_info/dex_strings/dex_classes、native_libs/so_info")
                appendLine("- apk_toolkit 逆向动作：decode_apk、disassemble_smali、decompile_java、assemble_dex、repack_apk")
                appendLine()
                appendLine("建议：先 rabin2 -I / manifest_meta 摸结构，再 strings/dex_strings 找密钥与端点，")
                appendLine("然后 decompile_java 或 r2 反汇编深入。最后给出：包结构、入口组件、加固/混淆特征、")
                appendLine("可疑密钥与网络端点、攻击面与结论。")
            }
            conversationRepository.insertConversation(
                Conversation(
                    id = id,
                    assistantId = assistantNow.id,
                    title = ANALYSIS_SESSION_TITLE,
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
            FeatureToggles(
                enabledTools = enabledTools,
                radare2Version = radare2Version,
                sessionCount = apkSessions,
                onToggle = ::setFeature,
                onCleanSessions = { cleanAnalysisSessions(notify = true) },
            )

            StorageAccessCard()

            ToolWorkbench(
                apkPath = apkPath,
                analyzing = analyzing,
                resultJson = resultJson,
                onPick = { apkPicker.launch(arrayOf("*/*")) },
                onClearResult = { resultJson = "" },
                onApkAction = ::runApkAction,
                onRadare2 = ::runRadare2,
                onCreateSession = ::createAnalysisConversation,
            )
        }
    }
}

@Composable
private fun StorageAccessCard() {
    val context = LocalContext.current
    val toaster = LocalToaster.current

    fun isGranted(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    fun open() {
        if (isGranted()) {
            toaster.show("已拥有所有文件访问权限", type = ToastType.Success)
            return
        }
        try {
            val intent = if (Build.VERSION.SDK_INT >= 30) {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:" + context.packageName))
            } else {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    CardGroup(title = { Text("宿主目录写入授权") }) {
        item(
            leadingContent = { Icon(HugeIcons.Folder01, null) },
            headlineContent = { Text(if (isGranted()) "已授权所有文件访问" else "授权所有文件访问（一键）") },
            supportingContent = {
                Text("用于 AI 在工作区 /sdcard 直接读写宿主共享存储。点击自动跳转系统授权页。")
            },
            trailingContent = {
                Button(onClick = ::open) { Text("去授权") }
            },
        )
    }
}

@Composable
private fun FeatureToggles(
    enabledTools: List<LocalToolOption>,
    radare2Version: String,
    sessionCount: Int,
    onToggle: (LocalToolOption, Boolean) -> Unit,
    onCleanSessions: () -> Unit,
) {
    CardGroup(title = { Text("本地工具") }) {
        FeatureToggleRow(
            title = "逆向工具箱",
            subtitle = "离线编解码、哈希、JWT、指标提取、XOR 爆破、IPv4/IPv6 识别",
            checked = enabledTools.contains(LocalToolOption.ReverseToolkit),
            onToggle = { onToggle(LocalToolOption.ReverseToolkit, it) },
        )
        FeatureToggleRow(
            title = "radare2 (r2)",
            subtitle = "内置离线 radare2：文件信息、导入导出、字符串、反汇编、函数、搜索、差异｜$radare2Version",
            checked = enabledTools.contains(LocalToolOption.Radare2),
            onToggle = { onToggle(LocalToolOption.Radare2, it) },
        )
        FeatureToggleRow(
            title = "交互式计划",
            subtitle = "多步任务计划 + 向用户提问，支持子步骤、依赖、负责人与进度",
            checked = enabledTools.contains(LocalToolOption.InteractivePlan),
            onToggle = { onToggle(LocalToolOption.InteractivePlan, it) },
        )
        FeatureToggleRow(
            title = "向用户提问",
            subtitle = "让模型在生成过程中向你提问并阻塞等待回答",
            checked = enabledTools.contains(LocalToolOption.AskUser),
            onToggle = { onToggle(LocalToolOption.AskUser, it) },
        )
        FeatureToggleRow(
            title = "JavaScript 引擎",
            subtitle = "在本地沙箱执行 JS 片段做计算与数据处理",
            checked = enabledTools.contains(LocalToolOption.JavascriptEngine),
            onToggle = { onToggle(LocalToolOption.JavascriptEngine, it) },
        )
    }

    CardGroup(title = { Text("设备与数据") }) {
        FeatureToggleRow(
            title = "剪贴板",
            subtitle = "读取/写入系统剪贴板",
            checked = enabledTools.contains(LocalToolOption.Clipboard),
            onToggle = { onToggle(LocalToolOption.Clipboard, it) },
        )
        FeatureToggleRow(
            title = "文本转语音 (TTS)",
            subtitle = "让模型朗读回复",
            checked = enabledTools.contains(LocalToolOption.Tts),
            onToggle = { onToggle(LocalToolOption.Tts, it) },
        )
        FeatureToggleRow(
            title = "时间信息",
            subtitle = "获取当前时间与时区",
            checked = enabledTools.contains(LocalToolOption.TimeInfo),
            onToggle = { onToggle(LocalToolOption.TimeInfo, it) },
        )
        FeatureToggleRow(
            title = "日历",
            subtitle = "查询与创建日历事件（需要日历权限）",
            checked = enabledTools.contains(LocalToolOption.Calendar),
            onToggle = { onToggle(LocalToolOption.Calendar, it) },
        )
        FeatureToggleRow(
            title = "屏幕使用时间",
            subtitle = "读取应用使用统计（需要使用情况访问权限）",
            checked = enabledTools.contains(LocalToolOption.ScreenTime),
            onToggle = { onToggle(LocalToolOption.ScreenTime, it) },
        )
        FeatureToggleRow(
            title = "图表展示",
            subtitle = "把数据渲染为图表",
            checked = enabledTools.contains(LocalToolOption.ChartDisplay),
            onToggle = { onToggle(LocalToolOption.ChartDisplay, it) },
        )
    }

    CardGroup(title = { Text("工作会话") }) {
        item(
            leadingContent = { Icon(HugeIcons.Bug01, null) },
            headlineContent = { Text("分析会话") },
            supportingContent = {
                Text("由 AI 调用并打开的分析会话（当前 $sessionCount 个）。结束后会被自动清理。")
            },
        )
        item(
            onClick = onCleanSessions,
            leadingContent = { Icon(HugeIcons.Folder01, null) },
            headlineContent = { Text("清理空工作会话") },
            supportingContent = { Text("移除所有没有消息的分析会话，保持整洁") },
        )
    }
}

private fun CardGroupScope.FeatureToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    item(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onToggle)
        },
    )
}

@Composable
private fun ToolWorkbench(
    apkPath: String,
    analyzing: Boolean,
    resultJson: String,
    onPick: () -> Unit,
    onClearResult: () -> Unit,
    onApkAction: (String, String?, String, Int) -> Unit,
    onRadare2: (List<String>, String) -> Unit,
    onCreateSession: () -> Unit,
) {
    CardGroup(title = { Text("目标文件") }) {
        item(
            leadingContent = { Icon(HugeIcons.Folder01, null) },
            headlineContent = { Text("文件") },
            supportingContent = { Text(if (apkPath.isBlank()) "尚未选择文件（APK / SO / DEX / ELF）" else apkPath) },
        )
        item(
            onClick = onPick,
            leadingContent = { Icon(HugeIcons.PackageOpen, null) },
            headlineContent = { Text(stringResource(R.string.setting_enhanced_choose_apk)) },
            supportingContent = { Text("支持 APK、SO、DEX、ELF 等二进制") },
        )
        item(
            onClick = onCreateSession,
            leadingContent = { Icon(HugeIcons.Bolt, null) },
            headlineContent = { Text(stringResource(R.string.setting_enhanced_create_session)) },
            supportingContent = { Text(stringResource(R.string.setting_enhanced_create_session_desc)) },
        )
    }

    CardGroup(title = { Text("radare2 (r2)") }) {
        item(
            onClick = { onRadare2(listOf("-I"), "rabin2") },
            leadingContent = { Icon(HugeIcons.Search01, null) },
            headlineContent = { Text("文件信息") },
            supportingContent = { Text("rabin2 -I：架构、类型、入口") },
        )
        item(
            onClick = { onRadare2(listOf("-i"), "rabin2") },
            leadingContent = { Icon(HugeIcons.Code, null) },
            headlineContent = { Text("导入符号") },
            supportingContent = { Text("rabin2 -i") },
        )
        item(
            onClick = { onRadare2(listOf("-E"), "rabin2") },
            leadingContent = { Icon(HugeIcons.Code, null) },
            headlineContent = { Text("导出符号") },
            supportingContent = { Text("rabin2 -E") },
        )
        item(
            onClick = { onRadare2(listOf("-q", "-z"), "rabin2") },
            leadingContent = { Icon(HugeIcons.Folder01, null) },
            headlineContent = { Text("静态字符串") },
            supportingContent = { Text("rabin2 -z") },
        )
        item(
            onClick = { onRadare2(listOf("-q", "-c", "-e", "scr.color=0", "pd 120 @ entry0"), "r2") },
            leadingContent = { Icon(HugeIcons.Cpu, null) },
            headlineContent = { Text("入口反汇编") },
            supportingContent = { Text("r2：从入口点反汇编 120 条（多线程分析）") },
        )
    }

    CardGroup(title = { Text("APK / DEX 工具") }) {
        item(
            onClick = { onApkAction("zip_info", null, "", 60) },
            leadingContent = { Icon(HugeIcons.Search01, null) },
            headlineContent = { Text(stringResource(R.string.setting_enhanced_apk_overview)) },
        )
        item(
            onClick = { onApkAction("manifest_meta", null, "", 60) },
            leadingContent = { Icon(HugeIcons.Bug01, null) },
            headlineContent = { Text(stringResource(R.string.setting_enhanced_manifest)) },
        )
        item(
            onClick = { onApkAction("disassemble_smali", null, "", 60) },
            leadingContent = { Icon(HugeIcons.Code, null) },
            headlineContent = { Text(stringResource(R.string.setting_enhanced_smali)) },
        )
        item(
            onClick = { onApkAction("decompile_java", null, "", 60) },
            leadingContent = { Icon(HugeIcons.Code, null) },
            headlineContent = { Text(stringResource(R.string.setting_enhanced_java)) },
        )
        item(
            onClick = { onApkAction("repack_apk", null, "", 60) },
            leadingContent = { Icon(HugeIcons.Cpu, null) },
            headlineContent = { Text(stringResource(R.string.setting_enhanced_repack_apk)) },
        )
    }

    if (analyzing) {
        Text(
            text = "分析中…（反汇编/反编译已启用多线程）",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(8.dp),
        )
    }

    if (resultJson.isNotBlank()) {
        OutlinedButton(onClick = onClearResult, modifier = Modifier.fillMaxWidth()) {
            Text("清除结果")
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
