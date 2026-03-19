package sc.pirate.app.onboarding

import android.content.Context
import android.util.Log
import sc.pirate.app.profile.TempoNameRecordsApi
import sc.pirate.app.tempo.ContentKeyManager
import sc.pirate.app.tempo.TempoClient
import sc.pirate.app.tempo.TempoPasskeyManager
import sc.pirate.app.tempo.TempoSessionKeyApi
import sc.pirate.app.tempo.SessionKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "OnboardingSession"

internal enum class SessionSetupStatus {
  CHECKING,
  AUTHORIZING,
  SIGNATURE_1,
  SIGNATURE_2,
  FINALIZING,
  READY,
  FAILED,
}

internal data class OnboardingSessionResult(
  val sessionKey: SessionKeyManager.SessionKey?,
  val status: SessionSetupStatus,
  val error: String?,
)

internal fun isUsableOnboardingSessionKey(
  sessionKey: SessionKeyManager.SessionKey?,
  ownerAddress: String,
): Boolean {
  return SessionKeyManager.isValid(sessionKey, ownerAddress = ownerAddress)
}

internal fun resolveKnownOnboardingSessionKey(
  account: TempoPasskeyManager.PasskeyAccount,
  hostActivity: androidx.fragment.app.FragmentActivity,
  currentSessionKey: SessionKeyManager.SessionKey?,
): SessionKeyManager.SessionKey? {
  val active = currentSessionKey?.takeIf { isUsableOnboardingSessionKey(it, ownerAddress = account.address) }
  if (active != null) {
    Log.d(TAG, "Using in-memory onboarding session key for ${account.address}")
    return active
  }

  val loaded = SessionKeyManager.load(hostActivity)
  if (isUsableOnboardingSessionKey(loaded, ownerAddress = account.address)) {
    Log.d(
      TAG,
      "Reusing stored onboarding session key for ${account.address}, expiresAt=${loaded?.expiresAt}",
    )
    return loaded
  }

  if (loaded != null) {
    Log.d(
      TAG,
      "Stored session key not usable for ${account.address}: owner=${loaded.ownerAddress} expiresAt=${loaded.expiresAt}",
    )
  } else {
    Log.d(TAG, "No stored session key found for ${account.address}")
  }
  // Do not clear shared session storage on owner mismatch; account selection can
  // change during app startup and we can otherwise wipe a valid key.
  return null
}

internal suspend fun ensureOnboardingSessionKey(
  activity: androidx.fragment.app.FragmentActivity?,
  account: TempoPasskeyManager.PasskeyAccount?,
  currentSessionKey: SessionKeyManager.SessionKey?,
  forceRefresh: Boolean = false,
  onProgress: ((SessionSetupStatus) -> Unit)? = null,
): OnboardingSessionResult {
  if (activity == null || account == null) {
    return OnboardingSessionResult(
      sessionKey = null,
      status = SessionSetupStatus.FAILED,
      error =
        when {
          account != null -> "Missing activity for Tempo transaction signing."
          else -> "Tempo account required for onboarding."
        },
    )
  }

  if (!forceRefresh) {
    val active =
      resolveKnownOnboardingSessionKey(
        account = account,
        hostActivity = activity,
        currentSessionKey = currentSessionKey,
      )
    if (active != null) {
      Log.d(TAG, "Onboarding session key already ready for ${account.address}")
      return OnboardingSessionResult(
        sessionKey = active,
        status = SessionSetupStatus.READY,
        error = null,
      )
    }
  } else {
    SessionKeyManager.clear(activity)
  }

  Log.d(TAG, "Authorizing new onboarding session key for ${account.address} (forceRefresh=$forceRefresh)")
  val authResult =
    TempoSessionKeyApi.authorizeSessionKey(
      activity = activity,
      account = account,
      rpId = account.rpId,
      onProgress = { stage ->
        val status = when (stage) {
          sc.pirate.app.tempo.SessionKeyAuthorizationProgress.SIGNATURE_1 -> SessionSetupStatus.SIGNATURE_1
          sc.pirate.app.tempo.SessionKeyAuthorizationProgress.SIGNATURE_2 -> SessionSetupStatus.SIGNATURE_2
          sc.pirate.app.tempo.SessionKeyAuthorizationProgress.FINALIZING -> SessionSetupStatus.FINALIZING
        }
        onProgress?.invoke(status)
      },
    )
  if (!authResult.success) {
    Log.w(TAG, "Onboarding session key authorization failed: ${authResult.error}")
    return OnboardingSessionResult(
      sessionKey = null,
      status = SessionSetupStatus.FAILED,
      error = authResult.error ?: "Session key authorization failed.",
    )
  }

  val authorized = authResult.sessionKey
  if (!isUsableOnboardingSessionKey(authorized, ownerAddress = account.address)) {
    Log.w(TAG, "Onboarding session key authorization returned invalid key")
    return OnboardingSessionResult(
      sessionKey = null,
      status = SessionSetupStatus.FAILED,
      error = "Session key authorization returned an invalid key.",
    )
  }

  Log.d(TAG, "Onboarding session key authorized for ${account.address} tx=${authResult.txHash}")
  return OnboardingSessionResult(
    sessionKey = authorized,
    status = SessionSetupStatus.READY,
    error = null,
  )
}

