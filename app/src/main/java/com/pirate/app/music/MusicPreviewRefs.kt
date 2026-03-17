package com.pirate.app.music

import android.net.Uri

internal fun resolveTrackPreviewUrl(trackId: String?): String? {
  val normalizedTrackId = trackId?.trim().orEmpty().lowercase()
  if (normalizedTrackId.isEmpty()) return null
  val apiBase = SongPublishService.API_CORE_URL.trim().trimEnd('/')
  if (!apiBase.startsWith("http://") && !apiBase.startsWith("https://")) return null
  val encodedTrackId = Uri.encode(normalizedTrackId)
  return "$apiBase/api/music/preview/$encodedTrackId"
}
