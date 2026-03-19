package sc.pirate.app.learn

import android.util.Log
import sc.pirate.app.music.CoverRef
import sc.pirate.app.music.SongPublishService
import sc.pirate.app.util.HttpClients
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.jcajce.provider.digest.Keccak
import org.json.JSONArray
import org.json.JSONObject

internal data class LearnStudySetPack(
  val specVersion: String,
  val trackId: String,
  val language: String,
  val attributionTrack: String?,
  val attributionArtist: String?,
  val questions: List<LearnStudyQuestion>,
)

internal data class LearnStudyQuestion(
  val id: String,
  val questionIdHash: String,
  val type: String,
  val prompt: String,
  val excerpt: String,
  val choices: List<String>,
  val correctIndex: Int,
  val explanation: String?,
  val difficulty: String,
) {
  val isMcq: Boolean
    get() = type == "translation_mcq" || type == "trivia_mcq"
}

internal object LearnStudyPackApi {
  private val client = HttpClients.Api
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
  private const val TAG = "LearnStudyPackApi"
  private const val ENSURE_MAX_POLLS = 8
  private const val ENSURE_MAX_TOTAL_WAIT_MS = 90_000L
  private val ADDRESS_REGEX = Regex("^0x[a-fA-F0-9]{40}$")

  suspend fun fetchPackByRef(studySetRef: String): LearnStudySetPack =
    withContext(Dispatchers.IO) {
      fetchFromRef(studySetRef)
    }

  suspend fun ensurePackByTrack(
    trackId: String,
    preferredLanguage: String,
    version: Int = 1,
    userAddress: String,
  ): LearnStudySetPack =
    withContext(Dispatchers.IO) {
      val apiBaseUrl = normalizedApiBaseUrl()
        ?: throw IllegalStateException("API unavailable: set API_CORE_URL to an absolute http(s) URL")
      val normalizedUserAddress = userAddress.trim()
      if (!normalizedUserAddress.matches(Regex("^0x[a-fA-F0-9]{40}$"))) {
        throw IllegalArgumentException("Invalid userAddress")
      }

      val payload =
        JSONObject()
          .put("trackId", trackId.trim())
          .put("language", preferredLanguage.trim().ifBlank { "en" })
          .put("version", version.coerceIn(1, 255))

      val req =
        Request.Builder()
          .url("$apiBaseUrl/api/study-sets/ensure")
          .header("X-User-Address", normalizedUserAddress)
          .post(payload.toString().toRequestBody((jsonMediaType)))
          .build()

      val ensureResponse =
        client.newCall(req).execute().use { res ->
          val json = JSONObject(res.body?.string().orEmpty())
          if (!res.isSuccessful) {
            val error = json.optString("error", "Ensure failed (HTTP ${res.code})")
            throw IllegalStateException(error)
          }
          json
        }

      val status = ensureResponse.optString("status", "").trim().lowercase()
      if (status == "ready") {
        val directPack = ensureResponse.optJSONObject("pack")
        if (directPack != null) return@withContext parsePack(directPack)
      }

      val jobId = ensureResponse.optString("jobId", "").trim()
      if (jobId.isBlank()) {
        val error = ensureResponse.optString("error", "Ensure response missing jobId")
        throw IllegalStateException(error)
      }

      val startedAt = System.currentTimeMillis()
      var polls = 0
      var pollAfterSec = ensureResponse.optInt("pollAfterSec", 4).coerceIn(2, 20)
      while (polls < ENSURE_MAX_POLLS && (System.currentTimeMillis() - startedAt) <= ENSURE_MAX_TOTAL_WAIT_MS) {
        delay(pollAfterSec * 1_000L)
        polls += 1

        val pollReq =
          Request.Builder()
            .url("$apiBaseUrl/api/study-sets/ensure/$jobId")
            .header("X-User-Address", normalizedUserAddress)
            .get()
            .build()

        val pollJson =
          client.newCall(pollReq).execute().use { res ->
            val json = JSONObject(res.body?.string().orEmpty())
            if (!res.isSuccessful) {
              val error = json.optString("error", "Ensure poll failed (HTTP ${res.code})")
              throw IllegalStateException(error)
            }
            json
          }

        val pollStatus = pollJson.optString("status", "").trim().lowercase()
        if (pollStatus == "ready") {
          val result = pollJson.optJSONObject("result")
          val pack = result?.optJSONObject("pack")
          if (pack != null) return@withContext parsePack(pack)
          throw IllegalStateException("Ensure ready without pack payload")
        }
        if (pollStatus == "failed") {
          val error = pollJson.optString("error", "Study set generation failed")
          throw IllegalStateException(error)
        }
        pollAfterSec = pollJson.optInt("pollAfterSec", pollAfterSec).coerceIn(2, 20)
      }

      throw IllegalStateException("Study set preparation timed out")
    }

