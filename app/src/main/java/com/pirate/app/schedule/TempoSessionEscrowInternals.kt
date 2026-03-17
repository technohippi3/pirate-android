package com.pirate.app.schedule

import android.util.Log
import com.pirate.app.tempo.P256Utils
import com.pirate.app.tempo.SessionKeyManager
import com.pirate.app.tempo.TempoClient
import com.pirate.app.tempo.TempoTransaction
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlinx.coroutines.Dispatchers
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

private const val TAG = "TempoSessionEscrowApi"
private const val RPC_URL = TempoClient.RPC_URL
internal const val ESCROW_ADDRESS = TempoClient.SESSION_ESCROW_V1

private const val GAS_LIMIT_BUFFER = 250_000L
private const val GAS_LIMIT_MAX = 3_000_000L
private const val EXPIRY_WINDOW_SEC = 25L
private val EXPIRING_NONCE_KEY = ByteArray(32) { 0xFF.toByte() }

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val httpClient = OkHttpClient()
private val rawPerUsd = BigDecimal("1000000")

internal suspend fun submitEscrowWriteTx(
  userAddress: String,
  sessionKey: SessionKeyManager.SessionKey,
  callData: String,
  minimumGasLimit: Long,
  opLabel: String,
): EscrowTxResult = withContext(Dispatchers.IO) {
  runCatching {
    val sender = normalizeAddress(userAddress)
      ?: throw IllegalStateException("Invalid signer address.")

    if (!SessionKeyManager.isValid(sessionKey, ownerAddress = sender)) {
      throw IllegalStateException("Missing valid Tempo session key. Please sign in again.")
    }

    val chainId = TempoClient.getChainId()
    if (chainId != TempoClient.CHAIN_ID) {
      throw IllegalStateException("Wrong chain connected: $chainId")
    }

    val gasLimit = withGasBuffer(
      estimated = estimateGas(from = sender, to = ESCROW_ADDRESS, data = callData),
      minimum = minimumGasLimit,
    )
    val fees = TempoClient.getSuggestedFees()

    fun buildTx(feeMode: TempoTransaction.FeeMode, txFees: TempoClient.Eip1559Fees): TempoTransaction.UnsignedTx {
      return TempoTransaction.UnsignedTx(
        nonceKeyBytes = EXPIRING_NONCE_KEY,
        nonce = 0L,
        validBeforeSec = nowSec() + EXPIRY_WINDOW_SEC,
        maxPriorityFeePerGas = txFees.maxPriorityFeePerGas,
        maxFeePerGas = txFees.maxFeePerGas,
        feeMode = feeMode,
        gasLimit = gasLimit,
        calls =
          listOf(
            TempoTransaction.Call(
              to = P256Utils.hexToBytes(ESCROW_ADDRESS),
              value = 0,
              input = P256Utils.hexToBytes(callData),
            ),
          ),
      )
    }

    suspend fun signTx(unsignedTx: TempoTransaction.UnsignedTx): String {
      val txHash = TempoTransaction.signatureHash(unsignedTx)
      val keychainSig =
        SessionKeyManager.signWithSessionKey(
          sessionKey = sessionKey,
          userAddress = sender,
          txHash = txHash,
        )
      return TempoTransaction.encodeSignedSessionKey(unsignedTx, keychainSig)
    }

    suspend fun submitRelay(): String {
      val unsignedTx = buildTx(TempoTransaction.FeeMode.RELAY_SPONSORED, fees)
      val signedTx = signTx(unsignedTx)
      return TempoClient.sendSponsoredRawTransaction(
        signedTxHex = signedTx,
        senderAddress = sender,
      )
    }

    suspend fun submitSelfPay(): String {
      runCatching { TempoClient.fundAddress(sender) }
      val selfFees = TempoClient.getSuggestedFees()
      val unsignedTx = buildTx(TempoTransaction.FeeMode.SELF, selfFees)
      val signedTx = signTx(unsignedTx)
      return TempoClient.sendRawTransaction(signedTx)
    }

    var usedSelfPayFallback = false
    val txHash =
      runCatching { submitRelay() }.getOrElse { relayErr ->
        usedSelfPayFallback = true
        Log.w(TAG, "$opLabel relay submit failed; trying self-pay fallback: ${relayErr.message}")
        runCatching { submitSelfPay() }.getOrElse { selfErr ->
          throw IllegalStateException(
            "$opLabel failed: relay=${relayErr.message}; self=${selfErr.message}",
            selfErr,
          )
        }
      }

    val receipt = TempoClient.waitForTransactionReceipt(
      txHash = txHash,
      timeoutMs = (EXPIRY_WINDOW_SEC + 30L) * 1000L,
    )
    if (!receipt.isSuccess) {
      throw IllegalStateException("$opLabel reverted on-chain: ${receipt.txHash}")
    }

    Log.d(TAG, "$opLabel success mode=${if (usedSelfPayFallback) "self" else "relay"} tx=$txHash")
    EscrowTxResult(
      success = true,
      txHash = txHash,
      usedSelfPayFallback = usedSelfPayFallback,
    )
  }.getOrElse { err ->
    EscrowTxResult(success = false, error = err.message ?: "$opLabel failed")
  }
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

internal data class EscrowSlotInputCall(
  val startTimeSec: Long,
  val durationMins: Int,
  val graceMins: Int,
  val minOverlapMins: Int,
  val cancelCutoffMins: Int,
  val priceRaw: BigInteger,
)

internal fun encodeCreateSlotsWithPricesCall(inputs: List<EscrowSlotInputCall>): String {
  require(inputs.isNotEmpty()) { "inputs must not be empty" }
  val selector = functionSelector("createSlotsWithPrices((uint48,uint32,uint32,uint32,uint32,uint256)[])")

  val headOffset = encodeUintWord(BigInteger.valueOf(32L))
  val arrayLength = encodeUintWord(BigInteger.valueOf(inputs.size.toLong()))
  val tupleWords =
    inputs.joinToString(separator = "") { input ->
      buildString {
        append(encodeUintWord(BigInteger.valueOf(input.startTimeSec)))
        append(encodeUintWord(BigInteger.valueOf(input.durationMins.toLong())))
        append(encodeUintWord(BigInteger.valueOf(input.graceMins.toLong())))
        append(encodeUintWord(BigInteger.valueOf(input.minOverlapMins.toLong())))
        append(encodeUintWord(BigInteger.valueOf(input.cancelCutoffMins.toLong())))
        append(encodeUintWord(input.priceRaw))
      }
    }

  return "0x$selector$headOffset$arrayLength$tupleWords"
}

internal fun encodeUintArrayFunctionCall(
  signature: String,
  values: List<BigInteger>,
): String {
  require(values.isNotEmpty()) { "values must not be empty" }
  val selector = functionSelector(signature)
  val headOffset = encodeUintWord(BigInteger.valueOf(32L))
  val arrayLength = encodeUintWord(BigInteger.valueOf(values.size.toLong()))
  val words = values.joinToString(separator = "") { value -> encodeUintWord(value) }
  return "0x$selector$headOffset$arrayLength$words"
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
  return P256Utils.bytesToHex(digest).take(8)
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
      .put(
        "method",
        "eth_call",
      ).put(
        "params",
        JSONArray()
          .put(
            JSONObject()
              .put("to", ESCROW_ADDRESS)
              .put("data", data),
          ).put("latest"),
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

private fun estimateGas(
  from: String,
  to: String,
  data: String,
): Long {
  val txObj =
    JSONObject()
      .put("from", from)
      .put("to", to)
      .put("value", "0x0")
      .put("data", data)

  val payload =
    JSONObject()
      .put("jsonrpc", "2.0")
      .put("id", 1)
      .put("method", "eth_estimateGas")
      .put("params", JSONArray().put(txObj).put("latest"))

  val request =
    Request.Builder()
      .url(RPC_URL)
      .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
      .build()

  return httpClient.newCall(request).execute().use { response ->
    if (!response.isSuccessful) {
      throw IllegalStateException("RPC failed: ${response.code}")
    }
    val body = JSONObject(response.body?.string().orEmpty())
    val error = body.optJSONObject("error")
    if (error != null) {
      throw IllegalStateException(error.optString("message", error.toString()))
    }
    val resultHex = body.optString("result", "").trim()
    if (!resultHex.startsWith("0x")) {
      throw IllegalStateException("RPC eth_estimateGas missing result")
    }
    val clean = resultHex.removePrefix("0x").ifBlank { "0" }
    clean.toLongOrNull(16) ?: throw IllegalStateException("Invalid eth_estimateGas result: $resultHex")
  }
}

private fun withGasBuffer(
  estimated: Long,
  minimum: Long,
): Long {
  val buffered =
    if (Long.MAX_VALUE - estimated < GAS_LIMIT_BUFFER) Long.MAX_VALUE else estimated + GAS_LIMIT_BUFFER
  return maxOf(buffered, minimum).coerceAtMost(GAS_LIMIT_MAX)
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

private fun BigInteger.toLongSafe(): Long {
  return if (this > BigInteger.valueOf(Long.MAX_VALUE)) Long.MAX_VALUE else this.toLong()
}

private fun BigInteger.toIntSafe(): Int {
  return if (this > BigInteger.valueOf(Int.MAX_VALUE.toLong())) Int.MAX_VALUE else this.toInt()
}
