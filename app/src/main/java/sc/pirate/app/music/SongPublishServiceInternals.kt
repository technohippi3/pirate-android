package sc.pirate.app.music

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import sc.pirate.app.tempo.P256Utils
import sc.pirate.app.tempo.SessionKeyManager
import sc.pirate.app.tempo.TempoClient
import sc.pirate.app.tempo.TempoPasskeyManager
import sc.pirate.app.tempo.TempoSessionKeyApi
import sc.pirate.app.tempo.TempoTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.bouncycastle.jcajce.provider.digest.Keccak
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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
import org.web3j.abi.datatypes.generated.Uint64
import org.web3j.abi.datatypes.generated.Uint8
import java.math.BigInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class SongPublishApiResponse(
  val status: Int,
  val body: String,
  val json: JSONObject?,
)

internal data class PublishedTrackReadiness(
  val trackId: String,
  val presentation: Presentation,
  val lyrics: Lyrics,
) {
  internal data class Presentation(
    val status: String,
    val ready: Boolean,
  )

  internal data class Lyrics(
    val status: String,
    val ready: Boolean,
    val manifestRef: String?,
  )
}

internal data class SongPublishPreparedStoryIntent(
  val operationId: String,
  val intent: Any,
  val typedDataDigest: String,
)

internal data class SongPublishMetaParts(
  val kind: Int,
  val payload: ByteArray,
  val trackId: ByteArray,
)

internal data class SongPreviewWindow(
  val startSec: Float,
  val endSec: Float,
  val durationSec: Float,
  val minClipSec: Float,
  val maxClipSec: Float,
)

private const val LANE_PRESET_NON_COMMERCIAL = "lane_v1_non_commercial"
private const val LANE_PRESET_COMMERCIAL_USE = "lane_v1_commercial_use"
private const val LANE_PRESET_COMMERCIAL_REMIX = "lane_v1_commercial_remix"
private val SONG_PUBLISH_REGISTER_AUTH_PATH_RE = Regex("^/api/music/publish/[^/]+/register(?:/confirm)?$")

internal suspend fun songPublishEnsureAuthorizedSessionKey(
  context: Context,
  activity: FragmentActivity,
  account: TempoPasskeyManager.PasskeyAccount,
): SessionKeyManager.SessionKey {
  val owner = account.address.trim().lowercase()
  val loaded = SessionKeyManager.load(context)
  val loadedValid =
    loaded != null &&
      SessionKeyManager.isValid(loaded, ownerAddress = owner) &&
      loaded.keyAuthorization?.isNotEmpty() == true
  if (loadedValid) return loaded!!

  val storedOwner = loaded?.ownerAddress?.trim()?.lowercase().orEmpty()
  if (storedOwner.isNotBlank() && storedOwner != owner) {
    SessionKeyManager.clear(context)
  }

  val auth = TempoSessionKeyApi.authorizeSessionKey(activity = activity, account = account)
  val authorized =
    auth.sessionKey?.takeIf {
      auth.success &&
        SessionKeyManager.isValid(it, ownerAddress = owner) &&
        it.keyAuthorization?.isNotEmpty() == true
    }
  return authorized ?: throw IllegalStateException(auth.error ?: "Session key authorization failed")
}

private fun songPublishShouldAttachMusicAuth(path: String): Boolean =
  SONG_PUBLISH_REGISTER_AUTH_PATH_RE.matches(path.trim())

private fun songPublishHashPersonalMessage(message: String): ByteArray {
  val prefix = "\u0019Ethereum Signed Message:\n${message.length}"
  val prefixedMessage = prefix.toByteArray(Charsets.UTF_8) + message.toByteArray(Charsets.UTF_8)
  return Keccak.Digest256().digest(prefixedMessage)
}

private fun songPublishBuildMusicAuthMessage(
  userAddress: String,
  method: String,
  path: String,
  bodySha256: String,
  timestamp: Long,
): String =
  listOf(
    "pirate-music-auth:v1",
    "wallet=${userAddress.trim().lowercase()}",
    "method=${method.trim().uppercase()}",
    "path=${path.trim()}",
    "body_sha256=$bodySha256",
    "timestamp=$timestamp",
  ).joinToString("\n")

internal fun songPublishKeyAuthorizationHex(sessionKey: SessionKeyManager.SessionKey): String {
  val keyAuthorization = sessionKey.keyAuthorization
    ?: throw IllegalStateException("Tempo session key authorization is missing")
  if (keyAuthorization.isEmpty()) {
    throw IllegalStateException("Tempo session key authorization is missing")
  }
  return "0x${P256Utils.bytesToHex(keyAuthorization)}"
}

internal fun songPublishSignDigestWithSessionKey(
  sessionKey: SessionKeyManager.SessionKey,
  userAddress: String,
  digestHex: String,
): String {
  val digest = P256Utils.hexToBytes(digestHex)
  val signature = SessionKeyManager.signWithSessionKey(
    sessionKey = sessionKey,
    userAddress = userAddress.trim().lowercase(),
    txHash = digest,
  )
  return "0x${signature.joinToString("") { "%02x".format(it) }}"
}

internal fun songPublishSha256Hex(data: ByteArray): String {
  val digest = MessageDigest.getInstance("SHA-256").digest(data)
  return digest.joinToString("") { "%02x".format(it) }
}

