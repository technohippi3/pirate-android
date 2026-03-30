package sc.pirate.app.music.ui

import android.content.Context
import androidx.fragment.app.FragmentActivity
import sc.pirate.app.music.LocalPlaylist
import sc.pirate.app.music.LocalPlaylistTrack
import sc.pirate.app.music.LocalPlaylistsStore
import sc.pirate.app.music.MusicTrack
import sc.pirate.app.music.ONCHAIN_PLAYLISTS_ENABLED
import sc.pirate.app.music.OnChainPlaylist
import sc.pirate.app.music.OnChainPlaylistsApi
import sc.pirate.app.music.PlaylistDisplayItem
import sc.pirate.app.music.TempoPlaylistApi
import sc.pirate.app.music.CoverRef
import sc.pirate.app.music.TrackIds
import sc.pirate.app.music.resolveCanonicalTrackIdForMutation
import sc.pirate.app.scrobble.awaitScrobbleReceipt
import sc.pirate.app.scrobble.encodeRegisterTracksForUser
import sc.pirate.app.scrobble.isTrackRegistered
import sc.pirate.app.scrobble.submitScrobbleSessionKeyContractCall
import sc.pirate.app.tempo.P256Utils
import sc.pirate.app.tempo.SessionKeyManager
import sc.pirate.app.tempo.TempoPasskeyManager
import sc.pirate.app.tempo.TempoSessionKeyApi
import kotlinx.coroutines.delay

private const val GAS_LIMIT_REGISTER_TRACK_FOR_PLAYLIST_MIN = 900_000L
private const val SET_TRACKS_STALE_RETRY_LIMIT = 2

internal data class PlaylistMutationSuccess(
  val playlistId: String,
  val playlistName: String,
  val trackAdded: Boolean,
)

internal suspend fun loadPlaylistDisplayItems(
  context: Context,
  isAuthenticated: Boolean,
  ownerEthAddress: String?,
): List<PlaylistDisplayItem> {
  val local = runCatching { LocalPlaylistsStore.getLocalPlaylists(context) }.getOrElse { emptyList() }
  val onChain =
    if (ONCHAIN_PLAYLISTS_ENABLED && isAuthenticated && !ownerEthAddress.isNullOrBlank()) {
      runCatching { OnChainPlaylistsApi.fetchUserPlaylists(ownerEthAddress) }.getOrElse { emptyList() }
    } else {
      emptyList()
    }

  return toDisplayItems(local, onChain)
}

internal suspend fun createPlaylistWithTrack(
  context: Context,
  track: MusicTrack,
  playlistName: String,
  isAuthenticated: Boolean,
  ownerEthAddress: String?,
  tempoAccount: TempoPasskeyManager.PasskeyAccount?,
  hostActivity: FragmentActivity?,
  onShowMessage: (String) -> Unit,
): PlaylistMutationSuccess? {
  val owner = ownerEthAddress?.trim()?.lowercase().orEmpty()
  if (ONCHAIN_PLAYLISTS_ENABLED && isAuthenticated && owner.isNotBlank() && tempoAccount != null) {
    val sessionKey =
      resolvePlaylistSessionKey(
        context = context,
        owner = owner,
        hostActivity = hostActivity,
        tempoAccount = tempoAccount,
        failureMessage = "Session expired. Sign in again to create playlists.",
        onShowMessage = onShowMessage,
      ) ?: return null

    val trackId =
      resolveCanonicalTrackIdForPlaylistMutation(
        track = track,
        account = tempoAccount,
        sessionKey = sessionKey,
        onShowMessage = onShowMessage,
      ) ?: return null

    val createResult =
      TempoPlaylistApi.createPlaylist(
        account = tempoAccount,
        sessionKey = sessionKey,
        name = playlistName,
        coverCid = "",
        visibility = 0,
        trackIds = listOf(trackId),
      )
    if (!createResult.success) {
      onShowMessage("Create failed: ${createResult.error ?: "unknown error"}")
      return null
    }

    val resolvedId = resolveCreatedPlaylistId(owner, playlistName, createResult.playlistId)
    onShowMessage("Added to $playlistName")
    return PlaylistMutationSuccess(
      playlistId = resolvedId ?: "pending:${System.currentTimeMillis()}",
      playlistName = playlistName,
      trackAdded = true,
    )
  }

  val created = LocalPlaylistsStore.createLocalPlaylist(context, playlistName, track.toLocalPlaylistTrack())
  onShowMessage("Added to ${created.name}")
  return PlaylistMutationSuccess(
    playlistId = created.id,
    playlistName = created.name,
    trackAdded = true,
  )
}

