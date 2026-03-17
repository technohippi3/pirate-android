package com.pirate.app.music.ui

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import com.pirate.app.ui.PirateIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pirate.app.music.MusicTrack
import com.pirate.app.theme.PiratePalette

@Composable
fun TrackItemRow(
  track: MusicTrack,
  isActive: Boolean,
  isPlaying: Boolean,
  onPress: () -> Unit,
  onMenuPress: () -> Unit,
) {
  // Include artwork URLs in remember keys so rows update when async/refreshed metadata
  // resolves cover art for an existing track id.
  var artworkUri by remember(track.id, track.artworkUri, track.artworkFallbackUri) { mutableStateOf(track.artworkUri) }
  var artworkFailed by remember(track.id, track.artworkUri, track.artworkFallbackUri) { mutableStateOf(false) }

  fun handleArtworkError() {
    if (
      artworkUri == track.artworkUri &&
      !track.artworkFallbackUri.isNullOrBlank() &&
      track.artworkFallbackUri != artworkUri
    ) {
      artworkUri = track.artworkFallbackUri
      return
    }
    artworkFailed = true
  }

  val rowBg = if (isActive) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent
  val titleColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(72.dp)
      .background(rowBg)
      .clickable(onClick = onPress)
      .padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(
      modifier = Modifier
        .size(48.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant),
      contentAlignment = Alignment.Center,
    ) {
      if (!artworkUri.isNullOrBlank() && !artworkFailed) {
        AsyncImage(
          model = artworkUri,
          contentDescription = "Album art",
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
          onError = { handleArtworkError() },
        )
      } else {
        Icon(
          if (isActive && isPlaying) PhosphorIcons.Regular.Equalizer else PhosphorIcons.Regular.MusicNote,
          contentDescription = null,
          tint = if (isActive) MaterialTheme.colorScheme.primary else PiratePalette.TextMuted,
          modifier = Modifier.size(20.dp),
        )
      }
    }

    Column(modifier = Modifier.weight(1f)) {
      Text(
        track.title,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = titleColor,
        fontWeight = FontWeight.Medium,
        style = MaterialTheme.typography.bodyLarge,
      )
      Text(
        track.artist,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = PiratePalette.TextMuted,
        style = MaterialTheme.typography.bodyLarge,
      )
    }

    PirateIconButton(onClick = onMenuPress) {
      Icon(
        PhosphorIcons.Regular.DotsThree,
        contentDescription = "Track menu",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
