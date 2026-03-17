package com.pirate.app.assistant

import android.content.Context
import android.util.Log
import com.pirate.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class AssistantMessage(
  val id: String,
  val role: String, // "user" or "assistant"
  val content: String,
  val timestamp: Long,
)

class AssistantService(private val appContext: Context) {

  companion object {
    private const val TAG = "AssistantService"
    private const val PREFS_NAME = "assistant_prefs"
    private const val KEY_MESSAGES = "messages"
    private const val MAX_HISTORY = 20
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
  }

  private fun requireChatWorkerUrl(): String {
    return BuildConfig.VOICE_AGENT_URL.trim().trimEnd('/').ifBlank {
      throw IllegalStateException("Missing VOICE_AGENT_URL BuildConfig field. Set gradle property VOICE_AGENT_URL.")
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _messages = MutableStateFlow<List<AssistantMessage>>(emptyList())
  val messages: StateFlow<List<AssistantMessage>> = _messages

  private val _sending = MutableStateFlow(false)
  val sending: StateFlow<Boolean> = _sending

  private val _quota = MutableStateFlow<AssistantQuotaStatus?>(null)
  val quota: StateFlow<AssistantQuotaStatus?> = _quota

  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

  private val loadMessagesJob: Job

  init {
    // Avoid synchronous disk/JSON work during first composition.
    loadMessagesJob = scope.launch { loadMessages() }
  }

  /**
   * Send a message to Assistant and get her response.
   */
  suspend fun sendMessage(
    text: String,
    userAddress: String,
  ): Result<String> {
    if (text.isBlank()) return Result.failure(IllegalArgumentException("Empty message"))
    loadMessagesJob.join()

    val userMsg = AssistantMessage(
      id = UUID.randomUUID().toString(),
      role = "user",
      content = text.trim(),
      timestamp = System.currentTimeMillis(),
    )
    _messages.value = _messages.value + userMsg
    saveMessages()

    _sending.value = true

    return try {
      val chatWorkerUrl = requireChatWorkerUrl()
      val auth = getWorkerAuthSession(
        appContext = appContext,
        workerUrl = chatWorkerUrl,
        userAddress = userAddress,
      )

      val history = _messages.value.takeLast(MAX_HISTORY).map { msg ->
        JSONObject().put("role", msg.role).put("content", msg.content)
      }
      val historyArray = JSONArray().apply { history.forEach { put(it) } }

      val payload = JSONObject()
        .put("message", text.trim())
        .put("clientMessageId", userMsg.id)
        .put("history", historyArray)
        .put("activityWallet", auth.wallet)
        .toString()
        .toRequestBody(JSON_MEDIA_TYPE)

      val req = Request.Builder()
        .url("${chatWorkerUrl}/chat/send")
        .post(payload)
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer ${auth.token}")
        .build()

      val response = withContext(Dispatchers.IO) {
        httpClient.newCall(req).execute().use { resp ->
          val body = resp.body?.string().orEmpty()
          val quotaStatus = parseAssistantQuotaStatus(body)
          if (quotaStatus != null) {
            _quota.value = quotaStatus
          }
          if (!resp.isSuccessful) {
            val failure = parseAssistantApiFailure(resp.code, body)
            if (failure.quotaStatus != null) {
              _quota.value = failure.quotaStatus
            }
            if (failure.code == "insufficient_credits") {
              throw AssistantQuotaExceededException(
                statusCode = failure.statusCode,
                code = failure.code,
                quotaStatus = failure.quotaStatus,
                requiredCredits = failure.requiredCredits,
                message = failure.error,
              )
            }
            throw IllegalStateException("Chat request failed (${resp.code}): ${failure.error}")
          }
          val root = runCatching { JSONObject(body) }.getOrNull()
          val msg = root?.optString("message", "") ?: ""
          sanitizeChatMessage(msg) to quotaStatus
        }
      }
      val responseText = response.first

      val assistantMsg = AssistantMessage(
        id = UUID.randomUUID().toString(),
        role = "assistant",
        content = responseText,
        timestamp = System.currentTimeMillis(),
      )
      _messages.value = _messages.value + assistantMsg
      saveMessages()

      Result.success(responseText)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to send message", e)
      Result.failure(e)
    } finally {
      _sending.value = false
    }
  }

  fun clearHistory() {
    _messages.value = emptyList()
    saveMessages()
    clearWorkerAuthCache()
  }

  suspend fun refreshQuota(userAddress: String): Result<AssistantQuotaStatus> {
    return runCatching {
      AssistantQuotaApi.fetchQuota(appContext = appContext, userAddress = userAddress)
    }.onSuccess { quotaStatus ->
      _quota.value = quotaStatus
    }
  }

  // --- Persistence ---

  private suspend fun loadMessages() = withContext(Dispatchers.IO) {
    try {
      val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      val json = prefs.getString(KEY_MESSAGES, null) ?: return@withContext
      val arr = JSONArray(json)
      val list = mutableListOf<AssistantMessage>()
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        list.add(
          AssistantMessage(
            id = obj.getString("id"),
            role = obj.getString("role"),
            content = obj.getString("content"),
            timestamp = obj.getLong("timestamp"),
          ),
        )
      }
      _messages.value = list
    } catch (e: Exception) {
      Log.e(TAG, "Failed to load messages", e)
    }
  }

  private fun saveMessages() {
    scope.launch {
      try {
        val arr = JSONArray()
        // Keep last 100 messages in storage
        _messages.value.takeLast(100).forEach { msg ->
          arr.put(
            JSONObject()
              .put("id", msg.id)
              .put("role", msg.role)
              .put("content", msg.content)
              .put("timestamp", msg.timestamp),
          )
        }
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MESSAGES, arr.toString()).apply()
      } catch (e: Exception) {
        Log.e(TAG, "Failed to save messages", e)
      }
    }
  }

  // --- Message sanitization (port from GPUI) ---

  private fun stripThinkSections(input: String): String {
    val output = StringBuilder(input.length)
    var cursor = 0
    val lower = input.lowercase()

    while (true) {
      val startRel = lower.indexOf("<think>", cursor)
      if (startRel == -1) break
      output.append(input, cursor, startRel)
      val bodyStart = startRel + "<think>".length
      val endRel = lower.indexOf("</think>", bodyStart)
      cursor = if (endRel != -1) endRel + "</think>".length else input.length
    }

    if (cursor < input.length) output.append(input, cursor, input.length)
    return output.toString()
  }

  private fun sanitizeChatMessage(raw: String): String {
    val stripped = stripThinkSections(raw)
    // Remove any leftover tags
    val cleaned = stripped
      .replace(Regex("</?think>", RegexOption.IGNORE_CASE), "")
      .trim()

    return cleaned.ifEmpty { "Sorry, I could not generate a response." }
  }
}
