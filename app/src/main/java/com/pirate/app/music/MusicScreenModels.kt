package com.pirate.app.music

internal enum class MusicView { Home, Library, Shared, SharedPlaylistDetail, Playlists, PlaylistDetail, Search }

internal data class AlbumCardModel(
  val trackId: String? = null,
  val contentId: String? = null,
  val title: String,
  val artist: String,
  val coverRef: String? = null,
  val lyricsRef: String? = null,
)

internal data class LiveRoomCardModel(
  val roomId: String,
  val title: String,
  val subtitle: String? = null,
  val hostWallet: String? = null,
  val coverRef: String? = null,
  val liveAmount: String? = null,
  val listenerCount: Int? = null,
  val status: String? = null,
)

internal const val HOME_NEW_RELEASES_MAX = 12
internal const val HOME_NEW_RELEASES_TTL_MS = 30_000L
internal const val HOME_LIVE_ROOMS_MAX = 12
internal const val HOME_LIVE_ROOMS_TTL_MS = 30_000L
internal const val SHARED_REFRESH_TTL_MS = 120_000L
internal const val TURBO_CREDITS_COPY = "Save this song forever on Arweave for ~\$0.03."

internal fun mergedNewReleases(
  recentPublished: List<AlbumCardModel>,
): List<AlbumCardModel> {
  if (recentPublished.isEmpty()) return emptyList()
  val out = ArrayList<AlbumCardModel>(recentPublished.size)
  val seen = LinkedHashSet<String>()

  fun add(item: AlbumCardModel) {
    val key = "${item.title.trim().lowercase()}|${item.artist.trim().lowercase()}"
    if (key == "|") return
    if (!seen.add(key)) return
    out.add(item)
  }

  for (item in recentPublished) add(item)
  return out.take(HOME_NEW_RELEASES_MAX)
}
