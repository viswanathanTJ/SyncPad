package com.viswa2k.syncpad.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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

/**
 * Quick navigation popup with large, easy-to-tap letter grid.
 * Opens centered on screen for easier navigation than small sidebar icons.
 * Long press on letters with > 50 items opens drill-down popup.
 */
@Composable
fun QuickNavigationPopup(
    indices: List<PrefixIndexEntity>,
    maxDepth: Int = 3,
    onPrefixSelected: (String) -> Unit,
    onGetChildCounts: suspend (String) -> Map<String, Int> = { emptyMap() },
    onDismiss: () -> Unit
) {
    // Get depth=1 prefixes (single letters) for quick navigation
    val level1Prefixes = remember(indices) {
        indices.filter { it.depth == 1 }.sortedBy { it.prefix }
    }
    
    // State for drill-down popup
    var drillDownPrefix by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Jump to",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Text(
                        text = "✕",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable(onClick = onDismiss)
                            .padding(8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (level1Prefixes.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No entries yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // Large grid of letters - 5 columns for bigger touch targets
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier.height(400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = level1Prefixes,
                            key = { it.prefix }
                        ) { indexEntry ->
                            QuickNavGridItem(
                                prefix = indexEntry.prefix,
                                count = indexEntry.count,
                                canDrillDown = indexEntry.count > 50 && maxDepth > 1,
                                onClick = {
                                    onPrefixSelected(indexEntry.prefix)
                                    onDismiss()
                                },
                                onLongClick = {
                                    if (indexEntry.count > 50 && maxDepth > 1) {
                                        drillDownPrefix = indexEntry.prefix
                                    } else {
                                        onPrefixSelected(indexEntry.prefix)
                                        onDismiss()
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Tap to jump • Long press to drill down",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
    
    // Drill-down popup - uses the same shared component as the sidebar
    drillDownPrefix?.let { prefix ->
        DrillDownPopup(
            parentPrefix = prefix,
            maxDepth = maxDepth,
            onGetChildCounts = onGetChildCounts,
            onPrefixSelected = { selectedPrefix ->
                onPrefixSelected(selectedPrefix)
                drillDownPrefix = null
                onDismiss()
            },
            onDismiss = { drillDownPrefix = null },
            onDrillDeeper = { newPrefix ->
                drillDownPrefix = newPrefix
            }
        )
    }
}

/**
 * Large grid item for quick navigation initial letter selection.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickNavGridItem(
    prefix: String,
    count: Int,
    canDrillDown: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = onClick
) {
    Surface(
        modifier = Modifier
            .size(56.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = prefix,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 22.sp
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (count > 999) "999+" else count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp
                ),
                color = if (canDrillDown) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}
