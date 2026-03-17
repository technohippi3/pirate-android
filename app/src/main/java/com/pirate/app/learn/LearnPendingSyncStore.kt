package com.pirate.app.learn

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal data class LearnPendingSyncState(
  val attempts: List<TempoStudyAttemptInput> = emptyList(),
  val streakClaims: List<TempoStreakClaimInput> = emptyList(),
  val updatedAtMs: Long = 0L,
)

internal data class LearnPendingSyncBucket(
  val ownerAddress: String,
  val attempts: List<TempoStudyAttemptInput>,
  val streakClaims: List<TempoStreakClaimInput>,
  val updatedAtMs: Long,
)

internal object LearnPendingSyncStore {
  private const val CACHE_FILENAME = "pirate_learn_pending_sync.json"
  private const val MAX_OWNERS = 8
  private const val MAX_ATTEMPTS_PER_OWNER = 4_096
  private const val MAX_CLAIMS_PER_OWNER = 256

  private val lock = Any()
  private val ADDRESS_REGEX = Regex("^0x[a-fA-F0-9]{40}$")
  private val BYTES32_REGEX = Regex("^0x[a-fA-F0-9]{64}$")
  private val SIGNATURE_REGEX = Regex("^0x[a-fA-F0-9]{130}$")

  fun load(
    context: Context,
    ownerAddress: String,
  ): LearnPendingSyncState {
    val normalizedOwner = normalizeAddress(ownerAddress) ?: return LearnPendingSyncState()
    synchronized(lock) {
      val bucket = decodeBuckets(readRaw(context)).firstOrNull { it.ownerAddress == normalizedOwner }
      return if (bucket == null) {
        LearnPendingSyncState()
      } else {
        LearnPendingSyncState(
          attempts = bucket.attempts,
          streakClaims = bucket.streakClaims,
          updatedAtMs = bucket.updatedAtMs,
        )
      }
    }
  }

  fun save(
    context: Context,
    ownerAddress: String,
    attempts: List<TempoStudyAttemptInput>,
    streakClaims: List<TempoStreakClaimInput>,
    updatedAtMs: Long = System.currentTimeMillis(),
  ) {
    val normalizedOwner = normalizeAddress(ownerAddress) ?: return
    val normalizedAttempts = attempts.mapNotNull(::normalizeAttempt).takeLast(MAX_ATTEMPTS_PER_OWNER)
    val normalizedClaims = streakClaims.mapNotNull(::normalizeClaim).takeLast(MAX_CLAIMS_PER_OWNER)

    synchronized(lock) {
      val next = ArrayList<LearnPendingSyncBucket>(MAX_OWNERS)
      if (normalizedAttempts.isNotEmpty() || normalizedClaims.isNotEmpty()) {
        next.add(
          LearnPendingSyncBucket(
            ownerAddress = normalizedOwner,
            attempts = normalizedAttempts,
            streakClaims = normalizedClaims,
            updatedAtMs = updatedAtMs.coerceAtLeast(0L),
          ),
        )
      }
      for (bucket in decodeBuckets(readRaw(context))) {
        if (bucket.ownerAddress == normalizedOwner) continue
        next.add(bucket)
        if (next.size >= MAX_OWNERS) break
      }
      writeRaw(context, encodeBuckets(next))
    }
  }

  internal fun decodeBuckets(raw: String): List<LearnPendingSyncBucket> {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return emptyList()

    return runCatching {
      val array = JSONArray(trimmed)
      val buckets = ArrayList<LearnPendingSyncBucket>(array.length())
      for (index in 0 until array.length()) {
        val row = array.optJSONObject(index) ?: continue
        val ownerAddress = normalizeAddress(row.optString("ownerAddress", "")) ?: continue
        val attempts = decodeAttempts(row.optJSONArray("attempts")).takeLast(MAX_ATTEMPTS_PER_OWNER)
        val streakClaims = decodeClaims(row.optJSONArray("streakClaims")).takeLast(MAX_CLAIMS_PER_OWNER)
        if (attempts.isEmpty() && streakClaims.isEmpty()) continue
        buckets.add(
          LearnPendingSyncBucket(
            ownerAddress = ownerAddress,
            attempts = attempts,
            streakClaims = streakClaims,
            updatedAtMs = row.optLong("updatedAtMs", 0L).coerceAtLeast(0L),
          ),
        )
        if (buckets.size >= MAX_OWNERS) break
      }
      buckets
    }.getOrElse { emptyList() }
  }

