package sc.pirate.app.learn

import android.content.Context
import sc.pirate.app.PirateChainConfig
import sc.pirate.app.auth.privy.PrivyRelayClient
import sc.pirate.app.crypto.P256Utils
import java.math.BigInteger
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint64

internal data class StreakClaimInput(
  val studySetKey: String,
  val dayUtc: Long,
  val nonce: Long,
  val expirySec: Long,
  val signatureHex: String,
)

internal data class StreakClaimTxResult(
  val success: Boolean,
  val txHashes: List<String> = emptyList(),
  val submittedCount: Int = 0,
  val error: String? = null,
)

internal object StreakClaimApi {
  private const val RECEIPT_POLL_DELAY_MS = 1_500L
  private const val RECEIPT_TIMEOUT_MS = 30_000L
  private val ADDRESS_REGEX = Regex("^0x[a-fA-F0-9]{40}$")
  private val BYTES32_REGEX = Regex("^0x[a-fA-F0-9]{64}$")
  private val SIGNATURE_REGEX = Regex("^0x[a-fA-F0-9]{130}$")
  private val jsonType = "application/json".toMediaType()
  private val client = OkHttpClient()

  fun isConfigured(): Boolean = streakClaimContractOrNull() != null

  suspend fun submitClaims(
    context: Context,
    ownerAddress: String,
    claims: List<StreakClaimInput>,
  ): StreakClaimTxResult {
    if (claims.isEmpty()) {
      return StreakClaimTxResult(success = true, submittedCount = 0)
    }

    val streakClaimContract = streakClaimContractOrNull() ?: return StreakClaimTxResult(
      success = false,
      error = "Streak claim contract is not configured",
    )
    val sender = runCatching { normalizeAddress(ownerAddress) }.getOrElse { err ->
      return StreakClaimTxResult(success = false, error = err.message ?: "Invalid sender address")
    }
    val normalizedClaims = runCatching { claims.mapIndexed { index, claim -> normalizeClaim(index, claim) } }.getOrElse { err ->
      return StreakClaimTxResult(success = false, error = err.message ?: "Invalid streak claim payload")
    }

    val txHashes = ArrayList<String>()
    var submittedCount = 0
    for (claim in normalizedClaims) {
      val txHash = runCatching {
        submitSingleClaim(context = context, sender = sender, contract = streakClaimContract, claim = claim)
      }.getOrElse { err ->
        return StreakClaimTxResult(
          success = false,
          txHashes = txHashes,
          submittedCount = submittedCount,
          error = err.message ?: "Streak claim tx failed",
        )
      }
      txHashes.add(txHash)
      submittedCount += 1
    }

    return StreakClaimTxResult(success = true, txHashes = txHashes, submittedCount = submittedCount)
  }

  private suspend fun submitSingleClaim(
    context: Context,
    sender: String,
    contract: String,
    claim: StreakClaimInput,
  ): String {
    val callData = encodeCalldata(sender, claim)
    val txHash =
      PrivyRelayClient.submitContractCall(
        context = context,
        chainId = PirateChainConfig.STORY_AENEID_CHAIN_ID,
        to = contract,
        data = callData,
        intentType = "pirate.learn.claim-streak",
        intentArgs = JSONObject().put("studySetKey", claim.studySetKey).put("dayUtc", claim.dayUtc),
      )
    val receipt = waitForReceipt(txHash)
    if (!receipt.isSuccess) {
      throw IllegalStateException("Streak claim tx reverted on-chain: $txHash")
    }
    return txHash
  }

  private fun encodeCalldata(
    userAddress: String,
    claim: StreakClaimInput,
  ): String {
    val signatureBytes = P256Utils.hexToBytes(claim.signatureHex)
    val function =
      Function(
        "claimStreakDay",
        listOf(
          Address(userAddress),
          Bytes32(P256Utils.hexToBytes(claim.studySetKey)),
          Uint64(BigInteger.valueOf(claim.dayUtc)),
          Uint64(BigInteger.valueOf(claim.nonce)),
          Uint64(BigInteger.valueOf(claim.expirySec)),
          DynamicBytes(signatureBytes),
        ),
        emptyList(),
      )
    return FunctionEncoder.encode(function)
  }

