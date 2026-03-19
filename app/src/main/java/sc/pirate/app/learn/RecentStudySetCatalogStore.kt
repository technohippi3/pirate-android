package sc.pirate.app.learn

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal data class RecentStudySetCatalogEntry(
  val trackId: String?,
  val title: String,
  val artist: String,
  val language: String? = null,
  val version: Int = 1,
  val studySetRef: String? = null,
  val totalAttempts: Int = 0,
  val uniqueQuestionsTouched: Int = 0,
  val streakDays: Int = 0,
  val pack: LearnStudySetPack? = null,
)

internal data class RecentStudySetCatalogBucket(
  val ownerAddress: String,
  val studySetKey: String,
  val entry: RecentStudySetCatalogEntry,
  val updatedAtMs: Long,
)

internal object RecentStudySetCatalogStore {
  private const val CACHE_FILENAME = "pirate_learn_recent_catalog.json"
  private const val MAX_OWNERS = 8
  private const val MAX_ENTRIES_PER_USER = 20

  private val lock = Any()
  private val ADDRESS_REGEX = Regex("^0x[a-fA-F0-9]{40}$")
  private val BYTES32_REGEX = Regex("^0x[a-fA-F0-9]{64}$")

  private var hydrated = false
  private val bucketsByKey = LinkedHashMap<String, RecentStudySetCatalogBucket>()

  private fun key(
    ownerAddress: String,
    studySetKey: String,
  ): String = "${ownerAddress.trim().lowercase()}:${studySetKey.trim().lowercase()}"

  private fun userPrefix(ownerAddress: String): String = "${ownerAddress.trim().lowercase()}:"

  fun record(
    context: Context,
    userAddress: String,
    studySetKey: String,
    entry: RecentStudySetCatalogEntry,
    updatedAtMs: Long = System.currentTimeMillis(),
  ) {
    recordAll(
      context = context,
      userAddress = userAddress,
      entries = listOf(studySetKey to entry),
      updatedAtMs = updatedAtMs,
    )
  }

  fun recordAll(
    context: Context,
    userAddress: String,
    entries: List<Pair<String, RecentStudySetCatalogEntry>>,
    updatedAtMs: Long = System.currentTimeMillis(),
  ) {
    val normalizedOwner = normalizeAddress(userAddress) ?: return
    val normalizedRows =
      entries.mapNotNull { (studySetKey, entry) ->
        val normalizedStudySetKey = normalizeBytes32(studySetKey) ?: return@mapNotNull null
        val normalizedEntry = normalizeEntry(entry) ?: return@mapNotNull null
        normalizedStudySetKey to normalizedEntry
      }
    if (normalizedRows.isEmpty()) return

    synchronized(lock) {
      ensureHydrated(context)
      normalizedRows.forEach { (normalizedStudySetKey, normalizedEntry) ->
        val bucketKey = key(normalizedOwner, normalizedStudySetKey)
        bucketsByKey.remove(bucketKey)
        bucketsByKey[bucketKey] =
          RecentStudySetCatalogBucket(
            ownerAddress = normalizedOwner,
            studySetKey = normalizedStudySetKey,
            entry = normalizedEntry,
            updatedAtMs = updatedAtMs.coerceAtLeast(0L),
          )
      }
      enforceUserCap(normalizedOwner)
      enforceOwnerCap()
      persist(context)
    }
  }

  fun lookup(
    context: Context,
    userAddress: String,
    studySetKey: String,
  ): RecentStudySetCatalogEntry? {
    val normalizedOwner = normalizeAddress(userAddress) ?: return null
    val normalizedStudySetKey = normalizeBytes32(studySetKey) ?: return null
    synchronized(lock) {
      ensureHydrated(context)
      return bucketsByKey[key(normalizedOwner, normalizedStudySetKey)]?.entry
    }
  }

  fun listForUser(
    context: Context,
    userAddress: String,
  ): List<Pair<String, RecentStudySetCatalogEntry>> {
    val normalizedOwner = normalizeAddress(userAddress) ?: return emptyList()
    synchronized(lock) {
      ensureHydrated(context)
      val prefix = userPrefix(normalizedOwner)
      return bucketsByKey
        .asSequence()
        .filter { it.key.startsWith(prefix) }
        .map { (_, bucket) -> bucket.studySetKey to bucket.entry }
        .toList()
        .asReversed()
    }
  }

