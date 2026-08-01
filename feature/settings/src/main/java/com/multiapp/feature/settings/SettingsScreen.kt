package com.multiapp.feature.settings

import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.multiapp.core.designsystem.components.SettingsSection
import com.multiapp.core.designsystem.components.SettingsDivider
import com.multiapp.core.common.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 产品化头部：关键身份信息前置，低密度但一眼可读
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                tonalElevation = 0.dp,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "MultiApp",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "v${uiState.appVersion} · ${uiState.buildType}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = uiState.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 设备身份模板：强调“影响宿主对外暴露身份”的说明
            SettingsSection(
                title = "设备身份模板",
                icon = Icons.Default.PhoneAndroid,
                iconTint = MaterialTheme.colorScheme.primary
            ) {
                Text(
                    text = "以下信息用于展示宿主设备身份，修改前请确认对目标应用的影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                DeviceIdentityContent()
            }

            // 存储与缓存：更贴近系统设置分组
            SettingsSection(
                title = "存储与缓存",
                icon = Icons.Default.Storage,
                iconTint = MaterialTheme.colorScheme.tertiary
            ) {
                StorageContent()
            }

            // 高级设置：当前仅做占位说明，避免用户误以为缺失
            SettingsSection(
                title = "高级设置",
                icon = Icons.Default.Tune,
                iconTint = MaterialTheme.colorScheme.secondary
            ) {
                Text(
                    text = "当前版本暂未开放更多高级设置，后续版本会逐步补齐。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}



@Composable
private fun DeviceIdentityContent() {
    Column {
        DeviceIdentityItem(
            icon = Icons.Default.PhoneAndroid,
            label = "设备型号",
            value = Build.MODEL
        )
        SettingsDivider()
        DeviceIdentityItem(
            icon = Icons.Default.Business,
            label = "制造商",
            value = Build.MANUFACTURER
        )
        SettingsDivider()
        DeviceIdentityItem(
            icon = Icons.Default.Android,
            label = "Android 版本",
            value = Build.VERSION.RELEASE
        )
        SettingsDivider()
        DeviceIdentityItem(
            icon = Icons.Default.Code,
            label = "SDK 版本",
            value = Build.VERSION.SDK_INT.toString()
        )
    }
}

@Composable
private fun DeviceIdentityItem(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun StorageContent() {
    val storageInfo = remember {
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stat.totalBytes
            val availableBytes = stat.availableBytes
            val usedBytes = totalBytes - availableBytes
            Triple(totalBytes, usedBytes, availableBytes)
        } catch (_: Exception) {
            Triple(0L, 0L, 0L)
        }
    }

    val (totalBytes, usedBytes, availableBytes) = storageInfo
    val usedPercent = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes) else 0f

    Column {
        // Storage usage bar
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "已使用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${formatBytes(usedBytes)} / ${formatBytes(totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { usedPercent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (usedPercent > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }

        SettingsDivider()

        StorageItem(
            icon = Icons.Default.SdStorage,
            label = "总容量",
            value = formatBytes(totalBytes)
        )
        SettingsDivider()
        StorageItem(
            icon = Icons.Default.DataUsage,
            label = "已使用",
            value = formatBytes(usedBytes)
        )
        SettingsDivider()
        StorageItem(
            icon = Icons.Default.Storage,
            label = "可用空间",
            value = formatBytes(availableBytes)
        )
    }
}

@Composable
private fun StorageItem(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
    }
}



// SettingsSection, SettingsDivider 已提取到 core/designsystem/CommonComponents.kt
