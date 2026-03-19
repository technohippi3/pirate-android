package sc.pirate.app.learn

import android.util.Base64
import android.util.Log
import sc.pirate.app.music.SongPublishService
import sc.pirate.app.util.HttpClients
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal data class SayItBackScoreResult(
  val success: Boolean,
  val passed: Boolean,
  val score: Double?,
  val transcript: String?,
  val streakAttestation: SayItBackStreakAttestation?,
  val errorCode: String?,
  val error: String?,
)

internal data class SayItBackStreakAttestation(
  val studySetKey: String,
  val dayUtc: Long,
  val nonce: Long,
  val expiry: Long,
  val signature: String,
)

internal object LearnSayItBackApi {
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
  private val client = HttpClients.Api
  private val addressRegex = Regex("^0x[a-fA-F0-9]{40}$")
  private val bytes32Regex = Regex("^0x[a-fA-F0-9]{64}$")
  private val signatureRegex = Regex("^0x[a-fA-F0-9]{130}$")

  suspend fun scoreRecording(
    trackId: String,
    questionId: String,
    expectedExcerpt: String,
    version: Int,
    language: String,
    difficulty: String,
    userAddress: String,
    audioFile: File,
  ): SayItBackScoreResult =
    withContext(Dispatchers.IO) {
      val apiBaseUrl = normalizedApiBaseUrl()
        ?: return@withContext SayItBackScoreResult(
          success = false,
          passed = false,
          score = null,
          transcript = null,
          streakAttestation = null,
          errorCode = "invalid_api_base_url",
          error = "API unavailable: set API_CORE_URL to an absolute http(s) URL",
        )
      val normalizedUserAddress = userAddress.trim()
      if (!addressRegex.matches(normalizedUserAddress)) {
        return@withContext SayItBackScoreResult(
          success = false,
          passed = false,
          score = null,
          transcript = null,
          streakAttestation = null,
          errorCode = "invalid_user_address",
          error = "Missing or invalid user address",
        )
      }

      val bytes = runCatching { audioFile.readBytes() }.getOrElse { err ->
        return@withContext SayItBackScoreResult(
          success = false,
          passed = false,
          score = null,
          transcript = null,
          streakAttestation = null,
          errorCode = "audio_read_failed",
          error = err.message ?: "Failed to read audio",
        )
      }
      if (bytes.isEmpty()) {
        return@withContext SayItBackScoreResult(
          success = false,
          passed = false,
          score = null,
          transcript = null,
          streakAttestation = null,
          errorCode = "audio_empty",
          error = "Recorded audio is empty",
        )
      }

      val audioBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
      Log.d(
        "LearnSayItBack",
        "score request questionId=$questionId expectedExcerpt=${expectedExcerpt.trim()} trackId=$trackId language=${language.ifBlank { "en" }} version=${version.coerceIn(1, 255)} bytes=${bytes.size}",
      )
      val body = JSONObject().apply {
        put("trackId", trackId)
        put("questionId", questionId)
        put("expectedExcerpt", expectedExcerpt)
        put("version", version.coerceIn(1, 255))
        put("language", language.ifBlank { "en" })
        put("difficulty", difficulty.ifBlank { "medium" })
        put("audioBase64", audioBase64)
        put("audioMimeType", "audio/mp4")
      }

      val request =
        Request.Builder()
          .url("$apiBaseUrl/api/study-sets/say-it-back/score")
          .post(body.toString().toRequestBody(jsonMediaType))
          .header("Content-Type", "application/json")
          .header("X-User-Address", normalizedUserAddress)
          .build()

      return@withContext runCatching {
        client.newCall(request).execute().use { res ->
          val text = res.body?.string().orEmpty()
          val json = runCatching { JSONObject(text) }.getOrNull()
          val success = json?.optBoolean("success") == true
          if (!res.isSuccessful || !success) {
            SayItBackScoreResult(
              success = false,
              passed = false,
              score = null,
              transcript = json?.optString("transcript")?.ifBlank { null },
              streakAttestation = null,
              errorCode = json?.optString("code")?.ifBlank { null },
              error = json?.optString("error")?.ifBlank { "HTTP ${res.code}" } ?: "HTTP ${res.code}",
            )
          } else {
            val passed = json.optBoolean("passed", false)
            SayItBackScoreResult(
              success = true,
              passed = passed,
              score = if (json.has("score")) json.optDouble("score") else null,
              transcript = json.optString("transcript").ifBlank { null },
              streakAttestation = if (passed) parseStreakAttestation(json.optJSONObject("attestation")) else null,
              errorCode = null,
              error = null,
            )
          }
        }
      }.getOrElse { err ->
        SayItBackScoreResult(
          success = false,
          passed = false,
          score = null,
          transcript = null,
          streakAttestation = null,
          errorCode = "network_error",
          error = err.message ?: "Network error",
        )
      }
    }

  private fun parseStreakAttestation(raw: JSONObject?): SayItBackStreakAttestation? {
    if (raw == null) return null
    val studySetKey = raw.optString("studySetKey", "").trim()
    val dayUtc = parseLongField(raw, "dayUtc")
    val nonce = parseLongField(raw, "nonce")
    val expiry = parseLongField(raw, "expiry")
    val signature = raw.optString("signature", "").trim()

    if (!bytes32Regex.matches(studySetKey)) return null
    if (dayUtc == null || dayUtc < 0L) return null
    if (nonce == null || nonce < 0L) return null
    if (expiry == null || expiry <= 0L) return null
    if (!signatureRegex.matches(signature)) return null

    return SayItBackStreakAttestation(
      studySetKey = studySetKey.lowercase(),
      dayUtc = dayUtc,
      nonce = nonce,
      expiry = expiry,
      signature = signature,
    )
  }

  private fun parseLongField(json: JSONObject, key: String): Long? {
    val asString = json.optString(key, "").trim()
    if (asString.isNotBlank()) {
      asString.toLongOrNull()?.let { return it }
    }
    if (json.has(key)) {
      val asLong = json.optLong(key, Long.MIN_VALUE)
      if (asLong != Long.MIN_VALUE) return asLong
    }
    return null
  }

  private fun normalizedApiBaseUrl(): String? {
    val raw = SongPublishService.API_CORE_URL.trim().trimEnd('/')
    if (raw.startsWith("https://") || raw.startsWith("http://")) return raw
    return null
  }
}
