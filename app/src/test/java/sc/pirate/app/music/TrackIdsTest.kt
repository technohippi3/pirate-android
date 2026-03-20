package sc.pirate.app.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TrackIdsTest {
  @Test
  fun computeScrobbleMetaParts_preservesExactSubmittedStrings() {
    val exact = TrackIds.computeScrobbleMetaParts(
      title = "Human After All",
      artist = "Daft Punk",
      album = "Human After All",
    )
    val normalized = TrackIds.computeMetaParts(
      title = "Human After All",
      artist = "Daft Punk",
      album = "Human After All",
    )

    assertNotEquals(normalized.payload.toList(), exact.payload.toList())
    assertNotEquals(normalized.trackId.toList(), exact.trackId.toList())
  }

  @Test
  fun computeScrobbleMetaParts_matchesExactInputWhenAlreadyNormalized() {
    val exact = TrackIds.computeScrobbleMetaParts(
      title = "human after all",
      artist = "daft punk",
      album = "human after all",
    )
    val normalized = TrackIds.computeMetaParts(
      title = "human after all",
      artist = "daft punk",
      album = "human after all",
    )

    assertEquals(normalized.payload.toList(), exact.payload.toList())
    assertEquals(normalized.trackId.toList(), exact.trackId.toList())
  }
}
