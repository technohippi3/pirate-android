package com.pirate.app.profile

import androidx.fragment.app.FragmentActivity
import com.pirate.app.tempo.P256Utils
import com.pirate.app.tempo.SessionKeyManager
import com.pirate.app.tempo.TempoClient
import com.pirate.app.tempo.TempoPasskeyManager
import com.pirate.app.tempo.TempoTransaction
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
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256

data class TempoNameRegisterResult(
  val success: Boolean,
  val txHash: String? = null,
  val label: String? = null,
  val tld: String? = null,
  val fullName: String? = null,
  val node: String? = null,
  val tokenId: String? = null,
  val sessionKey: SessionKeyManager.SessionKey? = null,
  val error: String? = null,
)

object TempoNameRegistryApi {
  private const val GAS_LIMIT_REGISTER = 900_000L
  private const val GAS_LIMIT_REGISTER_WITH_SESSION_BOOTSTRAP = 2_200_000L
  private const val GAS_LIMIT_REGISTER_BUFFER = 300_000L
  private const val GAS_LIMIT_REGISTER_MAX = 3_500_000L
  private const val MIN_DURATION_SECONDS = 365L * 24L * 60L * 60L

  private val jsonType = "application/json; charset=utf-8".toMediaType()
  private val client = OkHttpClient()

  suspend fun checkNameAvailable(label: String, tld: String): Boolean = withContext(Dispatchers.IO) {
    val normalizedLabel = label.trim().lowercase()
    if (normalizedLabel.isBlank()) return@withContext false
    val normalizedTld = tld.trim().lowercase()
    val parentNode = TempoNameRecordsApi.parentNodeForTld(normalizedTld)
      ?: throw IllegalArgumentException("Unsupported TLD: .$normalizedTld")

    if (!tldExists(parentNode)) {
      throw IllegalStateException(".$normalizedTld is not configured on the current registry yet.")
    }

    val callData =
      FunctionEncoder.encode(
        Function(
          "available",
          listOf(
            Bytes32(P256Utils.hexToBytes(parentNode.removePrefix("0x"))),
            Utf8String(normalizedLabel),
          ),
          emptyList(),
        ),
      )
    val result = ethCall(TempoNameRecordsApi.REGISTRY_V1, callData)
    parseBool(result)
  }

