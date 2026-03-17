package com.pirate.app.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecentStudyAttemptsStoreTest {
  @Before
  fun clearStore() {
    RecentStudyAttemptsStore.clearAllForTest()
  }

  @Test
  fun mergeWithOnChain_evictsCachedAttemptWhenExactClientTimestampMatches() {
    val user = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa0"
    val studySetKey = "0x0101010101010101010101010101010101010101010101010101010101010101"
    val questionId = "0x0202020202020202020202020202020202020202020202020202020202020202"
    val ts = 1_700_000_777L

    RecentStudyAttemptsStore.recordSubmittedAttempts(
      userAddress = user,
      attempts =
        listOf(
          TempoStudyAttemptInput(
            studySetKey = studySetKey,
            questionId = questionId,
            rating = 3,
            score = 10_000,
            timestampSec = ts,
          ),
        ),
    )

    val merged =
      RecentStudyAttemptsStore.mergeWithOnChain(
        userAddress = user,
        studySetKey = studySetKey,
        onChainAttempts =
          listOf(
            StudyAttemptRow(
              questionId = questionId,
              rating = 3,
              score = 10_000,
              canonicalOrder = 7L,
              blockTimestampSec = 1_700_000_900L,
              clientTimestampSec = ts,
            ),
          ),
      )

    assertEquals(1, merged.size)
    assertEquals(7L, merged[0].canonicalOrder)
  }

  @Test
  fun mergeWithOnChain_evictsCachedAttemptWhenOnChainClientTimestampMissing() {
    val user = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"
    val studySetKey = "0x1111111111111111111111111111111111111111111111111111111111111111"
    val questionId = "0x2222222222222222222222222222222222222222222222222222222222222222"

    RecentStudyAttemptsStore.recordSubmittedAttempts(
      userAddress = user,
      attempts =
        listOf(
          TempoStudyAttemptInput(
            studySetKey = studySetKey,
            questionId = questionId,
            rating = 3,
            score = 10_000,
            timestampSec = 1_700_000_001L,
          ),
        ),
    )

    val merged =
      RecentStudyAttemptsStore.mergeWithOnChain(
        userAddress = user,
        studySetKey = studySetKey,
        onChainAttempts =
          listOf(
            StudyAttemptRow(
              questionId = questionId,
              rating = 3,
              score = 10_000,
              canonicalOrder = 42L,
              blockTimestampSec = 1_700_000_123L,
              clientTimestampSec = 0L,
            ),
          ),
      )

    assertEquals(1, merged.size)
    assertEquals(42L, merged[0].canonicalOrder)
  }

  @Test
  fun mergeWithOnChain_onlyConsumesMatchingFallbackCount() {
    val user = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa2"
    val studySetKey = "0x3333333333333333333333333333333333333333333333333333333333333333"
    val questionId = "0x4444444444444444444444444444444444444444444444444444444444444444"

    RecentStudyAttemptsStore.recordSubmittedAttempts(
      userAddress = user,
      attempts =
        listOf(
          TempoStudyAttemptInput(
            studySetKey = studySetKey,
            questionId = questionId,
            rating = 3,
            score = 10_000,
            timestampSec = 1_700_000_010L,
          ),
          TempoStudyAttemptInput(
            studySetKey = studySetKey,
            questionId = questionId,
            rating = 3,
            score = 10_000,
            timestampSec = 1_700_000_020L,
          ),
        ),
    )

    val merged =
      RecentStudyAttemptsStore.mergeWithOnChain(
        userAddress = user,
        studySetKey = studySetKey,
        onChainAttempts =
          listOf(
            StudyAttemptRow(
              questionId = questionId,
              rating = 3,
              score = 10_000,
              canonicalOrder = 99L,
              blockTimestampSec = 1_700_000_200L,
              clientTimestampSec = 0L,
            ),
          ),
      )

    assertEquals(2, merged.size)
    assertEquals(1, merged.count { it.canonicalOrder == 99L })
    assertTrue(merged.any { it.canonicalOrder > 99L })
  }

  @Test
  fun mergeWithOnChain_missingClientTimestampNeverEvictsMoreThanOnChainRows() {
    val user = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa3"
    val studySetKey = "0x5555555555555555555555555555555555555555555555555555555555555555"
    val questionId = "0x6666666666666666666666666666666666666666666666666666666666666666"
    val sharedTs = 1_700_001_000L

    RecentStudyAttemptsStore.recordSubmittedAttempts(
      userAddress = user,
      attempts =
        listOf(
          TempoStudyAttemptInput(
            studySetKey = studySetKey,
            questionId = questionId,
            rating = 3,
            score = 10_000,
            timestampSec = sharedTs,
          ),
          TempoStudyAttemptInput(
            studySetKey = studySetKey,
            questionId = questionId,
            rating = 3,
            score = 10_000,
            timestampSec = sharedTs + 1,
          ),
        ),
    )

    val merged =
      RecentStudyAttemptsStore.mergeWithOnChain(
        userAddress = user,
        studySetKey = studySetKey,
        onChainAttempts =
          listOf(
            StudyAttemptRow(
              questionId = questionId,
              rating = 3,
              score = 10_000,
              canonicalOrder = 101L,
              blockTimestampSec = sharedTs,
              clientTimestampSec = 0L,
            ),
          ),
      )

    assertEquals(2, merged.size)
    assertEquals(1, merged.count { it.canonicalOrder == 101L })
    assertEquals(1, merged.count { it.canonicalOrder > 101L })
  }
}
