package com.flux.payload.dumper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.flux.payload.dumper.ui.theme.LocalFluxColors

/** The two-entry source picker card: 本地文件 / 网络链接. */
@Composable
fun SourceSelectorCard(
    onPickLocal: () -> Unit,
    onPickUrl: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val flux = LocalFluxColors.current
    FluxCard(modifier = modifier, padding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp, horizontal = 12.dp)) {
        SourceEntry(
            icon = Icons.Rounded.FolderOpen,
            title = "本地文件",
            subtitle = "选择 payload.bin 或 OTA 压缩包",
            iconTint = flux.iconTintLocal,
            iconBg = flux.iconBgLocal,
            onClick = onPickLocal,
        )
        Spacer(Modifier.height(2.dp))
        SourceEntry(
            icon = Icons.Rounded.Link,
            title = "网络链接",
            subtitle = "从 OTA 下载直链流式解析",
            iconTint = flux.iconTintUrl,
            iconBg = flux.iconBgUrl,
            onClick = onPickUrl,
        )
    }
}

@Composable
private fun SourceEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconTint: Color,
    iconBg: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = title, tint = iconTint, modifier = Modifier.size(24.dp))
        }
        Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}
