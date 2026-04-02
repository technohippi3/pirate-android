package sc.pirate.app.music

import android.content.Context
import android.util.Log
import sc.pirate.app.BuildConfig
import sc.pirate.app.PirateChainConfig
import sc.pirate.app.auth.privy.PrivyRelayClient
import sc.pirate.app.profile.PirateNameRecordsApi
import sc.pirate.app.crypto.ContentKeyManager
import sc.pirate.app.crypto.EciesContentCrypto
import sc.pirate.app.crypto.P256Utils
import java.math.BigInteger
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint32
import org.web3j.abi.datatypes.generated.Uint8

internal val uploadedTrackAddressRegex = Regex("^0x[a-fA-F0-9]{40}$")
private val uploadedTrackBytes32Regex = Regex("^0x[0-9a-f]{64}$")
private val uploadedTrackDataItemIdRegex = Regex("^[A-Za-z0-9_-]{32,}$")
private val uploadedTrackCidRegex = Regex("^(Qm[1-9A-HJ-NP-Za-km-z]{44,}|b[a-z2-7]{20,})$")
private const val uploadedTrackVisPrivate = 2
private const val uploadedTrackZeroBytes32 = "0x0000000000000000000000000000000000000000000000000000000000000000"
internal val uploadedTrackJsonMediaType = "application/json".toMediaType()
internal val uploadedTrackHttp: OkHttpClient =
  OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()

internal suspend fun resolveUploadedTrackRecipientAddress(rawRecipient: String): String? {
  val clean = rawRecipient.trim().removePrefix("@")
  if (clean.isBlank()) return null
  if (uploadedTrackAddressRegex.matches(clean)) return clean.lowercase()
  val resolved = PirateNameRecordsApi.resolveAddressForName(clean.lowercase())
  return resolved?.lowercase()
}

internal suspend fun ensureUploadedTrackGrantOnChainInternal(
  context: Context,
  track: MusicTrack,
  ownerAddress: String,
  granteeAddress: String,
): String = withContext(Dispatchers.IO) {
  val owner = normalizeChainAddress(ownerAddress)
  val grantee = normalizeChainAddress(granteeAddress)
  val normalizedContentId = normalizeUploadedContentId(track.contentId.orEmpty())
  if (normalizedContentId.isBlank()) {
    throw IllegalStateException("Track has no contentId.")
  }

  Log.d(
    SHARED_WITH_YOU_TAG,
    "share grant request contentId=$normalizedContentId owner=$owner grantee=$grantee",
  )

  val coordinatorAddress = configuredPublishCoordinatorAddress()
  Log.d(
    SHARED_WITH_YOU_TAG,
    "share grant start contentId=$normalizedContentId owner=$owner grantee=$grantee",
  )

  var publishId =
    resolvePublishIdForContent(
      coordinatorAddress = coordinatorAddress,
      ownerAddress = owner,
      contentId = normalizedContentId,
      track = track,
    )

  if (publishId == null) {
    Log.d(SHARED_WITH_YOU_TAG, "share grant publishId missing; attempting private publish contentId=$normalizedContentId")
    val trackIdForPublish = normalizedMetaTrackIdForShare(track)
      ?: throw IllegalStateException(
        "Track is not registered on-chain for sharing. Re-save with stable title/artist metadata, then try again.",
      )
    val expectedContentId =
      runCatching { ContentIds.computeContentId(trackIdForPublish, owner) }.getOrNull()?.lowercase()
    if (expectedContentId == null || expectedContentId != normalizedContentId) {
      throw IllegalStateException("Track contentId does not match derived track metadata; cannot register share grant.")
    }

    publishSavedForeverTrackForShare(
      context = context,
      coordinatorAddress = coordinatorAddress,
      ownerAddress = owner,
      track = track,
    )
    publishId =
      resolvePublishIdForTrack(
        coordinatorAddress = coordinatorAddress,
        ownerAddress = owner,
        trackId = trackIdForPublish,
      )
  }

  val resolvedPublishId = publishId
    ?: throw IllegalStateException("Unable to resolve publishId for this track; sharing is not available yet.")

  val grantCallData = encodeCoordinatorGrantAccessCallData(publishId = resolvedPublishId, granteeAddress = grantee)
  val txHash =
    submitCoordinatorContractCall(
      context = context,
      coordinatorAddress = coordinatorAddress,
      callData = grantCallData,
      opLabel = "grant uploaded track access",
      intentType = "pirate.track.share.grant-access",
      intentArgs =
        JSONObject()
          .put("publishId", resolvedPublishId)
          .put("granteeAddress", grantee),
    )
  Log.d(
    SHARED_WITH_YOU_TAG,
    "share grant success contentId=$normalizedContentId publishId=$resolvedPublishId tx=$txHash",
  )
  txHash
}

