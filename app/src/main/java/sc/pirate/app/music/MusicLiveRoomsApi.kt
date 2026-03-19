package sc.pirate.app.music

import android.content.Context
import sc.pirate.app.BuildConfig
import sc.pirate.app.assistant.getTempoWorkerAuthSession
import sc.pirate.app.resolvePublicProfileIdentity
import sc.pirate.app.tempo.SessionKeyManager
import sc.pirate.app.util.HttpClients
import sc.pirate.app.util.shortAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

private fun normalizeOptionalString(value: String?): String? {
  val trimmed = value?.trim().orEmpty()
  if (trimmed.isBlank()) return null
  if (trimmed.equals("null", ignoreCase = true)) return null
  if (trimmed.equals("undefined", ignoreCase = true)) return null
  return trimmed
}

private fun normalizeWallet(value: String?): String? {
  val trimmed = value?.trim().orEmpty().lowercase()
  if (!trimmed.startsWith("0x") || trimmed.length != 42) return null
  if (!trimmed.drop(2).all { it in '0'..'9' || it in 'a'..'f' }) return null
  return trimmed
}

internal suspend fun fetchDiscoverableLiveRooms(
  context: Context,
  ownerEthAddress: String?,
  isAuthenticated: Boolean,
  maxEntries: Int = HOME_LIVE_ROOMS_MAX,
): List<LiveRoomCardModel> = withContext(Dispatchers.IO) {
  val base = BuildConfig.VOICE_CONTROL_PLANE_URL.trim().trimEnd('/')
  if (!base.startsWith("http://") && !base.startsWith("https://")) return@withContext emptyList()

  val owner = ownerEthAddress?.trim()?.lowercase().orEmpty()
  val authHeader =
    if (isAuthenticated && owner.isNotBlank()) {
      val sessionKey =
        SessionKeyManager.load(context.applicationContext)?.takeIf {
          SessionKeyManager.isValid(it, ownerAddress = owner) &&
            it.keyAuthorization?.isNotEmpty() == true
        }
      sessionKey?.let {
        runCatching {
          getTempoWorkerAuthSession(
            workerUrl = base,
            walletAddress = owner,
            sessionKey = it,
          )
        }.getOrNull()?.let { session -> "Bearer ${session.token}" }
      }
    } else {
      null
    }

  val requestBuilder =
    Request.Builder()
      .url("$base/live/discover")
      .get()
  if (!authHeader.isNullOrBlank()) {
    requestBuilder.header("Authorization", authHeader)
  }

  HttpClients.Api.newCall(requestBuilder.build()).execute().use { response ->
    val payload = response.body?.string().orEmpty()
    if (!response.isSuccessful) {
      if (response.code == 401 || response.code == 403 || response.code == 404) return@withContext emptyList()
      throw IllegalStateException("Live rooms fetch failed: HTTP ${response.code}")
    }
    val json = runCatching { JSONObject(payload) }.getOrNull() ?: return@withContext emptyList()
    val rooms = json.optJSONArray("rooms") ?: return@withContext emptyList()
    if (rooms.length() == 0) return@withContext emptyList()

    val wallets = LinkedHashSet<String>(rooms.length() * 2)
    for (i in 0 until rooms.length()) {
      val row = rooms.optJSONObject(i) ?: continue
      normalizeWallet(row.opt("host_wallet")?.toString())?.let { wallets.add(it) }
      normalizeWallet(row.opt("guest_wallet")?.toString())?.let { wallets.add(it) }
    }
    val walletLabels = HashMap<String, String>(wallets.size)
    for (wallet in wallets) {
      val primaryName =
        runCatching { resolvePublicProfileIdentity(wallet).first }
          .getOrNull()
          ?.trim()
          .orEmpty()
      walletLabels[wallet] =
        if (primaryName.isNotBlank()) {
          primaryName
        } else {
          shortAddress(wallet, prefixChars = 5, suffixChars = 3, minLengthToShorten = 12)
        }
    }

    val out = ArrayList<LiveRoomCardModel>(maxEntries.coerceAtMost(rooms.length()))
    for (i in 0 until rooms.length()) {
      if (out.size >= maxEntries) break
      val row = rooms.optJSONObject(i) ?: continue
      val roomId = row.optString("room_id", "").trim()
      if (roomId.isBlank()) continue
      val hostWallet = normalizeWallet(row.opt("host_wallet")?.toString())
      val guestWallet = normalizeWallet(row.opt("guest_wallet")?.toString())
      val hostLabel = hostWallet?.let { walletLabels[it] }
      val guestLabel = guestWallet?.let { walletLabels[it] }
      val title =
        normalizeOptionalString(row.opt("title")?.toString())
          ?: when {
            !hostLabel.isNullOrBlank() && !guestLabel.isNullOrBlank() -> "$hostLabel x $guestLabel"
            !hostLabel.isNullOrBlank() -> hostLabel
            else -> "Live Room"
          }
      val status = row.optString("status", "").trim().lowercase()
      val audienceMode = row.optString("audience_mode", "").trim().lowercase()
      val shouldInclude =
        when (status) {
          "live" -> {
            val info = runCatching { LiveRoomEntryApi.fetchPublicInfo(roomId) }.getOrNull()
            info?.status == "live" && (info.broadcasterOnline || audienceMode == "ticketed")
          }
          else -> audienceMode == "ticketed"
        }
      if (!shouldInclude) continue
      val subtitle = hostLabel
      val coverRef = normalizeOptionalString(row.opt("cover_ref")?.toString())
      val liveAmount = normalizeOptionalString(row.opt("live_amount")?.toString())
      val listenerCount = row.optInt("listener_count", 0).coerceAtLeast(0)

      out.add(
        LiveRoomCardModel(
          roomId = roomId,
          title = title,
          subtitle = subtitle,
          hostWallet = hostWallet,
          coverRef = coverRef,
          liveAmount = liveAmount,
          listenerCount = listenerCount,
          status = status,
        ),
      )
    }
    out
  }
}
