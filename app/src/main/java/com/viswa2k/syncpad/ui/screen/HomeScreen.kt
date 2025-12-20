package com.viswa2k.syncpad.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.viswa2k.syncpad.data.model.BlogListItem
import com.viswa2k.syncpad.ui.components.AlphabetSidebar
import com.viswa2k.syncpad.ui.components.BottomIndexBar
import com.viswa2k.syncpad.ui.components.EmptyState
import com.viswa2k.syncpad.ui.components.ErrorDisplay
import com.viswa2k.syncpad.ui.components.HierarchicalIndexSidebar
import com.viswa2k.syncpad.ui.components.LoadingIndicator
import com.viswa2k.syncpad.ui.components.QuickNavigationPopup
import com.viswa2k.syncpad.ui.state.IndexState
import com.viswa2k.syncpad.ui.state.SyncState
import com.viswa2k.syncpad.ui.state.UiState
import com.viswa2k.syncpad.ui.viewmodel.BlogListViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    onSearchClick: () -> Unit,
    viewModel: BlogListViewModel = hiltViewModel()
) {
    val pagedBlogs = viewModel.pagedBlogs.collectAsLazyPagingItems()
    val alphabetIndex by viewModel.alphabetIndex.collectAsState()
    val prefixFilter by viewModel.prefixFilter.collectAsState()
    val blogCount by viewModel.blogCount.collectAsState()
    val showBottomIndex by viewModel.showBottomIndex.collectAsState()
    val fontSize by viewModel.fontSize.collectAsState()
    val maxDepth by viewModel.maxDepth.collectAsState()
    val indexState by viewModel.indexState.collectAsState()
    val syncState by viewModel.syncState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Auto-sync only on first install (when never synced before)
    var hasAutoSynced by remember { mutableStateOf(false) }
    
    // Quick navigation popup state
    var showQuickNav by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!hasAutoSynced) {
            hasAutoSynced = true
            viewModel.performAutoSync()
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

    // Handle sync state changes - auto-dismiss for non-manual sync
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
                // Auto-dismiss for automatic syncs after 3 seconds
                if (!state.isManual) {
                    kotlinx.coroutines.delay(3000)
                    viewModel.resetSyncState()
                }
                // Manual sync stays until user closes it
            }
            else -> {}
        }
    }

    // Copy content to clipboard
    // Note: Android 13+ automatically shows a visual confirmation when copying to clipboard
    fun copyToClipboard(blogId: Long, title: String) {
        scope.launch {
            val content = viewModel.getBlogContent(blogId)
            val textToCopy = if (content.isNullOrBlank()) {
                title
            } else {
                "$title\n\n$content"
            }
            
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Blog Content", textToCopy)
            clipboard.setPrimaryClip(clip)
            // No snackbar needed - Android 13+ shows system confirmation automatically
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("SyncPad")
                            when {
                                syncState is SyncState.Syncing -> {
                                    val syncing = syncState as SyncState.Syncing
                                    Text(
                                        text = syncing.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                prefixFilter != null -> {
                                    Text(
                                        text = "Filtered: $prefixFilter",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        // Quick navigation button
                        IconButton(onClick = { showQuickNav = true }) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Quick navigation"
                            )
                        }
                        
                        // Search button
                        IconButton(onClick = onSearchClick) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search"
                            )
                        }
                        
                        // Sync button
                        IconButton(
                            onClick = { viewModel.performSync() },
                            enabled = !syncState.isSyncing
                        ) {
                            if (syncState.isSyncing) {
                                // Rotating sync icon
                                val infiniteTransition = rememberInfiniteTransition(label = "sync")
                                val rotation by infiniteTransition.animateFloat(
                                    initialValue = 0f,
                                    targetValue = 360f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1000, easing = LinearEasing),
                                        repeatMode = RepeatMode.Restart
                                    ),
                                    label = "syncRotation"
                                )
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = "Syncing",
                                    modifier = Modifier.graphicsLayer { rotationZ = rotation }
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
                
                // Sync status bar - only show success result (syncing shows rotating icon)
                AnimatedVisibility(
                    visible = syncState is SyncState.Success,
                    enter = slideInVertically() + fadeIn(),
                    exit = slideOutVertically() + fadeOut()
                ) {
                    SyncStatusBar(
                        syncState = syncState,
                        onClose = { viewModel.resetSyncState() }
                    )
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
            // Hierarchical alphabet sidebar with drill-down popups
            when (val state = alphabetIndex) {
                is UiState.Success -> {
                    HierarchicalIndexSidebar(
                        indices = state.data,
                        maxDepth = maxDepth,
                        onPrefixSelected = { viewModel.filterByPrefix(it) },
                        onGetChildCounts = { parentPrefix -> 
                            viewModel.getChildPrefixCounts(parentPrefix) 
                        }
                    )
                }
                else -> {
                    // Show empty sidebar while loading
                    HierarchicalIndexSidebar(
                        indices = emptyList(),
                        maxDepth = maxDepth,
                        onPrefixSelected = {},
                        onGetChildCounts = { emptyMap() }
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
                        // State for popup drill-down from section header
                        var popupPrefix by remember { mutableStateOf<String?>(null) }
                        val coroutineScope = rememberCoroutineScope()
                        
                        // Current filter section prefix (use filter or first char of blogs)
                        val sectionPrefix = prefixFilter ?: ""
                        var headerShown by remember(sectionPrefix) { mutableStateOf(false) }
                        
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Show section header at top when filtered
                            if (sectionPrefix.isNotEmpty()) {
                                item(key = "section_header_$sectionPrefix") {
                                    // Use cached count from index as initial value (no flicker)
                                    val cachedCount = (alphabetIndex as? UiState.Success)?.data
                                        ?.find { it.prefix == sectionPrefix }?.count ?: 0
                                    var actualCount by remember(sectionPrefix) { mutableStateOf(cachedCount) }
                                    
                                    // Optionally refresh from DB if needed (for deep prefixes not in index)
                                    if (cachedCount == 0 && sectionPrefix.length > 1) {
                                        LaunchedEffect(sectionPrefix) {
                                            actualCount = viewModel.getCountByPrefix(sectionPrefix)
                                        }
                                    }
                                    
                                    SectionHeader(
                                        prefix = sectionPrefix,
                                        count = actualCount,
                                        canDrillDown = sectionPrefix.length < maxDepth && actualCount > 50,
                                        onClick = {
                                            if (sectionPrefix.length < maxDepth && actualCount > 50) {
                                                popupPrefix = sectionPrefix
                                            }
                                        }
                                    )
                                }
                            }
                            
                            // Track current section for headers in list
                            var currentSectionChar: String? = null
                            
                            items(
                                count = pagedBlogs.itemCount,
                                key = pagedBlogs.itemKey { it.id },
                                contentType = { "BlogItem" }
                            ) { index ->
                                val blog = pagedBlogs[index]
                                if (blog != null) {
                                    // Show section headers when NOT filtered (no prefix filter)
                                    if (sectionPrefix.isEmpty()) {
                                        val firstChar = blog.title.firstOrNull()
                                            ?.uppercaseChar()?.toString() ?: "#"
                                        
                                        if (firstChar != currentSectionChar) {
                                            currentSectionChar = firstChar
                                            // Get count from alphabet index
                                            val charCount = (alphabetIndex as? UiState.Success)?.data
                                                ?.find { it.prefix == firstChar && it.depth == 1 }?.count ?: 0
                                            
                                            SectionHeader(
                                                prefix = firstChar,
                                                count = charCount,
                                                canDrillDown = maxDepth > 1 && charCount > 50,
                                                onClick = { 
                                                    if (charCount > 50) {
                                                        popupPrefix = firstChar
                                                    }
                                                }
                                            )
                                        }
                                    }
                                    
                                    BlogListItemCard(
                                        blog = blog,
                                        fontSize = fontSize,
                                        onClick = { onBlogClick(blog.id) },
                                        onCopyClick = { copyToClipboard(blog.id, blog.title) }
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
                        
                        // Popup for section header drill-down
                        popupPrefix?.let { prefix ->
                            DrillDownPopupFromHeader(
                                parentPrefix = prefix,
                                maxDepth = maxDepth,
                                onGetChildCounts = { viewModel.getChildPrefixCounts(it) },
                                onPrefixSelected = { selectedPrefix ->
                                    viewModel.filterByPrefix(selectedPrefix)
                                    popupPrefix = null
                                },
                                onDismiss = { popupPrefix = null }
                            )
                        }
                    }
                }
            }
        }
    }
    
    // Quick navigation popup
    if (showQuickNav) {
        val indices = (alphabetIndex as? UiState.Success)?.data ?: emptyList()
        QuickNavigationPopup(
            indices = indices,
            onPrefixSelected = { viewModel.filterByPrefix(it) },
            onDismiss = { showQuickNav = false }
        )
    }
}

/**
 * Sync status bar showing download/upload/delete counts with close button.
 */
@Composable
private fun SyncStatusBar(
    syncState: SyncState,
    onClose: () -> Unit
) {
    val backgroundColor = MaterialTheme.colorScheme.secondaryContainer
    val textColor = MaterialTheme.colorScheme.onSecondaryContainer

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (syncState is SyncState.Success) {
            val result = syncState.result
            Text(
                text = "↓${result.downloaded}  ↑${result.uploaded}  ✕${result.deleted}",
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "✕",
                style = MaterialTheme.typography.titleMedium,
                color = textColor,
                modifier = Modifier
                    .clickable(onClick = onClose)
                    .padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun BlogListItemCard(
    blog: BlogListItem,
    fontSize: Int,
    onClick: () -> Unit,
    onCopyClick: () -> Unit
) {
    val dateFormatter = remember {
        SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    }
    var isCopying by remember { mutableStateOf(false) }

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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Blog info
            Column(
                modifier = Modifier.weight(1f)
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
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Copy button
            IconButton(
                onClick = {
                    isCopying = true
                    onCopyClick()
                }
            ) {
                if (isCopying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    // Reset after a short delay
                    LaunchedEffect(Unit) {
                        delay(1000)
                        isCopying = false
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy content",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * Section header showing a prefix and count of blogs.
 */
@Composable
private fun SectionHeader(
    prefix: String,
    count: Int,
    canDrillDown: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (onClick != null && canDrillDown) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = prefix,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (canDrillDown) {
                Text(
                    text = " ▸",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(
            text = "$count items",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Popup dialog for drilling down from section header.
 * Uses dynamic database queries for real counts.
 */
@Composable
private fun DrillDownPopupFromHeader(
    parentPrefix: String,
    maxDepth: Int,
    onGetChildCounts: suspend (String) -> Map<String, Int>,
    onPrefixSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val nextDepth = parentPrefix.length + 1
    val canDrillDeeper = nextDepth < maxDepth
    
    // State for loading and drilling
    var isLoading by remember { mutableStateOf(true) }
    var childCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var currentPrefix by remember { mutableStateOf(parentPrefix) }
    
    // Load child counts from database
    LaunchedEffect(currentPrefix) {
        isLoading = true
        childCounts = onGetChildCounts(currentPrefix)
        isLoading = false
    }
    
    val currentDepth = currentPrefix.length + 1
    // Show actual child prefixes from the data (not just A-Z)
    val displayItems = remember(childCounts, currentPrefix) {
        childCounts.entries
            .filter { it.value > 0 }
            .sortedBy { it.key }
            .map { (prefix, count) ->
                Triple(prefix, count, currentDepth < maxDepth)
            }
    }
    
    val totalWithItems = displayItems.size

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back button if not at parent level
                    if (currentPrefix.length > parentPrefix.length) {
                        Text(
                            text = "← ${currentPrefix.dropLast(1)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { currentPrefix = currentPrefix.dropLast(1) }
                                .padding(8.dp)
                        )
                    } else {
                        Spacer(modifier = Modifier.width(60.dp))
                    }
                    
                    Text(
                        text = currentPrefix,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    
                    Text(
                        text = "✕",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable(onClick = onDismiss)
                            .padding(8.dp)
                    )
                }

                Text(
                    text = "Depth $currentDepth / $maxDepth • $totalWithItems with items",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Spacer(modifier = Modifier.size(8.dp))

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .size(350.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    // Grid of next-level prefixes
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(4),
                        modifier = Modifier.size(350.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(displayItems.size) { index ->
                            val (prefix, count, hasChildren) = displayItems[index]
                            val hasItems = count > 0
                            
                            androidx.compose.material3.Surface(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                color = if (hasItems) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                }
                            ) {
                                Column(
                                    modifier = Modifier.padding(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = prefix.takeLast(2),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (hasItems) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        },
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                    )
                                    
                                    // Count - clickable to select
                                    Text(
                                        text = if (hasItems) count.toString() else "-",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (hasItems) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                        },
                                        modifier = if (hasItems) {
                                            Modifier
                                                .clickable { onPrefixSelected(prefix) }
                                                .padding(4.dp)
                                        } else {
                                            Modifier.padding(4.dp)
                                        }
                                    )
                                    
                                    // Drill deeper button
                                    if (hasChildren) {
                                        Text(
                                            text = "▶",
                                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clickable { currentPrefix = prefix }
                                                .padding(6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.size(8.dp))

                Text(
                    text = "Tap count to filter • Tap ▸ to drill deeper",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

