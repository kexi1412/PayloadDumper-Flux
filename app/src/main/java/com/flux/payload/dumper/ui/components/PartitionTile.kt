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
 * COUI 17 分区列表行——精确模仿 coui_preference.xml 的布局结构。
 *
 * 从 AXML 提取的真实参数：
 *   paddingStart = 32dp, paddingEnd = 32dp
 *   minHeight = 48dp（但实际因为有 icon+两行文字所以更高）
 *   图标容器 FrameLayout 在左侧（couiType=1 圆角方形）
 *   文字区在中间（weight=1），标题在上摘要下
 *   操作区在右侧
 *
 * 行间分割线从 72dp 开始（= paddingStart 32dp + icon 40dp），与图标右边缘对齐。
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
            .heightIn(min = 56.dp)
            .padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LeadingIconBox(info, progress, ringColor)

        // 文字区：标题 + 摘要，weight=1
        Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
            Text(
                // Short name. The row's key may be archive-qualified (a Honor package nests
                // three archives that reuse basenames), and that key would be ellipsised away.
                info.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                color = flux.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitleFor(info),
                style = MaterialTheme.typography.bodyMedium,
                color = if (errorish) flux.danger else flux.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        if (!running) {
            val label = when (info.extractState) {
                ExtractState.DONE -> stringResource(R.string.action_reextract)
                ExtractState.PAUSED, ExtractState.ERROR -> stringResource(R.string.action_resume)
                else -> stringResource(R.string.action_extract)
            }
            Spacer(Modifier.width(12.dp))
            TonalButton(text = label, onClick = onExtract)
        }
    }
}

/**
 * COUI 17 图标容器——进度环保留在着色圆角方块内。
 * 方块 40dp，圆角 10dp，内部进度环 34dp + 中心图标 18dp。
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
        // Idle also names the nested archive this entry came from, so two rows that share a
        // basename (`preload.img`, `version.img`) are distinguishable before extracting.
        else -> {
            val idle = stringResource(R.string.tile_idle, formatSize(info.size), formatSize(info.compressedSize))
            val archive = info.subtitle.substringBefore(" :: ", "").trim()
            if (archive.isNotEmpty() && archive != info.displayTitle) "$archive · $idle" else idle
        }
    }
}
