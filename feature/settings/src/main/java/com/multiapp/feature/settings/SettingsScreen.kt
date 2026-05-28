package com.multiapp.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection(title = "应用信息") {
                SettingsItem(icon = Icons.Default.Info, title = "版本", subtitle = uiState.appVersion)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(icon = Icons.Default.Settings, title = "包名", subtitle = uiState.packageName)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(icon = Icons.Default.Settings, title = "构建类型", subtitle = uiState.buildType)
            }

            SettingsSection(title = "默认设备身份模板") {
                SettingsItem(icon = Icons.Default.Phone, title = "设备型号", subtitle = "Pixel 7 Pro")
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(icon = Icons.Default.Phone, title = "制造商", subtitle = "Google")
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(icon = Icons.Default.Phone, title = "Android 版本", subtitle = "14")
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(icon = Icons.Default.Phone, title = "SDK 版本", subtitle = "34")
            }

            SettingsSection(title = "关于") {
                SettingsItem(icon = Icons.Default.Info, title = "MultiApp", subtitle = "Android 应用多开工具")
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(icon = Icons.Default.Info, title = "架构", subtitle = "Jetpack Compose + Material 3 + Hilt")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
