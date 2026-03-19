package sc.pirate.app.music

import android.net.Uri
import androidx.fragment.app.FragmentActivity
import sc.pirate.app.BuildConfig
import sc.pirate.app.store.submitCallWithFallback
import sc.pirate.app.tempo.SessionKeyManager
import sc.pirate.app.tempo.TempoClient
import sc.pirate.app.tempo.TempoPasskeyManager
import sc.pirate.app.util.HttpClients
import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256

private val liveRoomJsonType = "application/json; charset=utf-8".toMediaType()
private val liveRoomHexAddressRegex = Regex("^0x[0-9a-fA-F]{40}$")
private val liveRoomPositiveIntegerRegex = Regex("^\\d+$")
private const val MIN_GAS_LIMIT_LIVE_TICKET_TRANSFER = 120_000L
private const val LIVE_ROOM_MAX_UID = 0xFFFF_FFFFL

internal data class LiveRoomPublicInfo(
  val roomId: String,
  val title: String?,
  val hostWallet: String?,
  val liveAmount: String?,
  val listenerCount: Int,
  val ticketTransferable: Boolean?,
  val status: String,
  val audienceMode: String,
  val gateType: String,
  val canEnter: Boolean,
  val broadcastState: String,
  val broadcasterOnline: Boolean,
  val eventStartAt: Long?,
  val salesStartAt: Long?,
  val salesEndAt: Long?,
  val coverRef: String?,
)

internal data class LiveRoomJoinInfo(
  val roomId: String,
  val agoraAppId: String,
  val agoraChannel: String,
  val agoraUid: Int,
  val agoraViewerToken: String,
  val tokenExpiresInSeconds: Int,
)

internal data class LiveRoomPaymentRequirements(
  val scheme: String,
  val network: String,
  val asset: String,
  val amount: String,
  val payTo: String,
)

internal data class LiveRoomTicketStartResult(
  val quoteId: String?,
  val ticketPurchasedAt: Long?,
  val idempotent: Boolean,
  val paymentRequirements: LiveRoomPaymentRequirements?,
)

internal data class LiveRoomTicketConfirmResult(
  val ticketPurchasedAt: Long?,
  val idempotent: Boolean,
)

internal class LiveRoomApiException(
  val status: Int,
  val code: String,
  val reason: String?,
) : IllegalStateException(
  if (reason.isNullOrBlank()) code else "$code: $reason",
)

internal object LiveRoomEntryApi {
  private fun requireVoiceControlPlaneBaseUrl(): String {
    val base = BuildConfig.VOICE_CONTROL_PLANE_URL.trim().trimEnd('/')
    if (!base.startsWith("http://") && !base.startsWith("https://")) {
      throw IllegalStateException("Room service unavailable.")
    }
    return base
  }

  suspend fun fetchPublicInfo(
    roomId: String,
    bearerToken: String? = null,
  ): LiveRoomPublicInfo {
    val normalizedRoomId = roomId.trim()
    if (normalizedRoomId.isBlank()) throw IllegalStateException("Room ID is required")

    val base = requireVoiceControlPlaneBaseUrl()
    val body = requestJson(
      method = "GET",
      url = "$base/live/${encodePathSegment(normalizedRoomId)}/public-info",
      bearerToken = bearerToken?.trim()?.takeIf { it.isNotBlank() },
      payload = null,
      fallbackCode = "public_info_failed",
    )

    val status = body.optString("status", "").trim().lowercase().ifBlank { "unknown" }
    val audienceMode = body.optString("audience_mode", "").trim().lowercase().ifBlank { "unknown" }
    val broadcastState = body.optString("broadcast_state", "").trim().lowercase().ifBlank { "idle" }

    return LiveRoomPublicInfo(
      roomId = body.optString("room_id", "").trim().ifBlank { normalizedRoomId },
      title = normalizeOptionalString(body.opt("title")?.toString()),
      hostWallet = normalizeLiveAddress(body.optString("host_wallet", "")),
      liveAmount = normalizeOptionalString(body.opt("live_amount")?.toString()),
      listenerCount = body.optInt("listener_count", 0).coerceAtLeast(0),
      ticketTransferable = body.takeIf { it.has("ticket_transferable") }?.optBoolean("ticket_transferable"),
      status = status,
      audienceMode = audienceMode,
      gateType = body.optString("gate_type", "").trim().lowercase().ifBlank { "none" },
      canEnter = body.optBoolean("can_enter", false),
      broadcastState = broadcastState,
      broadcasterOnline = body.optBoolean("broadcaster_online", false),
      eventStartAt = body.optLong("event_start_at").takeIf { it > 0L },
      salesStartAt = body.optLong("sales_start_at").takeIf { it > 0L },
      salesEndAt = body.optLong("sales_end_at").takeIf { it > 0L },
      coverRef = normalizeOptionalString(body.opt("cover_ref")?.toString()),
    )
  }