  internal fun decodeBuckets(raw: String): List<RecentStudySetCatalogBucket> {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return emptyList()

    return runCatching {
      val array = JSONArray(trimmed)
      val buckets = ArrayList<RecentStudySetCatalogBucket>(array.length())
      for (index in 0 until array.length()) {
        val row = array.optJSONObject(index) ?: continue
        val ownerAddress = normalizeAddress(row.optString("ownerAddress", "")) ?: continue
        val studySetKey = normalizeBytes32(row.optString("studySetKey", "")) ?: continue
        val entry =
          normalizeEntry(
            RecentStudySetCatalogEntry(
              trackId = row.optString("trackId", "").trim().ifBlank { null },
              title = row.optString("title", ""),
              artist = row.optString("artist", ""),
              language = row.optString("language", "").trim().ifBlank { null },
              version = row.optInt("version", 1),
              studySetRef = row.optString("studySetRef", "").trim().ifBlank { null },
              totalAttempts = row.optInt("totalAttempts", 0),
              uniqueQuestionsTouched = row.optInt("uniqueQuestionsTouched", 0),
              streakDays = row.optInt("streakDays", 0),
              pack = decodePack(row.optJSONObject("pack")),
            ),
          ) ?: continue
        buckets.add(
          RecentStudySetCatalogBucket(
            ownerAddress = ownerAddress,
            studySetKey = studySetKey,
            entry = entry,
            updatedAtMs = row.optLong("updatedAtMs", 0L).coerceAtLeast(0L),
          ),
        )
        if (buckets.size >= MAX_OWNERS * MAX_ENTRIES_PER_USER) break
      }
      buckets
    }.getOrElse { emptyList() }
  }

  internal fun encodeBuckets(buckets: List<RecentStudySetCatalogBucket>): String {
    val array = JSONArray()
    buckets.take(MAX_OWNERS * MAX_ENTRIES_PER_USER).forEach { bucket ->
      val ownerAddress = normalizeAddress(bucket.ownerAddress) ?: return@forEach
      val studySetKey = normalizeBytes32(bucket.studySetKey) ?: return@forEach
      val entry = normalizeEntry(bucket.entry) ?: return@forEach
      val row =
        JSONObject()
          .put("ownerAddress", ownerAddress)
          .put("studySetKey", studySetKey)
          .put("title", entry.title)
          .put("artist", entry.artist)
          .put("version", entry.version.coerceIn(1, 255))
          .put("totalAttempts", entry.totalAttempts.coerceAtLeast(0))
          .put("uniqueQuestionsTouched", entry.uniqueQuestionsTouched.coerceAtLeast(0))
          .put("streakDays", entry.streakDays.coerceAtLeast(0))
          .put("updatedAtMs", bucket.updatedAtMs.coerceAtLeast(0L))
      if (!entry.trackId.isNullOrBlank()) row.put("trackId", entry.trackId)
      if (!entry.language.isNullOrBlank()) row.put("language", entry.language)
      if (!entry.studySetRef.isNullOrBlank()) row.put("studySetRef", entry.studySetRef)
      encodePack(entry.pack)?.let { row.put("pack", it) }
      array.put(row)
    }
    return array.toString()
  }

  private fun ensureHydrated(context: Context) {
    if (hydrated) return
    bucketsByKey.clear()
    decodeBuckets(readRaw(context)).forEach { bucket ->
      bucketsByKey[key(bucket.ownerAddress, bucket.studySetKey)] = bucket
    }
    hydrated = true
  }

  private fun persist(context: Context) {
    writeRaw(context, encodeBuckets(bucketsByKey.values.toList()))
  }

  private fun enforceUserCap(ownerAddress: String) {
    val prefix = userPrefix(ownerAddress)
    var count = 0
    val iterator = bucketsByKey.entries.iterator()
    while (iterator.hasNext()) {
      val row = iterator.next()
      if (!row.key.startsWith(prefix)) continue
      count += 1
      if (count > MAX_ENTRIES_PER_USER) {
        iterator.remove()
      }
    }
  }

  private fun enforceOwnerCap() {
    val seenOwners = LinkedHashSet<String>()
    val iterator = bucketsByKey.entries.iterator()
    while (iterator.hasNext()) {
      val row = iterator.next()
      val ownerAddress = row.value.ownerAddress
      if (!seenOwners.contains(ownerAddress) && seenOwners.size >= MAX_OWNERS) {
        iterator.remove()
        continue
      }
      seenOwners.add(ownerAddress)
    }
  }

  private fun normalizeEntry(entry: RecentStudySetCatalogEntry): RecentStudySetCatalogEntry? {
    val title = entry.title.trim()
    val artist = entry.artist.trim()
    if (title.isBlank() || artist.isBlank()) return null
    return RecentStudySetCatalogEntry(
      trackId = normalizeBytes32(entry.trackId),
      title = title,
      artist = artist,
      language = entry.language?.trim()?.ifBlank { null },
      version = entry.version.coerceIn(1, 255),
      studySetRef = entry.studySetRef?.trim()?.ifBlank { null },
      totalAttempts = entry.totalAttempts.coerceAtLeast(0),
      uniqueQuestionsTouched = entry.uniqueQuestionsTouched.coerceAtLeast(0),
      streakDays = entry.streakDays.coerceAtLeast(0),
      pack = normalizePack(entry.pack),
    )
  }

