package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.BreakerKeyword
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

/**
 * 「关键词替换」卡片：
 * - 开关：只改写助手侧消息（正文跳过代码围栏，推理内容整体替换），不触碰用户输入
 * - 规则编辑：每行一条 `原词=>替换词`，留空则使用内置默认表
 */
@Composable
fun BreakerKeywordCard(
    settingsStore: SettingsStore = koinInject(),
) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle(
        initialValue = settingsStore.settingsFlow.value,
    )

    var draft by remember(settings.breakerKeywordRules) {
        mutableStateOf(settings.breakerKeywordRules)
    }

    val effectiveCount = BreakerKeyword.ruleCount(settings.breakerKeywordRules)

    CardGroup(title = { Text("关键词替换") }) {
        item(
            headlineContent = { Text("关键词替换") },
            supportingContent = {
                Text("只改写助手发出的消息（正文与推理内容），不修改你的输入；正文中的代码块保持原样。当前生效 $effectiveCount 条规则。")
            },
            trailingContent = {
                Switch(
                    checked = settings.breakerKeywordReplace,
                    onCheckedChange = { checked ->
                        scope.launch {
                            settingsStore.update { it.copy(breakerKeywordReplace = checked) }
                        }
                    },
                )
            },
        )
        item(
            headlineContent = { Text("替换规则") },
            supportingContent = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    label = { Text("每行 原词=>替换词，留空用内置表") },
                    minLines = 3,
                    maxLines = 8,
                )
            },
            trailingContent = {
                TextButton(
                    onClick = {
                        val count = BreakerKeyword.ruleCount(draft)
                        scope.launch {
                            settingsStore.update { it.copy(breakerKeywordRules = draft) }
                        }
                        toaster.show("已保存，生效 $count 条", type = ToastType.Success)
                    },
                ) { Text("保存") }
            },
        )
        item(
            onClick = {
                draft = ""
                scope.launch {
                    settingsStore.update { it.copy(breakerKeywordRules = "") }
                }
                toaster.show("已恢复内置默认规则", type = ToastType.Success)
            },
            headlineContent = { Text("恢复内置默认表") },
            supportingContent = { Text("清空自定义规则，使用内置默认替换表") },
        )
    }
}
