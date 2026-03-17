package com.pirate.app.music

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.pirate.app.store.submitCallWithFallback
import com.pirate.app.tempo.ContentKeyManager
import com.pirate.app.tempo.P256Utils
import com.pirate.app.tempo.SessionKeyManager
import com.pirate.app.tempo.TempoClient
import com.pirate.app.tempo.TempoPasskeyManager
import com.pirate.app.util.HttpClients
import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256

data class SongPurchaseResult(
  val success: Boolean,
  val purchaseId: String? = null,
  val txHash: String? = null,
  val paidAmountRaw: String? = null,
  val error: String? = null,
  val errorCode: String? = null,
)

data class SongPurchaseQuotePreviewResult(
  val success: Boolean,
  val amountRaw: String? = null,
  val error: String? = null,
  val errorCode: String? = null,
)

private data class PurchasePaymentRequirements(
  val scheme: String,
  val network: String,
  val asset: String,
  val amount: String,
  val payTo: String,
)

private data class SongPurchaseQuote(
  val songTrackId: String,
  val purchaseId: String,
  val quoteId: String,
  val payment: PurchasePaymentRequirements,
)

private class SongPurchaseApiException(
  val status: Int,
  val code: String,
  val reason: String?,
) : IllegalStateException(
    if (reason.isNullOrBlank()) code else "$code: $reason",
  )

object SongPurchaseApi {
  private val jsonType = "application/json; charset=utf-8".toMediaType()
  private val hexAddressRegex = Regex("^0x[0-9a-fA-F]{40}$")
  private val bytes32Regex = Regex("^0x[0-9a-fA-F]{64}$")
  private val positiveIntegerRegex = Regex("^\\d+$")
  private const val MIN_GAS_LIMIT_PURCHASE_TRANSFER = 120_000L
  private const val SONG_ASSET_READY_RETRY_WINDOW_MS = 90_000L
  private const val SONG_ASSET_READY_RETRY_DELAY_MS = 3_000L

  suspend fun fetchQuotePreview(
    ownerAddress: String,
    songTrackId: String,
  ): SongPurchaseQuotePreviewResult {
    return runCatching {
      val owner = normalizeAddress(ownerAddress)
      if (!isHexAddress(owner)) throw IllegalStateException("Invalid wallet address")

      val normalizedTrackId = songTrackId.trim().lowercase()
      if (!bytes32Regex.matches(normalizedTrackId)) {
        throw IllegalStateException("Invalid song track id")
      }

      val apiBase = SongPublishService.API_CORE_URL.trim().trimEnd('/')
      val quote = requestQuote(apiBase = apiBase, owner = owner, songTrackId = normalizedTrackId)

      SongPurchaseQuotePreviewResult(
        success = true,
        amountRaw = quote.payment.amount,
      )
    }.getOrElse { error ->
      val apiError = error as? SongPurchaseApiException
      SongPurchaseQuotePreviewResult(
        success = false,
        error = resolveUserFacingError(error),
        errorCode = apiError?.code,
      )
    }
  }

  suspend fun buySong(
    activity: FragmentActivity,
    appContext: Context,
    account: TempoPasskeyManager.PasskeyAccount,
    ownerAddress: String,
    songTrackId: String,
    sessionKey: SessionKeyManager.SessionKey? = null,
    preferSelfPay: Boolean = false,
  ): SongPurchaseResult {
    return runCatching {
      val owner = normalizeAddress(ownerAddress)
      if (!isHexAddress(owner)) throw IllegalStateException("Invalid wallet address")
      if (normalizeAddress(account.address) != owner) {
        throw IllegalStateException("Account mismatch: active signer does not match wallet")
      }

      val normalizedTrackId = songTrackId.trim().lowercase()
      if (!bytes32Regex.matches(normalizedTrackId)) {
        throw IllegalStateException("Invalid song track id")
      }

      val chainId = withContext(Dispatchers.IO) { TempoClient.getChainId() }
      if (chainId != TempoClient.CHAIN_ID) {
        throw IllegalStateException("Wrong chain connected: $chainId (expected ${TempoClient.CHAIN_ID})")
      }

      val apiBase = SongPublishService.API_CORE_URL.trim().trimEnd('/')
      val quote = requestQuote(apiBase = apiBase, owner = owner, songTrackId = normalizedTrackId)
      val amountRaw = quote.payment.amount.trim()
      val paymentAmount =
        parsePositiveBigInteger(amountRaw)
          ?: throw IllegalStateException("Invalid payment amount from quote")
      if (quote.payment.scheme != "tempo-tip20") {
        throw IllegalStateException("Unsupported payment scheme: ${quote.payment.scheme}")
      }
      if (!isHexAddress(quote.payment.asset) || !isHexAddress(quote.payment.payTo)) {
        throw IllegalStateException("Invalid payment addresses from quote")
      }

      val buyerContentPublicKey = resolveBuyerContentPublicKey(appContext)
      val transferCallData = encodeTransferCalldata(payTo = quote.payment.payTo, amount = paymentAmount)
      val txHash =
        submitCallWithFallback(
          activity = activity,
          account = account,
          to = quote.payment.asset,
          callData = transferCallData,
          minimumGasLimit = MIN_GAS_LIMIT_PURCHASE_TRANSFER,
          rpId = account.rpId,
          sessionKey = sessionKey,
          preferSelfPay = preferSelfPay,
        )

      val purchaseId =
        confirmQuoteWithSongAssetRetry(
          apiBase = apiBase,
          owner = owner,
          quote = quote,
          txHash = txHash,
          buyerContentPublicKey = buyerContentPublicKey,
        )

      SongPurchaseResult(
        success = true,
        purchaseId = purchaseId,
        txHash = txHash,
        paidAmountRaw = amountRaw,
      )
    }.getOrElse { error ->
      val apiError = error as? SongPurchaseApiException
      SongPurchaseResult(
        success = false,
        error = resolveUserFacingError(error),
        errorCode = apiError?.code,
      )
    }
  }

