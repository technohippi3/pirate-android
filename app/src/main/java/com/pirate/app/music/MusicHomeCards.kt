package com.pirate.app.music

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pirate.app.theme.PiratePalette

@Composable
internal fun EntryRow(
  icon: ImageVector,
  iconTint: Color,
  iconBg: Color,
  title: String,
  subtitle: String,
  badge: String?,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .height(72.dp)
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(
      modifier =
        Modifier
          .size(48.dp)
          .padding(0.dp),
      contentAlignment = Alignment.Center,
    ) {
      Surface(
        modifier = Modifier.fillMaxSize(),
        color = iconBg,
        shape = MaterialTheme.shapes.medium,
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(icon, contentDescription = null, tint = iconTint)
        }
      }
    }

    Column(modifier = Modifier.weight(1f)) {
      Text(title, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
      Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (!badge.isNullOrBlank()) {
      Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = MaterialTheme.shapes.extraLarge,
      ) {
        Text(
          badge,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          color = MaterialTheme.colorScheme.onPrimary,
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
        )
      }
    } else {
      Icon(
        PhosphorIcons.Regular.CaretRight,
        contentDescription = null,
        tint = PiratePalette.TextMuted,
      )
    }
  }
}

@Composable
internal fun SectionHeader(
  title: String,
  action: String? = null,
  onAction: (() -> Unit)? = null,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = 14.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      title,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onBackground,
      style = MaterialTheme.typography.titleMedium,
    )
    if (!action.isNullOrBlank() && onAction != null) {
      Text(
        action,
        modifier = Modifier.clickable(onClick = onAction),
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

@Composable
internal fun PlaylistCard(
  playlist: PlaylistDisplayItem,
  onClick: () -> Unit,
) {
  Column(
    modifier =
      Modifier
        .width(140.dp)
        .clickable(onClick = onClick),
  ) {
    Surface(
      modifier = Modifier.size(140.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
      shape = MaterialTheme.shapes.large,
    ) {
      Box(contentAlignment = Alignment.Center) {
        if (!playlist.coverUri.isNullOrBlank()) {
          AsyncImage(
            model = playlist.coverUri,
            contentDescription = "Playlist cover",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          Icon(PhosphorIcons.Regular.MusicNote, contentDescription = null, tint = PiratePalette.TextMuted, modifier = Modifier.size(24.dp))
        }
      }
    }
    Spacer(modifier = Modifier.height(10.dp))
    Text(
      playlist.name,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      color = MaterialTheme.colorScheme.onBackground,
      fontWeight = FontWeight.Medium,
    )
    Text(
      "${playlist.trackCount} track${if (playlist.trackCount == 1) "" else "s"}",
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      color = PiratePalette.TextMuted,
    )
  }
}