private suspend fun resolvePublishIdForContent(
  coordinatorAddress: String,
  ownerAddress: String,
  contentId: String,
  track: MusicTrack,
): String? {
  val candidates = LinkedHashSet<String>(4)

  fun addCandidate(raw: String?) {
    val normalized =
      runCatching { normalizeBytes32(raw.orEmpty(), "trackId") }
        .getOrNull()
        ?.lowercase()
        ?: return
    val derivedContentId =
      runCatching { ContentIds.computeContentId(normalized, ownerAddress) }
        .getOrNull()
        ?.lowercase()
        ?: return
    if (derivedContentId != contentId.lowercase()) return
    candidates.add(normalized)
  }

  addCandidate(track.canonicalTrackId)
  addCandidate(resolveSongTrackId(track))
  addCandidate(queryContentEntryTrackId(contentId = contentId, expectedOwner = ownerAddress))
  addCandidate(normalizedMetaTrackIdForShare(track))

  for (trackId in candidates) {
    val publishId =
      resolvePublishIdForTrack(
        coordinatorAddress = coordinatorAddress,
        ownerAddress = ownerAddress,
        trackId = trackId,
      )
    if (!publishId.isNullOrBlank()) return publishId
  }
  return null
}

private suspend fun resolvePublishIdForTrack(
  coordinatorAddress: String,
  ownerAddress: String,
  trackId: String,
): String? {
  val normalizedTrackId = runCatching { normalizeBytes32(trackId, "trackId") }.getOrNull() ?: return null
  val active = queryActivePublishId(coordinatorAddress, ownerAddress, normalizedTrackId)
  if (!active.isNullOrBlank()) return active

  val latestVersion = queryLatestVersion(coordinatorAddress, ownerAddress, normalizedTrackId)
  if (latestVersion <= 0) return null
  return queryPublishIdByVersion(coordinatorAddress, ownerAddress, normalizedTrackId, latestVersion)
}

private fun queryContentEntryTrackId(
  contentId: String,
  expectedOwner: String,
): String? {
  val query =
    """
      {
        contentEntries(where: { id: "$contentId" }, first: 1) {
          id
          trackId
          owner
        }
      }
    """.trimIndent()
  val json = runCatching { postQuery(musicSocialSubgraphUrl(), query) }.getOrNull() ?: return null
  val row = json.optJSONObject("data")?.optJSONArray("contentEntries")?.optJSONObject(0) ?: return null
  val owner = row.optString("owner", "").trim().lowercase()
  if (owner != expectedOwner.lowercase()) return null
  val trackId = row.optString("trackId", "").trim().lowercase()
  return if (uploadedTrackBytes32Regex.matches(trackId)) trackId else null
}

private fun queryActivePublishId(
  coordinatorAddress: String,
  ownerAddress: String,
  trackId: String,
): String? {
  val function =
    Function(
      "activePublishId",
      listOf(Address(ownerAddress), Bytes32(P256Utils.hexToBytes(trackId))),
      listOf(object : org.web3j.abi.TypeReference<Bytes32>() {}),
    )
  return callCoordinatorBytes32(coordinatorAddress, function)
}

