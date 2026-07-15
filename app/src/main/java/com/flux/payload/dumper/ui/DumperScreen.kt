package com.flux.payload.dumper.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flux.payload.dumper.data.PathUtil
import com.flux.payload.dumper.data.Preferences
import com.flux.payload.dumper.model.ArchiveInfo
import com.flux.payload.dumper.ui.components.AboutDialog
import com.flux.payload.dumper.ui.components.FluxCard
import com.flux.payload.dumper.ui.components.GradientPillButton
import com.flux.payload.dumper.ui.components.InfoRow
import com.flux.payload.dumper.ui.components.PartitionRow
import com.flux.payload.dumper.ui.components.PermissionDialog
import com.flux.payload.dumper.ui.components.RelinkDialog
import com.flux.payload.dumper.ui.components.SectionTitle
import com.flux.payload.dumper.ui.components.SettingsDialog
import com.flux.payload.dumper.ui.components.SourceSelectorCard
import com.flux.payload.dumper.ui.components.TonalPill
import com.flux.payload.dumper.ui.components.formatSize
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
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showPermission by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbar) {
        snackbar?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeSnackbar()
        }
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
        if (PathUtil.isAllFilesAccessGranted()) action() else {
            pendingAction = action
            showPermission = true
        }
    }

    fun launchPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:" + context.packageName))
        runCatching { permissionLauncher.launch(intent) }
            .onFailure { permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { FluxTopBar(onSettings = { showSettings = true }, onFolder = { requireStorage { folderPicker.launch(null) } }, onAbout = { showAbout = true }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SourceSelectorCard(
                    onPickLocal = { requireStorage { filePicker.launch("*/*") } },
                    onPickUrl = { /* URL is typed directly into the field below */ },
                )
            }

            item {
                InputCard(
                    value = input,
                    onValueChange = vm::updateInput,
                    parsing = parseState is ParseState.Parsing,
                    onParse = {
                        if (input.trim().startsWith("http")) vm.parse() else requireStorage { vm.parse() }
                    },
                )
            }

            when (val st = parseState) {
                is ParseState.Ready -> {
                    item { SectionTitle("OTA 信息") }
                    item { OtaInfoCard(st.archive) }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            SectionTitle("镜像列表 · ${partitions.size}")
                            TonalPill(text = "提取全部", onClick = { requireStorage { vm.extractAll() } })
                        }
                    }
                    item { SearchField(search, vm::updateSearch) }
                    if (partitions.isNotEmpty()) {
                        item { PartitionListCard(partitions, onExtract = { name -> requireStorage { vm.extract(name) } }) }
                    }
                }
                is ParseState.Failed -> item { ErrorCard(st.message) }
                else -> {}
            }
        }
    }

    relink?.let { name ->
        RelinkDialog(
            partitionName = name,
            onSubmit = { vm.submitNewLink(it) },
            onCancel = { vm.cancelRelink() },
        )
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
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Payload Dumper",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "菜单", tint = MaterialTheme.colorScheme.onBackground)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("设置") }, onClick = { menu = false; onSettings() })
                    DropdownMenuItem(text = { Text("输出目录") }, onClick = { menu = false; onFolder() })
                    DropdownMenuItem(text = { Text("关于") }, onClick = { menu = false; onAbout() })
                }
            }
        }
    }
}

@Composable
private fun InputCard(value: String, onValueChange: (String) -> Unit, parsing: Boolean, onParse: () -> Unit) {
    FluxCard {
        Text("输入", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text("payload.bin 路径 或 OTA 直链 URL") },
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )
        Spacer(Modifier.height(16.dp))
        GradientPillButton(text = if (parsing) "解析中…" else "解析", onClick = onParse, enabled = !parsing)
    }
}

@Composable
private fun OtaInfoCard(info: ArchiveInfo) {
    FluxCard {
        InfoRow("文件大小", formatSize(info.fileSize))
        InfoRow("安全补丁等级", info.securityPatchLevel)
        InfoRow("分区数量 / 块大小", "${info.partitionCount} · ${info.blockSize} B")
        InfoRow("文件名", info.fileName)
    }
}

@Composable
private fun PartitionListCard(
    partitions: List<com.flux.payload.dumper.model.PartitionInfo>,
    onExtract: (String) -> Unit,
) {
    FluxCard(padding = PaddingValues(vertical = 4.dp)) {
        partitions.forEachIndexed { i, p ->
            PartitionRow(info = p, onExtract = { onExtract(p.partitionName) })
            if (i < partitions.lastIndex) {
                Box(
                    Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp).height(0.6.dp)
                        .background(MaterialTheme.colorScheme.outline)
                )
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text("搜索分区") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(100.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
    )
}

@Composable
private fun ErrorCard(message: String) {
    FluxCard {
        Text("解析失败", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
