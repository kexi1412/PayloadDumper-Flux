package com.flux.payload.dumper.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flux.payload.dumper.R
import com.flux.payload.dumper.data.PathUtil
import com.flux.payload.dumper.data.Preferences
import com.flux.payload.dumper.model.ArchiveInfo
import com.flux.payload.dumper.ui.components.AboutDialog
import com.flux.payload.dumper.ui.components.AccentButton
import com.flux.payload.dumper.ui.components.CardGroup
import com.flux.payload.dumper.ui.components.CategoryHeader
import com.flux.payload.dumper.ui.components.ExtractFab
import com.flux.payload.dumper.ui.components.FluxBackground
import com.flux.payload.dumper.ui.components.IconBox
import com.flux.payload.dumper.ui.components.InfoRow
import com.flux.payload.dumper.ui.components.PartitionRow
import com.flux.payload.dumper.ui.components.PermissionDialog
import com.flux.payload.dumper.ui.components.RelinkDialog
import com.flux.payload.dumper.ui.components.RowDivider
import com.flux.payload.dumper.ui.components.SegmentedToggle
import com.flux.payload.dumper.ui.components.SettingsDialog
import com.flux.payload.dumper.ui.components.formatSize
import com.flux.payload.dumper.ui.theme.FluxRadius
import com.flux.payload.dumper.ui.theme.LocalFluxColors
import com.flux.payload.dumper.viewmodel.DumperViewModel
import com.flux.payload.dumper.viewmodel.ParseState

@OptIn(ExperimentalMaterial3Api::class)
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

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.updateInput(it.toString())
        }
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
        val t = input.trim()
        if (t.startsWith("http") || t.startsWith("content://")) vm.parse() else requireStorage { vm.parse() }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    FluxBackground {
        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                FluxTopBar(
                    scrollBehavior = scrollBehavior,
                    onSettings = { showSettings = true },
                    onFolder = { requireStorage { folderPicker.launch(null) } },
                    onAbout = { showAbout = true },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                if (parseState is ParseState.Ready && partitions.isNotEmpty()) {
                    ExtractFab(text = stringResource(R.string.extract_all), icon = Icons.Rounded.Download, onClick = { requireStorage { vm.extractAll() } })
                }
            },
        ) { padding ->
            // COUI 17 的列表左右边距是 16dp（卡片外的 margin），卡片内行 padding 是 32dp
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding(),
                    bottom = 120.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // COUI 17 分类标题：左 margin 16dp（category_marginStart），紧贴列表
                item { CategoryHeader(stringResource(R.string.cat_source)) }
                item {
                    InputCard(
                        mode = sourceMode,
                        onModeChange = { sourceMode = it },
                        value = input,
                        onValueChange = vm::updateInput,
                        onBrowse = { filePicker.launch(arrayOf("*/*")) },
                        parsing = parseState is ParseState.Parsing,
                        onParse = ::doParse,
                    )
                }

                when (val st = parseState) {
                    is ParseState.Parsing -> {
                        // Walking a 9 GB nested Honor ZIP takes ~1 min; show live progress so
                        // it does not look like an unresponsive app.
                        item { Spacer(Modifier.height(12.dp)) }
                        item { ParseProgressCard(st.detail) }
                    }
                    is ParseState.Ready -> {
                        item { CategoryHeader(stringResource(R.string.cat_package)) }
                        item { OtaSummaryCard(st.archive) }

                        item { CategoryHeader(stringResource(R.string.images_header, partitions.size)) }
                        item { SearchField(search, vm::updateSearch) }
                        item { Spacer(Modifier.height(8.dp)) }
                        item {
                            CardGroup {
                                partitions.forEachIndexed { i, p ->
                                    PartitionRow(info = p, onExtract = { requireStorage { vm.extract(p.partitionName) } })
                                    if (i < partitions.lastIndex) RowDivider(startInset = 72.dp)
                                }
                            }
                        }
                    }
                    is ParseState.Failed -> {
                        item { Spacer(Modifier.height(12.dp)) }
                        item { ErrorCard(st.message) }
                    }
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

/**
 * COUI 17 折叠工具栏——大标题 32sp Bold，折叠后 24sp。
 * 展开高度 coui_appbar_title_expanded_height = 110dp。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FluxTopBar(
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    onSettings: () -> Unit,
    onFolder: () -> Unit,
    onAbout: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    LargeTopAppBar(
        title = {
            Text(
                stringResource(R.string.app_name),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                ),
            )
        },
        actions = {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.menu), tint = MaterialTheme.colorScheme.onBackground)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.action_settings)) }, onClick = { menu = false; onSettings() })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_output_folder)) }, onClick = { menu = false; onFolder() })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_about)) }, onClick = { menu = false; onAbout() })
            }
        },
        colors = TopAppBarDefaults.largeTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
        ),
        scrollBehavior = scrollBehavior,
    )
}

/**
 * 输入卡片——COUI 17 卡片内有 32dp 内距。
 */
@Composable
private fun InputCard(
    mode: Int,
    onModeChange: (Int) -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    onBrowse: () -> Unit,
    parsing: Boolean,
    onParse: () -> Unit,
) {
    val flux = LocalFluxColors.current
    CardGroup {
        Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 20.dp)) {
            SegmentedToggle(
                options = listOf(stringResource(R.string.source_local), stringResource(R.string.source_network)),
                selectedIndex = mode,
                onSelect = onModeChange,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(stringResource(if (mode == 0) R.string.input_hint_local else R.string.input_hint_network)) },
                leadingIcon = {
                    IconBox(
                        icon = if (mode == 0) Icons.Rounded.FolderOpen else Icons.Rounded.CloudUpload,
                        contentDescription = null,
                        tint = if (mode == 0) flux.iconLocal else flux.iconUrl,
                        bgColor = Color.Transparent,
                        size = 28.dp,
                        iconSize = 18.dp,
                    )
                },
                trailingIcon = {
                    if (mode == 0) {
                        IconButton(onClick = onBrowse) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = stringResource(R.string.action_browse))
                        }
                    }
                },
                singleLine = false,
                maxLines = 3,
                shape = RoundedCornerShape(FluxRadius.Field),
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = flux.fieldBg,
                    unfocusedContainerColor = flux.fieldBg,
                    focusedIndicatorColor = flux.accent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.height(16.dp))
            AccentButton(
                text = stringResource(if (parsing) R.string.action_parsing else R.string.action_parse),
                onClick = onParse,
                enabled = !parsing,
            )
        }
    }
}

