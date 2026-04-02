package sc.pirate.app.schedule

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.jcajce.provider.digest.Keccak
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256
import sc.pirate.app.PirateChainConfig
import sc.pirate.app.auth.privy.PrivyRelayClient
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

private const val TAG = "SessionEscrowApi"
private const val RPC_URL = PirateChainConfig.BASE_SEPOLIA_RPC_URL
internal const val ESCROW_ADDRESS = PirateChainConfig.BASE_SESSION_ESCROW_V2
private const val RECEIPT_POLL_DELAY_MS = 1_500L
private const val RECEIPT_TIMEOUT_MS = 90_000L

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val httpClient = OkHttpClient()
private val rawPerUsd = BigDecimal("1000000")

internal suspend fun submitEscrowWriteTx(
  context: Context,
  userAddress: String,
  callData: String,
  intentType: String,
  intentArgs: JSONObject? = null,
  opLabel: String,
): EscrowTxResult = withContext(Dispatchers.IO) {
  runCatching {
    val sender = normalizeAddress(userAddress)
      ?: throw IllegalStateException("Invalid signer address.")

    val txHash =
      PrivyRelayClient.submitContractCall(
        context = context,
        chainId = PirateChainConfig.BASE_SEPOLIA_CHAIN_ID,
        to = ESCROW_ADDRESS,
        data = callData,
        intentType = intentType,
        intentArgs = intentArgs,
      )

    val succeeded = awaitReceipt(txHash)
    check(succeeded) { "$opLabel reverted on-chain: $txHash" }

    Log.d(TAG, "$opLabel success sender=$sender tx=$txHash")
    EscrowTxResult(success = true, txHash = txHash)
  }.getOrElse { err ->
    EscrowTxResult(success = false, error = err.message ?: "$opLabel failed")
  }
}

private suspend fun awaitReceipt(txHash: String): Boolean = withContext(Dispatchers.IO) {
  val startedAt = System.currentTimeMillis()
  while (System.currentTimeMillis() - startedAt < RECEIPT_TIMEOUT_MS) {
    fetchReceiptOrNull(txHash)?.let { return@withContext it }
    delay(RECEIPT_POLL_DELAY_MS)
  }
  throw IllegalStateException("Transaction receipt timed out: $txHash")
}

internal fun getNextBookingId(): Long? {
  val data = encodeNoArgs("nextBookingId")
  val result = ethCall(data) ?: return null
  val words = splitWords(result)
  if (words.isEmpty()) return null
  return parseWordUint(words[0]).toLongSafe()
}

internal fun getNextSlotId(): Long? {
  val data = encodeNoArgs("nextSlotId")
  val result = ethCall(data) ?: return null
  val words = splitWords(result)
  if (words.isEmpty()) return null
  return parseWordUint(words[0]).toLongSafe()
}

internal fun getBooking(bookingId: Long): EscrowBooking? {
  val data = encodeSingleUintArg("getBooking", bookingId)
  val result = ethCall(data) ?: return null
  val words = splitWords(result)
  if (words.size < 11) return null

  val statusCode = parseWordUint(words[3]).toIntSafe()
  val status = EscrowBookingStatus.entries.firstOrNull { it.code == statusCode } ?: EscrowBookingStatus.None

  return EscrowBooking(
    id = bookingId,
    slotId = parseWordUint(words[0]).toLongSafe(),
    guest = parseWordAddress(words[1]),
    amountRaw = parseWordUint(words[2]),
    status = status,
  )
}

internal fun getSlot(slotId: Long): EscrowSlot? {
  val data = encodeSingleUintArg("getSlot", slotId)
  val result = ethCall(data) ?: return null
  val words = splitWords(result)
  if (words.size < 8) return null

  val statusCode = parseWordUint(words[7]).toIntSafe()
  val status = EscrowSlotStatus.entries.firstOrNull { it.code == statusCode } ?: EscrowSlotStatus.Open

  return EscrowSlot(
    id = slotId,
    host = parseWordAddress(words[0]),
    startTimeSec = parseWordUint(words[1]).toLongSafe(),
    durationMins = parseWordUint(words[2]).toIntSafe(),
    priceRaw = parseWordUint(words[3]),
    status = status,
  )
}

internal fun encodeFunctionCall(signature: String, uintArgs: List<BigInteger>): String {
  val selector = functionSelector(signature)
  val encodedArgs = uintArgs.joinToString(separator = "") { arg -> encodeUintWord(arg) }
  return "0x$selector$encodedArgs"
}

