package sc.pirate.app.music

import android.content.Context
import android.os.SystemClock
import android.util.Log

private const val TAG_SHARED = "MusicShared"

internal suspend fun downloadSharedTrackToDeviceWithUi(
  context: Context,
  track: SharedCloudTrack,
  notify: Boolean,
  isAuthenticated: Boolean,
  ownerEthAddress: String?,
  downloadedEntries: Map<String, DownloadedTrackEntry>,
  cloudPlayBusy: Boolean,
  onSetCloudPlayBusy: (Boolean) -> Unit,
  onSetCloudPlayLabel: (String?) -> Unit,
  onSetDownloadedEntries: (Map<String, DownloadedTrackEntry>) -> Unit,
  onShowMessage: (String) -> Unit,
  onRunScan: suspend () -> Unit,
): Boolean {
  if (cloudPlayBusy) {
    if (notify) onShowMessage("Another decrypt/download is in progress")
    return false
  }

  onSetCloudPlayBusy(true)
  onSetCloudPlayLabel("Downloading: ${track.title}")
  return try {
    val result =
      downloadSharedTrackToDeviceCore(
        context = context,
        track = track,
        notify = notify,
        isAuthenticated = isAuthenticated,
        ownerEthAddress = ownerEthAddress,
        downloadedEntries = downloadedEntries,
        onShowMessage = onShowMessage,
        onRunScan = onRunScan,
      )
    onSetDownloadedEntries(result.updatedEntries)
    result.success
  } finally {
    onSetCloudPlayBusy(false)
    onSetCloudPlayLabel(null)
  }
}

internal suspend fun playSharedCloudTrackWithUi(
  context: Context,
  ownerEthAddress: String?,
  track: SharedCloudTrack,
  isAuthenticated: Boolean,
  downloadedEntries: Map<String, DownloadedTrackEntry>,
  cloudPlayBusy: Boolean,
  onSetCloudPlayBusy: (Boolean) -> Unit,
  onSetCloudPlayLabel: (String?) -> Unit,
  onSetDownloadedEntries: (Map<String, DownloadedTrackEntry>) -> Unit,
  onShowMessage: (String) -> Unit,
  onPlayTrack: (MusicTrack) -> Unit,
): Unit {
  if (cloudPlayBusy) {
    onShowMessage("Playback already in progress")
    return
  }
  if (track.contentId.isBlank()) {
    Log.w(TAG_SHARED, "play blocked: missing contentId title='${track.title}' trackId='${track.trackId}'")
    onShowMessage("Not unlocked yet (missing contentId).")
    return
  }

  try {
    val resolvedDownloaded =
      resolveDownloadedEntry(
        context = context,
        downloadedEntries = downloadedEntries,
        contentId = track.contentId,
      )
    onSetDownloadedEntries(resolvedDownloaded.updatedEntries)
    val downloaded = resolvedDownloaded.entry
    if (downloaded != null) {
      Log.d(TAG_SHARED, "play local download contentId=${track.contentId} uri=${downloaded.mediaUri}")
      val playableTrack =
        if (track.coverCid.isNullOrBlank()) {
          track.copy(coverCid = normalizeSharedNullableString(downloaded.coverCid))
        } else {
          track
        }
      val playable = buildSharedTrackForPlayer(playableTrack, downloaded.mediaUri, downloaded.filename.ifBlank { track.title })
      onPlayTrack(playable)
      return
    }

    Log.d(TAG_SHARED, "play requested title='${track.title}' contentId='${track.contentId}' pieceCid='${track.pieceCid.take(18)}...' datasetOwner='${track.datasetOwner}' algo=${track.algo}")
    val cached = findCachedSharedAudio(context = context, contentId = track.contentId)
    if (cached != null) {
      Log.d(TAG_SHARED, "cache hit contentId=${track.contentId} file=${cached.filename}")
      val playable = buildSharedTrackForPlayer(track, cached.uri, cached.filename)
      onPlayTrack(playable)
      return
    }

    if (!isAuthenticated) {
      onShowMessage("Sign in to play")
      return
    }
    if (track.pieceCid.isBlank()) {
      Log.w(TAG_SHARED, "play blocked: missing pieceCid title='${track.title}' contentId='${track.contentId}' trackId='${track.trackId}'")
      onShowMessage("Not unlocked yet (missing pieceCid).")
      return
    }
    val isFilecoinPiece = run {
      val value = track.pieceCid.trim()
      value.startsWith("baga") || value.startsWith("bafy") || value.startsWith("Qm")
    }
    if (isFilecoinPiece && track.datasetOwner.isBlank()) {
      Log.w(TAG_SHARED, "play blocked: missing datasetOwner for Filecoin pieceCid='${track.pieceCid}' contentId='${track.contentId}'")
      onShowMessage("Not unlocked yet (missing datasetOwner).")
      return
    }

    onSetCloudPlayBusy(true)
    onSetCloudPlayLabel("Decrypting: ${track.title} (can take up to ~60s)")
    onShowMessage("Decrypting (can take up to ~60s)…")
    val startedAtMs = SystemClock.elapsedRealtime()

    val prepared = decryptSharedAudioToCache(context = context, ownerEthAddress = ownerEthAddress, track = track)

    val tookMs = SystemClock.elapsedRealtime() - startedAtMs
    Log.d(TAG_SHARED, "decrypt ok contentId=${track.contentId} tookMs=$tookMs file=${prepared.filename}")
    val playable = buildSharedTrackForPlayer(track, prepared.uri, prepared.filename)
    onPlayTrack(playable)
  } catch (err: Throwable) {
    Log.e(TAG_SHARED, "decrypt/play failed contentId=${track.contentId}", err)
    onShowMessage("Playback failed: ${err.message ?: "unknown error"}")
  } finally {
    onSetCloudPlayBusy(false)
    onSetCloudPlayLabel(null)
  }
}
