package com.pirate.app.arweave

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

object TurboCreditsApi {
  private const val HTTP_TIMEOUT_MS = 15_000

  data class Balance(
    val winc: BigDecimal,
  ) {
    val hasCredits: Boolean get() = winc > BigDecimal.ZERO
  }

  suspend fun fetchBalance(address: String): Balance = withContext(Dispatchers.IO) {
    val normalized = address.trim().lowercase()
    require(normalized.startsWith("0x") && normalized.length == 42) {
      "Invalid address for Turbo balance lookup"
    }

    val encoded = URLEncoder.encode(normalized, Charsets.UTF_8.name())
    val endpoint = "${ArweaveTurboConfig.DEFAULT_PAYMENT_URL.trimEnd('/')}/v1/balance?address=$encoded"
    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = HTTP_TIMEOUT_MS
      readTimeout = HTTP_TIMEOUT_MS
      setRequestProperty("Accept", "application/json")
    }

    val status = conn.responseCode
    val body =
      if (status in 200..299) {
        conn.inputStream.bufferedReader().use { it.readText() }
      } else {
        conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
      }

    if (status !in 200..299) {
      val detail = body.ifBlank { "HTTP $status" }
      throw IllegalStateException("Turbo balance check failed: $detail")
    }

    parseBalance(body)
  }

  fun isLikelyInsufficientBalanceError(message: String?): Boolean {
    val msg = message?.lowercase().orEmpty()
    if (msg.isBlank()) return false
    return msg.contains("insufficient") ||
      msg.contains("balance") ||
      msg.contains("credit") ||
      msg.contains("payment required") ||
      msg.contains("user not found")
  }

  private fun parseBalance(raw: String): Balance {
    val json = JSONObject(raw)
    val effective = json.optJSONObject("effectiveBalance")
    val wincStr = when {
      effective != null -> effective.optString("winc", "").trim()
      json.has("winc") -> json.optString("winc", "").trim()
      else -> ""
    }

    val winc = wincStr.toBigDecimalOrNull()
      ?: throw IllegalStateException("Turbo balance response missing numeric winc")
    return Balance(winc = winc)
  }
}
