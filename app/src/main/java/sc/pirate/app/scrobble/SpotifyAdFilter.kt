package sc.pirate.app.scrobble

internal const val SPOTIFY_ADVERTISEMENT_METADATA_KEY = "android.media.metadata.ADVERTISEMENT"

internal fun looksLikeSpotifyAdvertisement(
  artist: String,
  title: String,
  album: String? = null,
  mediaId: String? = null,
  mediaUri: String? = null,
): Boolean {
  val normalizedArtist = normalizeSpotifyAdField(artist)
  val normalizedTitle = normalizeSpotifyAdField(title)
  val normalizedAlbum = normalizeSpotifyAdField(album)
  val normalizedMediaId = normalizeSpotifyAdField(mediaId)
  val normalizedMediaUri = normalizeSpotifyAdField(mediaUri)

  if (normalizedMediaId.contains("spotify:ad:") || normalizedMediaUri.contains("spotify:ad:")) {
    return true
  }

  return normalizedArtist.contains("advertisement") ||
    normalizedTitle.contains("advertisement") ||
    normalizedAlbum.contains("advertisement")
}

private fun normalizeSpotifyAdField(value: String?): String = value?.trim()?.lowercase().orEmpty()
