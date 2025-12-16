package com.viswa2k.syncpad.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.viswa2k.syncpad.data.entity.PrefixIndexEntity
import com.viswa2k.syncpad.ui.theme.AlphabetActiveColor

/**
 * Hierarchical alphabet sidebar with drill-down navigation.
 * 
 * Uses the prefix_index table for sidebar, and dynamically queries
 * real counts from the database for popup drill-down.
 */
@Composable
fun HierarchicalIndexSidebar(
    indices: List<PrefixIndexEntity>,
    maxDepth: Int,
    onPrefixSelected: (String) -> Unit,
    onGetChildCounts: suspend (String) -> Map<String, Int>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    
    // Get depth=1 prefixes (single characters) for sidebar
    val level1Prefixes = remember(indices) {
        indices.filter { it.depth == 1 }.sortedBy { it.prefix }
    }

    // State for popup navigation (on long press)
    var currentPopupPrefix: String? by remember { mutableStateOf(null) }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxHeight()
            .width(52.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(
            items = level1Prefixes,
            key = { "${it.prefix}_${it.depth}" }
        ) { indexEntry ->
            HierarchicalIndexItem(
                prefix = indexEntry.prefix,
                count = indexEntry.count,
                onClick = {
                    // Tap: scroll to section
                    onPrefixSelected(indexEntry.prefix)
                },
                onLongClick = {
                    // Long press: show popup if > 50 items
                    if (indexEntry.count > 50) {
                        currentPopupPrefix = indexEntry.prefix
                    } else {
                        // Not enough items for drill-down, just scroll
                        onPrefixSelected(indexEntry.prefix)
                    }
                }
            )
        }
    }

    // Show drill-down popup on long press
    currentPopupPrefix?.let { prefix ->
        DrillDownPopup(
            parentPrefix = prefix,
            maxDepth = maxDepth,
            onGetChildCounts = onGetChildCounts,
            onPrefixSelected = { selectedPrefix ->
                onPrefixSelected(selectedPrefix)
                currentPopupPrefix = null
            },
            onDismiss = {
                currentPopupPrefix = null
            },
            onDrillDeeper = { newPrefix ->
                currentPopupPrefix = newPrefix
            }
        )
    }
}

/**
 * Single item in the hierarchical sidebar.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HierarchicalIndexItem(
    prefix: String,
    count: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .width(52.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = prefix,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            ),
            color = AlphabetActiveColor,
            textAlign = TextAlign.Center
        )
        // Count hidden per user request - only show prefix
    }
}

/**
 * Popup dialog for drill-down navigation.
 * Dynamically loads real counts from database.
 */
@Composable
private fun DrillDownPopup(
    parentPrefix: String,
    maxDepth: Int,
    onGetChildCounts: suspend (String) -> Map<String, Int>,
    onPrefixSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    onDrillDeeper: (String) -> Unit
) {
    val nextDepth = parentPrefix.length + 1
    val canDrillDeeper = nextDepth < maxDepth
    
    // State for loading child counts
    var isLoading by remember { mutableStateOf(true) }
    var childCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    
    // Load child counts from database
    LaunchedEffect(parentPrefix) {
        isLoading = true
        childCounts = onGetChildCounts(parentPrefix)
        isLoading = false
    }
    
    // Generate display items from actual counts - ONLY show items with count > 0
    val displayItems = remember(childCounts, parentPrefix) {
        ('A'..'Z').mapNotNull { char ->
            val nextPrefix = parentPrefix + char
            val count = childCounts[nextPrefix] ?: 0
            if (count > 0) {
                DisplayItem(
                    prefix = nextPrefix,
                    count = count,
                    hasChildren = canDrillDeeper && count > 0
                )
            } else null
        }
    }
    
    val totalWithItems = displayItems.size

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp),
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
                    // Back button if not at first level
                    if (parentPrefix.length > 1) {
                        Text(
                            text = "← ${parentPrefix.dropLast(1)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { onDrillDeeper(parentPrefix.dropLast(1)) }
                                .padding(8.dp)
                        )
                    } else {
                        Spacer(modifier = Modifier.width(60.dp))
                    }
                    
                    Text(
                        text = parentPrefix,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
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
                    text = "Depth $nextDepth / $maxDepth • $totalWithItems with items",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(350.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    // Grid of next-level prefixes
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.height(350.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items = displayItems,
                            key = { it.prefix }
                        ) { item ->
                            DrillDownGridItem(
                                displayPrefix = item.prefix.takeLast(2),
                                fullPrefix = item.prefix,
                                count = item.count,
                                hasChildren = item.hasChildren,
                                onClick = {
                                    if (item.count > 0) {
                                        onPrefixSelected(item.prefix)
                                    }
                                },
                                onDrillClick = {
                                    if (item.hasChildren) {
                                        onDrillDeeper(item.prefix)
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Instructions
                Text(
                    text = "Tap count to select • Tap ▸ to drill deeper",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Data class for popup display items.
 */
private data class DisplayItem(
    val prefix: String,
    val count: Int,
    val hasChildren: Boolean
)

/**
 * Grid item in the drill-down popup.
 */
@Composable
private fun DrillDownGridItem(
    displayPrefix: String,
    fullPrefix: String,
    count: Int,
    hasChildren: Boolean,
    onClick: () -> Unit,
    onDrillClick: () -> Unit
) {
    val hasItems = count > 0
    
    Surface(
        shape = RoundedCornerShape(8.dp),
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
                text = displayPrefix,
                style = MaterialTheme.typography.titleMedium,
                color = if (hasItems) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
                fontWeight = FontWeight.Bold
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
                        .clickable(onClick = onClick)
                        .padding(4.dp)
                } else {
                    Modifier.padding(4.dp)
                }
            )
            
            // Drill deeper button
            if (hasChildren) {
                Text(
                    text = "▸",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onDrillClick)
                        .padding(2.dp)
                )
            }
        }
    }
}
