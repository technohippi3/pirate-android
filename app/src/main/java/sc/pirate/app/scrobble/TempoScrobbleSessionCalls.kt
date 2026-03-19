package sc.pirate.app.scrobble

import sc.pirate.app.tempo.P256Utils
import sc.pirate.app.tempo.SessionKeyManager
import sc.pirate.app.tempo.TempoClient
import sc.pirate.app.tempo.TempoPasskeyManager
import sc.pirate.app.tempo.TempoTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class SubmissionOutcome(
  val txHash: String,
  val submittedFresh: Boolean,
)

internal data class SessionCallSubmission(
  val txHash: String,
)

private val scrobbleExpiringNonceKey = ByteArray(32) { 0xFF.toByte() } // TIP-1009 nonceKey=uint256.max
private const val scrobbleExpiryWindowSec = 25L
private const val maxUnderpricedRetries = 4
private const val retryDelayMs = 220L

internal suspend fun submitScrobbleSessionKeyContractCall(
  account: TempoPasskeyManager.PasskeyAccount,
  sessionKey: SessionKeyManager.SessionKey,
  callData: String,
  minimumGasLimit: Long,
): SessionCallSubmission {
  val gasLimit =
    withContext(Dispatchers.IO) {
      val estimated =
        estimateGas(
          from = account.address,
          to = TempoScrobbleApi.SCROBBLE_V4,
          data = callData,
        )
      withBuffer(estimated = estimated, minimum = minimumGasLimit)
    }

  val fees =
    withContext(Dispatchers.IO) {
      val suggested = TempoClient.getSuggestedFees()
      val floored = withRelayMinimumFeeFloor(suggested)
      withAddressBidFloor(account.address, floored)
    }

  fun buildTx(
    feeMode: TempoTransaction.FeeMode,
    txFees: TempoClient.Eip1559Fees,
  ): TempoTransaction.UnsignedTx =
    TempoTransaction.UnsignedTx(
      nonceKeyBytes = scrobbleExpiringNonceKey,
      nonce = 0L,
      validBeforeSec = nowSec() + scrobbleExpiryWindowSec,
      maxPriorityFeePerGas = txFees.maxPriorityFeePerGas,
      maxFeePerGas = txFees.maxFeePerGas,
      feeMode = feeMode,
      gasLimit = gasLimit,
      calls =
        listOf(
          TempoTransaction.Call(
            to = P256Utils.hexToBytes(TempoScrobbleApi.SCROBBLE_V4),
            value = 0,
            input = P256Utils.hexToBytes(callData),
          ),
        ),
    )

  suspend fun submitWithMode(
    feeMode: TempoTransaction.FeeMode,
    initialFees: TempoClient.Eip1559Fees,
  ): SubmissionOutcome {
    var feesForAttempt = withAddressBidFloor(account.address, initialFees)
    var lastUnderpriced: Throwable? = null

    repeat(maxUnderpricedRetries + 1) { attempt ->
      val tx = buildTx(feeMode, feesForAttempt)
      val sigHash = TempoTransaction.signatureHash(tx)
      val keychainSig =
        SessionKeyManager.signWithSessionKey(
          sessionKey = sessionKey,
          userAddress = account.address,
          txHash = sigHash,
        )
      val signedTxHex = TempoTransaction.encodeSignedSessionKey(tx, keychainSig)

      val result =
        withContext(Dispatchers.IO) {
          runCatching {
            when (feeMode) {
              TempoTransaction.FeeMode.RELAY_SPONSORED ->
                TempoClient.sendSponsoredRawTransaction(
                  signedTxHex = signedTxHex,
                  senderAddress = account.address,
                )
              TempoTransaction.FeeMode.SELF -> TempoClient.sendRawTransaction(signedTxHex)
            }
          }
        }
      val txHash = result.getOrNull()
      if (!txHash.isNullOrBlank()) {
        rememberAddressBidFloor(account.address, feesForAttempt)
        return SubmissionOutcome(txHash = txHash, submittedFresh = true)
      }

      val err = result.exceptionOrNull() ?: IllegalStateException("Unknown tx submission failure")
      if (!isReplacementUnderpriced(err)) throw err
      lastUnderpriced = err
      if (attempt >= maxUnderpricedRetries) return@repeat

      feesForAttempt = aggressivelyBumpFees(feesForAttempt)
      if (feeMode == TempoTransaction.FeeMode.RELAY_SPONSORED) {
        feesForAttempt = withRelayMinimumFeeFloor(feesForAttempt)
      }
      rememberAddressBidFloor(account.address, feesForAttempt)
      delay(retryDelayMs)
    }

    throw lastUnderpriced ?: IllegalStateException("replacement transaction underpriced")
  }

  val submission = submitWithMode(TempoTransaction.FeeMode.RELAY_SPONSORED, fees)

  val canonicalTxHash = submission.txHash
  if (!submission.submittedFresh) {
    throw IllegalStateException(
        "Scrobble submission not fresh at $canonicalTxHash; replacement rejected (underpriced)." +
          " gas=$gasLimit relay=${fees.maxPriorityFeePerGas}/${fees.maxFeePerGas}" +
          " self=disabled",
    )
  }
  return SessionCallSubmission(txHash = canonicalTxHash)
}
