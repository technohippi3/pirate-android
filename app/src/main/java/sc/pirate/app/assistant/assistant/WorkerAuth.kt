package sc.pirate.app.assistant

import android.content.Context
import android.util.Log
import sc.pirate.app.security.LocalSecp256k1Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Sign
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "WorkerAuth"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private data class CachedToken(
  val token: String,
  val expiresAt: Long,
)

data class WorkerAuthSession(
  val token: String,
  val wallet: String,
)

private val tokenCache = ConcurrentHashMap<String, CachedToken>()

private val httpClient = OkHttpClient.Builder()
  .connectTimeout(15, TimeUnit.SECONDS)
  .readTimeout(20, TimeUnit.SECONDS)
  .build()

/**
 * Build an authenticated worker session for a user address.
 * The wallet returned in [WorkerAuthSession.wallet] is the exact wallet bound to the JWT.
 */
suspend fun getWorkerAuthSession(
  appContext: Context,
  workerUrl: String,
  userAddress: String,
): WorkerAuthSession {
  val identity = LocalSecp256k1Store.getOrCreateIdentity(appContext, userAddress)
  val signingAddress = identity.signerAddress

  val key = "$workerUrl|$signingAddress"
  val now = System.currentTimeMillis()

  tokenCache[key]?.let { cached ->
    if (cached.expiresAt > now + 60_000) {
      return WorkerAuthSession(token = cached.token, wallet = signingAddress)
    }
  }

  Log.d(TAG, "Authenticating with worker $workerUrl as $signingAddress...")

  // Step 1: Get nonce
  val nonceBody = JSONObject().put("wallet", signingAddress).toString()
    .toRequestBody(JSON_MEDIA_TYPE)
  val nonceReq = Request.Builder()
    .url("${workerUrl.trimEnd('/')}/auth/nonce")
    .post(nonceBody)
    .build()

  val nonce = withContext(Dispatchers.IO) {
    httpClient.newCall(nonceReq).execute().use { resp ->
      val body = resp.body?.string().orEmpty()
      if (!resp.isSuccessful) throw IllegalStateException("Failed to get nonce (${resp.code}): $body")
      parseNonceFromAuthResponse(body)
    }
  }

  // Step 2: Sign the nonce with local key
  val signature = localSignMessage(nonce, identity.keyPair)

  // Step 3: Verify signature, get JWT
  val verifyPayload = JSONObject()
    .put("wallet", signingAddress)
    .put("signature", signature)
    .put("nonce", nonce)
    .toString()
    .toRequestBody(JSON_MEDIA_TYPE)
  val verifyReq = Request.Builder()
    .url("${workerUrl.trimEnd('/')}/auth/verify")
    .post(verifyPayload)
    .build()

  val token = withContext(Dispatchers.IO) {
    httpClient.newCall(verifyReq).execute().use { resp ->
      val body = resp.body?.string().orEmpty()
      if (!resp.isSuccessful) throw IllegalStateException("Auth verify failed (${resp.code}): $body")
      parseTokenFromVerifyResponse(body)
    }
  }

  tokenCache[key] = CachedToken(token = token, expiresAt = now + 55 * 60 * 1000)
  Log.d(TAG, "Authenticated successfully")
  return WorkerAuthSession(token = token, wallet = signingAddress)
}

fun clearWorkerAuthCache() {
  tokenCache.clear()
}

internal fun parseNonceFromAuthResponse(body: String): String {
  val json = JSONObject(body)
  return json.optString("nonce", "").takeIf { it.isNotBlank() }
    ?: throw IllegalStateException("Missing nonce in response")
}

internal fun parseTokenFromVerifyResponse(body: String): String {
  val json = JSONObject(body)
  return json.optString("token", "").takeIf { it.isNotBlank() }
    ?: throw IllegalStateException("Missing token in response")
}

/**
 * Sign a message using local secp256k1 key (EIP-191 personal sign).
 * Uses the same XMTP identity key stored in xmtp_prefs.
 * Returns the signature as a 0x-prefixed hex string.
 */
private suspend fun localSignMessage(
  message: String,
  keyPair: ECKeyPair,
): String = withContext(Dispatchers.IO) {
  val hash = hashWorkerAuthMessage(message)

  val sigData = Sign.signMessage(hash, keyPair, false)

  // r(32) + s(32) + v(1) — v is 27 or 28
  val sigBytes = ByteArray(65)
  System.arraycopy(sigData.r, 0, sigBytes, 0, 32)
  System.arraycopy(sigData.s, 0, sigBytes, 32, 32)
  sigBytes[64] = sigData.v[0]

  "0x" + sigBytes.joinToString("") { "%02x".format(it) }
}

private fun hashWorkerAuthMessage(message: String): ByteArray {
  val prefix = "\u0019Ethereum Signed Message:\n${message.length}"
  val prefixedMessage = prefix.toByteArray(Charsets.UTF_8) + message.toByteArray(Charsets.UTF_8)
  return keccak256(prefixedMessage)
}

private fun keccak256(input: ByteArray): ByteArray {
  val digest = org.bouncycastle.jcajce.provider.digest.Keccak.Digest256()
  return digest.digest(input)
}
