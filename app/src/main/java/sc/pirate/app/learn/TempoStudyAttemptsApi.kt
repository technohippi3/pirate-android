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
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint16
import org.web3j.abi.datatypes.generated.Uint64
import org.web3j.abi.datatypes.generated.Uint8

internal data class TempoStudyAttemptInput(
  val studySetKey: String,
  val questionId: String,
  val rating: Int,
  val score: Int,
  val timestampSec: Long,
)

internal data class TempoStudyAttemptsTxResult(
  val success: Boolean,
  val txHashes: List<String> = emptyList(),
  val submittedCount: Int = 0,
  val error: String? = null,
)

internal object TempoStudyAttemptsApi {
  private const val MIN_GAS_LIMIT = 340_000L
  private const val GAS_LIMIT_BUFFER = 150_000L
  private const val MAX_ATTEMPTS_PER_TX = 200
  private val ADDRESS_REGEX = Regex("^0x[a-fA-F0-9]{40}$")
  private val BYTES32_REGEX = Regex("^0x[a-fA-F0-9]{64}$")

  private val jsonType = "application/json".toMediaType()
  private val client = OkHttpClient()

  fun isConfigured(): Boolean = studyAttemptsContractOrNull() != null

  suspend fun submitAttempts(
    activity: FragmentActivity,
    account: TempoPasskeyManager.PasskeyAccount,
    attempts: List<TempoStudyAttemptInput>,
    rpId: String = account.rpId,
    sessionKey: SessionKeyManager.SessionKey? = null,
  ): TempoStudyAttemptsTxResult {
    if (attempts.isEmpty()) {
      return TempoStudyAttemptsTxResult(success = true, submittedCount = 0)
    }

    val studyAttemptsContract = studyAttemptsContractOrNull()
      ?: return TempoStudyAttemptsTxResult(
        success = false,
        error = "Study attempts contract is not configured",
      )
    val sender = runCatching { normalizeAddress(account.address) }
      .getOrElse { err ->
        return TempoStudyAttemptsTxResult(
          success = false,
          error = err.message ?: "Invalid sender address",
        )
      }
    val normalized =
      runCatching { attempts.mapIndexed { index, attempt -> normalizeAttempt(index, attempt) } }
        .getOrElse { err ->
          return TempoStudyAttemptsTxResult(
            success = false,
            error = err.message ?: "Invalid study attempt payload",
          )
        }
    val usableSessionKey = sessionKey?.takeIf { SessionKeyManager.isValid(it, ownerAddress = sender) }

    val chainId = withContext(Dispatchers.IO) { TempoClient.getChainId() }
    if (chainId != TempoClient.CHAIN_ID) {
      return TempoStudyAttemptsTxResult(
        success = false,
        error = "Wrong chain connected: $chainId (expected ${TempoClient.CHAIN_ID})",
      )
    }

    val txHashes = ArrayList<String>()
    var submittedCount = 0
    for (batch in normalized.chunked(MAX_ATTEMPTS_PER_TX)) {
      val txHash =
        runCatching {
          submitBatch(
            activity = activity,
            account = account,
            rpId = rpId,
            sessionKey = usableSessionKey,
            sender = sender,
            contract = studyAttemptsContract,
            attempts = batch,
          )
        }.getOrElse { err ->
          return TempoStudyAttemptsTxResult(
            success = false,
            txHashes = txHashes,
            submittedCount = submittedCount,
            error = err.message ?: "Study attempts tx failed",
          )
        }
      txHashes.add(txHash)
      submittedCount += batch.size
    }

    return TempoStudyAttemptsTxResult(
      success = true,
      txHashes = txHashes,
      submittedCount = submittedCount,
    )
  }

  private suspend fun submitBatch(
    activity: FragmentActivity,
    account: TempoPasskeyManager.PasskeyAccount,
    rpId: String,
    sessionKey: SessionKeyManager.SessionKey?,
    sender: String,
    contract: String,
    attempts: List<TempoStudyAttemptInput>,
  ): String {
    val nonce = withContext(Dispatchers.IO) { TempoClient.getNonce(sender) }
    val fees = withContext(Dispatchers.IO) { TempoClient.getSuggestedFees() }
    val callData = encodeCalldata(sender, attempts)
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
      throw IllegalStateException("Study attempts tx reverted on-chain: $txHash")
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
    attempts: List<TempoStudyAttemptInput>,
  ): String {
    val studySetKeys =
      DynamicArray(
        Bytes32::class.java,
        attempts.map { Bytes32(P256Utils.hexToBytes(it.studySetKey)) },
      )
    val questionIds =
      DynamicArray(
        Bytes32::class.java,
        attempts.map { Bytes32(P256Utils.hexToBytes(it.questionId)) },
      )
    val ratings =
      DynamicArray(
        Uint8::class.java,
        attempts.map { Uint8(BigInteger.valueOf(it.rating.toLong())) },
      )
    val scores =
      DynamicArray(
        Uint16::class.java,
        attempts.map { Uint16(BigInteger.valueOf(it.score.toLong())) },
      )
    val timestamps =
      DynamicArray(
        Uint64::class.java,
        attempts.map { Uint64(BigInteger.valueOf(it.timestampSec.coerceAtLeast(0L))) },
      )

    val function =
      Function(
        "submitAttempts",
        listOf(Address(userAddress), studySetKeys, questionIds, ratings, scores, timestamps),
        emptyList(),
      )
    return FunctionEncoder.encode(function)
  }

  private fun normalizeAttempt(
    index: Int,
    attempt: TempoStudyAttemptInput,
  ): TempoStudyAttemptInput {
    val position = index + 1
    if (attempt.rating !in 1..4) {
      throw IllegalArgumentException("Attempt $position: rating must be in [1, 4]")
    }
    if (attempt.score !in 0..10_000) {
      throw IllegalArgumentException("Attempt $position: score must be in [0, 10000]")
    }

    val studySetKey = normalizeBytes32(attempt.studySetKey, "studySetKey")
    val questionId = normalizeBytes32(attempt.questionId, "questionId")
    val timestamp = attempt.timestampSec.coerceAtLeast(0L)
    return attempt.copy(
      studySetKey = studySetKey,
      questionId = questionId,
      timestampSec = timestamp,
    )
  }

  private fun studyAttemptsContractOrNull(): String? {
    val configured = TempoClient.STUDY_ATTEMPTS_V1.trim()
    if (!ADDRESS_REGEX.matches(configured)) return null
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
