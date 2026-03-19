package sc.pirate.app.scrobble

import sc.pirate.app.tempo.P256Utils
import sc.pirate.app.tempo.TempoClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.Utf8String

private const val GAS_LIMIT_BUFFER = 250_000L
private const val MAX_TRACK_REF_BYTES = 128
private const val RELAY_MIN_PRIORITY_FEE_PER_GAS = 6_000_000_000L
private const val RELAY_MIN_MAX_FEE_PER_GAS = 120_000_000_000L
private const val SCROBBLE_DEFAULT_EXPIRY_WINDOW_SEC = 25L

private val lastBidByAddress = mutableMapOf<String, TempoClient.Eip1559Fees>()
private val jsonType = "application/json; charset=utf-8".toMediaType()
private val client = OkHttpClient()

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
      .post(payload.toString().toRequestBody(jsonType))
      .build()

  client.newCall(req).execute().use { response ->
    if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
    val body = JSONObject(response.body?.string().orEmpty())
    val error = body.optJSONObject("error")
    if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
    return body.optString("result", "0x")
  }
}

internal fun ethGetCode(contractAddress: String): String {
  val payload =
    JSONObject()
      .put("jsonrpc", "2.0")
      .put("id", 1)
      .put("method", "eth_getCode")
      .put(
        "params",
        JSONArray()
          .put(contractAddress)
          .put("latest"),
      )

  val req =
    Request.Builder()
      .url(TempoClient.RPC_URL)
      .post(payload.toString().toRequestBody(jsonType))
      .build()

  client.newCall(req).execute().use { response ->
    if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
    val body = JSONObject(response.body?.string().orEmpty())
    val error = body.optJSONObject("error")
    if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
    return body.optString("result", "0x")
  }
}

internal fun contractRuntimeContainsSelector(selectorHex: String): Boolean {
  val runtime = ethGetCode(TempoScrobbleApi.SCROBBLE_V4).removePrefix("0x").lowercase()
  if (runtime.isBlank()) return false
  return runtime.contains(selectorHex.lowercase())
}

internal fun estimateGas(
  from: String,
  to: String,
  data: String,
): Long {
  val payload =
    JSONObject()
      .put("jsonrpc", "2.0")
      .put("id", 1)
      .put("method", "eth_estimateGas")
      .put(
        "params",
        JSONArray()
          .put(
            JSONObject()
              .put("from", from)
              .put("to", to)
              .put("data", data),
          ),
      )

  val req =
    Request.Builder()
      .url(TempoClient.RPC_URL)
      .post(payload.toString().toRequestBody(jsonType))
      .build()

  client.newCall(req).execute().use { response ->
    if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
    val body = JSONObject(response.body?.string().orEmpty())
    val error = body.optJSONObject("error")
    if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
    val hex = body.optString("result", "0x0").removePrefix("0x").ifBlank { "0" }
    return hex.toLongOrNull(16) ?: 0L
  }
}

internal fun truncateUtf8(value: String, maxBytes: Int): String {
  if (value.isEmpty()) return value
  if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value

  var out = value
  while (out.isNotEmpty() && out.toByteArray(Charsets.UTF_8).size > maxBytes) {
    out = out.dropLast(1)
  }
  return out
}

private fun isReceiptTimeout(error: Throwable): Boolean =
  error.message?.contains("Timed out waiting for transaction receipt", ignoreCase = true) == true

internal suspend fun awaitScrobbleReceipt(
  txHash: String,
  expiryWindowSec: Long = SCROBBLE_DEFAULT_EXPIRY_WINDOW_SEC,
): TempoClient.TransactionReceipt {
  val timeoutMs = (expiryWindowSec + 5L) * 1000L
  val receipt =
    withContext(Dispatchers.IO) {
      try {
        TempoClient.waitForTransactionReceipt(txHash, timeoutMs = timeoutMs)
      } catch (error: Throwable) {
        if (!isReceiptTimeout(error)) throw error
        null
      }
    }
  if (receipt != null) return receipt

  val txStillKnown = withContext(Dispatchers.IO) { TempoClient.hasTransaction(txHash) }
  if (!txStillKnown) {
    throw IllegalStateException("Scrobble tx dropped before inclusion: $txHash")
  }

  delay(2_000L)
  val lateReceipt = withContext(Dispatchers.IO) { TempoClient.getTransactionReceipt(txHash) }
  if (lateReceipt != null) return lateReceipt
  throw IllegalStateException("Scrobble tx not confirmed before expiry: $txHash")
}

internal fun isReplacementUnderpriced(error: Throwable): Boolean =
  error.message?.contains("replacement transaction underpriced", ignoreCase = true) == true

internal fun decodeUtf8Field(
  decoded: List<Type<*>>,
  index: Int,
): String? {
  val value = (decoded.getOrNull(index) as? Utf8String)?.value?.trim().orEmpty()
  return value.ifBlank { null }
}

internal fun normalizeTrackId(trackId: String): String {
  val clean = trackId.trim().removePrefix("0x").removePrefix("0X")
  require(clean.length == 64 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
    "Invalid trackId"
  }
  return "0x${clean.lowercase()}"
}

internal fun normalizeTrackRef(
  value: String,
  fieldName: String,
): String {
  val normalized = value.trim()
  require(normalized.isNotEmpty()) { "$fieldName is required" }
  val canonical =
    normalized.startsWith("ipfs://") ||
      normalized.startsWith("ar://") ||
      normalized.startsWith("Qm") ||
      normalized.startsWith("bafy")
  require(canonical) {
    "$fieldName must use canonical cover ref format"
  }
  val length = normalized.toByteArray(Charsets.UTF_8).size
  require(length <= MAX_TRACK_REF_BYTES) {
    "$fieldName exceeds max length ($length > $MAX_TRACK_REF_BYTES)"
  }
  return normalized
}

