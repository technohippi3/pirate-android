package sc.pirate.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLyricsRepositoryTest {
  @Test
  fun localeFallbacks_mapPortugueseToPtBr() {
    assertEquals(
      listOf("pt-PT", "pt-BR", "pt"),
      PlayerLyricsRepository.localeFallbacksForTesting("pt-PT"),
    )
    assertEquals(
      listOf("pt", "pt-BR"),
      PlayerLyricsRepository.localeFallbacksForTesting("pt"),
    )
  }

  @Test
  fun displayableLines_stripSectionHeaders() {
    val filtered =
      PlayerLyricsRepository.filterDisplayableLyricsLinesForTesting(
        listOf(
          PlayerLyricsLine(text = "[Verse 1]", sourceIndex = 0),
          PlayerLyricsLine(text = "Lady luck", sourceIndex = 1),
          PlayerLyricsLine(text = "(Chorus)", sourceIndex = 2),
          PlayerLyricsLine(text = "Lady luck again", sourceIndex = 3),
          PlayerLyricsLine(text = "Verse 2", sourceIndex = 4),
          PlayerLyricsLine(text = "Final line", sourceIndex = 5),
        ),
      )

    assertEquals(
      listOf("Lady luck", "Lady luck again", "Final line"),
      filtered.map { it.text },
    )
    assertEquals(listOf(1, 3, 5), filtered.map { it.sourceIndex })
  }

  @Test
  fun highlightedWordRange_targetsRepeatedWordOccurrence() {
    val secondLadyRange =
      highlightedWordRangeForTesting(
        lineText = "lady luck lady luck",
        words = listOf("lady", "luck", "lady", "luck"),
        activeWordIndex = 2,
      )

    assertEquals(10..13, secondLadyRange)
  }

  @Test
  fun highlightedWordRange_returnsNullWhenTokenCannotBeLocated() {
    val range =
      highlightedWordRangeForTesting(
        lineText = "plain line",
        words = listOf("missing"),
        activeWordIndex = 0,
      )

    assertNull(range)
  }

  @Test
  fun resolvedRefCache_skipsNullDocs() {
    assertFalse(PlayerLyricsRepository.cacheResolvedRefDocForTesting(doc = null))
    assertTrue(
      PlayerLyricsRepository.cacheResolvedRefDocForTesting(
        doc =
          PlayerLyricsDoc(
            lines = listOf(PlayerLyricsLine(text = "line", sourceIndex = 0)),
            timed = false,
          ),
      ),
    )
  }

  @Test
  fun parseStatusLyricsRefs_prefersManifestRefFirst() {
    val body = """{"lyrics":{"manifestRef":"ar://manifest123","timedRef":"ar://timed123","textRef":"ar://text123"}}"""
    assertEquals(
      listOf("ar://manifest123", "ar://timed123", "ar://text123"),
      PlayerLyricsRepository.parseStatusLyricsRefsForTesting(body),
    )
  }

  @Test
  fun parseStatusLyricsRefs_skipsNullManifestReturnsTimedAndText() {
    val body = """{"lyrics":{"manifestRef":null,"timedRef":"ar://timed123","textRef":"ar://text123"}}"""
    assertEquals(
      listOf("ar://timed123", "ar://text123"),
      PlayerLyricsRepository.parseStatusLyricsRefsForTesting(body),
    )
  }

  @Test
  fun parseStatusLyricsRefs_returnsTextRefOnlyWhenOthersAbsent() {
    val body = """{"lyrics":{"textRef":"ar://text123"}}"""
    assertEquals(
      listOf("ar://text123"),
      PlayerLyricsRepository.parseStatusLyricsRefsForTesting(body),
    )
  }

  @Test
  fun parseStatusLyricsRefs_returnsEmptyWhenLyricsObjectMissing() {
    val body = """{"trackId":"0x1234"}"""
    assertEquals(emptyList<String>(), PlayerLyricsRepository.parseStatusLyricsRefsForTesting(body))
  }
}
