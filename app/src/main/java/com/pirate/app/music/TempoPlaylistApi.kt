package com.pirate.app.music

import android.util.Log
import com.pirate.app.tempo.P256Utils
import com.pirate.app.tempo.SessionKeyManager
import com.pirate.app.tempo.TempoClient
import com.pirate.app.tempo.TempoPasskeyManager
import com.pirate.app.tempo.TempoTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class TempoPlaylistTxResult(
  val success: Boolean,
  val txHash: String? = null,
  val playlistId: String? = null,
  val error: String? = null,
)

object TempoPlaylistApi {
  private const val TAG = "TempoPlaylistApi"
  const val PLAYLIST_V1 = "0xc8Eb0596daa842FF7A799C93Df4d3cEAB9B19eAb"

  private const val GAS_LIMIT_CREATE_MIN = 800_000L
  private const val GAS_LIMIT_SET_TRACKS_MIN = 100_000L
  private const val GAS_LIMIT_UPDATE_META_MIN = 100_000L
  private const val GAS_LIMIT_DELETE_MIN = 100_000L

  suspend fun createPlaylist(
    account: TempoPasskeyManager.PasskeyAccount,
    sessionKey: SessionKeyManager.SessionKey,
    name: String,
    coverCid: String,
    visibility: Int,
    trackIds: List<String>,
  ): TempoPlaylistTxResult {
    val safeName = truncateUtf8(name.trim(), maxBytes = 64)
    if (safeName.isBlank()) {
      return TempoPlaylistTxResult(success = false, error = "Playlist name is required")
    }

    val normalizedTracks =
      runCatching { normalizeTrackIds(trackIds) }
        .getOrElse { error -> return TempoPlaylistTxResult(success = false, error = error.message) }

    val data =
      encodeCreatePlaylist(
        name = safeName,
        coverCid = truncateUtf8(coverCid.trim(), maxBytes = 128),
        visibility = visibility,
        trackIds = normalizedTracks,
      )

    return submitContractCall(
      account = account,
      sessionKey = sessionKey,
      contract = PLAYLIST_V1,
      callData = data,
      minimumGasLimit = GAS_LIMIT_CREATE_MIN,
      opLabel = "create playlist",
      parsePlaylistId = true,
    )
  }

  suspend fun setTracks(
    account: TempoPasskeyManager.PasskeyAccount,
    sessionKey: SessionKeyManager.SessionKey,
    playlistId: String,
    expectedVersion: Int,
    trackIds: List<String>,
  ): TempoPlaylistTxResult {
    val pid =
      runCatching { normalizeBytes32(playlistId, "playlistId") }
        .getOrElse { error -> return TempoPlaylistTxResult(success = false, error = error.message) }
    if (expectedVersion <= 0) {
      return TempoPlaylistTxResult(success = false, error = "Invalid expected playlist version")
    }
    val normalizedTracks =
      runCatching { normalizeTrackIds(trackIds) }
        .getOrElse { error -> return TempoPlaylistTxResult(success = false, error = error.message) }

    val data =
      encodeSetTracks(
        playlistId = pid,
        expectedVersion = expectedVersion,
        trackIds = normalizedTracks,
      )
    return submitContractCall(
      account = account,
      sessionKey = sessionKey,
      contract = PLAYLIST_V1,
      callData = data,
      minimumGasLimit = GAS_LIMIT_SET_TRACKS_MIN,
      opLabel = "set tracks",
    )
  }

  suspend fun updateMeta(
    account: TempoPasskeyManager.PasskeyAccount,
    sessionKey: SessionKeyManager.SessionKey,
    playlistId: String,
    name: String,
    coverCid: String,
    visibility: Int,
  ): TempoPlaylistTxResult {
    val pid =
      runCatching { normalizeBytes32(playlistId, "playlistId") }
        .getOrElse { error -> return TempoPlaylistTxResult(success = false, error = error.message) }
    val safeName = truncateUtf8(name.trim(), maxBytes = 64)
    if (safeName.isBlank()) {
      return TempoPlaylistTxResult(success = false, error = "Playlist name is required")
    }

    val data =
      encodeUpdateMeta(
        playlistId = pid,
        name = safeName,
        coverCid = truncateUtf8(coverCid.trim(), maxBytes = 128),
        visibility = visibility,
      )

    return submitContractCall(
      account = account,
      sessionKey = sessionKey,
      contract = PLAYLIST_V1,
      callData = data,
      minimumGasLimit = GAS_LIMIT_UPDATE_META_MIN,
      opLabel = "update playlist meta",
    )
  }

  suspend fun deletePlaylist(
    account: TempoPasskeyManager.PasskeyAccount,
    sessionKey: SessionKeyManager.SessionKey,
    playlistId: String,
  ): TempoPlaylistTxResult {
    val pid =
      runCatching { normalizeBytes32(playlistId, "playlistId") }
        .getOrElse { error -> return TempoPlaylistTxResult(success = false, error = error.message) }
    val data = encodeDeletePlaylist(pid)

    return submitContractCall(
      account = account,
      sessionKey = sessionKey,
      contract = PLAYLIST_V1,
      callData = data,
      minimumGasLimit = GAS_LIMIT_DELETE_MIN,
      opLabel = "delete playlist",
    )
  }

