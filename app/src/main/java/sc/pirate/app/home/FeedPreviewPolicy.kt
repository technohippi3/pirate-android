package sc.pirate.app.home

internal const val FEED_DEFAULT_PREVIEW_FRAME_MS = 800L
private const val FEED_MAX_PREVIEW_FRAME_MS = 2_500L

internal fun resolveFeedPreviewFrameMs(previewAtMs: Long?): Long {
  val raw = previewAtMs ?: FEED_DEFAULT_PREVIEW_FRAME_MS
  return raw.coerceIn(0L, FEED_MAX_PREVIEW_FRAME_MS)
}
