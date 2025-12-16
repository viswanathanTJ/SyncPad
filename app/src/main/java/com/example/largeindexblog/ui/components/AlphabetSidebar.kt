package com.example.largeindexblog.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.largeindexblog.data.entity.PrefixIndexEntity
import com.example.largeindexblog.ui.theme.AlphabetActiveColor
import com.example.largeindexblog.ui.theme.AlphabetInactiveColor

/**
 * Alphabet sidebar for quick navigation.
 * Displays ALL available prefixes from the prefix index.
 * Fully scrollable to handle large numbers of entries.
 */
@Composable
fun AlphabetSidebar(
    indices: List<PrefixIndexEntity>,
    selectedPrefix: String?,
    onPrefixSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Sort indices alphabetically by prefix
    val sortedIndices = indices.sortedBy { it.prefix }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxHeight()
            .width(48.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(
            items = sortedIndices,
            key = { "${it.prefix}_${it.depth}" }
        ) { indexEntry ->
            val isSelected = selectedPrefix == indexEntry.prefix

            IndexItem(
                prefix = indexEntry.prefix,
                count = indexEntry.count,
                isSelected = isSelected,
                onClick = { onPrefixSelected(indexEntry.prefix) }
            )
        }
    }
}

@Composable
private fun IndexItem(
    prefix: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val textColor = if (isSelected) {
        AlphabetActiveColor
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f)
    }

    Box(
        modifier = Modifier
            .width(48.dp)
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = prefix,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = fontWeight
            ),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
