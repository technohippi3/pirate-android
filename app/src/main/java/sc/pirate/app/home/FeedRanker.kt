package sc.pirate.app.home

import kotlin.math.pow
import kotlin.random.Random

class FeedRanker(
  private val random: Random = Random.Default,
) {
  fun rankCandidates(
    candidates: List<FeedPostResolved>,
    watchHistory: Map<String, WatchRecord>,
    nowSec: Long,
  ): List<FeedPostResolved> {
    if (candidates.isEmpty()) return emptyList()
    val scored = candidates.map { post -> scorePost(post = post, watchHistory = watchHistory, nowSec = nowSec) }
    if (scored.all { it.inCooldown }) {
      return scored
        .sortedWith(
          compareBy<ScoredPost> { it.cooldownExpiresAtSec }
            .thenByDescending { it.post.createdAtSec }
            .thenBy { it.post.id },
        )
        .map { it.post }
    }

    val ready =
      scored
        .filterNot { it.inCooldown }
        .sortedWith(
          compareByDescending<ScoredPost> { it.score }
            .thenByDescending { it.post.createdAtSec }
            .thenBy { it.post.id },
        )
    val cooling =
      scored
        .filter { it.inCooldown }
        .sortedWith(
          compareBy<ScoredPost> { it.cooldownExpiresAtSec }
            .thenByDescending { it.post.createdAtSec }
            .thenBy { it.post.id },
        )
    return (ready + cooling).map { it.post }
  }

  private fun scorePost(
    post: FeedPostResolved,
    watchHistory: Map<String, WatchRecord>,
    nowSec: Long,
  ): ScoredPost {
    val ageHours = ((nowSec - post.createdAtSec).coerceAtLeast(0L)).toDouble() / 3600.0
    val freshness = 1.0 / (ageHours + 2.0).pow(1.5)
    val base = post.likeCount.toDouble().coerceAtLeast(0.0) * freshness
    val record = watchHistory[post.id]
    if (record == null) {
      val jitter = scaledJitter(base)
      return ScoredPost(post = post, score = base + jitter, inCooldown = false, cooldownExpiresAtSec = Long.MIN_VALUE)
    }

    return if (record.watchPct < 0.30f) {
      val cooldownExpiresAt = record.lastSeenAtSec + LIGHT_COOLDOWN_SEC
      if (nowSec < cooldownExpiresAt) {
        ScoredPost(post = post, score = COOLDOWN_SCORE, inCooldown = true, cooldownExpiresAtSec = cooldownExpiresAt)
      } else {
        ScoredPost(post = post, score = base * 0.4, inCooldown = false, cooldownExpiresAtSec = cooldownExpiresAt)
      }
    } else {
      val cooldownExpiresAt = record.lastSeenAtSec + HEAVY_COOLDOWN_SEC
      if (nowSec < cooldownExpiresAt) {
        ScoredPost(post = post, score = COOLDOWN_SCORE, inCooldown = true, cooldownExpiresAtSec = cooldownExpiresAt)
      } else {
        ScoredPost(post = post, score = base * 0.1, inCooldown = false, cooldownExpiresAtSec = cooldownExpiresAt)
      }
    }
  }

  private fun scaledJitter(base: Double): Double {
    val max = base * 0.15
    if (max <= 0.0) return 0.0
    return random.nextDouble(from = 0.0, until = max)
  }

  private data class ScoredPost(
    val post: FeedPostResolved,
    val score: Double,
    val inCooldown: Boolean,
    val cooldownExpiresAtSec: Long,
  )

  private companion object {
    const val COOLDOWN_SCORE = -1.0
    const val LIGHT_COOLDOWN_SEC = 2L * 3600L
    const val HEAVY_COOLDOWN_SEC = 12L * 3600L
  }
}
