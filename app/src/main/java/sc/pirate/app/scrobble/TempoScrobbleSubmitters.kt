package sc.pirate.app.scrobble

import android.app.Activity
import sc.pirate.app.music.TrackIds
import sc.pirate.app.tempo.P256Utils
import sc.pirate.app.tempo.SessionKeyManager
import sc.pirate.app.tempo.TempoClient
import sc.pirate.app.tempo.TempoPasskeyManager
import sc.pirate.app.tempo.TempoTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal suspend fun submitScrobbleWithPasskeyInternal(
  activity: Activity,
  account: TempoPasskeyManager.PasskeyAccount,
  input: TempoScrobbleInput,
  contractAddress: String,
  gasLimitScrobbleOnly: Long,
  gasLimitRegisterAndScrobble: Long,
  maxUnderpricedRetries: Int,
  retryDelayMs: Long,
  expiringNonceKey: ByteArray,
  expiryWindowSec: Long,
): TempoScrobbleSubmitResult {
  val safeTitle = truncateUtf8(input.title.trim(), maxBytes = 128)
  val safeArtist = truncateUtf8(input.artist.trim(), maxBytes = 128)
  val safeAlbum = truncateUtf8(input.album.orEmpty().trim(), maxBytes = 128)

  if (safeTitle.isBlank() || safeArtist.isBlank()) {
    return TempoScrobbleSubmitResult(success = false, error = "title/artist required")
  }

  return runCatching {
    val chainId = withContext(Dispatchers.IO) { TempoClient.getChainId() }
    if (chainId != TempoClient.CHAIN_ID) {
      throw IllegalStateException("Wrong chain connected: $chainId (expected ${TempoClient.CHAIN_ID})")
    }

    val parts = TrackIds.computeScrobbleMetaParts(safeTitle, safeArtist, safeAlbum)
    val trackIdHex = "0x${P256Utils.bytesToHex(parts.trackId)}"
    val isRegistered = withContext(Dispatchers.IO) { isTrackRegistered(trackIdHex) }

    val callData =
      if (isRegistered) {
        encodeScrobbleBatch(
          user = account.address,
          trackIds = listOf(trackIdHex),
          timestamps = listOf(input.playedAtSec.coerceAtLeast(0L)),
        )
      } else {
        encodeRegisterAndScrobbleBatch(
          user = account.address,
          kind = parts.kind,
          payloadBytes32 = parts.payload,
          title = safeTitle,
          artist = safeArtist,
          album = safeAlbum,
          durationSec = input.durationSec.coerceAtLeast(0),
          trackId = trackIdHex,
          timestamp = input.playedAtSec.coerceAtLeast(0L),
        )
      }

    val gasLimit =
      withContext(Dispatchers.IO) {
        val estimated =
          estimateGas(
            from = account.address,
            to = contractAddress,
            data = callData,
          )
        val minLimit = if (isRegistered) gasLimitScrobbleOnly else gasLimitRegisterAndScrobble
        withBuffer(estimated = estimated, minimum = minLimit)
      }

    var fees =
      withContext(Dispatchers.IO) {
        val suggested = TempoClient.getSuggestedFees()
        withAddressBidFloor(account.address, withRelayMinimumFeeFloor(suggested))
      }

    var txHash: String? = null
    var lastError: Throwable? = null
    for (attempt in 0..maxUnderpricedRetries) {
      val tx =
        TempoTransaction.UnsignedTx(
          nonceKeyBytes = expiringNonceKey,
          nonce = 0L,
          validBeforeSec = nowSec() + expiryWindowSec,
          maxPriorityFeePerGas = fees.maxPriorityFeePerGas,
          maxFeePerGas = fees.maxFeePerGas,
          feeMode = TempoTransaction.FeeMode.RELAY_SPONSORED,
          gasLimit = gasLimit,
          calls =
            listOf(
              TempoTransaction.Call(
                to = P256Utils.hexToBytes(contractAddress),
                value = 0,
                input = P256Utils.hexToBytes(callData),
              ),
            ),
        )

      val sigHash = TempoTransaction.signatureHash(tx)
      val assertion =
        TempoPasskeyManager.sign(
          activity = activity,
          challenge = sigHash,
          account = account,
          rpId = account.rpId,
        )
      val signedTxHex = TempoTransaction.encodeSignedWebAuthn(tx, assertion)

      val submitted =
        withContext(Dispatchers.IO) {
          runCatching {
            TempoClient.sendSponsoredRawTransaction(
              signedTxHex = signedTxHex,
              senderAddress = account.address,
            )
          }
        }
      val hash = submitted.getOrNull()
      if (!hash.isNullOrBlank()) {
        txHash = hash
        rememberAddressBidFloor(account.address, fees)
        break
      }
      val err = submitted.exceptionOrNull() ?: IllegalStateException("Unknown tx submission failure")
      lastError = err
      if (!isReplacementUnderpriced(err)) throw err
      if (attempt >= maxUnderpricedRetries) break
      fees = withAddressBidFloor(account.address, withRelayMinimumFeeFloor(aggressivelyBumpFees(fees)))
      rememberAddressBidFloor(account.address, fees)
      delay(retryDelayMs)
    }

    val canonicalTxHash = txHash ?: throw (lastError ?: IllegalStateException("Tempo scrobble tx failed"))
    val receipt = awaitScrobbleReceipt(canonicalTxHash)
    if (!receipt.isSuccess) {
      throw IllegalStateException("Scrobble tx reverted on-chain: ${receipt.txHash}")
    }

    TempoScrobbleSubmitResult(
      success = true,
      txHash = canonicalTxHash,
      trackId = trackIdHex,
      usedRegisterPath = !isRegistered,
      pendingConfirmation = false,
    )
  }.getOrElse { err ->
    TempoScrobbleSubmitResult(
      success = false,
      error = err.message ?: "Tempo scrobble tx failed",
    )
  }
}

