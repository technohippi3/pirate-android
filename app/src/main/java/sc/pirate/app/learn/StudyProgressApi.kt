package sc.pirate.app.learn

import sc.pirate.app.util.HttpClients
import sc.pirate.app.util.tempoStudyProgressSubgraphUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private val ADDRESS_REGEX = Regex("^0x[a-fA-F0-9]{40}$")
private val BYTES32_REGEX = Regex("^0x[a-fA-F0-9]{64}$")

data class UserStudySetSummary(
  val id: String,
  val studySetKey: String,
  val totalAttempts: Int,
  val uniqueQuestionsTouched: Int,
  val correctAttempts: Int,
  val incorrectAttempts: Int,
  val averageScore: Double,
  val latestBlockTimestampSec: Long,
)

data class StudyAttemptRow(
  val questionId: String,
  val rating: Int,
  val score: Int,
  val canonicalOrder: Long,
  val blockTimestampSec: Long,
  val clientTimestampSec: Long,
)

data class StudySetAnchorRow(
  val trackId: String,
  val version: Int,
  val studySetRef: String,
)

data class UserStudySetDetail(
  val summary: UserStudySetSummary,
  val attempts: List<StudyAttemptRow>,
  val anchor: StudySetAnchorRow?,
)

object StudyProgressApi {
  private const val GRAPH_MAX_FIRST = 1_000
  private const val STREAK_DAY_PAGE_SIZE = 1_000
  private const val STREAK_DAY_MAX_SCAN = 5_000
  private val client = HttpClients.Api
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

  suspend fun fetchTrackFlashcardAdderCount(trackId: String, maxEntries: Int = 10_000): Int =
    withContext(Dispatchers.IO) {
      val normalizedTrackId = normalizeBytes32(trackId)
        ?: throw IllegalArgumentException("Invalid track id: $trackId")
      val cap = maxEntries.coerceIn(1, 50_000)
      fetchTrackFlashcardAdderCountFromSubgraph(
        subgraphUrl = studyProgressSubgraphUrl(),
        normalizedTrackId = normalizedTrackId,
        maxEntries = cap,
      )
    }

  suspend fun fetchUserStudySetSummaries(userAddress: String, maxEntries: Int = 30): List<UserStudySetSummary> =
    withContext(Dispatchers.IO) {
      val normalizedUser = normalizeAddress(userAddress)
        ?: throw IllegalArgumentException("Invalid user address: $userAddress")
      val first = maxEntries.coerceIn(1, 100)
      fetchUserStudySetSummariesFromSubgraph(
        subgraphUrl = studyProgressSubgraphUrl(),
        normalizedUser = normalizedUser,
        first = first,
      )
    }

  suspend fun fetchUserStudySetDetail(
    userAddress: String,
    studySetKey: String,
    maxAttempts: Int = GRAPH_MAX_FIRST,
  ): UserStudySetDetail? = withContext(Dispatchers.IO) {
    val normalizedUser = normalizeAddress(userAddress)
      ?: throw IllegalArgumentException("Invalid user address: $userAddress")
    val normalizedStudySetKey = normalizeBytes32(studySetKey)
      ?: throw IllegalArgumentException("Invalid studySetKey: $studySetKey")

    val userStudySetId = toUserStudySetId(normalizedUser, normalizedStudySetKey)
    val first = maxAttempts.coerceIn(1, GRAPH_MAX_FIRST)
    fetchUserStudySetDetailFromSubgraph(
      subgraphUrl = studyProgressSubgraphUrl(),
      userStudySetId = userStudySetId,
      studySetKey = normalizedStudySetKey,
      maxAttempts = first,
    )
  }

  suspend fun fetchStudySetAnchors(studySetKeys: List<String>): Map<String, StudySetAnchorRow> =
    withContext(Dispatchers.IO) {
      val normalizedKeys =
        studySetKeys
          .mapNotNull { normalizeBytes32(it) }
          .map { it.lowercase() }
          .distinct()
      if (normalizedKeys.isEmpty()) return@withContext emptyMap()
      fetchStudySetAnchorsFromSubgraph(
        subgraphUrl = studyProgressSubgraphUrl(),
        studySetKeys = normalizedKeys,
      )
    }