  suspend fun publicEnter(
    roomId: String,
    wallet: String? = null,
  ): LiveRoomJoinInfo {
    val normalizedRoomId = roomId.trim()
    if (normalizedRoomId.isBlank()) throw IllegalStateException("Room ID is required")

    val payload = JSONObject()
    normalizeOptionalString(wallet)?.let { payload.put("wallet", it.lowercase()) }

    val base = requireVoiceControlPlaneBaseUrl()
    val body = requestJson(
      method = "POST",
      url = "$base/live/${encodePathSegment(normalizedRoomId)}/public-enter",
      bearerToken = null,
      payload = payload,
      fallbackCode = "public_enter_failed",
    )
    return parseJoinInfo(body, normalizedRoomId)
  }

  suspend fun authenticatedEnter(
    roomId: String,
    bearerToken: String,
    tokenId: String? = null,
  ): LiveRoomJoinInfo {
    val normalizedRoomId = roomId.trim()
    if (normalizedRoomId.isBlank()) throw IllegalStateException("Room ID is required")

    val authToken = bearerToken.trim()
    if (authToken.isBlank()) throw IllegalStateException("Missing auth token")

    val payload = JSONObject()
    normalizeOptionalString(tokenId)?.let { payload.put("tokenId", it) }

    val base = requireVoiceControlPlaneBaseUrl()
    val body = requestJson(
      method = "POST",
      url = "$base/live/${encodePathSegment(normalizedRoomId)}/enter",
      bearerToken = authToken,
      payload = payload,
      fallbackCode = "enter_failed",
    )
    return parseJoinInfo(body, normalizedRoomId)
  }

  suspend fun ticketStart(
    roomId: String,
    bearerToken: String,
  ): LiveRoomTicketStartResult {
    val normalizedRoomId = roomId.trim()
    if (normalizedRoomId.isBlank()) throw IllegalStateException("Room ID is required")

    val authToken = bearerToken.trim()
    if (authToken.isBlank()) throw IllegalStateException("Missing auth token")

    val base = requireVoiceControlPlaneBaseUrl()
    val body = requestJson(
      method = "POST",
      url = "$base/live/${encodePathSegment(normalizedRoomId)}/ticket/start",
      bearerToken = authToken,
      payload = JSONObject(),
      fallbackCode = "ticket_start_failed",
    )

    val paymentObj = body.optJSONObject("paymentRequirements")
    val requirements =
      paymentObj?.let {
        LiveRoomPaymentRequirements(
          scheme = it.optString("scheme", "").trim(),
          network = it.optString("network", "").trim(),
          asset = normalizeLiveAddress(it.optString("asset", "")),
          amount = it.optString("amount", "").trim(),
          payTo = normalizeLiveAddress(it.optString("payTo", "")),
        )
      }

    return LiveRoomTicketStartResult(
      quoteId = normalizeOptionalString(body.optString("quoteId", "")),
      ticketPurchasedAt = body.optLong("ticket_purchased_at").takeIf { it > 0L },
      idempotent = body.optBoolean("idempotent", false),
      paymentRequirements = requirements,
    )
  }

  suspend fun ticketConfirm(
    roomId: String,
    bearerToken: String,
    quoteId: String,
    txHash: String,
  ): LiveRoomTicketConfirmResult {
    val normalizedRoomId = roomId.trim()
    if (normalizedRoomId.isBlank()) throw IllegalStateException("Room ID is required")

    val normalizedQuoteId = quoteId.trim()
    if (normalizedQuoteId.isBlank()) throw IllegalStateException("Quote ID is required")

    val normalizedTxHash = txHash.trim().lowercase()
    if (normalizedTxHash.isBlank()) throw IllegalStateException("Transaction hash is required")

    val authToken = bearerToken.trim()
    if (authToken.isBlank()) throw IllegalStateException("Missing auth token")

    val payload =
      JSONObject()
        .put("quoteId", normalizedQuoteId)
        .put("txHash", normalizedTxHash)

    val base = requireVoiceControlPlaneBaseUrl()
    val body = requestJson(
      method = "POST",
      url = "$base/live/${encodePathSegment(normalizedRoomId)}/ticket/confirm",
      bearerToken = authToken,
      payload = payload,
      fallbackCode = "ticket_confirm_failed",
    )

    return LiveRoomTicketConfirmResult(
      ticketPurchasedAt = body.optLong("ticket_purchased_at").takeIf { it > 0L },
      idempotent = body.optBoolean("idempotent", false),
    )
  }

