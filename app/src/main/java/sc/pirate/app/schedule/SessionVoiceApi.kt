package sc.pirate.app.schedule

import android.content.Context
import sc.pirate.app.BuildConfig
import sc.pirate.app.assistant.getWorkerAuthSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SessionJoinResult(
  val channel: String,
  val agoraToken: String,
  val userUid: Int,
)

object SessionVoiceApi {
  private fun requireSessionVoiceUrl(): String {
    return BuildConfig.VOICE_CONTROL_PLANE_URL.trim().trimEnd('/').ifBlank {
      throw IllegalStateException("Missing VOICE_CONTROL_PLANE_URL BuildConfig field. Set gradle property VOICE_CONTROL_PLANE_URL.")
    }
  }

  private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

  suspend fun joinSession(
    appContext: Context,
    userAddress: String,
    bookingId: Long,
  ): SessionJoinResult = withContext(Dispatchers.IO) {
    val sessionVoiceUrl = requireSessionVoiceUrl()
    val auth = getWorkerAuthSession(
      appContext = appContext,
      workerUrl = sessionVoiceUrl,
      userAddress = userAddress,
    )

    val body = JSONObject()
      .put("booking_id", bookingId.toString())
      .toString()
      .toRequestBody(JSON_MEDIA_TYPE)

    val request = Request.Builder()
      .url("${sessionVoiceUrl}/session/join")
      .post(body)
      .header("Authorization", "Bearer ${auth.token}")
      .build()

    return@withContext httpClient.newCall(request).execute().use { response ->
      val raw = response.body?.string().orEmpty()
      val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
      if (!response.isSuccessful) {
        val message = json.optString("error", "join failed (${response.code})")
        throw IllegalStateException(message)
      }

      val channel = json.optString("channel", "")
      val token = json.optString("agora_token", "")
      val uid = json.optInt("user_uid", 0)
      if (channel.isBlank() || token.isBlank() || uid <= 0) {
        throw IllegalStateException("Invalid join response from session voice service.")
      }

      SessionJoinResult(
        channel = channel,
        agoraToken = token,
        userUid = uid,
      )
    }
  }

  suspend fun leaveSession(
    appContext: Context,
    userAddress: String,
    bookingId: Long,
  ) = withContext(Dispatchers.IO) {
    val sessionVoiceUrl = requireSessionVoiceUrl()
    val auth = getWorkerAuthSession(
      appContext = appContext,
      workerUrl = sessionVoiceUrl,
      userAddress = userAddress,
    )

    val request = Request.Builder()
      .url("${sessionVoiceUrl}/session/$bookingId/leave")
      .post("{}".toRequestBody(JSON_MEDIA_TYPE))
      .header("Authorization", "Bearer ${auth.token}")
      .build()

    httpClient.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        val raw = response.body?.string().orEmpty()
        val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        val message = json.optString("error", "leave failed (${response.code})")
        throw IllegalStateException(message)
      }
    }
  }
}
