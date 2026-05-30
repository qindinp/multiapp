package com.multiapp.feature.appmanager

import android.content.pm.PackageManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.multiapp.core.common.formatBytes
import com.multiapp.core.common.getDirSize
import com.multiapp.core.instance.InstanceInfo
import com.multiapp.core.instance.InstanceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppManagerScreen(
    viewModel: AppManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDetailDialog by remember { mutableStateOf<InstanceInfo?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle undo delete events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AppManagerEvent.UndoDelete -> {
                    val result = snackbarHostState.showSnackbar(
                        message = "已删除 ${event.instanceId.substringAfterLast("_").take(8)}…",
                        actionLabel = "撤销",
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.undoDelete(event.instanceId, event.identityJson)
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "实例管理",
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
                    IconButton(onClick = { viewModel.onEvent(AppManagerEvent.Refresh) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isLoading -> LoadingState()
                uiState.error != null -> ErrorState(error = uiState.error!!)
                uiState.instances.isEmpty() -> EmptyState()
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(
                            uiState.instances,
                            key = { _, instance -> instance.instanceId }
                        ) { index, instance ->
                            var visible by remember { mutableStateOf(false) }
                            LaunchedEffect(instance) {
                                kotlinx.coroutines.delay(index * 40L)
                                visible = true
                            }
                            AnimatedVisibility(
                                visible = visible,
                                enter = fadeIn(tween(300)) + slideInVertically { it / 2 }
                            ) {
                                AppManagerCard(
                                    instance = instance,
                                    isExpanded = uiState.expandedInstanceId == instance.instanceId,
                                    onToggleExpand = {
                                        viewModel.onEvent(
                                            AppManagerEvent.ToggleExpand(instance.instanceId)
                                        )
                                    },
                                    onShowDetail = { showDetailDialog = instance },
                                    onDelete = {
                                        viewModel.onEvent(
                                            AppManagerEvent.DeleteInstance(instance.instanceId)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Detail dialog (P1-3)
    showDetailDialog?.let { instance ->
        InstanceDetailDialog(
            instance = instance,
            onDismiss = { showDetailDialog = null }
        )
    }
}

@Composable
private fun AppManagerCard(
    instance: InstanceInfo,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onShowDetail: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current

    // Load original app icon
    val appIcon = remember(instance.originalPackageName) {
        try {
            context.packageManager.getApplicationIcon(instance.originalPackageName)
        } catch (_: Exception) {
            null
        }
    }

    // Async data size computation
    var dataSize by remember(instance) { mutableStateOf("—") }
    LaunchedEffect(instance) {
        dataSize = withContext(Dispatchers.IO) {
            try {
                val dataDir = File("/data/data/${instance.stubPackageName}")
                if (dataDir.exists()) formatBytes(getDirSize(dataDir)) else "—"
            } catch (_: Exception) {
                "—"
            }
        }
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon with gradient background
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
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
                        val bitmap = remember(appIcon) { appIcon.toBitmap(96, 96) }
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
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
                                .align(Alignment.BottomEnd)
                                .offset(x = 2.dp, y = 2.dp)
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = pulseAlpha))
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = instance.originalPackageName.substringAfterLast("."),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = instance.originalPackageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // Identity summary + data size
                    Text(
                        text = "${instance.identity.buildModel} · ${instance.identity.buildManufacturer} · $dataSize",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    InstanceStatusChip(status = instance.status)
                }

                Row {
                    IconButton(onClick = onShowDetail) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "详情",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onToggleExpand) {
                        Icon(
                            if (isExpanded) Icons.Default.KeyboardArrowUp
                            else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "收起" else "展开",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    DetailRow(Icons.Default.Tag, "实例 ID", instance.instanceId)
                    DetailRow(Icons.Default.FolderOpen, "原始包名", instance.originalPackageName)
                    DetailRow(Icons.Default.Folder, "Stub 包名", instance.stubPackageName)
                    DetailRow(Icons.Default.Phone, "IMEI", instance.identity.imei)
                    DetailRow(Icons.Default.Key, "Android ID", instance.identity.androidId)
                    DetailRow(Icons.Default.PhoneAndroid, "设备型号", instance.identity.buildModel)
                    DetailRow(Icons.Default.Business, "制造商", instance.identity.buildManufacturer)
                    DetailRow(Icons.Default.BrandingWatermark, "品牌", instance.identity.buildBrand)
                }
            }
        }
    }
}

/**
 * P1-3: Instance detail dialog showing device identity, timestamps, stub package, status.
 */
@Composable
private fun InstanceDetailDialog(
    instance: InstanceInfo,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
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
                Icon(
                    Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        title = {
            Text(
                text = instance.originalPackageName.substringAfterLast("."),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Status
                DetailDialogSection("状态") {
                    DetailDialogRow(Icons.Default.Circle, "当前状态", when (instance.status) {
                        InstanceStatus.CREATING -> "创建中"
                        InstanceStatus.READY -> "就绪"
                        InstanceStatus.RUNNING -> "运行中"
                        InstanceStatus.ERROR -> "错误"
                    })
                }

                // Package info
                DetailDialogSection("包信息") {
                    DetailDialogRow(Icons.Default.Tag, "实例 ID", instance.instanceId)
                    DetailDialogRow(Icons.Default.FolderOpen, "原始包名", instance.originalPackageName)
                    DetailDialogRow(Icons.Default.Folder, "Stub 包名", instance.stubPackageName)
                }

                // Device identity
                DetailDialogSection("设备身份") {
                    DetailDialogRow(Icons.Default.Phone, "IMEI", instance.identity.imei)
                    DetailDialogRow(Icons.Default.Key, "Android ID", instance.identity.androidId)
                    DetailDialogRow(Icons.Default.Wifi, "MAC 地址", instance.identity.macAddress)
                    DetailDialogRow(Icons.Default.Tag, "序列号", instance.identity.serial)
                    DetailDialogRow(Icons.Default.PhoneAndroid, "设备型号", instance.identity.buildModel)
                    DetailDialogRow(Icons.Default.Business, "制造商", instance.identity.buildManufacturer)
                    DetailDialogRow(Icons.Default.BrandingWatermark, "品牌", instance.identity.buildBrand)
                    DetailDialogRow(Icons.Default.Memory, "设备代号", instance.identity.buildDevice)
                    DetailDialogRow(Icons.Default.Android, "Android 版本", instance.identity.versionRelease)
                    DetailDialogRow(Icons.Default.Code, "SDK 版本", instance.identity.sdkInt.toString())
                }

                // Timestamps
                DetailDialogSection("时间") {
                    DetailDialogRow(
                        Icons.Default.Schedule,
                        "创建时间",
                        dateFormat.format(Date(instance.createdAt))
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun DetailDialogSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
    Column(content = content)
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun DetailDialogRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
        color = bgColor
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun DetailRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// LoadingState, ErrorState, EmptyState 已提取到 core/designsystem/CommonComponents.kt

@Composable
private fun EmptyState() {
    EmptyState(
        title = "暂无分身实例",
        subtitle = "在首页添加应用分身后，\n可以在这里管理所有实例。"
    )
}
