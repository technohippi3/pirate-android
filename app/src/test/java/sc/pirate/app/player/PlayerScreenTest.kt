package sc.pirate.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScreenTest {
  @Test
  fun currentWordIndex_staysOnPreviousWordInsideGap() {
    val words =
      listOf(
        PlayerLyricsWord(text = "lady", startMs = 0L, endMs = 300L),
        PlayerLyricsWord(text = "luck", startMs = 500L, endMs = 800L),
        PlayerLyricsWord(text = "lady", startMs = 1000L, endMs = 1300L),
        PlayerLyricsWord(text = "luck", startMs = 1500L, endMs = 1800L),
      )

    assertEquals(1, currentWordIndexForTesting(words = words, positionSec = 0.9f))
    assertEquals(2, currentWordIndexForTesting(words = words, positionSec = 1.4f))
  }

  @Test
  fun currentWordIndex_doesNotHighlightBeforeFirstWordStarts() {
    val words =
      listOf(
        PlayerLyricsWord(text = "lady", startMs = 500L, endMs = 800L),
        PlayerLyricsWord(text = "luck", startMs = 900L, endMs = 1200L),
      )

    assertEquals(null, currentWordIndexForTesting(words = words, positionSec = 0.1f))
    assertEquals(0, currentWordIndexForTesting(words = words, positionSec = 0.5f))
  }

  @Test
  fun highlightedWordRange_targetsRepeatedWordAfterComma() {
    val secondLadyRange =
      highlightedWordRangeForTesting(
        lineText = "lady luck, lady luck",
        words = listOf("lady", "luck", "lady", "luck"),
        activeWordIndex = 2,
      )

    assertEquals(11..14, secondLadyRange)
  }

  @Test
  fun canvasLayout_requiresCanvasUrl() {
    assertTrue(
      shouldUseCanvasPlayerLayoutForTesting(
        canvasUrl = "https://cdn.example.com/canvas.mp4",
      )
    )
    assertFalse(
      shouldUseCanvasPlayerLayoutForTesting(
        canvasUrl = null,
      )
    )
  }

  @Test
  fun canvasBackdrop_hidesAfterPlaybackFailure_withoutLeavingCanvasLayout() {
    assertTrue(
      shouldUseCanvasPlayerLayoutForTesting(
        canvasUrl = "https://cdn.example.com/canvas.mp4",
      )
    )
    assertTrue(
      shouldRenderCanvasBackdropForTesting(
        canvasUrl = "https://cdn.example.com/canvas.mp4",
        canvasVideoFailed = false,
      ),
    )
    assertFalse(
      shouldRenderCanvasBackdropForTesting(
        canvasUrl = "https://cdn.example.com/canvas.mp4",
        canvasVideoFailed = true,
      ),
    )
  }

  @Test
  fun canvasPlayback_holdsAudioUntilCanvasReady() {
    assertTrue(
      shouldHoldPlaybackUntilCanvasReadyForTesting(
        showCanvasLoadingState = true,
        useCanvasPlayerLayout = false,
        hasCanvasBackdrop = false,
        canvasVideoReady = false,
      ),
    )
    assertTrue(
      shouldHoldPlaybackUntilCanvasReadyForTesting(
        showCanvasLoadingState = false,
        useCanvasPlayerLayout = true,
        hasCanvasBackdrop = true,
        canvasVideoReady = false,
      ),
    )
    assertFalse(
      shouldHoldPlaybackUntilCanvasReadyForTesting(
        showCanvasLoadingState = false,
        useCanvasPlayerLayout = true,
        hasCanvasBackdrop = true,
        canvasVideoReady = true,
      ),
    )
  }

  @Test
  fun canvasVideo_keepsRunningDuringAudioHold() {
    assertTrue(
      shouldRunCanvasVideoForTesting(
        hasCanvasBackdrop = true,
        isPlaying = false,
        holdPlaybackUntilCanvasReady = true,
      ),
    )
    assertFalse(
      shouldRunCanvasVideoForTesting(
        hasCanvasBackdrop = false,
        isPlaying = true,
        holdPlaybackUntilCanvasReady = true,
      ),
    )
    assertFalse(
      shouldRunCanvasVideoForTesting(
        hasCanvasBackdrop = true,
        isPlaying = false,
        holdPlaybackUntilCanvasReady = false,
      ),
    )
  }

  @Test
  fun topBarBuyAction_isHiddenForCanvasMenuMode() {
    assertEquals(
      "BUY",
      resolvePlayerTopBarActionForTesting(
        isPreviewOnly = true,
        hasOwnedEntitlement = false,
        immersive = false,
      )
    )
    assertEquals(
      "NONE",
      resolvePlayerTopBarActionForTesting(
        isPreviewOnly = true,
        hasOwnedEntitlement = false,
        immersive = true,
      )
    )
  }

  @Test
  fun untimedLyrics_doNotAutoAdvanceByDurationRatio() {
    val lyrics =
      PlayerLyricsDoc(
        lines =
          listOf(
            PlayerLyricsLine(text = "line 1", sourceIndex = 0),
            PlayerLyricsLine(text = "line 2", sourceIndex = 1),
            PlayerLyricsLine(text = "line 3", sourceIndex = 2),
          ),
        timed = false,
      )

    assertEquals(
      0,
      currentLyricsIndexForTesting(
        lyrics = lyrics,
        positionSec = 120f,
        durationSec = 180f,
      ),
    )
  }

  @Test
  fun timedLyrics_doNotShowBeforeFirstLineStarts() {
    val lyrics =
      PlayerLyricsDoc(
        lines =
          listOf(
            PlayerLyricsLine(text = "line 1", sourceIndex = 0, startMs = 1000L, endMs = 2000L),
            PlayerLyricsLine(text = "line 2", sourceIndex = 1, startMs = 2500L, endMs = 3500L),
          ),
        timed = true,
      )

    assertEquals(
      -1,
      currentLyricsIndexForTesting(
        lyrics = lyrics,
        positionSec = 0.5f,
        durationSec = 180f,
      ),
    )
    assertEquals(
      0,
      currentLyricsIndexForTesting(
        lyrics = lyrics,
        positionSec = 1.1f,
        durationSec = 180f,
      ),
    )
  }

  @Test
  fun canvasLyrics_showFirstLineBeforeFirstLineStarts() {
    val lyrics =
      PlayerLyricsDoc(
        lines =
          listOf(
            PlayerLyricsLine(text = "line 1", sourceIndex = 0, startMs = 1000L, endMs = 2000L),
            PlayerLyricsLine(text = "line 2", sourceIndex = 1, startMs = 2500L, endMs = 3500L),
          ),
        timed = true,
      )

    assertEquals(
      0,
      currentCanvasLyricsIndexForTesting(
        lyrics = lyrics,
        positionSec = 0.5f,
        durationSec = 180f,
      ),
    )
  }

  @Test
  fun canvasTranslationToggle_onlyAppearsForDistinctTranslations() {
    val withTranslation =
      PlayerLyricsDoc(
        lines =
          listOf(
            PlayerLyricsLine(
              text = "hola",
              sourceIndex = 0,
              translationText = "hello",
            ),
          ),
        timed = true,
      )
    val withoutDistinctTranslation =
      PlayerLyricsDoc(
        lines =
          listOf(
            PlayerLyricsLine(
              text = "hello",
              sourceIndex = 0,
              translationText = "hello",
            ),
            PlayerLyricsLine(
              text = "world",
              sourceIndex = 1,
              translationText = null,
            ),
          ),
        timed = true,
      )

    assertTrue(hasCanvasLineTranslationsForTesting(withTranslation))
    assertFalse(hasCanvasLineTranslationsForTesting(withoutDistinctTranslation))
  }
}