internal fun isTrackNotRegistered(error: Throwable): Boolean {
  val message = error.message?.trim()?.lowercase().orEmpty()
  return message.contains("not registered")
}

@Suppress("UNCHECKED_CAST")
internal fun decodeFunctionResult(
  result: String,
  outputs: List<TypeReference<*>>,
): List<Type<*>> = FunctionReturnDecoder.decode(result, outputs as List<TypeReference<Type<*>>>)

internal fun applyReplacementFeeFloor(
  suggested: TempoClient.Eip1559Fees,
  existingTx: TempoClient.SenderNonceTransaction?,
): TempoClient.Eip1559Fees {
  if (existingTx == null || existingTx.isMined) return suggested

  val existingPriority = existingTx.maxPriorityFeePerGas ?: existingTx.gasPrice ?: 0L
  val existingMaxFee = existingTx.maxFeePerGas ?: existingTx.gasPrice ?: 0L
  val existingBid = maxOf(existingTx.gasPrice ?: 0L, existingMaxFee)
  if (existingPriority <= 0L && existingMaxFee <= 0L && existingBid <= 0L) return suggested

  // Stuck nonce replacement on Tempo occasionally requires a much stronger bid than a
  // standard 10-25% bump. We floor to ~2x the prior gas bid to force acceptance.
  val minPriority = maxOf(bumpForReplacement(existingPriority), saturatingMul(existingBid, 2))
  val minMaxFee = maxOf(bumpForReplacement(existingMaxFee), saturatingMul(existingBid, 2))

  val priority = maxOf(suggested.maxPriorityFeePerGas, minPriority)
  val maxFee = maxOf(
    suggested.maxFeePerGas,
    minMaxFee,
    saturatingAdd(priority, 1_000_000L),
  )
  return TempoClient.Eip1559Fees(
    maxPriorityFeePerGas = priority,
    maxFeePerGas = maxFee,
  )
}

private fun bumpForReplacement(value: Long): Long {
  if (value <= 0L) return value
  val twentyFivePercent = maxOf(1L, value / 4L)
  return saturatingAdd(value, twentyFivePercent)
}

internal fun saturatingAdd(a: Long, b: Long): Long =
  if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

internal fun saturatingMul(a: Long, factor: Int): Long =
  if (a > Long.MAX_VALUE / factor) Long.MAX_VALUE else a * factor

internal fun aggressivelyBumpFees(fees: TempoClient.Eip1559Fees): TempoClient.Eip1559Fees {
  val priority = maxOf(bumpForReplacement(fees.maxPriorityFeePerGas), saturatingMul(fees.maxPriorityFeePerGas, 2))
  val maxFee = maxOf(
    bumpForReplacement(fees.maxFeePerGas),
    saturatingMul(fees.maxFeePerGas, 2),
    saturatingAdd(priority, 1_000_000L),
  )
  return TempoClient.Eip1559Fees(maxPriorityFeePerGas = priority, maxFeePerGas = maxFee)
}

internal fun withRelayMinimumFeeFloor(fees: TempoClient.Eip1559Fees): TempoClient.Eip1559Fees {
  val priority = maxOf(fees.maxPriorityFeePerGas, RELAY_MIN_PRIORITY_FEE_PER_GAS)
  val maxFee = maxOf(
    fees.maxFeePerGas,
    RELAY_MIN_MAX_FEE_PER_GAS,
    saturatingAdd(priority, 1_000_000L),
  )
  return TempoClient.Eip1559Fees(maxPriorityFeePerGas = priority, maxFeePerGas = maxFee)
}

internal fun withAddressBidFloor(
  address: String,
  fees: TempoClient.Eip1559Fees,
): TempoClient.Eip1559Fees {
  val key = address.trim().lowercase()
  if (key.isBlank()) return fees
  val previous = synchronized(lastBidByAddress) { lastBidByAddress[key] } ?: return fees
  val priority = maxOf(fees.maxPriorityFeePerGas, previous.maxPriorityFeePerGas)
  val maxFee = maxOf(fees.maxFeePerGas, previous.maxFeePerGas, saturatingAdd(priority, 1_000_000L))
  return TempoClient.Eip1559Fees(maxPriorityFeePerGas = priority, maxFeePerGas = maxFee)
}

internal fun rememberAddressBidFloor(
  address: String,
  fees: TempoClient.Eip1559Fees,
) {
  val key = address.trim().lowercase()
  if (key.isBlank()) return
  synchronized(lastBidByAddress) {
    val previous = lastBidByAddress[key]
    if (previous == null) {
      lastBidByAddress[key] = fees
    } else {
      lastBidByAddress[key] =
        TempoClient.Eip1559Fees(
          maxPriorityFeePerGas = maxOf(previous.maxPriorityFeePerGas, fees.maxPriorityFeePerGas),
          maxFeePerGas = maxOf(previous.maxFeePerGas, fees.maxFeePerGas),
        )
    }
  }
}

internal fun withBuffer(
  estimated: Long,
  minimum: Long,
): Long {
  if (estimated <= 0L) return minimum
  val padded = saturatingAdd(saturatingMul(estimated, 3) / 2, GAS_LIMIT_BUFFER)
  return maxOf(minimum, padded)
}

internal fun nowSec(): Long = System.currentTimeMillis() / 1000L
