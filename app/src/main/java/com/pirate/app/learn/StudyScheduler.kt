package com.pirate.app.learn

import kotlin.math.roundToLong

private const val SECONDS_PER_DAY = 86_400L
private const val MAX_INTERVAL_DAYS = 36_500.0

enum class StudyCardState {
  NEW,
  LEARNING,
  REVIEW,
  RELEARNING,
}

data class StudyCardQueueItem(
  val questionId: String,
  val dueAtSec: Long,
  val stabilityDays: Double,
  val difficulty: Double,
  val reps: Int,
  val lapses: Int,
  val state: StudyCardState,
  val lastReviewAtSec: Long?,
  val lastRating: Int,
  val lastScore: Int,
)

data class StudyQueueSnapshot(
  val generatedAtSec: Long,
  val trackedCards: Int,
  val dueCount: Int,
  val learningCount: Int,
  val reviewCount: Int,
  val relearningCount: Int,
  val dueCards: List<StudyCardQueueItem>,
  val cards: List<StudyCardQueueItem>,
)

private data class MutableCard(
  val questionId: String,
  var dueAtSec: Long = 0L,
  var stabilityDays: Double = 0.0,
  var difficulty: Double = 5.0,
  var reps: Int = 0,
  var lapses: Int = 0,
  var state: StudyCardState = StudyCardState.NEW,
  var lastReviewAtSec: Long? = null,
  var lastRating: Int = 0,
  var lastScore: Int = 0,
)

object StudyScheduler {
  fun replay(
    attempts: List<StudyAttemptRow>,
    nowSec: Long = System.currentTimeMillis() / 1_000L,
  ): StudyQueueSnapshot {
    if (attempts.isEmpty()) {
      return StudyQueueSnapshot(
        generatedAtSec = nowSec,
        trackedCards = 0,
        dueCount = 0,
        learningCount = 0,
        reviewCount = 0,
        relearningCount = 0,
        dueCards = emptyList(),
        cards = emptyList(),
      )
    }

    val sortedAttempts = attempts.sortedWith(
      compareBy<StudyAttemptRow>({ it.canonicalOrder }, { it.blockTimestampSec }, { it.questionId }),
    )

    val cardsByQuestion = LinkedHashMap<String, MutableCard>()
    for (attempt in sortedAttempts) {
      val questionId = attempt.questionId.lowercase()
      val card = cardsByQuestion.getOrPut(questionId) { MutableCard(questionId = questionId) }
      applyAttempt(card, attempt)
    }

    val cards = cardsByQuestion.values
      .map { it.toQueueItem() }
      .sortedWith(compareBy<StudyCardQueueItem>({ it.dueAtSec }, { it.questionId }))

    val dueCards = cards.filter { it.dueAtSec <= nowSec }

    return StudyQueueSnapshot(
      generatedAtSec = nowSec,
      trackedCards = cards.size,
      dueCount = dueCards.size,
      learningCount = cards.count { it.state == StudyCardState.LEARNING },
      reviewCount = cards.count { it.state == StudyCardState.REVIEW },
      relearningCount = cards.count { it.state == StudyCardState.RELEARNING },
      dueCards = dueCards,
      cards = cards,
    )
  }

  private fun applyAttempt(card: MutableCard, attempt: StudyAttemptRow) {
    val rating = normalizeRating(attempt.rating, attempt.score)
    val score = attempt.score.coerceIn(0, 10_000)
    val reviewTs = when {
      attempt.blockTimestampSec > 0L -> attempt.blockTimestampSec
      attempt.clientTimestampSec > 0L -> attempt.clientTimestampSec
      else -> System.currentTimeMillis() / 1_000L
    }

    val previousReps = card.reps
    val previousStability = if (card.stabilityDays > 0.0) card.stabilityDays else 1.0

    val rawIntervalDays = if (previousReps == 0) {
      initialIntervalDays(rating)
    } else {
      var interval = previousStability * ratingMultiplier(rating) * scoreMultiplier(score)
      if (rating == 1) {
        interval = (previousStability * 0.2).coerceIn(0.0, 0.5)
      }
      interval
    }

    val clampedIntervalDays = rawIntervalDays.coerceIn(0.0, MAX_INTERVAL_DAYS)
    val dueAtSec = if (clampedIntervalDays <= 0.0) {
      reviewTs
    } else {
      reviewTs + (clampedIntervalDays * SECONDS_PER_DAY.toDouble()).roundToLong()
    }

    val difficultyDelta = difficultyDelta(rating, score)
    card.difficulty = round2((card.difficulty + difficultyDelta).coerceIn(1.0, 10.0))

    val stabilityBase = if (previousReps == 0) {
      clampedIntervalDays
    } else {
      (previousStability * 0.65) + (clampedIntervalDays * 0.35)
    }
    card.stabilityDays = round2(stabilityBase.coerceIn(0.1, MAX_INTERVAL_DAYS))

    if (rating == 1) {
      card.lapses += 1
      card.state = if (previousReps <= 1) StudyCardState.LEARNING else StudyCardState.RELEARNING
    } else {
      card.state = if (previousReps < 2) StudyCardState.LEARNING else StudyCardState.REVIEW
    }

    card.reps += 1
    card.lastReviewAtSec = reviewTs
    card.dueAtSec = dueAtSec
    card.lastRating = rating
    card.lastScore = score
  }

  private fun MutableCard.toQueueItem(): StudyCardQueueItem {
    return StudyCardQueueItem(
      questionId = questionId,
      dueAtSec = dueAtSec,
      stabilityDays = round2(stabilityDays),
      difficulty = round2(difficulty),
      reps = reps,
      lapses = lapses,
      state = state,
      lastReviewAtSec = lastReviewAtSec,
      lastRating = lastRating,
      lastScore = lastScore,
    )
  }

  private fun normalizeRating(rating: Int, score: Int): Int {
    if (rating in 1..4) return rating
    return when {
      score >= 9_500 -> 4
      score >= 8_000 -> 3
      score >= 6_000 -> 2
      else -> 1
    }
  }

  private fun initialIntervalDays(rating: Int): Double {
    return when (rating) {
      1 -> 0.0
      2 -> 0.5
      3 -> 1.0
      4 -> 2.0
      else -> 1.0
    }
  }

  private fun ratingMultiplier(rating: Int): Double {
    return when (rating) {
      1 -> 0.2
      2 -> 1.2
      3 -> 2.0
      4 -> 3.0
      else -> 1.0
    }
  }

  private fun scoreMultiplier(score: Int): Double {
    return when {
      score >= 9_500 -> 1.20
      score >= 8_500 -> 1.10
      score >= 7_000 -> 1.00
      score >= 5_000 -> 0.90
      else -> 0.80
    }
  }

  private fun difficultyDelta(rating: Int, score: Int): Double {
    val ratingDelta = when (rating) {
      1 -> 1.10
      2 -> 0.45
      3 -> -0.20
      4 -> -0.55
      else -> 0.0
    }

    val scoreDelta = when {
      score < 5_000 -> 0.45
      score < 7_000 -> 0.20
      score >= 9_500 -> -0.20
      else -> 0.0
    }

    return ratingDelta + scoreDelta
  }

  private fun round2(value: Double): Double {
    return kotlin.math.round(value * 100.0) / 100.0
  }
}
