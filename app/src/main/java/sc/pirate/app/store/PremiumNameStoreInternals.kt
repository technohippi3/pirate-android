package sc.pirate.app.store

import androidx.fragment.app.FragmentActivity
import sc.pirate.app.music.SongPublishService
import sc.pirate.app.tempo.P256Utils
import sc.pirate.app.tempo.SessionKeyManager
import sc.pirate.app.tempo.TempoClient
import sc.pirate.app.tempo.TempoPasskeyManager
import sc.pirate.app.tempo.TempoTransaction
import java.math.BigInteger
import java.nio.charset.StandardCharsets
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
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256

private val API_CORE_URL = SongPublishService.API_CORE_URL
private const val GAS_LIMIT_BUFFER = 300_000L
private const val GAS_LIMIT_MAX = 3_500_000L
private const val POW_MAX_ATTEMPTS = 5_000_000

internal val premiumJsonType = "application/json; charset=utf-8".toMediaType()
internal val premiumHttpClient = OkHttpClient()

internal data class PowChallenge(
  val challengeId: String,
  val challenge: String,
  val difficulty: Int,
  val expiresAt: Long,
)

internal data class PermitTxPayload(
  val txTo: String,
  val txData: String,
  val requiredPrice: BigInteger,
  val durationSeconds: Long,
  val policy: String,
)

internal fun mapPermitErrorMessage(message: String): String {
  val normalized = message.lowercase()
  if (
    normalized.contains("self verification required") ||
      normalized.contains("self nullifier not available") ||
      normalized.contains("older verification record without a short-name credential")
  ) {
    return "Short names (5 chars or less) require one-time Self verification. Open Verify Identity, complete it, then try again."
  }
  return message
}

internal fun requestPowChallenge(
  label: String,
  tld: String,
  wallet: String,
): PowChallenge {
  val payload =
    JSONObject()
      .put("label", label)
      .put("tld", tld)
      .put("wallet", wallet)
  val req =
    Request.Builder()
      .url("$API_CORE_URL/api/names/challenge")
      .post(payload.toString().toRequestBody(premiumJsonType))
      .build()

  premiumHttpClient.newCall(req).execute().use { response ->
    val bodyText = response.body?.string().orEmpty()
    val body = runCatching { JSONObject(bodyText) }.getOrElse { JSONObject() }
    if (!response.isSuccessful) {
      val msg = body.optString("error", "Failed to create PoW challenge.")
      throw IllegalStateException(msg)
    }

    val challengeId = body.optString("challengeId", "")
    val challenge = body.optString("challenge", "")
    val difficulty = body.optInt("difficulty", -1)
    val expiresAt =
      body.optString("expiresAt", "").trim().toLongOrNull()
        ?: body.optLong("expiresAt", -1L).takeIf { it > 0L }
        ?: -1L
    if (challengeId.isBlank() || challenge.isBlank() || difficulty < 0 || expiresAt <= 0L) {
      throw IllegalStateException("Invalid PoW challenge response.")
    }
    return PowChallenge(
      challengeId = challengeId,
      challenge = challenge,
      difficulty = difficulty,
      expiresAt = expiresAt,
    )
  }
}