internal fun songPublishReadUriWithMaxBytes(context: Context, uri: Uri, maxBytes: Int): ByteArray {
  // Fast-path when provider exposes content length.
  context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
    val length = afd.length
    if (length > maxBytes) {
      throw IllegalStateException("Audio file exceeds 50MB limit ($length bytes)")
    }
  }

  val stream = context.contentResolver.openInputStream(uri)
    ?: throw IllegalStateException("Cannot open URI: $uri")

  stream.use { input ->
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
      val read = input.read(buffer)
      if (read <= 0) break
      total += read
      if (total > maxBytes) {
        throw IllegalStateException("Audio file exceeds 50MB limit ($total bytes)")
      }
      out.write(buffer, 0, read)
    }
    if (total == 0) throw IllegalStateException("Audio file is empty")
    return out.toByteArray()
  }
}

internal fun songPublishReadCoverUriWithMaxBytes(context: Context, uri: Uri, maxBytes: Int): ByteArray {
  // Fast-path when provider exposes content length.
  context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
    val length = afd.length
    if (length > maxBytes) {
      throw IllegalStateException("Cover file exceeds 10MB limit ($length bytes)")
    }
  }

  val stream = context.contentResolver.openInputStream(uri)
    ?: throw IllegalStateException("Cannot open URI: $uri")

  stream.use { input ->
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
      val read = input.read(buffer)
      if (read <= 0) break
      total += read
      if (total > maxBytes) {
        throw IllegalStateException("Cover file exceeds 10MB limit ($total bytes)")
      }
      out.write(buffer, 0, read)
    }
    if (total == 0) throw IllegalStateException("Cover file is empty")
    return out.toByteArray()
  }
}

internal fun songPublishReadCanvasUriWithMaxBytes(context: Context, uri: Uri, maxBytes: Int): ByteArray {
  context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
    val length = afd.length
    if (length > maxBytes) {
      throw IllegalStateException("Canvas video exceeds 20MB limit ($length bytes)")
    }
  }

  val stream = context.contentResolver.openInputStream(uri)
    ?: throw IllegalStateException("Cannot open URI: $uri")

  stream.use { input ->
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
      val read = input.read(buffer)
      if (read <= 0) break
      total += read
      if (total > maxBytes) {
        throw IllegalStateException("Canvas video exceeds 20MB limit ($total bytes)")
      }
      out.write(buffer, 0, read)
    }
    if (total == 0) throw IllegalStateException("Canvas video is empty")
    return out.toByteArray()
  }
}

internal fun songPublishGetMimeType(context: Context, uri: Uri): String =
  context.contentResolver.getType(uri) ?: "application/octet-stream"

internal fun songPublishGetFileName(context: Context, uri: Uri): String? {
  context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    if (index >= 0 && cursor.moveToFirst()) {
      return cursor.getString(index)?.trim()?.ifBlank { null }
    }
  }
  return uri.lastPathSegment?.substringAfterLast('/')?.trim()?.ifBlank { null }
}

internal fun songPublishIsLikelyMp3(mimeType: String?, fileName: String?): Boolean {
  val normalizedMime = mimeType?.trim()?.lowercase().orEmpty()
  if (normalizedMime == "audio/mpeg" || normalizedMime == "audio/mp3") return true
  val normalizedFileName = fileName?.trim()?.lowercase().orEmpty()
  return normalizedFileName.endsWith(".mp3")
}

internal fun songPublishIsLikelyAudioContent(mimeType: String?, fileName: String?): Boolean {
  val normalizedMime = mimeType?.trim()?.lowercase().orEmpty()
  if (normalizedMime.startsWith("audio/")) return true
  val normalizedFileName = fileName?.trim()?.lowercase().orEmpty()
  return normalizedFileName.endsWith(".mp3") ||
    normalizedFileName.endsWith(".wav") ||
    normalizedFileName.endsWith(".flac") ||
    normalizedFileName.endsWith(".ogg") ||
    normalizedFileName.endsWith(".m4a") ||
    normalizedFileName.endsWith(".aac") ||
    normalizedFileName.endsWith(".mp4") ||
    normalizedFileName.endsWith(".webm")
}

internal fun songPublishIsLikelyVideoContent(mimeType: String?, fileName: String?): Boolean {
  val normalizedMime = mimeType?.trim()?.lowercase().orEmpty()
  if (normalizedMime.startsWith("video/")) return true
  val normalizedFileName = fileName?.trim()?.lowercase().orEmpty()
  return normalizedFileName.endsWith(".mp4") ||
    normalizedFileName.endsWith(".webm") ||
    normalizedFileName.endsWith(".mov") ||
    normalizedFileName.endsWith(".mkv")
}

internal fun songPublishGetAudioDurationSec(context: Context, uri: Uri): Float {
  val retriever = MediaMetadataRetriever()
  return try {
    retriever.setDataSource(context, uri)
    val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    (durationMs?.toFloat() ?: 0f) / 1000f
  } catch (_: Throwable) {
    0f
  } finally {
    runCatching { retriever.release() }
  }
}

