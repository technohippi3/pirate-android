package com.pirate.app.scrobble

import com.pirate.app.BuildConfig

/**
 * Scrobble thresholds (production):
 * - scrobble when >= 50% listened, capped at 4 minutes
 * - short/unknown tracks require the max threshold to avoid false positives
 */
private const val MAX_SCROBBLE_THRESHOLD_MS = 240_000L
private const val MIN_DURATION_FOR_SCROBBLE_MS = 30_000L
private const val DEBUG_SCROBBLE_THRESHOLD_MS = 3_000L

data class ReadyScrobble(
  val artist: String,
  val title: String,
  val album: String?,
  val durationMs: Long?,
  val playedAtSec: Long,
  val artworkUri: String? = null,
  val artworkFallbackUri: String? = null,
)

data class TrackMetadata(
  val artist: String,
  val title: String,
  val album: String? = null,
  val durationMs: Long? = null,
  val artworkUri: String? = null,
  val artworkFallbackUri: String? = null,
  val isScrobblable: Boolean = true,
)

private data class SessionState(
  val sessionKey: String,
  var trackKey: String? = null,
  var artist: String? = null,
  var title: String? = null,
  var album: String? = null,
  var durationMs: Long? = null,
  var artworkUri: String? = null,
  var artworkFallbackUri: String? = null,
  var startedAtEpochSec: Long? = null,
  var accumulatedPlayMs: Long = 0L,
  var lastUpdateTimeMs: Long = 0L,
  var isPlaying: Boolean = false,
  var alreadyScrobbled: Boolean = false,
)

class ScrobbleEngine(
  private val onScrobbleReady: (ReadyScrobble) -> Unit,
  private val currentTimeMs: () -> Long = { System.currentTimeMillis() },
) {
  private val sessions = HashMap<String, SessionState>()

  fun onMetadata(sessionKey: String, metadata: TrackMetadata) {
    val state = sessions.getOrPut(sessionKey) { SessionState(sessionKey) }

    val newTrackKey =
      buildTrackKey(
        source = sessionKey,
        artist = metadata.artist,
        title = metadata.title,
        album = metadata.album,
        durationMs = metadata.durationMs,
        isScrobblable = metadata.isScrobblable,
      )

    if (newTrackKey != state.trackKey && state.trackKey != null) {
      finalizeTrack(state)
    }

    state.trackKey = newTrackKey
    state.artist = metadata.artist
    state.title = metadata.title
    state.album = metadata.album
    state.durationMs = metadata.durationMs
    state.artworkUri = metadata.artworkUri
    state.artworkFallbackUri = metadata.artworkFallbackUri

    if (newTrackKey != null && state.isPlaying && state.startedAtEpochSec == null) {
      state.startedAtEpochSec = nowEpochSec()
      state.lastUpdateTimeMs = nowMs()
    }
  }

  fun onPlayback(sessionKey: String, isPlaying: Boolean) {
    val state = sessions.getOrPut(sessionKey) { SessionState(sessionKey) }

    val wasPlaying = state.isPlaying
    if (wasPlaying && !isPlaying) {
      accumulatePlayTime(state)
      state.isPlaying = false
      return
    }

    if (!wasPlaying && isPlaying) {
      state.isPlaying = true
      state.lastUpdateTimeMs = nowMs()
      if (state.startedAtEpochSec == null && state.trackKey != null) {
        state.startedAtEpochSec = nowEpochSec()
      }
    }
  }

  fun onSessionGone(sessionKey: String) {
    val state = sessions.remove(sessionKey) ?: return
    finalizeTrack(state)
  }

  fun tick() {
    for (state in sessions.values) {
      if (!state.isPlaying || state.alreadyScrobbled) continue

      accumulatePlayTime(state)

      val artist = state.artist
      val title = state.title
      val startedAt = state.startedAtEpochSec
      if (artist.isNullOrBlank() || title.isNullOrBlank() || startedAt == null) continue

      val threshold = computeThreshold(state.durationMs)
      if (state.accumulatedPlayMs >= threshold) {
        state.alreadyScrobbled = true
        onScrobbleReady(
          ReadyScrobble(
            artist = artist,
            title = title,
            album = state.album,
            durationMs = state.durationMs,
            playedAtSec = startedAt,
            artworkUri = state.artworkUri,
            artworkFallbackUri = state.artworkFallbackUri,
          ),
        )
      }
    }
  }

  private fun accumulatePlayTime(state: SessionState) {
    if (!state.isPlaying || state.trackKey == null) return
    val now = nowMs()
    val elapsed = now - state.lastUpdateTimeMs
    if (elapsed > 0) {
      state.accumulatedPlayMs += elapsed
      state.lastUpdateTimeMs = now
    }
  }

  private fun finalizeTrack(state: SessionState) {
    if (state.isPlaying) {
      accumulatePlayTime(state)
    }

    val artist = state.artist
    val title = state.title
    val album = state.album
    val durationMs = state.durationMs
    val artworkUri = state.artworkUri
    val artworkFallbackUri = state.artworkFallbackUri
    val startedAt = state.startedAtEpochSec
    val accumulated = state.accumulatedPlayMs
    val alreadyScrobbled = state.alreadyScrobbled

    // Reset state
    state.trackKey = null
    state.artist = null
    state.title = null
    state.album = null
    state.durationMs = null
    state.artworkUri = null
    state.artworkFallbackUri = null
    state.startedAtEpochSec = null
    state.accumulatedPlayMs = 0L
    state.lastUpdateTimeMs = 0L
    state.alreadyScrobbled = false

    if (alreadyScrobbled) return
    if (artist.isNullOrBlank() || title.isNullOrBlank() || startedAt == null) return

    val threshold = computeThreshold(durationMs)
    if (accumulated >= threshold) {
      onScrobbleReady(
        ReadyScrobble(
          artist = artist,
          title = title,
          album = album,
          durationMs = durationMs,
          playedAtSec = startedAt,
          artworkUri = artworkUri,
          artworkFallbackUri = artworkFallbackUri,
        ),
      )
    }
  }

  private fun nowMs(): Long = currentTimeMs()

  private fun nowEpochSec(): Long = nowMs() / 1000L
}

private fun buildTrackKey(
  source: String,
  artist: String?,
  title: String?,
  album: String?,
  durationMs: Long?,
  isScrobblable: Boolean,
): String? {
  if (!isScrobblable || artist.isNullOrBlank() || title.isNullOrBlank()) return null
  return "$source|$artist|$title|${album.orEmpty()}|${durationMs ?: 0L}"
}

private fun computeThreshold(durationMs: Long?): Long {
  if (BuildConfig.DEBUG) {
    return DEBUG_SCROBBLE_THRESHOLD_MS
  }
  if (durationMs != null && durationMs >= MIN_DURATION_FOR_SCROBBLE_MS) {
    return minOf(durationMs / 2, MAX_SCROBBLE_THRESHOLD_MS)
  }
  return MAX_SCROBBLE_THRESHOLD_MS
}