  private fun normalizePack(pack: LearnStudySetPack?): LearnStudySetPack? {
    if (pack == null) return null
    val specVersion = pack.specVersion.trim()
    val trackId = normalizeBytes32(pack.trackId) ?: return null
    val language = pack.language.trim().ifBlank { "en" }
    if (specVersion.isBlank()) return null
    val questions =
      pack.questions.mapNotNull { question ->
        val questionIdHash = normalizeBytes32(question.questionIdHash) ?: return@mapNotNull null
        val id = normalizeBytes32(question.id) ?: return@mapNotNull null
        LearnStudyQuestion(
          id = id,
          questionIdHash = questionIdHash,
          type = question.type.trim(),
          prompt = question.prompt.trim(),
          excerpt = question.excerpt.trim(),
          choices = question.choices.map { it.trim() },
          correctIndex = question.correctIndex,
          explanation = question.explanation?.trim()?.ifBlank { null },
          difficulty = question.difficulty.trim(),
        )
      }
    if (questions.isEmpty()) return null
    return LearnStudySetPack(
      specVersion = specVersion,
      trackId = trackId,
      language = language,
      attributionTrack = pack.attributionTrack?.trim()?.ifBlank { null },
      attributionArtist = pack.attributionArtist?.trim()?.ifBlank { null },
      questions = questions,
    )
  }

  private fun encodePack(pack: LearnStudySetPack?): JSONObject? {
    val normalized = normalizePack(pack) ?: return null
    val questions =
      JSONArray().apply {
        normalized.questions.forEach { question ->
          put(
            JSONObject()
              .put("id", question.id)
              .put("questionIdHash", question.questionIdHash)
              .put("type", question.type)
              .put("prompt", question.prompt)
              .put("excerpt", question.excerpt)
              .put("choices", JSONArray(question.choices))
              .put("correctIndex", question.correctIndex)
              .put("difficulty", question.difficulty)
              .apply {
                if (!question.explanation.isNullOrBlank()) put("explanation", question.explanation)
              },
          )
        }
      }
    return JSONObject()
      .put("specVersion", normalized.specVersion)
      .put("trackId", normalized.trackId)
      .put("language", normalized.language)
      .put("questions", questions)
      .apply {
        if (!normalized.attributionTrack.isNullOrBlank()) put("attributionTrack", normalized.attributionTrack)
        if (!normalized.attributionArtist.isNullOrBlank()) put("attributionArtist", normalized.attributionArtist)
      }
  }

  private fun decodePack(json: JSONObject?): LearnStudySetPack? {
    if (json == null) return null
    val specVersion = json.optString("specVersion", "").trim()
    val trackId = normalizeBytes32(json.optString("trackId", "")) ?: return null
    val language = json.optString("language", "").trim().ifBlank { "en" }
    val questionRows = json.optJSONArray("questions") ?: return null
    val questions = ArrayList<LearnStudyQuestion>(questionRows.length())
    for (index in 0 until questionRows.length()) {
      val row = questionRows.optJSONObject(index) ?: continue
      val questionId = normalizeBytes32(row.optString("id", "")) ?: continue
      val questionIdHash = normalizeBytes32(row.optString("questionIdHash", "")) ?: continue
      val choicesJson = row.optJSONArray("choices") ?: JSONArray()
      val choices = ArrayList<String>(choicesJson.length())
      for (choiceIndex in 0 until choicesJson.length()) {
        choices.add(choicesJson.optString(choiceIndex, ""))
      }
      questions.add(
        LearnStudyQuestion(
          id = questionId,
          questionIdHash = questionIdHash,
          type = row.optString("type", "").trim(),
          prompt = row.optString("prompt", ""),
          excerpt = row.optString("excerpt", ""),
          choices = choices,
          correctIndex = row.optInt("correctIndex", 0),
          explanation = row.optString("explanation", "").trim().ifBlank { null },
          difficulty = row.optString("difficulty", "").trim(),
        ),
      )
    }
    return normalizePack(
      LearnStudySetPack(
        specVersion = specVersion,
        trackId = trackId,
        language = language,
        attributionTrack = json.optString("attributionTrack", "").trim().ifBlank { null },
        attributionArtist = json.optString("attributionArtist", "").trim().ifBlank { null },
        questions = questions,
      ),
    )
  }

  private fun normalizeAddress(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    return if (ADDRESS_REGEX.matches(trimmed)) trimmed.lowercase() else null
  }

  private fun normalizeBytes32(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    return if (BYTES32_REGEX.matches(trimmed)) trimmed.lowercase() else null
  }

  private fun file(context: Context): File = File(context.filesDir, CACHE_FILENAME)

  private fun readRaw(context: Context): String =
    runCatching {
      val target = file(context)
      if (!target.exists()) return ""
      target.readText()
    }.getOrElse { "" }

  private fun writeRaw(
    context: Context,
    raw: String,
  ) {
    runCatching {
      file(context).writeText(raw)
    }
  }
}
