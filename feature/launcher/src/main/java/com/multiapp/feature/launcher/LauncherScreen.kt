package com.multiapp.feature.launcher

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import com.multiapp.core.designsystem.components.LoadingState
import com.multiapp.core.designsystem.components.ErrorState
import com.multiapp.core.designsystem.components.EmptyState
import com.multiapp.core.designsystem.components.InstanceStatusChip
import com.multiapp.core.instance.isCloneCandidate
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.VirtualApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(
    viewModel: LauncherViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showAppPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<VirtualInstanceRecord?>(null) }
    var pendingCreateApp by remember { mutableStateOf<VirtualApp?>(null) }
    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importApkFile(context.applicationContext, uri)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "快捷启动",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (uiState.instances.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(10.dp))
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    text = "${uiState.instances.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadInstances() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAppPicker = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("添加", fontWeight = FontWeight.SemiBold) },
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isLoading && uiState.creationStep == null -> LoadingState()
                uiState.error != null && uiState.instances.isEmpty() && uiState.creationStep == null -> ErrorState(
                    error = uiState.formattedError(),
                    onRetry = { viewModel.loadInstances() }
                )
                uiState.instances.isEmpty() && uiState.creationStep == null -> EmptyState(onAdd = { showAppPicker = true })
                else -> AppGrid(
                    instances = uiState.instances,
                    onLaunch = { instance ->
                        viewModel.launchInstance(instance.instanceId)
                    },
                    onDelete = { showDeleteConfirm = it }
                )
            }

            // Creation progress overlay
            uiState.creationStep?.let { step ->
                CreationProgressDialog(step = step)
            }
        }
    }

    if (showAppPicker) {
        AppPickerSheet(
            onDismiss = { showAppPicker = false },
            onPickApkFile = {
                showAppPicker = false
                apkPickerLauncher.launch(
                    arrayOf(
                        "application/vnd.android.package-archive",
                        "application/octet-stream",
                        "*/*"
                    )
                )
            },
            onAppSelected = { app ->
                showAppPicker = false
                pendingCreateApp = app
            },
            viewModel = viewModel
        )
    }

    val createTargetApp = pendingCreateApp ?: uiState.importedApkCandidate
    createTargetApp?.let { app ->
        val existingCount = uiState.instances.count { it.originPackageName == app.packageName }
        CreateInstanceDialog(
            app = app,
            defaultName = viewModel.suggestedDisplayName(app),
            existingCount = existingCount,
            onDismiss = {
                pendingCreateApp = null
                viewModel.clearImportedApkCandidate()
            },
            onConfirm = { displayName ->
                pendingCreateApp = null
                viewModel.clearImportedApkCandidate()
                viewModel.createInstance(app, displayName)
            }
        )
    }

    uiState.lastCreatedInstanceId?.let { createdId ->
        uiState.instances.firstOrNull { it.instanceId == createdId }?.let { instance ->
            CreateResultDialog(
                instance = instance,
                onLaunch = {
                    viewModel.clearLastCreatedInstance()
                    viewModel.launchInstance(instance.instanceId)
                },
                onDismiss = { viewModel.clearLastCreatedInstance() }
            )
        }
    }

    if (uiState.error != null && uiState.instances.isNotEmpty() && uiState.creationStep == null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text(uiState.error ?: "创建失败") },
            text = { Text(uiState.errorDetail.orEmpty().ifBlank { "请重试。" }) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) { Text("知道了") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.loadInstances() }) { Text("刷新") }
            }
        )
    }

    // Delete confirmation dialog
    showDeleteConfirm?.let { instance ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除 ${instance.displayName} 吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteInstance(instance.instanceId)
                    showDeleteConfirm = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun CreationProgressDialog(step: String) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            ElevatedCard(
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .wrapContentHeight()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = step,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppGrid(
    instances: List<VirtualInstanceRecord>,
    onLaunch: (VirtualInstanceRecord) -> Unit,
    onDelete: (VirtualInstanceRecord) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        itemsIndexed(
            instances,
            key = { _, instance -> instance.instanceId }
        ) { index, instance ->
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(instance) {
                kotlinx.coroutines.delay(index * 40L)
                visible = true
            }
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.85f),
            ) {
                AppGridItem(
                    instance = instance,
                    onLaunch = { onLaunch(instance) },
                    onDelete = { onDelete(instance) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppGridItem(
    instance: VirtualInstanceRecord,
    onLaunch: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Load app icon from origin package
    val appIcon = remember(instance.originPackageName) {
        try {
            context.packageManager.getApplicationIcon(instance.originPackageName)
        } catch (_: Exception) {
            null
        }
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onLaunch,
                onLongClick = { showMenu = true }
            ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Icon container with gradient background
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.tertiaryContainer
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (appIcon != null) {
                    val bitmap = remember(appIcon) { appIcon.toBitmap(128, 128) }
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = instance.displayName,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = instance.displayName,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // Running indicator
                if (instance.state == InstanceState.RUNNING) {
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.5f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            tween(600, easing = FastOutSlowInEasing),
                            RepeatMode.Reverse
                        ),
                        label = "pulseAlpha"
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 2.dp, y = (-2).dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = pulseAlpha))
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = instance.displayName.ifBlank { instance.originPackageName.substringAfterLast(".") },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )

            // Status chip
            InstanceStatusChip(label = instance.state.name)

            // Context menu
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("启动") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    onClick = {
                        showMenu = false
                        onLaunch()
                    }
                )
                DropdownMenuItem(
                    text = { Text("删除") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        showMenu = false
                        onDelete()
                    }
                )
            }
        }
    }
}

// InstanceStatusChip 已提取到 core/designsystem/CommonComponents.kt

