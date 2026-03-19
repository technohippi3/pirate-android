package sc.pirate.app.learn

import androidx.fragment.app.FragmentActivity
import sc.pirate.app.tempo.P256Utils
import sc.pirate.app.tempo.SessionKeyManager
import sc.pirate.app.tempo.TempoClient
import sc.pirate.app.tempo.TempoPasskeyManager
import sc.pirate.app.tempo.TempoTransaction
import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
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

internal data class TempoStreakClaimInput(
  val studySetKey: String,
  val dayUtc: Long,
  val nonce: Long,
  val expirySec: Long,
  val signatureHex: String,
)

internal data class TempoStreakClaimTxResult(
  val success: Boolean,
  val txHashes: List<String> = emptyList(),
  val submittedCount: Int = 0,
  val error: String? = null,
)

internal object TempoStreakClaimApi {
  private const val MIN_GAS_LIMIT = 220_000L
  private const val GAS_LIMIT_BUFFER = 100_000L
  private val ADDRESS_REGEX = Regex("^0x[a-fA-F0-9]{40}$")
  private val BYTES32_REGEX = Regex("^0x[a-fA-F0-9]{64}$")
  private val SIGNATURE_REGEX = Regex("^0x[a-fA-F0-9]{130}$")

  private val jsonType = "application/json".toMediaType()
  private val client = OkHttpClient()

  fun isConfigured(): Boolean = streakClaimContractOrNull() != null

  suspend fun submitClaims(
    activity: FragmentActivity,
    account: TempoPasskeyManager.PasskeyAccount,
    claims: List<TempoStreakClaimInput>,
    rpId: String = account.rpId,
    sessionKey: SessionKeyManager.SessionKey? = null,
  ): TempoStreakClaimTxResult {
    if (claims.isEmpty()) {
      return TempoStreakClaimTxResult(success = true, submittedCount = 0)
    }

    val streakClaimContract = streakClaimContractOrNull()
      ?: return TempoStreakClaimTxResult(
        success = false,
        error = "Streak claim contract is not configured",
      )

    val sender = runCatching { normalizeAddress(account.address) }
      .getOrElse { err ->
        return TempoStreakClaimTxResult(
          success = false,
          error = err.message ?: "Invalid sender address",
        )
      }
    val normalizedClaims =
      runCatching { claims.mapIndexed { index, claim -> normalizeClaim(index, claim) } }
        .getOrElse { err ->
          return TempoStreakClaimTxResult(
            success = false,
            error = err.message ?: "Invalid streak claim payload",
          )
        }

    val usableSessionKey = sessionKey?.takeIf { SessionKeyManager.isValid(it, ownerAddress = sender) }
    val chainId = withContext(Dispatchers.IO) { TempoClient.getChainId() }
    if (chainId != TempoClient.CHAIN_ID) {
      return TempoStreakClaimTxResult(
        success = false,
        error = "Wrong chain connected: $chainId (expected ${TempoClient.CHAIN_ID})",
      )
    }

    val txHashes = ArrayList<String>()
    var submittedCount = 0
    for (claim in normalizedClaims) {
      val txHash =
        runCatching {
          submitSingleClaim(
            activity = activity,
            account = account,
            rpId = rpId,
            sessionKey = usableSessionKey,
            sender = sender,
            contract = streakClaimContract,
            claim = claim,
          )
        }.getOrElse { err ->
          return TempoStreakClaimTxResult(
            success = false,
            txHashes = txHashes,
            submittedCount = submittedCount,
            error = err.message ?: "Streak claim tx failed",
          )
        }
      txHashes.add(txHash)
      submittedCount += 1
    }

    return TempoStreakClaimTxResult(
      success = true,
      txHashes = txHashes,
      submittedCount = submittedCount,
    )
  }

  private suspend fun submitSingleClaim(
    activity: FragmentActivity,
    account: TempoPasskeyManager.PasskeyAccount,
    rpId: String,
    sessionKey: SessionKeyManager.SessionKey?,
    sender: String,
    contract: String,
    claim: TempoStreakClaimInput,
  ): String {
    val nonce = withContext(Dispatchers.IO) { TempoClient.getNonce(sender) }
    val fees = withContext(Dispatchers.IO) { TempoClient.getSuggestedFees() }
    val callData = encodeCalldata(sender, claim)
    val gasLimit =
      withContext(Dispatchers.IO) {
        val estimated = estimateGas(from = sender, to = contract, data = callData)
        withBuffer(estimated = estimated, minimum = MIN_GAS_LIMIT)
      }

    val tx =
      TempoTransaction.UnsignedTx(
        nonce = nonce,
        maxPriorityFeePerGas = fees.maxPriorityFeePerGas,
        maxFeePerGas = fees.maxFeePerGas,
        feeMode = TempoTransaction.FeeMode.RELAY_SPONSORED,
        gasLimit = gasLimit,
        calls =
          listOf(
            TempoTransaction.Call(
              to = P256Utils.hexToBytes(contract),
              value = 0,
              input = P256Utils.hexToBytes(callData),
            ),
          ),
      )

    val signedTx =
      signTx(
        activity = activity,
        account = account,
        rpId = rpId,
        sessionKey = sessionKey,
        tx = tx,
      )
    val txHash =
      withContext(Dispatchers.IO) {
        TempoClient.sendSponsoredRawTransaction(
          signedTxHex = signedTx,
          senderAddress = sender,
        )
      }
    val receipt = withContext(Dispatchers.IO) { TempoClient.waitForTransactionReceipt(txHash) }
    if (!receipt.isSuccess) {
      throw IllegalStateException("Streak claim tx reverted on-chain: $txHash")
    }
    return txHash
  }