internal fun encodeAddressWord(address: String): String {
  val normalized = normalizeAddress(address) ?: throw IllegalArgumentException("invalid address")
  return normalized.removePrefix("0x").padStart(64, '0')
}

internal fun normalizeAddress(address: String?): String? {
  val trimmed = address?.trim().orEmpty()
  if (!trimmed.startsWith("0x", ignoreCase = true) || trimmed.length != 42) return null
  return "0x${trimmed.substring(2).lowercase()}"
}

internal fun parseUsdToRaw(priceUsd: String): BigInteger? {
  val normalized = priceUsd.trim()
  if (normalized.isBlank()) return null
  val decimal = runCatching { BigDecimal(normalized) }.getOrNull() ?: return null
  if (decimal <= BigDecimal.ZERO) return null
  return decimal.multiply(rawPerUsd).setScale(0, RoundingMode.DOWN).toBigInteger().takeIf { it > BigInteger.ZERO }
}

internal fun formatTokenAmount(raw: BigInteger): String {
  val usd = BigDecimal(raw).divide(rawPerUsd, 6, RoundingMode.DOWN).stripTrailingZeros()
  return usd.toPlainString()
}

internal fun nowSec(): Long = System.currentTimeMillis() / 1000L

private fun encodeNoArgs(functionName: String): String {
  return FunctionEncoder.encode(Function(functionName, emptyList(), emptyList()))
}

private fun encodeSingleUintArg(functionName: String, value: Long): String {
  return FunctionEncoder.encode(
    Function(
      functionName,
      listOf(Uint256(BigInteger.valueOf(value))),
      emptyList(),
    ),
  )
}

internal fun functionSelector(signature: String): String {
  val digest = Keccak.Digest256().digest(signature.toByteArray(Charsets.UTF_8))
  return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(8)
}

private fun encodeUintWord(value: BigInteger): String {
  require(value >= BigInteger.ZERO) { "uint must be non-negative" }
  return value.toString(16).padStart(64, '0')
}

internal fun ethCall(data: String): String? {
  val payload =
    JSONObject()
      .put("jsonrpc", "2.0")
      .put("id", 1)
      .put("method", "eth_call")
      .put(
        "params",
        JSONArray()
          .put(
            JSONObject()
              .put("to", ESCROW_ADDRESS)
              .put("data", data),
          )
          .put("latest"),
      ).toString()
      .toRequestBody(JSON_MEDIA_TYPE)

  val request =
    Request.Builder()
      .url(RPC_URL)
      .post(payload)
      .build()

  return runCatching {
    httpClient.newCall(request).execute().use { response ->
      if (!response.isSuccessful) return null
      val body = response.body?.string().orEmpty()
      val json = JSONObject(body)
      if (json.has("error")) return null
      val result = json.optString("result", "")
      if (!result.startsWith("0x") || result.length <= 2) return null
      result
    }
  }.getOrNull()
}

internal fun splitWords(rawHex: String): List<String> {
  val clean = rawHex.removePrefix("0x")
  if (clean.length < 64 || clean.length % 64 != 0) return emptyList()
  return clean.chunked(64)
}

private fun parseWordAddress(word: String): String {
  return "0x${word.takeLast(40)}".lowercase()
}

internal fun parseWordUint(word: String): BigInteger {
  return runCatching { BigInteger(word, 16) }.getOrDefault(BigInteger.ZERO)
}

private fun fetchReceiptOrNull(txHash: String): Boolean? {
  val payload =
    JSONObject()
      .put("jsonrpc", "2.0")
      .put("id", 1)
      .put("method", "eth_getTransactionReceipt")
      .put("params", JSONArray().put(txHash))

  val request =
    Request.Builder()
      .url(RPC_URL)
      .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
      .build()

  httpClient.newCall(request).execute().use { response ->
    if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
    val body = JSONObject(response.body?.string().orEmpty())
    val error = body.optJSONObject("error")
    if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
    val result = body.optJSONObject("result") ?: return null
    return result.optString("status", "0x0").trim().lowercase() == "0x1"
  }
}

private fun BigInteger.toLongSafe(): Long {
  return if (this > BigInteger.valueOf(Long.MAX_VALUE)) Long.MAX_VALUE else this.toLong()
}

private fun BigInteger.toIntSafe(): Int {
  return if (this > BigInteger.valueOf(Int.MAX_VALUE.toLong())) Int.MAX_VALUE else this.toInt()
}
