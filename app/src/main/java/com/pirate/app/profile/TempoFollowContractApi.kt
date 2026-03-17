package com.pirate.app.profile

import androidx.fragment.app.FragmentActivity
import com.pirate.app.BuildConfig
import com.pirate.app.tempo.P256Utils
import com.pirate.app.tempo.SessionKeyManager
import com.pirate.app.tempo.TempoClient
import com.pirate.app.tempo.TempoPasskeyManager
import com.pirate.app.tempo.TempoTransaction
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
import org.web3j.abi.datatypes.Function

data class TempoFollowTxResult(
  val success: Boolean,
  val txHash: String? = null,
  val error: String? = null,
)

private enum class FollowAction {
  FOLLOW,
  UNFOLLOW,
}

object TempoFollowContractApi {
  private const val MIN_GAS_LIMIT = 300_000L
  private const val GAS_LIMIT_BUFFER = 120_000L
  private val ADDRESS_REGEX = Regex("^0x[0-9a-fA-F]{40}$")

  private val jsonType = "application/json".toMediaType()
  private val client = OkHttpClient()

  fun isConfigured(): Boolean = followContractOrNull() != null

  suspend fun follow(
    activity: FragmentActivity,
    account: TempoPasskeyManager.PasskeyAccount,
    targetAddress: String,
    rpId: String = account.rpId,
    sessionKey: SessionKeyManager.SessionKey? = null,
  ): TempoFollowTxResult = submit(
    activity = activity,
    account = account,
    targetAddress = targetAddress,
    rpId = rpId,
    sessionKey = sessionKey,
    action = FollowAction.FOLLOW,
  )

  suspend fun unfollow(
    activity: FragmentActivity,
    account: TempoPasskeyManager.PasskeyAccount,
    targetAddress: String,
    rpId: String = account.rpId,
    sessionKey: SessionKeyManager.SessionKey? = null,
  ): TempoFollowTxResult = submit(
    activity = activity,
    account = account,
    targetAddress = targetAddress,
    rpId = rpId,
    sessionKey = sessionKey,
    action = FollowAction.UNFOLLOW,
  )

  private suspend fun submit(
    activity: FragmentActivity,
    account: TempoPasskeyManager.PasskeyAccount,
    targetAddress: String,
    rpId: String,
    sessionKey: SessionKeyManager.SessionKey?,
    action: FollowAction,
  ): TempoFollowTxResult {
    return runCatching {
      val followContract = followContractOrNull()
        ?: throw IllegalStateException("Follow contract is not configured")
      val follower = normalizeAddress(account.address)
      val followee = normalizeAddress(targetAddress)
      if (follower.equals(followee, ignoreCase = true)) {
        throw IllegalStateException("Cannot follow yourself")
      }

      val chainId = withContext(Dispatchers.IO) { TempoClient.getChainId() }
      if (chainId != TempoClient.CHAIN_ID) {
        throw IllegalStateException("Wrong chain connected: $chainId (expected ${TempoClient.CHAIN_ID})")
      }

      val nonce = withContext(Dispatchers.IO) { TempoClient.getNonce(follower) }
      val fees = withContext(Dispatchers.IO) { TempoClient.getSuggestedFees() }
      val calldata = encodeCalldata(action = action, follower = follower, followee = followee)
      val gasLimit = withContext(Dispatchers.IO) {
        val estimated = estimateGas(from = follower, to = followContract, data = calldata)
        withBuffer(estimated = estimated, minimum = MIN_GAS_LIMIT)
      }

      val tx = TempoTransaction.UnsignedTx(
        nonce = nonce,
        maxPriorityFeePerGas = fees.maxPriorityFeePerGas,
        maxFeePerGas = fees.maxFeePerGas,
        feeMode = TempoTransaction.FeeMode.RELAY_SPONSORED,
        gasLimit = gasLimit,
        calls = listOf(
          TempoTransaction.Call(
            to = P256Utils.hexToBytes(followContract),
            value = 0,
            input = P256Utils.hexToBytes(calldata),
          ),
        ),
      )

      val txHash = withContext(Dispatchers.IO) {
        val signedTx = signTx(
          activity = activity,
          account = account,
          rpId = rpId,
          sessionKey = sessionKey,
          tx = tx,
        )
        TempoClient.sendSponsoredRawTransaction(
          signedTxHex = signedTx,
          senderAddress = follower,
        )
      }

      val receipt = withContext(Dispatchers.IO) { TempoClient.waitForTransactionReceipt(txHash) }
      if (!receipt.isSuccess) {
        throw IllegalStateException("Follow tx reverted on-chain: $txHash")
      }
      TempoFollowTxResult(success = true, txHash = txHash)
    }.getOrElse { err ->
      TempoFollowTxResult(success = false, error = err.message ?: "Follow tx failed")
    }
  }

  private suspend fun signTx(
    activity: FragmentActivity,
    account: TempoPasskeyManager.PasskeyAccount,
    rpId: String,
    sessionKey: SessionKeyManager.SessionKey?,
    tx: TempoTransaction.UnsignedTx,
  ): String {
    val sigHash = TempoTransaction.signatureHash(tx)
    return if (sessionKey != null) {
      val keychainSig = SessionKeyManager.signWithSessionKey(
        sessionKey = sessionKey,
        userAddress = account.address,
        txHash = sigHash,
      )
      TempoTransaction.encodeSignedSessionKey(tx, keychainSig)
    } else {
      val assertion = TempoPasskeyManager.sign(
        activity = activity,
        challenge = sigHash,
        account = account,
        rpId = rpId,
      )
      TempoTransaction.encodeSignedWebAuthn(tx, assertion)
    }
  }

  private fun encodeCalldata(
    action: FollowAction,
    follower: String,
    followee: String,
  ): String {
    val functionName = if (action == FollowAction.FOLLOW) "followFor" else "unfollowFor"
    val function = Function(
      functionName,
      listOf(Address(follower), Address(followee)),
      emptyList(),
    )
    return FunctionEncoder.encode(function)
  }

  private fun normalizeAddress(address: String): String {
    val trimmed = address.trim()
    val prefixed = if (trimmed.startsWith("0x", ignoreCase = true)) trimmed else "0x$trimmed"
    if (!ADDRESS_REGEX.matches(prefixed)) {
      throw IllegalArgumentException("Invalid address: $address")
    }
    return "0x${prefixed.removePrefix("0x").removePrefix("0X")}"
  }

  private fun followContractOrNull(): String? {
    val configured = BuildConfig.TEMPO_FOLLOW_V1.trim()
    if (!ADDRESS_REGEX.matches(configured)) return null
    return "0x${configured.removePrefix("0x").removePrefix("0X")}"
  }

  private fun estimateGas(
    from: String,
    to: String,
    data: String,
  ): Long {
    val payload = JSONObject()
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

    val req = Request.Builder()
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
