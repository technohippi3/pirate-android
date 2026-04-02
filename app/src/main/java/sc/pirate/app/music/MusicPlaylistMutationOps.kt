package sc.pirate.app.music

import android.content.Context
import android.net.Uri
import sc.pirate.app.arweave.ArweaveUploadApi
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_PLAYLIST_COVER_SOURCE_BYTES = 10 * 1024 * 1024

private data class PlaylistCoverPayload(
  val bytes: ByteArray,
  val filename: String,
  val contentType: String,
)

private fun readPlaylistCoverPayload(
  context: Context,
  uri: Uri,
): PlaylistCoverPayload {
  val filename =
    uri.lastPathSegment
      ?.substringAfterLast('/')
      ?.trim()
      .orEmpty()
      .ifBlank { "playlist-cover.jpg" }
  val contentType =
    context.contentResolver.getType(uri)?.trim().orEmpty()
      .takeIf { it.startsWith("image/") }
      ?: guessCoverContentType(filename)
  val bytes =
    context.contentResolver.openInputStream(uri)?.use { input ->
      readStreamWithLimit(input, MAX_PLAYLIST_COVER_SOURCE_BYTES)
    } ?: throw IllegalStateException("Unable to read selected image")

  return PlaylistCoverPayload(
    bytes = bytes,
    filename = filename,
    contentType = contentType,
  )
}

private fun guessCoverContentType(filename: String): String {
  val lower = filename.lowercase()
  return when {
    lower.endsWith(".png") -> "image/png"
    lower.endsWith(".webp") -> "image/webp"
    lower.endsWith(".bmp") -> "image/bmp"
    else -> "image/jpeg"
  }
}

private fun readStreamWithLimit(
  input: InputStream,
  maxBytes: Int,
): ByteArray {
  val out = ByteArrayOutputStream()
  val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
  var total = 0
  while (true) {
    val read = input.read(buffer)
    if (read <= 0) break
    total += read
    if (total > maxBytes) {
      throw IllegalStateException("Selected image is too large")
    }
    out.write(buffer, 0, read)
  }
  if (total == 0) throw IllegalStateException("Selected image is empty")
  return out.toByteArray()
}

internal suspend fun changePlaylistCoverWithUi(
  context: Context,
  playlist: PlaylistDisplayItem,
  coverUri: Uri,
  ownerEthAddress: String?,
  isAuthenticated: Boolean,
  onChainPlaylists: List<OnChainPlaylist>,
  selectedPlaylistId: String?,
  selectedPlaylist: PlaylistDisplayItem?,
  onSetOnChainPlaylists: (List<OnChainPlaylist>) -> Unit,
  onSetSelectedPlaylist: (PlaylistDisplayItem?) -> Unit,
  onShowMessage: (String) -> Unit,
): Boolean {
  val playlistId = playlist.id.trim()
  if (playlist.isLocal || !playlistId.startsWith("0x", ignoreCase = true)) {
    onShowMessage("Cover editing is only available for on-chain playlists")
    return false
  }

  val owner = ownerEthAddress?.trim()?.lowercase().orEmpty()
  if (!isAuthenticated || owner.isBlank()) {
    onShowMessage("Sign in to update playlist cover")
    return false
  }

  val payload =
    runCatching {
      withContext(Dispatchers.IO) { readPlaylistCoverPayload(context, coverUri) }
    }.getOrElse { error ->
      onShowMessage("Cover read failed: ${error.message ?: "unknown error"}")
      return false
    }

  val uploaded =
    runCatching {
      ArweaveUploadApi.uploadCover(
        context = context,
        ownerEthAddress = owner,
        coverBytes = payload.bytes,
        filename = payload.filename,
        contentType = payload.contentType,
      )
    }.getOrElse { error ->
      onShowMessage("Cover upload failed: ${error.message ?: "unknown error"}")
      return false
    }

  val visibility =
    onChainPlaylists
      .firstOrNull { it.id.equals(playlistId, ignoreCase = true) }
      ?.visibility
      ?: 0
  val result =
    PiratePlaylistApi.updateMeta(
      context = context,
      playlistId = playlistId,
      name = playlist.name,
      coverCid = uploaded.arRef,
      visibility = visibility,
    )
  if (!result.success) {
    onShowMessage("Cover update failed: ${result.error ?: "unknown error"}")
    return false
  }

  onSetOnChainPlaylists(
    onChainPlaylists.map { row ->
      if (row.id.equals(playlistId, ignoreCase = true)) {
        row.copy(coverCid = uploaded.arRef)
      } else {
        row
      }
    },
  )
  if (selectedPlaylistId?.equals(playlistId, ignoreCase = true) == true) {
    onSetSelectedPlaylist(
      selectedPlaylist?.copy(
        coverUri = CoverRef.resolveCoverUrl(uploaded.arRef, width = 140, height = 140, format = "webp", quality = 80),
      ),
    )
  }

  onShowMessage("Playlist cover updated")
  return true
}

internal suspend fun deletePlaylistWithUi(
  context: Context,
  playlist: PlaylistDisplayItem,
  ownerEthAddress: String?,
  isAuthenticated: Boolean,
  onChainPlaylists: List<OnChainPlaylist>,
  selectedPlaylistId: String?,
  onSetOnChainPlaylists: (List<OnChainPlaylist>) -> Unit,
  onSelectedPlaylistDeleted: () -> Unit,
  onShowMessage: (String) -> Unit,
): Boolean {
  val playlistId = playlist.id.trim()
  if (playlist.isLocal || !playlistId.startsWith("0x", ignoreCase = true)) {
    onShowMessage("Playlist delete is only available for on-chain playlists")
    return false
  }

  val owner = ownerEthAddress?.trim()?.lowercase().orEmpty()
  if (!isAuthenticated || owner.isBlank()) {
    onShowMessage("Sign in to delete playlists")
    return false
  }

  val result =
    PiratePlaylistApi.deletePlaylist(
      context = context,
      playlistId = playlistId,
    )
  if (!result.success) {
    onShowMessage("Delete failed: ${result.error ?: "unknown error"}")
    return false
  }

  onSetOnChainPlaylists(onChainPlaylists.filterNot { it.id.equals(playlistId, ignoreCase = true) })
  if (selectedPlaylistId?.equals(playlistId, ignoreCase = true) == true) {
    onSelectedPlaylistDeleted()
  }

  onShowMessage("Deleted ${playlist.name}")
  return true
}