internal suspend fun addTrackToPlaylistWithUi(
  context: Context,
  playlist: PlaylistDisplayItem,
  track: MusicTrack,
  isAuthenticated: Boolean,
  ownerEthAddress: String?,
  tempoAccount: TempoPasskeyManager.PasskeyAccount?,
  hostActivity: FragmentActivity?,
  onShowMessage: (String) -> Unit,
): PlaylistMutationSuccess? {
  if (playlist.isLocal) {
    LocalPlaylistsStore.addTrackToLocalPlaylist(context, playlist.id, track.toLocalPlaylistTrack())
    onShowMessage("Added to ${playlist.name}")
    return PlaylistMutationSuccess(
      playlistId = playlist.id,
      playlistName = playlist.name,
      trackAdded = true,
    )
  }

  if (!ONCHAIN_PLAYLISTS_ENABLED) {
    onShowMessage("Could not update playlist")
    return null
  }

  val owner = ownerEthAddress?.trim()?.lowercase().orEmpty()
  if (!isAuthenticated || owner.isBlank() || tempoAccount == null) {
    onShowMessage("Sign in to update playlists")
    return null
  }

  val sessionKey =
    resolvePlaylistSessionKey(
      context = context,
      owner = owner,
      hostActivity = hostActivity,
      tempoAccount = tempoAccount,
      failureMessage = "Session expired. Sign in again to update playlists.",
      onShowMessage = onShowMessage,
      ) ?: return null

  val trackId =
    resolveCanonicalTrackIdForPlaylistMutation(
      track = track,
      account = tempoAccount,
      sessionKey = sessionKey,
      onShowMessage = onShowMessage,
    ) ?: return null

  val existingTrackIds = runCatching { OnChainPlaylistsApi.fetchPlaylistTrackIds(playlist.id) }
    .getOrElse {
      onShowMessage("Could not load playlist tracks yet. Try again in a moment.")
      return null
    }

  if (playlist.trackCount > 0 && existingTrackIds.isEmpty()) {
    onShowMessage("Playlist tracks are still indexing. Try again shortly.")
    return null
  }

  if (existingTrackIds.any { it.equals(trackId, ignoreCase = true) }) {
    onShowMessage("Already in ${playlist.name}")
    return PlaylistMutationSuccess(
      playlistId = playlist.id,
      playlistName = playlist.name,
      trackAdded = false,
    )
  }

  val nextTrackIds = ArrayList<String>(existingTrackIds.size + 1)
  nextTrackIds.addAll(existingTrackIds)
  nextTrackIds.add(trackId)

  val initialVersion = playlist.version?.takeIf { it > 0 } ?: run {
    onShowMessage("Playlist version unavailable. Refresh playlists and try again.")
    return null
  }
  val result =
    setTracksWithStaleRetry(
      account = tempoAccount,
      sessionKey = sessionKey,
      ownerAddress = owner,
      playlistId = playlist.id,
      initialVersion = initialVersion,
      initialTrackIds = existingTrackIds,
      trackIdToAppend = trackId,
    )
  if (!result.success) {
    onShowMessage("Add failed: ${result.error ?: "unknown error"}")
    return null
  }
  if (result.alreadyPresent) {
    onShowMessage("Already in ${playlist.name}")
    return PlaylistMutationSuccess(
      playlistId = playlist.id,
      playlistName = playlist.name,
      trackAdded = false,
    )
  }

  onShowMessage("Added to ${playlist.name}")
  return PlaylistMutationSuccess(
    playlistId = playlist.id,
    playlistName = playlist.name,
    trackAdded = true,
  )
}

private suspend fun resolvePlaylistSessionKey(
  context: Context,
  owner: String,
  hostActivity: FragmentActivity?,
  tempoAccount: TempoPasskeyManager.PasskeyAccount?,
  failureMessage: String,
  onShowMessage: (String) -> Unit,
): SessionKeyManager.SessionKey? {
  val loaded =
    SessionKeyManager.load(context)?.takeIf {
      SessionKeyManager.isValid(it, ownerAddress = owner) &&
        it.keyAuthorization?.isNotEmpty() == true
    }
  if (loaded != null) return loaded

  val activity = hostActivity
  val account = tempoAccount
  if (activity == null || account == null) {
    onShowMessage(failureMessage)
    return null
  }
  onShowMessage("Authorizing session key...")
  val auth = TempoSessionKeyApi.authorizeSessionKey(activity = activity, account = account)
  val authorized =
    auth.sessionKey?.takeIf {
      auth.success &&
        SessionKeyManager.isValid(it, ownerAddress = owner) &&
        it.keyAuthorization?.isNotEmpty() == true
    }
  if (authorized != null) return authorized

  onShowMessage(auth.error ?: failureMessage)
  return null
}

