package sc.pirate.app.schedule

import sc.pirate.app.tempo.SessionKeyManager
import sc.pirate.app.tempo.TempoPasskeyManager

internal suspend fun executeCreatePlan(
  account: TempoPasskeyManager.PasskeyAccount,
  sessionKey: SessionKeyManager.SessionKey,
  editor: SlotEditorState,
  preview: SlotEditorPreview,
): EscrowTxResult {
  if (preview.createStartTimesMillis.isEmpty()) {
    return EscrowTxResult(success = true)
  }

  val firstStart = preview.createStartTimesMillis.first()
  val firstAttempt = TempoSessionEscrowApi.createSlotWithPrice(
    userAddress = account.address,
    sessionKey = sessionKey,
    startTimeSec = firstStart / 1_000L,
    durationMins = SLOT_DURATION_MINS,
    graceMins = 5,
    minOverlapMins = 10,
    cancelCutoffMins = 60,
    priceUsd = editor.priceUsd,
  )

  if (!firstAttempt.success) return firstAttempt

  var usedSelfPayFallback = firstAttempt.usedSelfPayFallback
  var lastHash = firstAttempt.txHash
  for (startMillis in preview.createStartTimesMillis.drop(1)) {
    val result = TempoSessionEscrowApi.createSlotWithPrice(
      userAddress = account.address,
      sessionKey = sessionKey,
      startTimeSec = startMillis / 1_000L,
      durationMins = SLOT_DURATION_MINS,
      graceMins = 5,
      minOverlapMins = 10,
      cancelCutoffMins = 60,
      priceUsd = editor.priceUsd,
    )
    if (!result.success) return result
    usedSelfPayFallback = usedSelfPayFallback || result.usedSelfPayFallback
    if (!result.txHash.isNullOrBlank()) lastHash = result.txHash
  }

  return EscrowTxResult(success = true, txHash = lastHash, usedSelfPayFallback = usedSelfPayFallback)
}

internal suspend fun executeCancelPlan(
  account: TempoPasskeyManager.PasskeyAccount,
  sessionKey: SessionKeyManager.SessionKey,
  preview: SlotEditorPreview,
): EscrowTxResult {
  var usedSelfPayFallback = false
  var lastHash: String? = null

  for (slotId in preview.cancelSlotIds) {
    val result = TempoSessionEscrowApi.cancelSlot(
      userAddress = account.address,
      sessionKey = sessionKey,
      slotId = slotId,
    )
    if (!result.success) return result
    usedSelfPayFallback = usedSelfPayFallback || result.usedSelfPayFallback
    if (!result.txHash.isNullOrBlank()) lastHash = result.txHash
  }

  return EscrowTxResult(success = true, txHash = lastHash, usedSelfPayFallback = usedSelfPayFallback)
}