internal fun requestPermit(
  label: String,
  tld: String,
  wallet: String,
  recipient: String,
  durationSeconds: Long,
  maxPrice: BigInteger?,
  challenge: PowChallenge?,
): PermitTxPayload {
  val payload =
    JSONObject()
      .put("label", label)
      .put("tld", tld)
      .put("wallet", wallet)
      .put("recipient", recipient)
      .put("durationSeconds", durationSeconds)
  if (maxPrice != null && maxPrice >= BigInteger.ZERO) {
    payload.put("maxPrice", maxPrice.toString())
  }

  if (challenge != null) {
    val powNonce = solvePow(challenge.challenge, challenge.difficulty, challenge.expiresAt)
      ?: throw IllegalStateException("Unable to solve PoW challenge before expiry. Try again.")
    payload.put("challengeId", challenge.challengeId)
    payload.put("powNonce", powNonce)
  }

  val req =
    Request.Builder()
      .url("$API_CORE_URL/api/names/permit")
      .post(payload.toString().toRequestBody(premiumJsonType))
      .build()

  premiumHttpClient.newCall(req).execute().use { response ->
    val bodyText = response.body?.string().orEmpty()
    val body = runCatching { JSONObject(bodyText) }.getOrElse { JSONObject() }
    if (!response.isSuccessful) {
      val msg = body.optString("error", "Failed to fetch name permit.")
      throw IllegalStateException(mapPermitErrorMessage(msg))
    }

    val txObj = body.optJSONObject("tx") ?: throw IllegalStateException("Permit response missing tx payload.")
    val quoteObj = body.optJSONObject("quote") ?: throw IllegalStateException("Permit response missing quote payload.")
    val txTo = txObj.optString("to", "").trim()
    val txData = txObj.optString("data", "").trim()
    val requiredPrice = quoteObj.optString("price", "").trim().toBigIntegerOrNull()
      ?: throw IllegalStateException("Permit response missing quote price.")
    val duration =
      quoteObj.optString("durationSeconds", "").trim().toLongOrNull()
        ?: quoteObj.optLong("durationSeconds", -1L).takeIf { it > 0L }
        ?: throw IllegalStateException("Permit response missing quote duration.")
    val policy = body.optString("policy", "UNKNOWN")
    if (!txTo.startsWith("0x") || txTo.length != 42 || !txData.startsWith("0x")) {
      throw IllegalStateException("Permit response returned invalid transaction payload.")
    }

    return PermitTxPayload(
      txTo = txTo,
      txData = txData,
      requiredPrice = requiredPrice,
      durationSeconds = duration,
      policy = policy,
    )
  }
}

private fun solvePow(challenge: String, difficulty: Int, expiresAt: Long): String? {
  val solveDeadline = (expiresAt - 2L).coerceAtLeast(0L)
  if ((System.currentTimeMillis() / 1000L) >= solveDeadline) return null

  val requiredPrefix = "0".repeat(difficulty.coerceAtLeast(0))
  for (attempt in 0 until POW_MAX_ATTEMPTS) {
    if ((attempt and 2047) == 0 && (System.currentTimeMillis() / 1000L) >= solveDeadline) {
      return null
    }
    val nonce = attempt.toString()
    val digest = keccak256Hex("$challenge:$nonce")
    if (digest.startsWith(requiredPrefix)) return nonce
  }
  return null
}

private fun keccak256Hex(input: String): String {
  val digest = Keccak.Digest256()
  val bytes = input.toByteArray(StandardCharsets.UTF_8)
  digest.update(bytes, 0, bytes.size)
  return digest.digest().joinToString(separator = "") { b ->
    "%02x".format(b)
  }
}

internal fun encodeApproveCall(spender: String, value: BigInteger): String {
  val function =
    Function(
      "approve",
      listOf(Address(spender), Uint256(value.max(BigInteger.ZERO))),
      emptyList(),
    )
  return FunctionEncoder.encode(function)
}

