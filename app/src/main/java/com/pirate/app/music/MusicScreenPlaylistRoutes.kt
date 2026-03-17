package com.pirate.app.music

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import com.pirate.app.ui.PirateIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import com.pirate.app.ui.PirateTextButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.pirate.app.ui.PirateMobileHeader
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun PlaylistsRoute(
  loading: Boolean,
  playlists: List<PlaylistDisplayItem>,
  onBack: () -> Unit,
  onRefreshPlaylists: () -> Unit,
  onCreatePlaylist: () -> Unit,
  onOpenPlaylist: (PlaylistDisplayItem) -> Unit,
) {
  PirateMobileHeader(
    title = "Playlists",
    onBackPress = onBack,
    rightSlot = {
      Row {
        PirateIconButton(
          enabled = !loading,
          onClick = onRefreshPlaylists,
        ) {
          Icon(
            PhosphorIcons.Regular.ArrowClockwise,
            contentDescription = "Refresh playlists",
            tint = MaterialTheme.colorScheme.onBackground,
          )
        }
        PirateIconButton(onClick = onCreatePlaylist) {
          Icon(
            PhosphorIcons.Regular.Plus,
            contentDescription = "Create playlist",
            tint = MaterialTheme.colorScheme.onBackground,
          )
        }
      }
    },
  )
  PlaylistsView(
    loading = loading,
    playlists = playlists,
    onOpenPlaylist = onOpenPlaylist,
  )
}

@Composable
internal fun PlaylistDetailRoute(
  playlist: PlaylistDisplayItem?,
  ownerEthAddress: String?,
  loading: Boolean,
  error: String?,
  tracks: List<MusicTrack>,
  currentTrackId: String?,
  isPlaying: Boolean,
  onBack: () -> Unit,
  onPlayTrack: (MusicTrack) -> Unit,
  onTrackMenu: (MusicTrack) -> Unit,
  onChangeCover: suspend (PlaylistDisplayItem, Uri) -> Boolean,
  onDeletePlaylist: suspend (PlaylistDisplayItem) -> Boolean,
) {
  val scope = rememberCoroutineScope()
  val clipboard = LocalClipboardManager.current
  var menuOpen by remember { mutableStateOf(false) }
  var shareDialogOpen by remember { mutableStateOf(false) }
  var deleteDialogOpen by remember { mutableStateOf(false) }
  var actionBusy by remember { mutableStateOf(false) }
  var coverUpdateBusy by remember { mutableStateOf(false) }

  val canManageOnChainPlaylist =
    playlist != null &&
      !playlist.isLocal &&
      playlist.id.startsWith("0x", ignoreCase = true)

  val coverPicker =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.GetContent(),
    ) { picked ->
      val activePlaylist = playlist
      if (picked == null || activePlaylist == null || actionBusy || coverUpdateBusy) {
        return@rememberLauncherForActivityResult
      }

      coverUpdateBusy = true
      scope.launch {
        onChangeCover(activePlaylist, picked)
        coverUpdateBusy = false
      }
    }

  PirateMobileHeader(
    title = "",
    onBackPress = onBack,
    rightSlot = {
      if (canManageOnChainPlaylist) {
        Box {
          PirateIconButton(onClick = { menuOpen = true }) {
            Icon(
              PhosphorIcons.Regular.DotsThree,
              contentDescription = "Playlist actions",
              tint = MaterialTheme.colorScheme.onBackground,
            )
          }
          DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
          ) {
            DropdownMenuItem(
              text = { Text("Change cover") },
              enabled = !actionBusy && !coverUpdateBusy,
              onClick = {
                menuOpen = false
                coverPicker.launch("image/*")
              },
            )
            DropdownMenuItem(
              text = { Text("Share Playlist Metadata") },
              enabled = !actionBusy && !coverUpdateBusy,
              onClick = {
                menuOpen = false
                shareDialogOpen = true
              },
            )
            DropdownMenuItem(
              text = { Text("Delete playlist") },
              enabled = !actionBusy && !coverUpdateBusy,
              onClick = {
                menuOpen = false
                deleteDialogOpen = true
              },
            )
          }
        }
      }
    },
  )
  PlaylistDetailView(
    playlist = playlist,
    loading = loading,
    error = error,
    tracks = tracks,
    currentTrackId = currentTrackId,
    isPlaying = isPlaying,
    onPlayTrack = onPlayTrack,
    onTrackMenu = onTrackMenu,
    coverUpdating = coverUpdateBusy,
  )

  val activePlaylist = playlist
  if (shareDialogOpen && activePlaylist != null) {
    AlertDialog(
      onDismissRequest = {
        if (!actionBusy) {
          shareDialogOpen = false
        }
      },
      title = { Text("Share Playlist Metadata") },
      text = {
        Text("This copies a share code that points to this playlist. No encrypted files or decrypt grants are shared.")
      },
      confirmButton = {
        PirateTextButton(
          enabled = !actionBusy && !coverUpdateBusy,
          onClick = {
            val ownerAddress = ownerEthAddress?.trim()?.lowercase().orEmpty()
            val payload =
              JSONObject()
                .put("version", 1)
                .put("kind", "playlist-metadata")
                .put("playlistId", activePlaylist.id)
                .put("ownerAddress", ownerAddress)
                .toString()
            val encoded =
              Base64.encodeToString(
                payload.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
              )
            val shareCode =
              "heaven:playlist-meta:v1:$encoded"
            clipboard.setText(AnnotatedString(shareCode))
            shareDialogOpen = false
          },
        ) {
          Text("Copy Share Code")
        }
      },
      dismissButton = {
        PirateTextButton(
          enabled = !actionBusy && !coverUpdateBusy,
          onClick = { shareDialogOpen = false },
        ) {
          Text("Cancel")
        }
      },
    )
  }

  if (deleteDialogOpen && activePlaylist != null) {
    AlertDialog(
      onDismissRequest = {
        if (!actionBusy) {
          deleteDialogOpen = false
        }
      },
      title = { Text("Delete playlist?") },
      text = { Text("This will remove \"${activePlaylist.name}\" from your playlists.") },
      confirmButton = {
        PirateTextButton(
          enabled = !actionBusy && !coverUpdateBusy,
          onClick = {
            actionBusy = true
            scope.launch {
              val deleted = onDeletePlaylist(activePlaylist)
              actionBusy = false
              if (deleted) {
                deleteDialogOpen = false
              }
            }
          },
        ) {
          Text(if (actionBusy) "Deleting..." else "Delete")
        }
      },
      dismissButton = {
        PirateTextButton(
          enabled = !actionBusy && !coverUpdateBusy,
          onClick = { deleteDialogOpen = false },
        ) {
          Text("Cancel")
        }
      },
    )
  }
}
