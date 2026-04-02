package sc.pirate.app.store

import sc.pirate.app.PirateChainConfig
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function

private const val STORE_RECEIPT_POLL_DELAY_MS = 1_500L
private const val STORE_RECEIPT_TIMEOUT_MS = 90_000L

internal fun formatStoreTokenAmount(
  rawAmount: BigInteger,
  decimals: Int = 6,
): String {
  if (rawAmount <= BigInteger.ZERO) return "0.00"
  val safeDecimals = decimals.coerceAtLeast(0)
  val divisor = BigDecimal.TEN.pow(safeDecimals)
  val value = rawAmount.toBigDecimal().divide(divisor, 2, RoundingMode.DOWN)
  return value.setScale(2, RoundingMode.DOWN).toPlainString()
}

internal fun tokenSymbolForAddress(address: String): String {
  return when {
    address.equals(PirateChainConfig.BASE_SEPOLIA_USDC, ignoreCase = true) -> "USD"
    address.equals(PirateChainConfig.STORY_STABLE_TOKEN, ignoreCase = true) -> "USD"
    else -> "Token"
  }
}

internal suspend fun readErc20BalanceRaw(
  address: String,
  token: String,
  rpcUrl: String = PirateChainConfig.STORY_AENEID_RPC_URL,
): BigInteger = withContext(Dispatchers.IO) {
  val function =
    Function(
      "balanceOf",
      listOf(Address(address)),
      emptyList(),
    )
  parseUint256(rpcEthCall(token, FunctionEncoder.encode(function), rpcUrl))
}

internal fun readErc20Allowance(
  token: String,
  owner: String,
  spender: String,
  rpcUrl: String = PirateChainConfig.STORY_AENEID_RPC_URL,
): BigInteger {
  val function =
    Function(
      "allowance",
      listOf(Address(owner), Address(spender)),
      emptyList(),
    )
  return parseUint256(rpcEthCall(token, FunctionEncoder.encode(function), rpcUrl))
}

internal suspend fun awaitReceipt(
  txHash: String,
  rpcUrl: String,
): Boolean = withContext(Dispatchers.IO) {
  val startedAt = System.currentTimeMillis()
  while (System.currentTimeMillis() - startedAt < STORE_RECEIPT_TIMEOUT_MS) {
    fetchReceiptOrNull(txHash, rpcUrl)?.let { return@withContext it }
    delay(STORE_RECEIPT_POLL_DELAY_MS)
  }
  throw IllegalStateException("Transaction receipt timed out: $txHash")
}

internal suspend fun awaitStoryReceipt(txHash: String): Boolean = awaitReceipt(txHash, PirateChainConfig.STORY_AENEID_RPC_URL)

internal suspend fun awaitBaseReceipt(txHash: String): Boolean = awaitReceipt(txHash, PirateChainConfig.BASE_SEPOLIA_RPC_URL)

internal fun parseUint256(resultHex: String): BigInteger {
  val clean = resultHex.removePrefix("0x").ifBlank { "0" }
  return clean.toBigIntegerOrNull(16) ?: BigInteger.ZERO
}

internal fun parseAddressWord(resultHex: String): String? {
  val clean = resultHex.removePrefix("0x").lowercase(Locale.US)
  if (clean.length < 64) return null
  val word = clean.takeLast(64)
  val raw = word.takeLast(40)
  if (raw.all { it == '0' }) return null
  return "0x$raw"
}

internal fun rpcEthCall(
  to: String,
  data: String,
  rpcUrl: String,
): String {
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

  val request =
    Request.Builder()
      .url(rpcUrl)
      .post(payload.toString().toRequestBody(premiumJsonType))
      .build()

  premiumHttpClient.newCall(request).execute().use { response ->
    if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
    val body = JSONObject(response.body?.string().orEmpty())
    val error = body.optJSONObject("error")
    if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
    return body.optString("result", "0x")
  }
}

internal fun storyEthCall(
  to: String,
  data: String,
): String = rpcEthCall(to, data, PirateChainConfig.STORY_AENEID_RPC_URL)

internal fun baseEthCall(
  to: String,
  data: String,
): String = rpcEthCall(to, data, PirateChainConfig.BASE_SEPOLIA_RPC_URL)

private fun fetchReceiptOrNull(
  txHash: String,
  rpcUrl: String,
): Boolean? {
  val payload =
    JSONObject()
      .put("jsonrpc", "2.0")
      .put("id", 1)
      .put("method", "eth_getTransactionReceipt")
      .put("params", JSONArray().put(txHash))

  val request =
    Request.Builder()
      .url(rpcUrl)
      .post(payload.toString().toRequestBody(premiumJsonType))
      .build()

  premiumHttpClient.newCall(request).execute().use { response ->
    if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
    val body = JSONObject(response.body?.string().orEmpty())
    val error = body.optJSONObject("error")
    if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
    val result = body.optJSONObject("result") ?: return null
    return result.optString("status", "0x0").trim().lowercase(Locale.US) == "0x1"
  }
}
