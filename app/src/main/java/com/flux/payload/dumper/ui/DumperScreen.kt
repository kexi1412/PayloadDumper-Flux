package com.flux.payload.dumper.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.Download
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
import com.flux.payload.dumper.ui.components.SourceCard
import com.flux.payload.dumper.ui.components.FirmwareInfoCard
import com.flux.payload.dumper.ui.components.AboutDialog
import com.flux.payload.dumper.ui.components.CardGroup
import com.flux.payload.dumper.ui.components.CategoryHeader
import com.flux.payload.dumper.ui.components.ExtractFab
import com.flux.payload.dumper.ui.components.FluxBackground
import com.flux.payload.dumper.ui.components.IconBox
import com.flux.payload.dumper.ui.components.PartitionRow
import com.flux.payload.dumper.ui.components.PermissionDialog
import com.flux.payload.dumper.ui.components.RelinkDialog
import com.flux.payload.dumper.ui.components.SettingsDialog
import com.flux.payload.dumper.ui.theme.FluxRadius
import com.flux.payload.dumper.ui.theme.LocalFluxColors
import com.flux.payload.dumper.viewmodel.DumperViewModel
import com.flux.payload.dumper.viewmodel.ParseState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DumperScreen(vm: DumperViewModel) {
    val selection by vm.selection.collectAsState()
    val inputLocked by vm.inputLocked.collectAsState()
    val parseState by vm.parseState.collectAsState()
    val partitions by vm.partitions.collectAsState()
    val search by vm.search.collectAsState()
    val snackbar by vm.snackbar.collectAsState()
    val relink by vm.relink.collectAsState()
    val relinkBusy by vm.relinkBusy.collectAsState()
    val relinkReason by vm.relinkReason.collectAsState()
    val relinkError by vm.relinkError.collectAsState()
    val batchRunning by vm.batchRunning.collectAsState()

    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.refreshDocumentDetails() }
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
            vm.selectDocument(it)
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
        vm.parse() // SAF reads need only the picked URI grant, not all-files access.
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
                if (parseState is ParseState.Ready && partitions.isNotEmpty() && !inputLocked) {
                    ExtractFab(text = stringResource(R.string.extract_all), icon = Icons.Rounded.Download, onClick = { requireStorage { vm.extractAll() } })
                }
            },
        ) { padding ->
            // COUI17 card margin token: 16dp. Keep each partition lazy and independently rounded.
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
                    SourceCard(
                        selection = selection,
                        onModeChange = vm::changeSourceMode,
                        onUrlChange = vm::updateNetworkUrl,
                        locked = inputLocked,
                        onBrowse = { filePicker.launch(arrayOf("*/*")) },
                        parsing = parseState is ParseState.Parsing,
                        onParse = ::doParse,
                    )
                }

                when (val st = parseState) {
                    is ParseState.Parsing -> {
                        // Walking a 9 GB nested Honor ZIP takes tens of seconds; show live progress
                        // so it does not look like an unresponsive app.
                        item { Spacer(Modifier.height(12.dp)) }
                        item { ParseProgressCard(st.detail) }

                        // Show partial results immediately; extraction is enabled when parsing commits the source.
                        if (partitions.isNotEmpty()) {
                            item { CategoryHeader(stringResource(R.string.images_header, partitions.size)) }
                            item { Spacer(Modifier.height(8.dp)) }
                            items(partitions, key = { it.partitionName }) { p ->
                                CardGroup(Modifier.padding(bottom = 8.dp)) {
                                    PartitionRow(info = p, enabled = false, onExtract = {})
                                }
                            }
                        }
                    }
                    is ParseState.Ready -> {
                        item { CategoryHeader(stringResource(R.string.cat_package)) }
                        item { FirmwareInfoCard(st.archive) }

                        item { CategoryHeader(stringResource(R.string.images_header, partitions.size)) }
                        item { SearchField(search, vm::updateSearch) }
                        item { Spacer(Modifier.height(8.dp)) }
                        if (partitions.isEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.search_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(24.dp),
                                )
                            }
                        }
                        items(partitions, key = { it.partitionName }) { p ->
                            CardGroup(Modifier.padding(bottom = 8.dp)) {
                                PartitionRow(info = p, enabled = !batchRunning && relink == null && !relinkBusy, onExtract = { requireStorage { vm.extract(p.partitionName) } })
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
        RelinkDialog(partitionName = name, busy = relinkBusy, failureReason = relinkReason, error = relinkError, onSubmit = { vm.submitNewLink(it) }, onCancel = { vm.cancelRelink() })
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
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (32f - 8f * scrollBehavior.state.collapsedFraction).sp,
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
        expandedHeight = 110.dp,
    )
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

/**
 * Parse-failure card.
 *
 * The heading used to be a fixed `R.string.parse_failed`, so every distinct failure — a
 * truncated file, an unreadable central directory, an allocation failure — looked identical
 * and the specific reason was buried in small secondary text. The first line of [message] now
 * becomes the heading, so the cause is the first thing visible.
 */
@Composable
private fun ErrorCard(message: String) {
    val flux = LocalFluxColors.current
    val lines = message.trim().lines()
    val heading = lines.firstOrNull()?.takeIf { it.isNotBlank() } ?: stringResource(R.string.parse_failed)
    val rest = lines.drop(1).joinToString("\n").trim()

    CardGroup {
        Column(modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 20.dp)) {
            Text(
                heading,
                style = MaterialTheme.typography.titleMedium,
                color = flux.danger,
            )
            if (rest.isNotEmpty()) {
                Text(
                    rest,
                    style = MaterialTheme.typography.bodyMedium,
                    color = flux.labelSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            // A report is the only thing that can be acted on after the fact, so it is called
            // out explicitly rather than left as the last line of a long error string.
            Text(
                stringResource(R.string.err_report_hint),
                style = MaterialTheme.typography.labelMedium,
                color = flux.labelTertiary,
                modifier = Modifier.padding(top = 10.dp),
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
