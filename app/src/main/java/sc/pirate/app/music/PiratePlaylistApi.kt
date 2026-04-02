package sc.pirate.app.music

import android.content.Context
import android.util.Log
import org.json.JSONObject
import sc.pirate.app.PirateChainConfig
import sc.pirate.app.auth.privy.PrivyRelayClient

data class PiratePlaylistTxResult(
  val success: Boolean,
  val txHash: String? = null,
  val playlistId: String? = null,
  val error: String? = null,
)

object PiratePlaylistApi {
  private const val TAG = "PiratePlaylistApi"
  const val PLAYLIST_V1 = PirateChainConfig.STORY_PLAYLIST_V1

  suspend fun createPlaylist(
    context: Context,
    name: String,
    coverCid: String,
    visibility: Int,
    trackIds: List<String>,
  ): PiratePlaylistTxResult {
    val safeName = truncateUtf8(name.trim(), maxBytes = 64)
    if (safeName.isBlank()) {
      return PiratePlaylistTxResult(success = false, error = "Playlist name is required")
    }

    val normalizedTracks =
      runCatching { normalizeTrackIds(trackIds) }
        .getOrElse { error -> return PiratePlaylistTxResult(success = false, error = error.message) }

    val data =
      encodeCreatePlaylist(
        name = safeName,
        coverCid = truncateUtf8(coverCid.trim(), maxBytes = 128),
        visibility = visibility,
        trackIds = normalizedTracks,
      )

    return submitContractCall(
      context = context,
      callData = data,
      opLabel = "create playlist",
      intentType = "pirate.playlist.create",
      intentArgs = JSONObject().put("name", safeName).put("visibility", visibility),
      parsePlaylistId = true,
    )
  }

  suspend fun setTracks(
    context: Context,
    playlistId: String,
    expectedVersion: Int,
    trackIds: List<String>,
  ): PiratePlaylistTxResult {
    val pid =
      runCatching { normalizeBytes32(playlistId, "playlistId") }
        .getOrElse { error -> return PiratePlaylistTxResult(success = false, error = error.message) }
    if (expectedVersion <= 0) {
      return PiratePlaylistTxResult(success = false, error = "Invalid expected playlist version")
    }
    val normalizedTracks =
      runCatching { normalizeTrackIds(trackIds) }
        .getOrElse { error -> return PiratePlaylistTxResult(success = false, error = error.message) }

    return submitContractCall(
      context = context,
      callData = encodeSetTracks(pid, expectedVersion, normalizedTracks),
      opLabel = "set tracks",
      intentType = "pirate.playlist.set-tracks",
      intentArgs = JSONObject().put("playlistId", pid).put("expectedVersion", expectedVersion).put("trackCount", normalizedTracks.size),
    )
  }

  suspend fun updateMeta(
    context: Context,
    playlistId: String,
    name: String,
    coverCid: String,
    visibility: Int,
  ): PiratePlaylistTxResult {
    val pid =
      runCatching { normalizeBytes32(playlistId, "playlistId") }
        .getOrElse { error -> return PiratePlaylistTxResult(success = false, error = error.message) }
    val safeName = truncateUtf8(name.trim(), maxBytes = 64)
    if (safeName.isBlank()) {
      return PiratePlaylistTxResult(success = false, error = "Playlist name is required")
    }

    return submitContractCall(
      context = context,
      callData = encodeUpdateMeta(pid, safeName, truncateUtf8(coverCid.trim(), maxBytes = 128), visibility),
      opLabel = "update playlist meta",
      intentType = "pirate.playlist.update-meta",
      intentArgs = JSONObject().put("playlistId", pid).put("name", safeName).put("visibility", visibility),
    )
  }

  suspend fun deletePlaylist(
    context: Context,
    playlistId: String,
  ): PiratePlaylistTxResult {
    val pid =
      runCatching { normalizeBytes32(playlistId, "playlistId") }
        .getOrElse { error -> return PiratePlaylistTxResult(success = false, error = error.message) }

    return submitContractCall(
      context = context,
      callData = encodeDeletePlaylist(pid),
      opLabel = "delete playlist",
      intentType = "pirate.playlist.delete",
      intentArgs = JSONObject().put("playlistId", pid),
    )
  }

  private suspend fun submitContractCall(
    context: Context,
    callData: String,
    opLabel: String,
    intentType: String,
    intentArgs: JSONObject,
    parsePlaylistId: Boolean = false,
  ): PiratePlaylistTxResult {
    return runCatching {
      val txHash =
        PrivyRelayClient.submitContractCall(
          context = context,
          chainId = PirateChainConfig.STORY_AENEID_CHAIN_ID,
          to = PLAYLIST_V1,
          data = callData,
          intentType = intentType,
          intentArgs = intentArgs,
        )
      val receipt = awaitPlaylistReceipt(txHash)
      if (!receipt.isSuccess) {
        throw IllegalStateException("$opLabel reverted on-chain: ${receipt.txHash}")
      }
      val playlistId = if (parsePlaylistId) runCatching { extractCreatedPlaylistIdFromReceipt(txHash) }.getOrNull() else null
      PiratePlaylistTxResult(success = true, txHash = txHash, playlistId = playlistId)
    }.getOrElse { error ->
      Log.w(TAG, "$opLabel failed: ${error.message}", error)
      PiratePlaylistTxResult(success = false, error = error.message ?: "$opLabel failed")
    }
  }
}
