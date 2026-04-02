package sc.pirate.app.music

import android.content.Context
import android.net.Uri
import sc.pirate.app.BuildConfig
import sc.pirate.app.arweave.ArweaveUploadApi
import sc.pirate.app.crypto.ContentKeyManager
import sc.pirate.app.crypto.EciesContentCrypto
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SaveForeverResult(
  val trackId: String,
  val contentId: String,
  val permanentRef: String,
  val permanentGatewayUrl: String,
  val permanentSavedAtMs: Long,
  val datasetOwner: String,
  val algo: Int,
)

object TrackSaveForeverService {
  // Guardrail for mobile heap pressure while building encrypted payload + ANS-104.
  private const val MAX_AUDIO_BYTES = 50 * 1024 * 1024

  private data class SaveForeverUploadRef(
    val ref: String,
    val gatewayUrl: String,
  )

  suspend fun saveForever(
    context: Context,
    ownerEthAddress: String,
    track: MusicTrack,
  ): SaveForeverResult = withContext(Dispatchers.IO) {
    val owner = ownerEthAddress.trim().lowercase()
    val trackId = TrackIds.computeMetaTrackId(track.title, track.artist, track.album).lowercase()
    val computedContentId = ContentIds.computeContentId(trackId, owner).lowercase()
    val contentId = normalizeContentId(track.contentId) ?: computedContentId

    val encryptedBlob = readAndEncryptLocalAudio(context, track, contentId) ?: run {
      val pieceCid = track.pieceCid?.trim().orEmpty()
      if (pieceCid.isBlank()) {
        throw IllegalStateException("Track source is unavailable for Save Forever.")
      }
      UploadedTrackActions.fetchResolvePayload(pieceCid)
    }

    require(encryptedBlob.size <= MAX_AUDIO_BYTES) {
      "Track exceeds the mobile Save Forever limit (50 MB)."
    }

    val filename = buildEncryptedFilename(track)
    val upload =
      runCatching {
        val arweave = ArweaveUploadApi.uploadEncryptedAudio(
          context = context,
          ownerEthAddress = owner,
          encryptedBlob = encryptedBlob,
          filename = filename,
          contentId = contentId,
          trackId = trackId,
          algo = ContentCryptoConfig.ALGO_AES_GCM_256,
        )
        SaveForeverUploadRef(ref = arweave.arRef, gatewayUrl = arweave.gatewayUrl)
      }.getOrElse { err ->
        if (!isLocalFilebaseTestPathEnabled()) throw err
        uploadEncryptedAudioViaApiCore(
          ownerEthAddress = owner,
          encryptedBlob = encryptedBlob,
          filename = filename,
          contentId = contentId,
          trackId = trackId,
          algo = ContentCryptoConfig.ALGO_AES_GCM_256,
        )
      }

    SaveForeverResult(
      trackId = trackId,
      contentId = contentId,
      permanentRef = upload.ref,
      permanentGatewayUrl = upload.gatewayUrl,
      permanentSavedAtMs = System.currentTimeMillis(),
      datasetOwner = owner,
      algo = ContentCryptoConfig.ALGO_AES_GCM_256,
    )
  }

  private fun readAndEncryptLocalAudio(
    context: Context,
    track: MusicTrack,
    contentId: String,
  ): ByteArray? {
    val uri = runCatching { Uri.parse(track.uri) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase().orEmpty()
    if (scheme != "content" && scheme != "file") return null

    val payload = runCatching { readAllBytesFromContentUri(context, uri) }.getOrNull() ?: return null
    if (payload.isEmpty()) return null

    val contentKey = ContentKeyManager.getOrCreate(context)
    val encrypted = EciesContentCrypto.encryptFile(payload)
    val wrappedKey = EciesContentCrypto.eciesEncrypt(contentKey.publicKey, encrypted.rawKey)
    val blob = encrypted.iv + encrypted.ciphertext
    encrypted.rawKey.fill(0)

    ContentKeyManager.saveWrappedKey(context, contentId, wrappedKey)
    return blob
  }

  private fun buildEncryptedFilename(track: MusicTrack): String {
    val ext = track.filename.substringAfterLast('.', "").lowercase().takeIf { it.isNotBlank() } ?: "bin"
    val slug = track.title.lowercase().trim().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    val base = slug.ifBlank { "track" }
    return "${base}.${ext}.enc"
  }

  private fun normalizeContentId(raw: String?): String? {
    val clean = raw?.trim()?.lowercase().orEmpty().removePrefix("0x")
    if (clean.isBlank()) return null
    return "0x$clean"
  }

  private fun readAllBytesFromContentUri(context: Context, uri: Uri): ByteArray {
    context.contentResolver.openInputStream(uri)?.use { input ->
      val out = ByteArrayOutputStream()
      val buf = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(buf)
        if (read <= 0) break
        out.write(buf, 0, read)
      }
      return out.toByteArray()
    }
    throw IllegalStateException("Unable to open local audio URI: $uri")
  }

