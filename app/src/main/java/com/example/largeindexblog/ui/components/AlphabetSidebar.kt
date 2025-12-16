package com.example.largeindexblog.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.largeindexblog.data.entity.PrefixIndexEntity
import com.example.largeindexblog.ui.theme.AlphabetActiveColor
import com.example.largeindexblog.ui.theme.AlphabetInactiveColor

/**
 * Alphabet sidebar for quick navigation.
 * Displays available letters from the prefix index.
 */
@Composable
fun AlphabetSidebar(
    indices: List<PrefixIndexEntity>,
    selectedPrefix: String?,
    onPrefixSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Get unique first letters from indices
    val availableLetters = indices
        .map { it.prefix.firstOrNull()?.toString() ?: "" }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()

    // All possible letters
    val allLetters = ('A'..'Z').map { it.toString() } + listOf("#")

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(32.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        allLetters.forEach { letter ->
            val isAvailable = availableLetters.contains(letter) || 
                (letter == "#" && availableLetters.any { it.first().isDigit() })
            val isSelected = selectedPrefix?.firstOrNull()?.toString()?.uppercase() == letter

            AlphabetItem(
                letter = letter,
                isAvailable = isAvailable,
                isSelected = isSelected,
                onClick = {
                    if (isAvailable) {
                        onPrefixSelected(letter)
                    }
                }
            )
        }
    }
}

@Composable
private fun AlphabetItem(
    letter: String,
    isAvailable: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val textColor = when {
        isSelected -> AlphabetActiveColor
        isAvailable -> MaterialTheme.colorScheme.onSurface
        else -> AlphabetInactiveColor.copy(alpha = 0.4f)
    }

    val fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal

    Box(
        modifier = Modifier
            .clickable(enabled = isAvailable, onClick = onClick)
            .padding(vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = letter,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = fontWeight
            ),
            color = textColor
        )
    }
}
