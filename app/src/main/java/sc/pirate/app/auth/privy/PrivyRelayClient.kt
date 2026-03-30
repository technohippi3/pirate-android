package sc.pirate.app.auth.privy

import android.content.Context
import io.privy.wallet.ethereum.EmbeddedEthereumWallet
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import sc.pirate.app.PirateChainConfig
import sc.pirate.app.profile.BaseNameRegistrationQuote
import sc.pirate.app.profile.ContractProfileData
import sc.pirate.app.profile.ProfileContractApi
import sc.pirate.app.profile.TempoNameRecordsApi
import sc.pirate.app.profile.TempoProfileContractApi
import sc.pirate.app.util.HttpClients

internal data class PrivyRelayLanguageEntry(
  val code: String,
  val proficiency: Int,
)

internal data class PrivyRelayProfileInput(
  val displayName: String,
  val age: Int,
  val gender: String,
  val location: String?,
  val languages: List<PrivyRelayLanguageEntry>,
  val avatarUrl: String? = null,
)

internal data class PrivyRelayNameRegistrationResult(
  val txHash: String,
  val fullName: String,
  val label: String,
  val node: String,
  val tld: String,
)

internal object PrivyRelayClient {
  private const val RELAY_URL = "https://pirate.sc/api/privy-relay"
  private val jsonType = "application/json; charset=utf-8".toMediaType()

  suspend fun registerPirateName(
    context: Context,
    quote: BaseNameRegistrationQuote,
  ): PrivyRelayNameRegistrationResult {
    val session = resolveSession(context)
    val payload =
      JSONObject()
        .put("chainId", PirateChainConfig.BASE_SEPOLIA_CHAIN_ID)
        .put("walletAddress", session.wallet.address)
        .put("privyWalletId", session.wallet.id)
        .put(
          "intent",
          JSONObject()
            .put("type", "pirate.name.register")
            .put("nameLabel", quote.label)
            .put("nameTld", quote.tld),
        )
        .put(
          "transaction",
          JSONObject()
            .put("to", PirateChainConfig.BASE_SEPOLIA_REGISTRY_V2)
            .put("data", quote.callData)
            .put("value", quote.priceWei.toString()),
        )
    val txHash = submitRelay(session = session, payload = payload)
    return PrivyRelayNameRegistrationResult(
      txHash = txHash,
      fullName = quote.fullName,
      label = quote.label,
      node = quote.node,
      tld = quote.tld,
    )
  }

  suspend fun upsertProfile(
    context: Context,
    input: PrivyRelayProfileInput,
  ): String {
    val session = resolveSession(context)
    val draft =
      ContractProfileData(
        displayName = input.displayName.trim(),
        age = input.age,
        gender = mapGender(input.gender),
        locationCityId = input.location?.trim().orEmpty(),
        languages =
          input.languages.map { entry ->
            sc.pirate.app.profile.ProfileLanguageEntry(
              code = entry.code.trim().lowercase(),
              proficiency = entry.proficiency,
            )
          },
        photoUri = input.avatarUrl?.trim().orEmpty(),
      )
    val profileInput = ProfileContractApi.buildProfileInput(draft)
    val payload =
      JSONObject()
        .put("chainId", PirateChainConfig.BASE_SEPOLIA_CHAIN_ID)
        .put("walletAddress", session.wallet.address)
        .put("privyWalletId", session.wallet.id)
        .put(
          "intent",
          JSONObject()
            .put("type", "pirate.profile.upsert")
            .put(
              "input",
              JSONObject()
                .put("displayName", input.displayName.trim())
                .put("age", input.age)
                .put("gender", input.gender.trim().lowercase())
                .put("location", input.location?.trim().orEmpty())
                .put(
                  "languages",
                  org.json.JSONArray().apply {
                    input.languages.forEach { entry ->
                      put(
                        JSONObject()
                          .put("code", entry.code.trim().lowercase())
                          .put("proficiency", entry.proficiency),
                      )
                    }
                  },
                )
                .put("avatarUrl", input.avatarUrl?.trim().orEmpty()),
            ),
        )
        .put(
          "transaction",
          JSONObject()
            .put("to", PirateChainConfig.BASE_SEPOLIA_PROFILE_V2)
            .put("data", TempoProfileContractApi.encodeUpsertProfileCall(profileInput)),
        )
    return submitRelay(session = session, payload = payload)
  }

