package sc.pirate.app.songpicker

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import sc.pirate.app.music.SongPublishService

data class SongTerms(
  val commercialUse: Boolean,
  val commercialRevSharePpm8: Int,
  val approvalMode: String,
  val approvalSlaSec: Int,
  val remixable: Boolean,
)

object SongTermsApi {
  private val httpClient: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(20, TimeUnit.SECONDS)
      .build()

  suspend fun fetchSongTermsBatch(trackIds: List<String>): Map<String, SongTerms> {
    val normalizedTrackIds =
      trackIds
        .asSequence()
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(100)
        .toList()
    if (normalizedTrackIds.isEmpty()) return emptyMap()

    val apiBase = SongPublishService.API_CORE_URL.trim().trimEnd('/')
    return withContext(Dispatchers.IO) {
      runCatching {
        val out = linkedMapOf<String, SongTerms>()
        for (chunk in normalizedTrackIds.chunked(100)) {
          if (chunk.isEmpty()) continue
          val trackIdsParam = chunk.joinToString(",")
          val request =
            Request.Builder()
              .url("$apiBase/api/music/terms?trackIds=$trackIdsParam")
              .get()
              .build()
          httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
              throw IllegalStateException("Song terms request failed: ${response.code}")
            }
            val payload = JSONObject(response.body?.string().orEmpty())
            val termsJson = payload.optJSONObject("terms") ?: JSONObject()
            for (trackId in chunk) {
              val row = termsJson.optJSONObject(trackId) ?: continue
              out[trackId] =
                SongTerms(
                  commercialUse = row.optBoolean("commercialUse", true),
                  commercialRevSharePpm8 = row.optInt("commercialRevSharePpm8", 0),
                  approvalMode = normalizeApprovalMode(row.optString("approvalMode", "auto")),
                  approvalSlaSec = row.optInt("approvalSlaSec", 259200),
                  remixable = row.optBoolean("remixable", false),
                )
            }
          }
        }
        out
      }.getOrElse { emptyMap() }
    }
  }

  private fun normalizeApprovalMode(value: String): String {
    return if (value.trim().equals("gated", ignoreCase = true)) "gated" else "auto"
  }
}