private fun queryLatestVersion(
  coordinatorAddress: String,
  ownerAddress: String,
  trackId: String,
): Int {
  val function =
    Function(
      "latestVersion",
      listOf(Address(ownerAddress), Bytes32(P256Utils.hexToBytes(trackId))),
      listOf(object : org.web3j.abi.TypeReference<Uint32>() {}),
    )
  val data = FunctionEncoder.encode(function)
  val raw = runCatching { ethCall(coordinatorAddress, data) }.getOrNull().orEmpty().removePrefix("0x").trim()
  if (raw.isBlank()) return 0
  return runCatching { BigInteger(raw, 16).toInt() }.getOrDefault(0)
}

private fun queryPublishIdByVersion(
  coordinatorAddress: String,
  ownerAddress: String,
  trackId: String,
  version: Int,
): String? {
  if (version <= 0) return null
  val function =
    Function(
      "publishIdByVersion",
      listOf(
        Address(ownerAddress),
        Bytes32(P256Utils.hexToBytes(trackId)),
        Uint32(BigInteger.valueOf(version.toLong())),
      ),
      listOf(object : org.web3j.abi.TypeReference<Bytes32>() {}),
    )
  return callCoordinatorBytes32(coordinatorAddress, function)
}

private fun callCoordinatorBytes32(
  coordinatorAddress: String,
  function: Function,
): String? {
  val data = FunctionEncoder.encode(function)
  val raw = runCatching { ethCall(coordinatorAddress, data) }.getOrNull().orEmpty().trim()
  if (!raw.startsWith("0x") || raw.length < 66) return null
  val value = "0x${raw.removePrefix("0x").takeLast(64).lowercase()}"
  return if (value == uploadedTrackZeroBytes32) null else value
}

private fun resolveSharePublishCoverRef(track: MusicTrack): String {
  val candidates = listOf(track.artworkFallbackUri, track.artworkUri)
  for (candidate in candidates) {
    val normalized = normalizeSharedNullableString(candidate) ?: continue
    if (normalized.startsWith("content://")) continue
    if (normalized.startsWith("file://")) continue
    if (normalized.startsWith("http://") || normalized.startsWith("https://")) continue
    return normalized
  }
  return ""
}

private suspend fun publishSavedForeverTrackForShare(
  context: Context,
  coordinatorAddress: String,
  ownerAddress: String,
  track: MusicTrack,
) {
  val title = track.title.trim()
  val artist = track.artist.trim()
  val album = track.album.trim()
  val pieceCid = track.pieceCid?.trim().orEmpty()
  if (title.isBlank() || artist.isBlank()) {
    throw IllegalStateException("Track title and artist are required before sharing.")
  }
  if (pieceCid.isBlank()) {
    throw IllegalStateException("Track has no pieceCid; save it forever before sharing.")
  }

  val datasetOwner = runCatching { normalizeChainAddress(track.datasetOwner ?: ownerAddress) }.getOrDefault(ownerAddress)
  val algo = (track.algo ?: ContentCryptoConfig.ALGO_AES_GCM_256).coerceIn(1, 255)
  if (isUnsupportedUploadedTrackAlgo(algo)) {
    throw IllegalStateException(uploadedTrackUnsupportedUploadError())
  }

  val trackParts = songPublishComputeMetaParts(title = title, artist = artist, album = album)
  val coverRef = resolveSharePublishCoverRef(track)
  val callData =
    encodeCoordinatorPublishCallData(
      ownerAddress = ownerAddress,
      payload = trackParts.payload,
      title = title,
      artist = artist,
      album = album,
      durationSec = track.durationSec.coerceAtLeast(0),
      coverRef = coverRef,
      datasetOwner = datasetOwner,
      pieceCid = pieceCid,
      algo = algo,
      visibility = uploadedTrackVisPrivate,
      replaceIfActive = false,
    )
  submitCoordinatorContractCall(
    context = context,
    coordinatorAddress = coordinatorAddress,
    callData = callData,
    opLabel = "register uploaded track release",
    intentType = "pirate.track.share.publish-private",
    intentArgs =
      JSONObject()
        .put("ownerAddress", ownerAddress)
        .put("title", title)
        .put("artist", artist)
        .put("trackId", normalizedMetaTrackIdForShare(track)),
  )
}

