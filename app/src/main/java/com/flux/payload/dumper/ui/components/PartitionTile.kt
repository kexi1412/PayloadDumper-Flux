package com.flux.payload.dumper.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flux.payload.dumper.R
import com.flux.payload.dumper.model.ExtractState
import com.flux.payload.dumper.model.PartitionInfo
import com.flux.payload.dumper.model.VerifyState
import com.flux.payload.dumper.ui.theme.LocalFluxColors

/**
 * A single partition list-row, laid out like a ColorOS preference item and meant to sit inside a
 * [CardGroup] with [RowDivider]s between siblings. The leading glyph is wrapped by a thin progress
 * ring that fills during extraction and turns into a check on success — the one functional flourish
 * kept from the previous design.
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
            .heightIn(min = 64.dp)
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LeadingRing(info, progress, ringColor)

        Column(modifier = Modifier.padding(horizontal = 14.dp).weight(1f)) {
            Text(
                info.partitionName,
                style = MaterialTheme.typography.titleMedium,
                color = flux.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitleFor(info),
                style = MaterialTheme.typography.labelMedium,
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

@Composable
private fun LeadingRing(info: PartitionInfo, progress: Float, ringColor: Color) {
    val flux = LocalFluxColors.current
    val running = info.extractState == ExtractState.RUNNING
    val done = info.extractState == ExtractState.DONE
    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { if (running || done || info.extractState == ExtractState.PAUSED) progress else 0f },
            modifier = Modifier.size(40.dp),
            strokeWidth = 2.5.dp,
            color = ringColor,
            trackColor = flux.fieldBg,
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
