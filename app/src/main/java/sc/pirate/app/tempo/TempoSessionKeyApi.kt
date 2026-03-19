package sc.pirate.app.tempo

import android.app.Activity
import android.util.Log
import sc.pirate.app.util.shortAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class TempoSessionAuthorizationResult(
  val success: Boolean,
  val sessionKey: SessionKeyManager.SessionKey? = null,
  val txHash: String? = null,
  val error: String? = null,
)

enum class SessionKeyAuthorizationProgress {
  SIGNATURE_1,
  SIGNATURE_2,
  FINALIZING,
}

object TempoSessionKeyApi {
  private const val TAG = "TempoSessionKeyApi"
  private const val GAS_LIMIT_AUTHORIZE_SESSION_KEY = 1_200_000L
  private const val GAS_LIMIT_BUFFER = 250_000L
  private const val GAS_LIMIT_MAX = 3_000_000L

  private const val AUTH_EXPIRY_WINDOW_SEC = 25L
  private const val MAX_UNDERPRICED_RETRIES = 4
  private const val RETRY_DELAY_MS = 220L
  private const val RELAY_MIN_PRIORITY_FEE_PER_GAS = 6_000_000_000L
  private const val RELAY_MIN_MAX_FEE_PER_GAS = 120_000_000_000L

  private val authorizationMutex = Mutex()
  private val bidFloorLock = Any()
  private val lastBidByAddress = mutableMapOf<String, TempoClient.Eip1559Fees>()

  private val jsonType = "application/json; charset=utf-8".toMediaType()
  private val httpClient = OkHttpClient()

