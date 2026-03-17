package com.pirate.app.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecentStudySetCatalogStoreTest {
  @Test
  fun decodeBuckets_dropsInvalidRows() {
    val raw =
      """
      [
        {
          "ownerAddress": "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "studySetKey": "0x0101010101010101010101010101010101010101010101010101010101010101",
          "trackId": "0x0202020202020202020202020202020202020202020202020202020202020202",
          "title": "Track",
          "artist": "Artist",
          "language": "en",
          "version": 2,
          "studySetRef": "ipfs://pack",
          "totalAttempts": 12,
          "uniqueQuestionsTouched": 6,
          "streakDays": 4,
          "pack": {
            "specVersion": "exercise-pack-v2",
            "trackId": "0x0202020202020202020202020202020202020202020202020202020202020202",
            "language": "en",
            "questions": [
              {
                "id": "0x0303030303030303030303030303030303030303030303030303030303030303",
                "questionIdHash": "0x0404040404040404040404040404040404040404040404040404040404040404",
                "type": "translation_mcq",
                "prompt": "Prompt",
                "excerpt": "Excerpt",
                "choices": ["A", "B"],
                "correctIndex": 0,
                "difficulty": "easy"
              }
            ]
          }
        },
        {
          "ownerAddress": "bad-owner",
          "studySetKey": "0x0505050505050505050505050505050505050505050505050505050505050505",
          "title": "Broken",
          "artist": "Broken"
        },
        {
          "ownerAddress": "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
          "studySetKey": "bad-key",
          "title": "Broken",
          "artist": "Broken"
        }
      ]
      """.trimIndent()

    val decoded = RecentStudySetCatalogStore.decodeBuckets(raw)

    assertEquals(1, decoded.size)
    assertEquals("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", decoded[0].ownerAddress)
    assertEquals("0x0101010101010101010101010101010101010101010101010101010101010101", decoded[0].studySetKey)
    assertEquals("Track", decoded[0].entry.title)
    assertNotNull(decoded[0].entry.pack)
  }

  @Test
  fun encodeBuckets_roundTripsNormalizedPayload() {
    val input =
      listOf(
        RecentStudySetCatalogBucket(
          ownerAddress = "0xAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
          studySetKey = "0x0101010101010101010101010101010101010101010101010101010101010101",
          entry =
            RecentStudySetCatalogEntry(
              trackId = "0x0202020202020202020202020202020202020202020202020202020202020202",
              title = " Track ",
              artist = " Artist ",
              language = "en",
              version = 2,
              studySetRef = "ipfs://pack",
              totalAttempts = 8,
              uniqueQuestionsTouched = 5,
              streakDays = 3,
              pack =
                LearnStudySetPack(
                  specVersion = "exercise-pack-v2",
                  trackId = "0x0202020202020202020202020202020202020202020202020202020202020202",
                  language = "en",
                  attributionTrack = "Track",
                  attributionArtist = "Artist",
                  questions =
                    listOf(
                      LearnStudyQuestion(
                        id = "0x0303030303030303030303030303030303030303030303030303030303030303",
                        questionIdHash = "0x0404040404040404040404040404040404040404040404040404040404040404",
                        type = "translation_mcq",
                        prompt = "Prompt",
                        excerpt = "Excerpt",
                        choices = listOf("A", "B"),
                        correctIndex = 0,
                        explanation = null,
                        difficulty = "easy",
                      ),
                    ),
                ),
            ),
          updatedAtMs = 1_700_000_000_000L,
        ),
      )

    val encoded = RecentStudySetCatalogStore.encodeBuckets(input)
    val decoded = RecentStudySetCatalogStore.decodeBuckets(encoded)

    assertEquals(1, decoded.size)
    assertEquals("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", decoded[0].ownerAddress)
    assertEquals("Track", decoded[0].entry.title)
    assertEquals("Artist", decoded[0].entry.artist)
    assertEquals(2, decoded[0].entry.version)
    assertEquals(8, decoded[0].entry.totalAttempts)
    assertEquals(3, decoded[0].entry.streakDays)
    assertNotNull(decoded[0].entry.pack)
    assertNull(decoded[0].entry.pack?.questions?.firstOrNull()?.explanation)
  }
}
