package com.pirate.app.util

import android.util.Log
import com.pirate.app.music.CoverRef

private const val AVATAR_TARGET_SIZE_PX = 384
private const val AVATAR_WEBP_QUALITY = 82
private const val PROFILE_COVER_TARGET_WIDTH_PX = 1440
private const val PROFILE_COVER_TARGET_HEIGHT_PX = 480
private const val PROFILE_COVER_WEBP_QUALITY = 82

fun resolveAvatarUrl(avatarUri: String?): String? {
  val url = CoverRef.resolveCoverUrl(
    ref = avatarUri,
    width = AVATAR_TARGET_SIZE_PX,
    height = AVATAR_TARGET_SIZE_PX,
    format = "webp",
    quality = AVATAR_WEBP_QUALITY,
  )
  if (avatarUri != null) Log.d("AvatarUrl", "resolveAvatarUrl: ref=$avatarUri -> url=$url")
  return url
}

fun resolveProfileCoverUrl(coverUri: String?): String? {
  val url = CoverRef.resolveCoverUrl(
    ref = coverUri,
    width = PROFILE_COVER_TARGET_WIDTH_PX,
    height = PROFILE_COVER_TARGET_HEIGHT_PX,
    format = "webp",
    quality = PROFILE_COVER_WEBP_QUALITY,
  )
  if (coverUri != null) Log.d("AvatarUrl", "resolveProfileCoverUrl: ref=$coverUri -> url=$url")
  return url
}