private fun encodeCoordinatorGrantAccessCallData(
  publishId: String,
  granteeAddress: String,
): String {
  val function =
    Function(
      "grantAccess",
      listOf(
        Bytes32(P256Utils.hexToBytes(normalizeBytes32(publishId, "publishId"))),
        Address(normalizeChainAddress(granteeAddress)),
      ),
      emptyList(),
    )
  return FunctionEncoder.encode(function)
}

private fun encodeCoordinatorPublishCallData(
  ownerAddress: String,
  payload: ByteArray,
  title: String,
  artist: String,
  album: String,
  durationSec: Int,
  coverRef: String,
  datasetOwner: String,
  pieceCid: String,
  algo: Int,
  visibility: Int,
  replaceIfActive: Boolean,
): String {
  require(payload.size == 32) { "payload must be bytes32" }
  val normalizedTitle = title.trim()
  val normalizedArtist = artist.trim()
  val normalizedAlbum = album.trim()
  val normalizedCoverRef = coverRef.trim()
  val normalizedPieceCid = pieceCid.trim()
  val publishStruct =
    DynamicStruct(
      Address(ownerAddress),
      Uint8(BigInteger.valueOf(3L)),
      Bytes32(payload),
      Utf8String(normalizedTitle),
      Utf8String(normalizedArtist),
      Utf8String(normalizedAlbum),
      Uint32(BigInteger.valueOf(durationSec.coerceAtLeast(0).toLong())),
      Utf8String(normalizedCoverRef),
      Address(datasetOwner),
      DynamicBytes(normalizedPieceCid.toByteArray(Charsets.UTF_8)),
      Uint8(BigInteger.valueOf(algo.toLong())),
      Uint8(BigInteger.valueOf(visibility.toLong())),
      Bool(replaceIfActive),
    )
  val function = Function("publish", listOf(publishStruct), emptyList())
  return FunctionEncoder.encode(function)
}

private suspend fun submitCoordinatorContractCall(
  context: Context,
  coordinatorAddress: String,
  callData: String,
  opLabel: String,
  intentType: String,
  intentArgs: JSONObject,
): String {
  val txHash =
    PrivyRelayClient.submitContractCall(
      context = context,
      chainId = PirateChainConfig.STORY_AENEID_CHAIN_ID,
      to = coordinatorAddress,
      data = callData,
      intentType = intentType,
      intentArgs = intentArgs,
    )
  val receipt = awaitPlaylistReceipt(txHash)
  if (!receipt.isSuccess) {
    throw IllegalStateException("$opLabel reverted on-chain: ${receipt.txHash}")
  }
  return txHash
}

private fun configuredPublishCoordinatorAddress(): String {
  val raw = BuildConfig.STORY_PUBLISH_COORDINATOR.trim()
  if (raw.isBlank() || raw.equals("0x0000000000000000000000000000000000000000", ignoreCase = true)) {
    throw IllegalStateException("Story publish coordinator is not configured in this build.")
  }
  return normalizeChainAddress(raw)
}

private fun normalizedMetaTrackIdForShare(track: MusicTrack): String? {
  return runCatching {
    val parts = songPublishComputeMetaParts(
      title = track.title,
      artist = track.artist,
      album = track.album,
    )
    "0x${P256Utils.bytesToHex(parts.trackId)}"
  }.getOrNull()?.let { runCatching { normalizeBytes32(it, "trackId") }.getOrNull() }
}

