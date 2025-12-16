package com.example.largeindexblog.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.largeindexblog.ui.components.ErrorDisplay
import com.example.largeindexblog.ui.components.LoadingIndicator
import com.example.largeindexblog.ui.state.UiState
import com.example.largeindexblog.ui.viewmodel.AddBlogViewModel

/**
 * Screen for adding or editing a blog post.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBlogScreen(
    blogId: Long? = null,
    onNavigateBack: () -> Unit,
    viewModel: AddBlogViewModel = hiltViewModel()
) {
    val saveState by viewModel.saveState.collectAsState()
    val loadState by viewModel.loadState.collectAsState()
    
    val snackbarHostState = remember { SnackbarHostState() }
    
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    
    val isEditing = blogId != null && blogId > 0

    // Load existing blog if editing
    LaunchedEffect(blogId) {
        if (isEditing && blogId != null) {
            viewModel.loadBlog(blogId)
        }
    }

    // Handle load state for editing
    LaunchedEffect(loadState) {
        when (val state = loadState) {
            is UiState.Success -> {
                title = state.data.title
                content = state.data.content ?: ""
            }
            is UiState.Error -> {
                snackbarHostState.showSnackbar(
                    message = state.message,
                    duration = SnackbarDuration.Long
                )
            }
            else -> {}
        }
    }

    // Handle save state
    LaunchedEffect(saveState) {
        when (val state = saveState) {
            is UiState.Success -> {
                snackbarHostState.showSnackbar(
                    message = if (isEditing) "Blog updated" else "Blog created",
                    duration = SnackbarDuration.Short
                )
                onNavigateBack()
            }
            is UiState.Error -> {
                snackbarHostState.showSnackbar(
                    message = state.message,
                    duration = SnackbarDuration.Long
                )
                viewModel.resetSaveState()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (isEditing) "Edit Blog" else "Add Blog")
                },
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
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.saveBlog(title, content) },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Save",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                loadState is UiState.Loading -> {
                    LoadingIndicator(
                        message = "Loading blog...",
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                loadState is UiState.Error && isEditing -> {
                    ErrorDisplay(
                        message = (loadState as UiState.Error).message,
                        onRetry = { blogId?.let { viewModel.loadBlog(it) } },
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                saveState is UiState.Loading -> {
                    LoadingIndicator(
                        message = "Saving...",
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Title") },
                            placeholder = { Text("Enter blog title") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = content,
                            onValueChange = { content = it },
                            label = { Text("Content") },
                            placeholder = { Text("Write your blog content here...") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            minLines = 10
                        )
                    }
                }
            }
        }
    }
}