  internal fun encodeBuckets(buckets: List<LearnPendingSyncBucket>): String {
    val array = JSONArray()
    buckets.take(MAX_OWNERS).forEach { bucket ->
      val normalizedOwner = normalizeAddress(bucket.ownerAddress) ?: return@forEach
      val attempts = bucket.attempts.mapNotNull(::normalizeAttempt).takeLast(MAX_ATTEMPTS_PER_OWNER)
      val streakClaims = bucket.streakClaims.mapNotNull(::normalizeClaim).takeLast(MAX_CLAIMS_PER_OWNER)
      if (attempts.isEmpty() && streakClaims.isEmpty()) return@forEach

      val attemptArray = JSONArray()
      attempts.forEach { attempt ->
        attemptArray.put(
          JSONObject()
            .put("studySetKey", attempt.studySetKey)
            .put("questionId", attempt.questionId)
            .put("rating", attempt.rating)
            .put("score", attempt.score)
            .put("timestampSec", attempt.timestampSec),
        )
      }

      val claimArray = JSONArray()
      streakClaims.forEach { claim ->
        claimArray.put(
          JSONObject()
            .put("studySetKey", claim.studySetKey)
            .put("dayUtc", claim.dayUtc)
            .put("nonce", claim.nonce)
            .put("expirySec", claim.expirySec)
            .put("signatureHex", claim.signatureHex),
        )
      }

      array.put(
        JSONObject()
          .put("ownerAddress", normalizedOwner)
          .put("attempts", attemptArray)
          .put("streakClaims", claimArray)
          .put("updatedAtMs", bucket.updatedAtMs.coerceAtLeast(0L)),
      )
    }
    return array.toString()
  }

  private fun decodeAttempts(array: JSONArray?): List<TempoStudyAttemptInput> {
    if (array == null) return emptyList()
    val attempts = ArrayList<TempoStudyAttemptInput>(array.length())
    for (index in 0 until array.length()) {
      val row = array.optJSONObject(index) ?: continue
      val attempt =
        normalizeAttempt(
          TempoStudyAttemptInput(
            studySetKey = row.optString("studySetKey", ""),
            questionId = row.optString("questionId", ""),
            rating = row.optInt("rating", 0),
            score = row.optInt("score", -1),
            timestampSec = row.optLong("timestampSec", -1L),
          ),
        ) ?: continue
      attempts.add(attempt)
    }
    return attempts
  }

  private fun decodeClaims(array: JSONArray?): List<TempoStreakClaimInput> {
    if (array == null) return emptyList()
    val claims = ArrayList<TempoStreakClaimInput>(array.length())
    for (index in 0 until array.length()) {
      val row = array.optJSONObject(index) ?: continue
      val claim =
        normalizeClaim(
          TempoStreakClaimInput(
            studySetKey = row.optString("studySetKey", ""),
            dayUtc = row.optLong("dayUtc", -1L),
            nonce = row.optLong("nonce", -1L),
            expirySec = row.optLong("expirySec", -1L),
            signatureHex = row.optString("signatureHex", ""),
          ),
        ) ?: continue
      claims.add(claim)
    }
    return claims
  }

  private fun normalizeAttempt(attempt: TempoStudyAttemptInput): TempoStudyAttemptInput? {
    val studySetKey = normalizeBytes32(attempt.studySetKey) ?: return null
    val questionId = normalizeBytes32(attempt.questionId) ?: return null
    if (attempt.rating !in 1..4) return null
    if (attempt.score !in 0..10_000) return null
    return TempoStudyAttemptInput(
      studySetKey = studySetKey,
      questionId = questionId,
      rating = attempt.rating,
      score = attempt.score,
      timestampSec = attempt.timestampSec.coerceAtLeast(0L),
    )
  }

  private fun normalizeClaim(claim: TempoStreakClaimInput): TempoStreakClaimInput? {
    val studySetKey = normalizeBytes32(claim.studySetKey) ?: return null
    val signatureHex = normalizeSignature(claim.signatureHex) ?: return null
    val dayUtc = claim.dayUtc.coerceAtLeast(0L)
    val nonce = claim.nonce.coerceAtLeast(0L)
    val expirySec = claim.expirySec.coerceAtLeast(0L)
    if (expirySec <= 0L) return null
    return TempoStreakClaimInput(
      studySetKey = studySetKey,
      dayUtc = dayUtc,
      nonce = nonce,
      expirySec = expirySec,
      signatureHex = signatureHex,
    )
  }

  private fun readRaw(context: Context): String {
    return runCatching { cacheFile(context).readText() }.getOrNull().orEmpty()
  }

  private fun writeRaw(
    context: Context,
    raw: String,
  ) {
    runCatching { cacheFile(context).writeText(raw) }
  }

  private fun cacheFile(context: Context): File = File(context.filesDir, CACHE_FILENAME)

  private fun normalizeAddress(raw: String): String? {
    val trimmed = raw.trim()
    if (!ADDRESS_REGEX.matches(trimmed)) return null
    return "0x${trimmed.removePrefix("0x").removePrefix("0X").lowercase()}"
  }

  private fun normalizeBytes32(raw: String): String? {
    val trimmed = raw.trim()
    if (!BYTES32_REGEX.matches(trimmed)) return null
    return "0x${trimmed.removePrefix("0x").removePrefix("0X").lowercase()}"
  }

  private fun normalizeSignature(raw: String): String? {
    val trimmed = raw.trim()
    if (!SIGNATURE_REGEX.matches(trimmed)) return null
    return "0x${trimmed.removePrefix("0x").removePrefix("0X").lowercase()}"
  }
}
