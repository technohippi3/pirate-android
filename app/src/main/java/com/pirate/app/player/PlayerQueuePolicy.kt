package com.pirate.app.player

import com.pirate.app.music.MusicTrack

enum class PlayerRepeatMode {
  OFF,
  ALL,
  ONE,
}

internal data class PlayerQueueState(
  val queue: List<MusicTrack>,
  val index: Int,
)

internal fun buildPlaybackQueue(
  baseQueue: List<MusicTrack>,
  startIndex: Int,
  shuffleEnabled: Boolean,
  shuffleTail: (List<MusicTrack>) -> List<MusicTrack> = { tracks -> tracks.shuffled() },
): PlayerQueueState {
  if (baseQueue.isEmpty()) return PlayerQueueState(queue = emptyList(), index = 0)

  val safeIndex = startIndex.coerceIn(0, baseQueue.lastIndex)
  if (!shuffleEnabled || baseQueue.size == 1) {
    return PlayerQueueState(queue = baseQueue, index = safeIndex)
  }

  val current = baseQueue[safeIndex]
  val tail = baseQueue.filterIndexed { index, _ -> index != safeIndex }
  return PlayerQueueState(
    queue = listOf(current) + shuffleTail(tail),
    index = 0,
  )
}

internal fun resolvePlaybackQueueForCurrentTrack(
  baseQueue: List<MusicTrack>,
  currentTrack: MusicTrack,
  shuffleEnabled: Boolean,
  shuffleTail: (List<MusicTrack>) -> List<MusicTrack> = { tracks -> tracks.shuffled() },
): PlayerQueueState? {
  val currentIndex = findPlaybackTrackIndex(baseQueue, currentTrack)
  if (currentIndex < 0) return null
  return buildPlaybackQueue(
    baseQueue = baseQueue,
    startIndex = currentIndex,
    shuffleEnabled = shuffleEnabled,
    shuffleTail = shuffleTail,
  )
}

internal fun findPlaybackTrackIndex(
  queue: List<MusicTrack>,
  track: MusicTrack,
): Int {
  return queue.indexOfFirst { candidate -> tracksSharePlaybackIdentity(candidate, track) }
}

internal fun trackEndQueueIndex(
  currentIndex: Int,
  lastIndex: Int,
  repeatMode: PlayerRepeatMode,
): Int? {
  if (lastIndex < 0) return null
  if (currentIndex < lastIndex) return currentIndex + 1
  return when (repeatMode) {
    PlayerRepeatMode.OFF -> null
    PlayerRepeatMode.ALL -> 0
    PlayerRepeatMode.ONE -> currentIndex.coerceIn(0, lastIndex)
  }
}

internal fun manualNextQueueIndex(
  currentIndex: Int,
  lastIndex: Int,
  repeatMode: PlayerRepeatMode,
): Int? {
  if (lastIndex < 0) return null
  if (currentIndex < lastIndex) return currentIndex + 1
  return if (repeatMode == PlayerRepeatMode.ALL) 0 else null
}

internal fun manualPreviousQueueIndex(
  currentIndex: Int,
  lastIndex: Int,
  repeatMode: PlayerRepeatMode,
): Int? {
  if (lastIndex < 0) return null
  if (currentIndex > 0) return currentIndex - 1
  return if (repeatMode == PlayerRepeatMode.ALL) lastIndex else null
}

internal fun nextRepeatMode(current: PlayerRepeatMode): PlayerRepeatMode {
  return when (current) {
    PlayerRepeatMode.OFF -> PlayerRepeatMode.ALL
    PlayerRepeatMode.ALL -> PlayerRepeatMode.ONE
    PlayerRepeatMode.ONE -> PlayerRepeatMode.OFF
  }
}

internal fun playbackStartRepeatMode(
  currentRepeatMode: PlayerRepeatMode,
  currentQueueSize: Int,
  nextQueueSize: Int,
): PlayerRepeatMode {
  if (nextQueueSize <= 0) return currentRepeatMode
  if (nextQueueSize == 1) {
    return if (currentRepeatMode == PlayerRepeatMode.OFF) PlayerRepeatMode.ONE else currentRepeatMode
  }
  if (currentQueueSize == 1 && currentRepeatMode == PlayerRepeatMode.ONE) {
    return PlayerRepeatMode.OFF
  }
  return currentRepeatMode
}

private fun tracksSharePlaybackIdentity(
  left: MusicTrack,
  right: MusicTrack,
): Boolean {
  return left.id == right.id && left.uri.trim() == right.uri.trim()
}
