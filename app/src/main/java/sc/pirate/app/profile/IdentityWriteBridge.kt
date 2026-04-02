package sc.pirate.app.profile

import androidx.fragment.app.FragmentActivity
import org.json.JSONObject
import sc.pirate.app.PirateChainConfig
import sc.pirate.app.auth.privy.PrivyRelayClient

data class IdentityNameRegistrationResult(
  val success: Boolean,
  val txHash: String? = null,
  val label: String? = null,
  val tld: String? = null,
  val fullName: String? = null,
  val node: String? = null,
  val tokenId: String? = null,
  val error: String? = null,
)

data class IdentityWriteResult(
  val success: Boolean,
  val txHash: String? = null,
  val error: String? = null,
)

interface PirateIdentityWriteBridge {
  suspend fun registerName(
    activity: FragmentActivity,
    ownerAddress: String,
    label: String,
    tld: String,
    durationSeconds: Long = 365L * 24L * 60L * 60L,
    bootstrapSessionKey: Boolean = false,
    preferSelfPay: Boolean = false,
  ): IdentityNameRegistrationResult

  suspend fun upsertProfile(
    activity: FragmentActivity,
    ownerAddress: String,
    profileInput: JSONObject,
  ): IdentityWriteResult

  suspend fun setTextRecords(
    activity: FragmentActivity,
    ownerAddress: String,
    node: String,
    keys: List<String>,
    values: List<String>,
  ): IdentityWriteResult

  suspend fun upsertXmtpInboxId(
    activity: FragmentActivity,
    ownerAddress: String,
    inboxId: String,
  ): IdentityWriteResult
}

object PrivyIdentityWriteBridge : PirateIdentityWriteBridge {
  override suspend fun registerName(
    activity: FragmentActivity,
    ownerAddress: String,
    label: String,
    tld: String,
    durationSeconds: Long,
    bootstrapSessionKey: Boolean,
    preferSelfPay: Boolean,
  ): IdentityNameRegistrationResult {
    return runCatching {
      require(ownerAddress.trim().isNotBlank()) { "Wallet address is required." }
      require(label.trim().isNotBlank()) { "Name label is empty." }
      require(durationSeconds > 0L) { "Invalid registration duration." }
      require(tld.trim().equals("pirate", ignoreCase = true)) {
        "Unsupported TLD: ${tld.trim().lowercase()}"
      }

      val quote =
        BaseNameRegistryApi.quotePirateRegistration(
          label = label,
          durationSeconds = durationSeconds,
        )
      val result = PrivyRelayClient.registerPirateName(context = activity.applicationContext, quote = quote)
      IdentityNameRegistrationResult(
        success = true,
        txHash = result.txHash,
        label = result.label,
        tld = result.tld,
        fullName = result.fullName,
        node = result.node,
        tokenId = result.node,
      )
    }.getOrElse { error ->
      IdentityNameRegistrationResult(
        success = false,
        error = sanitizeBridgeError(error.message ?: "Name registration failed."),
      )
    }
  }

  override suspend fun upsertProfile(
    activity: FragmentActivity,
    ownerAddress: String,
    profileInput: JSONObject,
  ): IdentityWriteResult {
    return runContractCall(
      activity = activity,
      ownerAddress = ownerAddress,
      to = PirateChainConfig.BASE_SEPOLIA_PROFILE_V2,
      data = BaseProfileContractCodec.encodeUpsertProfileCall(profileInput),
      intentType = "pirate.profile.upsert",
      intentArgs = JSONObject().put("ownerAddress", ownerAddress.trim()),
      fallbackError = "Profile update failed.",
    )
  }

  override suspend fun setTextRecords(
    activity: FragmentActivity,
    ownerAddress: String,
    node: String,
    keys: List<String>,
    values: List<String>,
  ): IdentityWriteResult {
    return runContractCall(
      activity = activity,
      ownerAddress = ownerAddress,
      to = PirateChainConfig.BASE_SEPOLIA_RECORDS_V1,
      data = PirateNameRecordsApi.encodeSetRecordsCallForRelay(node = node, keys = keys, values = values),
      intentType = "pirate.name.records",
      intentArgs =
        JSONObject()
          .put("ownerAddress", ownerAddress.trim())
          .put("node", node.trim())
          .put("recordCount", keys.size),
      fallbackError = "Identity records update failed.",
    )
  }

  override suspend fun upsertXmtpInboxId(
    activity: FragmentActivity,
    ownerAddress: String,
    inboxId: String,
  ): IdentityWriteResult {
    val normalizedInboxId = inboxId.trim()
    if (normalizedInboxId.isBlank()) {
      return IdentityWriteResult(success = false, error = "Missing XMTP inbox ID.")
    }
    val primary =
      PirateNameRecordsApi.getPrimaryNameDetails(ownerAddress)
        ?: return IdentityWriteResult(success = false, error = "Primary name required to publish XMTP inbox ID.")
    return setTextRecords(
      activity = activity,
      ownerAddress = ownerAddress,
      node = PirateNameRecordsApi.computeNode(primary.fullName),
      keys = listOf(PirateNameRecordsApi.XMTP_INBOX_ID_RECORD_KEY),
      values = listOf(normalizedInboxId),
    )
  }

  private suspend fun runContractCall(
    activity: FragmentActivity,
    ownerAddress: String,
    to: String,
    data: String,
    intentType: String,
    intentArgs: JSONObject,
    fallbackError: String,
  ): IdentityWriteResult {
    val normalizedOwnerAddress = ownerAddress.trim()
    if (normalizedOwnerAddress.isBlank()) {
      return IdentityWriteResult(success = false, error = "Wallet address is required.")
    }
    return runCatching {
      val txHash =
        PrivyRelayClient.submitContractCall(
          context = activity.applicationContext,
          chainId = PirateChainConfig.BASE_SEPOLIA_CHAIN_ID,
          to = to,
          data = data,
          intentType = intentType,
          intentArgs = intentArgs,
        )
      IdentityWriteResult(success = true, txHash = txHash)
    }.getOrElse { error ->
      IdentityWriteResult(
        success = false,
        error = sanitizeBridgeError(error.message ?: fallbackError),
      )
    }
  }

  private fun sanitizeBridgeError(error: String): String =
    error
      .replace("publish signing", "wallet approval")
}
