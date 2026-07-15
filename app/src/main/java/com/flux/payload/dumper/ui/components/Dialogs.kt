package com.flux.payload.dumper.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flux.payload.dumper.BuildConfig
import com.flux.payload.dumper.core.Net
import com.flux.payload.dumper.data.Preferences

private const val REPO_URL = "https://github.com/kexi1412/PayloadDumper-Flux"
private const val AUTHOR = "kexi1412"

/** Base ColorOS-style centered dialog surface. */
@Composable
private fun FluxDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(24.dp), content = content)
        }
    }
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    var uaEnabled by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_CUSTOM_UA_ENABLED, false)) }
    var ua by remember { mutableStateOf(Preferences.getString(Preferences.KEY_CUSTOM_UA) ?: Net.DEFAULT_USER_AGENT) }
    var verify by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_VERIFY_ENABLED, true)) }

    fun save() {
        Preferences.setBoolean(Preferences.KEY_CUSTOM_UA_ENABLED, uaEnabled)
        Preferences.setString(Preferences.KEY_CUSTOM_UA, ua)
        Preferences.setBoolean(Preferences.KEY_VERIFY_ENABLED, verify)
    }

    FluxDialog(onDismiss = { save(); onDismiss() }) {
        Text("下载与提取设置", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(16.dp))

        ToggleRow("提取后 SHA-256 校验", verify) { verify = it }
        Spacer(Modifier.height(8.dp))
        ToggleRow("自定义 User-Agent", uaEnabled) { uaEnabled = it }

        if (uaEnabled) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = ua, onValueChange = { ua = it },
                label = { Text("User-Agent") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(20.dp))
        GradientPillButton(text = "完成", onClick = { save(); onDismiss() })
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    FluxDialog(onDismiss = onDismiss) {
        Text("OTA Flux", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            "版本 ${BuildConfig.VERSION_NAME} · 作者 $AUTHOR",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "从 Android A/B (payload.bin) 全量 OTA 中并行提取分区镜像。\n" +
                "支持本地文件与 OTA 直链，多分区并发提取，网络断点续传，提取后 SHA-256 校验。\n" +
                "界面为自研 ColorOS 16「Flux」设计。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        LinkRow(
            title = "项目主页",
            subtitle = "github.com/$AUTHOR/PayloadDumper-Flux",
            onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                }
            },
        )
        Spacer(Modifier.height(16.dp))
        Text("致谢", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            "· xyiguanle — payload 分析源码\n" +
                "· rcmiku/Payload-Dumper-Compose — 前身项目\n" +
                "· payload-dumper-c — 引擎参考",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        GradientPillButton(text = "好的", onClick = onDismiss)
    }
}

/** A tappable row that opens an external link in the browser. */
@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.Rounded.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Forced dialog shown when a network extraction stalls (expired/broken OTA link). The user pastes
 * a fresh link; the ViewModel re-verifies it is the same ROM (SHA-256) before resuming, so a wrong
 * link can't silently corrupt the half-written image.
 */
@Composable
fun RelinkDialog(
    partitionName: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    Dialog(
        onDismissRequest = { /* forced — cannot dismiss by tapping outside */ },
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
    ) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("链接已失效", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(10.dp))
                Text(
                    "分区「$partitionName」下载中断，进度已保留。请粘贴一个新的 OTA 直链继续续传。\n" +
                        "提交后会先用 SHA-256 校验它是否为同一个 ROM，一致才会接着下，避免不同链接混包。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("新的 OTA 直链 URL") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                GradientPillButton(text = "校验并续传", onClick = { onSubmit(url) }, enabled = url.isNotBlank())
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Text(
                        "取消",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp).clickable { onCancel() },
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    FluxDialog(onDismiss = onDismiss) {
        Text("需要文件访问权限", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(12.dp))
        Text(
            "提取分区镜像需要「所有文件访问权限」，用于把 .img 写入下载目录。请在接下来的系统设置中授予。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        GradientPillButton(text = "去授权", onClick = onConfirm)
    }
}
