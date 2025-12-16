package com.example.largeindexblog.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.largeindexblog.data.entity.PrefixIndexEntity
import com.example.largeindexblog.ui.theme.AlphabetActiveColor

/**
 * Hierarchical alphabet sidebar with drill-down navigation.
 * 
 * Uses the prefix_index table which has:
 * - prefix: the prefix string (A, AA, AAA, etc.)
 * - depth: length of the prefix (1, 2, 3, etc.)
 * - count: number of blogs with this prefix
 * - first_blog_id: ID of first blog for navigation
 * 
 * Shows first-level (depth=1) in sidebar, popup shows next level on click.
 */
@Composable
fun HierarchicalIndexSidebar(
    indices: List<PrefixIndexEntity>,
    maxDepth: Int,
    onPrefixSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    
    // Group indices by depth for efficient lookup
    val indicesByDepth = remember(indices) {
        indices.groupBy { it.depth }
    }
    
    // Get depth=1 prefixes (single characters) for sidebar
    val level1Prefixes = remember(indicesByDepth) {
        (indicesByDepth[1] ?: emptyList())
            .sortedBy { it.prefix }
    }

    // State for popup navigation
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
            // Check if this prefix has children at next depth
            val hasChildren = indicesByDepth[2]?.any { 
                it.prefix.startsWith(indexEntry.prefix) 
            } ?: false
            
            HierarchicalIndexItem(
                prefix = indexEntry.prefix,
                count = indexEntry.count,
                hasChildren = hasChildren,
                onClick = {
                    // Open popup for drilling down
                    currentPopupPrefix = indexEntry.prefix
                }
            )
        }
    }

    // Show drill-down popup when a prefix is clicked
    currentPopupPrefix?.let { prefix ->
        DrillDownPopup(
            parentPrefix = prefix,
            indicesByDepth = indicesByDepth,
            maxDepth = maxDepth,
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
@Composable
private fun HierarchicalIndexItem(
    prefix: String,
    count: Int,
    hasChildren: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = prefix,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            ),
            color = if (hasChildren) AlphabetActiveColor else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Popup dialog for drill-down navigation.
 * Uses prefix_index data grouped by depth.
 */
@Composable
private fun DrillDownPopup(
    parentPrefix: String,
    indicesByDepth: Map<Int, List<PrefixIndexEntity>>,
    maxDepth: Int,
    onPrefixSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    onDrillDeeper: (String) -> Unit
) {
    val nextDepth = parentPrefix.length + 1
    val canDrillDeeper = nextDepth < maxDepth
    
    // Get children at next depth that start with parent prefix
    val childrenFromIndex = indicesByDepth[nextDepth]
        ?.filter { it.prefix.startsWith(parentPrefix) }
        ?.sortedBy { it.prefix }
        ?: emptyList()
    
    // Create display items: use index data OR generate A-Z with 0 counts
    val displayItems = if (childrenFromIndex.isNotEmpty()) {
        // Use actual index data
        childrenFromIndex.map { 
            DisplayItem(
                prefix = it.prefix,
                count = it.count,
                hasChildren = indicesByDepth[nextDepth + 1]?.any { child -> 
                    child.prefix.startsWith(it.prefix) 
                } ?: false
            )
        }
    } else {
        // Generate all possible next-level prefixes with counts from index
        ('A'..'Z').map { char ->
            val nextPrefix = parentPrefix + char
            // Look up count from any depth that starts with this prefix
            val count = indicesByDepth.values.flatten()
                .filter { it.prefix.startsWith(nextPrefix) }
                .maxOfOrNull { if (it.prefix == nextPrefix) it.count else 0 } ?: 0
            
            DisplayItem(
                prefix = nextPrefix,
                count = count,
                hasChildren = canDrillDeeper && count > 0
            )
        }
    }

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
                    text = "Depth $nextDepth / $maxDepth • ${childrenFromIndex.size} indexed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

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