internal suspend fun ensureWrappedKeyFromArweaveInternal(
  context: Context,
  contentId: String,
  ownerAddress: String,
  granteeAddress: String,
): Boolean = withContext(Dispatchers.IO) {
  val normalizedContentId = normalizeUploadedContentId(contentId)
  val normalizedOwner = ownerAddress.trim().lowercase()
  val normalizedGrantee = granteeAddress.trim().lowercase()
  if (normalizedContentId.isEmpty() || normalizedOwner.isEmpty() || normalizedGrantee.isEmpty()) {
    return@withContext false
  }
  if (ContentKeyManager.loadWrappedKey(context, normalizedContentId) != null) {
    return@withContext true
  }

  val envelopeIds =
    runCatching {
      queryEnvelopeIds(
        contentId = normalizedContentId,
        ownerAddress = normalizedOwner,
        granteeAddress = normalizedGrantee,
      )
    }.getOrElse { emptyList() }

  if (envelopeIds.isEmpty()) return@withContext false

  for (envelopeId in envelopeIds) {
    val payload = runCatching { fetchResolveBytes(envelopeId) }.getOrNull() ?: continue
    val envelope =
      parseEnvelopePayload(
        payload = payload,
        expectedContentId = normalizedContentId,
        expectedOwner = normalizedOwner,
        expectedGrantee = normalizedGrantee,
      ) ?: continue
    ContentKeyManager.saveWrappedKey(context, normalizedContentId, envelope)
    return@withContext true
  }
  false
}

private fun queryEnvelopeIds(
  contentId: String,
  ownerAddress: String,
  granteeAddress: String,
): List<String> {
  val arweaveRefs =
    runCatching {
      queryArweaveEnvelopeIds(
        contentId = contentId,
        ownerAddress = ownerAddress,
        granteeAddress = granteeAddress,
      )
    }.getOrElse { emptyList() }
  if (arweaveRefs.isNotEmpty()) return arweaveRefs

  return runCatching {
    queryApiCoreEnvelopeRefs(
      contentId = contentId,
      ownerAddress = ownerAddress,
      granteeAddress = granteeAddress,
    )
  }.getOrElse { emptyList() }
}

private fun queryArweaveEnvelopeIds(
  contentId: String,
  ownerAddress: String,
  granteeAddress: String,
): List<String> {
  val tags =
    JSONArray()
      .put(JSONObject().put("name", "App-Name").put("values", JSONArray().put("Pirate")))
      .put(JSONObject().put("name", "Data-Type").put("values", JSONArray().put("content-key-envelope")))
      .put(JSONObject().put("name", "Content-Id").put("values", JSONArray().put(contentId)))
      .put(JSONObject().put("name", "Owner").put("values", JSONArray().put(ownerAddress)))
      .put(JSONObject().put("name", "Grantee").put("values", JSONArray().put(granteeAddress)))

  val query =
    """
      query EnvelopeIds(${'$'}tags: [TagFilter!], ${'$'}first: Int!) {
        transactions(first: ${'$'}first, sort: HEIGHT_DESC, tags: ${'$'}tags) {
          edges {
            node {
              id
            }
          }
        }
      }
    """.trimIndent()

  val body =
    JSONObject()
      .put("query", query)
      .put(
        "variables",
        JSONObject()
          .put("first", 8)
          .put("tags", tags),
      )
      .toString()
      .toRequestBody(uploadedTrackJsonMediaType)

  val request =
    Request.Builder()
      .url("${BuildConfig.ARWEAVE_GATEWAY_URL.trim().trimEnd('/')}/graphql")
      .post(body)
      .build()
  val response = uploadedTrackHttp.newCall(request).execute()
  if (!response.isSuccessful) return emptyList()
  val raw = response.body?.string().orEmpty()
  if (raw.isBlank()) return emptyList()
  val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
  val items = json
    .optJSONObject("data")
    ?.optJSONObject("transactions")
    ?.optJSONArray("edges")
    ?: return emptyList()
  if (items.length() == 0) return emptyList()

  val out = ArrayList<String>(items.length())
  for (i in 0 until items.length()) {
    val id = items.optJSONObject(i)?.optJSONObject("node")?.optString("id", "")?.trim().orEmpty()
    if (id.isNotBlank()) out.add(id)
  }
  return out
}