/**
 * OTA 摘要卡片——精确模仿 coui_preference_assignment_in_right 行结构。
 * 每行 paddingStart/End = 32dp，行间分割线从 32dp 开始。
 */
@Composable
private fun OtaSummaryCard(info: ArchiveInfo) {
    val flux = LocalFluxColors.current
    CardGroup {
        // 文件名标题区域——不使用标准 InfoRow，用自定义标题块
        Column(modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 8.dp)) {
            Text(
                info.fileName,
                style = MaterialTheme.typography.titleMedium,
                color = flux.labelPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.block_size, info.blockSize),
                style = MaterialTheme.typography.bodyMedium,
                color = flux.labelSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        RowDivider(startInset = 32.dp)
        InfoRow(stringResource(R.string.summary_file_size), formatSize(info.fileSize))
        RowDivider(startInset = 32.dp)
        InfoRow(stringResource(R.string.summary_security_patch), info.securityPatchLevel.ifBlank { "—" })
        RowDivider(startInset = 32.dp)
        InfoRow(stringResource(R.string.summary_partitions), "${info.partitionCount}")
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
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
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
    val flux = LocalFluxColors.current
    CardGroup {
        Column(modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 20.dp)) {
            Text(stringResource(R.string.parse_failed), style = MaterialTheme.typography.titleMedium, color = flux.danger)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = flux.labelSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * COUI 17 风格的行内进度卡：一条细进度条 + 当前阶段文字。
 * 用于解析超大嵌套包（荣耀 SD 卡升级包内层 9 GB）时的实时反馈。
 */
@Composable
private fun ParseProgressCard(detail: String?) {
    val flux = LocalFluxColors.current
    CardGroup {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBox(icon = Icons.Outlined.Refresh, contentDescription = null, tint = flux.iconLocal, bgColor = flux.iconBoxBg)
            Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                Text(
                    detail ?: stringResource(R.string.state_parsing),
                    style = MaterialTheme.typography.titleMedium,
                    color = flux.labelPrimary,
                    maxLines = 2,
                )
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp)),
                    color = flux.iconLocal,
                    trackColor = flux.divider,
                )
            }
        }
    }
}