  private suspend fun submitContractCall(
    account: TempoPasskeyManager.PasskeyAccount,
    sessionKey: SessionKeyManager.SessionKey,
    contract: String,
    callData: String,
    minimumGasLimit: Long,
    opLabel: String,
    parsePlaylistId: Boolean = false,
  ): TempoPlaylistTxResult {
    return runCatching {
      if (!SessionKeyManager.isValid(sessionKey, ownerAddress = account.address)) {
        throw IllegalStateException("Missing valid Tempo session key. Please sign in again.")
      }

      val chainId = withContext(Dispatchers.IO) { TempoClient.getChainId() }
      if (chainId != TempoClient.CHAIN_ID) {
        throw IllegalStateException("Wrong chain connected: $chainId (expected ${TempoClient.CHAIN_ID})")
      }

      val normalizedContract = normalizeAddress(contract)
      val gasLimit =
        withContext(Dispatchers.IO) {
          val estimated = estimateGas(from = account.address, to = normalizedContract, data = callData)
          withBuffer(estimated = estimated, minimum = minimumGasLimit)
        }
      var fees = withContext(Dispatchers.IO) {
        val suggested = TempoClient.getSuggestedFees()
        withAddressBidFloor(account.address, withRelayMinimumFeeFloor(suggested))
      }
      Log.d(
        TAG,
        "$opLabel tx params gas=$gasLimit fees=${fees.maxPriorityFeePerGas}/${fees.maxFeePerGas}",
      )

      suspend fun submitWithMode(
        feeMode: TempoTransaction.FeeMode,
        txFees: TempoClient.Eip1559Fees,
      ): String {
        var attemptFees =
          when (feeMode) {
            TempoTransaction.FeeMode.RELAY_SPONSORED ->
              withAddressBidFloor(account.address, withRelayMinimumFeeFloor(txFees))
            TempoTransaction.FeeMode.SELF ->
              withAddressBidFloor(account.address, txFees)
          }
        var lastUnderpriced: Throwable? = null

        repeat(MAX_UNDERPRICED_RETRIES + 1) { attempt ->
          val tx =
            TempoTransaction.UnsignedTx(
              nonceKeyBytes = EXPIRING_NONCE_KEY,
              nonce = 0L,
              validBeforeSec = nowSec() + EXPIRY_WINDOW_SEC,
              maxPriorityFeePerGas = attemptFees.maxPriorityFeePerGas,
              maxFeePerGas = attemptFees.maxFeePerGas,
              feeMode = feeMode,
              gasLimit = gasLimit,
              calls =
                listOf(
                  TempoTransaction.Call(
                    to = P256Utils.hexToBytes(normalizedContract),
                    value = 0,
                    input = P256Utils.hexToBytes(callData),
                  ),
                ),
            )

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
                  TempoTransaction.FeeMode.SELF -> throw IllegalStateException("SELF fee mode is disabled")
                }
              }
            }

          val txHash = result.getOrNull()
          if (!txHash.isNullOrBlank()) {
            rememberAddressBidFloor(account.address, attemptFees)
            Log.d(
              TAG,
              "$opLabel submit success mode=$feeMode attempt=${attempt + 1} tx=$txHash",
            )
            return txHash
          }

          val err = result.exceptionOrNull() ?: IllegalStateException("Unknown $opLabel submission failure")
          if (!isReplacementUnderpriced(err) || attempt >= MAX_UNDERPRICED_RETRIES) {
            throw err
          }
          lastUnderpriced = err
          attemptFees = aggressivelyBumpFees(attemptFees)
          attemptFees =
            when (feeMode) {
              TempoTransaction.FeeMode.RELAY_SPONSORED ->
                withAddressBidFloor(account.address, withRelayMinimumFeeFloor(attemptFees))
              TempoTransaction.FeeMode.SELF ->
                withAddressBidFloor(account.address, attemptFees)
            }
          rememberAddressBidFloor(account.address, attemptFees)
          Log.w(
            TAG,
            "$opLabel underpriced mode=$feeMode attempt=${attempt + 1}; bumping to " +
              "${attemptFees.maxPriorityFeePerGas}/${attemptFees.maxFeePerGas}",
          )
          delay(RETRY_DELAY_MS)
        }

        throw (lastUnderpriced ?: IllegalStateException("replacement transaction underpriced"))
      }

      val relayTxHash = submitWithMode(TempoTransaction.FeeMode.RELAY_SPONSORED, fees)

      val receipt = awaitPlaylistReceipt(relayTxHash)
      if (!receipt.isSuccess) {
        throw IllegalStateException("$opLabel reverted on-chain: ${receipt.txHash}")
      }

      val playlistId =
        if (parsePlaylistId) {
          runCatching { extractCreatedPlaylistIdFromReceipt(relayTxHash) }.getOrNull()
        } else {
          null
        }

      TempoPlaylistTxResult(
        success = true,
        txHash = relayTxHash,
        playlistId = playlistId,
      )
    }.getOrElse { error ->
      Log.w(TAG, "$opLabel failed: ${error.message}", error)
      TempoPlaylistTxResult(
        success = false,
        error = error.message ?: "$opLabel failed",
      )
    }
  }
}
