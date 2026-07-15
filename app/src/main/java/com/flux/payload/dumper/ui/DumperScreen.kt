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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.flux.payload.dumper.data.PathUtil
import com.flux.payload.dumper.data.Preferences
import com.flux.payload.dumper.model.ArchiveInfo
import com.flux.payload.dumper.model.PartitionInfo
import com.flux.payload.dumper.ui.components.AboutDialog
import com.flux.payload.dumper.ui.components.GradientFab
import com.flux.payload.dumper.ui.components.GradientPillButton
import com.flux.payload.dumper.ui.components.PartitionTile
import com.flux.payload.dumper.ui.components.PermissionDialog
import com.flux.payload.dumper.ui.components.RelinkDialog
import com.flux.payload.dumper.ui.components.SegmentedToggle
import com.flux.payload.dumper.ui.components.SettingsDialog
import com.flux.payload.dumper.ui.components.StatStrip
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { FluxTopBar(onSettings = { showSettings = true }, onFolder = { requireStorage { folderPicker.launch(null) } }, onAbout = { showAbout = true }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (parseState is ParseState.Ready && partitions.isNotEmpty()) {
                GradientFab(text = "提取全部", icon = Icons.Rounded.Download, onClick = { requireStorage { vm.extractAll() } })
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
                            "镜像 · ${partitions.size}",
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
private fun InputHero(
    mode: Int,
    onModeChange: (Int) -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    onBrowse: () -> Unit,
    parsing: Boolean,
    onParse: () -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FluxRadius.Card),
        color = MaterialTheme.colorScheme.surface,
    ) {
        val browseIcon: (@Composable () -> Unit)? = if (mode == 0) {
            { IconButton(onClick = onBrowse) { Icon(Icons.Rounded.FolderOpen, contentDescription = "浏览") } }
        } else {
            null
        }
        Column(modifier = Modifier.padding(18.dp)) {
            SegmentedToggle(options = listOf("本地文件", "网络链接"), selectedIndex = mode, onSelect = onModeChange)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(if (mode == 0) "payload.bin / OTA 包路径" else "粘贴 OTA 直链 URL") },
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
            GradientPillButton(text = if (parsing) "解析中…" else "解析", onClick = onParse, enabled = !parsing)
        }
    }
}

@Composable
private fun OtaSummary(info: ArchiveInfo) {
    Column {
        Text(
            info.fileName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
        )
        StatStrip(
            chips = listOf(
                "文件大小" to formatSize(info.fileSize),
                "安全补丁" to info.securityPatchLevel,
                "分区数" to "${info.partitionCount}",
                "块大小" to "${info.blockSize} B",
            )
        )
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
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FluxRadius.Card),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("解析失败", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
