package com.flux.payload.dumper.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flux.payload.dumper.model.ExtractState
import com.flux.payload.dumper.model.PartitionInfo
import com.flux.payload.dumper.model.VerifyState
import com.flux.payload.dumper.ui.theme.FluxRadius
import com.flux.payload.dumper.ui.theme.LocalFluxColors

/**
 * A standalone partition tile. Its signature element is the **progress ring** wrapping the leading
 * glyph — extraction fills the ring, and on success it turns into a check. This is the main visual
 * departure from the previous linear-bar layout.
 */
@Composable
fun PartitionTile(info: PartitionInfo, onExtract: () -> Unit) {
    val flux = LocalFluxColors.current
    val running = info.extractState == ExtractState.RUNNING
    val progress by animateFloatAsState(info.progress, label = "tileProgress")

    val ringColor = when {
        info.verifyState == VerifyState.PASSED -> flux.success
        info.verifyState == VerifyState.FAILED -> MaterialTheme.colorScheme.error
        info.extractState == ExtractState.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        shape = RoundedCornerShape(FluxRadius.Inner),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LeadingRing(info, progress, ringColor)

            Column(modifier = Modifier.padding(horizontal = 14.dp).weight(1f)) {
                Text(
                    info.partitionName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitleFor(info),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (info.extractState == ExtractState.ERROR || info.verifyState == VerifyState.FAILED)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!running) {
                val label = when (info.extractState) {
                    ExtractState.DONE -> "重新提取"
                    ExtractState.PAUSED, ExtractState.ERROR -> "续传"
                    else -> "提取"
                }
                TonalPill(text = label, onClick = onExtract)
            }
        }
    }
}

@Composable
private fun LeadingRing(info: PartitionInfo, progress: Float, ringColor: Color) {
    val running = info.extractState == ExtractState.RUNNING
    val done = info.extractState == ExtractState.DONE
    Box(modifier = Modifier.size(46.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { if (running || done || info.extractState == ExtractState.PAUSED) progress else 0f },
            modifier = Modifier.size(46.dp),
            strokeWidth = 3.dp,
            color = ringColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            gapSize = 0.dp,
        )
        val centerIcon = when {
            info.verifyState == VerifyState.PASSED -> Icons.Rounded.Check
            info.verifyState == VerifyState.FAILED || info.extractState == ExtractState.ERROR -> Icons.Rounded.PriorityHigh
            else -> Icons.Rounded.Dns
        }
        Icon(centerIcon, contentDescription = null, tint = ringColor, modifier = Modifier.size(20.dp))
    }
}

private fun subtitleFor(info: PartitionInfo): String = when (info.extractState) {
    ExtractState.RUNNING -> "${formatSize(info.size)} · 提取中 ${(info.progress * 100).toInt()}%"
    ExtractState.PAUSED -> "已下 ${(info.progress * 100).toInt()}% · ${info.message.ifBlank { "已暂停" }}"
    ExtractState.DONE -> when (info.verifyState) {
        VerifyState.PASSED -> "${formatSize(info.size)} · 校验通过"
        VerifyState.FAILED -> "${formatSize(info.size)} · 校验失败"
        else -> "${formatSize(info.size)} · 已提取"
    }
    ExtractState.ERROR -> info.message.ifBlank { "提取失败" }
    else -> "${formatSize(info.size)} · 压缩 ${formatSize(info.compressedSize)}"
}
