package sc.pirate.app.schedule

import android.content.Context

internal suspend fun executeCreatePlan(
  context: Context,
  userAddress: String,
  editor: SlotEditorState,
  preview: SlotEditorPreview,
): EscrowTxResult {
  if (preview.createStartTimesMillis.isEmpty()) {
    return EscrowTxResult(success = true)
  }

  var lastHash: String? = null
  for (startMillis in preview.createStartTimesMillis) {
    val result = SessionEscrowApi.createSlotWithPrice(
      context = context,
      userAddress = userAddress,
      startTimeSec = startMillis / 1_000L,
      durationMins = SLOT_DURATION_MINS,
      graceMins = 5,
      minOverlapMins = 10,
      cancelCutoffMins = 60,
      priceUsd = editor.priceUsd,
    )
    if (!result.success) return result
    if (!result.txHash.isNullOrBlank()) lastHash = result.txHash
  }

  return EscrowTxResult(success = true, txHash = lastHash)
}

internal suspend fun executeCancelPlan(
  context: Context,
  userAddress: String,
  preview: SlotEditorPreview,
): EscrowTxResult {
  var lastHash: String? = null

  for (slotId in preview.cancelSlotIds) {
    val result = SessionEscrowApi.cancelSlot(
      context = context,
      userAddress = userAddress,
      slotId = slotId,
    )
    if (!result.success) return result
    if (!result.txHash.isNullOrBlank()) lastHash = result.txHash
  }

  return EscrowTxResult(success = true, txHash = lastHash)
}