  private suspend fun signTx(
    activity: FragmentActivity,
    account: TempoPasskeyManager.PasskeyAccount,
    rpId: String,
    sessionKey: SessionKeyManager.SessionKey?,
    tx: TempoTransaction.UnsignedTx,
  ): String {
    val sigHash = TempoTransaction.signatureHash(tx)
    return if (sessionKey != null && SessionKeyManager.isValid(sessionKey, ownerAddress = account.address)) {
      val keychainSig =
        SessionKeyManager.signWithSessionKey(
          sessionKey = sessionKey,
          userAddress = account.address,
          txHash = sigHash,
        )
      TempoTransaction.encodeSignedSessionKey(tx, keychainSig)
    } else {
      val assertion =
        TempoPasskeyManager.sign(
          activity = activity,
          challenge = sigHash,
          account = account,
          rpId = rpId,
        )
      TempoTransaction.encodeSignedWebAuthn(tx, assertion)
    }
  }

  private fun encodeCalldata(
    userAddress: String,
    claim: TempoStreakClaimInput,
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

  private fun normalizeClaim(
    index: Int,
    claim: TempoStreakClaimInput,
  ): TempoStreakClaimInput {
    val position = index + 1
    val studySetKey = normalizeBytes32(claim.studySetKey, "studySetKey")
    if (claim.dayUtc < 0L) {
      throw IllegalArgumentException("Claim $position: dayUtc must be >= 0")
    }
    if (claim.nonce < 0L) {
      throw IllegalArgumentException("Claim $position: nonce must be >= 0")
    }
    if (claim.expirySec <= 0L) {
      throw IllegalArgumentException("Claim $position: expirySec must be > 0")
    }
    val signatureHex = normalizeSignatureHex(claim.signatureHex, "signatureHex")

    return claim.copy(
      studySetKey = studySetKey,
      signatureHex = signatureHex,
    )
  }

  private fun streakClaimContractOrNull(): String? {
    val configured = TempoClient.STREAK_CLAIM_V1.trim()
    if (!ADDRESS_REGEX.matches(configured)) return null
    if (configured.equals("0x0000000000000000000000000000000000000000", ignoreCase = true)) return null
    return "0x${configured.removePrefix("0x").removePrefix("0X")}"
  }

  private fun normalizeAddress(address: String): String {
    val trimmed = address.trim()
    val prefixed = if (trimmed.startsWith("0x", ignoreCase = true)) trimmed else "0x$trimmed"
    if (!ADDRESS_REGEX.matches(prefixed)) {
      throw IllegalArgumentException("Invalid address: $address")
    }
    return "0x${prefixed.removePrefix("0x").removePrefix("0X")}"
  }

  private fun normalizeBytes32(
    value: String,
    fieldName: String,
  ): String {
    val trimmed = value.trim()
    if (!BYTES32_REGEX.matches(trimmed)) {
      throw IllegalArgumentException("Invalid $fieldName: $value")
    }
    return trimmed.lowercase()
  }

  private fun normalizeSignatureHex(
    value: String,
    fieldName: String,
  ): String {
    val trimmed = value.trim()
    val prefixed = if (trimmed.startsWith("0x", ignoreCase = true)) trimmed else "0x$trimmed"
    if (!SIGNATURE_REGEX.matches(prefixed)) {
      throw IllegalArgumentException("Invalid $fieldName: $value")
    }
    return "0x${prefixed.removePrefix("0x").removePrefix("0X")}"
  }

  private fun estimateGas(
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
          JSONArray().put(
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

  private fun withBuffer(
    estimated: Long,
    minimum: Long,
  ): Long {
    if (estimated <= 0L) return minimum
    val buffered = if (estimated > Long.MAX_VALUE - GAS_LIMIT_BUFFER) Long.MAX_VALUE else estimated + GAS_LIMIT_BUFFER
    return maxOf(minimum, buffered)
  }
}
