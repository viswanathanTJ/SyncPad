package com.example.largeindexblog.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.example.largeindexblog.data.model.BlogListItem
import com.example.largeindexblog.ui.components.AlphabetSidebar
import com.example.largeindexblog.ui.components.BottomIndexBar
import com.example.largeindexblog.ui.components.EmptyState
import com.example.largeindexblog.ui.components.ErrorDisplay
import com.example.largeindexblog.ui.components.LoadingIndicator
import com.example.largeindexblog.ui.state.IndexState
import com.example.largeindexblog.ui.state.SyncState
import com.example.largeindexblog.ui.state.UiState
import com.example.largeindexblog.ui.viewmodel.BlogListViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home screen with blog list, alphabet sidebar, sync button, and FAB.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onBlogClick: (Long) -> Unit,
    onAddClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: BlogListViewModel = hiltViewModel()
) {
    val pagedBlogs = viewModel.pagedBlogs.collectAsLazyPagingItems()
    val alphabetIndex by viewModel.alphabetIndex.collectAsState()
    val prefixFilter by viewModel.prefixFilter.collectAsState()
    val blogCount by viewModel.blogCount.collectAsState()
    val showBottomIndex by viewModel.showBottomIndex.collectAsState()
    val fontSize by viewModel.fontSize.collectAsState()
    val indexState by viewModel.indexState.collectAsState()
    val syncState by viewModel.syncState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

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

    // Handle sync state changes - show snackbar for errors
    LaunchedEffect(syncState) {
        when (val state = syncState) {
            is SyncState.Error -> {
                snackbarHostState.showSnackbar(
                    message = state.message,
                    duration = SnackbarDuration.Long
                )
                viewModel.resetSyncState()
            }
            is SyncState.Success -> {
                // Auto-dismiss after 3 seconds
                delay(3000)
                viewModel.resetSyncState()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("LargeIndexBlog")
                            if (prefixFilter != null) {
                                Text(
                                    text = "Filtered: $prefixFilter",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    },
                    actions = {
                        // Sync button
                        IconButton(
                            onClick = { viewModel.performSync() },
                            enabled = !syncState.isSyncing
                        ) {
                            if (syncState.isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = "Sync"
                                )
                            }
                        }
                        
                        if (prefixFilter != null) {
                            IconButton(onClick = { viewModel.clearFilter() }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear filter"
                                )
                            }
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
                
                // Sync status bar
                AnimatedVisibility(
                    visible = syncState is SyncState.Syncing || syncState is SyncState.Success,
                    enter = slideInVertically() + fadeIn(),
                    exit = slideOutVertically() + fadeOut()
                ) {
                    SyncStatusBar(syncState = syncState)
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddClick,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add blog",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomIndex) {
                val indices = (alphabetIndex as? UiState.Success)?.data
                    ?.map { it.prefix }
                    ?.distinct()
                    ?: emptyList()
                
                if (indices.isNotEmpty()) {
                    BottomIndexBar(
                        indices = indices.take(15), // Limit for bottom bar
                        selectedIndex = prefixFilter,
                        onIndexSelected = { viewModel.filterByPrefix(it) }
                    )
                }
            }
        }
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Alphabet sidebar
            when (val state = alphabetIndex) {
                is UiState.Success -> {
                    AlphabetSidebar(
                        indices = state.data,
                        selectedPrefix = prefixFilter,
                        onPrefixSelected = { viewModel.filterByPrefix(it) }
                    )
                }
                else -> {
                    // Show empty sidebar while loading
                    AlphabetSidebar(
                        indices = emptyList(),
                        selectedPrefix = null,
                        onPrefixSelected = {}
                    )
                }
            }

            // Blog list
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            ) {
                when {
                    pagedBlogs.loadState.refresh is LoadState.Loading -> {
                        LoadingIndicator(
                            message = "Loading blogs...",
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    pagedBlogs.loadState.refresh is LoadState.Error -> {
                        val error = (pagedBlogs.loadState.refresh as LoadState.Error).error
                        ErrorDisplay(
                            message = "Failed to load blogs: ${error.message}",
                            onRetry = { pagedBlogs.retry() },
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    pagedBlogs.itemCount == 0 -> {
                        EmptyState(
                            message = if (prefixFilter != null) {
                                "No blogs starting with '$prefixFilter'"
                            } else {
                                "No blogs yet. Tap + to add one!"
                            },
                            actionLabel = if (prefixFilter != null) "Clear filter" else null,
                            onAction = if (prefixFilter != null) {
                                { viewModel.clearFilter() }
                            } else null,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                count = pagedBlogs.itemCount,
                                key = pagedBlogs.itemKey { it.id },
                                contentType = pagedBlogs.itemContentType { "BlogItem" }
                            ) { index ->
                                val blog = pagedBlogs[index]
                                if (blog != null) {
                                    BlogListItemCard(
                                        blog = blog,
                                        fontSize = fontSize,
                                        onClick = { onBlogClick(blog.id) }
                                    )
                                }
                            }

                            // Loading more indicator
                            if (pagedBlogs.loadState.append is LoadState.Loading) {
                                item {
                                    LoadingIndicator(
                                        message = "Loading more...",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp)
                                    )
                                }
                            }

                            // Error loading more
                            if (pagedBlogs.loadState.append is LoadState.Error) {
                                item {
                                    val error = (pagedBlogs.loadState.append as LoadState.Error).error
                                    ErrorDisplay(
                                        message = "Error: ${error.message}",
                                        onRetry = { pagedBlogs.retry() },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sync status bar showing download/upload/delete counts.
 */
@Composable
private fun SyncStatusBar(syncState: SyncState) {
    val backgroundColor = when (syncState) {
        is SyncState.Success -> MaterialTheme.colorScheme.secondaryContainer
        is SyncState.Syncing -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    
    val textColor = when (syncState) {
        is SyncState.Success -> MaterialTheme.colorScheme.onSecondaryContainer
        is SyncState.Syncing -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (syncState) {
            is SyncState.Syncing -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(16.dp)
                        .padding(end = 8.dp),
                    strokeWidth = 2.dp,
                    color = textColor
                )
                Text(
                    text = "Syncing...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor
                )
            }
            is SyncState.Success -> {
                val result = syncState.result
                Text(
                    text = "Sync complete: ↓${result.downloaded} downloaded  ↑${result.uploaded} uploaded  ✕${result.deleted} deleted",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor
                )
            }
            else -> {}
        }
    }
}

@Composable
private fun BlogListItemCard(
    blog: BlogListItem,
    fontSize: Int,
    onClick: () -> Unit
) {
    val dateFormatter = remember {
        SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clickable(onClick = onClick),
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
                text = blog.title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = fontSize.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = dateFormatter.format(Date(blog.createdAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
