package com.flux.payload.dumper.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.flux.payload.dumper.R
import com.flux.payload.dumper.model.ArchiveInfo
import com.flux.payload.dumper.ui.theme.FluxRadius
import com.flux.payload.dumper.ui.theme.LocalFluxColors

/** Assignment-style independent cards. Values always describe the opened archive, not this device. */
@Composable
fun FirmwareInfoCard(info: ArchiveInfo) {
    val unknown = stringResource(R.string.metadata_unknown)
    val kernelLabel = stringResource(R.string.summary_kernel_version)
    val rows = listOf(
        Triple(stringResource(R.string.summary_file_name), info.fileName, 2),
        Triple(stringResource(R.string.summary_file_size), "${formatSize(info.fileSize)} (${info.fileSize} B)", 2),
        Triple(stringResource(R.string.summary_android_version), info.androidVersion, 2),
        Triple(stringResource(R.string.summary_security_patch), info.securityPatchLevel, 2),
        Triple(kernelLabel, info.kernelVersion, 3),
        Triple(stringResource(R.string.summary_fingerprint), info.fingerprint, 2),
        Triple(stringResource(R.string.summary_partitions), info.partitionCount.toString(), 2),
        Triple(stringResource(R.string.summary_block_size), "${info.blockSize} B", 2),
    )
    var detail by remember(info) { mutableStateOf<Pair<String, String>?>(null) }
    val flux = LocalFluxColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { (label, rawValue, maxLines) ->
            val known = rawValue.isNotBlank() && rawValue != "—"
            CardGroup(
                modifier = Modifier.clickable(enabled = known, role = Role.Button) { detail = label to rawValue },
            ) {
                InfoRow(label, if (known) rawValue else unknown, valueMaxLines = maxLines)
            }
        }
        Text(
            stringResource(R.string.metadata_source_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = flux.labelSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    detail?.let { (label, value) ->
        val clipboard = LocalClipboardManager.current
        Dialog(onDismissRequest = { detail = null }) {
            Surface(shape = RoundedCornerShape(FluxRadius.Dialog), color = flux.card) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Text(label, style = MaterialTheme.typography.titleLarge, color = flux.labelPrimary)
                    Spacer(Modifier.height(16.dp))
                    SelectionContainer {
                        Text(
                            value,
                            style = MaterialTheme.typography.bodyLarge,
                            color = flux.labelSecondary,
                            modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    AccentButton(
                        text = stringResource(R.string.copy_and_close),
                        onClick = { clipboard.setText(AnnotatedString(value)); detail = null },
                    )
                }
            }
        }
    }
}
