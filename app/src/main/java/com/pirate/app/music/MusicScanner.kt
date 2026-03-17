package com.pirate.app.music

internal suspend fun runScan(
  context: android.content.Context,
  onShowMessage: (String) -> Unit,
  silent: Boolean,
  setScanning: (Boolean) -> Unit,
  setTracks: (List<MusicTrack>) -> Unit,
  setError: (String?) -> Unit,
) {
  // Guard against overlapping scans; `scope` is stable per composition, but callers can still spam.
  // Use the UI state lock for now.
  // (If this becomes flaky, move scanning to a ViewModel with an atomic guard.)
  setScanning(true)
  setError(null)
  val result = runCatching { MusicLibrary.scanDeviceTracks(context) }
  result.onFailure { err ->
    setScanning(false)
    setError(err.message ?: "Failed to scan")
    if (!silent) onShowMessage(err.message ?: "Scan failed")
  }
  result.onSuccess { list ->
    val cached = runCatching { MusicLibrary.loadCachedTracks(context) }.getOrElse { emptyList() }
    val cachedById = cached.associateBy { it.id }
    val cachedOrder = cached.map { it.id }
    val downloadedByUri =
      runCatching { DownloadedTracksStore.load(context).values.associateBy { it.mediaUri } }
        .getOrElse { emptyMap() }

    val merged =
      list.map { scanned ->
        val downloaded = downloadedByUri[scanned.uri]
        if (downloaded != null) {
          val mergedTitle =
            when {
              scanned.title.isBlank() -> downloaded.title
              scanned.title == "(untitled)" && downloaded.title.isNotBlank() -> downloaded.title
              else -> scanned.title
            }
          val mergedArtist =
            when {
              scanned.artist.isBlank() -> downloaded.artist
              scanned.artist.equals("unknown artist", ignoreCase = true) && downloaded.artist.isNotBlank() -> downloaded.artist
              else -> scanned.artist
            }
          val mergedAlbum = if (scanned.album.isBlank()) downloaded.album else scanned.album

          return@map scanned.copy(
            title = mergedTitle,
            artist = mergedArtist,
            album = mergedAlbum,
            contentId = downloaded.contentId,
            pieceCid = null,
            datasetOwner = null,
            algo = null,
            permanentRef = null,
            permanentGatewayUrl = null,
            permanentSavedAtMs = null,
            savedForever = false,
          )
        }

        val prior = cachedById[scanned.id] ?: return@map scanned
        scanned.copy(
          contentId = prior.contentId,
          pieceCid = prior.pieceCid,
          datasetOwner = prior.datasetOwner,
          algo = prior.algo,
          permanentRef = prior.permanentRef,
          permanentGatewayUrl = prior.permanentGatewayUrl,
          permanentSavedAtMs = prior.permanentSavedAtMs,
          savedForever = prior.savedForever,
        )
      }

    val mergedById = merged.associateBy { it.id }
    val stable = ArrayList<MusicTrack>(merged.size)
    for (id in cachedOrder) {
      val track = mergedById[id] ?: continue
      stable.add(track)
    }
    for (track in merged) {
      if (stable.any { it.id == track.id }) continue
      stable.add(track)
    }

    setTracks(stable)
    MusicLibrary.saveCachedTracks(context, stable)
    setScanning(false)
  }
}