  private fun parseJoinInfo(
    body: JSONObject,
    roomIdFallback: String,
  ): LiveRoomJoinInfo {
    val roomId = body.optString("room_id", "").trim().ifBlank { roomIdFallback }
    val appId = body.optString("agora_app_id", "").trim()
    val channel = body.optString("agora_channel", "").trim()
    val token = body.optString("agora_viewer_token", "").trim()
    val uidRaw = body.optLong("agora_uid", -1L)
    val uid = uidRaw.toUInt().toInt()
    val ttl = body.optInt("token_expires_in_seconds", 90)

    if (appId.isBlank() || channel.isBlank() || token.isBlank() || uidRaw <= 0L || uidRaw > LIVE_ROOM_MAX_UID) {
      throw IllegalStateException("Invalid room entry response")
    }

    return LiveRoomJoinInfo(
      roomId = roomId,
      agoraAppId = appId,
      agoraChannel = channel,
      agoraUid = uid,
      agoraViewerToken = token,
      tokenExpiresInSeconds = ttl.coerceAtLeast(30),
    )
  }

  private suspend fun requestJson(
    method: String,
    url: String,
    bearerToken: String?,
    payload: JSONObject?,
    fallbackCode: String,
  ): JSONObject = withContext(Dispatchers.IO) {
    val normalizedMethod = method.trim().uppercase()
    val requestBuilder =
      Request.Builder()
        .url(url)
        .header("Content-Type", "application/json")

    if (!bearerToken.isNullOrBlank()) {
      requestBuilder.header("Authorization", "Bearer $bearerToken")
    }

    val body = payload?.toString()?.toRequestBody(liveRoomJsonType)
    when (normalizedMethod) {
      "GET" -> requestBuilder.get()
      "POST" -> requestBuilder.post(body ?: "{}".toRequestBody(liveRoomJsonType))
      else -> throw IllegalStateException("Unsupported request method: $normalizedMethod")
    }

    HttpClients.Api.newCall(requestBuilder.build()).execute().use { response ->
      val bodyText = response.body?.string().orEmpty()
      val bodyObj = parseJsonObject(bodyText)
      if (!response.isSuccessful) {
        throwApiError(
          status = response.code,
          body = bodyObj,
          fallbackCode = fallbackCode,
        )
      }
      bodyObj ?: JSONObject()
    }
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
        ?: body?.optString("message", "")?.trim()?.ifBlank { null }
    throw LiveRoomApiException(status = status, code = code, reason = reason)
  }
}

internal suspend fun submitLiveTicketPayment(
  activity: FragmentActivity,
  account: TempoPasskeyManager.PasskeyAccount,
  requirements: LiveRoomPaymentRequirements,
  sessionKey: SessionKeyManager.SessionKey? = null,
  preferSelfPay: Boolean = false,
): String {
  val scheme = requirements.scheme.trim().lowercase()
  if (scheme != "tempo-tip20") {
    throw IllegalStateException("Unsupported live ticket payment scheme: $scheme")
  }
  val network = requirements.network.trim().lowercase()
  if (network != "eip155:42431") {
    throw IllegalStateException("Unsupported live ticket payment network: $network")
  }

  val chainId = withContext(Dispatchers.IO) { TempoClient.getChainId() }
  if (chainId != TempoClient.CHAIN_ID) {
    throw IllegalStateException("Wrong chain connected: $chainId (expected ${TempoClient.CHAIN_ID})")
  }

  val asset = normalizeLiveAddress(requirements.asset)
  val payTo = normalizeLiveAddress(requirements.payTo)
  val amount = parsePositiveBigInteger(requirements.amount)
    ?: throw IllegalStateException("Invalid live ticket payment amount")

  if (!isHexAddress(asset) || !isHexAddress(payTo)) {
    throw IllegalStateException("Invalid live ticket payment addresses")
  }

  val transferCallData = encodeTransferCalldata(payTo = payTo, amount = amount)
  return submitCallWithFallback(
    activity = activity,
    account = account,
    to = asset,
    callData = transferCallData,
    minimumGasLimit = MIN_GAS_LIMIT_LIVE_TICKET_TRANSFER,
    rpId = account.rpId,
    sessionKey = sessionKey,
    preferSelfPay = preferSelfPay,
  )
}

private fun parsePositiveBigInteger(raw: String): BigInteger? {
  val trimmed = raw.trim()
  if (!liveRoomPositiveIntegerRegex.matches(trimmed)) return null
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

private fun isHexAddress(value: String): Boolean =
  liveRoomHexAddressRegex.matches(value.trim())

private fun normalizeLiveAddress(value: String): String {
  val trimmed = value.trim()
  if (trimmed.isBlank()) return ""
  val prefixed = if (trimmed.startsWith("0x", ignoreCase = true)) trimmed else "0x$trimmed"
  return "0x${prefixed.removePrefix("0x").removePrefix("0X").lowercase()}"
}

private fun normalizeOptionalString(value: String?): String? {
  val trimmed = value?.trim().orEmpty()
  if (trimmed.isBlank()) return null
  if (trimmed.equals("null", ignoreCase = true)) return null
  if (trimmed.equals("undefined", ignoreCase = true)) return null
  return trimmed
}

private fun encodePathSegment(value: String): String =
  Uri.encode(value.trim())