  private fun normalizeClaim(index: Int, claim: StreakClaimInput): StreakClaimInput {
    val position = index + 1
    val studySetKey = normalizeBytes32(claim.studySetKey, "Claim $position: studySetKey")
    if (claim.dayUtc < 0L) throw IllegalArgumentException("Claim $position: dayUtc must be >= 0")
    if (claim.nonce < 0L) throw IllegalArgumentException("Claim $position: nonce must be >= 0")
    if (claim.expirySec <= 0L) throw IllegalArgumentException("Claim $position: expirySec must be > 0")
    val signatureHex = normalizeSignatureHex(claim.signatureHex, "Claim $position: signatureHex")
    return claim.copy(studySetKey = studySetKey, signatureHex = signatureHex)
  }

  private fun streakClaimContractOrNull(): String? {
    val configured = PirateChainConfig.STORY_STREAK_CLAIM_CONTRACT.trim()
    if (!ADDRESS_REGEX.matches(configured)) return null
    if (configured.equals("0x0000000000000000000000000000000000000000", ignoreCase = true)) return null
    return "0x${configured.removePrefix("0x").removePrefix("0X")}"
  }

  private fun normalizeAddress(raw: String): String {
    val trimmed = raw.trim()
    val prefixed = if (trimmed.startsWith("0x", ignoreCase = true)) trimmed else "0x$trimmed"
    require(ADDRESS_REGEX.matches(prefixed)) { "Invalid sender address" }
    return "0x${prefixed.removePrefix("0x").removePrefix("0X")}"
  }

  private fun normalizeBytes32(raw: String, fieldName: String): String {
    val trimmed = raw.trim().removePrefix("0x").removePrefix("0X").lowercase(Locale.US)
    require(BYTES32_REGEX.matches("0x$trimmed")) { "$fieldName must be bytes32" }
    return "0x$trimmed"
  }

  private fun normalizeSignatureHex(raw: String, fieldName: String): String {
    val trimmed = raw.trim()
    val prefixed = if (trimmed.startsWith("0x", ignoreCase = true)) trimmed else "0x$trimmed"
    require(SIGNATURE_REGEX.matches(prefixed)) { "$fieldName must be a 65-byte hex signature" }
    return "0x${prefixed.removePrefix("0x").removePrefix("0X")}"
  }

  private data class TxReceipt(val isSuccess: Boolean)

  private suspend fun waitForReceipt(txHash: String): TxReceipt = withContext(Dispatchers.IO) {
    val startedAt = System.currentTimeMillis()
    while (System.currentTimeMillis() - startedAt < RECEIPT_TIMEOUT_MS) {
      fetchReceiptOrNull(txHash)?.let { return@withContext it }
      delay(RECEIPT_POLL_DELAY_MS)
    }
    throw IllegalStateException("Streak claim receipt timed out: $txHash")
  }

  private fun fetchReceiptOrNull(txHash: String): TxReceipt? {
    val payload =
      JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", "eth_getTransactionReceipt")
        .put("params", JSONArray().put(txHash))
    val request = Request.Builder().url(PirateChainConfig.STORY_AENEID_RPC_URL).post(payload.toString().toRequestBody(jsonType)).build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IllegalStateException("RPC failed: ${response.code}")
      val body = JSONObject(response.body?.string().orEmpty())
      val error = body.optJSONObject("error")
      if (error != null) throw IllegalStateException(error.optString("message", error.toString()))
      val result = body.optJSONObject("result") ?: return null
      val status = result.optString("status", "0x0").trim().lowercase(Locale.US)
      return TxReceipt(isSuccess = status == "0x1")
    }
  }
}
