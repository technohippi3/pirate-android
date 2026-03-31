package sc.pirate.app.scrobble

import android.content.Context
import android.util.Log
import io.privy.auth.PrivyUser
import io.privy.wallet.ethereum.EmbeddedEthereumWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import sc.pirate.app.auth.PirateAuthUiState
import sc.pirate.app.auth.privy.PrivyClientStore
import sc.pirate.app.auth.privy.PrivyRuntimeConfig
import sc.pirate.app.music.SongPublishService
import sc.pirate.app.util.HttpClients

private const val TAG = "PrivyScrobbleClient"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private data class PrivyScrobbleSession(
  val accessToken: String?,
  val identityToken: String?,
  val walletAddress: String,
)

internal object PrivyScrobbleClient {
  suspend fun submitScrobble(
    context: Context,
    authState: PirateAuthUiState,
    input: TempoScrobbleInput,
  ): TempoScrobbleSubmitResult {
    return runCatching {
      val session = resolveSession(context = context, authState = authState)
      withContext(Dispatchers.IO) {
        val body =
          JSONObject()
            .put("artist", input.artist)
            .put("title", input.title)
            .put("album", input.album)
            .put("durationSec", input.durationSec)
            .put("playedAtSec", input.playedAtSec)
        val apiBase = SongPublishService.API_CORE_URL.trim().trimEnd('/')
        val request =
          Request.Builder()
            .url("$apiBase/api/music/scrobble")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("x-user-address", session.walletAddress)
            .apply {
              session.accessToken?.let { header("Authorization", "Bearer $it") }
              session.identityToken?.let { header("x-privy-identity-token", it) }
            }
            .build()

        Log.d(
          TAG,
          "submitScrobble: wallet=${session.walletAddress} access=${if (session.accessToken != null) "present" else "absent"} identity=${if (session.identityToken != null) "present" else "absent"}",
        )

        HttpClients.Api.newCall(request).execute().use { response ->
          val rawBody = response.body?.string().orEmpty()
          if (!response.isSuccessful) {
            val detail = parseErrorMessage(rawBody)
            if (response.code == 401) {
              error("Privy session expired. Open Wallet to sign in again.")
            }
            error("Privy scrobble route failed: HTTP ${response.code}${detail?.let { " ($it)" } ?: ""}")
          }

          val payload = JSONObject(rawBody.ifBlank { "{}" })
          val txHash = payload.optString("txHash").trim()
          if (txHash.isBlank()) {
            error("Privy scrobble route succeeded without a txHash.")
          }
          TempoScrobbleSubmitResult(
            success = true,
            txHash = txHash,
            trackId = payload.optString("trackId").trim().ifBlank { null },
            usedRegisterPath = !payload.optBoolean("alreadyRegistered", false),
            pendingConfirmation = false,
          )
        }
      }
    }.getOrElse { err ->
      TempoScrobbleSubmitResult(
        success = false,
        error = err.message ?: "Privy scrobble request failed",
      )
    }
  }

  private suspend fun resolveSession(
    context: Context,
    authState: PirateAuthUiState,
  ): PrivyScrobbleSession {
    return withContext(Dispatchers.Main.immediate) {
      runCatching {
        val config = PrivyRuntimeConfig.fromBuildConfig()
        check(config.disabledReason() == null) { config.disabledReason() ?: "Privy auth is unavailable." }
        val privy = PrivyClientStore.get(context = context.applicationContext, config = config)
        val user = privy.user ?: error("Privy user session unavailable.")
        val accessToken = user.getAccessToken().getOrThrow().trim().ifBlank { null }
        val identityToken = user.identityToken?.trim()?.ifBlank { null }
        if (accessToken == null && identityToken == null) {
          error("Privy session expired. Open Wallet to sign in again.")
        }

        val wallet = resolveWallet(user = user, authState = authState)
          ?: error("Privy wallet is unavailable for scrobbling.")
        PrivyScrobbleSession(
          accessToken = accessToken,
          identityToken = identityToken,
          walletAddress = wallet.address,
        )
      }.getOrElse { err ->
        throw err
      }
    }
  }

  private fun resolveWallet(
    user: PrivyUser,
    authState: PirateAuthUiState,
  ): EmbeddedEthereumWallet? {
    authState.privyWalletId
      ?.trim()
      ?.takeIf { it.isNotBlank() }
      ?.let { walletId ->
        user.embeddedEthereumWallets.firstOrNull { it.id == walletId }?.let { return it }
      }

    authState.activeAddress()
      ?.trim()
      ?.takeIf { it.isNotBlank() }
      ?.let { address ->
        user.embeddedEthereumWallets.firstOrNull { it.address.equals(address, ignoreCase = true) }?.let { return it }
      }

    return user.embeddedEthereumWallets.firstOrNull { it.hdWalletIndex == 0 }
  }

  private fun parseErrorMessage(rawBody: String): String? {
    if (rawBody.isBlank()) return null
    return runCatching {
      JSONObject(rawBody).optString("error").trim().ifBlank {
        JSONObject(rawBody).optString("message").trim()
      }.ifBlank { rawBody.trim() }
    }.getOrElse { rawBody.trim().takeIf { it.isNotBlank() } }
  }
}
