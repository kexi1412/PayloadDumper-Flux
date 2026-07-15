package com.flux.payload.dumper.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flux.payload.dumper.model.ExtractState
import com.flux.payload.dumper.model.PartitionInfo
import com.flux.payload.dumper.model.VerifyState
import com.flux.payload.dumper.ui.theme.LocalFluxColors

@Composable
fun PartitionRow(
    info: PartitionInfo,
    onExtract: () -> Unit,
) {
    val running = info.extractState == ExtractState.RUNNING
    val progress by animateFloatAsState(info.progress, label = "progress")

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }

        Column(modifier = Modifier.padding(horizontal = 14.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    info.partitionName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                VerifyBadge(info.verifyState)
            }
            Text(
                text = subtitleFor(info),
                style = MaterialTheme.typography.labelMedium,
                color = if (info.extractState == ExtractState.ERROR) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnimatedVisibility(
                visible = running || info.extractState == ExtractState.DONE || info.extractState == ExtractState.PAUSED
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = if (info.verifyState == VerifyState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    drawStopIndicator = {},
                )
            }
        }

        Spacer(Modifier.width(6.dp))
        if (running) {
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            val label = when (info.extractState) {
                ExtractState.DONE -> "重新提取"
                ExtractState.PAUSED, ExtractState.ERROR -> "续传"
                else -> "提取"
            }
            TonalPill(text = label, onClick = onExtract)
        }
    }
}

@Composable
private fun VerifyBadge(state: VerifyState) {
    val flux = LocalFluxColors.current
    when (state) {
        VerifyState.PASSED -> Icon(
            Icons.Rounded.CheckCircle, contentDescription = "校验通过",
            tint = flux.success, modifier = Modifier.padding(start = 6.dp).size(18.dp),
        )
        VerifyState.FAILED -> Icon(
            Icons.Rounded.ErrorOutline, contentDescription = "校验失败",
            tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 6.dp).size(18.dp),
        )
        else -> {}
    }
}

private fun subtitleFor(info: PartitionInfo): String = when (info.extractState) {
    ExtractState.RUNNING -> "${formatSize(info.size)} · 提取中 ${(info.progress * 100).toInt()}%"
    ExtractState.PAUSED -> "已下 ${(info.progress * 100).toInt()}% · ${info.message.ifBlank { "已暂停" }}"
    ExtractState.DONE -> when (info.verifyState) {
        VerifyState.PASSED -> "${formatSize(info.size)} · 已提取 · 校验通过"
        VerifyState.FAILED -> "${formatSize(info.size)} · 已提取 · 校验失败"
        else -> "${formatSize(info.size)} · 已提取"
    }
    ExtractState.ERROR -> info.message.ifBlank { "提取失败" }
    else -> "${formatSize(info.size)} · 压缩 ${formatSize(info.compressedSize)}"
}
