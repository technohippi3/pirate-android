package com.pirate.app.music

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import com.pirate.app.BuildConfig
import com.pirate.app.tempo.ContentKeyManager
import com.pirate.app.tempo.P256Utils
import com.pirate.app.tempo.PrfRootSeedStore
import com.pirate.app.tempo.PurchaseContentCrypto
import com.pirate.app.tempo.TempoPasskeyManager
import com.pirate.app.util.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

internal data class PlaybackResolveResult(
    val track: MusicTrack?,
    val message: String?,
)

private data class PurchaseAccessInfo(
    val manifestRef: String,
    val artifactRef: String,
    val artifactIvBytes: Int,
    val errorCode: String? = null,
)

private data class PurchaseReEnvelopeResult(
    val success: Boolean,
    val errorCode: String? = null,
    val errorDetail: String? = null,
)

private data class ReEnvelopeAccessRefreshResult(
    val accessInfo: PurchaseAccessInfo? = null,
    val errorDetail: String? = null,
)

private data class KaraokeStemInfo(
    val stemType: String,
    val artifactRef: String,
    val artifactIvBytes: Int,
)

private data class PurchaseKaraokeAccessInfo(
    val manifestRef: String,
    val stems: List<KaraokeStemInfo>,
    val errorCode: String? = null,
)

private val jsonType = "application/json; charset=utf-8".toMediaType()
private const val TAG_PLAYBACK = "MusicPlaybackResolver"
private const val MANIFEST_FETCH_ATTEMPTS = 6
private const val MANIFEST_FETCH_DELAY_MS = 1_000L
internal const val PURCHASE_UNLOCK_BUDGET_MS = 10_000L

internal data class KaraokeResolveResult(
    val stemsByType: Map<String, String>,
    val message: String?,
)

internal data class PurchasedTrackDownloadResult(
    val success: Boolean,
    val alreadyDownloaded: Boolean = false,
    val mediaUri: String? = null,
    val error: String? = null,
)

internal fun downloadLookupIdForTrack(track: MusicTrack): String? {
    val contentId = track.contentId?.trim()?.lowercase().orEmpty()
    if (contentId.isNotBlank()) return contentId
    val purchaseId = track.purchaseId?.trim()?.lowercase().orEmpty()
    if (purchaseId.isBlank()) return null
    return "purchase:$purchaseId"
}

private fun resolveApiBase(): String? {
    val base = SongPublishService.API_CORE_URL.trim().trimEnd('/')
    if (!base.startsWith("http://") && !base.startsWith("https://")) return null
    return base
}

private fun resolveArweaveRef(ref: String): String? {
    val trimmed = ref.trim()
    if (!trimmed.startsWith("ar://", ignoreCase = true)) return null
    val txId = trimmed.substring(5).trim()
    if (txId.isEmpty()) return null
    val gateway = BuildConfig.ARWEAVE_GATEWAY_URL.trim().trimEnd('/')
    return "$gateway/$txId"
}

private fun resolveCanonicalTrackIdForPlayback(track: MusicTrack): String? {
    return resolveSongTrackId(track)
        ?.trim()
        ?.lowercase()
        ?.ifBlank { null }
}

private fun encodeQueryValue(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name())

private suspend fun requestPurchaseAccess(
    apiBase: String,
    ownerAddress: String,
    purchaseId: String,
): PurchaseAccessInfo = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder()
            .url("$apiBase/api/music/purchase/${encodeQueryValue(purchaseId)}/access")
            .header("X-User-Address", ownerAddress)
            .get()
            .build()

        HttpClients.Api.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(payload) }.getOrNull()
            if (!response.isSuccessful) {
                return@withContext PurchaseAccessInfo(
                    manifestRef = "",
                    artifactRef = "",
                    artifactIvBytes = 12,
                    errorCode = json?.optString("error", "").orEmpty().ifBlank { null },
                )
            }
            val manifestRef = json?.optString("manifestRef", "").orEmpty().ifBlank { null }
                ?: return@withContext PurchaseAccessInfo(
                    manifestRef = "",
                    artifactRef = "",
                    artifactIvBytes = 12,
                    errorCode = "purchase_manifest_not_ready",
                )
            val artifact = json?.optJSONObject("artifact")
            val artifactRef = artifact?.optString("ref", "").orEmpty().ifBlank { null } ?: ""
            val artifactIvBytes = artifact?.optInt("ivBytes", 12) ?: 12
            return@withContext PurchaseAccessInfo(
                manifestRef = manifestRef,
                artifactRef = artifactRef,
                artifactIvBytes = artifactIvBytes,
            )
        }
    } catch (error: Exception) {
        Log.w(
            TAG_PLAYBACK,
            "requestPurchaseAccess failed purchaseId=$purchaseId error=${error.message}",
        )
        PurchaseAccessInfo(
            manifestRef = "",
            artifactRef = "",
            artifactIvBytes = 12,
            errorCode = "network_error",
        )
    }
}

private fun resolveBuyerContentPublicKey(context: Context): String? {
    val keyPair = runCatching { ContentKeyManager.getOrCreate(context) }.getOrNull() ?: return null
    val publicKeyBytes = keyPair.publicKey
    if (publicKeyBytes.size != 65 || publicKeyBytes[0] != 0x04.toByte()) return null
    return "0x${P256Utils.bytesToHex(publicKeyBytes)}"
}