private class MetaTrackRegistration(
  val trackId: String,
  val kind: Int,
  val payloadBytes32: ByteArray,
  val title: String,
  val artist: String,
  val album: String,
  val durationSec: Int,
)

private data class SetTracksAttemptResult(
  val success: Boolean,
  val alreadyPresent: Boolean = false,
  val error: String? = null,
)

private data class PlaylistSnapshot(
  val version: Int,
  val trackCount: Int,
  val trackIds: List<String>,
)

private suspend fun resolveCanonicalTrackIdForPlaylistMutation(
  track: MusicTrack,
  account: TempoPasskeyManager.PasskeyAccount,
  sessionKey: SessionKeyManager.SessionKey,
  onShowMessage: (String) -> Unit,
): String? {
  val direct = resolveCanonicalTrackIdForMutation(track)?.value
  if (!direct.isNullOrBlank()) return direct

  val registration = prepareMetaTrackRegistration(track)
  if (registration == null) {
    onShowMessage("This track is missing canonical identity and metadata required to register it.")
    return null
  }

  val alreadyRegistered = runCatching { isTrackRegistered(registration.trackId) }.getOrDefault(false)
  if (alreadyRegistered) return registration.trackId

  val registerCallData =
    encodeRegisterTracksForUser(
      user = account.address,
      kind = registration.kind,
      payloadBytes32 = registration.payloadBytes32,
      title = registration.title,
      artist = registration.artist,
      album = registration.album,
      durationSec = registration.durationSec,
    )

  val submission =
    runCatching {
      submitScrobbleSessionKeyContractCall(
        account = account,
        sessionKey = sessionKey,
        callData = registerCallData,
        minimumGasLimit = GAS_LIMIT_REGISTER_TRACK_FOR_PLAYLIST_MIN,
      )
    }.getOrElse {
      onShowMessage("Track registration failed: ${it.message ?: "unknown error"}")
      return null
    }

  val receipt =
    runCatching { awaitScrobbleReceipt(submission.txHash) }
      .getOrElse {
        onShowMessage("Track registration confirmation failed: ${it.message ?: "unknown error"}")
        return null
      }
  if (!receipt.isSuccess) {
    onShowMessage("Track registration reverted on-chain")
    return null
  }

  return registration.trackId
}

private fun prepareMetaTrackRegistration(track: MusicTrack): MetaTrackRegistration? {
  val title = normalizeMetaField(track.title)
  val artist = normalizeMetaField(track.artist)
  val album = normalizeMetaField(track.album)
  if (title.isBlank() || artist.isBlank()) return null

  val parts = TrackIds.computeMetaParts(title = title, artist = artist, album = album)
  val trackId = "0x${P256Utils.bytesToHex(parts.trackId)}".lowercase()
  return MetaTrackRegistration(
    trackId = trackId,
    kind = parts.kind,
    payloadBytes32 = parts.payload,
    title = title,
    artist = artist,
    album = album,
    durationSec = track.durationSec.coerceAtLeast(0),
  )
}

private fun normalizeMetaField(value: String): String = value.lowercase().trim().replace(Regex("\\s+"), " ")