private fun queryApiCoreEnvelopeRefs(
  contentId: String,
  ownerAddress: String,
  granteeAddress: String,
): List<String> {
  val apiBase = SongPublishService.API_CORE_URL.trim().trimEnd('/')
  if (apiBase.isBlank()) return emptyList()
  val contentParam = URLEncoder.encode(contentId, Charsets.UTF_8.name())
  val ownerParam = URLEncoder.encode(ownerAddress, Charsets.UTF_8.name())
  val granteeParam = URLEncoder.encode(granteeAddress, Charsets.UTF_8.name())
  val request =
    Request.Builder()
      .url("$apiBase/api/music/share-envelope/resolve?contentId=$contentParam&ownerAddress=$ownerParam&granteeAddress=$granteeParam")
      .header("X-User-Address", granteeAddress)
      .get()
      .build()
  uploadedTrackHttp.newCall(request).execute().use { response ->
    if (!response.isSuccessful) return emptyList()
    val raw = response.body?.string().orEmpty()
    if (raw.isBlank()) return emptyList()
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
    val refs = json.optJSONArray("refs") ?: return emptyList()
    if (refs.length() == 0) return emptyList()

    val out = ArrayList<String>(refs.length())
    for (i in 0 until refs.length()) {
      val ref = refs.optString(i, "").trim()
      if (ref.isNotBlank()) out.add(ref)
    }
    if (out.isNotEmpty()) {
      Log.d(
        SHARED_WITH_YOU_TAG,
        "share envelope resolve fallback source=api-core owner=$ownerAddress grantee=$granteeAddress contentId=$contentId refs=${out.size}",
      )
    }
    return out
  }
}

internal fun uploadShareEnvelopeViaApiCore(
  ownerAddress: String,
  granteeAddress: String,
  contentId: String,
  envelopePayload: JSONObject,
): String {
  val apiBase = SongPublishService.API_CORE_URL.trim().trimEnd('/')
  if (apiBase.isBlank()) {
    throw IllegalStateException("API_CORE_URL is missing or invalid")
  }

  val body =
    JSONObject()
      .put("contentId", contentId.trim().lowercase())
      .put("granteeAddress", granteeAddress.trim().lowercase())
      .put("envelope", envelopePayload)
      .toString()
      .toRequestBody(uploadedTrackJsonMediaType)
  val request =
    Request.Builder()
      .url("$apiBase/api/music/share-envelope")
      .header("X-User-Address", ownerAddress.trim().lowercase())
      .header("Content-Type", "application/json")
      .post(body)
      .build()

  uploadedTrackHttp.newCall(request).execute().use { response ->
    val raw = response.body?.string().orEmpty()
    val json = runCatching { JSONObject(raw) }.getOrNull()
    if (!response.isSuccessful) {
      val detail = json?.optString("detail", "")?.trim().orEmpty()
      val error = json?.optString("error", "")?.trim().orEmpty()
      val message =
        when {
          detail.isNotBlank() && error.isNotBlank() -> "$error: $detail"
          detail.isNotBlank() -> detail
          error.isNotBlank() -> error
          else -> "HTTP ${response.code}"
        }
      throw IllegalStateException("Share envelope upload failed via api-core: $message")
    }
    val ref = json?.optString("ref", "")?.trim().orEmpty()
    if (ref.isBlank()) {
      throw IllegalStateException("Share envelope upload succeeded but ref is missing")
    }
    return ref
  }
}