  suspend fun submitOnboardingRecords(
    context: Context,
    nameLabel: String,
    nameTld: String,
    location: String?,
    avatarRef: String?,
  ): String? {
    val trimmedLocation = location?.trim().orEmpty()
    val trimmedAvatar = avatarRef?.trim().orEmpty()
    if (trimmedLocation.isBlank() && trimmedAvatar.isBlank()) return null

    val normalizedLabel = nameLabel.trim().lowercase()
    val normalizedTld = nameTld.trim().lowercase()
    val keys = mutableListOf<String>()
    val values = mutableListOf<String>()
    if (trimmedLocation.isNotBlank()) {
      keys += "heaven.location"
      values += trimmedLocation
    }
    if (trimmedAvatar.isNotBlank()) {
      keys += "avatar"
      values += trimmedAvatar
    }
    val fullName = "$normalizedLabel.$normalizedTld"
    val node = TempoNameRecordsApi.computeNode(fullName)

    val session = resolveSession(context)
    val payload =
      JSONObject()
        .put("chainId", PirateChainConfig.BASE_SEPOLIA_CHAIN_ID)
        .put("walletAddress", session.wallet.address)
        .put("privyWalletId", session.wallet.id)
        .put(
          "intent",
          JSONObject()
            .put("type", "pirate.onboarding.records")
            .put("nameLabel", normalizedLabel)
            .put("nameTld", normalizedTld)
            .put("avatarRef", trimmedAvatar.ifBlank { JSONObject.NULL })
            .put(
              "input",
              JSONObject().apply {
                if (trimmedLocation.isNotBlank()) {
                  put("location", trimmedLocation)
                }
              },
            ),
        )
        .put(
          "transaction",
          JSONObject()
            .put("to", PirateChainConfig.BASE_SEPOLIA_RECORDS_V1)
            .put("data", TempoNameRecordsApi.encodeSetRecordsCallForRelay(node, keys, values)),
        )
    return submitRelay(session = session, payload = payload)
  }

  private suspend fun resolveSession(context: Context): PrivyRelaySession {
    val config = PrivyRuntimeConfig.fromBuildConfig()
    val reason = config.disabledReason()
    check(reason == null) { reason ?: "Privy auth is unavailable." }

    val privy = PrivyClientStore.get(context = context.applicationContext, config = config)
    val user = privy.user ?: error("Privy user session unavailable.")
    val wallet =
      user.embeddedEthereumWallets.firstOrNull { it.hdWalletIndex == 0 }
        ?: user.createEthereumWallet(allowAdditional = false).getOrThrow()
    val accessToken = user.getAccessToken().getOrThrow().trim().ifBlank { null }
    val identityToken = user.identityToken?.trim()?.ifBlank { null }

    if (accessToken == null && identityToken == null) {
      error("Privy session expired. Sign in again.")
    }

    return PrivyRelaySession(
      accessToken = accessToken,
      identityToken = identityToken,
      wallet = wallet,
    )
  }

  private fun mapGender(gender: String): Int {
    return when (gender.trim().lowercase()) {
      "woman" -> 1
      "man" -> 2
      "nonbinary" -> 3
      "transwoman" -> 4
      "transman" -> 5
      "intersex" -> 6
      "other" -> 7
      else -> 0
    }
  }

  private fun submitRelay(
    session: PrivyRelaySession,
    payload: JSONObject,
  ): String {
    val request =
      Request.Builder()
        .url(RELAY_URL)
        .post(payload.toString().toRequestBody(jsonType))
        .header("Content-Type", "application/json")
        .apply {
          session.accessToken?.let { header("Authorization", "Bearer $it") }
          session.identityToken?.let { header("x-privy-identity-token", it) }
        }
        .build()

    HttpClients.Api.newCall(request).execute().use { response ->
      val body = response.body?.string().orEmpty()
      val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
      if (!response.isSuccessful) {
        throw IllegalStateException(
          json.optString("message", "Privy relay request failed: HTTP ${response.code}"),
        )
      }
      val txHash = json.optString("txHash", "").trim()
      if (txHash.startsWith("0x") && txHash.length == 66) {
        return txHash
      }
      val message = json.optString("message", "").trim()
      if (message.isNotBlank()) throw IllegalStateException(message)
      throw IllegalStateException("Privy relay returned an invalid response.")
    }
  }
}

private data class PrivyRelaySession(
  val accessToken: String?,
  val identityToken: String?,
  val wallet: EmbeddedEthereumWallet,
)
