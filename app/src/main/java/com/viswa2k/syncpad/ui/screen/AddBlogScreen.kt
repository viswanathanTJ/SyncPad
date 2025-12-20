package com.viswa2k.syncpad.ui.screen

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.viswa2k.syncpad.ui.components.ErrorDisplay
import com.viswa2k.syncpad.ui.components.LoadingIndicator
import com.viswa2k.syncpad.ui.state.UiState
import com.viswa2k.syncpad.ui.viewmodel.AddBlogViewModel

/**
 * Maximum length for blog title.
 */
private const val MAX_TITLE_LENGTH = 200

/**
 * Screen for adding or editing a blog post.
 * Auto-saves on back navigation.
 * Title is auto-populated from content.
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
    val context = LocalContext.current
    
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var hasUnsavedChanges by remember { mutableStateOf(false) }
    
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
                hasUnsavedChanges = false
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
                hasUnsavedChanges = false
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

    // Auto-save on back navigation - only save if content is not empty
    fun saveAndGoBack() {
        if (content.isNotBlank()) {
            // Auto-populate title from content if empty
            val finalTitle = if (title.isBlank()) {
                generateTitleFromContent(content)
            } else {
                title
            }
            viewModel.saveBlog(finalTitle, content)
        } else {
            // Content is empty, don't save, just go back
            onNavigateBack()
        }
    }

    // Handle back button
    BackHandler {
        saveAndGoBack()
    }

    // Paste from clipboard
    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val pastedText = clipData.getItemAt(0).coerceToText(context).toString()
            
            if (pastedText.isNotBlank()) {
                content = pastedText
                hasUnsavedChanges = true
                
                // Auto-generate title from content if title is empty
                if (title.isBlank()) {
                    title = generateTitleFromContent(pastedText)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (isEditing) "Edit Blog" else "Add Blog")
                },
                navigationIcon = {
                    IconButton(onClick = { saveAndGoBack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Save and go back"
                        )
                    }
                },
                actions = {
                    // Paste button
                    IconButton(onClick = { pasteFromClipboard() }) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Paste from clipboard"
                        )
                    }
                    // Cancel button with X icon and text - discard changes and go back
                    TextButton(onClick = { onNavigateBack() }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cancel")
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
                        // Title field (auto-populated) with character count
                        OutlinedTextField(
                            value = title,
                            onValueChange = { newTitle ->
                                title = cleanTitle(newTitle).take(MAX_TITLE_LENGTH)
                                hasUnsavedChanges = true
                            },
                            label = { Text("Title (auto-generated)") },
                            placeholder = { Text("Will be generated from content") },
                            singleLine = true,
                            supportingText = {
                                Text("${title.length}/$MAX_TITLE_LENGTH")
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = content,
                            onValueChange = { newContent ->
                                content = newContent
                                hasUnsavedChanges = true
                                
                                // Auto-update title from content - clear title if content is empty
                                title = if (newContent.isNotBlank()) {
                                    generateTitleFromContent(newContent)
                                } else {
                                    ""
                                }
                            },
                            label = { Text("Content") },
                            placeholder = { Text("Start writing...") },
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

/**
 * Generate title from content.
 * Takes the first line or first 200 characters, removes emojis and invalid characters.
 */
private fun generateTitleFromContent(content: String): String {
    val trimmedContent = content.trim()
    
    // Get first line or first 200 chars
    val firstLine = trimmedContent.lines().firstOrNull()?.trim() ?: ""
    val rawTitle = if (firstLine.length > MAX_TITLE_LENGTH) {
        firstLine.take(MAX_TITLE_LENGTH)
    } else if (firstLine.isBlank()) {
        trimmedContent.take(MAX_TITLE_LENGTH)
    } else {
        firstLine
    }
    
    return cleanTitle(rawTitle)
}

/**
 * Clean title by removing emojis and invalid characters.
 * Keeps letters (including Tamil/Unicode), numbers, spaces, basic punctuation.
 */
private fun cleanTitle(title: String): String {
    return title
        .replace(Regex("[\\p{So}\\p{Sk}]"), "") // Symbols, modifiers
        .replace(Regex("[\\p{Cc}\\p{Cf}]"), "") // Control chars
        .replace(Regex("\\s+"), " ") // Normalize whitespace
        .trim()
        .take(MAX_TITLE_LENGTH)
}