// LoadingState, ErrorState, EmptyState 已提取到 core/designsystem/CommonComponents.kt

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    EmptyState(
        icon = Icons.Default.CloudDownload,
        title = "暂无分身应用",
        subtitle = "点击下方按钮添加应用分身，\n在独立沙箱中运行多个实例。",
        action = {
            Button(
                onClick = onAdd,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("添加应用", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

@Composable
private fun CloneSourceButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        tonalElevation = 0.dp,
        onClick = onClick ?: {}
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(
    onDismiss: () -> Unit,
    onPickApkFile: () -> Unit,
    onAppSelected: (VirtualApp) -> Unit,
    viewModel: LauncherViewModel
) {
    val allApps by viewModel.allApps.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showAdvancedApps by remember { mutableStateOf(false) }
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadAllApps()
    }

    val instanceCounts = remember(uiState.instances) {
        uiState.instances.groupingBy { it.originPackageName }.eachCount()
    }
    val recommendedApps = remember(allApps) {
        allApps.filter { it.isCloneCandidate() }
    }
    val candidateApps = if (showAdvancedApps) allApps else recommendedApps
    val filteredApps = remember(candidateApps, searchQuery) {
        if (searchQuery.isBlank()) {
            candidateApps
        } else {
            candidateApps.filter {
                it.appName.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    val appListError = uiState.allAppsError

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "添加分身",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "推荐 ${recommendedApps.size} 个，全部 ${allApps.size} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilterChip(
                    selected = showAdvancedApps,
                    onClick = { showAdvancedApps = !showAdvancedApps },
                    label = { Text(if (showAdvancedApps) "全部" else "推荐") },
                    leadingIcon = {
                        Icon(
                            if (showAdvancedApps) Icons.Default.FilterList else Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CloneSourceButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Apps,
                    title = "已安装应用",
                    subtitle = "从设备应用创建"
                )
                CloneSourceButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.UploadFile,
                    title = "APK 文件",
                    subtitle = "从本地安装包导入",
                    onClick = onPickApkFile
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索应用...") },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "清除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                )
            )

            if (uiState.allAppsLoading) {
                LoadingState(message = "读取应用列表...")
            } else if (appListError != null) {
                ErrorState(
                    error = appListError,
                    onRetry = { viewModel.loadAllApps(forceRefresh = true) }
                )
            } else if (allApps.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Apps,
                    title = "未找到可添加的应用",
                    subtitle = "可以导入 APK 文件，或重试刷新已安装应用列表。"
                )
            } else if (filteredApps.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.SearchOff,
                    title = "没有匹配应用",
                    subtitle = "换个关键词或切换到全部应用。"
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    lazyItemsIndexed(
                        filteredApps,
                        key = { _, app -> app.packageName }
                    ) { index, app ->
                    var visible by remember { mutableStateOf(false) }
                    LaunchedEffect(app) {
                        kotlinx.coroutines.delay(index * 20L)
                        visible = true
                    }
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.9f),
                    ) {
                        AppPickerItem(
                            app = app,
                            existingCount = instanceCounts[app.packageName] ?: 0,
                            onClick = { onAppSelected(app) }
                        )
                    }
                }
            }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AppPickerItem(
    app: VirtualApp,
    existingCount: Int,
    onClick: () -> Unit
) {
    val canCreate = app.mainActivity != null
    ElevatedCard(
        onClick = { if (canCreate) onClick() },
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (canCreate) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                app.icon?.let { drawable ->
                    val bitmap = remember(drawable) { drawable.toBitmap(112, 112) }
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = app.appName,
                        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp))
                    )
                } ?: run {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = app.appName,
                        modifier = Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppSmallBadge(app.supportLabel())
                    if (app.isSystemApp) AppSmallBadge("系统")
                    if (existingCount > 0) AppSmallBadge("${existingCount} 个分身")
                }
            }

            Icon(
                imageVector = if (canCreate) Icons.Default.ChevronRight else Icons.Default.Block,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CreateInstanceDialog(
    app: VirtualApp,
    defaultName: String,
    existingCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var displayName by remember(app.packageName, defaultName) { mutableStateOf(defaultName) }
    val context = LocalContext.current
    val appIcon = remember(app.packageName) {
        try {
            app.icon ?: context.packageManager.getApplicationIcon(app.packageName)
        } catch (_: Exception) {
            null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建分身") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (appIcon != null) {
                            val bitmap = remember(appIcon) { appIcon.toBitmap(96, 96) }
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = app.appName,
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                            )
                        } else {
                            Icon(Icons.Default.PhoneAndroid, contentDescription = null)
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.appName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("分身名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = "将为该应用创建独立数据空间。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                if (existingCount > 0) {
                    AppSmallBadge("已有 $existingCount 个同源分身")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(displayName) },
                enabled = displayName.isNotBlank()
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun CreateResultDialog(
    instance: VirtualInstanceRecord,
    onLaunch: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = { Text("分身已创建") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailLine("名称", instance.displayName)
                DetailLine("原始包名", instance.originPackageName)
                DetailLine("虚拟包名", instance.virtualPackageName)
                DetailLine("运行路线", "v2 hosted container")
                DetailLine("数据目录", instance.dataRoot)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text("完成") }
                Button(onClick = onLaunch) { Text("启动") }
            }
        }
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AppSmallBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            maxLines = 1
        )
    }
}

private fun VirtualApp.supportLabel(): String {
    return when {
        mainActivity == null -> "无启动入口"
        isSystemApp -> "谨慎"
        else -> "可创建"
    }
}

private fun LauncherUiState.formattedError(): String {
    return listOfNotNull(
        error,
        errorDetail?.takeIf { it.isNotBlank() && it != error }
    ).joinToString("\n")
}
