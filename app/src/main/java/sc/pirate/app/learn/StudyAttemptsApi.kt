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
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint16
import org.web3j.abi.datatypes.generated.Uint64
import org.web3j.abi.datatypes.generated.Uint8

internal data class StudyAttemptInput(
  val studySetKey: String,
  val questionId: String,
  val rating: Int,
  val score: Int,
  val timestampSec: Long,
)

internal data class StudyAttemptsTxResult(
  val success: Boolean,
  val txHashes: List<String> = emptyList(),
  val submittedCount: Int = 0,
  val error: String? = null,
)

internal object StudyAttemptsApi {
  private const val MAX_ATTEMPTS_PER_TX = 200
  private const val RECEIPT_POLL_DELAY_MS = 1_500L
  private const val RECEIPT_TIMEOUT_MS = 90_000L
  private val ADDRESS_REGEX = Regex("^0x[a-fA-F0-9]{40}$")
  private val BYTES32_REGEX = Regex("^0x[a-fA-F0-9]{64}$")
  private val jsonType = "application/json".toMediaType()
  private val client = OkHttpClient()

  fun isConfigured(): Boolean = studyAttemptsContractOrNull() != null

  suspend fun submitAttempts(
    context: Context,
    ownerAddress: String,
    attempts: List<StudyAttemptInput>,
  ): StudyAttemptsTxResult {
    if (attempts.isEmpty()) {
      return StudyAttemptsTxResult(success = true, submittedCount = 0)
    }

    val studyAttemptsContract =
      studyAttemptsContractOrNull() ?: return StudyAttemptsTxResult(
        success = false,
        error = "Study attempts contract is not configured",
      )
    val sender = runCatching { normalizeAddress(ownerAddress) }.getOrElse { err ->
      return StudyAttemptsTxResult(success = false, error = err.message ?: "Invalid sender address")
    }
    val normalized = runCatching { attempts.mapIndexed { index, attempt -> normalizeAttempt(index, attempt) } }.getOrElse { err ->
      return StudyAttemptsTxResult(success = false, error = err.message ?: "Invalid study attempt payload")
    }

    val txHashes = ArrayList<String>()
    var submittedCount = 0
    for (batch in normalized.chunked(MAX_ATTEMPTS_PER_TX)) {
      val txHash = runCatching {
        submitBatch(context = context, sender = sender, contract = studyAttemptsContract, attempts = batch)
      }.getOrElse { err ->
        return StudyAttemptsTxResult(
          success = false,
          txHashes = txHashes,
          submittedCount = submittedCount,
          error = err.message ?: "Study attempts tx failed",
        )
      }
      txHashes.add(txHash)
      submittedCount += batch.size
    }

    return StudyAttemptsTxResult(success = true, txHashes = txHashes, submittedCount = submittedCount)
  }

  private suspend fun submitBatch(
    context: Context,
    sender: String,
    contract: String,
    attempts: List<StudyAttemptInput>,
  ): String {
    val callData = encodeCalldata(sender, attempts)
    val txHash =
      PrivyRelayClient.submitContractCall(
        context = context,
        chainId = PirateChainConfig.STORY_AENEID_CHAIN_ID,
        to = contract,
        data = callData,
        intentType = "pirate.learn.submit-attempts",
        intentArgs = JSONObject().put("count", attempts.size),
      )
    val receipt = waitForReceipt(txHash)
    if (!receipt.isSuccess) {
      throw IllegalStateException("Study attempts tx reverted on-chain: $txHash")
    }
    return txHash
  }

  private fun encodeCalldata(
    userAddress: String,
    attempts: List<StudyAttemptInput>,
  ): String {
    val studySetKeys = DynamicArray(Bytes32::class.java, attempts.map { Bytes32(P256Utils.hexToBytes(it.studySetKey)) })
    val questionIds = DynamicArray(Bytes32::class.java, attempts.map { Bytes32(P256Utils.hexToBytes(it.questionId)) })
    val ratings = DynamicArray(Uint8::class.java, attempts.map { Uint8(BigInteger.valueOf(it.rating.toLong())) })
    val scores = DynamicArray(Uint16::class.java, attempts.map { Uint16(BigInteger.valueOf(it.score.toLong())) })
    val timestamps = DynamicArray(Uint64::class.java, attempts.map { Uint64(BigInteger.valueOf(it.timestampSec.coerceAtLeast(0L))) })
    val function = Function("submitAttempts", listOf(Address(userAddress), studySetKeys, questionIds, ratings, scores, timestamps), emptyList())
    return FunctionEncoder.encode(function)
  }

  private fun normalizeAttempt(index: Int, attempt: StudyAttemptInput): StudyAttemptInput {
    val position = index + 1
    if (attempt.rating !in 1..4) {
      throw IllegalArgumentException("Attempt $position: rating must be in [1, 4]")
    }
    if (attempt.score !in 0..10_000) {
      throw IllegalArgumentException("Attempt $position: score must be in [0, 10000]")
    }
    val studySetKey = normalizeBytes32(attempt.studySetKey, "Attempt $position: studySetKey")
    val questionId = normalizeBytes32(attempt.questionId, "Attempt $position: questionId")
    val timestampSec = attempt.timestampSec.coerceAtLeast(0L)
    return attempt.copy(studySetKey = studySetKey, questionId = questionId, timestampSec = timestampSec)
  }

  private fun studyAttemptsContractOrNull(): String? {
    val configured = PirateChainConfig.STORY_STUDY_ATTEMPTS_CONTRACT.trim()
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

  private data class TxReceipt(val isSuccess: Boolean)

  private suspend fun waitForReceipt(txHash: String): TxReceipt = withContext(Dispatchers.IO) {
    val startedAt = System.currentTimeMillis()
    while (System.currentTimeMillis() - startedAt < RECEIPT_TIMEOUT_MS) {
      fetchReceiptOrNull(txHash)?.let { return@withContext it }
      delay(RECEIPT_POLL_DELAY_MS)
    }
    throw IllegalStateException("Study attempts receipt timed out: $txHash")
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
