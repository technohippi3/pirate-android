package com.pirate.app.music

data class TrackMenuPolicy(
  val canSaveForever: Boolean,
  val canDownload: Boolean,
  val canShare: Boolean,
)

object TrackMenuPolicyResolver {
  fun resolve(
    track: MusicTrack,
    ownerEthAddress: String?,
    alreadyDownloaded: Boolean = false,
  ): TrackMenuPolicy {
    val owner = ownerEthAddress?.trim()?.lowercase().orEmpty()
    val hasOwner = owner.isNotBlank()
    val hasPieceCid = !track.pieceCid.isNullOrBlank()
    val hasContentId = !track.contentId.isNullOrBlank()
    val hasPurchase = !track.purchaseId.isNullOrBlank()
    val isRemoteStream = isRemoteHttpUri(track.uri)
    val isLocalDeviceTrack = isMediaStoreAudioUri(track.uri)
    val isCloudOnly = track.isCloudOnly

    val datasetOwner = track.datasetOwner?.trim()?.lowercase().orEmpty()
    val ownsUploadedTrack = hasOwner && (datasetOwner.isBlank() || datasetOwner == owner)

    val canSaveForever = hasOwner && track.permanentRef.isNullOrBlank() && (
      !hasPurchase &&
        (
          (hasPieceCid && ownsUploadedTrack && !isCloudOnly) ||
            (!hasPieceCid && !isRemoteStream && !isCloudOnly)
          )
      )
    val canDownload = (hasPieceCid || hasPurchase) && !isLocalDeviceTrack && !alreadyDownloaded
    val canShare = hasContentId && ownsUploadedTrack && !hasPurchase

    return TrackMenuPolicy(
      canSaveForever = canSaveForever,
      canDownload = canDownload,
      canShare = canShare,
    )
  }

  private fun isRemoteHttpUri(rawUri: String): Boolean {
    val uri = rawUri.trim()
    return uri.startsWith("http://") || uri.startsWith("https://")
  }

  private fun isMediaStoreAudioUri(rawUri: String): Boolean {
    val uri = rawUri.trim().lowercase()
    if (!uri.startsWith("content://media/")) return false
    return uri.contains("/audio/media/")
  }
}
