package com.flux.payload.dumper.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.flux.payload.dumper.R
import com.flux.payload.dumper.data.PathUtil
import com.flux.payload.dumper.data.Preferences
import com.flux.payload.dumper.model.ArchiveInfo
import com.flux.payload.dumper.ui.components.AboutDialog
import com.flux.payload.dumper.ui.components.FluxBackground
import com.flux.payload.dumper.ui.components.GlassCard
import com.flux.payload.dumper.ui.components.GradientFab
import com.flux.payload.dumper.ui.components.GradientPillButton
import com.flux.payload.dumper.ui.components.PartitionTile
import com.flux.payload.dumper.ui.components.PermissionDialog
import com.flux.payload.dumper.ui.components.RelinkDialog
import com.flux.payload.dumper.ui.components.SegmentedToggle
import com.flux.payload.dumper.ui.components.SettingsDialog
import com.flux.payload.dumper.ui.components.SummaryData
import com.flux.payload.dumper.ui.components.SummaryRow
import com.flux.payload.dumper.ui.components.formatSize
import com.flux.payload.dumper.ui.theme.FluxRadius
import com.flux.payload.dumper.ui.theme.LocalFluxColors
import com.flux.payload.dumper.viewmodel.DumperViewModel
import com.flux.payload.dumper.viewmodel.ParseState

@Composable
fun DumperScreen(vm: DumperViewModel) {
    val input by vm.input.collectAsState()
    val parseState by vm.parseState.collectAsState()
    val partitions by vm.partitions.collectAsState()
    val search by vm.search.collectAsState()
    val snackbar by vm.snackbar.collectAsState()
    val relink by vm.relink.collectAsState()

    val context = LocalContext.current
    var sourceMode by remember { mutableIntStateOf(if (input.startsWith("http")) 1 else 0) }
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showPermission by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbar) {
        snackbar?.let { snackbarHostState.showSnackbar(it); vm.consumeSnackbar() }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { PathUtil.realPathFromUri(context, it)?.let(vm::updateInput) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { PathUtil.realPathFromUri(context, it)?.let { p -> Preferences.setString(Preferences.KEY_OUTPUT_FOLDER, p) } }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (PathUtil.isAllFilesAccessGranted()) pendingAction?.invoke()
        pendingAction = null
    }

    fun requireStorage(action: () -> Unit) {
        if (PathUtil.isAllFilesAccessGranted()) action() else { pendingAction = action; showPermission = true }
    }
    fun launchPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:" + context.packageName))
        runCatching { permissionLauncher.launch(intent) }
            .onFailure { permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
    fun doParse() {
        if (input.trim().startsWith("http")) vm.parse() else requireStorage { vm.parse() }
    }

    FluxBackground {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = { FluxTopBar(onSettings = { showSettings = true }, onFolder = { requireStorage { folderPicker.launch(null) } }, onAbout = { showAbout = true }) },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                if (parseState is ParseState.Ready && partitions.isNotEmpty()) {
                    GradientFab(text = stringResource(R.string.extract_all), icon = Icons.Rounded.Download, onClick = { requireStorage { vm.extractAll() } })
                }
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    InputHero(
                        mode = sourceMode,
                        onModeChange = { sourceMode = it },
                        value = input,
                        onValueChange = vm::updateInput,
                        onBrowse = { requireStorage { filePicker.launch("*/*") } },
                        parsing = parseState is ParseState.Parsing,
                        onParse = ::doParse,
                    )
                }

                when (val st = parseState) {
                    is ParseState.Ready -> {
                        item { OtaSummary(st.archive) }
                        item {
                            Text(
                                stringResource(R.string.images_header, partitions.size),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                            )
                        }
                        item { SearchField(search, vm::updateSearch) }
                        items(partitions, key = { it.partitionName }) { p ->
                            PartitionTile(info = p, onExtract = { requireStorage { vm.extract(p.partitionName) } })
                        }
                    }
                    is ParseState.Failed -> item { ErrorCard(st.message) }
                    else -> {}
                }
            }
        }
    }

    relink?.let { name ->
        RelinkDialog(partitionName = name, onSubmit = { vm.submitNewLink(it) }, onCancel = { vm.cancelRelink() })
    }
    if (showSettings) SettingsDialog(onDismiss = { showSettings = false })
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    if (showPermission) PermissionDialog(
        onConfirm = { showPermission = false; launchPermission() },
        onDismiss = { showPermission = false; pendingAction = null },
    )
}

@Composable
private fun FluxTopBar(onSettings: () -> Unit, onFolder: () -> Unit, onAbout: () -> Unit) {
    val flux = LocalFluxColors.current
    var menu by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxWidth().background(flux.glass).statusBarsPadding()
            .padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(flux.gradientStart, flux.gradientEnd))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.DataUsage, contentDescription = null, tint = Color.White, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.menu), tint = MaterialTheme.colorScheme.onBackground)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_settings)) }, onClick = { menu = false; onSettings() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_output_folder)) }, onClick = { menu = false; onFolder() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_about)) }, onClick = { menu = false; onAbout() })
                }
            }
        }
    }
}

@Composable
private fun InputHero(
    mode: Int,
    onModeChange: (Int) -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    onBrowse: () -> Unit,
    parsing: Boolean,
    onParse: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(FluxRadius.Card)) {
        val browseIcon: (@Composable () -> Unit)? = if (mode == 0) {
            { IconButton(onClick = onBrowse) { Icon(Icons.Rounded.FolderOpen, contentDescription = stringResource(R.string.action_browse)) } }
        } else {
            null
        }
        Column(modifier = Modifier.padding(18.dp)) {
            SegmentedToggle(options = listOf(stringResource(R.string.source_local), stringResource(R.string.source_network)), selectedIndex = mode, onSelect = onModeChange)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(stringResource(if (mode == 0) R.string.input_hint_local else R.string.input_hint_network)) },
                trailingIcon = browseIcon,
                singleLine = false,
                maxLines = 3,
                shape = RoundedCornerShape(FluxRadius.Field),
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.height(16.dp))
            GradientPillButton(text = stringResource(if (parsing) R.string.action_parsing else R.string.action_parse), onClick = onParse, enabled = !parsing)
        }
    }
}

@Composable
private fun OtaSummary(info: ArchiveInfo) {
    val flux = LocalFluxColors.current
    Column {
        Text(
            info.fileName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 4.dp),
        )
        Text(
            stringResource(R.string.block_size, info.blockSize),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 12.dp),
        )
        // 信息卡固定用蓝/绿/橙三色,让这排始终是彩色的,不随主色变单调。
        SummaryRow(
            cards = listOf(
                SummaryData(Icons.Rounded.Storage, stringResource(R.string.summary_file_size), formatSize(info.fileSize), Color(0xFF3B82F6)),
                SummaryData(Icons.Rounded.Security, stringResource(R.string.summary_security_patch), info.securityPatchLevel.ifBlank { "—" }, flux.success),
                SummaryData(Icons.Rounded.Dns, stringResource(R.string.summary_partitions), "${info.partitionCount}", Color(0xFFF59E0B)),
            ),
        )
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(FluxRadius.Pill),
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun ErrorCard(message: String) {
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(FluxRadius.Card)) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.parse_failed), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
