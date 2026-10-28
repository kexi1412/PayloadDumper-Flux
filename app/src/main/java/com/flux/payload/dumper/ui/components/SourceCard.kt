package com.flux.payload.dumper.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flux.payload.dumper.R
import com.flux.payload.dumper.model.SourceMode
import com.flux.payload.dumper.model.SourceSelection
import com.flux.payload.dumper.ui.theme.FluxRadius
import com.flux.payload.dumper.ui.theme.LocalFluxColors

/** The local branch deliberately contains no text editor, even a read-only one. */
@Composable
fun SourceCard(
    selection: SourceSelection,
    onModeChange: (SourceMode) -> Unit,
    onUrlChange: (String) -> Unit,
    onBrowse: () -> Unit,
    locked: Boolean,
    parsing: Boolean,
    onParse: () -> Unit,
) {
    val flux = LocalFluxColors.current
    val focus = LocalFocusManager.current
    CardGroup {
        Column(Modifier.padding(24.dp)) {
            SegmentedToggle(
                options = listOf(stringResource(R.string.source_local), stringResource(R.string.source_network)),
                selectedIndex = selection.mode.ordinal,
                enabled = !locked,
                onSelect = {
                    focus.clearFocus()
                    onModeChange(SourceMode.entries[it])
                },
            )
            Spacer(Modifier.height(16.dp))
            if (selection.mode == SourceMode.LOCAL) {
                val document = selection.local
                Row(
                    modifier = Modifier
                        .testTag("local_file_picker")
                        .fillMaxWidth()
                        .heightIn(min = 104.dp)
                        .clickable(enabled = !locked, role = Role.Button, onClick = onBrowse)
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBox(
                        icon = if (document == null) Icons.Rounded.FolderOpen else Icons.Rounded.Description,
                        contentDescription = null,
                        size = 48.dp,
                        iconSize = 26.dp,
                    )
                    Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
                        Text(
                            text = document?.displayName?.ifBlank { stringResource(R.string.selected_document) }
                                ?: stringResource(R.string.select_file_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = flux.labelPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (document == null) stringResource(R.string.select_file_hint)
                                else document.size?.let(::formatSize) ?: stringResource(R.string.file_size_pending),
                            style = MaterialTheme.typography.bodyMedium,
                            color = flux.labelSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (document != null) {
                            Text(
                                stringResource(R.string.change_file),
                                color = flux.accent,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = flux.labelSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                OutlinedTextField(
                    value = selection.networkUrl,
                    onValueChange = onUrlChange,
                    enabled = !locked,
                    placeholder = { Text(stringResource(R.string.input_hint_network)) },
                    leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null, tint = flux.accent) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    shape = RoundedCornerShape(FluxRadius.Field),
                    modifier = Modifier.fillMaxWidth().testTag("network_url_input"),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = flux.fieldBg,
                        unfocusedContainerColor = flux.fieldBg,
                        focusedIndicatorColor = flux.accent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )
                Spacer(Modifier.height(16.dp))
            }
            if (locked) {
                Text(
                    stringResource(R.string.source_locked),
                    style = MaterialTheme.typography.bodyMedium,
                    color = flux.labelSecondary,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            AccentButton(
                text = stringResource(if (parsing) R.string.action_parsing else R.string.action_parse),
                enabled = !locked && selection.canParse,
                modifier = Modifier.testTag("parse_source"),
                onClick = { focus.clearFocus(); onParse() },
            )
        }
    }
}
