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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flux.payload.dumper.BuildConfig
import com.flux.payload.dumper.R
import com.flux.payload.dumper.core.Net
import com.flux.payload.dumper.data.Preferences
import com.flux.payload.dumper.ui.theme.FluxRadius

private const val REPO_URL = "https://github.com/kexi1412/PayloadDumper-Flux"
private const val AUTHOR = "kexi1412"

/** Base ColorOS-style centered dialog surface. */
@Composable
private fun FluxDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(FluxRadius.Dialog),
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
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(16.dp))

        ToggleRow(stringResource(R.string.setting_verify), verify) { verify = it }
        Spacer(Modifier.height(8.dp))
        ToggleRow(stringResource(R.string.setting_custom_ua), uaEnabled) { uaEnabled = it }

        if (uaEnabled) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = ua, onValueChange = { ua = it },
                label = { Text(stringResource(R.string.label_user_agent)) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(20.dp))
        AccentButton(text = stringResource(R.string.action_done), onClick = { save(); onDismiss() })
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
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.about_version_author, BuildConfig.VERSION_NAME, AUTHOR),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.about_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        LinkRow(
            title = stringResource(R.string.about_link_title),
            subtitle = "github.com/$AUTHOR/PayloadDumper-Flux",
            onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                }
            },
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.credits_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.credits_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        AccentButton(text = stringResource(R.string.action_ok), onClick = onDismiss)
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
        Surface(shape = RoundedCornerShape(FluxRadius.Dialog), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(stringResource(R.string.relink_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.relink_body, partitionName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text(stringResource(R.string.relink_input_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                AccentButton(text = stringResource(R.string.relink_confirm), onClick = { onSubmit(url) }, enabled = url.isNotBlank())
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Text(
                        stringResource(R.string.action_cancel),
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
        Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.permission_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        AccentButton(text = stringResource(R.string.permission_confirm), onClick = onConfirm)
    }
}
