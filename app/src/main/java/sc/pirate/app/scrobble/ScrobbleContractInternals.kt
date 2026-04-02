package sc.pirate.app.scrobble

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import sc.pirate.app.PirateChainConfig

private const val JSONRPC_VERSION = "2.0"
private const val DEFAULT_EXPIRY_WINDOW_SEC = 25L
private val jsonType = "application/json; charset=utf-8".toMediaType()
private val client = OkHttpClient()

internal data class ScrobbleTransactionReceipt(
  val txHash: String,
  val statusHex: String,
) {
  val isSuccess: Boolean
    get() = statusHex.equals("0x1", ignoreCase = true)
}

internal fun ethCall(to: String, data: String): String {
  val body = rpcRequest(
    method = "eth_call",
    params = JSONArray().put(JSONObject().put("to", to).put("data", data)).put("latest"),
  )
  return body.optString("result", "0x")
}

internal suspend fun awaitScrobbleReceipt(
  txHash: String,
  expiryWindowSec: Long = DEFAULT_EXPIRY_WINDOW_SEC,
): ScrobbleTransactionReceipt {
  val deadlineMs = System.currentTimeMillis() + (expiryWindowSec + 5L) * 1000L
  while (System.currentTimeMillis() < deadlineMs) {
    fetchReceiptOrNull(txHash)?.let { return it }
    delay(1_500L)
  }

  val txStillKnown = withContext(Dispatchers.IO) { hasTransaction(txHash) }
  if (!txStillKnown) {
    throw IllegalStateException("Scrobble tx dropped before inclusion: $txHash")
  }

  delay(2_000L)
  return fetchReceiptOrNull(txHash)
    ?: throw IllegalStateException("Scrobble tx not confirmed before expiry: $txHash")
}

private fun fetchReceiptOrNull(txHash: String): ScrobbleTransactionReceipt? {
  val body = rpcRequest(
    method = "eth_getTransactionReceipt",
    params = JSONArray().put(txHash),
    allowNullResult = true,
  )
  val receipt = body.optJSONObject("result") ?: return null
  return ScrobbleTransactionReceipt(
    txHash = receipt.optString("transactionHash", txHash),
    statusHex = receipt.optString("status", "0x0"),
  )
}

private fun hasTransaction(txHash: String): Boolean {
  val body = rpcRequest(
    method = "eth_getTransactionByHash",
    params = JSONArray().put(txHash),
    allowNullResult = true,
  )
  val tx = body.optJSONObject("result") ?: return false
  return tx.optString("hash", "").trim().equals(txHash, ignoreCase = true)
}

private fun rpcRequest(
  method: String,
  params: JSONArray,
  allowNullResult: Boolean = false,
): JSONObject {
  val payload =
    JSONObject()
      .put("jsonrpc", JSONRPC_VERSION)
      .put("id", 1)
      .put("method", method)
      .put("params", params)

  val req =
    Request.Builder()
      .url(PirateChainConfig.STORY_AENEID_RPC_URL)
      .post(payload.toString().toRequestBody(jsonType))
      .build()

  client.newCall(req).execute().use { response ->
    if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
    val body = JSONObject(response.body?.string().orEmpty())
    val error = body.optJSONObject("error")
    if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
    if (!allowNullResult && !body.has("result")) {
      throw IllegalStateException("RPC response missing result for $method")
    }
    return body
  }
}