private suspend fun requestPurchaseReEnvelope(
    apiBase: String,
    ownerAddress: String,
    purchaseId: String,
    buyerContentPublicKey: String,
): PurchaseReEnvelopeResult = withContext(Dispatchers.IO) {
    try {
        val payload = JSONObject().put("buyerContentPublicKey", buyerContentPublicKey)
        val request = Request.Builder()
            .url("$apiBase/api/music/purchase/${encodeQueryValue(purchaseId)}/re-envelope")
            .header("X-User-Address", ownerAddress)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonType))
            .build()

        HttpClients.Api.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            val body = runCatching { JSONObject(bodyText) }.getOrNull()
            if (!response.isSuccessful) {
                return@withContext PurchaseReEnvelopeResult(
                    success = false,
                    errorCode = body?.optString("error", "").orEmpty().ifBlank { "purchase_manifest_not_ready" },
                    errorDetail = body?.optString("detail", "").orEmpty().ifBlank { null },
                )
            }

            val manifestRef = body?.optString("manifestRef", "").orEmpty().trim()
            return@withContext PurchaseReEnvelopeResult(
                success = manifestRef.isNotBlank(),
            )
        }
    } catch (error: Exception) {
        Log.w(
            TAG_PLAYBACK,
            "requestPurchaseReEnvelope failed purchaseId=$purchaseId error=${error.message}",
        )
        PurchaseReEnvelopeResult(
            success = false,
            errorCode = "network_error",
            errorDetail = "Network error while preparing your purchase. Try again.",
        )
    }
}

private suspend fun reEnvelopeAndRefreshPurchaseAccess(
    context: Context,
    apiBase: String,
    ownerAddress: String,
    purchaseId: String,
): ReEnvelopeAccessRefreshResult? {
    val buyerContentPublicKey = resolveBuyerContentPublicKey(context) ?: return null
    val reEnvelope = requestPurchaseReEnvelope(
        apiBase = apiBase,
        ownerAddress = ownerAddress,
        purchaseId = purchaseId,
        buyerContentPublicKey = buyerContentPublicKey,
    )
    if (!reEnvelope.success) {
        return ReEnvelopeAccessRefreshResult(errorDetail = reEnvelope.errorDetail)
    }
    return ReEnvelopeAccessRefreshResult(
        accessInfo = requestPurchaseAccess(apiBase, ownerAddress, purchaseId),
    )
}

private suspend fun requestPurchaseKaraokeAccess(
    apiBase: String,
    ownerAddress: String,
    purchaseId: String,
): PurchaseKaraokeAccessInfo = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder()
            .url("$apiBase/api/music/purchase/${encodeQueryValue(purchaseId)}/karaoke-access")
            .header("X-User-Address", ownerAddress)
            .get()
            .build()

        HttpClients.Api.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(payload) }.getOrNull()
            if (!response.isSuccessful) {
                return@withContext PurchaseKaraokeAccessInfo(
                    manifestRef = "",
                    stems = emptyList(),
                    errorCode = json?.optString("error", "").orEmpty().ifBlank { null },
                )
            }

            val parsedJson = json
                ?: return@withContext PurchaseKaraokeAccessInfo(
                    manifestRef = "",
                    stems = emptyList(),
                    errorCode = "purchase_manifest_not_ready",
                )

            val manifestRef = parsedJson.optString("manifestRef", "").ifBlank { null }
                ?: return@withContext PurchaseKaraokeAccessInfo(
                    manifestRef = "",
                    stems = emptyList(),
                    errorCode = "purchase_manifest_not_ready",
                )

            val stemsArray = parsedJson.optJSONArray("stems")
            if (stemsArray == null || stemsArray.length() == 0) {
                return@withContext PurchaseKaraokeAccessInfo(
                    manifestRef = manifestRef,
                    stems = emptyList(),
                    errorCode = null,
                )
            }

            val stems = ArrayList<KaraokeStemInfo>(stemsArray.length())
            for (i in 0 until stemsArray.length()) {
                val item = stemsArray.optJSONObject(i) ?: continue
                val stemType = item.optString("stemType", "").trim().lowercase()
                if (stemType != "instrumental" && stemType != "vocals") continue
                val artifact = item.optJSONObject("artifact") ?: continue
                val artifactRef = artifact.optString("ref", "").trim()
                if (artifactRef.isEmpty()) continue
                val ivBytes = artifact.optInt("ivBytes", 12)
                stems.add(
                    KaraokeStemInfo(
                        stemType = stemType,
                        artifactRef = artifactRef,
                        artifactIvBytes = ivBytes,
                    ),
                )
            }
            return@withContext PurchaseKaraokeAccessInfo(
                manifestRef = manifestRef,
                stems = stems,
                errorCode = null,
            )
        }
    } catch (error: Exception) {
        Log.w(
            TAG_PLAYBACK,
            "requestPurchaseKaraokeAccess failed purchaseId=$purchaseId error=${error.message}",
        )
        PurchaseKaraokeAccessInfo(
            manifestRef = "",
            stems = emptyList(),
            errorCode = "network_error",
        )
    }
}

