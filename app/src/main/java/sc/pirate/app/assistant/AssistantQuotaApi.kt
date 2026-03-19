package sc.pirate.app.assistant

import android.content.Context
import sc.pirate.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AssistantQuotaApi {
  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

  private fun requireVoiceAgentUrl(): String {
    return BuildConfig.VOICE_AGENT_URL.trim().trimEnd('/').ifBlank {
      throw IllegalStateException("Missing VOICE_AGENT_URL BuildConfig field. Set gradle property VOICE_AGENT_URL.")
    }
  }

  suspend fun fetchQuota(
    appContext: Context,
    userAddress: String,
  ): AssistantQuotaStatus = withContext(Dispatchers.IO) {
    val workerUrl = requireVoiceAgentUrl()
    val auth =
      getWorkerAuthSession(
        appContext = appContext,
        workerUrl = workerUrl,
        userAddress = userAddress,
      )

    val request = Request.Builder()
      .url("$workerUrl/credits/quota")
      .get()
      .header("Authorization", "Bearer ${auth.token}")
      .build()

    httpClient.newCall(request).execute().use { resp ->
      val body = resp.body?.string().orEmpty()
      val parsed = parseAssistantQuotaStatus(body)
      if (!resp.isSuccessful) {
        val error =
          runCatching { JSONObject(body).optString("error") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "Failed to fetch Violet quota (${resp.code})"
        throw IllegalStateException(error)
      }
      return@use parsed
        ?: throw IllegalStateException("Missing quota payload in /credits/quota response")
    }
  }
}
