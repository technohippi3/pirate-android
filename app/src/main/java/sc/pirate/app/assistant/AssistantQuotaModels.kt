package sc.pirate.app.assistant

import org.json.JSONObject

data class AssistantQuotaSnapshot(
  val dayKey: String,
  val verificationTier: String,
  val freeChatMessagesLimit: Int,
  val freeChatMessagesUsed: Int,
  val freeChatMessagesRemaining: Int,
  val freeCallSecondsLimit: Int,
  val freeCallSecondsUsed: Int,
  val freeCallSecondsRemaining: Int,
  val paidChatCreditsUsed: Int,
  val paidCallCreditsUsed: Int,
)

data class AssistantQuotaStatus(
  val quota: AssistantQuotaSnapshot,
  val maxCallSeconds: Int? = null,
  val paidCreditsAvailable: String? = null,
)

internal data class AssistantApiFailure(
  val statusCode: Int,
  val code: String?,
  val error: String,
  val requiredCredits: Int?,
  val quotaStatus: AssistantQuotaStatus?,
)

internal class AssistantQuotaExceededException(
  val statusCode: Int,
  val code: String,
  val quotaStatus: AssistantQuotaStatus?,
  val requiredCredits: Int?,
  message: String,
) : IllegalStateException(message)

internal fun parseAssistantQuotaStatus(body: String): AssistantQuotaStatus? {
  val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
  val quotaJson = root.optJSONObject("quota") ?: return null
  val quota =
    AssistantQuotaSnapshot(
      dayKey = quotaJson.optString("dayKey", ""),
      verificationTier = quotaJson.optString("verificationTier", "unverified"),
      freeChatMessagesLimit = quotaJson.optInt("freeChatMessagesLimit", 0),
      freeChatMessagesUsed = quotaJson.optInt("freeChatMessagesUsed", 0),
      freeChatMessagesRemaining = quotaJson.optInt("freeChatMessagesRemaining", 0),
      freeCallSecondsLimit = quotaJson.optInt("freeCallSecondsLimit", 0),
      freeCallSecondsUsed = quotaJson.optInt("freeCallSecondsUsed", 0),
      freeCallSecondsRemaining = quotaJson.optInt("freeCallSecondsRemaining", 0),
      paidChatCreditsUsed = quotaJson.optInt("paidChatCreditsUsed", 0),
      paidCallCreditsUsed = quotaJson.optInt("paidCallCreditsUsed", 0),
    )
  val callLimits = root.optJSONObject("call_limits")
  val maxCallSeconds = callLimits?.optInt("max_call_seconds", -1)?.takeIf { it >= 0 }
  val paidCreditsAvailable = callLimits
    ?.opt("paid_credits_available")
    ?.toString()
    ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
  return AssistantQuotaStatus(
    quota = quota,
    maxCallSeconds = maxCallSeconds,
    paidCreditsAvailable = paidCreditsAvailable,
  )
}

internal fun parseAssistantApiFailure(statusCode: Int, body: String): AssistantApiFailure {
  val root = runCatching { JSONObject(body) }.getOrNull()
  val code = root?.opt("code")?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
  val defaultError = "Request failed ($statusCode)"
  val error = root?.optString("error", defaultError)?.takeIf { it.isNotBlank() } ?: defaultError
  val requiredCredits = when {
    root == null -> null
    root.has("requiredCredits") -> root.optInt("requiredCredits", -1).takeIf { it >= 0 }
    root.has("required_credits") -> root.optInt("required_credits", -1).takeIf { it >= 0 }
    else -> null
  }
  val quotaStatus = parseAssistantQuotaStatus(body)
  return AssistantApiFailure(
    statusCode = statusCode,
    code = code,
    error = error,
    requiredCredits = requiredCredits,
    quotaStatus = quotaStatus,
  )
}

internal fun Throwable.asAssistantApiFailureOrNull(): AssistantApiFailure? {
  return when (this) {
    is AssistantQuotaExceededException ->
      AssistantApiFailure(
        statusCode = statusCode,
        code = code,
        error = message ?: "Insufficient Violet quota",
        requiredCredits = requiredCredits,
        quotaStatus = quotaStatus,
      )
    else -> null
  }
}

internal fun formatCallSeconds(seconds: Int): String {
  val mins = seconds / 60
  val secs = seconds % 60
  return "${mins}m ${secs}s"
}