internal suspend fun submitScrobbleWithSessionKeyInternal(
  account: TempoPasskeyManager.PasskeyAccount,
  sessionKey: SessionKeyManager.SessionKey,
  input: TempoScrobbleInput,
  gasLimitScrobbleOnly: Long,
  gasLimitRegisterAndScrobble: Long,
): TempoScrobbleSubmitResult {
  val safeTitle = truncateUtf8(input.title.trim(), maxBytes = 128)
  val safeArtist = truncateUtf8(input.artist.trim(), maxBytes = 128)
  val safeAlbum = truncateUtf8(input.album.orEmpty().trim(), maxBytes = 128)

  if (safeTitle.isBlank() || safeArtist.isBlank()) {
    return TempoScrobbleSubmitResult(success = false, error = "title/artist required")
  }

  return runCatching {
    val chainId = withContext(Dispatchers.IO) { TempoClient.getChainId() }
    if (chainId != TempoClient.CHAIN_ID) {
      throw IllegalStateException("Wrong chain connected: $chainId (expected ${TempoClient.CHAIN_ID})")
    }

    val parts = TrackIds.computeScrobbleMetaParts(safeTitle, safeArtist, safeAlbum)
    val trackIdHex = "0x${P256Utils.bytesToHex(parts.trackId)}"
    val isRegistered = withContext(Dispatchers.IO) { isTrackRegistered(trackIdHex) }

    val callData =
      if (isRegistered) {
        encodeScrobbleBatch(
          user = account.address,
          trackIds = listOf(trackIdHex),
          timestamps = listOf(input.playedAtSec.coerceAtLeast(0L)),
        )
      } else {
        encodeRegisterAndScrobbleBatch(
          user = account.address,
          kind = parts.kind,
          payloadBytes32 = parts.payload,
          title = safeTitle,
          artist = safeArtist,
          album = safeAlbum,
          durationSec = input.durationSec.coerceAtLeast(0),
          trackId = trackIdHex,
          timestamp = input.playedAtSec.coerceAtLeast(0L),
        )
      }

    val minimumGasLimit = if (isRegistered) gasLimitScrobbleOnly else gasLimitRegisterAndScrobble
    val submission =
      submitScrobbleSessionKeyContractCall(
        account = account,
        sessionKey = sessionKey,
        callData = callData,
        minimumGasLimit = minimumGasLimit,
      )

    val canonicalTxHash = submission.txHash
    val receipt = awaitScrobbleReceipt(canonicalTxHash)
    if (!receipt.isSuccess) {
      throw IllegalStateException("Tempo scrobble tx reverted on-chain: ${receipt.txHash}")
    }
    TempoScrobbleSubmitResult(
      success = true,
      txHash = canonicalTxHash,
      trackId = trackIdHex,
      usedRegisterPath = !isRegistered,
      pendingConfirmation = false,
    )
  }.getOrElse { err ->
    TempoScrobbleSubmitResult(
      success = false,
      error = err.message ?: "Tempo scrobble tx failed",
    )
  }
}