internal fun songPublishNormalizePreviewWindow(
  rawStartSec: Float,
  rawEndSec: Float,
  rawDurationSec: Float,
  minClipSec: Float = 5f,
  maxClipSec: Float = 30f,
): SongPreviewWindow {
  val duration = if (rawDurationSec.isFinite()) rawDurationSec.coerceAtLeast(0f) else 0f
  if (duration <= 0f) {
    return SongPreviewWindow(
      startSec = 0f,
      endSec = 0f,
      durationSec = 0f,
      minClipSec = 0f,
      maxClipSec = 0f,
    )
  }

  val safeMaxClip = maxClipSec.coerceAtLeast(1f).coerceAtMost(duration)
  val safeMinClip = minClipSec.coerceAtLeast(1f).coerceAtMost(safeMaxClip)

  var start = if (rawStartSec.isFinite()) rawStartSec else 0f
  var end = if (rawEndSec.isFinite()) rawEndSec else safeMaxClip

  val startMax = (duration - safeMinClip).coerceAtLeast(0f)
  start = start.coerceIn(0f, startMax)
  end = end.coerceIn(0f, duration)

  if (end <= start) {
    end = (start + safeMinClip).coerceAtMost(duration)
  }
  if (end - start > safeMaxClip) {
    end = (start + safeMaxClip).coerceAtMost(duration)
  }
  if (end - start < safeMinClip) {
    if (start + safeMinClip <= duration) {
      end = start + safeMinClip
    } else {
      end = duration
      start = (end - safeMinClip).coerceAtLeast(0f)
    }
  }

  start = start.coerceIn(0f, (duration - safeMinClip).coerceAtLeast(0f))
  end = end.coerceIn(start + safeMinClip, duration)

  return SongPreviewWindow(
    startSec = start,
    endSec = end,
    durationSec = duration,
    minClipSec = safeMinClip,
    maxClipSec = safeMaxClip,
  )
}

internal suspend fun songPublishBuildPreviewClip(
  context: Context,
  sourceAudioUri: Uri,
  previewStartSec: Int,
  previewEndSec: Int,
  maxBytes: Int,
): ByteArray {
  if (previewEndSec <= previewStartSec) {
    throw IllegalStateException("Preview end must be greater than preview start")
  }

  val outputDir = File(context.cacheDir, "song-preview-clips")
  if (!outputDir.exists()) {
    outputDir.mkdirs()
  }
  val outputFile = File(outputDir, "${System.currentTimeMillis()}-preview.mp4")

  try {
    withContext(Dispatchers.Main.immediate) {
      val mediaItem =
        MediaItem.Builder()
          .setUri(sourceAudioUri)
          .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
              .setStartPositionMs(previewStartSec.toLong() * 1_000L)
              .setEndPositionMs(previewEndSec.toLong() * 1_000L)
              .build(),
          )
          .build()
      val editedMediaItem =
        EditedMediaItem.Builder(mediaItem)
          .setRemoveVideo(true)
          .build()
      val composition = Composition.Builder(listOf(EditedMediaItemSequence(listOf(editedMediaItem)))).build()

      suspendCancellableCoroutine<Unit> { continuation ->
        val listener =
          object : Transformer.Listener {
            override fun onCompleted(
              composition: Composition,
              exportResult: ExportResult,
            ) {
              if (!continuation.isActive) return
              continuation.resume(Unit)
            }

            override fun onError(
              composition: Composition,
              exportResult: ExportResult,
              exportException: ExportException,
            ) {
              if (!continuation.isActive) return
              continuation.resumeWithException(exportException)
            }
          }
        val transformer =
          Transformer.Builder(context)
            .addListener(listener)
            .build()
        continuation.invokeOnCancellation {
          runCatching { transformer.cancel() }
        }
        transformer.start(composition, outputFile.absolutePath)
      }
    }

    val outputSize = outputFile.length()
    if (outputSize <= 0L) {
      throw IllegalStateException("Preview clip export produced an empty file")
    }
    if (outputSize > maxBytes.toLong()) {
      throw IllegalStateException("Preview clip exceeds ${maxBytes / (1024 * 1024)}MB limit ($outputSize bytes)")
    }

    return outputFile.readBytes()
  } finally {
    runCatching { outputFile.delete() }
  }
}

internal fun songPublishGuessImageExtension(mimeType: String): String {
  val normalized = mimeType.trim().lowercase()
  return when {
    normalized.contains("png") -> "png"
    normalized.contains("webp") -> "webp"
    normalized.contains("gif") -> "gif"
    else -> "jpg"
  }
}

internal fun songPublishCacheRecentCoverRef(
  context: Context,
  coverUri: Uri,
  audioSha256: String,
  recentCoversDir: String,
  maxCoverBytes: Int,
): String? {
  return runCatching {
    val mime = songPublishGetMimeType(context, coverUri)
    val ext = songPublishGuessImageExtension(mime)
    val dir = File(context.filesDir, recentCoversDir)
    if (!dir.exists()) dir.mkdirs()
    val file = File(dir, "${audioSha256.take(24)}.$ext")

    context.contentResolver.openInputStream(coverUri)?.use { input ->
      file.outputStream().use { out ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
          val read = input.read(buffer)
          if (read <= 0) break
          total += read
          if (total > maxCoverBytes) {
            throw IllegalStateException("Cover image exceeds 10MB local cache limit")
          }
          out.write(buffer, 0, read)
        }
        if (total == 0L) throw IllegalStateException("Cover image is empty")
      }
    } ?: throw IllegalStateException("Cannot open cover URI: $coverUri")

    Uri.fromFile(file).toString()
  }.getOrNull()
}

private fun songPublishParseJsonObject(raw: String): JSONObject? {
  if (raw.isBlank()) return null
  return runCatching { JSONObject(raw) }.getOrNull()
}

private fun songPublishReadApiResponse(conn: HttpURLConnection): SongPublishApiResponse {
  val status = conn.responseCode
  val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
    ?.bufferedReader()
    ?.use { it.readText() }
    .orEmpty()
  return SongPublishApiResponse(status = status, body = body, json = songPublishParseJsonObject(body))
}

