package sc.pirate.app.player

import sc.pirate.app.music.MusicTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerQueuePolicyTest {
  @Test
  fun buildPlaybackQueue_keepsCurrentTrackFirstWhenShuffleEnabled() {
    val queue = listOf(track("a"), track("b"), track("c"))

    val shuffled =
      buildPlaybackQueue(
        baseQueue = queue,
        startIndex = 1,
        shuffleEnabled = true,
        shuffleTail = { tracks -> tracks.reversed() },
      )

    assertEquals(listOf("b", "c", "a"), shuffled.queue.map { it.id })
    assertEquals(0, shuffled.index)
  }

  @Test
  fun resolvePlaybackQueueForCurrentTrack_restoresOriginalOrderWhenShuffleDisabled() {
    val queue = listOf(track("a"), track("b"), track("c"))

    val restored =
      resolvePlaybackQueueForCurrentTrack(
        baseQueue = queue,
        currentTrack = queue[2],
        shuffleEnabled = false,
      )

    requireNotNull(restored)
    assertEquals(listOf("a", "b", "c"), restored.queue.map { it.id })
    assertEquals(2, restored.index)
  }

  @Test
  fun trackEndQueueIndex_cyclesAccordingToRepeatMode() {
    assertNull(trackEndQueueIndex(currentIndex = 2, lastIndex = 2, repeatMode = PlayerRepeatMode.OFF))
    assertEquals(0, trackEndQueueIndex(currentIndex = 2, lastIndex = 2, repeatMode = PlayerRepeatMode.ALL))
    assertEquals(2, trackEndQueueIndex(currentIndex = 2, lastIndex = 2, repeatMode = PlayerRepeatMode.ONE))
  }

  @Test
  fun manualQueueIndexWrapsOnlyForRepeatAll() {
    assertEquals(0, manualNextQueueIndex(currentIndex = 0, lastIndex = 0, repeatMode = PlayerRepeatMode.ALL))
    assertNull(manualNextQueueIndex(currentIndex = 0, lastIndex = 0, repeatMode = PlayerRepeatMode.ONE))
    assertEquals(2, manualPreviousQueueIndex(currentIndex = 0, lastIndex = 2, repeatMode = PlayerRepeatMode.ALL))
    assertNull(manualPreviousQueueIndex(currentIndex = 0, lastIndex = 2, repeatMode = PlayerRepeatMode.OFF))
  }

  @Test
  fun nextRepeatMode_cyclesOffAllOne() {
    assertEquals(PlayerRepeatMode.ALL, nextRepeatMode(PlayerRepeatMode.OFF))
    assertEquals(PlayerRepeatMode.ONE, nextRepeatMode(PlayerRepeatMode.ALL))
    assertEquals(PlayerRepeatMode.OFF, nextRepeatMode(PlayerRepeatMode.ONE))
  }

  @Test
  fun playbackStartRepeatMode_defaultsSingleTrackSessionsToLoop() {
    assertEquals(
      PlayerRepeatMode.ONE,
      playbackStartRepeatMode(
        currentRepeatMode = PlayerRepeatMode.OFF,
        currentQueueSize = 0,
        nextQueueSize = 1,
      ),
    )
  }

  @Test
  fun playbackStartRepeatMode_clearsAutoSingleTrackLoopWhenEnteringMultiTrackQueue() {
    assertEquals(
      PlayerRepeatMode.OFF,
      playbackStartRepeatMode(
        currentRepeatMode = PlayerRepeatMode.ONE,
        currentQueueSize = 1,
        nextQueueSize = 3,
      ),
    )
    assertEquals(
      PlayerRepeatMode.ALL,
      playbackStartRepeatMode(
        currentRepeatMode = PlayerRepeatMode.ALL,
        currentQueueSize = 4,
        nextQueueSize = 3,
      ),
    )
  }

  private fun track(id: String): MusicTrack {
    return MusicTrack(
      id = id,
      title = id,
      artist = "artist",
      album = "album",
      durationSec = 180,
      uri = "file:///tmp/$id.mp3",
      filename = "$id.mp3",
    )
  }
}