private suspend fun fetchJsonFromUrl(url: String): JSONObject? = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder().url(url).get().build()
        HttpClients.Api.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG_PLAYBACK, "fetchJsonFromUrl failed: http=${response.code} url=$url")
                return@withContext null
            }
            val body = response.body?.string().orEmpty()
            runCatching { JSONObject(body) }
                .onFailure { error ->
                    val preview = body.replace('\n', ' ').take(160)
                    Log.w(
                        TAG_PLAYBACK,
                        "fetchJsonFromUrl invalid JSON url=$url error=${error.message} bodyPreview=$preview",
                    )
                }
                .getOrNull()
        }
    } catch (error: Exception) {
        Log.w(TAG_PLAYBACK, "fetchJsonFromUrl exception url=$url error=${error.message}")
        null
    }
}

private suspend fun fetchBytesFromUrl(url: String): ByteArray? = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder().url(url).get().build()
        HttpClients.Api.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG_PLAYBACK, "fetchBytesFromUrl failed: http=${response.code} url=$url")
                return@withContext null
            }
            response.body?.bytes()
        }
    } catch (error: Exception) {
        Log.w(TAG_PLAYBACK, "fetchBytesFromUrl exception url=$url error=${error.message}")
        null
    }
}

internal suspend fun fetchJsonFromUrlWithRetry(
    url: String,
    deadlineMs: Long? = null,
    fetchJson: suspend (String) -> JSONObject? = ::fetchJsonFromUrl,
    delayMillis: suspend (Long) -> Unit = { waitMs -> delay(waitMs) },
    nowMs: () -> Long = { System.currentTimeMillis() },
    logInfo: (String) -> Unit = { message -> Log.i(TAG_PLAYBACK, message) },
    logWarn: (String) -> Unit = { message -> Log.w(TAG_PLAYBACK, message) },
): JSONObject? {
    repeat(MANIFEST_FETCH_ATTEMPTS) { attempt ->
        val attemptNumber = attempt + 1
        if (deadlineMs != null && nowMs() >= deadlineMs) {
            logWarn("fetchJsonFromUrl retry deadline reached before attempt url=$url")
            return null
        }

        val value = fetchJson(url)
        if (value != null) return value

        if (attemptNumber < MANIFEST_FETCH_ATTEMPTS) {
            val remainingMs = deadlineMs?.let { it - nowMs() }
            if (remainingMs != null && remainingMs <= 0L) {
                logWarn("fetchJsonFromUrl retry deadline reached url=$url")
                return null
            }
            logInfo("fetchJsonFromUrl retry $attemptNumber/$MANIFEST_FETCH_ATTEMPTS url=$url")
        }
        if (attempt < MANIFEST_FETCH_ATTEMPTS - 1) {
            val remainingMs = deadlineMs?.let { it - nowMs() } ?: MANIFEST_FETCH_DELAY_MS
            val waitMs = MANIFEST_FETCH_DELAY_MS.coerceAtMost(remainingMs)
            if (waitMs <= 0L) {
                logWarn("fetchJsonFromUrl retry deadline reached before delay url=$url")
                return null
            }
            delayMillis(waitMs)
        }
    }
    logWarn("fetchJsonFromUrl retry exhausted url=$url")
    return null
}

private fun normalizeAddress(value: String?): String =
    value.orEmpty().trim().lowercase()

private fun selectEnvelopeRef(manifestJson: JSONObject, buyerAddress: String): String? {
    val envelopes = manifestJson.optJSONArray("envelopes") ?: return null
    val normalizedBuyer = normalizeAddress(buyerAddress)

    // Primary: explicit grantee match.
    for (i in 0 until envelopes.length()) {
        val entry = envelopes.optJSONObject(i) ?: continue
        val grantee = normalizeAddress(entry.optString("grantee", ""))
        if (grantee == normalizedBuyer) {
            val ref = entry.optString("ref", "").trim()
            if (ref.isNotEmpty()) return ref
        }
    }

    // Secondary: buyer role envelope.
    for (i in 0 until envelopes.length()) {
        val entry = envelopes.optJSONObject(i) ?: continue
        val role = entry.optString("role", "").trim().lowercase()
        if (role == "buyer") {
            val ref = entry.optString("ref", "").trim()
            if (ref.isNotEmpty()) return ref
        }
    }

    // Last fallback: first envelope with a ref.
    for (i in 0 until envelopes.length()) {
        val entry = envelopes.optJSONObject(i) ?: continue
        val ref = entry.optString("ref", "").trim()
        if (ref.isNotEmpty()) return ref
    }
    return null
}

private fun mapPlaybackError(errorCode: String?): String = when (errorCode) {
    "purchase_not_found" -> "You don't own this track yet. Buy it to play."
    "purchase_not_accessible" -> "This purchase isn't accessible right now."
    "purchase_manifest_not_ready" -> "Your purchase is still being prepared. Try again shortly."
    "network_error" -> "Network error while loading this purchase. Try again."
    "song_asset_not_ready" -> "This track is still processing. Try again shortly."
    "song_asset_invalid" -> "This track format isn't supported."
    else -> "This track isn't playable right now."
}

