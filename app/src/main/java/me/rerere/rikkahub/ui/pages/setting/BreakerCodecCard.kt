package me.rerere.rikkahub.ui.pages.setting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.BreakerCodec
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

/**
 * 「产物编码」卡片：
 * - 开关：开启后模型按分段 Base64 交付产物（规避出口文本审核）
 * - 一键解码：把剪贴板里的模型输出还原为明文，并覆盖回剪贴板
 */
@Composable
fun BreakerCodecCard(
    settingsStore: SettingsStore = koinInject(),
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle(
        initialValue = settingsStore.settingsFlow.value,
    )

    fun clipboard(): ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    fun decodeFromClipboard() {
        val cm = clipboard()
        val raw = cm?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (raw.isBlank()) {
            toaster.show("剪贴板为空", type = ToastType.Warning)
            return
        }
        val blocks = BreakerCodec.countBlocks(raw)
        val decoded = BreakerCodec.decodeAll(raw)
        if (decoded.isBlank()) {
            toaster.show("未发现 CLBI-B64 编码块，且非纯 Base64 文本", type = ToastType.Warning)
            return
        }
        cm?.setPrimaryClip(ClipData.newPlainText("clbi-decoded", decoded))
        val suffix = if (blocks > 0) "（还原 $blocks 个块）" else "（整段 Base64）"
        toaster.show("已解码 $suffix，${decoded.length} 字符，已覆盖剪贴板", type = ToastType.Success)
    }

    CardGroup(title = { Text("产物编码") }) {
        item(
            headlineContent = { Text("产物编码（分段 Base64）") },
            supportingContent = {
                Text("开启后，模型交付的代码/命令按分段 Base64 输出，规避传输链路的文本审核；用下方「解码」还原明文。")
            },
            trailingContent = {
                Switch(
                    checked = settings.breakerEncodedOutput,
                    onCheckedChange = { checked ->
                        scope.launch {
                            settingsStore.update { it.copy(breakerEncodedOutput = checked) }
                        }
                    },
                )
            },
        )
        item(
            onClick = { decodeFromClipboard() },
            headlineContent = { Text("解码剪贴板产物") },
            supportingContent = {
                Text("读取剪贴板中的模型输出，还原 [[CLBI-B64:n]] 块为明文代码，并覆盖回剪贴板，直接粘贴即可使用。")
            },
        )
    }
}
