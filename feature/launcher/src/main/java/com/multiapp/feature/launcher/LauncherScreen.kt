package com.multiapp.feature.launcher

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import com.multiapp.core.instance.InstanceInfo
import com.multiapp.core.instance.InstanceStatus
import com.multiapp.core.model.VirtualApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(
    viewModel: LauncherViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAppPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MultiApp") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAppPicker = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add instance")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                uiState.error != null -> {
                    Text(
                        text = uiState.error ?: "Unknown error",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                uiState.instances.isEmpty() -> {
                    Text(
                        text = "No instances yet. Tap + to create one.",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.instances) { instance ->
                            InstanceCard(
                                instance = instance,
                                onDelete = { viewModel.deleteInstance(instance.instanceId) }
                            )
                        }
                    }
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(
    onDismiss: () -> Unit,
    onAppSelected: (VirtualApp) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }

    val apps = remember {
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

    val filteredApps = if (searchQuery.isBlank()) apps
    else apps.filter {
        it.appName.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "Select App to Clone",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search apps...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                singleLine = true
            )

            Text(
                text = "${filteredApps.size} apps",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    AppItem(
                        app = app,
                        onClick = { onAppSelected(app) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AppItem(
    app: VirtualApp,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        app.icon?.let { drawable ->
            val bitmap = remember(drawable) {
                drawable.toBitmap(40, 40)
            }
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = app.appName,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = app.versionName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InstanceCard(
    instance: InstanceInfo,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = instance.originalPackageName,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "ID: ${instance.instanceId}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Status: ${instance.status.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = when (instance.status) {
                        InstanceStatus.RUNNING -> MaterialTheme.colorScheme.primary
                        InstanceStatus.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Row {
                IconButton(onClick = { /* TODO: launch instance */ }) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Launch",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