private fun purchaseCacheFile(context: Context, purchaseId: String, assetType: String): File {
    return if (assetType == "main") {
        File(context.cacheDir, "purchase_dec_${purchaseId}.tmp")
    } else {
        File(context.cacheDir, "purchase_dec_${purchaseId}_${assetType}.tmp")
    }
}

/**
 * Resolve a purchased track for playback by:
 *   1. Fetching access info (manifestRef + artifactRef) from the API.
 *   2. Fetching the Arweave manifest → envelope ref.
 *   3. Fetching the Arweave envelope → ECIES data.
 *   4. Obtaining the PRF root seed (from cache or passkey assertion).
 *   5. Deriving the purchase keypair and unwrapping the DEK.
 *   6. Downloading and decrypting the encrypted artifact.
 *   7. Writing plaintext to a cache file and returning its file:// URI.
 *
 * Decrypted cache files live in [context.cacheDir] and are named by purchaseId.
 * They persist across calls so repeated playback skips re-download.
 */
internal suspend fun resolvePlayableTrackForUi(
    track: MusicTrack,
    context: Context,
    ownerEthAddress: String?,
    purchaseIdsByTrackId: Map<String, String>,
    activity: Activity? = null,
    tempoAccount: TempoPasskeyManager.PasskeyAccount? = null,
): PlaybackResolveResult = withContext(Dispatchers.IO) {
    if (track.uri.isNotBlank()) return@withContext PlaybackResolveResult(track = track, message = null)

    val ownerAddress = ownerEthAddress?.trim()?.lowercase().orEmpty()
    if (ownerAddress.isBlank()) {
        return@withContext PlaybackResolveResult(track = null, message = "Sign in to play this track.")
    }

    val songTrackId = resolveCanonicalTrackIdForPlayback(track)
        ?: return@withContext PlaybackResolveResult(
            track = null,
            message = "This track is metadata-only right now and cannot be played.",
        )

    val apiBase = resolveApiBase()
        ?: return@withContext PlaybackResolveResult(track = null, message = "Music API is not configured.")

    val purchaseId = track.purchaseId?.trim().orEmpty()
        .ifBlank { purchaseIdsByTrackId[songTrackId].orEmpty() }
    if (purchaseId.isBlank()) {
        return@withContext PlaybackResolveResult(track = null, message = "Buy this track to play it.")
    }

    // Return cached decrypted file if present.
    val tempFile = purchaseCacheFile(context, purchaseId, "main")
    if (tempFile.exists() && tempFile.length() > 0) {
        return@withContext PlaybackResolveResult(
            track = track.copy(
                uri = tempFile.toURI().toString(),
                filename = track.filename.ifBlank { "${track.title}.mp3" },
                isPreviewOnly = false,
            ),
            message = null,
        )
    }

    val unlockStartedAtMs = System.currentTimeMillis()
    val unlockDeadlineMs = unlockStartedAtMs + PURCHASE_UNLOCK_BUDGET_MS
    val unlockPendingMessage = mapPlaybackError("purchase_manifest_not_ready")

    // Fetch access info.
    var accessInfo = requestPurchaseAccess(apiBase, ownerAddress, purchaseId)
    if (accessInfo.errorCode == "purchase_manifest_not_ready") {
        val refreshed = reEnvelopeAndRefreshPurchaseAccess(context, apiBase, ownerAddress, purchaseId)
        if (!refreshed?.errorDetail.isNullOrBlank()) {
            return@withContext PlaybackResolveResult(
                track = null,
                message = refreshed?.errorDetail,
            )
        }
        if (refreshed?.accessInfo != null) {
            accessInfo = refreshed.accessInfo
        }
    }
    if (accessInfo.errorCode != null) {
        return@withContext PlaybackResolveResult(
            track = null,
            message = mapPlaybackError(accessInfo.errorCode),
        )
    }
    Log.i(
        TAG_PLAYBACK,
        "purchase access ready purchaseId=$purchaseId manifestRef=${accessInfo.manifestRef}",
    )

    // Fetch manifest from Arweave to find the buyer envelope ref.
    var manifestRef = accessInfo.manifestRef
    var manifestUrl = resolveArweaveRef(manifestRef)
        ?: return@withContext PlaybackResolveResult(track = null, message = "Invalid manifest reference.")
    var resolvedManifestJson = fetchJsonFromUrlWithRetry(
        url = manifestUrl,
        deadlineMs = unlockDeadlineMs,
    )
    if (resolvedManifestJson == null) {
        Log.w(
            TAG_PLAYBACK,
            "manifest fetch failed purchaseId=$purchaseId manifestRef=$manifestRef url=$manifestUrl; requesting re-envelope",
        )
        val refreshed = reEnvelopeAndRefreshPurchaseAccess(context, apiBase, ownerAddress, purchaseId)
        if (!refreshed?.errorDetail.isNullOrBlank()) {
            return@withContext PlaybackResolveResult(
                track = null,
                message = refreshed?.errorDetail,
            )
        }
        if (refreshed?.accessInfo != null) {
            accessInfo = refreshed.accessInfo
            if (accessInfo.errorCode != null) {
                return@withContext PlaybackResolveResult(
                    track = null,
                    message = mapPlaybackError(accessInfo.errorCode),
                )
            }
            manifestRef = accessInfo.manifestRef
            manifestUrl = resolveArweaveRef(manifestRef)
                ?: return@withContext PlaybackResolveResult(track = null, message = "Invalid manifest reference.")
            resolvedManifestJson = fetchJsonFromUrlWithRetry(
                url = manifestUrl,
                deadlineMs = unlockDeadlineMs,
            )
        }
    }
    if (resolvedManifestJson == null) {
        Log.w(
            TAG_PLAYBACK,
            "manifest fetch unavailable purchaseId=$purchaseId manifestRef=$manifestRef url=$manifestUrl",
        )
        val message = if (System.currentTimeMillis() >= unlockDeadlineMs) {
            unlockPendingMessage
        } else {
            "Failed to fetch purchase manifest."
        }
        return@withContext PlaybackResolveResult(track = null, message = message)
    }

    var envelopeRef = selectEnvelopeRef(resolvedManifestJson, ownerAddress)
    if (envelopeRef == null) {
        Log.w(
            TAG_PLAYBACK,
            "envelope ref missing purchaseId=$purchaseId manifestRef=$manifestRef; requesting re-envelope",
        )
        val refreshed = reEnvelopeAndRefreshPurchaseAccess(context, apiBase, ownerAddress, purchaseId)
        if (!refreshed?.errorDetail.isNullOrBlank()) {
            return@withContext PlaybackResolveResult(
                track = null,
                message = refreshed?.errorDetail,
            )
        }
        if (refreshed?.accessInfo != null) {
            accessInfo = refreshed.accessInfo
            if (accessInfo.errorCode != null) {
                return@withContext PlaybackResolveResult(
                    track = null,
                    message = mapPlaybackError(accessInfo.errorCode),
                )
            }
            manifestRef = accessInfo.manifestRef
            manifestUrl = resolveArweaveRef(manifestRef)
                ?: return@withContext PlaybackResolveResult(track = null, message = "Invalid manifest reference.")
            resolvedManifestJson = fetchJsonFromUrlWithRetry(
                url = manifestUrl,
                deadlineMs = unlockDeadlineMs,
            ) ?: return@withContext PlaybackResolveResult(
                track = null,
                message = if (System.currentTimeMillis() >= unlockDeadlineMs) {
                    unlockPendingMessage
                } else {
                    "Failed to fetch purchase manifest."
                },
            )
            envelopeRef = selectEnvelopeRef(resolvedManifestJson, ownerAddress)
        }
    }
    if (envelopeRef == null) {
        return@withContext PlaybackResolveResult(track = null, message = "No envelope found in manifest.")
    }

    // Fetch envelope from Arweave.
    var envelopeUrl = resolveArweaveRef(envelopeRef)
        ?: return@withContext PlaybackResolveResult(track = null, message = "Invalid envelope reference.")
    var envelopeJson = fetchJsonFromUrlWithRetry(
        url = envelopeUrl,
        deadlineMs = unlockDeadlineMs,
    )
    if (envelopeJson == null) {
        Log.w(
            TAG_PLAYBACK,
            "envelope fetch failed purchaseId=$purchaseId envelopeRef=$envelopeRef url=$envelopeUrl; requesting re-envelope",
        )
        val refreshed = reEnvelopeAndRefreshPurchaseAccess(context, apiBase, ownerAddress, purchaseId)
        if (!refreshed?.errorDetail.isNullOrBlank()) {
            return@withContext PlaybackResolveResult(
                track = null,
                message = refreshed?.errorDetail,
            )
        }
        if (refreshed?.accessInfo != null) {
            accessInfo = refreshed.accessInfo
            if (accessInfo.errorCode != null) {
                return@withContext PlaybackResolveResult(
                    track = null,
                    message = mapPlaybackError(accessInfo.errorCode),
                )
            }
            manifestRef = accessInfo.manifestRef
            manifestUrl = resolveArweaveRef(manifestRef)
                ?: return@withContext PlaybackResolveResult(track = null, message = "Invalid manifest reference.")
            resolvedManifestJson = fetchJsonFromUrlWithRetry(
                url = manifestUrl,
                deadlineMs = unlockDeadlineMs,
            ) ?: return@withContext PlaybackResolveResult(
                track = null,
                message = if (System.currentTimeMillis() >= unlockDeadlineMs) {
                    unlockPendingMessage
                } else {
                    "Failed to fetch purchase manifest."
                },
            )
            envelopeRef = selectEnvelopeRef(resolvedManifestJson, ownerAddress)
                ?: return@withContext PlaybackResolveResult(track = null, message = "No envelope found in manifest.")
            envelopeUrl = resolveArweaveRef(envelopeRef)
                ?: return@withContext PlaybackResolveResult(track = null, message = "Invalid envelope reference.")
            envelopeJson = fetchJsonFromUrlWithRetry(
                url = envelopeUrl,
                deadlineMs = unlockDeadlineMs,
            )
        }
    }
    if (envelopeJson == null) {
        val message = if (System.currentTimeMillis() >= unlockDeadlineMs) {
            unlockPendingMessage
        } else {
            "Failed to fetch key envelope."
        }
        return@withContext PlaybackResolveResult(
            track = null,
            message = message,
        )
    }

    val ephemeralPubHex = envelopeJson.optString("ephemeralPub", "").ifBlank { null }
        ?: return@withContext PlaybackResolveResult(track = null, message = "Invalid envelope: missing ephemeralPub.")
    val ivHex = envelopeJson.optString("iv", "").ifBlank { null }
        ?: return@withContext PlaybackResolveResult(track = null, message = "Invalid envelope: missing iv.")
    val ciphertextHex = envelopeJson.optString("ciphertext", "").ifBlank { null }
        ?: return@withContext PlaybackResolveResult(track = null, message = "Invalid envelope: missing ciphertext.")

    val envelope = runCatching {
        PurchaseContentCrypto.PurchaseEnvelope(
            ephemeralPub = P256Utils.hexToBytes(ephemeralPubHex),
            iv = P256Utils.hexToBytes(ivHex),
            ciphertext = P256Utils.hexToBytes(ciphertextHex),
        )
    }.getOrNull() ?: return@withContext PlaybackResolveResult(track = null, message = "Invalid envelope data.")

    // First try device-local content key path (no credential-manager prompt).
    val localContentKey = runCatching { ContentKeyManager.getOrCreate(context) }.getOrNull()
    var dekBytes = localContentKey?.let { keyPair ->
        runCatching {
            PurchaseContentCrypto.unwrapDekFromEnvelope(keyPair.privateKey, envelope)
        }.getOrNull()
    }

    // Fallback to PRF-derived purchase key for legacy purchases.
    if (dekBytes == null) {
        val credentialId = tempoAccount?.credentialId
        var rootSeed = if (credentialId != null) PrfRootSeedStore.load(context, credentialId) else null
        if (rootSeed == null) {
            val act = activity
            val acct = tempoAccount
            if (act != null && acct != null) {
                rootSeed =
                    runCatching {
                        withContext(Dispatchers.Main) {
                            TempoPasskeyManager.getPrfRootSeed(act, acct, rpId = acct.rpId)
                        }
                    }.onSuccess { derived ->
                        PrfRootSeedStore.store(context, acct.credentialId, derived)
                    }.getOrNull()
            }
        }

        if (rootSeed != null) {
            val keypair =
                runCatching {
                    PurchaseContentCrypto.derivePurchaseKeyPair(rootSeed, purchaseId)
                }.getOrNull()
            if (keypair != null) {
                dekBytes =
                    runCatching {
                        PurchaseContentCrypto.unwrapDekFromEnvelope(keypair.privateScalarBytes, envelope)
                    }.getOrNull()
            }
        }
    }
    val unwrappedDek =
        dekBytes ?: return@withContext PlaybackResolveResult(
            track = null,
            message = "Failed to unlock content key for this purchase.",
        )

    // Resolve artifact ref (from access endpoint, falling back to manifest).
    val artifactRef = accessInfo.artifactRef.ifBlank {
        resolvedManifestJson.optJSONObject("artifact")?.optString("ref", "").orEmpty().ifBlank { null }
            ?: return@withContext PlaybackResolveResult(track = null, message = "Artifact reference missing.")
    }

    // Download encrypted artifact.
    val artifactUrl = resolveArweaveRef(artifactRef)
        ?: return@withContext PlaybackResolveResult(track = null, message = "Invalid artifact reference.")
    val encryptedBytes = fetchBytesFromUrl(artifactUrl)
        ?: return@withContext PlaybackResolveResult(track = null, message = "Failed to download track.")

    // Decrypt.
    val decryptedBytes = runCatching {
        PurchaseContentCrypto.decryptIvPrependedArtifact(unwrappedDek, encryptedBytes, accessInfo.artifactIvBytes)
    }.getOrNull() ?: return@withContext PlaybackResolveResult(track = null, message = "Failed to decrypt track.")

    val taggedBytes = runCatching {
        injectPurchaseProvenanceId3(
            audioBytes = decryptedBytes,
            buyerWallet = ownerAddress,
            purchaseId = purchaseId,
        )
    }.getOrElse { decryptedBytes }

    // Write to cache file.
    runCatching { tempFile.writeBytes(taggedBytes) }.getOrNull()
        ?: return@withContext PlaybackResolveResult(track = null, message = "Failed to write decrypted track.")

    Log.i(
        TAG_PLAYBACK,
        "purchase unlock ready purchaseId=$purchaseId elapsedMs=${System.currentTimeMillis() - unlockStartedAtMs}",
    )

    PlaybackResolveResult(
        track = track.copy(
            uri = tempFile.toURI().toString(),
            filename = track.filename.ifBlank { "${track.title}.mp3" },
            isPreviewOnly = false,
        ),
        message = null,
    )
}