internal fun resolveOnboardingSessionKeyForWrites(
  activity: androidx.fragment.app.FragmentActivity?,
  account: TempoPasskeyManager.PasskeyAccount,
  currentSessionKey: SessionKeyManager.SessionKey?,
): OnboardingSessionResult {
  val hostActivity =
    activity
      ?: return OnboardingSessionResult(
        sessionKey = null,
        status = SessionSetupStatus.FAILED,
        error = "Missing activity for Tempo transaction signing.",
      )

  val active =
    resolveKnownOnboardingSessionKey(
      account = account,
      hostActivity = hostActivity,
      currentSessionKey = currentSessionKey,
    )
  if (active != null) {
    Log.d(TAG, "Resolved onboarding write session key for ${account.address}")
    return OnboardingSessionResult(
      sessionKey = active,
      status = SessionSetupStatus.READY,
      error = null,
    )
  }

  Log.w(TAG, "Session key unavailable for onboarding writes: ${account.address}")
  return OnboardingSessionResult(
    sessionKey = null,
    status = SessionSetupStatus.FAILED,
    error = "Session key unavailable. Retry setup to continue onboarding.",
  )
}

internal suspend fun ensureOnboardingContentPubKeyPublished(
  context: Context,
  activity: androidx.fragment.app.FragmentActivity,
  account: TempoPasskeyManager.PasskeyAccount,
  sessionKey: SessionKeyManager.SessionKey,
  claimedName: String,
  claimedTld: String,
): String? {
  val contentPubKey = withContext(Dispatchers.IO) { ContentKeyManager.getOrCreate(context).publicKey }
  val targetName =
    when {
      claimedName.isNotBlank() -> "$claimedName.$claimedTld"
      else -> TempoNameRecordsApi.getPrimaryName(account.address)
    }
  val node =
    targetName?.let { runCatching { TempoNameRecordsApi.computeNode(it) }.getOrNull() }
      ?: return "Primary name required to publish content encryption key."
  Log.d(TAG, "ensureContentPubKey: name=$targetName node=$node address=${account.address}")

  // Debug: check on-chain authorization before attempting write
  withContext(Dispatchers.IO) {
    runCatching {
      val isAuth = TempoNameRecordsApi.isAuthorized(node, account.address)
      Log.d(TAG, "ensureContentPubKey: isAuthorized($node, ${account.address}) = $isAuth")
    }.onFailure { Log.w(TAG, "ensureContentPubKey: isAuthorized check failed: ${it.message}") }
  }

  val existing =
    TempoNameRecordsApi.decodeContentPubKey(
      TempoNameRecordsApi.getTextRecord(node, TempoNameRecordsApi.CONTENT_PUBKEY_RECORD_KEY),
    )
  if (existing != null && existing.contentEquals(contentPubKey)) {
    return null
  }

  val publishResult =
    TempoNameRecordsApi.setTextRecords(
      activity = activity,
      account = account,
      node = node,
      keys = listOf(TempoNameRecordsApi.CONTENT_PUBKEY_RECORD_KEY),
      values = listOf(TempoNameRecordsApi.encodeContentPubKey(contentPubKey)),
      rpId = account.rpId,
      sessionKey = sessionKey,
    )
  if (!publishResult.success) {
    return publishResult.error ?: "Failed to publish content encryption key."
  }

  // Wait for TX receipt before polling state — the TX hash is returned immediately
  // but the state isn't updated until the TX is mined.
  val txHash = publishResult.txHash
  if (!txHash.isNullOrBlank()) {
    var receiptConfirmed = false
    for (attempt in 0 until 30) {
      val receipt =
        withContext(Dispatchers.IO) {
          runCatching { TempoClient.getTransactionReceipt(txHash) }.getOrNull()
        }
      if (receipt != null) {
        if (!receipt.isSuccess) {
          Log.e(TAG, "contentPubKey TX reverted: txHash=$txHash status=${receipt.statusHex}")
          return "Content encryption key TX reverted (status ${receipt.statusHex}). Check session key permissions."
        }
        Log.d(TAG, "contentPubKey TX confirmed: txHash=$txHash")
        receiptConfirmed = true
        break
      }
      if (attempt < 29) delay(1000)
    }
    if (!receiptConfirmed) {
      Log.w(TAG, "contentPubKey TX receipt not found after 30s: txHash=$txHash")
      return "Content encryption key TX not confirmed after 30s. Please try again."
    }
  }

  // Now verify the on-chain state matches
  repeat(6) { attempt ->
    val stored =
      TempoNameRecordsApi.decodeContentPubKey(
        TempoNameRecordsApi.getTextRecord(node, TempoNameRecordsApi.CONTENT_PUBKEY_RECORD_KEY),
      )
    if (stored != null && stored.contentEquals(contentPubKey)) {
      return null
    }
    if (attempt < 5) delay(1000)
  }
  return "Content encryption key is still confirming on-chain. Please try again."
}