  suspend fun register(
    activity: FragmentActivity,
    account: TempoPasskeyManager.PasskeyAccount,
    label: String,
    tld: String,
    durationSeconds: Long = MIN_DURATION_SECONDS,
    rpId: String = account.rpId,
    sessionKey: SessionKeyManager.SessionKey? = null,
    bootstrapSessionKey: Boolean = false,
    preferSelfPay: Boolean = false,
  ): TempoNameRegisterResult {
    val normalizedLabel = label.trim().lowercase()
    val normalizedTld = tld.trim().lowercase()
    if (normalizedLabel.isBlank()) {
      return TempoNameRegisterResult(success = false, error = "Name label is empty.")
    }
    if (durationSeconds <= 0L) {
      return TempoNameRegisterResult(success = false, error = "Invalid registration duration.")
    }
    if (sessionKey != null && bootstrapSessionKey) {
      return TempoNameRegisterResult(
        success = false,
        error = "Cannot bootstrap a new session key when an active session key is already provided.",
      )
    }
    val parentNode = TempoNameRecordsApi.parentNodeForTld(normalizedTld)
      ?: return TempoNameRegisterResult(success = false, error = "Unsupported TLD: $normalizedTld")
    val tldExists = runCatching { withContext(Dispatchers.IO) { tldExists(parentNode) } }.getOrDefault(false)
    if (!tldExists) {
      return TempoNameRegisterResult(
        success = false,
        error = ".$normalizedTld is not configured on the current registry yet.",
      )
    }

    return runCatching {
      val chainId = withContext(Dispatchers.IO) { TempoClient.getChainId() }
      if (chainId != TempoClient.CHAIN_ID) {
        throw IllegalStateException("Wrong chain connected: $chainId (expected ${TempoClient.CHAIN_ID})")
      }

      val priceWei = withContext(Dispatchers.IO) {
        getRegistrationPriceWei(parentNode = parentNode, label = normalizedLabel, durationSeconds = durationSeconds)
      }
      if (priceWei > BigInteger.valueOf(Long.MAX_VALUE)) {
        throw IllegalStateException("Registration price exceeds transaction value limits.")
      }

      val nonce = withContext(Dispatchers.IO) { TempoClient.getNonce(account.address) }
      val fees = withContext(Dispatchers.IO) { TempoClient.getSuggestedFees() }

      val callData = encodeRegisterCall(parentNode = parentNode, label = normalizedLabel, durationSeconds = durationSeconds)
      val estimatedGasLimit = withContext(Dispatchers.IO) {
        estimateGas(
          from = account.address,
          to = TempoNameRecordsApi.REGISTRY_V1,
          valueWei = priceWei,
          data = callData,
        )
      }
      val minimumGasLimit = if (bootstrapSessionKey) GAS_LIMIT_REGISTER_WITH_SESSION_BOOTSTRAP else GAS_LIMIT_REGISTER
      val gasLimit = withRegisterGasBuffer(estimated = estimatedGasLimit, minimum = minimumGasLimit)
      val bootstrappedSessionKey =
        if (bootstrapSessionKey) SessionKeyManager.generate(activity = activity, ownerAddress = account.address) else null
      val signedKeyAuthorization =
        if (bootstrappedSessionKey != null) {
          val keyAuthDigest = SessionKeyManager.buildKeyAuthDigest(bootstrappedSessionKey)
          val keyAuthAssertion =
            TempoPasskeyManager.sign(
              activity = activity,
              challenge = keyAuthDigest,
              account = account,
              rpId = rpId,
            )
          SessionKeyManager.buildSignedKeyAuthorization(bootstrappedSessionKey, keyAuthAssertion)
        } else {
          null
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
          keyAuthorization = signedKeyAuthorization,
          calls =
            listOf(
              TempoTransaction.Call(
                to = P256Utils.hexToBytes(TempoNameRecordsApi.REGISTRY_V1),
                value = priceWei.toLong(),
                input = P256Utils.hexToBytes(callData),
              ),
            ),
        )

      suspend fun signTx(tx: TempoTransaction.UnsignedTx): String {
        val sigHash = TempoTransaction.signatureHash(tx)
        return if (sessionKey != null) {
          val keychainSig = SessionKeyManager.signWithSessionKey(
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

      suspend fun submitWithFallback(): String {
        suspend fun submitRelay(): String {
          val relayTx = buildTx(feeMode = TempoTransaction.FeeMode.RELAY_SPONSORED, txFees = fees)
          val relaySignedTxHex = signTx(relayTx)
          return withContext(Dispatchers.IO) {
            TempoClient.sendSponsoredRawTransaction(
              signedTxHex = relaySignedTxHex,
              senderAddress = account.address,
            )
          }
        }

        suspend fun submitSelf(): String {
          withContext(Dispatchers.IO) { runCatching { TempoClient.fundAddress(account.address) } }
          val selfFees = withContext(Dispatchers.IO) { TempoClient.getSuggestedFees() }
          val selfTx = buildTx(feeMode = TempoTransaction.FeeMode.SELF, txFees = selfFees)
          val selfSignedTxHex = signTx(selfTx)
          return withContext(Dispatchers.IO) { TempoClient.sendRawTransaction(selfSignedTxHex) }
        }

        return if (preferSelfPay) {
          runCatching { submitSelf() }.getOrElse { selfErr ->
            runCatching { submitRelay() }.getOrElse { relayErr ->
              throw IllegalStateException(
                "Name registration submit failed: self=${selfErr.message}; relay=${relayErr.message}",
                relayErr,
              )
            }
          }
        } else {
          runCatching { submitRelay() }.getOrElse { relayErr ->
            runCatching { submitSelf() }.getOrElse { selfErr ->
              throw IllegalStateException(
                "Name registration submit failed: relay=${relayErr.message}; self=${selfErr.message}",
                selfErr,
              )
            }
          }
        }
      }

      val txHash = submitWithFallback()
      val receipt = withContext(Dispatchers.IO) { TempoClient.waitForTransactionReceipt(txHash) }
      if (!receipt.isSuccess) {
        throw IllegalStateException("Name registration reverted on-chain: $txHash")
      }

      val persistedSessionKey =
        if (bootstrappedSessionKey != null && signedKeyAuthorization != null) {
          val authorized = bootstrappedSessionKey.copy(keyAuthorization = signedKeyAuthorization)
          SessionKeyManager.save(activity, authorized)
          authorized
        } else {
          null
        }

      val fullName = "$normalizedLabel.$normalizedTld"
      val node = TempoNameRecordsApi.computeNode(fullName)
      TempoNameRegisterResult(
        success = true,
        txHash = txHash,
        label = normalizedLabel,
        tld = normalizedTld,
        fullName = fullName,
        node = node,
        tokenId = node,
        sessionKey = persistedSessionKey,
      )
    }.getOrElse { err ->
      TempoNameRegisterResult(success = false, error = err.message ?: "Name registration failed.")
    }
  }

  private fun encodeRegisterCall(parentNode: String, label: String, durationSeconds: Long): String {
    val function =
      Function(
        "register",
        listOf(
          Bytes32(P256Utils.hexToBytes(parentNode.removePrefix("0x"))),
          Utf8String(label),
          Uint256(BigInteger.valueOf(durationSeconds)),
        ),
        emptyList(),
      )
    return FunctionEncoder.encode(function)
  }

  private fun getRegistrationPriceWei(parentNode: String, label: String, durationSeconds: Long): BigInteger {
    val function =
      Function(
        "price",
        listOf(
          Bytes32(P256Utils.hexToBytes(parentNode.removePrefix("0x"))),
          Utf8String(label),
          Uint256(BigInteger.valueOf(durationSeconds)),
        ),
        emptyList(),
      )
    val callData = FunctionEncoder.encode(function)
    val result = ethCall(TempoNameRecordsApi.REGISTRY_V1, callData)
    val clean = result.removePrefix("0x").ifBlank { "0" }
    return clean.toBigIntegerOrNull(16) ?: BigInteger.ZERO
  }

  private fun parseBool(result: String): Boolean {
    val clean = result.removePrefix("0x").ifBlank { "0" }
    return clean.toBigIntegerOrNull(16)?.let { it != BigInteger.ZERO } ?: false
  }

  private fun tldExists(parentNode: String): Boolean {
    val callData =
      FunctionEncoder.encode(
        Function(
          "tldExists",
          listOf(Bytes32(P256Utils.hexToBytes(parentNode.removePrefix("0x")))),
          emptyList(),
        ),
      )
    val result = ethCall(TempoNameRecordsApi.REGISTRY_V1, callData)
    return parseBool(result)
  }

  private fun ethCall(to: String, data: String): String {
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
      return body.optString("result", "0x")
    }
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
        .post(payload.toString().toRequestBody(jsonType))
        .build()

    client.newCall(req).execute().use { response ->
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

  private fun withRegisterGasBuffer(
    estimated: Long,
    minimum: Long,
  ): Long {
    val buffered = if (Long.MAX_VALUE - estimated < GAS_LIMIT_REGISTER_BUFFER) Long.MAX_VALUE else estimated + GAS_LIMIT_REGISTER_BUFFER
    return maxOf(buffered, minimum).coerceAtMost(GAS_LIMIT_REGISTER_MAX)
  }
}
