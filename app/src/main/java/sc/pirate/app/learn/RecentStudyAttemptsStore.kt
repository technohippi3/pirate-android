package sc.pirate.app.learn

private const val MAX_LOCAL_ATTEMPTS_PER_STUDY_SET = 2_000

internal object RecentStudyAttemptsStore {
  private val attemptsByUserAndStudySet = LinkedHashMap<String, MutableList<StudyAttemptRow>>()
  // Seed to epoch-microseconds so synthetic orders stay above on-chain canonicalOrder (block * 1_000_000 + logIndex).
  private var syntheticCanonicalOrder = System.currentTimeMillis() * 1_000L

  private fun key(
    userAddress: String,
    studySetKey: String,
  ): String {
    return "${userAddress.trim().lowercase()}:${studySetKey.trim().lowercase()}"
  }

  private fun exactSignature(attempt: StudyAttemptRow): String {
    val dedupTs = if (attempt.clientTimestampSec > 0L) attempt.clientTimestampSec else attempt.blockTimestampSec
    return "${attempt.questionId.lowercase()}:${attempt.rating}:${attempt.score}:$dedupTs"
  }

  private fun fallbackSignature(attempt: StudyAttemptRow): String {
    return "${attempt.questionId.lowercase()}:${attempt.rating}:${attempt.score}"
  }

  private fun incrementCount(
    counts: MutableMap<String, Int>,
    key: String,
  ) {
    counts[key] = (counts[key] ?: 0) + 1
  }

  private fun tryConsumeCount(
    counts: MutableMap<String, Int>,
    key: String,
  ): Boolean {
    val current = counts[key] ?: return false
    if (current <= 1) {
      counts.remove(key)
    } else {
      counts[key] = current - 1
    }
    return true
  }

  @Synchronized
  fun recordSubmittedAttempts(
    userAddress: String,
    attempts: List<StudyAttemptInput>,
  ) {
    val normalizedUser = userAddress.trim().lowercase()
    if (normalizedUser.isBlank() || attempts.isEmpty()) return

    for (attempt in attempts) {
      val studySetKey = attempt.studySetKey.trim().lowercase()
      if (studySetKey.isBlank()) continue
      val cacheKey = key(normalizedUser, studySetKey)
      val rows = attemptsByUserAndStudySet.getOrPut(cacheKey) { mutableListOf() }
      syntheticCanonicalOrder += 1
      rows.add(
        StudyAttemptRow(
          questionId = attempt.questionId.trim().lowercase(),
          rating = attempt.rating,
          score = attempt.score,
          canonicalOrder = syntheticCanonicalOrder,
          blockTimestampSec = attempt.timestampSec.coerceAtLeast(0L),
          clientTimestampSec = attempt.timestampSec.coerceAtLeast(0L),
        ),
      )
      if (rows.size > MAX_LOCAL_ATTEMPTS_PER_STUDY_SET) {
        val drop = rows.size - MAX_LOCAL_ATTEMPTS_PER_STUDY_SET
        repeat(drop) { rows.removeAt(0) }
      }
    }
  }

  @Synchronized
  fun mergeWithOnChain(
    userAddress: String,
    studySetKey: String,
    onChainAttempts: List<StudyAttemptRow>,
  ): List<StudyAttemptRow> {
    val normalizedUser = userAddress.trim().lowercase()
    val normalizedStudySetKey = studySetKey.trim().lowercase()
    if (normalizedUser.isBlank() || normalizedStudySetKey.isBlank()) return onChainAttempts

    val cacheKey = key(normalizedUser, normalizedStudySetKey)
    val cached = attemptsByUserAndStudySet[cacheKey]
    if (cached.isNullOrEmpty()) return onChainAttempts

    val onChainExactCounts = LinkedHashMap<String, Int>(onChainAttempts.size)
    val onChainExactFromMissingClientTsCounts = LinkedHashMap<String, Int>()
    val onChainFallbackCounts = LinkedHashMap<String, Int>()
    for (attempt in onChainAttempts) {
      val exactKey = exactSignature(attempt)
      incrementCount(onChainExactCounts, exactKey)
      if (attempt.clientTimestampSec <= 0L) {
        incrementCount(onChainExactFromMissingClientTsCounts, exactKey)
        incrementCount(onChainFallbackCounts, fallbackSignature(attempt))
      }
    }

    val retainedCached =
      cached.filterNot { cachedAttempt ->
        val exactKey = exactSignature(cachedAttempt)
        if (tryConsumeCount(onChainExactCounts, exactKey)) {
          if (tryConsumeCount(onChainExactFromMissingClientTsCounts, exactKey)) {
            // This exact slot came from an on-chain row with missing client timestamp, so consume
            // the paired fallback slot too to keep 1:1 eviction accounting.
            tryConsumeCount(onChainFallbackCounts, fallbackSignature(cachedAttempt))
          }
          return@filterNot true
        }
        if (cachedAttempt.clientTimestampSec > 0L && tryConsumeCount(onChainFallbackCounts, fallbackSignature(cachedAttempt))) {
          return@filterNot true
        }
        false
      }
    if (retainedCached.size != cached.size) {
      if (retainedCached.isEmpty()) {
        attemptsByUserAndStudySet.remove(cacheKey)
      } else {
        attemptsByUserAndStudySet[cacheKey] = retainedCached.toMutableList()
      }
    }
    if (retainedCached.isEmpty()) return onChainAttempts

    val merged = ArrayList<StudyAttemptRow>(onChainAttempts.size + retainedCached.size)
    merged.addAll(onChainAttempts)
    merged.addAll(retainedCached)
    return merged.sortedWith(
      compareBy<StudyAttemptRow>({ it.canonicalOrder }, { it.blockTimestampSec }, { it.questionId }),
    )
  }

  @Synchronized
  internal fun clearAllForTest() {
    attemptsByUserAndStudySet.clear()
    syntheticCanonicalOrder = System.currentTimeMillis() * 1_000L
  }
}