  suspend fun fetchPack(
    anchor: StudySetAnchorRow,
    preferredLanguage: String,
    userAddress: String,
  ): LearnStudySetPack =
    withContext(Dispatchers.IO) {
      val normalizedUserAddress = userAddress.trim()
      val language = preferredLanguage.trim().ifBlank { "en" }
      if (normalizedUserAddress.matches(ADDRESS_REGEX)) {
        val apiError =
          try {
            return@withContext fetchFromApi(
              trackId = anchor.trackId,
              language = language,
              version = anchor.version,
              userAddress = normalizedUserAddress,
            )
          } catch (err: Throwable) {
            err
          }

        val refError =
          try {
            return@withContext fetchFromRef(anchor.studySetRef)
          } catch (err: Throwable) {
            err
          }

        throw IllegalStateException(
          "Study set fetch failed via API and ref fallback: api=${apiError.message ?: "unknown"} ref=${refError.message ?: "unknown"}",
        )
      }

      try {
        fetchFromRef(anchor.studySetRef)
      } catch (refErr: Throwable) {
        throw IllegalStateException("Study set fetch failed: ${refErr.message ?: "invalid user address for API fetch"}")
      }
    }

  private fun fetchFromRef(studySetRef: String): LearnStudySetPack {
    val url =
      CoverRef.resolveCoverUrl(
        ref = studySetRef,
        width = null,
        height = null,
        format = null,
        quality = null,
      ) ?: throw IllegalStateException("Unsupported study set reference: $studySetRef")
    Log.d(TAG, "fetchFromRef ref=$studySetRef url=$url")

    val req = Request.Builder().url(url).get().build()
    client.newCall(req).execute().use { res ->
      if (!res.isSuccessful) throw IllegalStateException("Study set fetch failed: HTTP ${res.code}")
      val json = JSONObject(res.body?.string().orEmpty())
      return parsePack(json)
    }
  }

  private fun fetchFromApi(
    trackId: String,
    language: String,
    version: Int,
    userAddress: String,
  ): LearnStudySetPack {
    val apiBaseUrl = normalizedApiBaseUrl()
      ?: throw IllegalStateException("API unavailable: set API_CORE_URL to an absolute http(s) URL")
    val payload =
      JSONObject()
        .put("trackId", trackId.trim())
        .put("language", language.trim().ifBlank { "en" })
        .put("version", version.coerceIn(1, 255))

    val req =
      Request.Builder()
        .url("$apiBaseUrl/api/study-sets/generate")
        .header("X-User-Address", userAddress)
        .post(payload.toString().toRequestBody((jsonMediaType)))
        .build()

    client.newCall(req).execute().use { res ->
      val rawBody = res.body?.string().orEmpty()
      val json =
        try {
          JSONObject(rawBody)
        } catch (err: Throwable) {
          throw IllegalStateException("Study set API fallback returned invalid JSON (HTTP ${res.code})")
        }

      if (!res.isSuccessful) {
        val error = json.optString("error", "Study set API fallback failed (HTTP ${res.code})")
        throw IllegalStateException(error)
      }

      val packJson = json.optJSONObject("pack") ?: json.optJSONObject("result")?.optJSONObject("pack")
      if (packJson == null) {
        throw IllegalStateException("Study set API fallback missing pack payload")
      }
      return parsePack(packJson)
    }
  }