internal fun songPublishErrorMessageFromApi(
  operation: String,
  response: SongPublishApiResponse,
): String {
  val errorText = response.json
    ?.optString("error", "")
    ?.trim()
    ?.ifBlank { null }
  val detailsText = response.json
    ?.optString("details", "")
    ?.trim()
    ?.ifBlank { null }
  val base = when {
    errorText != null && detailsText != null && !errorText.equals(detailsText, ignoreCase = true) ->
      "$errorText ($detailsText)"
    errorText != null -> errorText
    detailsText != null -> detailsText
    else -> response.body.trim().ifBlank { null } ?: "HTTP ${response.status}"
  }
  return "$operation failed: $base"
}

internal fun songPublishPostJsonToMusicApi(
  path: String,
  userAddress: String,
  body: JSONObject,
  sessionKey: SessionKeyManager.SessionKey? = null,
): SongPublishApiResponse {
  val url = URL("${SongPublishService.API_CORE_URL}$path")
  val bodyText = body.toString()
  val conn = (url.openConnection() as HttpURLConnection).apply {
    requestMethod = "POST"
    doOutput = true
    connectTimeout = 120_000
    readTimeout = 120_000
    setRequestProperty("Content-Type", "application/json")
    setRequestProperty("X-User-Address", userAddress)
    if (sessionKey != null && songPublishShouldAttachMusicAuth(path)) {
      val timestamp = System.currentTimeMillis() / 1_000L
      val bodySha256 = songPublishSha256Hex(bodyText.toByteArray(Charsets.UTF_8))
      val message = songPublishBuildMusicAuthMessage(
        userAddress = userAddress,
        method = requestMethod,
        path = path,
        bodySha256 = bodySha256,
        timestamp = timestamp,
      )
      val digest = songPublishHashPersonalMessage(message)
      val signature = SessionKeyManager.signWithSessionKey(
        sessionKey = sessionKey,
        userAddress = userAddress,
        txHash = digest,
      )
      setRequestProperty("X-Music-Auth-Timestamp", timestamp.toString())
      setRequestProperty("X-Music-Auth-Signature", "0x${signature.joinToString("") { "%02x".format(it) }}")
      setRequestProperty("X-Music-Auth-Key-Authorization", songPublishKeyAuthorizationHex(sessionKey))
    }
  }
  conn.outputStream.use { out ->
    out.write(bodyText.toByteArray(Charsets.UTF_8))
  }
  return songPublishReadApiResponse(conn)
}

internal fun songPublishGetFromMusicApi(path: String): SongPublishApiResponse {
  val url = URL("${SongPublishService.API_CORE_URL}$path")
  val conn = (url.openConnection() as HttpURLConnection).apply {
    requestMethod = "GET"
    connectTimeout = 120_000
    readTimeout = 120_000
    setRequestProperty("Accept", "application/json")
  }
  return songPublishReadApiResponse(conn)
}

internal fun songPublishGetPublishedTrackStatus(
  trackId: String,
): SongPublishApiResponse {
  val normalizedTrackId = trackId.trim().lowercase()
  return songPublishGetFromMusicApi(
    path = "/api/music/tracks/$normalizedTrackId/status",
  )
}

internal fun songPublishParsePublishedTrackReadiness(json: JSONObject?): PublishedTrackReadiness? {
  if (json == null) return null
  val trackId = json.optString("trackId", "").trim().lowercase()
  if (trackId.isBlank()) return null
  val presentationJson = json.optJSONObject("presentation") ?: return null
  val lyricsJson = json.optJSONObject("lyrics") ?: return null
  return PublishedTrackReadiness(
    trackId = trackId,
    presentation =
      PublishedTrackReadiness.Presentation(
        status = presentationJson.optString("status", "").trim().ifBlank { "none" },
        ready = presentationJson.optBoolean("ready", false),
      ),
    lyrics =
      PublishedTrackReadiness.Lyrics(
        status = lyricsJson.optString("status", "").trim().ifBlank { "processing" },
        ready = lyricsJson.optBoolean("ready", false),
        manifestRef = lyricsJson.optString("manifestRef", "").trim().ifBlank { null },
      ),
  )
}

internal fun songPublishParsePreparedStoryIntent(response: SongPublishApiResponse): SongPublishPreparedStoryIntent? {
  if (response.status !in 200..299) return null
  val registration = response.json?.optJSONObject("registration") ?: return null
  if (!registration.optBoolean("requiresIntentSignature", false)) return null
  val operationId = registration.optString("operationId", "").trim()
  if (operationId.isBlank()) {
    throw IllegalStateException("Story intent prepare response is missing operationId")
  }
  val intent = registration.opt("intent")
    ?: throw IllegalStateException("Story intent prepare response is missing intent")
  val typedDataDigest = registration
    .optJSONObject("typedData")
    ?.optString("digest", "")
    ?.trim()
    .orEmpty()
  if (!Regex("^0x[a-fA-F0-9]{64}$").matches(typedDataDigest)) {
    throw IllegalStateException("Story intent prepare response is missing typedData.digest")
  }
  return SongPublishPreparedStoryIntent(
    operationId = operationId,
    intent = intent,
    typedDataDigest = typedDataDigest.lowercase(),
  )
}

