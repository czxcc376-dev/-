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
 * 「分块交付」卡片：
 * - 开关：交付物以分块数据格式承载
 * - 一键还原：把剪贴板里的内容还原为明文，并覆盖回剪贴板
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
            toaster.show("未发现分块数据（[[DATA:n]]…[[/DATA]]）", type = ToastType.Warning)
            return
        }
        cm?.setPrimaryClip(ClipData.newPlainText("clbi-decoded", decoded))
        toaster.show("已还原 $blocks 个块，共 ${decoded.length} 字符，已覆盖剪贴板", type = ToastType.Success)
    }

    CardGroup(title = { Text("分块交付") }) {
        item(
            headlineContent = { Text("分块数据格式") },
            supportingContent = {
                Text("开启后，交付物以分块数据格式承载（[[DATA:n]]…[[/DATA]]），便于跨端复制与避免转义丢失；用下方按钮还原为明文。")
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
            headlineContent = { Text("还原剪贴板产物") },
            supportingContent = {
                Text("读取剪贴板中的内容，还原全部分块为明文代码，并覆盖回剪贴板，直接粘贴即可使用。")
            },
        )
    }
}