private fun parseEnvelopePayload(
  payload: ByteArray,
  expectedContentId: String,
  expectedOwner: String,
  expectedGrantee: String,
): EciesContentCrypto.EciesEnvelope? {
  val raw = payload.toString(Charsets.UTF_8).trim()
  if (raw.isBlank()) return null
  val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
  if (json.optInt("version", -1) != 1) return null

  val rawContentId = json.optString("contentId", "").trim()
  if (rawContentId.isEmpty()) return null
  val payloadContentId = normalizeUploadedContentId(rawContentId)
  if (payloadContentId != expectedContentId) return null

  val payloadOwner = json.optString("owner", "").trim().lowercase()
  if (payloadOwner.isEmpty() || payloadOwner != expectedOwner) return null

  val payloadGrantee = json.optString("grantee", "").trim().lowercase()
  if (payloadGrantee.isEmpty() || payloadGrantee != expectedGrantee) return null

  val ephemeralHex = json.optString("ephemeralPub", "").trim()
  val ivHex = json.optString("iv", "").trim()
  val ciphertextHex = json.optString("ciphertext", "").trim()
  if (ephemeralHex.isEmpty() || ivHex.isEmpty() || ciphertextHex.isEmpty()) return null

  val ephemeral = runCatching { P256Utils.hexToBytes(ephemeralHex) }.getOrNull() ?: return null
  val iv = runCatching { P256Utils.hexToBytes(ivHex) }.getOrNull() ?: return null
  val ciphertext = runCatching { P256Utils.hexToBytes(ciphertextHex) }.getOrNull() ?: return null
  if (ephemeral.size != 65 || iv.size != 12 || ciphertext.isEmpty()) return null

  return EciesContentCrypto.EciesEnvelope(
    ephemeralPub = ephemeral,
    iv = iv,
    ciphertext = ciphertext,
  )
}

internal fun fetchUploadedResolvePayload(dataitemId: String): ByteArray {
  return fetchResolveBytes(dataitemId)
}

internal fun fetchUploadedBlob(pieceCid: String): ByteArray {
  return fetchResolveBytes(pieceCid)
}

private fun fetchResolveBytes(dataitemId: String): ByteArray {
  val ref = dataitemId.trim()
  val resolvedUrl = resolveDataRefUrl(ref)
    ?: throw IllegalStateException("Unsupported payload ref format.")
  val request = Request.Builder().url(resolvedUrl).get().build()
  val response = uploadedTrackHttp.newCall(request).execute()
  if (!response.isSuccessful) {
    throw IllegalStateException("Failed to fetch payload (HTTP ${response.code}).")
  }
  return response.body?.bytes() ?: throw IllegalStateException("Payload is empty.")
}

private fun resolveDataRefUrl(ref: String): String? {
  val trimmed = ref.trim()
  if (trimmed.isBlank()) return null
  if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) return trimmed
  if (trimmed.startsWith("ar://")) {
    val id = trimmed.removePrefix("ar://").trim()
    if (id.isBlank()) return null
    return "${BuildConfig.ARWEAVE_GATEWAY_URL.trim().trimEnd('/')}/$id"
  }
  if (trimmed.startsWith("ipfs://")) {
    val cid = trimmed.removePrefix("ipfs://").trim()
    if (cid.isBlank()) return null
    return "${BuildConfig.IPFS_GATEWAY_URL.trim().trimEnd('/')}/$cid"
  }
  if (uploadedTrackCidRegex.matches(trimmed)) {
    return "${BuildConfig.IPFS_GATEWAY_URL.trim().trimEnd('/')}/$trimmed"
  }
  if (uploadedTrackDataItemIdRegex.matches(trimmed)) {
    return "${BuildConfig.ARWEAVE_GATEWAY_URL.trim().trimEnd('/')}/$trimmed"
  }
  return null
}

internal fun normalizeUploadedContentId(raw: String): String {
  val clean = raw.trim().lowercase().removePrefix("0x")
  if (clean.isBlank()) return ""
  return "0x$clean"
}

internal fun isUnsupportedUploadedTrackAlgo(algo: Int?): Boolean {
  return algo != null && algo != ContentCryptoConfig.ALGO_AES_GCM_256
}

internal fun uploadedTrackUnsupportedUploadError(): String {
  return "Unencrypted upload is not supported. Re-upload encrypted to enable this action."
}

internal fun uploadedTrackMissingWrappedKeyError(): String {
  return "Missing encrypted key for this track. This wallet may not have a key copy."
}

internal fun uploadedTrackPreferredExtension(track: MusicTrack): String {
  val fromFilename = track.filename.substringAfterLast('.', "").trim().lowercase()
  return when {
    fromFilename.isNotBlank() && fromFilename.length <= 10 -> fromFilename
    else -> "mp3"
  }
}
