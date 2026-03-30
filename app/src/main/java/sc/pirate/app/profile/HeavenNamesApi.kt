package sc.pirate.app.profile

import android.content.Context
import io.privy.auth.PrivyUser
import io.privy.wallet.ethereum.EmbeddedEthereumWallet
import io.privy.wallet.ethereum.EthereumRpcRequest
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import sc.pirate.app.auth.privy.PrivyClientStore
import sc.pirate.app.auth.privy.PrivyRuntimeConfig
import sc.pirate.app.music.SongPublishService
import sc.pirate.app.util.HttpClients

internal data class HeavenReverseName(
  val label: String,
  val fullName: String,
  val status: String,
  val expiresAt: Long,
)

internal data class HeavenNameRegistrationResult(
  val success: Boolean,
  val label: String? = null,
  val fullName: String? = null,
  val expiresAt: Long? = null,
  val error: String? = null,
)

internal object HeavenNamesApi {
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

  suspend fun checkNameAvailable(label: String): Boolean = withContext(Dispatchers.IO) {
    val normalizedLabel = normalizeLabel(label)
    if (normalizedLabel.isBlank()) return@withContext false
    val response =
      executeGet("/api/names/available/${encodePath(normalizedLabel)}")
    val payload = JSONObject(response)
    payload.optBoolean("available", false)
  }

  suspend fun reverse(address: String): HeavenReverseName? = withContext(Dispatchers.IO) {
    val normalizedAddress = normalizeAddress(address) ?: return@withContext null
    val request =
      Request.Builder()
        .url("${apiBaseUrl()}/api/names/reverse/${encodePath(normalizedAddress)}")
        .get()
        .header("Accept", "application/json")
        .build()
    HttpClients.Api.newCall(request).execute().use { response ->
      if (response.code == 404) return@withContext null
      if (!response.isSuccessful) {
        throw IllegalStateException("Heaven reverse lookup failed: HTTP ${response.code}")
      }
      val payload = JSONObject(response.body?.string().orEmpty())
      val label = payload.optString("label", "").trim().lowercase()
      if (label.isBlank()) return@withContext null
      HeavenReverseName(
        label = label,
        fullName = "$label.heaven",
        status = payload.optString("status", "").trim(),
        expiresAt = payload.optLong("expires_at", 0L),
      )
    }
  }

  suspend fun register(
    context: Context,
    ownerAddress: String,
    label: String,
    profileCid: String? = null,
  ): HeavenNameRegistrationResult = withContext(Dispatchers.IO) {
    val normalizedOwner = normalizeAddress(ownerAddress)
      ?: return@withContext HeavenNameRegistrationResult(success = false, error = "Invalid wallet address.")
    val normalizedLabel = normalizeLabel(label)
    if (normalizedLabel.isBlank()) {
      return@withContext HeavenNameRegistrationResult(success = false, error = "Invalid label.")
    }

    val timestamp = System.currentTimeMillis() / 1000L
    val nonce = buildNonce()
    val message =
      buildRegisterMessage(
        label = normalizedLabel,
        ownerAddress = normalizedOwner,
        nonce = nonce,
        issuedAt = timestamp,
        expiresAt = timestamp + SIGNATURE_TTL_SECONDS,
        profileCid = profileCid.orEmpty(),
      )
    val wallet = resolveEmbeddedWallet(context)
    val signature = signMessage(wallet = wallet, message = message)

    val payload =
      JSONObject()
        .put("label", normalizedLabel)
        .put("ownerAddress", normalizedOwner)
        .put("profileCid", profileCid.orEmpty())
        .put("signature", signature)
        .put("nonce", nonce)
        .put("timestamp", timestamp)

    val request =
      Request.Builder()
        .url("${apiBaseUrl()}/api/names/register")
        .post(payload.toString().toRequestBody(jsonMediaType))
        .header("Accept", "application/json")
        .build()

    HttpClients.Api.newCall(request).execute().use { response ->
      val body = response.body?.string().orEmpty()
      val responsePayload = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
      if (!response.isSuccessful || !responsePayload.optBoolean("success", false)) {
        return@withContext HeavenNameRegistrationResult(
          success = false,
          error = responsePayload.optString("error", "").trim().ifBlank {
            if (!response.isSuccessful) {
              "Heaven registration failed: HTTP ${response.code}"
            } else {
              "Heaven registration failed."
            }
          },
        )
      }
      val registeredLabel = responsePayload.optString("label", normalizedLabel).trim().lowercase()
      HeavenNameRegistrationResult(
        success = true,
        label = registeredLabel,
        fullName = "$registeredLabel.heaven",
        expiresAt = responsePayload.optLong("expires_at", 0L).takeIf { it > 0L },
      )
    }
  }

  internal fun buildRegisterMessage(
    label: String,
    ownerAddress: String,
    nonce: String,
    issuedAt: Long,
    expiresAt: Long,
    profileCid: String = "",
  ): String {
    return listOf(
      "heaven-registry:v1",
      "action=register",
      "tld=heaven",
      "label=${normalizeLabel(label)}",
      "wallet=${normalizeAddress(ownerAddress) ?: ownerAddress.trim()}",
      "nonce=$nonce",
      "issued_at=$issuedAt",
      "expires_at=$expiresAt",
      "profile_cid=$profileCid",
    ).joinToString(separator = "\n")
  }

  private suspend fun executeGet(path: String): String = withContext(Dispatchers.IO) {
    val request =
      Request.Builder()
        .url("${apiBaseUrl()}$path")
        .get()
        .header("Accept", "application/json")
        .build()
    HttpClients.Api.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        throw IllegalStateException("Heaven names request failed: HTTP ${response.code}")
      }
      response.body?.string().orEmpty()
    }
  }

  private suspend fun resolveEmbeddedWallet(context: Context): EmbeddedEthereumWallet {
    val config = PrivyRuntimeConfig.fromBuildConfig()
    check(config.disabledReason() == null) { config.disabledReason() ?: "Privy auth is unavailable." }
    val privy = PrivyClientStore.get(context = context.applicationContext, config = config)
    val user = privy.user ?: throw IllegalStateException("Privy user session unavailable.")
    return resolvePrimaryWallet(user)
  }

  private suspend fun resolvePrimaryWallet(user: PrivyUser): EmbeddedEthereumWallet {
    user.embeddedEthereumWallets.firstOrNull { it.hdWalletIndex == 0 }?.let { return it }
    return user.createEthereumWallet(allowAdditional = false).getOrThrow()
  }

  private suspend fun signMessage(
    wallet: EmbeddedEthereumWallet,
    message: String,
  ): String {
    val response =
      wallet.provider.request(
        request = EthereumRpcRequest.personalSign(message, wallet.address),
      ).getOrThrow()
    return response.data.trim()
  }

  private fun apiBaseUrl(): String = SongPublishService.API_CORE_URL.trim().trimEnd('/')

  private fun encodePath(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

  private fun normalizeLabel(label: String): String {
    return label.trim().lowercase().filter { it.isLetterOrDigit() || it == '-' }
  }

  private fun normalizeAddress(address: String): String? {
    val normalized = address.trim()
    if (!normalized.matches(ADDRESS_REGEX)) return null
    return normalized.lowercase()
  }

  private fun buildNonce(): String = UUID.randomUUID().toString().replace("-", "")

  private val ADDRESS_REGEX = Regex("^0x[0-9a-fA-F]{40}$")
  private const val SIGNATURE_TTL_SECONDS = 120L
}
