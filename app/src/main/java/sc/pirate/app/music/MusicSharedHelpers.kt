package sc.pirate.app.music

import android.content.Context

private const val SHARED_SEEN_PREFS = "pirate_music_shared_seen"
private const val SHARED_SEEN_KEY_PREFIX = "seen_v1_"

internal fun sharedPlaylistCoverUrl(coverCid: String?): String? {
  return CoverRef.resolveCoverUrl(coverCid, width = 140, height = 140, format = "webp", quality = 80)
}

internal fun sharedCloudTrackToRowTrack(track: SharedCloudTrack): MusicTrack {
  return MusicTrack(
    id = track.contentId.ifBlank { track.trackId },
    canonicalTrackId = track.trackId,
    title = track.title,
    artist = track.artist,
    album = track.album,
    durationSec = track.durationSec,
    uri = "",
    filename = "",
    artworkUri = sharedPlaylistCoverUrl(track.coverCid),
    contentId = track.contentId,
    pieceCid = track.pieceCid,
    datasetOwner = track.datasetOwner,
    algo = track.algo,
    lyricsRef = track.lyricsRef,
    isCloudOnly = true,
  )
}

internal fun mergeLibraryTracks(
  localTracks: List<MusicTrack>,
  sharedTracks: List<SharedCloudTrack>,
  purchasedTracks: List<MusicTrack>,
  downloadedTracksByContentId: Map<String, DownloadedTrackEntry>,
): List<MusicTrack> {
  if (sharedTracks.isEmpty() && purchasedTracks.isEmpty()) return localTracks

  val out = ArrayList<MusicTrack>(localTracks.size + sharedTracks.size + purchasedTracks.size)
  out.addAll(localTracks)

  val localIds =
    localTracks
      .map { it.id.trim().lowercase() }
      .filter { it.isNotBlank() }
      .toHashSet()
  val localContentIds =
    localTracks
      .mapNotNull { it.contentId?.trim()?.lowercase() }
      .filter { it.isNotBlank() }
      .toHashSet()
  val downloadedContentIds =
    downloadedTracksByContentId
      .keys
      .map { it.trim().lowercase() }
      .filter { it.isNotBlank() }
      .toHashSet()
  val localSignatures =
    localTracks
      .map(::trackSignature)
      .filter { it.isNotBlank() }
      .toHashSet()
  val addedCloudIds = HashSet<String>(sharedTracks.size)
  val addedSignatures = HashSet<String>(sharedTracks.size + purchasedTracks.size)
  val cloudRows = ArrayList<MusicTrack>(sharedTracks.size + purchasedTracks.size)
  for (shared in sharedTracks) cloudRows.add(sharedCloudTrackToRowTrack(shared))
  cloudRows.addAll(purchasedTracks)

  for (row in cloudRows) {
    val contentId = row.contentId?.trim()?.lowercase().orEmpty()
    if (contentId.isNotBlank() && (localContentIds.contains(contentId) || downloadedContentIds.contains(contentId))) {
      continue
    }

    val rowId = row.id.trim().lowercase()
    if (rowId.isBlank()) continue
    if (localIds.contains(rowId)) continue
    if (!addedCloudIds.add(rowId)) continue
    val signature = trackSignature(row)
    if (signature.isNotBlank() && localSignatures.contains(signature)) continue
    if (signature.isNotBlank() && !addedSignatures.add(signature)) continue
    out.add(row)
  }

  return out
}

private fun trackSignature(track: MusicTrack): String {
  val title = track.title.trim().lowercase()
  val artist = track.artist.trim().lowercase()
  val album = track.album.trim().lowercase()
  if (title.isBlank() || artist.isBlank()) return ""
  return "$title|$artist|$album"
}

private fun sharedItemIdForPlaylist(share: PlaylistShareEntry): String {
  return "pl:${share.id.trim().lowercase()}"
}

private fun sharedItemIdForTrack(track: SharedCloudTrack): String {
  val stable = track.contentId.ifBlank { track.trackId }.trim().lowercase()
  return "tr:$stable"
}

internal fun computeSharedItemIds(
  playlists: List<PlaylistShareEntry>,
  tracks: List<SharedCloudTrack>,
): Set<String> {
  val out = LinkedHashSet<String>(playlists.size + tracks.size)
  for (playlist in playlists) out.add(sharedItemIdForPlaylist(playlist))
  for (track in tracks) out.add(sharedItemIdForTrack(track))
  return out
}

private fun sharedSeenStorageKey(ownerEthAddress: String?): String? {
  val owner = ownerEthAddress?.trim()?.lowercase().orEmpty()
  if (owner.isBlank()) return null
  return SHARED_SEEN_KEY_PREFIX + owner
}

internal fun loadSeenSharedItemIds(context: Context, ownerEthAddress: String?): Set<String> {
  val key = sharedSeenStorageKey(ownerEthAddress) ?: return emptySet()
  val prefs = context.getSharedPreferences(SHARED_SEEN_PREFS, Context.MODE_PRIVATE)
  val raw = prefs.getString(key, "").orEmpty()
  if (raw.isBlank()) return emptySet()
  return raw
    .split('|')
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .toSet()
}

internal fun saveSeenSharedItemIds(
  context: Context,
  ownerEthAddress: String?,
  ids: Set<String>,
) {
  val key = sharedSeenStorageKey(ownerEthAddress) ?: return
  val payload = ids.joinToString("|")
  context.getSharedPreferences(SHARED_SEEN_PREFS, Context.MODE_PRIVATE)
    .edit()
    .putString(key, payload)
    .apply()
}
