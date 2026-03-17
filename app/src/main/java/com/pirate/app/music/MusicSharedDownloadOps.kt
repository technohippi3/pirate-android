package com.pirate.app.music

import android.content.Context
import android.util.Log
import java.io.File

private const val TAG_SHARED_DOWNLOAD = "MusicShared"

internal data class ResolveDownloadedEntryResult(
  val entry: DownloadedTrackEntry?,
  val updatedEntries: Map<String, DownloadedTrackEntry>,
)

internal data class DownloadSharedTrackResult(
  val success: Boolean,
  val updatedEntries: Map<String, DownloadedTrackEntry>,
)

internal suspend fun removeDownloadedEntry(
  context: Context,
  downloadedEntries: Map<String, DownloadedTrackEntry>,
  contentId: String,
): Map<String, DownloadedTrackEntry> {
  val key = contentId.trim().lowercase()
  if (key.isBlank()) return downloadedEntries
  return DownloadedTracksStore.remove(context, key)
}

internal suspend fun resolveDownloadedEntry(
  context: Context,
  downloadedEntries: Map<String, DownloadedTrackEntry>,
  contentId: String,
): ResolveDownloadedEntryResult {
  val key = contentId.trim().lowercase()
  if (key.isBlank()) return ResolveDownloadedEntryResult(entry = null, updatedEntries = downloadedEntries)

  val entry = downloadedEntries[key] ?: return ResolveDownloadedEntryResult(entry = null, updatedEntries = downloadedEntries)
  val exists = runCatching { MediaStoreAudioDownloads.uriExists(context, entry.mediaUri) }.getOrDefault(false)
  if (exists) return ResolveDownloadedEntryResult(entry = entry, updatedEntries = downloadedEntries)

  val updated = removeDownloadedEntry(context = context, downloadedEntries = downloadedEntries, contentId = key)
  return ResolveDownloadedEntryResult(entry = null, updatedEntries = updated)
}

internal suspend fun downloadSharedTrackToDeviceCore(
  context: Context,
  track: SharedCloudTrack,
  notify: Boolean,
  isAuthenticated: Boolean,
  ownerEthAddress: String?,
  downloadedEntries: Map<String, DownloadedTrackEntry>,
  onShowMessage: (String) -> Unit,
  onRunScan: suspend () -> Unit,
): DownloadSharedTrackResult {
  if (track.contentId.isBlank()) {
    if (notify) onShowMessage("Download failed: missing contentId")
    return DownloadSharedTrackResult(success = false, updatedEntries = downloadedEntries)
  }

  var currentEntries = downloadedEntries
  val resolved =
    resolveDownloadedEntry(
      context = context,
      downloadedEntries = currentEntries,
      contentId = track.contentId,
    )
  currentEntries = resolved.updatedEntries
  if (resolved.entry != null) {
    if (notify) onShowMessage("Already downloaded")
    return DownloadSharedTrackResult(success = true, updatedEntries = currentEntries)
  }

  return try {
    val cached = findCachedSharedAudio(context = context, contentId = track.contentId)
    val prepared =
      if (cached != null) {
        cached
      } else {
        if (track.pieceCid.isBlank()) {
          throw IllegalStateException("missing pieceCid")
        }
        if (!isAuthenticated) {
          throw IllegalStateException("Sign in to download")
        }
        decryptSharedAudioToCache(
          context = context,
          ownerEthAddress = ownerEthAddress,
          track = track,
        )
      }

    val preferredName =
      listOf(track.artist.trim(), track.title.trim())
        .filter { it.isNotBlank() }
        .joinToString(" - ")
        .ifBlank { track.contentId.removePrefix("0x") }

    val mediaUri =
      MediaStoreAudioDownloads.saveAudio(
        context = context,
        sourceFile = prepared.file,
        title = track.title,
        artist = track.artist,
        album = track.album,
        mimeType = prepared.mimeType,
        preferredName = preferredName,
      )

    val entry =
      DownloadedTrackEntry(
        contentId = track.contentId.trim().lowercase(),
        mediaUri = mediaUri,
        title = track.title,
        artist = track.artist,
        album = track.album,
        filename = prepared.filename,
        mimeType = prepared.mimeType,
        pieceCid = track.pieceCid,
        datasetOwner = track.datasetOwner,
        algo = track.algo,
        coverCid = track.coverCid,
        downloadedAtMs = System.currentTimeMillis(),
      )

    currentEntries = DownloadedTracksStore.upsert(context, entry)

    // Once a persistent device copy exists, drop the temporary decrypt cache file.
    val cacheRoot = File(context.cacheDir, "heaven_cloud").absolutePath
    if (prepared.file.absolutePath.startsWith(cacheRoot)) {
      runCatching { prepared.file.delete() }
    }

    onRunScan()

    if (notify) onShowMessage("Downloaded to device")
    DownloadSharedTrackResult(success = true, updatedEntries = currentEntries)
  } catch (err: Throwable) {
    Log.e(TAG_SHARED_DOWNLOAD, "download failed contentId=${track.contentId}", err)
    if (notify) onShowMessage("Download failed: ${err.message ?: "unknown error"}")
    DownloadSharedTrackResult(success = false, updatedEntries = currentEntries)
  }
}
