package com.multiapp.feature.launcher

import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
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
import com.multiapp.core.instance.InstanceInfo
import com.multiapp.core.instance.InstanceStatus
import com.multiapp.core.model.VirtualApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(
    viewModel: LauncherViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showAppPicker by remember { mutableStateOf(false) }

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
                                shape = RoundedCornerShape(8.dp),
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
                uiState.error != null && uiState.creationStep == null -> ErrorState(
                    error = uiState.error!!,
                    onRetry = { viewModel.loadInstances() }
                )
                uiState.instances.isEmpty() && uiState.creationStep == null -> EmptyState(onAdd = { showAppPicker = true })
                else -> AppGrid(
                    instances = uiState.instances,
                    onLaunch = { instance ->
                        try {
                            val intent = context.packageManager.getLaunchIntentForPackage(instance.stubPackageName)
                            if (intent != null) {
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } else {
                                Toast.makeText(context, "无法启动 ${instance.originalPackageName.substringAfterLast(".")}", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onDelete = { viewModel.deleteInstance(it.instanceId) }
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
            onAppSelected = { app ->
                showAppPicker = false
                viewModel.createInstance(app)
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
    instances: List<InstanceInfo>,
    onLaunch: (InstanceInfo) -> Unit,
    onDelete: (InstanceInfo) -> Unit
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
    instance: InstanceInfo,
    onLaunch: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Load original app icon
    val appIcon = remember(instance.originalPackageName) {
        try {
            context.packageManager.getApplicationIcon(instance.originalPackageName)
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
        shape = RoundedCornerShape(16.dp),
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
                        contentDescription = instance.originalPackageName,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = instance.originalPackageName,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // Running indicator
                if (instance.status == InstanceStatus.RUNNING) {
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
                text = instance.originalPackageName.substringAfterLast("."),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )

            // Status chip
            InstanceStatusChip(status = instance.status)

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

@Composable
private fun InstanceStatusChip(status: InstanceStatus) {
    val (label, color, bgColor) = when (status) {
        InstanceStatus.CREATING -> Triple("创建中", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
        InstanceStatus.READY -> Triple("就绪", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
        InstanceStatus.RUNNING -> Triple("运行中", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
        InstanceStatus.ERROR -> Triple("错误", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor,
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(
    onDismiss: () -> Unit,
    onAppSelected: (VirtualApp) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var allApps by remember { mutableStateOf<List<VirtualApp>>(emptyList()) }

    LaunchedEffect(Unit) {
        allApps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledPackages(PackageManager.GET_META_DATA)
                .filter { pkg ->
                    val appInfo = pkg.applicationInfo ?: return@filter false
                    val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    val isSelf = pkg.packageName == context.packageName
                    val hasLauncher = pm.getLaunchIntentForPackage(pkg.packageName) != null
                    !isSelf && (hasLauncher || !isSystem)
                }
                .map { pkg ->
                    val appInfo = pkg.applicationInfo!!
                    VirtualApp(
                        packageName = pkg.packageName,
                        appName = pm.getApplicationLabel(appInfo).toString(),
                        versionName = pkg.versionName ?: "",
                        versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong(),
                        icon = pm.getApplicationIcon(appInfo),
                        apkPath = appInfo.sourceDir,
                        instanceId = "",
                        mainActivity = pm.getLaunchIntentForPackage(pkg.packageName)?.component?.className
                    )
                }
                .sortedBy { it.appName.lowercase() }
        }
    }

    val filteredApps = if (searchQuery.isBlank()) allApps
    else allApps.filter {
        it.appName.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "选择要分身的应用",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Search bar
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

            // App count
            Text(
                text = "${filteredApps.size} 个应用",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // App grid
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 80.dp),
                modifier = Modifier.heightIn(max = 500.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(
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
                            onClick = { onAppSelected(app) }
                        )
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
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                app.icon?.let { drawable ->
                    val bitmap = remember(drawable) { drawable.toBitmap(112, 112) }
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = app.appName,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                    )
                } ?: run {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = app.appName,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = app.appName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
