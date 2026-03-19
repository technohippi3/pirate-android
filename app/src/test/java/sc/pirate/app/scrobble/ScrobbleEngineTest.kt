package sc.pirate.app.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrobbleEngineTest {
  @Test
  fun looksLikeSpotifyAdvertisement_matchesAdvertisementSignals() {
    assertTrue(looksLikeSpotifyAdvertisement(artist = "Spotify", title = "Advertisement"))
    assertTrue(looksLikeSpotifyAdvertisement(artist = "Brand", title = "Sale", mediaId = "spotify:ad:123"))
    assertTrue(looksLikeSpotifyAdvertisement(artist = "Artist", title = "Track", album = "Advertisement"))
    assertFalse(looksLikeSpotifyAdvertisement(artist = "Spotify Singles", title = "Session Track"))
  }

  @Test
  fun nonScrobblableMetadata_doesNotConsumePlaybackForNextTrack() {
    val ready = mutableListOf<ReadyScrobble>()
    var nowMs = 0L
    val engine = ScrobbleEngine(onScrobbleReady = { ready += it }, currentTimeMs = { nowMs })

    engine.onPlayback("spotify", true)
    engine.onMetadata(
      "spotify",
      TrackMetadata(
        artist = "Artist One",
        title = "Song One",
        durationMs = 300_000L,
      ),
    )

    nowMs = 1_000L
    engine.onMetadata(
      "spotify",
      TrackMetadata(
        artist = "Spotify",
        title = "Advertisement",
        durationMs = 30_000L,
        isScrobblable = false,
      ),
    )

    nowMs = 10_000L
    engine.tick()
    assertTrue(ready.isEmpty())

    engine.onMetadata(
      "spotify",
      TrackMetadata(
        artist = "Artist Two",
        title = "Song Two",
        durationMs = 300_000L,
      ),
    )

    nowMs = 170_000L
    engine.tick()

    assertEquals(listOf("Song Two"), ready.map { it.title })
  }
}
