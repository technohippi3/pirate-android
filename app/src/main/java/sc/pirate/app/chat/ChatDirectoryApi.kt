package sc.pirate.app.chat

import sc.pirate.app.util.tempoProfilesSubgraphUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class ChatDirectoryProfile(
  val address: String,
  val displayName: String,
  val photoUri: String?,
)

object ChatDirectoryApi {
  private val jsonType = "application/json; charset=utf-8".toMediaType()
  private val client = OkHttpClient()

  suspend fun searchProfilesByDisplayNamePrefix(
    query: String,
    first: Int = 12,
  ): List<ChatDirectoryProfile> = withContext(Dispatchers.IO) {
    val endpoint = tempoProfilesSubgraphUrls().first()

    val needle = query.trim()
    if (needle.isBlank()) return@withContext emptyList()

    val safeFirst = first.coerceIn(1, 30)
    val escapedNeedle = escapeForGraphQl(needle)
    val gql =
      """
      {
        profiles(
          first: $safeFirst
          orderBy: updatedAt
          orderDirection: desc
          where: { displayName_starts_with_nocase: "$escapedNeedle" }
        ) {
          id
          displayName
          photoURI
        }
      }
      """.trimIndent()

    val payload = JSONObject().put("query", gql)
    val response = postGraphQl(endpoint, payload)
    val data = response.optJSONObject("data") ?: JSONObject()
    val rows = data.optJSONArray("profiles") ?: JSONArray()
    val out = ArrayList<ChatDirectoryProfile>(rows.length())
    for (i in 0 until rows.length()) {
      val row = rows.optJSONObject(i) ?: continue
      val address = row.optString("id", "").trim().lowercase()
      if (!looksLikeAddress(address)) continue
      val displayName = row.optString("displayName", "").trim()
      val photoUri = row.optString("photoURI", "").trim().ifBlank { null }
      out.add(
        ChatDirectoryProfile(
          address = address,
          displayName = displayName,
          photoUri = photoUri,
        ),
      )
    }
    out
  }

  private fun postGraphQl(url: String, payload: JSONObject): JSONObject {
    val req =
      Request.Builder()
        .url(url)
        .post(payload.toString().toRequestBody(jsonType))
        .build()
    client.newCall(req).execute().use { res ->
      if (!res.isSuccessful) throw IllegalStateException("Directory query failed: HTTP ${res.code}")
      val json = JSONObject(res.body?.string().orEmpty())
      val errors = json.optJSONArray("errors")
      if (errors != null && errors.length() > 0) {
        val first = errors.optJSONObject(0)
        val msg = first?.optString("message", "").orEmpty().ifBlank { errors.toString() }
        throw IllegalStateException("Directory query failed: $msg")
      }
      return json
    }
  }

  private fun escapeForGraphQl(value: String): String {
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
  }

  private fun looksLikeAddress(value: String): Boolean {
    if (value.length != 42 || !value.startsWith("0x")) return false
    return value.drop(2).all { it.isDigit() || it in 'a'..'f' }
  }
}
