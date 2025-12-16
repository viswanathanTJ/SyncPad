package com.example.largeindexblog.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.largeindexblog.repository.SettingsRepository
import com.example.largeindexblog.ui.state.IndexState
import com.example.largeindexblog.ui.state.SyncState
import com.example.largeindexblog.ui.viewmodel.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings screen with theme, font size, sync, and index options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val infoState by viewModel.infoState.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val indexState by viewModel.indexState.collectAsState()
    
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle sync state changes
    LaunchedEffect(syncState) {
        when (val state = syncState) {
            is SyncState.Success -> {
                snackbarHostState.showSnackbar(
                    message = state.result.toDisplayString(),
                    duration = SnackbarDuration.Short
                )
                viewModel.resetSyncState()
            }
            is SyncState.Error -> {
                snackbarHostState.showSnackbar(
                    message = state.message,
                    duration = SnackbarDuration.Long
                )
                viewModel.resetSyncState()
            }
            else -> {}
        }
    }

    // Handle index state changes
    LaunchedEffect(indexState) {
        when (val state = indexState) {
            is IndexState.Complete -> {
                snackbarHostState.showSnackbar(
                    message = "Index rebuilt: ${state.entriesCount} entries",
                    duration = SnackbarDuration.Short
                )
                viewModel.resetIndexState()
            }
            is IndexState.Error -> {
                snackbarHostState.showSnackbar(
                    message = state.message,
                    duration = SnackbarDuration.Long
                )
                viewModel.resetIndexState()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Appearance Section
            SettingsSection(title = "Appearance") {
                // Theme
                SettingsItem(
                    title = "Theme",
                    subtitle = when (settings.theme) {
                        SettingsRepository.THEME_LIGHT -> "Light"
                        SettingsRepository.THEME_DARK -> "Dark"
                        else -> "System default"
                    }
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeButton(
                            label = "Light",
                            isSelected = settings.theme == SettingsRepository.THEME_LIGHT,
                            onClick = { viewModel.setTheme(SettingsRepository.THEME_LIGHT) }
                        )
                        ThemeButton(
                            label = "Dark",
                            isSelected = settings.theme == SettingsRepository.THEME_DARK,
                            onClick = { viewModel.setTheme(SettingsRepository.THEME_DARK) }
                        )
                        ThemeButton(
                            label = "System",
                            isSelected = settings.theme == SettingsRepository.THEME_SYSTEM,
                            onClick = { viewModel.setTheme(SettingsRepository.THEME_SYSTEM) }
                        )
                    }
                }

                HorizontalDivider()

                // Font Size
                SettingsItem(
                    title = "Font Size",
                    subtitle = "${settings.fontSize}sp"
                ) {
                    Slider(
                        value = settings.fontSize.toFloat(),
                        onValueChange = { viewModel.setFontSize(it.toInt()) },
                        valueRange = SettingsRepository.MIN_FONT_SIZE.toFloat()..SettingsRepository.MAX_FONT_SIZE.toFloat(),
                        steps = (SettingsRepository.MAX_FONT_SIZE - SettingsRepository.MIN_FONT_SIZE) / 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Index Section
            SettingsSection(title = "Index") {
                // MAX_DEPTH
                SettingsItem(
                    title = "Max Prefix Depth",
                    subtitle = "${settings.maxDepth} characters"
                ) {
                    Slider(
                        value = settings.maxDepth.toFloat(),
                        onValueChange = { viewModel.setMaxDepth(it.toInt()) },
                        valueRange = 1f..10f,
                        steps = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider()

                // Rebuild Index
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Refresh Index",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Button(
                        onClick = { viewModel.rebuildIndex() },
                        enabled = indexState !is IndexState.Building
                    ) {
                        if (indexState is IndexState.Building) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        Text("Rebuild")
                    }
                }
            }

            // Sync Section
            SettingsSection(title = "Sync") {
                // Full Sync
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Full Sync",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Sync with server",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = { viewModel.performIncrementalSync() },
                        enabled = syncState !is SyncState.Syncing
                    ) {
                        if (syncState is SyncState.Syncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        Text("Sync")
                    }
                }

                HorizontalDivider()

                // Hard Sync
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Hard Sync",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Clear and re-download all data",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = { viewModel.performHardSync() },
                        enabled = syncState !is SyncState.Syncing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Hard Sync")
                    }
                }
            }

            // Info Section
            SettingsSection(title = "Info") {
                val dateFormatter = remember {
                    SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.getDefault())
                }

                InfoRow(
                    label = "Local Blogs",
                    value = infoState.localCount.toString()
                )
                
                HorizontalDivider()
                
                InfoRow(
                    label = "Last Sync",
                    value = if (infoState.lastSyncTime > 0) {
                        dateFormatter.format(Date(infoState.lastSyncTime))
                    } else {
                        "Never"
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            content()
        }
    }
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun ThemeButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Text(label)
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
