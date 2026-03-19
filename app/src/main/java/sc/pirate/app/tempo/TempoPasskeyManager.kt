package sc.pirate.app.tempo

import android.app.Activity
import android.util.Base64
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import sc.pirate.app.BuildConfig
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Manages passkey creation and assertion for Tempo.
 * No auth service needed — passkey is the account.
 */
object TempoPasskeyManager {

    const val DEFAULT_RP_ID = "pirate.sc"
    const val DEFAULT_RP_NAME = "Pirate"
    private const val PREFS_NAME = "tempo_passkey"
    private const val ACCOUNTS_JSON_KEY = "accounts_json"
    private const val TEMPO_KEY_MANAGER_PATH = "/api/wallet/tempo-key-manager"
    private const val TAG = "TempoPasskeyManager"
    private val keyManagerClient = OkHttpClient()
    private val keyManagerJsonType = "application/json; charset=utf-8".toMediaType()

    private fun prefs(activity: Activity): SharedPreferences =
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun normalizeRpId(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("rpId cannot be empty")
        val withScheme =
            if (Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
                trimmed
            } else {
                "https://$trimmed"
            }
        val host = try {
            java.net.URL(withScheme).host
        } catch (_: Throwable) {
            throw IllegalArgumentException("Invalid rpId: $value")
        }
        val normalized = host.trim().lowercase()
        if (normalized.isEmpty()) throw IllegalArgumentException("Invalid rpId: $value")
        return normalized
    }

    private fun normalizeCredentialId(value: String): String {
        return value.trim()
            .replace('+', '-')
            .replace('/', '_')
            .trimEnd('=')
    }

