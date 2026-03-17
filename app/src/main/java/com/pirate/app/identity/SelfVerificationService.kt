package com.pirate.app.identity

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client for Self.xyz verification endpoints on api-core.
 * All methods are blocking — call from Dispatchers.IO.
 */
object SelfVerificationService {

  sealed class IdentityResult {
    data class Verified(
      val age: Int? = null,
      val nationality: String? = null,
      val hasShortNameCredential: Boolean = true,
    ) : IdentityResult()

    data object NotVerified : IdentityResult()

    data class Error(val message: String) : IdentityResult()
  }

  sealed class SessionResult {
    data class Success(
      val sessionId: String,
      val deeplinkUrl: String,
      val expiresAt: Long,
    ) : SessionResult()

    data class Error(val message: String) : SessionResult()
  }

  sealed class SessionStatus {
    data class Success(
      val status: String, // "pending" | "verified" | "failed" | "expired"
      val age: Int? = null,
      val nationality: String? = null,
      val reason: String? = null,
    ) : SessionStatus()

    data class Error(val message: String) : SessionStatus()
  }

  /** Check if user already has a verified identity. */
  fun checkIdentity(apiUrl: String, userAddress: String): IdentityResult {
    val conn = URL("$apiUrl/api/self/identity/${userAddress.lowercase()}").openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.connectTimeout = 10_000
    conn.readTimeout = 10_000
    try {
      val status = conn.responseCode
      if (status == 404) return IdentityResult.NotVerified
      if (status != 200) return IdentityResult.Error("Identity check failed: HTTP $status")
      val body = conn.inputStream.bufferedReader().readText()
      val json = JSONObject(body)
      return IdentityResult.Verified(
        age = json.optInt("currentAge", -1).takeIf { it >= 0 },
        nationality = json.optString("nationality", "").takeIf { it.isNotEmpty() },
        hasShortNameCredential = json.optBoolean("hasShortNameCredential", true),
      )
    } catch (e: Exception) {
      return IdentityResult.Error("Identity check failed: ${e.message}")
    } finally {
      conn.disconnect()
    }
  }

  /** Create a new verification session. Returns deeplink URL to open. */
  fun createSession(apiUrl: String, userAddress: String): SessionResult {
    val conn = URL("$apiUrl/api/self/session").openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.connectTimeout = 10_000
    conn.readTimeout = 10_000
    conn.doOutput = true
    try {
      val payload = JSONObject().put("userAddress", userAddress.lowercase()).toString()
      conn.outputStream.use { it.write(payload.toByteArray()) }
      val status = conn.responseCode
      val body = (if (status in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()
      if (status != 200) return SessionResult.Error("Create session failed: HTTP $status — $body")
      return parseCreateSessionResponse(body)
    } catch (e: Exception) {
      return SessionResult.Error("Create session failed: ${e.message}")
    } finally {
      conn.disconnect()
    }
  }

  /** Poll session status. */
  fun pollSession(apiUrl: String, sessionId: String): SessionStatus {
    val conn = URL("$apiUrl/api/self/session/$sessionId").openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.connectTimeout = 10_000
    conn.readTimeout = 10_000
    try {
      val status = conn.responseCode
      if (status == 404) return SessionStatus.Error("Session not found")
      if (status != 200) return SessionStatus.Error("Poll failed: HTTP $status")
      val body = conn.inputStream.bufferedReader().readText()
      return parsePollSessionResponse(body)
    } catch (e: Exception) {
      return SessionStatus.Error("Poll failed: ${e.message}")
    } finally {
      conn.disconnect()
    }
  }
}

internal fun parseCreateSessionResponse(body: String): SelfVerificationService.SessionResult {
  val json = JSONObject(body)
  val sessionId = json.optString("sessionId", "").takeIf { it.isNotBlank() }
    ?: return SelfVerificationService.SessionResult.Error("Create session failed: missing sessionId")
  val deeplinkUrl = json.optString("deeplinkUrl", "").takeIf { it.isNotBlank() }
    ?: return SelfVerificationService.SessionResult.Error("Create session failed: missing deeplinkUrl")
  val expiresAt = json.optLong("expiresAt", -1L).takeIf { it > 0L }
    ?: return SelfVerificationService.SessionResult.Error("Create session failed: invalid expiresAt")
  return SelfVerificationService.SessionResult.Success(
    sessionId = sessionId,
    deeplinkUrl = deeplinkUrl,
    expiresAt = expiresAt,
  )
}

internal fun parsePollSessionResponse(body: String): SelfVerificationService.SessionStatus {
  val json = JSONObject(body)
  val sessionStatus = json.optString("status", "").takeIf { it.isNotBlank() }
    ?: return SelfVerificationService.SessionStatus.Error("Poll failed: missing status")
  return SelfVerificationService.SessionStatus.Success(
    status = sessionStatus,
    age = json.optInt("age", -1).takeIf { it >= 0 },
    nationality = json.optString("nationality", "").takeIf { it.isNotEmpty() },
    reason = json.optString("reason", "").takeIf { it.isNotEmpty() },
  )
}
