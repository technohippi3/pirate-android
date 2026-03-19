package sc.pirate.app.store

import sc.pirate.app.tempo.P256Utils
import sc.pirate.app.tempo.TempoClient
import java.io.IOException
import java.math.BigInteger
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32

private const val ETH_CALL_IO_MAX_ATTEMPTS = 3
private const val ETH_CALL_IO_RETRY_DELAY_MS = 250L

internal fun getListing(parentNode: String, label: String): PremiumStoreListing {
  val function =
    Function(
      "quote",
      listOf(
        Bytes32(P256Utils.hexToBytes(parentNode.removePrefix("0x"))),
        Utf8String(label),
      ),
      emptyList(),
    )
  val callData = FunctionEncoder.encode(function)
  val result = ethCall(PremiumNameStoreApi.PREMIUM_NAME_STORE, callData)
  return parseListing(result)
}

private fun parseListing(resultHex: String): PremiumStoreListing {
  val clean = resultHex.removePrefix("0x").lowercase()
  if (clean.isBlank()) {
    return PremiumStoreListing(price = BigInteger.ZERO, durationSeconds = 0L, enabled = false)
  }

  val padded = clean.padStart(64 * 3, '0')
  if (padded.length < 64 * 3) {
    return PremiumStoreListing(price = BigInteger.ZERO, durationSeconds = 0L, enabled = false)
  }

  val price = padded.substring(0, 64).toBigIntegerOrNull(16) ?: BigInteger.ZERO
  val durationWord = padded.substring(64, 128).toBigIntegerOrNull(16) ?: BigInteger.ZERO
  val duration = durationWord.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()
  val enabled = (padded.substring(128, 192).toBigIntegerOrNull(16) ?: BigInteger.ZERO) != BigInteger.ZERO
  return PremiumStoreListing(price = price, durationSeconds = duration, enabled = enabled)
}

internal fun parseUint256(resultHex: String): BigInteger {
  val clean = resultHex.removePrefix("0x").ifBlank { "0" }
  return clean.toBigIntegerOrNull(16) ?: BigInteger.ZERO
}

internal fun ethCall(to: String, data: String): String {
  val payload =
    JSONObject()
      .put("jsonrpc", "2.0")
      .put("id", 1)
      .put("method", "eth_call")
      .put(
        "params",
        JSONArray()
          .put(JSONObject().put("to", to).put("data", data))
          .put("latest"),
      )

  val req =
    Request.Builder()
      .url(TempoClient.RPC_URL)
      .post(payload.toString().toRequestBody(premiumJsonType))
      .build()

  var lastIoError: String? = null
  for (attempt in 1..ETH_CALL_IO_MAX_ATTEMPTS) {
    try {
      premiumHttpClient.newCall(req).execute().use { response ->
        if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
        val body = JSONObject(response.body?.string().orEmpty())
        val error = body.optJSONObject("error")
        if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
        return body.optString("result", "0x")
      }
    } catch (err: IOException) {
      lastIoError = err.message ?: err::class.java.simpleName
      if (attempt < ETH_CALL_IO_MAX_ATTEMPTS) {
        Thread.sleep(ETH_CALL_IO_RETRY_DELAY_MS * attempt)
        continue
      }
      throw IllegalStateException("RPC call failed: $lastIoError", err)
    }
  }
  throw IllegalStateException("RPC call failed: ${lastIoError ?: "unknown error"}")
}
