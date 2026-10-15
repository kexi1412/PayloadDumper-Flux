package com.flux.payload.dumper.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flux.payload.dumper.R
import com.flux.payload.dumper.model.ExtractState
import com.flux.payload.dumper.model.PartitionInfo
import com.flux.payload.dumper.model.VerifyState
import com.flux.payload.dumper.ui.theme.FluxRadius
import com.flux.payload.dumper.ui.theme.LocalFluxColors

/**
 * COUI 17 分区列表行——设计为 COUI preference item，放在 [CardGroup] 里，
 * 行间用 [RowDivider] 分隔。
 *
 * COUI 17 的显著变化是引导图标不再是裸露的圆环，而是包裹在
 * COUIRoundImageView 风格的着色圆角方块里。进度环保留在方块上方，
 * 提取完成后变成对勾。
 */
@Composable
fun PartitionRow(info: PartitionInfo, onExtract: () -> Unit) {
    val flux = LocalFluxColors.current
    val running = info.extractState == ExtractState.RUNNING
    val progress by animateFloatAsState(info.progress, label = "rowProgress")

    val ringColor = when {
        info.verifyState == VerifyState.PASSED -> flux.success
        info.verifyState == VerifyState.FAILED -> flux.danger
        info.extractState == ExtractState.ERROR -> flux.danger
        else -> flux.accent
    }
    val errorish = info.extractState == ExtractState.ERROR || info.verifyState == VerifyState.FAILED

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .padding(start = 24.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LeadingIconBox(info, progress, ringColor)

        Column(modifier = Modifier.padding(horizontal = 14.dp).weight(1f)) {
            Text(
                info.partitionName,
                style = MaterialTheme.typography.titleMedium,
                color = flux.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitleFor(info),
                style = MaterialTheme.typography.bodyMedium,
                color = if (errorish) flux.danger else flux.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (!running) {
            val label = when (info.extractState) {
                ExtractState.DONE -> stringResource(R.string.action_reextract)
                ExtractState.PAUSED, ExtractState.ERROR -> stringResource(R.string.action_resume)
                else -> stringResource(R.string.action_extract)
            }
            TonalButton(text = label, onClick = onExtract)
            Spacer(Modifier.width(4.dp))
        }
    }
}

/**
 * COUI 17 图标容器——进度环包裹在一个着色圆角方块内（COUIRoundImageView 风格）。
 * 方块尺寸 40dp，内嵌进度环和中心图标。
 */
@Composable
private fun LeadingIconBox(info: PartitionInfo, progress: Float, ringColor: Color) {
    val flux = LocalFluxColors.current
    val running = info.extractState == ExtractState.RUNNING
    val done = info.extractState == ExtractState.DONE
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(FluxRadius.IconBox))
            .background(flux.iconBoxBg),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { if (running || done || info.extractState == ExtractState.PAUSED) progress else 0f },
            modifier = Modifier.size(34.dp),
            strokeWidth = 2.5.dp,
            color = ringColor,
            trackColor = Color.Transparent,
            gapSize = 0.dp,
        )
        val centerIcon = when {
            info.verifyState == VerifyState.PASSED -> Icons.Rounded.Check
            info.verifyState == VerifyState.FAILED || info.extractState == ExtractState.ERROR -> Icons.Rounded.PriorityHigh
            else -> Icons.Rounded.Dns
        }
        Icon(centerIcon, contentDescription = null, tint = ringColor, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun subtitleFor(info: PartitionInfo): String {
    val percent = (info.progress * 100).toInt()
    return when (info.extractState) {
        ExtractState.RUNNING -> stringResource(R.string.tile_extracting, formatSize(info.size), percent)
        ExtractState.PAUSED -> stringResource(R.string.tile_paused, percent, info.message.ifBlank { stringResource(R.string.state_paused) })
        ExtractState.DONE -> when (info.verifyState) {
            VerifyState.PASSED -> stringResource(R.string.tile_verified, formatSize(info.size))
            VerifyState.FAILED -> stringResource(R.string.tile_verify_failed, formatSize(info.size))
            else -> stringResource(R.string.tile_extracted, formatSize(info.size))
        }
        ExtractState.ERROR -> info.message.ifBlank { stringResource(R.string.state_extract_failed) }
        else -> stringResource(R.string.tile_idle, formatSize(info.size), formatSize(info.compressedSize))
    }
}