  /**
   * Authorizes a new ephemeral session key for the passkey account.
   * This requires two passkey prompts:
   * 1) sign KeyAuthorization digest
   * 2) sign authorization transaction
   *
   * @param onProgress Optional callback to report authorization progress stages:
   *   - SIGNATURE_1 when requesting first signature (key authorization)
   *   - SIGNATURE_2 when requesting second signature (transaction)
   *   - FINALIZING when waiting for on-chain confirmation
   */
  suspend fun authorizeSessionKey(
    activity: Activity,
    account: TempoPasskeyManager.PasskeyAccount,
    rpId: String = account.rpId,
    onProgress: ((SessionKeyAuthorizationProgress) -> Unit)? = null,
  ): TempoSessionAuthorizationResult =
    authorizationMutex.withLock {
      val existing =
        SessionKeyManager.load(activity)?.takeIf {
          SessionKeyManager.isValid(it, ownerAddress = account.address) &&
            it.keyAuthorization?.isNotEmpty() == true
        }
      if (existing != null) {
        Log.d(TAG, "Session key already active for ${shortAddress(account.address, minLengthToShorten = 10)}")
        return@withLock TempoSessionAuthorizationResult(success = true, sessionKey = existing)
      }

      val traceId = buildTraceId(account.address)
      var stage = "start"
      var generatedAlias: String? = null

      runCatching {
        stage = "chain-check"
        val chainId = withContext(Dispatchers.IO) { TempoClient.getChainId() }
        if (chainId != TempoClient.CHAIN_ID) {
          throw IllegalStateException("Wrong chain connected: $chainId (expected ${TempoClient.CHAIN_ID})")
        }
        Log.d(TAG, "[$traceId] chain ok=$chainId")

        stage = "generate-session-key"
        val sessionKey = SessionKeyManager.generate(activity = activity, ownerAddress = account.address)
        generatedAlias = sessionKey.keystoreAlias
        Log.d(TAG, "[$traceId] generated alias=${sessionKey.keystoreAlias.takeLast(8)} keyId=${sessionKey.address}")

        stage = "sign-key-authorization"
        onProgress?.invoke(SessionKeyAuthorizationProgress.SIGNATURE_1)
        val keyAuthDigest = SessionKeyManager.buildKeyAuthDigest(sessionKey)
        val keyAuthAssertion =
          TempoPasskeyManager.sign(
            activity = activity,
            challenge = keyAuthDigest,
            account = account,
            rpId = rpId,
          )
        val signedKeyAuthorization =
          SessionKeyManager.buildSignedKeyAuthorization(sessionKey, keyAuthAssertion)
        Log.d(TAG, "[$traceId] key authorization signed")

        stage = "prepare-transaction"
        val sender = account.address
        val nonce = withContext(Dispatchers.IO) { TempoClient.getNonce(sender) }
        val gasLimit = estimateAuthorizationGas(senderAddress = sender)
        var fees = withContext(Dispatchers.IO) {
          val suggested = TempoClient.getSuggestedFees()
          withAddressBidFloor(sender, withRelayMinimumFeeFloor(suggested))
        }
        Log.d(TAG, "[$traceId] tx params nonce=$nonce gas=$gasLimit fees=${fees.maxPriorityFeePerGas}/${fees.maxFeePerGas}")

        fun buildTx(txFees: TempoClient.Eip1559Fees): TempoTransaction.UnsignedTx =
          TempoTransaction.UnsignedTx(
            nonce = nonce,
            maxPriorityFeePerGas = txFees.maxPriorityFeePerGas,
            maxFeePerGas = txFees.maxFeePerGas,
            feeMode = TempoTransaction.FeeMode.RELAY_SPONSORED,
            gasLimit = gasLimit,
            calls =
              listOf(
                TempoTransaction.Call(
                  to = P256Utils.hexToBytes(sender),
                  value = 0,
                  input = ByteArray(0),
                ),
              ),
            keyAuthorization = signedKeyAuthorization,
          )

        suspend fun signTx(tx: TempoTransaction.UnsignedTx): String {
          val txSigHash = TempoTransaction.signatureHash(tx)
          val txAssertion =
            TempoPasskeyManager.sign(
              activity = activity,
              challenge = txSigHash,
              account = account,
              rpId = rpId,
            )
          return TempoTransaction.encodeSignedWebAuthn(tx, txAssertion)
        }

        stage = "relay-submit"
        onProgress?.invoke(SessionKeyAuthorizationProgress.SIGNATURE_2)
        var txHash: String? = null
        var lastError: Throwable? = null
        for (attempt in 0..MAX_UNDERPRICED_RETRIES) {
          val relayTx = buildTx(txFees = fees)
          val relaySignedTxHex = signTx(relayTx)
          val submitted = withContext(Dispatchers.IO) {
            runCatching {
              TempoClient.sendSponsoredRawTransaction(
                signedTxHex = relaySignedTxHex,
                senderAddress = sender,
              )
            }
          }
          val hash = submitted.getOrNull()
          if (!hash.isNullOrBlank()) {
            txHash = hash
            rememberAddressBidFloor(sender, fees)
            Log.d(TAG, "[$traceId] relay submit success attempt=${attempt + 1} tx=$hash")
            break
          }

          val err = submitted.exceptionOrNull() ?: IllegalStateException("Unknown relay submission failure")
          lastError = err
          if (!isReplacementUnderpriced(err) || attempt >= MAX_UNDERPRICED_RETRIES) {
            throw err
          }

          fees = withAddressBidFloor(sender, withRelayMinimumFeeFloor(aggressivelyBumpFees(fees)))
          rememberAddressBidFloor(sender, fees)
          Log.w(
            TAG,
            "[$traceId] relay underpriced attempt=${attempt + 1}; bumping fees to " +
              "${fees.maxPriorityFeePerGas}/${fees.maxFeePerGas}",
          )
          delay(RETRY_DELAY_MS)
        }

        val canonicalTxHash = txHash ?: throw (lastError ?: IllegalStateException("No transaction hash returned"))

        stage = "receipt-wait"
        onProgress?.invoke(SessionKeyAuthorizationProgress.FINALIZING)
        val receipt = awaitAuthorizationReceipt(canonicalTxHash, traceId)
        if (!receipt.isSuccess) {
          throw IllegalStateException("Session key authorization reverted on-chain: $canonicalTxHash")
        }

        stage = "persist-session-key"
        val persistedSessionKey = sessionKey.copy(keyAuthorization = signedKeyAuthorization)
        SessionKeyManager.save(activity, persistedSessionKey)
        generatedAlias = null
        Log.d(TAG, "[$traceId] authorization complete tx=$canonicalTxHash")

        TempoSessionAuthorizationResult(
          success = true,
          sessionKey = persistedSessionKey,
          txHash = canonicalTxHash,
        )
      }.getOrElse { err ->
        generatedAlias?.let { alias ->
          SessionKeyManager.deleteAlias(activity, alias)
          Log.w(TAG, "[$traceId] cleaned failed alias=${alias.takeLast(8)}")
        }
        val message = "Session key authorization failed at $stage: ${err.message ?: "unknown error"}"
        Log.w(TAG, "[$traceId] $message", err)
        TempoSessionAuthorizationResult(
          success = false,
          error = message,
        )
      }
    }

  private suspend fun estimateAuthorizationGas(senderAddress: String): Long = withContext(Dispatchers.IO) {
    val txObject =
      JSONObject()
        .put("from", senderAddress)
        .put("to", senderAddress)
        .put("value", "0x0")
        .put("data", "0x")

    val payload =
      JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", "eth_estimateGas")
        .put("params", JSONArray().put(txObject).put("latest"))

    val request =
      Request.Builder()
        .url(TempoClient.RPC_URL)
        .post(payload.toString().toRequestBody(jsonType))
        .build()

    val estimated =
      httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          throw IllegalStateException("Gas estimation RPC failed: ${response.code}")
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

    withGasBuffer(estimated = estimated, minimum = GAS_LIMIT_AUTHORIZE_SESSION_KEY)
  }

