package sc.pirate.app.music

import sc.pirate.app.PirateChainConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient

internal const val SHARED_WITH_YOU_TAG = "SharedWithYouApi"
internal const val SHARED_WITH_YOU_STORY_RPC = PirateChainConfig.STORY_AENEID_RPC_URL
internal const val SHARED_WITH_YOU_SCROBBLE_V4 = PirateChainConfig.STORY_SCROBBLE_V4

internal val sharedWithYouClient = OkHttpClient()
internal val sharedWithYouJsonMediaType = "application/json; charset=utf-8".toMediaType()

internal fun sharedWithYouPlaylistsSubgraphUrl(): String = PirateChainConfig.STORY_PLAYLISTS_SUBGRAPH_URL

internal fun musicSocialSubgraphUrl(): String = PirateChainConfig.STORY_MUSIC_SOCIAL_SUBGRAPH_URL

internal fun normalizeSharedNullableString(raw: String?): String? {
  val value = raw?.trim().orEmpty()
  if (value.isBlank()) return null
  if (value.equals("null", ignoreCase = true)) return null
  if (value.equals("undefined", ignoreCase = true)) return null
  return value
}

internal fun normalizeSharedDecodedString(raw: String?): String? {
  val decoded = decodeBytesUtf8(raw.orEmpty())
  return normalizeSharedNullableString(decoded)
}

internal data class ContentRow(
  val contentId: String,
  val trackId: String,
  val owner: String,
  val pieceCid: String,
  val datasetOwner: String,
  val algo: Int,
  val updatedAtSec: Long,
)

internal data class ContentMeta(
  val trackId: String,
  val contentId: String,
  val pieceCid: String,
  val datasetOwner: String,
  val algo: Int,
)

internal data class GrantedContentIndexes(
  val byMetaHash: Map<String, ContentMeta>,
  val byTrackId: Map<String, ContentMeta>,
  val byContentId: Map<String, ContentMeta>,
)

internal data class TrackMeta(
  val id: String,
  val title: String,
  val artist: String,
  val album: String,
  val coverCid: String? = null,
  val lyricsRef: String? = null,
  val durationSec: Int = 0,
  val metaHash: String? = null,
)