  suspend fun fetchUserStudySetStreakDays(
    userAddress: String,
    studySetKeys: List<String>,
  ): Map<String, Set<Long>> = withContext(Dispatchers.IO) {
    val normalizedUser = normalizeAddress(userAddress)
      ?: throw IllegalArgumentException("Invalid user address: $userAddress")
    val normalizedKeys =
      studySetKeys
        .mapNotNull { normalizeBytes32(it) }
        .map { it.lowercase() }
        .distinct()
    if (normalizedKeys.isEmpty()) return@withContext emptyMap()
    fetchUserStudySetStreakDaysFromSubgraph(
      subgraphUrl = studyProgressSubgraphUrl(),
      normalizedUser = normalizedUser,
      studySetKeys = normalizedKeys,
    )
  }

  private fun fetchUserStudySetSummariesFromSubgraph(
    subgraphUrl: String,
    normalizedUser: String,
    first: Int,
  ): List<UserStudySetSummary> {
    val query = """
      {
        userStudySetProgresses(
          where: { user: "$normalizedUser" }
          orderBy: latestCanonicalOrder
          orderDirection: desc
          first: $first
        ) {
          id
          studySetKey
          totalAttempts
          uniqueQuestionsTouched
          correctAttempts
          incorrectAttempts
          averageScore
          latestBlockTimestamp
        }
      }
    """.trimIndent()

    val json = postQuery(subgraphUrl, query)
    val rows = json.optJSONObject("data")?.optJSONArray("userStudySetProgresses") ?: JSONArray()
    val out = ArrayList<UserStudySetSummary>(rows.length())
    for (i in 0 until rows.length()) {
      val row = rows.optJSONObject(i) ?: continue
      val id = row.optString("id", "").trim().lowercase()
      val studySetKey = normalizeBytes32(row.optString("studySetKey", "")) ?: continue
      if (id.isEmpty()) continue

      out.add(
        UserStudySetSummary(
          id = id,
          studySetKey = studySetKey,
          totalAttempts = row.optInt("totalAttempts", 0).coerceAtLeast(0),
          uniqueQuestionsTouched = row.optInt("uniqueQuestionsTouched", 0).coerceAtLeast(0),
          correctAttempts = row.optInt("correctAttempts", 0).coerceAtLeast(0),
          incorrectAttempts = row.optInt("incorrectAttempts", 0).coerceAtLeast(0),
          averageScore = row.optString("averageScore", "0").trim().toDoubleOrNull() ?: 0.0,
          latestBlockTimestampSec = row.optString("latestBlockTimestamp", "0").trim().toLongOrNull() ?: 0L,
        ),
      )
    }

    return out
  }

  private fun fetchTrackFlashcardAdderCountFromSubgraph(
    subgraphUrl: String,
    normalizedTrackId: String,
    maxEntries: Int,
  ): Int {
    val anchorQuery = """
      {
        studySetAnchors(
          where: { trackId: "$normalizedTrackId" }
          first: 100
        ) {
          studySetKey
        }
      }
    """.trimIndent()

    val anchorJson = postQuery(subgraphUrl, anchorQuery)
    val anchorRows = anchorJson.optJSONObject("data")?.optJSONArray("studySetAnchors") ?: JSONArray()
    val studySetKeys = LinkedHashSet<String>(anchorRows.length())
    for (i in 0 until anchorRows.length()) {
      val row = anchorRows.optJSONObject(i) ?: continue
      val key = normalizeBytes32(row.optString("studySetKey", "")) ?: continue
      studySetKeys.add(key)
    }
    if (studySetKeys.isEmpty()) return 0

    val escapedKeys = studySetKeys.joinToString(separator = ",") { "\"$it\"" }
    val seenUsers = LinkedHashSet<String>()
    val pageSize = 1_000
    var skip = 0

    while (seenUsers.size < maxEntries) {
      val progressQuery = """
        {
          userStudySetProgresses(
            where: { studySetKey_in: [$escapedKeys] }
            orderBy: latestCanonicalOrder
            orderDirection: desc
            first: $pageSize
            skip: $skip
          ) {
            user
          }
        }
      """.trimIndent()

      val json = postQuery(subgraphUrl, progressQuery)
      val rows = json.optJSONObject("data")?.optJSONArray("userStudySetProgresses") ?: JSONArray()
      if (rows.length() == 0) break

      for (i in 0 until rows.length()) {
        val row = rows.optJSONObject(i) ?: continue
        val user = normalizeAddress(row.optString("user", "")) ?: continue
        seenUsers.add(user)
      }

      if (rows.length() < pageSize) break
      skip += pageSize
      if (skip >= maxEntries * 4) break
    }

    return seenUsers.size.coerceAtMost(maxEntries)
  }