internal fun songPublishFinalizeMusicPublish(
  jobId: String,
  userAddress: String,
  title: String,
  artist: String,
  durationSec: Int,
  tempoSignedTx: String,
  album: String = "",
  purchasePrice: String? = null,
  maxSupply: Int? = null,
): SongPublishApiResponse {
  val body = JSONObject().apply {
    put("title", title.trim())
    put("artist", artist.trim())
    put("album", album.trim())
    put("durationS", durationSec)
    put("tempoSignedTx", tempoSignedTx.trim())
    if (!purchasePrice.isNullOrBlank()) {
      put("purchasePrice", purchasePrice.trim())
    }
    if (maxSupply != null) {
      put("maxSupply", maxSupply)
    }
  }
  return songPublishPostJsonToMusicApi(
    path = "/api/music/publish/$jobId/finalize",
    userAddress = userAddress,
    body = body,
  )
}

internal fun songPublishAttachPresentationForMusicPublish(
  jobId: String,
  userAddress: String,
  delegateSignedTx: String,
): SongPublishApiResponse {
  val body = JSONObject().apply {
    put("delegateSignedTx", delegateSignedTx.trim())
  }
  return songPublishPostJsonToMusicApi(
    path = "/api/music/publish/$jobId/presentation/attach",
    userAddress = userAddress,
    body = body,
  )
}

internal suspend fun songPublishBuildSignedTempoPublishTx(
  context: Context,
  activity: FragmentActivity,
  account: TempoPasskeyManager.PasskeyAccount,
  coordinatorAddress: String,
  ownerAddress: String,
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
  val owner = normalizeAddress(ownerAddress)
  val coordinator = normalizeAddress(coordinatorAddress)
  val dataset = normalizeAddress(datasetOwner)
  require(account.address.equals(owner, ignoreCase = true)) { "Passkey account must match publish owner" }
  require(durationSec > 0) { "durationSec must be positive" }
  require(algo in 1..255) { "algo must be between 1 and 255" }
  require(visibility in 0..2) { "visibility must be 0, 1, or 2" }
  require(pieceCid.isNotBlank()) { "pieceCid is required" }

  val callData = songPublishEncodeCoordinatorPublishCallData(
    owner = owner,
    title = title,
    artist = artist,
    album = album,
    durationSec = durationSec,
    coverRef = coverRef,
    datasetOwner = dataset,
    pieceCid = pieceCid,
    algo = algo,
    visibility = visibility,
    replaceIfActive = replaceIfActive,
  )
  return songPublishBuildSignedTempoContractCallTx(
    context = context,
    activity = activity,
    account = account,
    ownerAddress = owner,
    targetAddress = coordinator,
    callData = callData,
    minimumGasLimit = 650_000L,
  )
}

internal suspend fun songPublishBuildSignedTempoSetPublishDelegateTx(
  context: Context,
  activity: FragmentActivity,
  account: TempoPasskeyManager.PasskeyAccount,
  registryAddress: String,
  ownerAddress: String,
  publishId: String,
  delegateAddress: String,
  permissions: Int,
  expiresAtSec: Long,
): String {
  val owner = normalizeAddress(ownerAddress)
  val registry = normalizeAddress(registryAddress)
  require(account.address.equals(owner, ignoreCase = true)) { "Passkey account must match publish owner" }
  require(permissions in 1..3) { "permissions must be between 1 and 3" }
  require(expiresAtSec > 0L) { "expiresAtSec must be > 0" }

  val callData = songPublishEncodeSetPublishDelegateCallData(
    publishId = publishId,
    delegateAddress = delegateAddress,
    permissions = permissions,
    expiresAtSec = expiresAtSec,
  )
  return songPublishBuildSignedTempoContractCallTx(
    context = context,
    activity = activity,
    account = account,
    ownerAddress = owner,
    targetAddress = registry,
    callData = callData,
    minimumGasLimit = 350_000L,
  )
}

private suspend fun songPublishBuildSignedTempoContractCallTx(
  context: Context,
  activity: FragmentActivity,
  account: TempoPasskeyManager.PasskeyAccount,
  ownerAddress: String,
  targetAddress: String,
  callData: String,
  minimumGasLimit: Long,
): String {
  val owner = normalizeAddress(ownerAddress)
  val target = normalizeAddress(targetAddress)
  require(account.address.equals(owner, ignoreCase = true)) { "Passkey account must match tx signer" }
  val nonce = withContext(Dispatchers.IO) { TempoClient.getNonce(owner) }
  val fees = withContext(Dispatchers.IO) { withAddressBidFloor(owner, TempoClient.getSuggestedFees()) }
  val gasLimit =
    withContext(Dispatchers.IO) {
      val estimated = estimateGas(from = owner, to = target, data = callData)
      withBuffer(estimated = estimated, minimum = minimumGasLimit)
    }
  val tx =
    TempoTransaction.UnsignedTx(
      nonce = nonce,
      maxPriorityFeePerGas = fees.maxPriorityFeePerGas,
      maxFeePerGas = fees.maxFeePerGas,
      feeMode = TempoTransaction.FeeMode.RELAY_SPONSORED,
      gasLimit = gasLimit,
      calls =
        listOf(
          TempoTransaction.Call(
            to = P256Utils.hexToBytes(target),
            value = 0,
            input = P256Utils.hexToBytes(callData),
          ),
        ),
    )
  val sigHash = TempoTransaction.signatureHash(tx)
  val sessionKey =
    SessionKeyManager.load(context)?.takeIf {
      SessionKeyManager.isValid(it, ownerAddress = owner) && it.keyAuthorization?.isNotEmpty() == true
    }
  val signedTx =
    if (sessionKey != null) {
      val keychainSig =
        SessionKeyManager.signWithSessionKey(
          sessionKey = sessionKey,
          userAddress = owner,
          txHash = sigHash,
        )
      TempoTransaction.encodeSignedSessionKey(tx, keychainSig)
    } else {
      val assertion =
        TempoPasskeyManager.sign(
          activity = activity,
          challenge = sigHash,
          account = account,
          rpId = account.rpId,
        )
      TempoTransaction.encodeSignedWebAuthn(tx, assertion)
    }
  rememberAddressBidFloor(owner, fees)
  return signedTx
}