internal suspend fun downloadPurchasedTrackToDevice(
    context: Context,
    track: MusicTrack,
    ownerEthAddress: String?,
    purchaseIdsByTrackId: Map<String, String>,
    activity: Activity? = null,
    tempoAccount: TempoPasskeyManager.PasskeyAccount? = null,
): PurchasedTrackDownloadResult = withContext(Dispatchers.IO) {
    val downloadKey = downloadLookupIdForTrack(track)
        ?: return@withContext PurchasedTrackDownloadResult(success = false, error = "Track is not purchased.")

    val existing = DownloadedTracksStore.load(context)[downloadKey]
    if (existing != null && MediaStoreAudioDownloads.uriExists(context, existing.mediaUri)) {
        return@withContext PurchasedTrackDownloadResult(
            success = true,
            alreadyDownloaded = true,
            mediaUri = existing.mediaUri,
        )
    }

    val resolved = resolvePlayableTrackForUi(
        track = track.copy(uri = "", isPreviewOnly = false),
        context = context,
        ownerEthAddress = ownerEthAddress,
        purchaseIdsByTrackId = purchaseIdsByTrackId,
        activity = activity,
        tempoAccount = tempoAccount,
    )
    val playable = resolved.track
        ?: return@withContext PurchasedTrackDownloadResult(
            success = false,
            error = resolved.message ?: "Track is not playable.",
        )

    val sourceUri = runCatching { Uri.parse(playable.uri) }.getOrNull()
        ?: return@withContext PurchasedTrackDownloadResult(success = false, error = "Track file is invalid.")
    if (!sourceUri.scheme.equals("file", ignoreCase = true)) {
        return@withContext PurchasedTrackDownloadResult(success = false, error = "Track file is unavailable.")
    }
    val sourcePath = sourceUri.path.orEmpty()
    if (sourcePath.isBlank()) {
        return@withContext PurchasedTrackDownloadResult(success = false, error = "Track file is unavailable.")
    }
    val sourceFile = File(sourcePath)
    if (!sourceFile.exists() || sourceFile.length() <= 0L) {
        return@withContext PurchasedTrackDownloadResult(success = false, error = "Track file is unavailable.")
    }

    val ext = sourceFile.extension.trim().lowercase().ifBlank { "mp3" }
    val preferredName = listOf(playable.artist.trim(), playable.title.trim())
        .filter { it.isNotBlank() }
        .joinToString(" - ")
        .ifBlank { downloadKey.replace(':', '_') }

    val mediaUri = runCatching {
        MediaStoreAudioDownloads.saveAudio(
            context = context,
            sourceFile = sourceFile,
            title = playable.title,
            artist = playable.artist,
            album = playable.album,
            mimeType = audioMimeFromExtension(ext),
            preferredName = preferredName,
        )
    }.getOrElse { error ->
        return@withContext PurchasedTrackDownloadResult(success = false, error = error.message ?: "Download failed.")
    }

    DownloadedTracksStore.upsert(
        context,
        DownloadedTrackEntry(
            contentId = downloadKey,
            mediaUri = mediaUri,
            title = playable.title,
            artist = playable.artist,
            album = playable.album,
            filename = sourceFile.name,
            mimeType = audioMimeFromExtension(ext),
            pieceCid = null,
            datasetOwner = null,
            algo = null,
            coverCid = null,
            downloadedAtMs = System.currentTimeMillis(),
        ),
    )

    PurchasedTrackDownloadResult(success = true, mediaUri = mediaUri)
}

