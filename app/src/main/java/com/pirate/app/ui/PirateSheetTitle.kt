package com.pirate.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun PirateSheetTitle(
  text: String,
  modifier: Modifier = Modifier,
  color: Color = MaterialTheme.colorScheme.onSurface,
  maxLines: Int = Int.MAX_VALUE,
  overflow: TextOverflow = TextOverflow.Clip,
) {
  Text(
    text = text,
    modifier = modifier,
    style = MaterialTheme.typography.titleLarge,
    fontWeight = FontWeight.SemiBold,
    color = color,
    maxLines = maxLines,
    overflow = overflow,
  )
}
