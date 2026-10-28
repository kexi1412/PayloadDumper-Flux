package com.flux.payload.dumper.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flux.payload.dumper.BuildConfig
import com.flux.payload.dumper.R
import com.flux.payload.dumper.core.Net
import com.flux.payload.dumper.data.Preferences
import com.flux.payload.dumper.ui.theme.FluxRadius
import com.flux.payload.dumper.ui.theme.LocalFluxColors

private const val REPO_URL = "https://github.com/kexi1412/PayloadDumper-Flux"
private const val UPDATER_URL = "https://github.com/YuKongA/Updater-KMP"
private const val AUTHOR = "kexi1412"

/**
 * Bounded dialog with a scrollable body and persistent actions. The 24dp corner,
 * 392dp maximum width and 632dp height cap come from COUI resource tokens;
 * insets and flexible measurement are Compose accessibility adaptations.
 */
@Composable
private fun FluxDialog(
    onDismiss: () -> Unit,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = properties) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val availableHeight = maxHeight.coerceAtMost(632.dp)
            Surface(
                shape = RoundedCornerShape(FluxRadius.Dialog),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                modifier = Modifier.widthIn(max = 392.dp).fillMaxWidth()
                    .heightIn(max = availableHeight),
            ) {
                if (availableHeight < 280.dp) {
                    // IME/landscape can leave less room than the action area.
                    // In that case every item remains reachable by scrolling.
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                        content()
                        Spacer(Modifier.height(20.dp))
                        actions()
                    }
                } else {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Column(
                            modifier = Modifier.weight(1f, fill = false)
                                .verticalScroll(rememberScrollState()),
                            content = content,
                        )
                        Spacer(Modifier.height(20.dp))
                        actions()
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
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

    FluxDialog(
        onDismiss = { save(); onDismiss() },
        actions = {
            AccentButton(text = stringResource(R.string.action_done), onClick = { save(); onDismiss() })
        },
    ) {
        DialogHeading(stringResource(R.string.settings_title))
        Spacer(Modifier.height(16.dp))
        ToggleRow(stringResource(R.string.setting_verify), verify) { verify = it }
        Spacer(Modifier.height(8.dp))
        ToggleRow(stringResource(R.string.setting_custom_ua), uaEnabled) { uaEnabled = it }
        if (uaEnabled) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = ua,
                onValueChange = { ua = it },
                label = { Text(stringResource(R.string.label_user_agent)) },
                singleLine = true,
                shape = RoundedCornerShape(FluxRadius.Field),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    fun openLink(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
    FluxDialog(
        onDismiss = onDismiss,
        actions = { AccentButton(text = stringResource(R.string.action_ok), onClick = onDismiss) },
    ) {
        DialogHeading(stringResource(R.string.app_name))
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.about_version_author, BuildConfig.VERSION_NAME, AUTHOR),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.about_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        LinkRow(
            title = stringResource(R.string.about_link_title),
            subtitle = "github.com/$AUTHOR/PayloadDumper-Flux",
            onClick = { openLink(REPO_URL) },
        )
        Spacer(Modifier.height(20.dp))
        DialogHeading(stringResource(R.string.about_whats_new_title))
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.about_whats_new_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        DialogHeading(stringResource(R.string.credits_title))
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.credits_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        LinkRow(
            title = stringResource(R.string.credits_updater_title),
            subtitle = "github.com/YuKongA/Updater-KMP",
            onClick = { openLink(UPDATER_URL) },
        )
    }
}

/** External-link row, with one accessible action and text allowed to wrap. */
@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clip(RoundedCornerShape(FluxRadius.Inner))
            .background(LocalFluxColors.current.accentContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.width(10.dp))
        Icon(Icons.Rounded.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
}

/** The existing ROM-identity verification stays in the ViewModel, not in UI. */
@Composable
fun RelinkDialog(
    partitionName: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    busy: Boolean = false,
) {
    var url by remember { mutableStateOf("") }
    FluxDialog(
        onDismiss = { /* The explicit cancel action is required. */ },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = false,
            usePlatformDefaultWidth = false,
        ),
        actions = {
            AccentButton(text = stringResource(R.string.relink_confirm), onClick = { onSubmit(url) }, enabled = !busy && url.isNotBlank())
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
            }
        },
    ) {
        DialogHeading(stringResource(R.string.relink_title))
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.relink_body, partitionName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.relink_input_label)) },
            singleLine = true,
            shape = RoundedCornerShape(FluxRadius.Field),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun PermissionDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    FluxDialog(
        onDismiss = onDismiss,
        actions = { AccentButton(text = stringResource(R.string.permission_confirm), onClick = onConfirm) },
    ) {
        DialogHeading(stringResource(R.string.permission_title))
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.permission_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
