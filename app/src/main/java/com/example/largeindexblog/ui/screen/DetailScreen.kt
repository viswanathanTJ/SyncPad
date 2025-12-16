package com.example.largeindexblog.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.largeindexblog.ui.components.ErrorDisplay
import com.example.largeindexblog.ui.components.LoadingIndicator
import com.example.largeindexblog.ui.state.UiState
import com.example.largeindexblog.ui.viewmodel.BlogDetailViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Detail screen for viewing blog content.
 * Loads content by ID only - content is never loaded in the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onNavigateBack: () -> Unit,
    onEditClick: (Long) -> Unit,
    viewModel: BlogDetailViewModel = hiltViewModel()
) {
    val blogState by viewModel.blogState.collectAsState()
    val deleteState by viewModel.deleteState.collectAsState()
    
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Copy to clipboard function
    fun copyToClipboard() {
        val state = blogState
        if (state is UiState.Success) {
            val blog = state.data
            val textToCopy = if (!blog.content.isNullOrBlank()) {
                "${blog.title}\n\n${blog.content}"
            } else {
                blog.title
            }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("SyncPad Note", textToCopy)
            clipboard.setPrimaryClip(clip)
        }
    }

    // Handle delete state
    LaunchedEffect(deleteState) {
        when (val state = deleteState) {
            is UiState.Success -> {
                snackbarHostState.showSnackbar(
                    message = "Blog deleted",
                    duration = SnackbarDuration.Short
                )
                onNavigateBack()
            }
            is UiState.Error -> {
                snackbarHostState.showSnackbar(
                    message = state.message,
                    duration = SnackbarDuration.Long
                )
                viewModel.resetDeleteState()
            }
            else -> {}
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Blog") },
            text = { Text("Are you sure you want to delete this blog?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteBlog()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (val state = blogState) {
                            is UiState.Success -> state.data.title
                            else -> "Blog"
                        },
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (blogState is UiState.Success) {
                        val blogId = (blogState as UiState.Success).data.id
                        // Copy button
                        IconButton(onClick = { 
                            copyToClipboard()
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Copied to clipboard",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy"
                            )
                        }
                        IconButton(onClick = { onEditClick(blogId) }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit"
                            )
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val state = blogState) {
                is UiState.Loading -> {
                    LoadingIndicator(
                        message = "Loading blog...",
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is UiState.Error -> {
                    ErrorDisplay(
                        message = state.message,
                        onRetry = { viewModel.loadBlog() },
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is UiState.Success -> {
                    val blog = state.data
                    val dateFormatter = remember {
                        SimpleDateFormat("MMMM dd, yyyy 'at' hh:mm a", Locale.getDefault())
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        // Title
                        Text(
                            text = blog.title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // Date
                        Text(
                            text = "Created: ${dateFormatter.format(Date(blog.createdAt))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )

                        if (blog.updatedAt != blog.createdAt) {
                            Text(
                                text = "Updated: ${dateFormatter.format(Date(blog.updatedAt))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Content
                        if (!blog.content.isNullOrBlank()) {
                            Text(
                                text = blog.content,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 24.dp)
                            )
                        } else {
                            Text(
                                text = "No content",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