  fun isLocalFilebaseTestPathEnabled(): Boolean {
    if (BuildConfig.DEBUG) return true
    val api = SongPublishService.API_CORE_URL.trim().lowercase()
    return api.startsWith("http://127.0.0.1:") ||
      api.startsWith("http://10.0.2.2:") ||
      api.startsWith("http://localhost:")
  }

  private fun uploadEncryptedAudioViaApiCore(
    ownerEthAddress: String,
    encryptedBlob: ByteArray,
    filename: String,
    contentId: String,
    trackId: String,
    algo: Int,
  ): SaveForeverUploadRef {
    val boundary = "----PirateSaveForever${System.currentTimeMillis()}"
    val uploadUrl = URL("${SongPublishService.API_CORE_URL.trim().trimEnd('/')}/api/storage/upload")
    val conn =
      (uploadUrl.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 120_000
        readTimeout = 120_000
        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        setRequestProperty("X-User-Address", ownerEthAddress.trim().lowercase())
      }

    conn.outputStream.use { out ->
      out.write("--$boundary\r\n".toByteArray())
      out.write("Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n".toByteArray())
      out.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
      out.write(encryptedBlob)
      out.write("\r\n".toByteArray())

      out.write("--$boundary\r\n".toByteArray())
      out.write("Content-Disposition: form-data; name=\"contentType\"\r\n\r\n".toByteArray())
      out.write("application/octet-stream".toByteArray(Charsets.UTF_8))
      out.write("\r\n".toByteArray())

      val tagsJson =
        """
          [
            {"key":"App-Name","value":"heaven"},
            {"key":"Data-Type","value":"track-audio"},
            {"key":"Upload-Source","value":"heaven-android-local-filebase"},
            {"key":"Content-Id","value":"${contentId.trim().lowercase()}"},
            {"key":"Track-Id","value":"${trackId.trim().lowercase()}"},
            {"key":"Algo","value":"$algo"}
          ]
        """.trimIndent()
      out.write("--$boundary\r\n".toByteArray())
      out.write("Content-Disposition: form-data; name=\"tags\"\r\n\r\n".toByteArray())
      out.write(tagsJson.toByteArray(Charsets.UTF_8))
      out.write("\r\n".toByteArray())

      out.write("--$boundary--\r\n".toByteArray())
    }

    val status = conn.responseCode
    val raw =
      (if (status in 200..299) conn.inputStream else conn.errorStream)
        ?.bufferedReader()
        ?.use { it.readText() }
        .orEmpty()
    val json = runCatching { JSONObject(raw) }.getOrNull()
    if (status !in 200..299) {
      val details = json?.optString("error", "").orEmpty().ifBlank { raw.ifBlank { "HTTP $status" } }
      throw IllegalStateException("Save Forever upload failed via api-core: $details")
    }

    val ref = json?.optString("ref", "").orEmpty().trim().ifBlank {
      val cid = json?.optString("cid", "").orEmpty().trim()
      if (cid.isBlank()) "" else "ipfs://$cid"
    }
    if (ref.isBlank()) {
      throw IllegalStateException("Save Forever upload succeeded via api-core but no ref was returned")
    }
    val gatewayUrl =
      json?.optString("gatewayUrl", "")
        .orEmpty()
        .trim()
        .ifBlank { "${BuildConfig.IPFS_GATEWAY_URL.trim().trimEnd('/')}/${ref.removePrefix("ipfs://")}" }
    return SaveForeverUploadRef(ref = ref, gatewayUrl = gatewayUrl)
  }
}