internal fun songPublishEncodeCoordinatorPublishCallData(
  owner: String,
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
  val normalizedTitle = title.trim()
  val normalizedArtist = artist.trim()
  val normalizedAlbum = album.trim()
  val normalizedCoverRef = coverRef.trim()
  val normalizedPieceCid = pieceCid.trim()
  val parts = songPublishComputeMetaParts(
    title = normalizedTitle,
    artist = normalizedArtist,
    album = normalizedAlbum,
  )
  val publishStruct =
    DynamicStruct(
      Address(owner),
      Uint8(BigInteger.valueOf(3L)),
      Bytes32(parts.payload),
      Utf8String(normalizedTitle),
      Utf8String(normalizedArtist),
      Utf8String(normalizedAlbum),
      Uint32(BigInteger.valueOf(durationSec.toLong())),
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

internal fun songPublishEncodeSetPublishDelegateCallData(
  publishId: String,
  delegateAddress: String,
  permissions: Int,
  expiresAtSec: Long,
): String {
  val normalizedPublishId = normalizeBytes32(publishId, "publishId")
  val normalizedDelegate = normalizeAddress(delegateAddress)
  val function =
    Function(
      "setPublishDelegate",
      listOf(
        Bytes32(P256Utils.hexToBytes(normalizedPublishId)),
        Address(normalizedDelegate),
        Uint8(BigInteger.valueOf(permissions.toLong())),
        Uint64(BigInteger.valueOf(expiresAtSec)),
      ),
      emptyList(),
    )
  return FunctionEncoder.encode(function)
}

internal fun songPublishComputeMetaParts(
  title: String,
  artist: String,
  album: String,
): SongPublishMetaParts {
  val normalizedTitle = title.trim()
  val normalizedArtist = artist.trim()
  val normalizedAlbum = album.trim()
  val payload = songPublishComputeMetaPayload(normalizedTitle, normalizedArtist, normalizedAlbum)
  val trackId = Keccak.Digest256().digest(songPublishAbiEncodeUint8Bytes32(kind = 3, payload32 = payload))
  return SongPublishMetaParts(
    kind = 3,
    payload = payload,
    trackId = trackId,
  )
}

private fun songPublishComputeMetaPayload(
  title: String,
  artist: String,
  album: String,
): ByteArray {
  val encoded = songPublishAbiEncodeStrings(title, artist, album)
  return Keccak.Digest256().digest(encoded)
}

private fun songPublishAbiEncodeStrings(
  title: String,
  artist: String,
  album: String,
): ByteArray {
  val values = listOf(title, artist, album).map { it.toByteArray(Charsets.UTF_8) }
  val headSize = 32 * values.size
  val tails =
    values.map { bytes ->
      val paddedLen = ((bytes.size + 31) / 32) * 32
      ByteArrayOutputStream().apply {
        write(songPublishU256Word(bytes.size.toLong()))
        write(bytes)
        if (paddedLen > bytes.size) write(ByteArray(paddedLen - bytes.size))
      }.toByteArray()
    }

  var offset = headSize.toLong()
  return ByteArrayOutputStream().apply {
    tails.forEach { tail ->
      write(songPublishU256Word(offset))
      offset += tail.size.toLong()
    }
    tails.forEach { write(it) }
  }.toByteArray()
}

private fun songPublishU256Word(value: Long): ByteArray {
  val out = ByteArray(32)
  var v = value
  var i = 31
  while (i >= 0 && v != 0L) {
    out[i] = (v and 0xff).toByte()
    v = v ushr 8
    i--
  }
  return out
}

private fun songPublishAbiEncodeUint8Bytes32(kind: Int, payload32: ByteArray): ByteArray {
  require(kind in 0..255) { "kind must fit in uint8" }
  require(payload32.size == 32) { "payload must be 32 bytes" }

  val out = ByteArray(64)
  out[31] = kind.toByte()
  System.arraycopy(payload32, 0, out, 32, 32)
  return out
}

internal fun songPublishUploadMetadataMusicPublish(
  jobId: String,
  userAddress: String,
  ipMetadataJson: JSONObject,
  nftMetadataJson: JSONObject,
): SongPublishApiResponse {
  val body = JSONObject().apply {
    put("ipMetadataJson", ipMetadataJson)
    put("nftMetadataJson", nftMetadataJson)
  }
  return songPublishPostJsonToMusicApi(
    path = "/api/music/publish/$jobId/metadata",
    userAddress = userAddress,
    body = body,
  )
}

internal fun songPublishRegisterMusicPublish(
  jobId: String,
  userAddress: String,
  recipient: String,
  ipMetadataURI: String,
  ipMetadataHash: String,
  nftMetadataURI: String,
  nftMetadataHash: String,
  license: String,
  commercialRevShare: Int,
  donationPolicy: JSONObject? = null,
  defaultMintingFee: String,
  sessionKey: SessionKeyManager.SessionKey,
  storyIntentOperationId: String? = null,
  storyIntentUserSig: String? = null,
  storyIntent: Any? = null,
  storyIntentKeyAuthorization: String? = null,
): SongPublishApiResponse {
  val lanePresetId =
    when (license.trim()) {
      "commercial-use" -> LANE_PRESET_COMMERCIAL_USE
      "commercial-remix" -> LANE_PRESET_COMMERCIAL_REMIX
      else -> LANE_PRESET_NON_COMMERCIAL
    }
  val normalizedRevShare = commercialRevShare.coerceIn(0, 100)
  val normalizedDefaultMintingFee = defaultMintingFee.trim().takeIf { it.matches(Regex("^\\d+$")) } ?: "0"
  val body = JSONObject().apply {
    put("recipient", recipient.trim())
    put("ipMetadataURI", ipMetadataURI.trim())
    put("ipMetadataHash", ipMetadataHash.trim())
    put("nftMetadataURI", nftMetadataURI.trim())
    put("nftMetadataHash", nftMetadataHash.trim())
    put("lanePresetId", lanePresetId)
    put("commercialRevShare", normalizedRevShare)
    put("defaultMintingFee", normalizedDefaultMintingFee)
    put("allowDuplicates", true)
    if (donationPolicy != null) put("donationPolicy", donationPolicy)
    if (!storyIntentOperationId.isNullOrBlank() || !storyIntentUserSig.isNullOrBlank() || storyIntent != null) {
      put("storyIntentOperationId", storyIntentOperationId?.trim())
      put("storyIntentUserSig", storyIntentUserSig?.trim())
      put("storyIntent", storyIntent)
      if (!storyIntentKeyAuthorization.isNullOrBlank()) {
        put("storyIntentKeyAuthorization", storyIntentKeyAuthorization.trim())
      }
    }
  }
  return songPublishPostJsonToMusicApi(
    path = "/api/music/publish/$jobId/register",
    userAddress = userAddress,
    body = body,
    sessionKey = sessionKey,
  )
}

internal fun songPublishConfirmRegisterMusicPublish(
  jobId: String,
  userAddress: String,
  sessionKey: SessionKeyManager.SessionKey,
  storyIntentOperationId: String? = null,
  storyIntentUserSig: String? = null,
  storyIntent: Any? = null,
  storyIntentKeyAuthorization: String? = null,
): SongPublishApiResponse {
  val body = JSONObject()
  if (!storyIntentOperationId.isNullOrBlank() || !storyIntentUserSig.isNullOrBlank() || storyIntent != null) {
    body.put("storyIntentOperationId", storyIntentOperationId?.trim())
    body.put("storyIntentUserSig", storyIntentUserSig?.trim())
    body.put("storyIntent", storyIntent)
    if (!storyIntentKeyAuthorization.isNullOrBlank()) {
      body.put("storyIntentKeyAuthorization", storyIntentKeyAuthorization.trim())
    }
  }
  return songPublishPostJsonToMusicApi(
    path = "/api/music/publish/$jobId/register/confirm",
    userAddress = userAddress,
    body = body,
    sessionKey = sessionKey,
  )
}

internal fun songPublishStageAudioForMusicPublish(
  audioBytes: ByteArray,
  audioMime: String,
  audioSha256: String,
  durationSec: Int,
  userAddress: String,
  idempotencyKey: String,
): SongPublishApiResponse {
  val boundary = "----PirateMusicStart${System.currentTimeMillis()}"
  val url = URL("${SongPublishService.API_CORE_URL}/api/music/publish/start")
  val conn = (url.openConnection() as HttpURLConnection).apply {
    requestMethod = "POST"
    doOutput = true
    connectTimeout = 120_000
    readTimeout = 120_000
    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
    setRequestProperty("X-User-Address", userAddress)
    setRequestProperty("Idempotency-Key", idempotencyKey)
  }

  val tags =
    """[{"key":"App-Name","value":"Pirate"},{"key":"Upload-Source","value":"android-song-publish"}]"""
  val fingerprint = "sha256:$audioSha256"

  conn.outputStream.use { out ->
    fun writeField(name: String, value: String) {
      out.write("--$boundary\r\n".toByteArray())
      out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
      out.write(value.toByteArray(Charsets.UTF_8))
      out.write("\r\n".toByteArray())
    }

    out.write("--$boundary\r\n".toByteArray())
    out.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.bin\"\r\n".toByteArray())
    out.write("Content-Type: $audioMime\r\n\r\n".toByteArray())
    out.write(audioBytes)
    out.write("\r\n".toByteArray())

    writeField("publishType", "original")
    writeField("contentType", audioMime)
    writeField("sourceAudioEncryption", "none")
    writeField("audioSha256", audioSha256)
    writeField("durationS", durationSec.toString())
    writeField("fingerprint", fingerprint)
    writeField("idempotencyKey", idempotencyKey)
    writeField("tags", tags)
    out.write("--$boundary--\r\n".toByteArray())
  }

  return songPublishReadApiResponse(conn)
}

internal fun songPublishStageArtifactsForMusicPublish(
  jobId: String,
  userAddress: String,
  coverBytes: ByteArray,
  coverMime: String,
  lyricsText: String,
  previewClipBytes: ByteArray,
  previewClipMime: String,
  previewStartSec: Int,
  previewEndSec: Int,
  canvasBytes: ByteArray? = null,
  canvasMime: String? = null,
  instrumentalBytes: ByteArray,
  instrumentalMime: String? = null,
  vocalsBytes: ByteArray,
  vocalsMime: String? = null,
): SongPublishApiResponse {
  val boundary = "----PirateMusicArtifacts${System.currentTimeMillis()}"
  val url = URL("${SongPublishService.API_CORE_URL}/api/music/publish/$jobId/artifacts/stage")
  val conn = (url.openConnection() as HttpURLConnection).apply {
    requestMethod = "POST"
    doOutput = true
    connectTimeout = 120_000
    readTimeout = 120_000
    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
    setRequestProperty("X-User-Address", userAddress)
  }

  conn.outputStream.use { out ->
    fun writeField(name: String, value: String) {
      out.write("--$boundary\r\n".toByteArray())
      out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
      out.write(value.toByteArray(Charsets.UTF_8))
      out.write("\r\n".toByteArray())
    }

    out.write("--$boundary\r\n".toByteArray())
    out.write("Content-Disposition: form-data; name=\"cover\"; filename=\"cover.bin\"\r\n".toByteArray())
    out.write("Content-Type: $coverMime\r\n\r\n".toByteArray())
    out.write(coverBytes)
    out.write("\r\n".toByteArray())

    out.write("--$boundary\r\n".toByteArray())
    out.write("Content-Disposition: form-data; name=\"preview\"; filename=\"preview.m4a\"\r\n".toByteArray())
    out.write("Content-Type: $previewClipMime\r\n\r\n".toByteArray())
    out.write(previewClipBytes)
    out.write("\r\n".toByteArray())

    if (canvasBytes != null) {
      val normalizedCanvasMime = canvasMime?.trim().orEmpty().ifBlank { "video/mp4" }
      out.write("--$boundary\r\n".toByteArray())
      out.write("Content-Disposition: form-data; name=\"canvas\"; filename=\"canvas.mp4\"\r\n".toByteArray())
      out.write("Content-Type: $normalizedCanvasMime\r\n\r\n".toByteArray())
      out.write(canvasBytes)
      out.write("\r\n".toByteArray())
      writeField("canvasContentType", normalizedCanvasMime)
    }

    val instrumentalContentType = instrumentalMime?.trim().orEmpty().ifBlank { "audio/mpeg" }
    out.write("--$boundary\r\n".toByteArray())
    out.write("Content-Disposition: form-data; name=\"instrumental\"; filename=\"instrumental.bin\"\r\n".toByteArray())
    out.write("Content-Type: $instrumentalContentType\r\n\r\n".toByteArray())
    out.write(instrumentalBytes)
    out.write("\r\n".toByteArray())
    writeField("instrumentalContentType", instrumentalContentType)

    val contentType = vocalsMime?.trim().orEmpty().ifBlank { "audio/mpeg" }
    out.write("--$boundary\r\n".toByteArray())
    out.write("Content-Disposition: form-data; name=\"vocals\"; filename=\"vocals.bin\"\r\n".toByteArray())
    out.write("Content-Type: $contentType\r\n\r\n".toByteArray())
    out.write(vocalsBytes)
    out.write("\r\n".toByteArray())
    writeField("vocalsContentType", contentType)

    writeField("coverContentType", coverMime)
    writeField("previewContentType", previewClipMime)
    writeField("previewStartSec", previewStartSec.toString())
    writeField("previewEndSec", previewEndSec.toString())
    writeField("lyricsText", lyricsText)
    out.write("--$boundary--\r\n".toByteArray())
  }

  return songPublishReadApiResponse(conn)
}

internal fun songPublishRequireJobObject(
  operation: String,
  response: SongPublishApiResponse,
): JSONObject {
  val job = response.json?.optJSONObject("job")
  if (job != null) return job
  throw IllegalStateException("$operation failed: missing job in response")
}

internal fun songPublishExtractDataitemId(job: JSONObject): String? {
  val stagedId =
    job
      .optJSONObject("upload")
      ?.optString("stagedDataitemId", "")
      ?.trim()
      .orEmpty()
  return stagedId.ifBlank { null }
}

internal fun songPublishExtractStagedCoverGatewayUrl(job: JSONObject): String? {
  return job
    .optJSONObject("upload")
    ?.optJSONObject("cover")
    ?.optString("stagedGatewayUrl", "")
    ?.trim()
    ?.ifBlank { null }
}

internal fun songPublishExtractStagedCoverDataitemId(job: JSONObject): String? {
  return job
    .optJSONObject("upload")
    ?.optJSONObject("cover")
    ?.optString("stagedDataitemId", "")
    ?.trim()
    ?.ifBlank { null }
}

internal fun songPublishExtractStagedInstrumentalDataitemId(job: JSONObject): String? {
  return job
    .optJSONObject("upload")
    ?.optJSONObject("stems")
    ?.optJSONObject("instrumental")
    ?.optString("stagedDataitemId", "")
    ?.trim()
    ?.ifBlank { null }
}

internal fun songPublishExtractStagedVocalsDataitemId(job: JSONObject): String? {
  return job
    .optJSONObject("upload")
    ?.optJSONObject("stems")
    ?.optJSONObject("vocals")
    ?.optString("stagedDataitemId", "")
    ?.trim()
    ?.ifBlank { null }
}

internal fun songPublishNormalizeUserAddress(address: String): String {
  val clean = address.trim().lowercase()
  if (!Regex("^0x[a-f0-9]{40}$").matches(clean)) {
    throw IllegalStateException("Invalid user address: $address")
  }
  return clean
}
