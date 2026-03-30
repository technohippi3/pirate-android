package sc.pirate.app.profile

import androidx.fragment.app.FragmentActivity
import org.json.JSONObject
import sc.pirate.app.auth.LegacySignerAccountStore
import sc.pirate.app.tempo.SessionKeyManager
import sc.pirate.app.tempo.TempoPasskeyManager

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

/**
 * Temporary identity write bridge while Android still depends on the legacy passkey/session-key
 * submission path. This is the seam that should be swapped to Privy relay or native wallet
 * signing once the Android auth migration is complete.
 */
object LegacyIdentityWriteBridge : PirateIdentityWriteBridge {
  override suspend fun registerName(
    activity: FragmentActivity,
    ownerAddress: String,
    label: String,
    tld: String,
    durationSeconds: Long,
    bootstrapSessionKey: Boolean,
    preferSelfPay: Boolean,
  ): IdentityNameRegistrationResult {
    val account =
      loadLegacyAccount(activity = activity, ownerAddress = ownerAddress)
        ?: return IdentityNameRegistrationResult(
          success = false,
          error = "Signing account unavailable for this wallet.",
        )
    return TempoNameRegistryApi.register(
      activity = activity,
      account = account,
      label = label,
      tld = tld,
      durationSeconds = durationSeconds,
      rpId = account.rpId,
      sessionKey = loadLegacySessionKey(activity = activity, ownerAddress = account.address),
      bootstrapSessionKey = bootstrapSessionKey,
      preferSelfPay = preferSelfPay,
    ).toIdentityNameRegistrationResult()
  }

  override suspend fun upsertProfile(
    activity: FragmentActivity,
    ownerAddress: String,
    profileInput: JSONObject,
  ): IdentityWriteResult {
    val account =
      loadLegacyAccount(activity = activity, ownerAddress = ownerAddress)
        ?: return IdentityWriteResult(
          success = false,
          error = "Signing account unavailable for this wallet.",
        )
    return TempoProfileContractApi.upsertProfile(
      activity = activity,
      account = account,
      profileInput = profileInput,
      rpId = account.rpId,
      sessionKey = loadLegacySessionKey(activity = activity, ownerAddress = account.address),
    ).toIdentityWriteResult()
  }

  override suspend fun setTextRecords(
    activity: FragmentActivity,
    ownerAddress: String,
    node: String,
    keys: List<String>,
    values: List<String>,
  ): IdentityWriteResult {
    val account =
      loadLegacyAccount(activity = activity, ownerAddress = ownerAddress)
        ?: return IdentityWriteResult(
          success = false,
          error = "Signing account unavailable for this wallet.",
        )
    return TempoNameRecordsApi.setTextRecords(
      activity = activity,
      account = account,
      node = node,
      keys = keys,
      values = values,
      rpId = account.rpId,
      sessionKey = loadLegacySessionKey(activity = activity, ownerAddress = account.address),
    ).toIdentityWriteResult()
  }

  override suspend fun upsertXmtpInboxId(
    activity: FragmentActivity,
    ownerAddress: String,
    inboxId: String,
  ): IdentityWriteResult {
    val account =
      loadLegacyAccount(activity = activity, ownerAddress = ownerAddress)
        ?: return IdentityWriteResult(
          success = false,
          error = "Signing account unavailable for this wallet.",
        )
    return TempoNameRecordsApi.upsertXmtpInboxId(
      activity = activity,
      account = account,
      inboxId = inboxId,
      rpId = account.rpId,
      sessionKey = loadLegacySessionKey(activity = activity, ownerAddress = account.address),
    ).toIdentityWriteResult()
  }

  private fun loadLegacyAccount(
    activity: FragmentActivity,
    ownerAddress: String,
  ): TempoPasskeyManager.PasskeyAccount? {
    val normalizedOwnerAddress = ownerAddress.trim()
    if (normalizedOwnerAddress.isBlank()) return null
    return LegacySignerAccountStore.loadAccount(
      context = activity.applicationContext,
      ownerAddress = normalizedOwnerAddress,
    )
  }

  private fun loadLegacySessionKey(
    activity: FragmentActivity,
    ownerAddress: String,
  ): SessionKeyManager.SessionKey? {
    val loaded = SessionKeyManager.load(activity)
    return loaded?.takeIf {
      SessionKeyManager.isValid(it, ownerAddress = ownerAddress) &&
        it.keyAuthorization?.isNotEmpty() == true
    }
  }

  private fun TempoNameRegisterResult.toIdentityNameRegistrationResult(): IdentityNameRegistrationResult =
    IdentityNameRegistrationResult(
      success = success,
      txHash = txHash,
      label = label,
      tld = tld,
      fullName = fullName,
      node = node,
      tokenId = tokenId,
      error = sanitizeLegacyError(error),
    )

  private fun TempoProfileUpsertResult.toIdentityWriteResult(): IdentityWriteResult =
    IdentityWriteResult(
      success = success,
      txHash = txHash,
      error = sanitizeLegacyError(error),
    )

  private fun TempoRecordsWriteResult.toIdentityWriteResult(): IdentityWriteResult =
    IdentityWriteResult(
      success = success,
      txHash = txHash,
      error = sanitizeLegacyError(error),
    )

  private fun sanitizeLegacyError(error: String?): String? {
    val raw = error ?: return null
    return when (raw) {
      "Tempo profile tx failed" -> "Profile update failed."
      "Tempo records tx failed" -> "Identity records update failed."
      else ->
        raw
          .replace("Tempo account", "signing account")
          .replace("Tempo transaction signing", "wallet approval")
          .replace("Tempo passkey account", "signing account")
          .replace("Tempo publish signing", "wallet approval")
    }
  }
}
