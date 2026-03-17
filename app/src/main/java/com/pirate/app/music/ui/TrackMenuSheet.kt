package com.pirate.app.music.ui

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pirate.app.music.MusicTrack
import com.pirate.app.ui.PirateSheetTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMenuSheet(
  open: Boolean,
  track: MusicTrack?,
  onClose: () -> Unit,
  onSaveForever: ((MusicTrack) -> Unit)? = null,
  onDownload: ((MusicTrack) -> Unit)? = null,
  onShare: ((MusicTrack) -> Unit)? = null,
  onAddToPlaylist: ((MusicTrack) -> Unit)? = null,
  onAddToQueue: ((MusicTrack) -> Unit)? = null,
  onGoToSong: ((MusicTrack) -> Unit)? = null,
  onGoToAlbum: ((MusicTrack) -> Unit)? = null,
  onGoToArtist: ((MusicTrack) -> Unit)? = null,
  showSaveAction: Boolean = true,
  showDownloadAction: Boolean = true,
  showShareAction: Boolean = true,
  saveActionLabel: String = "Save Forever",
  savedActionLabel: String = "Saved Forever",
  downloadLabel: String = "Download",
) {
  if (!open || track == null) return

  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    sheetState = sheetState,
    onDismissRequest = onClose,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      PirateSheetTitle(
        text = track.title,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
      HorizontalDivider()

      val isPermanent = !track.permanentRef.isNullOrBlank()

      if (onSaveForever != null && showSaveAction) {
        if (isPermanent) {
          MenuItemRow(
            icon = { Icon(PhosphorIcons.Regular.Infinity, contentDescription = null) },
            label = savedActionLabel,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = { onClose() },
          )
        } else {
          MenuItemRow(
            icon = { Icon(PhosphorIcons.Regular.Infinity, contentDescription = null) },
            label = saveActionLabel,
            onClick = {
              onSaveForever(track)
              onClose()
            },
          )
        }
      } else if (isPermanent && showSaveAction) {
        MenuItemRow(
          icon = { Icon(PhosphorIcons.Regular.Infinity, contentDescription = null) },
          label = savedActionLabel,
          labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
          onClick = { onClose() },
        )
      }

      if (onDownload != null && showDownloadAction) {
        MenuItemRow(
          icon = { Icon(PhosphorIcons.Regular.Download, contentDescription = null) },
          label = downloadLabel,
          onClick = {
            onDownload(track)
            onClose()
          },
        )
      }

      if (onShare != null && showShareAction) {
        MenuItemRow(
          icon = { Icon(PhosphorIcons.Regular.Share, contentDescription = null) },
          label = "Share",
          onClick = {
            onShare(track)
            onClose()
          },
        )
      }

      if (onAddToPlaylist != null) {
        MenuItemRow(
          icon = { Icon(PhosphorIcons.Regular.Playlist, contentDescription = null) },
          label = "Add to Playlist",
          onClick = {
            onAddToPlaylist(track)
            onClose()
          },
        )
      }

      if (onAddToQueue != null) {
        MenuItemRow(
          icon = { Icon(PhosphorIcons.Regular.Queue, contentDescription = null) },
          label = "Add to Queue",
          onClick = {
            onAddToQueue(track)
            onClose()
          },
        )
      }

      if (onGoToAlbum != null && track.album.isNotBlank()) {
        MenuItemRow(
          icon = { Icon(PhosphorIcons.Regular.VinylRecord, contentDescription = null) },
          label = "Go to Album",
          onClick = {
            onGoToAlbum(track)
            onClose()
          },
        )
      }

      if (onGoToSong != null) {
        MenuItemRow(
          icon = { Icon(PhosphorIcons.Regular.MusicNote, contentDescription = null) },
          label = "Go to Song",
          onClick = {
            onGoToSong(track)
            onClose()
          },
        )
      }

      if (onGoToArtist != null) {
        MenuItemRow(
          icon = { Icon(PhosphorIcons.Regular.User, contentDescription = null) },
          label = "Go to Artist",
          onClick = {
            onGoToArtist(track)
            onClose()
          },
        )
      }

      Spacer(modifier = Modifier.size(4.dp))
    }
  }
}

@Composable
private fun MenuItemRow(
  icon: @Composable () -> Unit,
  label: String,
  labelColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onClick() }
      .padding(vertical = 14.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Spacer(modifier = Modifier.width(6.dp))
    icon()
    Spacer(modifier = Modifier.width(16.dp))
    Text(label, color = labelColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
  }
}
