package sc.pirate.app.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnPendingSyncStoreTest {
  @Test
  fun decodeBuckets_dropsInvalidRows() {
    val raw =
      """
      [
        {
          "ownerAddress": "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "attempts": [
            {
              "studySetKey": "0x0101010101010101010101010101010101010101010101010101010101010101",
              "questionId": "0x0202020202020202020202020202020202020202020202020202020202020202",
              "rating": 3,
              "score": 10000,
              "timestampSec": 1700000000
            }
          ],
          "streakClaims": [
            {
              "studySetKey": "0x0101010101010101010101010101010101010101010101010101010101010101",
              "dayUtc": 20000,
              "nonce": 7,
              "expirySec": 1700000100,
              "signatureHex": "0x${"ab".repeat(65)}"
            }
          ],
          "updatedAtMs": 1700000000123
        },
        {
          "ownerAddress": "not-an-address",
          "attempts": [],
          "streakClaims": []
        },
        {
          "ownerAddress": "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
          "attempts": [
            {
              "studySetKey": "bad",
              "questionId": "0x0303030303030303030303030303030303030303030303030303030303030303",
              "rating": 3,
              "score": 10000,
              "timestampSec": 1700000001
            }
          ],
          "streakClaims": []
        }
      ]
      """.trimIndent()

    val decoded = LearnPendingSyncStore.decodeBuckets(raw)

    assertEquals(1, decoded.size)
    assertEquals("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", decoded[0].ownerAddress)
    assertEquals(1, decoded[0].attempts.size)
    assertEquals(1, decoded[0].streakClaims.size)
  }

  @Test
  fun encodeBuckets_roundTripsNormalizedPayload() {
    val input =
      listOf(
        LearnPendingSyncBucket(
          ownerAddress = "0xAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
          attempts =
            listOf(
              StudyAttemptInput(
                studySetKey = "0x0101010101010101010101010101010101010101010101010101010101010101",
                questionId = "0x0202020202020202020202020202020202020202020202020202020202020202",
                rating = 3,
                score = 10_000,
                timestampSec = 1_700_000_000L,
              ),
            ),
          streakClaims =
            listOf(
              StreakClaimInput(
                studySetKey = "0x0303030303030303030303030303030303030303030303030303030303030303",
                dayUtc = 20_000L,
                nonce = 4L,
                expirySec = 1_700_000_123L,
                signatureHex = "0x${"cd".repeat(65)}",
              ),
            ),
          updatedAtMs = 1_700_000_999_000L,
        ),
      )

    val encoded = LearnPendingSyncStore.encodeBuckets(input)
    val decoded = LearnPendingSyncStore.decodeBuckets(encoded)

    assertEquals(1, decoded.size)
    assertEquals("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", decoded[0].ownerAddress)
    assertEquals(1, decoded[0].attempts.size)
    assertEquals(1, decoded[0].streakClaims.size)
    assertEquals(3, decoded[0].attempts[0].rating)
    assertTrue(decoded[0].streakClaims[0].signatureHex.startsWith("0x"))
  }
}
