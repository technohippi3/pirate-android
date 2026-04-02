package sc.pirate.app.auth.privy

import android.content.Context
import android.util.Log
import io.privy.auth.PrivyUser
import io.privy.auth.generateAuthorizationSignature
import io.privy.wallet.ethereum.EmbeddedEthereumWallet
import io.privy.wallet.ethereum.EthereumRpcRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import sc.pirate.app.BuildConfig
import sc.pirate.app.PirateChainConfig
import sc.pirate.app.profile.BaseNameRegistrationQuote
import sc.pirate.app.profile.BaseProfileContractCodec
import sc.pirate.app.profile.ContractProfileData
import sc.pirate.app.profile.ProfileContractApi
import sc.pirate.app.profile.PirateNameRecordsApi
import sc.pirate.app.util.HttpClients
import java.util.UUID
import io.privy.wallet.walletApi.WalletApiPayload

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
  private const val TAG = "PrivyRelayClient"
  private const val PRIVY_AUTHORIZATION_API_BASE_URL = "https://api.privy.io"
  private val jsonType = "application/json; charset=utf-8".toMediaType()
  private val canonicalJson =
    Json {
      encodeDefaults = false
      explicitNulls = false
    }

  private fun relayUrl(): String {
    return BuildConfig.PRIVY_RELAY_URL.trim().ifBlank { "https://pirate.sc/api/privy-relay" }
  }

  private fun summarizeToken(token: String?): String {
    if (token.isNullOrBlank()) return "absent"
    val segments = token.count { it == '.' } + 1
    return "present(len=${token.length},segments=$segments)"
  }

  private fun summarizePayload(payload: JSONObject): String {
    val intentType = payload.optJSONObject("intent")?.optString("type").orEmpty()
    val tx = payload.optJSONObject("transaction")
    val txTo = tx?.optString("to").orEmpty()
    val txValue = tx?.optString("value").orEmpty().ifBlank { "0" }
    return "intent=$intentType wallet=${payload.optString("walletAddress")} privyWalletId=${payload.optString("privyWalletId")} txTo=$txTo txValue=$txValue"
  }

  suspend fun registerPirateName(
    context: Context,
    quote: BaseNameRegistrationQuote,
  ): PrivyRelayNameRegistrationResult =
    withContext(Dispatchers.IO) {
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
              .apply {
                if (quote.priceWei.signum() > 0) {
                  put("value", quote.priceWei.toString())
                }
              },
          )
      val txHash = submitRelay(session = session, payload = payload)
      PrivyRelayNameRegistrationResult(
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
  ): String =
    withContext(Dispatchers.IO) {
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
              .put("data", BaseProfileContractCodec.encodeUpsertProfileCall(profileInput)),
          )
      submitRelay(session = session, payload = payload)
    }

  suspend fun submitOnboardingRecords(
    context: Context,
    nameLabel: String,
    nameTld: String,
    location: String?,
    avatarRef: String?,
  ): String? =
    withContext(Dispatchers.IO) {
      val trimmedLocation = location?.trim().orEmpty()
      val trimmedAvatar = avatarRef?.trim().orEmpty()
      if (trimmedLocation.isBlank() && trimmedAvatar.isBlank()) return@withContext null

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
      val node = PirateNameRecordsApi.computeNode(fullName)

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
              .put("data", PirateNameRecordsApi.encodeSetRecordsCallForRelay(node, keys, values)),
          )
      submitRelay(session = session, payload = payload)
    }

  suspend fun submitContractCall(
    context: Context,
    chainId: Long,
    to: String,
    data: String,
    value: String? = null,
    intentType: String,
    intentArgs: JSONObject? = null,
  ): String =
    withContext(Dispatchers.IO) {
      val session = resolveSession(context)
      val normalizedTo = to.trim()
      val normalizedData = data.trim()
      check(normalizedTo.startsWith("0x") && normalizedTo.length == 42) {
        "Invalid contract target."
      }
      check(normalizedData.startsWith("0x") && normalizedData.length > 2) {
        "Invalid contract calldata."
      }

      val payload =
        JSONObject()
          .put("chainId", chainId)
          .put("walletAddress", session.wallet.address)
          .put("privyWalletId", session.wallet.id)
          .put(
            "intent",
            JSONObject().apply {
              put("type", intentType.trim())
              intentArgs?.let { args ->
                val keys = args.keys()
                while (keys.hasNext()) {
                  val key = keys.next()
                  put(key, args.get(key))
                }
              }
            },
          )
          .put(
            "transaction",
            JSONObject()
              .put("to", normalizedTo)
              .put("data", normalizedData)
              .apply {
                value?.trim()?.takeIf { it.isNotEmpty() }?.let { put("value", it) }
              },
          )
      submitRelay(session = session, payload = payload)
    }

  suspend fun signPersonalMessage(
    context: Context,
    expectedWalletAddress: String,
    message: String,
  ): String =
    withContext(Dispatchers.IO) {
      val session = resolveSession(context)
      requireWalletAddressMatches(session.wallet, expectedWalletAddress)
      val response =
        session.wallet.provider.request(
          request = EthereumRpcRequest.personalSign(message, session.wallet.address),
        ).getOrThrow()
      response.data.trim().ifBlank {
        throw IllegalStateException("Privy wallet returned an empty personal_sign response.")
      }
    }

  suspend fun signTypedDataV4(
    context: Context,
    expectedWalletAddress: String,
    typedData: JSONObject,
  ): String =
    withContext(Dispatchers.IO) {
      val session = resolveSession(context)
      requireWalletAddressMatches(session.wallet, expectedWalletAddress)
      val response =
        session.wallet.provider.request(
          request =
            EthereumRpcRequest(
              method = "eth_signTypedData_v4",
              params =
                listOf(
                  session.wallet.address,
                  typedData.toString(),
                ),
            ),
        ).getOrThrow()
      response.data.trim().ifBlank {
        throw IllegalStateException("Privy wallet returned an empty eth_signTypedData_v4 response.")
      }
    }

  suspend fun signTransaction(
    context: Context,
    expectedWalletAddress: String,
    transaction: JSONObject,
  ): String =
    withContext(Dispatchers.IO) {
      val session = resolveSession(context)
      requireWalletAddressMatches(session.wallet, expectedWalletAddress)
      val response =
        session.wallet.provider.request(
          request =
            EthereumRpcRequest(
              method = "eth_signTransaction",
              params = listOf(transaction.toString()),
            ),
        ).getOrThrow()
      response.data.trim().ifBlank {
        throw IllegalStateException("Privy wallet returned an empty eth_signTransaction response.")
      }
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

    Log.d(
      TAG,
      "resolveSession: walletId=${wallet.id} walletAddress=${wallet.address} embeddedWalletCount=${user.embeddedEthereumWallets.size} accessToken=${summarizeToken(accessToken)} identityToken=${summarizeToken(identityToken)}",
    )

    if (accessToken == null && identityToken == null) {
      error("Privy session expired. Sign in again.")
    }

    return PrivyRelaySession(
      accessToken = accessToken,
      identityToken = if (accessToken == null) identityToken else null,
      user = user,
      wallet = wallet,
    )
  }

  private fun requireWalletAddressMatches(
    wallet: EmbeddedEthereumWallet,
    expectedWalletAddress: String,
  ) {
    val normalizedExpected = expectedWalletAddress.trim().lowercase()
    val normalizedWallet = wallet.address.trim().lowercase()
    check(normalizedExpected.isNotBlank() && normalizedWallet == normalizedExpected) {
      "Privy wallet does not match the active owner address."
    }
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

  private suspend fun submitRelay(
    session: PrivyRelaySession,
    payload: JSONObject,
  ): String {
    val debugId = UUID.randomUUID().toString().substring(0, 8)
    // Mobile already requires a live Privy session token before it reaches this path.
    // Prefer the simpler session-auth flow and avoid triggering extra wallet/browser auth.
    val authorizationSignature: String? = null
    if (authorizationSignature != null) {
      payload.put("authorizationSignature", authorizationSignature)
    }
    Log.d(
      TAG,
      "submitRelay[$debugId]: url=${relayUrl()} ${summarizePayload(payload)} auth(access=${summarizeToken(session.accessToken)}, identity=${summarizeToken(session.identityToken)}, ownerSig=${if (authorizationSignature != null) "present" else "absent"})",
    )
    val request =
      Request.Builder()
        .url(relayUrl())
        .post(payload.toString().toRequestBody(jsonType))
        .header("Content-Type", "application/json")
        .header("x-pirate-debug-id", debugId)
        .apply {
          session.accessToken?.let { header("Authorization", "Bearer $it") }
          session.identityToken?.let { header("x-privy-identity-token", it) }
        }
        .build()

    HttpClients.Api.newCall(request).execute().use { response ->
      val body = response.body?.string().orEmpty()
      val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
      val responseDebugId = response.header("x-pirate-debug-id").orEmpty()
      Log.d(
        TAG,
        "submitRelay[$debugId]: responseDebugId=$responseDebugId status=${response.code} body=${body.take(800)}",
      )
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

  private suspend fun buildRelayAuthorizationSignature(
    session: PrivyRelaySession,
    payload: JSONObject,
  ): String? {
    val walletId = session.wallet.id?.trim().orEmpty()
    if (walletId.isBlank()) {
      Log.w(TAG, "buildRelayAuthorizationSignature: missing wallet id for ${session.wallet.address}")
      return null
    }

    val signatureBody = buildPrivySendTransactionBody(payload)
    val signaturePayload =
      WalletApiPayload(
        1,
        "$PRIVY_AUTHORIZATION_API_BASE_URL/v1/wallets/$walletId/rpc",
        "POST",
        mapOf("privy-app-id" to BuildConfig.PRIVY_APP_ID),
        signatureBody,
      )
    val signature = session.user.generateAuthorizationSignature(signaturePayload).getOrThrow()
    Log.d(
      TAG,
      "buildRelayAuthorizationSignature: walletId=$walletId url=${signaturePayload.url} body=${canonicalJson.encodeToString(JsonObject.serializer(), signatureBody)}",
    )
    return signature
  }

  private fun buildPrivySendTransactionBody(
    payload: JSONObject,
  ): JsonObject {
    val transaction = payload.optJSONObject("transaction") ?: error("Relay payload transaction is missing.")
    val chainId = payload.optLong("chainId")
    return buildJsonObject {
      put("method", JsonPrimitive("eth_sendTransaction"))
      put("chain_type", JsonPrimitive("ethereum"))
      put("caip2", JsonPrimitive("eip155:$chainId"))
      put("sponsor", JsonPrimitive(true))
      put(
        "params",
        buildJsonObject {
          put(
            "transaction",
            buildJsonObject {
              put("to", JsonPrimitive(transaction.optString("to")))
              put("data", JsonPrimitive(transaction.optString("data")))
              transaction.optString("value").trim().takeIf { it.isNotEmpty() }?.let { value ->
                put("value", JsonPrimitive(value))
              }
            },
          )
        },
      )
    }
  }

}

private data class PrivyRelaySession(
  val accessToken: String?,
  val identityToken: String?,
  val user: PrivyUser,
  val wallet: EmbeddedEthereumWallet,
)