  private suspend fun confirmQuoteWithSongAssetRetry(
    apiBase: String,
    owner: String,
    quote: SongPurchaseQuote,
    txHash: String,
    buyerContentPublicKey: String,
  ): String {
    var activeQuote = quote
    var lastAssetNotReadyError: SongPurchaseApiException? = null
    val deadlineMs = System.currentTimeMillis() + SONG_ASSET_READY_RETRY_WINDOW_MS

    while (true) {
      try {
        return confirmQuote(
          apiBase = apiBase,
          owner = owner,
          quote = activeQuote,
          txHash = txHash,
          buyerContentPublicKey = buyerContentPublicKey,
        )
      } catch (error: SongPurchaseApiException) {
        if (error.code != "song_asset_not_ready") throw error
        lastAssetNotReadyError = error
      }

      if (System.currentTimeMillis() >= deadlineMs) {
        throw (lastAssetNotReadyError ?: IllegalStateException("song_asset_not_ready"))
      }

      delay(SONG_ASSET_READY_RETRY_DELAY_MS)

      val nextQuoteResult =
        runCatching {
          requestQuote(
            apiBase = apiBase,
            owner = owner,
            songTrackId = quote.songTrackId,
          )
        }
      if (nextQuoteResult.isSuccess) {
        activeQuote = nextQuoteResult.getOrThrow()
        continue
      }

      val quoteError = nextQuoteResult.exceptionOrNull()
      val quoteApiError = quoteError as? SongPurchaseApiException
      if (quoteApiError?.code == "song_asset_not_ready") {
        lastAssetNotReadyError = quoteApiError
        continue
      }
      throw (quoteError ?: IllegalStateException("purchase_confirm_retry_failed"))
    }
  }

  private suspend fun requestQuote(
    apiBase: String,
    owner: String,
    songTrackId: String,
  ): SongPurchaseQuote = withContext(Dispatchers.IO) {
    val payload = JSONObject().put("songTrackId", songTrackId)
    val request =
      Request.Builder()
        .url("$apiBase/api/music/purchase/start")
        .header("X-User-Address", owner)
        .header("Content-Type", "application/json")
        .post(payload.toString().toRequestBody(jsonType))
        .build()

    HttpClients.Api.newCall(request).execute().use { response ->
      val bodyText = response.body?.string().orEmpty()
      val body = parseJsonObject(bodyText)
      if (!response.isSuccessful) {
        throwApiError(
          status = response.code,
          body = body,
          fallbackCode = "purchase_start_failed",
        )
      }

      val purchaseId = body?.optString("purchaseId", "").orEmpty().trim()
      val quoteId = body?.optString("quoteId", "").orEmpty().trim()
      val resolvedTrackId = body?.optString("songTrackId", "").orEmpty().trim().ifBlank { songTrackId }
      val paymentObj = body?.optJSONObject("paymentRequirements")
      val payment =
        PurchasePaymentRequirements(
          scheme = paymentObj?.optString("scheme", "").orEmpty().trim(),
          network = paymentObj?.optString("network", "").orEmpty().trim(),
          asset = normalizeAddress(paymentObj?.optString("asset", "").orEmpty()),
          amount = paymentObj?.optString("amount", "").orEmpty().trim(),
          payTo = normalizeAddress(paymentObj?.optString("payTo", "").orEmpty()),
        )

      if (purchaseId.isBlank() || quoteId.isBlank()) {
        throw IllegalStateException("Invalid purchase quote response")
      }
      if (resolvedTrackId.isBlank() || payment.network.isBlank()) {
        throw IllegalStateException("Incomplete purchase quote response")
      }
      SongPurchaseQuote(
        songTrackId = resolvedTrackId,
        purchaseId = purchaseId,
        quoteId = quoteId,
        payment = payment,
      )
    }
  }