internal suspend fun submitCallWithFallback(
  activity: FragmentActivity,
  account: TempoPasskeyManager.PasskeyAccount,
  to: String,
  callData: String,
  minimumGasLimit: Long,
  rpId: String,
  sessionKey: SessionKeyManager.SessionKey?,
  preferSelfPay: Boolean,
): String {
  val usableSessionKey = sessionKey?.takeIf { SessionKeyManager.isValid(it, ownerAddress = account.address) }
  val nonce = withContext(Dispatchers.IO) { TempoClient.getNonce(account.address) }
  val fees = withContext(Dispatchers.IO) { TempoClient.getSuggestedFees() }
  val gasLimit =
    withContext(Dispatchers.IO) {
      val estimated = estimateGas(from = account.address, to = to, valueWei = BigInteger.ZERO, data = callData)
      withGasBuffer(estimated = estimated, minimum = minimumGasLimit)
    }

  fun buildTx(
    feeMode: TempoTransaction.FeeMode,
    txFees: TempoClient.Eip1559Fees,
  ): TempoTransaction.UnsignedTx =
    TempoTransaction.UnsignedTx(
      nonce = nonce,
      maxPriorityFeePerGas = txFees.maxPriorityFeePerGas,
      maxFeePerGas = txFees.maxFeePerGas,
      feeMode = feeMode,
      gasLimit = gasLimit,
      calls =
        listOf(
          TempoTransaction.Call(
            to = P256Utils.hexToBytes(to),
            value = 0,
            input = P256Utils.hexToBytes(callData),
          ),
        ),
    )

  suspend fun signTx(unsignedTx: TempoTransaction.UnsignedTx): String {
    val sigHash = TempoTransaction.signatureHash(unsignedTx)
    return if (usableSessionKey != null) {
      val keychainSig = SessionKeyManager.signWithSessionKey(
        sessionKey = usableSessionKey,
        userAddress = account.address,
        txHash = sigHash,
      )
      TempoTransaction.encodeSignedSessionKey(unsignedTx, keychainSig)
    } else {
      val assertion =
        TempoPasskeyManager.sign(
          activity = activity,
          challenge = sigHash,
          account = account,
          rpId = rpId,
        )
      TempoTransaction.encodeSignedWebAuthn(unsignedTx, assertion)
    }
  }

  suspend fun submitRelay(): String {
    val tx = buildTx(feeMode = TempoTransaction.FeeMode.RELAY_SPONSORED, txFees = fees)
    val signed = signTx(tx)
    return withContext(Dispatchers.IO) {
      TempoClient.sendSponsoredRawTransaction(
        signedTxHex = signed,
        senderAddress = account.address,
      )
    }
  }

  suspend fun submitSelf(): String {
    withContext(Dispatchers.IO) { runCatching { TempoClient.fundAddress(account.address) } }
    val selfFees = withContext(Dispatchers.IO) { TempoClient.getSuggestedFees() }
    val tx = buildTx(feeMode = TempoTransaction.FeeMode.SELF, txFees = selfFees)
    val signed = signTx(tx)
    return withContext(Dispatchers.IO) { TempoClient.sendRawTransaction(signed) }
  }

  val txHash =
    if (preferSelfPay) {
      runCatching { submitSelf() }.getOrElse { selfErr ->
        runCatching { submitRelay() }.getOrElse { relayErr ->
          throw IllegalStateException(
            "Transaction submit failed: self=${selfErr.message}; relay=${relayErr.message}",
            relayErr,
          )
        }
      }
    } else {
      runCatching { submitRelay() }.getOrElse { relayErr ->
        runCatching { submitSelf() }.getOrElse { selfErr ->
          throw IllegalStateException(
            "Transaction submit failed: relay=${relayErr.message}; self=${selfErr.message}",
            selfErr,
          )
        }
      }
    }

  val receipt = withContext(Dispatchers.IO) { TempoClient.waitForTransactionReceipt(txHash) }
  if (!receipt.isSuccess) {
    throw IllegalStateException("Transaction reverted on-chain: $txHash")
  }
  return txHash
}

private fun estimateGas(
  from: String,
  to: String,
  valueWei: BigInteger,
  data: String,
): Long {
  val txObj =
    JSONObject()
      .put("from", from)
      .put("to", to)
      .put("value", "0x${valueWei.toString(16)}")
      .put("data", data)
  val payload =
    JSONObject()
      .put("jsonrpc", "2.0")
      .put("id", 1)
      .put("method", "eth_estimateGas")
      .put("params", JSONArray().put(txObj).put("latest"))

  val req =
    Request.Builder()
      .url(TempoClient.RPC_URL)
      .post(payload.toString().toRequestBody(premiumJsonType))
      .build()

  premiumHttpClient.newCall(req).execute().use { response ->
    if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
    val body = JSONObject(response.body?.string().orEmpty())
    val error = body.optJSONObject("error")
    if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
    val resultHex = body.optString("result", "").trim()
    if (!resultHex.startsWith("0x")) {
      throw IllegalStateException("RPC eth_estimateGas missing result")
    }
    val clean = resultHex.removePrefix("0x").ifBlank { "0" }
    return clean.toLongOrNull(16)
      ?: throw IllegalStateException("Invalid eth_estimateGas result: $resultHex")
  }
}

private fun withGasBuffer(estimated: Long, minimum: Long): Long {
  val buffered =
    if (Long.MAX_VALUE - estimated < GAS_LIMIT_BUFFER) Long.MAX_VALUE else estimated + GAS_LIMIT_BUFFER
  return maxOf(buffered, minimum).coerceAtMost(GAS_LIMIT_MAX)
}
