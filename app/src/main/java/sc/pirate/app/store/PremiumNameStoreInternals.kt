package sc.pirate.app.store

import sc.pirate.app.PirateChainConfig
import sc.pirate.app.music.SongPublishService
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.jcajce.provider.digest.Keccak
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256

private val API_CORE_URL = SongPublishService.API_CORE_URL
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
  val paymentToken: String,
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
    val requiredPrice =
      quoteObj.optString("price", "").trim().toBigIntegerOrNull()
        ?: throw IllegalStateException("Permit response missing quote price.")
    val paymentToken = quoteObj.optString("paymentToken", PirateChainConfig.BASE_SEPOLIA_USDC).trim()
    val duration =
      quoteObj.optString("durationSeconds", "").trim().toLongOrNull()
        ?: quoteObj.optLong("durationSeconds", -1L).takeIf { it > 0L }
        ?: throw IllegalStateException("Permit response missing quote duration.")
    val policy = body.optString("policy", "UNKNOWN")
    if (!txTo.startsWith("0x") || txTo.length != 42 || !txData.startsWith("0x")) {
      throw IllegalStateException("Permit response returned invalid transaction payload.")
    }
    if (!paymentToken.startsWith("0x") || paymentToken.length != 42) {
      throw IllegalStateException("Permit response missing payment token.")
    }

    return PermitTxPayload(
      txTo = txTo,
      txData = txData,
      paymentToken = paymentToken,
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

internal fun encodeApproveCall(
  spender: String,
  value: BigInteger,
): String {
  val function =
    Function(
      "approve",
      listOf(Address(spender), Uint256(value.max(BigInteger.ZERO))),
      emptyList(),
    )
  return FunctionEncoder.encode(function)
}
