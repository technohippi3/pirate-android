package com.pirate.app.home

import com.pirate.app.music.SongPublishService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object FeedTranslationApi {
  private val client = OkHttpClient()
  private val jsonType = "application/json".toMediaType()

  data class ResolveResult(
    val text: String,
    val locale: String,
    val source: String,
  )

  fun resolvePostCaption(
    postId: String,
    locale: String,
  ): ResolveResult {
    val payload = JSONObject().put("postId", postId).put("locale", locale)
    val req =
      Request.Builder()
        .url("${SongPublishService.API_CORE_URL}/api/music/post-translation/resolve")
        .post(payload.toString().toRequestBody(jsonType))
        .build()

    client.newCall(req).execute().use { response ->
      val raw = response.body?.string().orEmpty()
      val json = runCatching { JSONObject(raw) }.getOrNull()
      if (!response.isSuccessful) {
        val details = json?.optString("error", "")?.trim().orEmpty().ifBlank { raw.ifBlank { "HTTP ${response.code}" } }
        throw IllegalStateException("Caption translation failed: $details")
      }
      val text = json?.optString("text", "")?.trim().orEmpty()
      if (text.isBlank()) throw IllegalStateException("Caption translation returned empty text")
      return ResolveResult(
        text = text,
        locale = json?.optString("locale", "")?.trim().orEmpty().ifBlank { locale },
        source = json?.optString("source", "")?.trim().orEmpty().ifBlank { "unknown" },
      )
    }
  }
}