private suspend fun setTracksWithStaleRetry(
  account: TempoPasskeyManager.PasskeyAccount,
  sessionKey: SessionKeyManager.SessionKey,
  ownerAddress: String,
  playlistId: String,
  initialVersion: Int,
  initialTrackIds: List<String>,
  trackIdToAppend: String,
): SetTracksAttemptResult {
  var expectedVersion = initialVersion
  var currentTrackIds = initialTrackIds

  repeat(SET_TRACKS_STALE_RETRY_LIMIT + 1) { attempt ->
    val preSubmitSnapshot =
      fetchPlaylistSnapshot(ownerAddress = ownerAddress, playlistId = playlistId)
        ?: return SetTracksAttemptResult(success = false, error = "Playlist refresh failed during retry")
    if (preSubmitSnapshot.trackCount > 0 && preSubmitSnapshot.trackIds.isEmpty()) {
      return SetTracksAttemptResult(success = false, error = "Playlist tracks are still indexing. Try again shortly.")
    }

    if (preSubmitSnapshot.trackIds.any { it.equals(trackIdToAppend, ignoreCase = true) }) {
      return SetTracksAttemptResult(success = true, alreadyPresent = true)
    }

    currentTrackIds = preSubmitSnapshot.trackIds
    if (preSubmitSnapshot.version != expectedVersion) {
      if (attempt >= SET_TRACKS_STALE_RETRY_LIMIT) {
        return SetTracksAttemptResult(
          success = false,
          error = "Playlist changed while updating. Refresh playlists and try again.",
        )
      }
      expectedVersion = preSubmitSnapshot.version
      return@repeat
    }

    if (currentTrackIds.any { it.equals(trackIdToAppend, ignoreCase = true) }) {
      return SetTracksAttemptResult(success = true, alreadyPresent = true)
    }

    val nextTrackIds = ArrayList<String>(currentTrackIds.size + 1)
    nextTrackIds.addAll(currentTrackIds)
    nextTrackIds.add(trackIdToAppend)

    val result =
      TempoPlaylistApi.setTracks(
        account = account,
        sessionKey = sessionKey,
        playlistId = playlistId,
        expectedVersion = expectedVersion,
        trackIds = nextTrackIds,
      )
    if (result.success) {
      return SetTracksAttemptResult(success = true)
    }

    val postFailureSnapshot =
      fetchPlaylistSnapshot(ownerAddress = ownerAddress, playlistId = playlistId)
        ?: return SetTracksAttemptResult(success = false, error = result.error ?: "unknown error")
    if (postFailureSnapshot.trackCount > 0 && postFailureSnapshot.trackIds.isEmpty()) {
      return SetTracksAttemptResult(success = false, error = "Playlist tracks are still indexing. Try again shortly.")
    }
    if (postFailureSnapshot.trackIds.any { it.equals(trackIdToAppend, ignoreCase = true) }) {
      return SetTracksAttemptResult(success = true, alreadyPresent = true)
    }

    val versionAdvanced = postFailureSnapshot.version != expectedVersion
    if (!versionAdvanced || attempt >= SET_TRACKS_STALE_RETRY_LIMIT) {
      return SetTracksAttemptResult(success = false, error = result.error)
    }

    expectedVersion = postFailureSnapshot.version
    currentTrackIds = postFailureSnapshot.trackIds
  }

  return SetTracksAttemptResult(success = false, error = "unknown error")
}

private suspend fun fetchPlaylistSnapshot(
  ownerAddress: String,
  playlistId: String,
): PlaylistSnapshot? {
  val playlist =
    runCatching {
      OnChainPlaylistsApi.fetchUserPlaylists(ownerAddress, maxEntries = 100)
        .firstOrNull { it.id.equals(playlistId, ignoreCase = true) }
    }.getOrNull() ?: return null
  if (playlist.version <= 0) return null

  val trackIds =
    runCatching { OnChainPlaylistsApi.fetchPlaylistTrackIds(playlistId) }
      .getOrElse { return null }

  return PlaylistSnapshot(
    version = playlist.version,
    trackCount = playlist.trackCount,
    trackIds = trackIds,
  )
}

private fun toDisplayItems(local: List<LocalPlaylist>, onChain: List<OnChainPlaylist>): List<PlaylistDisplayItem> {
  val out = ArrayList<PlaylistDisplayItem>(local.size + onChain.size)
  for (localPlaylist in local) {
    out.add(
      PlaylistDisplayItem(
        id = localPlaylist.id,
        name = localPlaylist.name,
        trackCount = localPlaylist.tracks.size,
        coverUri = localPlaylist.coverUri ?: localPlaylist.tracks.firstOrNull()?.artworkUri,
        isLocal = true,
      ),
    )
  }
  for (onChainPlaylist in onChain) {
    out.add(
      PlaylistDisplayItem(
        id = onChainPlaylist.id,
        name = onChainPlaylist.name,
        trackCount = onChainPlaylist.trackCount,
        coverUri = CoverRef.resolveCoverUrl(onChainPlaylist.coverCid.ifBlank { null }, width = 96, height = 96, format = "webp", quality = 80),
        isLocal = false,
        version = onChainPlaylist.version,
        tracksHash = onChainPlaylist.tracksHash,
      ),
    )
  }
  return out
}

private fun MusicTrack.toLocalPlaylistTrack(): LocalPlaylistTrack {
  return LocalPlaylistTrack(
    artist = artist,
    title = title,
    album = album.ifBlank { null },
    durationSec = durationSec.takeIf { it > 0 },
    uri = uri,
    artworkUri = artworkUri,
    artworkFallbackUri = artworkFallbackUri,
  )
}

private suspend fun resolveCreatedPlaylistId(
  ownerAddress: String,
  playlistName: String,
  immediateId: String?,
): String? {
  val direct = immediateId?.trim().orEmpty()
  if (direct.startsWith("0x") && direct.length == 66) return direct.lowercase()

  repeat(4) {
    val candidates = runCatching { OnChainPlaylistsApi.fetchUserPlaylists(ownerAddress, maxEntries = 30) }.getOrNull()
    val match =
      candidates
        ?.firstOrNull { playlist ->
          playlist.name.trim().equals(playlistName.trim(), ignoreCase = true)
        }
        ?.id
        ?.trim()
    if (!match.isNullOrBlank()) return match.lowercase()
    delay(1_200L)
  }

  return null
}