  private fun parsePack(json: JSONObject): LearnStudySetPack {
    val specVersion = json.optString("specVersion", "").trim()
    if (specVersion != "exercise-pack-v2") {
      throw IllegalStateException("Unsupported study set version: $specVersion")
    }

    val trackId = json.optString("trackId", "").trim()
    val language = json.optString("language", "").trim().ifBlank { "en" }
    val attribution = json.optJSONObject("compliance")?.optJSONObject("attribution")
    val attributionTrack = attribution?.optString("track", "")?.trim().orEmpty().ifBlank { null }
    val attributionArtist = attribution?.optString("artist", "")?.trim().orEmpty().ifBlank { null }
    val rows = json.optJSONArray("questions") ?: JSONArray()
    val questions = ArrayList<LearnStudyQuestion>(rows.length())
    val rawTypeCounts = LinkedHashMap<String, Int>()
    val parsedTypeCounts = LinkedHashMap<String, Int>()

    for (i in 0 until rows.length()) {
      val row = rows.optJSONObject(i) ?: continue
      val id = row.optString("id", "").trim()
      val rawType = row.optString("type", "").trim()
      if (id.isBlank() || rawType.isBlank()) continue
      rawTypeCounts[rawType] = (rawTypeCounts[rawType] ?: 0) + 1
      val type = normalizeQuestionType(rawType = rawType, prompt = row.optString("prompt", ""))
      if (type == null) continue

      parsedTypeCounts[type] = (parsedTypeCounts[type] ?: 0) + 1

      val choicesJson = row.optJSONArray("choices") ?: row.optJSONArray("options") ?: JSONArray()
      val choices = ArrayList<String>(choicesJson.length())
      for (j in 0 until choicesJson.length()) {
        val choice = choicesJson.optString(j, "").trim()
        if (choice.isNotBlank()) choices.add(choice)
      }

      val correctIndex = when {
        row.has("correctIndex") -> row.optInt("correctIndex", 0)
        row.has("answerIndex") -> row.optInt("answerIndex", 0)
        row.has("correctOptionIndex") -> row.optInt("correctOptionIndex", 0)
        else -> 0
      }
      questions.add(
        LearnStudyQuestion(
          id = id,
          questionIdHash = hashQuestionId(id),
          type = type,
          prompt = row.optString("prompt", "").trim(),
          excerpt = row.optString("excerpt", "").trim(),
          choices = choices,
          correctIndex = correctIndex,
          explanation = row.optString("explanation", "").trim().ifBlank { null },
          difficulty = row.optString("difficulty", "medium").trim().ifBlank { "medium" },
        ),
      )
    }

    Log.d(
      TAG,
      "parsePack trackId=$trackId lang=$language totalRows=${rows.length()} parsed=${questions.size} rawTypes=$rawTypeCounts parsedTypes=$parsedTypeCounts",
    )

    return LearnStudySetPack(
      specVersion = specVersion,
      trackId = trackId,
      language = language,
      attributionTrack = attributionTrack,
      attributionArtist = attributionArtist,
      questions = questions,
    )
  }

  private fun hashQuestionId(raw: String): String {
    val digest = Keccak.Digest256().digest(raw.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString(separator = "") { b -> "%02x".format(b) }
    return "0x$hex"
  }

  private fun normalizeQuestionType(
    rawType: String,
    prompt: String,
  ): String? {
    val type = rawType.trim().lowercase()
    val promptLower = prompt.trim().lowercase()
    if (type == "say_it_back" || type == "say-it-back" || type == "sayitback") return "say_it_back"
    if (type.contains("say") && type.contains("back")) return "say_it_back"
    if (type == "translation_mcq" || type.contains("translation")) return "translation_mcq"
    if (type == "trivia_mcq" || type.contains("trivia") || type.contains("quiz") || type.contains("fact")) return "trivia_mcq"
    if (type == "multiple_choice" || type == "multiple-choice" || type == "mcq" || type.contains("mcq")) {
      if (
        promptLower.contains("translate") ||
          promptLower.contains("translation") ||
          promptLower.contains("means in")
      ) {
        return "translation_mcq"
      }
      return "trivia_mcq"
    }
    return null
  }

  private fun normalizedApiBaseUrl(): String? {
    val raw = SongPublishService.API_CORE_URL.trim().trimEnd('/')
    if (raw.startsWith("https://") || raw.startsWith("http://")) return raw
    return null
  }
}