  private fun fetchStudySetAnchorsFromSubgraph(
    subgraphUrl: String,
    studySetKeys: List<String>,
  ): Map<String, StudySetAnchorRow> {
    if (studySetKeys.isEmpty()) return emptyMap()
    val queryKeys = studySetKeys.take(GRAPH_MAX_FIRST).joinToString(separator = ",") { "\"$it\"" }
    val query = """
      {
        studySetAnchors(
          where: { studySetKey_in: [$queryKeys] }
          first: $GRAPH_MAX_FIRST
        ) {
          studySetKey
          trackId
          version
          studySetRef
        }
      }
    """.trimIndent()

    val json = postQuery(subgraphUrl, query)
    val rows = json.optJSONObject("data")?.optJSONArray("studySetAnchors") ?: JSONArray()
    val out = LinkedHashMap<String, StudySetAnchorRow>(rows.length())
    for (i in 0 until rows.length()) {
      val row = rows.optJSONObject(i) ?: continue
      val studySetKey = normalizeBytes32(row.optString("studySetKey", "")) ?: continue
      val trackId = normalizeBytes32(row.optString("trackId", "")) ?: continue
      val studySetRef = row.optString("studySetRef", "").trim()
      out[studySetKey.lowercase()] =
        StudySetAnchorRow(
          trackId = trackId.lowercase(),
          version = row.optInt("version", 1).coerceIn(1, 255),
          studySetRef = studySetRef,
        )
    }
    return out
  }

  private fun fetchUserStudySetStreakDaysFromSubgraph(
    subgraphUrl: String,
    normalizedUser: String,
    studySetKeys: List<String>,
  ): Map<String, Set<Long>> {
    if (studySetKeys.isEmpty()) return emptyMap()
    val queryKeys = studySetKeys.joinToString(separator = ",") { "\"$it\"" }
    val out = LinkedHashMap<String, MutableSet<Long>>()
    var skip = 0

    while (skip < STREAK_DAY_MAX_SCAN) {
      val first = minOf(STREAK_DAY_PAGE_SIZE, STREAK_DAY_MAX_SCAN - skip)
      val query = """
        {
          streakDayEarneds(
            where: { user: "$normalizedUser", studySetKey_in: [$queryKeys] }
            orderBy: dayUtc
            orderDirection: desc
            first: $first
            skip: $skip
          ) {
            studySetKey
            dayUtc
          }
        }
      """.trimIndent()

      val json = postQuery(subgraphUrl, query)
      val rows = json.optJSONObject("data")?.optJSONArray("streakDayEarneds") ?: JSONArray()
      if (rows.length() == 0) break

      for (i in 0 until rows.length()) {
        val row = rows.optJSONObject(i) ?: continue
        val studySetKey = normalizeBytes32(row.optString("studySetKey", "")) ?: continue
        val dayUtc =
          row.optString("dayUtc", "").trim().toLongOrNull()
            ?: row.optLong("dayUtc", Long.MIN_VALUE)
        if (dayUtc < 0L) continue
        out.getOrPut(studySetKey.lowercase()) { LinkedHashSet() }.add(dayUtc)
      }

      skip += rows.length()
      if (rows.length() < first) break
    }

    return out.mapValues { (_, days) -> days.toSet() }
  }