    private fun normalizeHex(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val withPrefix =
            if (trimmed.startsWith("0x", ignoreCase = true)) trimmed else "0x$trimmed"
        val rawHex = withPrefix.removePrefix("0x").removePrefix("0X")
        if (rawHex.isEmpty() || rawHex.length % 2 != 0) return null
        if (!rawHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return "0x${rawHex.lowercase()}"
    }

    private fun parseP256PublicKeyHex(value: String?): P256Utils.P256PublicKey? {
        val normalized = normalizeHex(value) ?: return null
        val bytes = runCatching { P256Utils.hexToBytes(normalized) }.getOrNull() ?: return null
        val xy =
            when (bytes.size) {
                64 -> bytes
                65 -> {
                    if (bytes[0] != 0x04.toByte()) return null
                    bytes.copyOfRange(1, 65)
                }

                else -> return null
            }
        return P256Utils.P256PublicKey(
            x = xy.copyOfRange(0, 32),
            y = xy.copyOfRange(32, 64),
        )
    }

    private fun extractPublicKeyHexFromPayload(payload: JSONObject?): String? {
        if (payload == null) return null
        normalizeHex(payload.optString("publicKey", ""))?.let { return it }
        val nested = payload.optJSONObject("credential")
        normalizeHex(nested?.optString("publicKey", ""))?.let { return it }
        extractPublicKeyHexFromPayload(payload.optJSONObject("raw"))?.let { return it }
        return null
    }

    private fun decodeCredentialIdBytes(value: String): ByteArray? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (
            trimmed.startsWith("0x", ignoreCase = true) &&
                (trimmed.length - 2) % 2 == 0
        ) {
            return runCatching { P256Utils.hexToBytes(trimmed) }.getOrNull()
        }
        return runCatching {
            val normalized = trimmed.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4)
            Base64.decode(padded, Base64.DEFAULT)
        }.getOrNull()
    }

    private fun credentialIdCandidates(value: String): List<String> {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return emptyList()
        val candidates = LinkedHashSet<String>()
        candidates += trimmed
        val bytes = decodeCredentialIdBytes(trimmed) ?: return candidates.toList()
        candidates += P256Utils.toBase64Url(bytes)
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        candidates += b64
        candidates += b64.trimEnd('=')
        candidates += "0x${P256Utils.bytesToHex(bytes)}"
        return candidates.toList()
    }

    private fun resolveApiCoreBaseUrlOrNull(): String? {
        val configured = BuildConfig.API_CORE_URL.trim().trimEnd('/')
        return if (configured.startsWith("https://") || configured.startsWith("http://")) configured else null
    }

    private fun resolveTempoKeyManagerBaseUrlOrNull(): String? {
        val apiBase = resolveApiCoreBaseUrlOrNull() ?: return null
        return if (apiBase.endsWith("/api")) {
            "$apiBase/wallet/tempo-key-manager"
        } else {
            "$apiBase$TEMPO_KEY_MANAGER_PATH"
        }
    }

    private fun toUncompressedPubKeyHex(pubKey: P256Utils.P256PublicKey): String =
        "0x04${pubKey.xHex}${pubKey.yHex}"

    private fun parseHexBytes(value: String?): ByteArray? {
        val trimmed = value?.trim().orEmpty()
        if (!trimmed.startsWith("0x", ignoreCase = true)) return null
        val hex = trimmed.removePrefix("0x").removePrefix("0X")
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return runCatching { P256Utils.hexToBytes("0x$hex") }.getOrNull()
    }

    private suspend fun fetchKeyManagerChallenge(): ByteArray? =
        withContext(Dispatchers.IO) {
            val keyManagerBase = resolveTempoKeyManagerBaseUrlOrNull() ?: return@withContext null
            val request = Request.Builder()
                .url("$keyManagerBase/challenge")
                .get()
                .build()
            runCatching {
                keyManagerClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) return@use null
                    val payload = runCatching { JSONObject(body) }.getOrNull() ?: return@use null
                    parseHexBytes(payload.optString("challenge", ""))
                }
            }.getOrNull()
        }

    private suspend fun storeCredentialInKeyManager(
        credentialId: String,
        credentialPayload: JSONObject,
        publicKeyHex: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val keyManagerBase = resolveTempoKeyManagerBaseUrlOrNull() ?: return@withContext false
            val encoded =
                runCatching { URLEncoder.encode(normalizeCredentialId(credentialId), Charsets.UTF_8.name()) }
                    .getOrNull() ?: return@withContext false
            val payload = JSONObject()
                .put(
                    "credential",
                    credentialPayload,
                )
                .put("publicKey", publicKeyHex)
            val request =
                Request.Builder()
                    .url("$keyManagerBase/$encoded")
                    .post(payload.toString().toRequestBody(keyManagerJsonType))
                    .build()
            runCatching {
                keyManagerClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val responsePreview = response.body?.string().orEmpty().take(180)
                        Log.w(TAG, "Key manager store failed: status=${response.code} body='$responsePreview'")
                    }
                    response.isSuccessful
                }
            }.getOrDefault(false)
        }

    private suspend fun syncCredentialMetadataToKeyManager(
        credentialId: String,
        credentialPayload: JSONObject,
        account: PasskeyAccount,
        reason: String,
    ) {
        val synced =
            storeCredentialInKeyManager(
                credentialId = credentialId,
                credentialPayload = credentialPayload,
                publicKeyHex = toUncompressedPubKeyHex(account.pubKey),
            )
        if (synced) {
            Log.i(
                TAG,
                "Key manager metadata sync succeeded: reason=$reason credentialIdLen=${credentialId.length}",
            )
        } else {
            Log.w(
                TAG,
                "Key manager metadata sync failed: reason=$reason credentialIdLen=${credentialId.length}",
            )
        }
    }

    private suspend fun fetchPublicKeyFromKeyManager(credentialId: String): String? =
        withContext(Dispatchers.IO) {
            val keyManagerBase = resolveTempoKeyManagerBaseUrlOrNull() ?: return@withContext null
            val candidates = credentialIdCandidates(credentialId)
            Log.i(
                TAG,
                "Key manager lookup start: credentialIdLen=${credentialId.length}, candidates=${candidates.size}",
            )
            for ((index, candidate) in candidates.withIndex()) {
                val encoded =
                    runCatching { URLEncoder.encode(candidate, Charsets.UTF_8.name()) }.getOrNull()
                        ?: continue
                val request = Request.Builder()
                    .url("$keyManagerBase/$encoded")
                    .get()
                    .build()
                val publicKeyHex = runCatching {
                    keyManagerClient.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            Log.w(
                                TAG,
                                "Key manager lookup miss: idx=$index status=${response.code} candidateLen=${candidate.length} body='${body.take(180)}'",
                            )
                            return@use null
                        }
                        if (body.isBlank()) {
                            Log.w(
                                TAG,
                                "Key manager lookup empty body: idx=$index candidateLen=${candidate.length}",
                            )
                            return@use null
                        }
                        val payload = runCatching { JSONObject(body) }.getOrNull()
                        if (payload == null) {
                            Log.w(
                                TAG,
                                "Key manager lookup invalid JSON: idx=$index candidateLen=${candidate.length}",
                            )
                            return@use null
                        }
                        extractPublicKeyHexFromPayload(payload).also { extracted ->
                            if (extracted.isNullOrBlank()) {
                                Log.w(
                                    TAG,
                                    "Key manager lookup missing public key: idx=$index candidateLen=${candidate.length}",
                                )
                            }
                        }
                    }
                }.getOrNull()
                if (!publicKeyHex.isNullOrBlank()) return@withContext publicKeyHex
            }
            Log.w(
                TAG,
                "Key manager lookup exhausted all candidates without a public key: credentialIdLen=${credentialId.length}",
            )
            null
        }

    private fun resolveAccountFromPublicKeyHex(
        credentialId: String,
        rpId: String,
        publicKeyHex: String?,
        authenticatorData: ByteArray,
        clientDataJSON: ByteArray,
        signatureDer: ByteArray,
    ): PasskeyAccount? {
        val pubKey = parseP256PublicKeyHex(publicKeyHex) ?: return null
        val verified =
            P256Utils.verifyAssertionSignature(
                pubKey = pubKey,
                authenticatorData = authenticatorData,
                clientDataJSON = clientDataJSON,
                signatureDer = signatureDer,
            )
        if (!verified) return null
        return PasskeyAccount(
            pubKey = pubKey,
            address = P256Utils.deriveAddress(pubKey),
            credentialId = normalizeCredentialId(credentialId),
            rpId = normalizeRpId(rpId),
        )
    }

    data class PasskeyAccount(
        val pubKey: P256Utils.P256PublicKey,
        val address: String,
        val credentialId: String, // base64url rawId
        val rpId: String,
    )

    data class PasskeyAssertion(
        val authenticatorData: ByteArray,
        val clientDataJSON: ByteArray,
        val signatureR: ByteArray,  // 32 bytes
        val signatureS: ByteArray,  // 32 bytes
        val pubKey: P256Utils.P256PublicKey,
    )

    /** Create a new passkey and derive the Tempo account address. */
    suspend fun createAccount(
        activity: Activity,
        rpId: String = DEFAULT_RP_ID,
        rpName: String = DEFAULT_RP_NAME,
    ): PasskeyAccount {
        val normalizedRpId = normalizeRpId(rpId)
        val normalizedRpName = rpName.trim().ifEmpty { DEFAULT_RP_NAME }
        val challenge =
            fetchKeyManagerChallenge()
                ?: throw IllegalStateException("Could not fetch Tempo key manager challenge for passkey registration.")
        val userId = ByteArray(16).also { SecureRandom().nextBytes(it) }

        val options = JSONObject().apply {
            put("challenge", P256Utils.toBase64Url(challenge))
            put("rp", JSONObject().put("id", normalizedRpId).put("name", normalizedRpName))
            put("user", JSONObject()
                .put("id", P256Utils.toBase64Url(userId))
                .put("name", "pirate-user")
                .put("displayName", "Pirate User"))
            put("pubKeyCredParams", JSONArray().put(
                JSONObject().put("alg", -7).put("type", "public-key") // ES256 = P256
            ))
            put("timeout", 60000)
            put("attestation", "none")
            put("authenticatorSelection", JSONObject()
                .put("residentKey", "required")
                .put("userVerification", "required"))
        }

        val manager = CredentialManager.create(activity)
        val request = CreatePublicKeyCredentialRequest(options.toString())

        val result = suspendCoroutine { cont ->
            manager.createCredentialAsync(
                activity, request, null, activity.mainExecutor,
                object : CredentialManagerCallback<CreateCredentialResponse, CreateCredentialException> {
                    override fun onResult(result: CreateCredentialResponse) {
                        if (result is CreatePublicKeyCredentialResponse) {
                            cont.resume(result)
                        } else {
                            cont.resumeWithException(
                                IllegalStateException("unexpected response: ${result::class.simpleName}"))
                        }
                    }
                    override fun onError(e: CreateCredentialException) {
                        cont.resumeWithException(e)
                    }
                }
            )
        }

        val regJson = JSONObject(result.registrationResponseJson)
        val response = regJson.getJSONObject("response")
        val attestationObjectB64 = response.getString("attestationObject")
        val rawId = normalizeCredentialId(regJson.getString("rawId"))

        val pubKey = P256Utils.extractP256KeyFromRegistration(attestationObjectB64)
        val address = P256Utils.deriveAddress(pubKey)

        val account = PasskeyAccount(
            pubKey = pubKey,
            address = address,
            credentialId = rawId,
            rpId = normalizedRpId,
        )
        val storedRemotely =
            storeCredentialInKeyManager(
                credentialId = rawId,
                credentialPayload = regJson,
                publicKeyHex = toUncompressedPubKeyHex(pubKey),
            )
        if (!storedRemotely) {
            Log.w(TAG, "Key manager store failed after passkey creation: credentialIdLen=${rawId.length}")
            throw IllegalStateException(
                "Passkey created but remote account metadata persistence failed. Please try Sign Up again."
            )
        }
        Log.i(TAG, "Key manager store success after passkey creation: credentialIdLen=${rawId.length}")
        saveAccount(activity, account)
        return account
    }

    /** Login with an existing passkey. Prompts the passkey picker (no allowCredentials filter). */
    suspend fun login(
        activity: Activity,
        rpId: String = DEFAULT_RP_ID,
    ): PasskeyAccount {
        val normalizedRpId = normalizeRpId(rpId)
        val knownAccounts = loadAccounts(activity)

        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val options = JSONObject().apply {
            put("challenge", P256Utils.toBase64Url(challenge))
            put("timeout", 60000)
            put("userVerification", "required")
            put("rpId", normalizedRpId)
            // No allowCredentials — shows all passkeys for this RP
        }

        val manager = CredentialManager.create(activity)
        val request = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(options.toString())))

        val result = suspendCoroutine { cont ->
            manager.getCredentialAsync(
                activity, request, null, activity.mainExecutor,
                object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                    override fun onResult(result: GetCredentialResponse) {
                        val credential = result.credential
                        if (credential is PublicKeyCredential) {
                            cont.resume(credential)
                        } else {
                            cont.resumeWithException(
                                IllegalStateException("unexpected credential: ${credential::class.simpleName}"))
                        }
                    }
                    override fun onError(e: GetCredentialException) {
                        cont.resumeWithException(e)
                    }
                }
            )
        }

        val authJson = JSONObject(result.authenticationResponseJson)
        val rawId = normalizeCredentialId(authJson.getString("rawId"))
        val response = authJson.getJSONObject("response")
        val authenticatorData = P256Utils.base64UrlToBytes(response.getString("authenticatorData"))
        val clientDataJSON = P256Utils.base64UrlToBytes(response.getString("clientDataJSON"))
        val signatureDer = P256Utils.base64UrlToBytes(response.getString("signature"))
        Log.i(
            TAG,
            "Login assertion received: rawIdLen=${rawId.length}, knownAccounts=${knownAccounts.size}, rpId=$normalizedRpId",
        )

        val matched =
            knownAccounts.firstOrNull {
                it.rpId == normalizedRpId && normalizeCredentialId(it.credentialId) == rawId
            }
        if (matched != null) {
            // Promote last-used account to active.
            saveAccount(activity, matched)
            syncCredentialMetadataToKeyManager(
                credentialId = rawId,
                credentialPayload = authJson,
                account = matched,
                reason = "local_match",
            )
            return matched
        }

        // Some providers can return a credential-id representation that differs
        // from what was persisted during registration. Fallback to cryptographic
        // verification against known public keys.
        val signatureMatched =
            knownAccounts.firstOrNull { account ->
                account.rpId == normalizedRpId &&
                    P256Utils.verifyAssertionSignature(
                        pubKey = account.pubKey,
                        authenticatorData = authenticatorData,
                        clientDataJSON = clientDataJSON,
                        signatureDer = signatureDer,
                    )
            }
        if (signatureMatched != null) {
            Log.w(
                TAG,
                "Resolved passkey via signature fallback; credentialId mismatch (rawId len=${rawId.length})",
            )
            val updated = signatureMatched.copy(credentialId = rawId)
            saveAccount(activity, updated)
            syncCredentialMetadataToKeyManager(
                credentialId = rawId,
                credentialPayload = authJson,
                account = updated,
                reason = "signature_match",
            )
            return updated
        }

        // If no local account matches, try to recover using provider payload / key-manager metadata.
        val payloadRecovered =
            resolveAccountFromPublicKeyHex(
                credentialId = rawId,
                rpId = normalizedRpId,
                publicKeyHex = extractPublicKeyHexFromPayload(authJson),
                authenticatorData = authenticatorData,
                clientDataJSON = clientDataJSON,
                signatureDer = signatureDer,
            )
        if (payloadRecovered != null) {
            saveAccount(activity, payloadRecovered)
            Log.i(TAG, "Recovered passkey account from assertion payload for credentialId len=${rawId.length}")
            syncCredentialMetadataToKeyManager(
                credentialId = rawId,
                credentialPayload = authJson,
                account = payloadRecovered,
                reason = "payload_recovery",
            )
            return payloadRecovered
        }

        val keyManagerRecovered =
            resolveAccountFromPublicKeyHex(
                credentialId = rawId,
                rpId = normalizedRpId,
                publicKeyHex = fetchPublicKeyFromKeyManager(rawId),
                authenticatorData = authenticatorData,
                clientDataJSON = clientDataJSON,
                signatureDer = signatureDer,
            )
        if (keyManagerRecovered != null) {
            saveAccount(activity, keyManagerRecovered)
            Log.i(TAG, "Recovered passkey account from key manager for credentialId len=${rawId.length}")
            return keyManagerRecovered
        }

        if (knownAccounts.isNotEmpty()) {
            Log.w(
                TAG,
                "Login could not match selected passkey to known local accounts: rawIdLen=${rawId.length}, knownAccounts=${knownAccounts.size}",
            )
            throw IllegalStateException(
                "Selected passkey could not be matched to a Pirate account on this device."
            )
        }

        Log.w(
            TAG,
            "Login failed remote recovery: rawIdLen=${rawId.length}, knownAccounts=0",
        )
        throw IllegalStateException(
            "No local passkey account found and remote recovery failed for this passkey."
        )
    }

    /** Load active account from SharedPreferences. */
    fun loadAccount(activity: Activity): PasskeyAccount? {
        return loadAccounts(activity).firstOrNull()
    }

    private fun loadAccounts(activity: Activity): List<PasskeyAccount> {
        val p = prefs(activity)
        val parsed = runCatching {
            val raw = p.getString(ACCOUNTS_JSON_KEY, null)?.trim().orEmpty()
            if (raw.isEmpty()) return@runCatching emptyList()
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val xHex = item.optString("pub_key_x", "")
                    val yHex = item.optString("pub_key_y", "")
                    val address = item.optString("address", "")
                    val credentialId = normalizeCredentialId(item.optString("credential_id", ""))
                    val rpId = normalizeRpId(item.optString("rp_id", DEFAULT_RP_ID))
                    if (xHex.isEmpty() || yHex.isEmpty() || address.isEmpty() || credentialId.isEmpty()) continue
                    add(
                        PasskeyAccount(
                            pubKey = P256Utils.P256PublicKey(
                                P256Utils.hexToBytes(xHex),
                                P256Utils.hexToBytes(yHex),
                            ),
                            address = address,
                            credentialId = credentialId,
                            rpId = rpId,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
        return parsed
    }

    private fun saveAccount(activity: Activity, account: PasskeyAccount) {
        val normalizedAccount = account.copy(
            credentialId = normalizeCredentialId(account.credentialId),
            rpId = normalizeRpId(account.rpId),
        )
        val remainder = loadAccounts(activity).filterNot {
            normalizeCredentialId(it.credentialId) == normalizedAccount.credentialId &&
                normalizeRpId(it.rpId) == normalizedAccount.rpId
        }
        val allAccounts = listOf(normalizedAccount) + remainder
        val serialized = JSONArray().apply {
            allAccounts.forEach { item ->
                put(
                    JSONObject()
                        .put("pub_key_x", item.pubKey.xHex)
                        .put("pub_key_y", item.pubKey.yHex)
                        .put("address", item.address)
                        .put("credential_id", item.credentialId)
                        .put("rp_id", item.rpId),
                )
            }
        }

        prefs(activity).edit()
            .putString(ACCOUNTS_JSON_KEY, serialized.toString())
            .apply()
    }

    /**
     * Assert the passkey with the PRF extension and return the 32-byte root seed.
     *
     * The PRF extension evaluates HMAC(passkey-internal-key, label) without
     * exposing the passkey private key. The result is deterministic across
     * devices for the same passkey, making it suitable as a cross-device
     * ownership root for content decryption.
     *
     * Callers should cache the result in [PrfRootSeedStore] to avoid repeated
     * biometric prompts.
     *
     * @throws IllegalStateException("webauthn_prf_unavailable") if the authenticator
     *   does not support the PRF extension or the result is missing.
     */
    suspend fun getPrfRootSeed(
        activity: Activity,
        account: PasskeyAccount,
        rpId: String = DEFAULT_RP_ID,
    ): ByteArray {
        val normalizedRpId = normalizeRpId(rpId)
        if (account.rpId != normalizedRpId) {
            throw IllegalStateException(
                "Passkey account is bound to ${account.rpId}; requested rpId is $normalizedRpId",
            )
        }

        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val prfLabel = P256Utils.toBase64Url(
            PurchaseContentCrypto.CONTENT_KEY_DERIVATION_LABEL.toByteArray(Charsets.UTF_8),
        )

        val options = JSONObject().apply {
            put("challenge", P256Utils.toBase64Url(challenge))
            put("timeout", 60000)
            put("userVerification", "required")
            put("rpId", normalizedRpId)
            put("allowCredentials", JSONArray().put(
                JSONObject().put("id", account.credentialId).put("type", "public-key"),
            ))
            put("extensions", JSONObject().put(
                "prf", JSONObject().put(
                    "eval", JSONObject().put("first", prfLabel),
                ),
            ))
        }

        val manager = CredentialManager.create(activity)
        val request = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(options.toString())))

        val result = suspendCoroutine { cont ->
            manager.getCredentialAsync(
                activity, request, null, activity.mainExecutor,
                object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                    override fun onResult(result: GetCredentialResponse) {
                        val credential = result.credential
                        if (credential is PublicKeyCredential) {
                            cont.resume(credential)
                        } else {
                            cont.resumeWithException(
                                IllegalStateException("unexpected credential: ${credential::class.simpleName}"))
                        }
                    }
                    override fun onError(e: GetCredentialException) {
                        cont.resumeWithException(e)
                    }
                },
            )
        }

        val authJson = JSONObject(result.authenticationResponseJson)
        val prfFirstB64 = authJson
            .optJSONObject("clientExtensionResults")
            ?.optJSONObject("prf")
            ?.optJSONObject("results")
            ?.optString("first", "")
            ?.ifBlank { null }
            ?: throw IllegalStateException("webauthn_prf_unavailable")

        val rootSeed = P256Utils.base64UrlToBytes(prfFirstB64)
        if (rootSeed.size != 32) {
            throw IllegalStateException("webauthn_prf_invalid_length:${rootSeed.size}")
        }
        return rootSeed
    }

    /**
     * Sign a challenge (e.g., tx hash) with the passkey.
     * Returns the raw WebAuthn assertion components needed for a Tempo WebAuthn signature.
     */
    suspend fun sign(
        activity: Activity,
        challenge: ByteArray,
        account: PasskeyAccount,
        rpId: String = DEFAULT_RP_ID,
    ): PasskeyAssertion {
        val caller =
            Throwable().stackTrace.firstOrNull { frame ->
                !frame.className.contains("TempoPasskeyManager")
            }
        Log.w(
            TAG,
            "Passkey sign requested for ${account.address} via ${caller?.className}.${caller?.methodName}:${caller?.lineNumber}",
        )

        val normalizedRpId = normalizeRpId(rpId)
        if (account.rpId != normalizedRpId) {
            throw IllegalStateException(
                "Passkey account is bound to ${account.rpId}; requested rpId is $normalizedRpId",
            )
        }

        val options = JSONObject().apply {
            put("challenge", P256Utils.toBase64Url(challenge))
            put("timeout", 60000)
            put("userVerification", "required")
            put("rpId", normalizedRpId)
            put("allowCredentials", JSONArray().put(
                JSONObject().put("id", account.credentialId).put("type", "public-key")
            ))
        }

        val manager = CredentialManager.create(activity)
        val request = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(options.toString())))

        val result = suspendCoroutine { cont ->
            manager.getCredentialAsync(
                activity, request, null, activity.mainExecutor,
                object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                    override fun onResult(result: GetCredentialResponse) {
                        val credential = result.credential
                        if (credential is PublicKeyCredential) {
                            cont.resume(credential)
                        } else {
                            cont.resumeWithException(
                                IllegalStateException("unexpected credential: ${credential::class.simpleName}"))
                        }
                    }
                    override fun onError(e: GetCredentialException) {
                        cont.resumeWithException(e)
                    }
                }
            )
        }

        val authJson = JSONObject(result.authenticationResponseJson)
        val response = authJson.getJSONObject("response")

        val authenticatorData = P256Utils.base64UrlToBytes(response.getString("authenticatorData"))
        val clientDataJSON = P256Utils.base64UrlToBytes(response.getString("clientDataJSON"))
        val signatureBytes = P256Utils.base64UrlToBytes(response.getString("signature"))

        // Parse DER signature into (r, s)
        val (r, s) = P256Utils.parseDerSignature(signatureBytes)

        return PasskeyAssertion(
            authenticatorData = authenticatorData,
            clientDataJSON = clientDataJSON,
            signatureR = r,
            signatureS = s,
            pubKey = account.pubKey,
        )
    }
}