internal suspend fun resolveKaraokeStemsForUi(
    context: Context,
    ownerEthAddress: String?,
    purchaseId: String,
    activity: Activity? = null,
    tempoAccount: TempoPasskeyManager.PasskeyAccount? = null,
): KaraokeResolveResult = withContext(Dispatchers.IO) {
    val ownerAddress = ownerEthAddress?.trim()?.lowercase().orEmpty()
    if (ownerAddress.isBlank()) {
        return@withContext KaraokeResolveResult(stemsByType = emptyMap(), message = "Sign in to unlock karaoke stems.")
    }

    val normalizedPurchaseId = purchaseId.trim()
    if (normalizedPurchaseId.isBlank()) {
        return@withContext KaraokeResolveResult(stemsByType = emptyMap(), message = "Purchase ID is required for karaoke.")
    }

    val apiBase = resolveApiBase()
        ?: return@withContext KaraokeResolveResult(stemsByType = emptyMap(), message = "Music API is not configured.")

    val karaokeAccess = requestPurchaseKaraokeAccess(apiBase, ownerAddress, normalizedPurchaseId)
    if (karaokeAccess.errorCode != null) {
        return@withContext KaraokeResolveResult(
            stemsByType = emptyMap(),
            message = mapPlaybackError(karaokeAccess.errorCode),
        )
    }
    if (karaokeAccess.stems.isEmpty()) {
        return@withContext KaraokeResolveResult(
            stemsByType = emptyMap(),
            message = "No karaoke stems are available for this purchase.",
        )
    }

    val resolvedCache = LinkedHashMap<String, String>(karaokeAccess.stems.size)
    val pendingStems = ArrayList<KaraokeStemInfo>(karaokeAccess.stems.size)
    for (stem in karaokeAccess.stems) {
        val cacheFile = purchaseCacheFile(context, normalizedPurchaseId, stem.stemType)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            resolvedCache[stem.stemType] = cacheFile.toURI().toString()
        } else {
            pendingStems.add(stem)
        }
    }
    if (pendingStems.isEmpty()) {
        return@withContext KaraokeResolveResult(stemsByType = resolvedCache, message = null)
    }

    val manifestUrl = resolveArweaveRef(karaokeAccess.manifestRef)
        ?: return@withContext KaraokeResolveResult(stemsByType = emptyMap(), message = "Invalid manifest reference.")
    val manifestJson = fetchJsonFromUrlWithRetry(manifestUrl)
        ?: return@withContext KaraokeResolveResult(stemsByType = emptyMap(), message = "Failed to fetch purchase manifest.")

    val envelopeRef = selectEnvelopeRef(manifestJson, ownerAddress)
        ?: return@withContext KaraokeResolveResult(stemsByType = emptyMap(), message = "No envelope found in manifest.")
    val envelopeUrl = resolveArweaveRef(envelopeRef)
        ?: return@withContext KaraokeResolveResult(stemsByType = emptyMap(), message = "Invalid envelope reference.")
    val envelopeJson = fetchJsonFromUrlWithRetry(envelopeUrl)
        ?: return@withContext KaraokeResolveResult(stemsByType = emptyMap(), message = "Failed to fetch key envelope.")

    val ephemeralPubHex = envelopeJson.optString("ephemeralPub", "").ifBlank { null }
        ?: return@withContext KaraokeResolveResult(stemsByType = emptyMap(), message = "Invalid envelope: missing ephemeralPub.")
    val ivHex = envelopeJson.optString("iv", "").ifBlank { null }
        ?: return@withContext KaraokeResolveResult(stemsByType = emptyMap(), message = "Invalid envelope: missing iv.")
    val ciphertextHex = envelopeJson.optString("ciphertext", "").ifBlank { null }
        ?: return@withContext KaraokeResolveResult(stemsByType = emptyMap(), message = "Invalid envelope: missing ciphertext.")

    val envelope = runCatching {
        PurchaseContentCrypto.PurchaseEnvelope(
            ephemeralPub = P256Utils.hexToBytes(ephemeralPubHex),
            iv = P256Utils.hexToBytes(ivHex),
            ciphertext = P256Utils.hexToBytes(ciphertextHex),
        )
    }.getOrNull() ?: return@withContext KaraokeResolveResult(stemsByType = emptyMap(), message = "Invalid envelope data.")

    val localContentKey = runCatching { ContentKeyManager.getOrCreate(context) }.getOrNull()
    var dekBytes = localContentKey?.let { keyPair ->
        runCatching {
            PurchaseContentCrypto.unwrapDekFromEnvelope(keyPair.privateKey, envelope)
        }.getOrNull()
    }

    if (dekBytes == null) {
        val credentialId = tempoAccount?.credentialId
        var rootSeed = if (credentialId != null) PrfRootSeedStore.load(context, credentialId) else null
        if (rootSeed == null) {
            val act = activity
            val acct = tempoAccount
            if (act != null && acct != null) {
                rootSeed =
                    runCatching {
                        withContext(Dispatchers.Main) {
                            TempoPasskeyManager.getPrfRootSeed(act, acct, rpId = acct.rpId)
                        }
                    }.onSuccess { derived ->
                        PrfRootSeedStore.store(context, acct.credentialId, derived)
                    }.getOrNull()
            }
        }
        if (rootSeed != null) {
            val keypair =
                runCatching {
                    PurchaseContentCrypto.derivePurchaseKeyPair(rootSeed, normalizedPurchaseId)
                }.getOrNull()
            if (keypair != null) {
                dekBytes =
                    runCatching {
                        PurchaseContentCrypto.unwrapDekFromEnvelope(keypair.privateScalarBytes, envelope)
                    }.getOrNull()
            }
        }
    }
    val unwrappedDek = dekBytes
        ?: return@withContext KaraokeResolveResult(
            stemsByType = emptyMap(),
            message = "Failed to unlock content key for this purchase.",
        )

    for (stem in pendingStems) {
        val artifactUrl = resolveArweaveRef(stem.artifactRef)
            ?: return@withContext KaraokeResolveResult(stemsByType = emptyMap(), message = "Invalid ${stem.stemType} artifact reference.")
        val encryptedBytes = fetchBytesFromUrl(artifactUrl)
            ?: return@withContext KaraokeResolveResult(stemsByType = emptyMap(), message = "Failed to download ${stem.stemType} stem.")
        val decryptedBytes = runCatching {
            PurchaseContentCrypto.decryptIvPrependedArtifact(unwrappedDek, encryptedBytes, stem.artifactIvBytes)
        }.getOrNull() ?: return@withContext KaraokeResolveResult(stemsByType = emptyMap(), message = "Failed to decrypt ${stem.stemType} stem.")

        val cacheFile = purchaseCacheFile(context, normalizedPurchaseId, stem.stemType)
        runCatching { cacheFile.writeBytes(decryptedBytes) }.getOrNull()
            ?: return@withContext KaraokeResolveResult(stemsByType = emptyMap(), message = "Failed to cache ${stem.stemType} stem.")
        resolvedCache[stem.stemType] = cacheFile.toURI().toString()
    }

    KaraokeResolveResult(stemsByType = resolvedCache, message = null)
}
