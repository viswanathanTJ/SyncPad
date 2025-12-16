package com.example.largeindexblog.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.largeindexblog.data.model.BlogListItem
import com.example.largeindexblog.ui.components.EmptyState
import com.example.largeindexblog.ui.state.UiState
import com.example.largeindexblog.ui.viewmodel.SearchViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Search screen with title-first search and advanced filters.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBlogClick: (Long) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val searchState by viewModel.searchState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchFilters by viewModel.searchFilters.collectAsState()
    
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var showFiltersSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    // Copy content to clipboard
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
            
            snackbarHostState.showSnackbar(
                message = "Copied to clipboard",
                duration = SnackbarDuration.Short
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Filter button
                    IconButton(onClick = { showFiltersSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = "Advanced filters"
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
        ) {
            // Search input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Search blogs...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearSearch() }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true
            )

            // Active filters indicator
            if (searchFilters.hasActiveFilters()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Filters: ${searchFilters.getFilterSummary()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.clearFilters() }) {
                        Text("Clear filters")
                    }
                }
            }

            // Results
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                when (val state = searchState) {
                    is UiState.Loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    is UiState.Success -> {
                        val results = state.data
                        if (results.isEmpty()) {
                            if (searchQuery.isEmpty()) {
                                EmptyState(
                                    message = "Start typing to search",
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            } else {
                                EmptyState(
                                    message = "No results for '$searchQuery'",
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                item {
                                    Text(
                                        text = "${results.size} results",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                    )
                                }
                                items(results, key = { it.id }) { blog ->
                                    SearchResultCard(
                                        blog = blog,
                                        onClick = { onBlogClick(blog.id) },
                                        onCopyClick = { copyToClipboard(blog.id, blog.title) }
                                    )
                                }
                            }
                        }
                    }
                    is UiState.Error -> {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp)
                        )
                    }
                }
            }
        }

        // Advanced filters bottom sheet
        if (showFiltersSheet) {
            ModalBottomSheet(
                onDismissRequest = { showFiltersSheet = false },
                sheetState = sheetState
            ) {
                AdvancedFiltersContent(
                    filters = searchFilters,
                    onFiltersChanged = { viewModel.setFilters(it) },
                    onDismiss = { showFiltersSheet = false }
                )
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    blog: BlogListItem,
    onClick: () -> Unit,
    onCopyClick: () -> Unit
) {
    val dateFormatter = remember {
        SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = blog.title,
                    style = MaterialTheme.typography.titleMedium,
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
            
            IconButton(onClick = onCopyClick) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy content",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedFiltersContent(
    filters: SearchFilters,
    onFiltersChanged: (SearchFilters) -> Unit,
    onDismiss: () -> Unit
) {
    var includeContent by remember { mutableStateOf(filters.includeContent) }
    var createdAfter by remember { mutableStateOf(filters.createdAfter) }
    var createdBefore by remember { mutableStateOf(filters.createdBefore) }
    var updatedAfter by remember { mutableStateOf(filters.updatedAfter) }
    var updatedBefore by remember { mutableStateOf(filters.updatedBefore) }
    
    var showDatePicker by remember { mutableStateOf<DatePickerType?>(null) }
    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = "Advanced Search",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Include content checkbox
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { includeContent = !includeContent }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = includeContent,
                onCheckedChange = { includeContent = it }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Search in content")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Created date filters
        Text(
            text = "Created Date",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DateFilterButton(
                label = "After",
                date = createdAfter,
                dateFormatter = dateFormatter,
                onClick = { showDatePicker = DatePickerType.CREATED_AFTER },
                onClear = { createdAfter = 0 },
                modifier = Modifier.weight(1f)
            )
            DateFilterButton(
                label = "Before",
                date = createdBefore,
                dateFormatter = dateFormatter,
                onClick = { showDatePicker = DatePickerType.CREATED_BEFORE },
                onClear = { createdBefore = 0 },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Updated date filters
        Text(
            text = "Updated Date",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DateFilterButton(
                label = "After",
                date = updatedAfter,
                dateFormatter = dateFormatter,
                onClick = { showDatePicker = DatePickerType.UPDATED_AFTER },
                onClear = { updatedAfter = 0 },
                modifier = Modifier.weight(1f)
            )
            DateFilterButton(
                label = "Before",
                date = updatedBefore,
                dateFormatter = dateFormatter,
                onClick = { showDatePicker = DatePickerType.UPDATED_BEFORE },
                onClear = { updatedBefore = 0 },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Apply/Cancel buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = {
                onFiltersChanged(
                    SearchFilters(
                        includeContent = includeContent,
                        createdAfter = createdAfter,
                        createdBefore = createdBefore,
                        updatedAfter = updatedAfter,
                        updatedBefore = updatedBefore
                    )
                )
                onDismiss()
            }) {
                Text("Apply")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    // Date picker dialog
    showDatePicker?.let { pickerType ->
        val datePickerState = rememberDatePickerState()
        
        DatePickerDialog(
            onDismissRequest = { showDatePicker = null },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        when (pickerType) {
                            DatePickerType.CREATED_AFTER -> createdAfter = millis
                            DatePickerType.CREATED_BEFORE -> createdBefore = millis
                            DatePickerType.UPDATED_AFTER -> updatedAfter = millis
                            DatePickerType.UPDATED_BEFORE -> updatedBefore = millis
                        }
                    }
                    showDatePicker = null
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = null }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun DateFilterButton(
    label: String,
    date: Long,
    dateFormatter: SimpleDateFormat,
    onClick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = if (date > 0) dateFormatter.format(Date(date)) else "",
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        placeholder = { Text("Any") },
        modifier = modifier.clickable(onClick = onClick),
        trailingIcon = {
            if (date > 0) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                }
            }
        },
        enabled = false
    )
}

private enum class DatePickerType {
    CREATED_AFTER, CREATED_BEFORE, UPDATED_AFTER, UPDATED_BEFORE
}

/**
 * Search filters data class.
 */
data class SearchFilters(
    val includeContent: Boolean = false,
    val createdAfter: Long = 0,
    val createdBefore: Long = 0,
    val updatedAfter: Long = 0,
    val updatedBefore: Long = 0
) {
    fun hasActiveFilters(): Boolean {
        return includeContent || createdAfter > 0 || createdBefore > 0 || 
               updatedAfter > 0 || updatedBefore > 0
    }

    fun getFilterSummary(): String {
        val parts = mutableListOf<String>()
        if (includeContent) parts.add("content")
        if (createdAfter > 0 || createdBefore > 0) parts.add("created date")
        if (updatedAfter > 0 || updatedBefore > 0) parts.add("updated date")
        return parts.joinToString(", ")
    }
}
