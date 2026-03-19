package sc.pirate.app.scrobble

import android.app.Activity
import sc.pirate.app.tempo.P256Utils
import sc.pirate.app.tempo.TempoClient
import sc.pirate.app.tempo.TempoPasskeyManager
import sc.pirate.app.tempo.TempoTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val scrobblePasskeyExpiringNonceKey = ByteArray(32) { 0xFF.toByte() } // TIP-1009 nonceKey=uint256.max
private const val scrobblePasskeyExpiryWindowSec = 25L
private const val passkeyMaxUnderpricedRetries = 4
private const val passkeyRetryDelayMs = 220L

internal suspend fun submitScrobblePasskeyContractCall(
  activity: Activity,
  account: TempoPasskeyManager.PasskeyAccount,
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
      nonceKeyBytes = scrobblePasskeyExpiringNonceKey,
      nonce = 0L,
      validBeforeSec = nowSec() + scrobblePasskeyExpiryWindowSec,
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
  ): String {
    var feesForAttempt =
      when (feeMode) {
        TempoTransaction.FeeMode.RELAY_SPONSORED ->
          withAddressBidFloor(account.address, withRelayMinimumFeeFloor(initialFees))
        TempoTransaction.FeeMode.SELF ->
          withAddressBidFloor(account.address, initialFees)
      }
    var lastUnderpriced: Throwable? = null

    repeat(passkeyMaxUnderpricedRetries + 1) { attempt ->
      val tx = buildTx(feeMode, feesForAttempt)
      val sigHash = TempoTransaction.signatureHash(tx)
      val assertion =
        TempoPasskeyManager.sign(
          activity = activity,
          challenge = sigHash,
          account = account,
          rpId = account.rpId,
        )
      val signedTxHex = TempoTransaction.encodeSignedWebAuthn(tx, assertion)

      val result =
        withContext(Dispatchers.IO) {
          runCatching {
            when (feeMode) {
              TempoTransaction.FeeMode.RELAY_SPONSORED ->
                TempoClient.sendSponsoredRawTransaction(
                  signedTxHex = signedTxHex,
                  senderAddress = account.address,
                )
              TempoTransaction.FeeMode.SELF ->
                TempoClient.sendRawTransaction(signedTxHex)
            }
          }
        }

      val txHash = result.getOrNull()
      if (!txHash.isNullOrBlank()) {
        rememberAddressBidFloor(account.address, feesForAttempt)
        return txHash
      }

      val error = result.exceptionOrNull() ?: IllegalStateException("Unknown tx submission failure")
      if (!isReplacementUnderpriced(error) || attempt >= passkeyMaxUnderpricedRetries) {
        throw error
      }
      lastUnderpriced = error
      feesForAttempt = aggressivelyBumpFees(feesForAttempt)
      feesForAttempt =
        when (feeMode) {
          TempoTransaction.FeeMode.RELAY_SPONSORED ->
            withAddressBidFloor(account.address, withRelayMinimumFeeFloor(feesForAttempt))
          TempoTransaction.FeeMode.SELF ->
            withAddressBidFloor(account.address, feesForAttempt)
        }
      rememberAddressBidFloor(account.address, feesForAttempt)
      delay(passkeyRetryDelayMs)
    }

    throw (lastUnderpriced ?: IllegalStateException("replacement transaction underpriced"))
  }

  val txHash = submitWithMode(TempoTransaction.FeeMode.RELAY_SPONSORED, fees)

  return SessionCallSubmission(
    txHash = txHash,
  )
}