  private suspend fun awaitAuthorizationReceipt(
    txHash: String,
    traceId: String,
  ): TempoClient.TransactionReceipt {
    return runCatching {
      withContext(Dispatchers.IO) {
        TempoClient.waitForTransactionReceipt(
          txHash = txHash,
          timeoutMs = (AUTH_EXPIRY_WINDOW_SEC + 65L) * 1000L,
          pollMs = 1_500L,
        )
      }
    }.getOrElse { waitErr ->
      val message = waitErr.message.orEmpty()
      val timedOut = message.contains("timed out waiting for transaction receipt", ignoreCase = true)
      if (!timedOut) throw waitErr

      val hasTx = withContext(Dispatchers.IO) {
        runCatching { TempoClient.hasTransaction(txHash) }.getOrDefault(false)
      }
      if (!hasTx) {
        throw IllegalStateException(
          "Session key authorization not confirmed before timeout: $txHash",
          waitErr,
        )
      }

      Log.w(TAG, "[$traceId] receipt timeout but tx exists; extending wait")
      withContext(Dispatchers.IO) {
        TempoClient.waitForTransactionReceipt(
          txHash = txHash,
          timeoutMs = 30_000L,
          pollMs = 1_500L,
        )
      }
    }
  }

  private fun withGasBuffer(estimated: Long, minimum: Long): Long {
    val buffered =
      if (Long.MAX_VALUE - estimated < GAS_LIMIT_BUFFER) Long.MAX_VALUE else estimated + GAS_LIMIT_BUFFER
    return maxOf(buffered, minimum).coerceAtMost(GAS_LIMIT_MAX)
  }

  private fun withRelayMinimumFeeFloor(fees: TempoClient.Eip1559Fees): TempoClient.Eip1559Fees {
    val flooredPriority = maxOf(fees.maxPriorityFeePerGas, RELAY_MIN_PRIORITY_FEE_PER_GAS)
    val flooredMaxFee = maxOf(maxOf(fees.maxFeePerGas, RELAY_MIN_MAX_FEE_PER_GAS), flooredPriority + 1_000_000L)
    return TempoClient.Eip1559Fees(
      maxPriorityFeePerGas = flooredPriority,
      maxFeePerGas = flooredMaxFee,
    )
  }

  private fun withAddressBidFloor(address: String, fees: TempoClient.Eip1559Fees): TempoClient.Eip1559Fees {
    val key = address.trim().lowercase()
    val prior = synchronized(bidFloorLock) { lastBidByAddress[key] }
    if (prior == null) return fees
    val mergedPriority = maxOf(fees.maxPriorityFeePerGas, prior.maxPriorityFeePerGas)
    val mergedMax = maxOf(maxOf(fees.maxFeePerGas, prior.maxFeePerGas), mergedPriority + 1_000_000L)
    return TempoClient.Eip1559Fees(
      maxPriorityFeePerGas = mergedPriority,
      maxFeePerGas = mergedMax,
    )
  }

  private fun rememberAddressBidFloor(address: String, fees: TempoClient.Eip1559Fees) {
    val key = address.trim().lowercase()
    synchronized(bidFloorLock) {
      lastBidByAddress[key] = fees
    }
  }

  private fun aggressivelyBumpFees(fees: TempoClient.Eip1559Fees): TempoClient.Eip1559Fees {
    val priorityBump = maxOf(1_000_000_000L, fees.maxPriorityFeePerGas / 5L)
    val maxFeeBump = maxOf(2_000_000_000L, fees.maxFeePerGas / 5L)
    val bumpedPriority = saturatingAdd(fees.maxPriorityFeePerGas, priorityBump)
    val bumpedMaxRaw = saturatingAdd(fees.maxFeePerGas, maxFeeBump)
    val bumpedMax = maxOf(bumpedMaxRaw, saturatingAdd(bumpedPriority, 1_000_000L))
    return TempoClient.Eip1559Fees(
      maxPriorityFeePerGas = bumpedPriority,
      maxFeePerGas = bumpedMax,
    )
  }

  private fun isReplacementUnderpriced(err: Throwable): Boolean {
    val message = err.message.orEmpty().lowercase()
    return message.contains("replacement transaction underpriced") ||
      message.contains("transaction underpriced") ||
      message.contains("underpriced")
  }

  private fun buildTraceId(address: String): String {
    val suffix = address.removePrefix("0x").takeLast(6).ifBlank { "unknown" }
    return "$suffix-${UUID.randomUUID().toString().take(8)}"
  }

  private fun saturatingAdd(a: Long, b: Long): Long =
    if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
}
