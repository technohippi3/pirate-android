package sc.pirate.app.music

private const val RELEASE_CARD_TARGET_SIZE_PX = 420
private const val RELEASE_CARD_WEBP_QUALITY = 82
private const val PLAYBACK_COVER_TARGET_SIZE_PX = 1200
private const val PLAYBACK_COVER_WEBP_QUALITY = 85

internal fun resolveReleaseCoverUrl(ref: String?): String? {
  // The card is rendered at 140.dp, so requesting only 140 px makes covers blurry on
  // high-density phones. Ask the image backend for a 3x asset to match common xxhdpi devices.
  val fromRef =
    CoverRef.resolveCoverUrl(
      ref,
      width = RELEASE_CARD_TARGET_SIZE_PX,
      height = RELEASE_CARD_TARGET_SIZE_PX,
      format = "webp",
      quality = RELEASE_CARD_WEBP_QUALITY,
    )
  if (!fromRef.isNullOrBlank()) return fromRef
  return resolveRawCoverRefUrl(ref)
}

internal fun resolvePlaybackCoverUrl(ref: String?): String? {
  val fromRef =
    CoverRef.resolveCoverUrl(
      ref,
      width = PLAYBACK_COVER_TARGET_SIZE_PX,
      height = PLAYBACK_COVER_TARGET_SIZE_PX,
      format = "webp",
      quality = PLAYBACK_COVER_WEBP_QUALITY,
    )
  if (!fromRef.isNullOrBlank()) return fromRef
  return resolveRawCoverRefUrl(ref)
}

private fun resolveRawCoverRefUrl(ref: String?): String? {
  val raw = ref?.trim().orEmpty()
  if (raw.startsWith("content://")) return raw
  if (raw.startsWith("file://")) return raw
  if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
  return null
}