  private suspend fun confirmQuote(
    apiBase: String,
    owner: String,
    quote: SongPurchaseQuote,
    txHash: String,
    buyerContentPublicKey: String,
  ): String = withContext(Dispatchers.IO) {
    val payload =
      JSONObject()
        .put("songTrackId", quote.songTrackId)
        .put("purchaseId", quote.purchaseId)
        .put("quoteId", quote.quoteId)
        .put("txHash", txHash)
        .put("buyerContentPublicKey", buyerContentPublicKey)
    val request =
      Request.Builder()
        .url("$apiBase/api/music/purchase/confirm")
        .header("X-User-Address", owner)
        .header("Content-Type", "application/json")
        .post(payload.toString().toRequestBody(jsonType))
        .build()

    HttpClients.Api.newCall(request).execute().use { response ->
      val bodyText = response.body?.string().orEmpty()
      val body = parseJsonObject(bodyText)
      if (!response.isSuccessful) {
        throwApiError(
          status = response.code,
          body = body,
          fallbackCode = "purchase_confirm_failed",
        )
      }

      val resolvedPurchaseId = body?.optString("purchaseId", "").orEmpty().trim()
      if (resolvedPurchaseId.isBlank()) {
        throw IllegalStateException("Invalid purchase confirmation response")
      }
      resolvedPurchaseId
    }
  }

  private fun resolveBuyerContentPublicKey(
    appContext: Context,
  ): String {
    val contentKey = ContentKeyManager.getOrCreate(appContext)
    val publicKeyBytes = contentKey.publicKey
    if (publicKeyBytes.size != 65 || publicKeyBytes[0] != 0x04.toByte()) {
      throw IllegalStateException("Invalid local content public key")
    }
    return "0x${P256Utils.bytesToHex(publicKeyBytes)}"
  }

  private fun parsePositiveBigInteger(raw: String): BigInteger? {
    val trimmed = raw.trim()
    if (!positiveIntegerRegex.matches(trimmed)) return null
    if (trimmed == "0") return null
    return trimmed.toBigIntegerOrNull()?.takeIf { it > BigInteger.ZERO }
  }

  private fun encodeTransferCalldata(
    payTo: String,
    amount: BigInteger,
  ): String {
    val transfer =
      Function(
        "transfer",
        listOf(Address(payTo), Uint256(amount)),
        emptyList(),
      )
    return FunctionEncoder.encode(transfer)
  }

  private fun parseJsonObject(raw: String): JSONObject? =
    runCatching { JSONObject(raw) }.getOrNull()

  private fun throwApiError(
    status: Int,
    body: JSONObject?,
    fallbackCode: String,
  ): Nothing {
    val code = body?.optString("error", "").orEmpty().trim().ifBlank { fallbackCode }
    val reason =
      body?.optString("reason", "")?.trim()?.ifBlank { null }
        ?: body?.optString("detail", "")?.trim()?.ifBlank { null }
    throw SongPurchaseApiException(status = status, code = code, reason = reason)
  }

  private fun resolveUserFacingError(error: Throwable): String {
    val apiError = error as? SongPurchaseApiException ?: return error.message ?: "Song purchase failed"
    return when (apiError.code) {
      "song_not_purchasable" -> "This song is not purchasable."
      "song_sold_out" -> "This song is sold out."
      "song_asset_not_ready" -> "Song is still processing. Try again shortly."
      "quote_expired" -> "Quote expired. Try buying again."
      "quote_not_found" -> "Quote not found. Try buying again."
      "quote_already_used" -> "Quote already used. Start a new purchase."
      "payment_pending" -> "Payment is pending confirmation. Try again shortly."
      "payment_invalid" -> "Payment did not match quote requirements."
      "payment_settlement_unavailable" -> "Payment settlement is temporarily unavailable."
      "purchase_not_accessible" -> "Purchase is not accessible yet."
      else -> apiError.reason ?: apiError.code
    }
  }

  private fun isHexAddress(value: String): Boolean = hexAddressRegex.matches(value.trim())

  private fun normalizeAddress(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return ""
    val prefixed = if (trimmed.startsWith("0x", ignoreCase = true)) trimmed else "0x$trimmed"
    return "0x${prefixed.removePrefix("0x").removePrefix("0X").lowercase()}"
  }
}