  private fun fetchUserStudySetDetailFromSubgraph(
    subgraphUrl: String,
    userStudySetId: String,
    studySetKey: String,
    maxAttempts: Int,
  ): UserStudySetDetail? {
    val query = """
      {
        userStudySetProgress(id: "$userStudySetId") {
          id
          studySetKey
          totalAttempts
          uniqueQuestionsTouched
          correctAttempts
          incorrectAttempts
          averageScore
          latestBlockTimestamp
          attempts(first: $maxAttempts, orderBy: canonicalOrder, orderDirection: asc) {
            questionId
            rating
            score
            canonicalOrder
            blockTimestamp
            clientTimestamp
          }
        }
        studySetAnchor(id: "${studySetKey.lowercase()}") {
          trackId
          version
          studySetRef
        }
      }
    """.trimIndent()

    val json = postQuery(subgraphUrl, query)
    val data = json.optJSONObject("data") ?: return null
    val progress = data.optJSONObject("userStudySetProgress") ?: return null

    val normalizedStudySetKey = normalizeBytes32(progress.optString("studySetKey", "")) ?: return null
    val summary = UserStudySetSummary(
      id = progress.optString("id", "").trim().lowercase(),
      studySetKey = normalizedStudySetKey,
      totalAttempts = progress.optInt("totalAttempts", 0).coerceAtLeast(0),
      uniqueQuestionsTouched = progress.optInt("uniqueQuestionsTouched", 0).coerceAtLeast(0),
      correctAttempts = progress.optInt("correctAttempts", 0).coerceAtLeast(0),
      incorrectAttempts = progress.optInt("incorrectAttempts", 0).coerceAtLeast(0),
      averageScore = progress.optString("averageScore", "0").trim().toDoubleOrNull() ?: 0.0,
      latestBlockTimestampSec = progress.optString("latestBlockTimestamp", "0").trim().toLongOrNull() ?: 0L,
    )

    val attemptsJson = progress.optJSONArray("attempts") ?: JSONArray()
    val attempts = ArrayList<StudyAttemptRow>(attemptsJson.length())
    for (i in 0 until attemptsJson.length()) {
      val row = attemptsJson.optJSONObject(i) ?: continue
      val questionId = normalizeBytes32(row.optString("questionId", "")) ?: continue
      attempts.add(
        StudyAttemptRow(
          questionId = questionId,
          rating = row.optInt("rating", 0),
          score = row.optInt("score", 0),
          canonicalOrder = row.optString("canonicalOrder", "0").trim().toLongOrNull() ?: 0L,
          blockTimestampSec = row.optString("blockTimestamp", "0").trim().toLongOrNull() ?: 0L,
          clientTimestampSec = row.optString("clientTimestamp", "0").trim().toLongOrNull() ?: 0L,
        ),
      )
    }

    val anchorJson = data.optJSONObject("studySetAnchor")
    val anchor = if (anchorJson == null) {
      null
    } else {
      val trackId = normalizeBytes32(anchorJson.optString("trackId", "")) ?: ""
      val studySetRef = anchorJson.optString("studySetRef", "").trim()
      if (trackId.isBlank() || studySetRef.isBlank()) {
        null
      } else {
        StudySetAnchorRow(
          trackId = trackId,
          version = anchorJson.optInt("version", 1).coerceIn(1, 255),
          studySetRef = studySetRef,
        )
      }
    }

    return UserStudySetDetail(
      summary = summary,
      attempts = attempts,
      anchor = anchor,
    )
  }

  private fun studyProgressSubgraphUrl(): String {
    return tempoStudyProgressSubgraphUrl()
  }

  private fun postQuery(subgraphUrl: String, query: String): JSONObject {
    val body = JSONObject().put("query", query).toString().toRequestBody(jsonMediaType)
    val req = Request.Builder().url(subgraphUrl).post(body).build()
    return client.newCall(req).execute().use { res ->
      if (!res.isSuccessful) throw IllegalStateException("Study subgraph query failed: ${res.code}")
      val json = JSONObject(res.body?.string().orEmpty())
      val errors = json.optJSONArray("errors")
      if (errors != null && errors.length() > 0) {
        val message = errors.optJSONObject(0)?.optString("message", "GraphQL error") ?: "GraphQL error"
        throw IllegalStateException(message)
      }
      json
    }
  }

  private fun normalizeAddress(raw: String): String? {
    val trimmed = raw.trim()
    if (!ADDRESS_REGEX.matches(trimmed)) return null
    return trimmed.lowercase()
  }

  private fun normalizeBytes32(raw: String): String? {
    val trimmed = raw.trim()
    if (!BYTES32_REGEX.matches(trimmed)) return null
    return trimmed.lowercase()
  }

  private fun toUserStudySetId(userAddress: String, studySetKey: String): String {
    return "${userAddress.lowercase()}-${studySetKey.lowercase()}"
  }
}
