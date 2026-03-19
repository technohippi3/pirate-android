package sc.pirate.app.music

import android.content.Context
import android.net.Uri
import android.util.Log
import sc.pirate.app.tempo.ContentKeyManager
import sc.pirate.app.tempo.EciesContentCrypto
import sc.pirate.app.util.shortAddress
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class CachedSharedAudio(
  val file: File,
  val uri: String,
  val filename: String,
  val mimeType: String?,
)

internal fun resolveCanonicalTrackIdForMutation(track: MusicTrack): CanonicalTrackId? {
  val fromCanonicalTrackId = CanonicalTrackId.parse(track.canonicalTrackId)
  val fromTrackField = CanonicalTrackId.parse(track.id)
  val fromLabeledContentField = CanonicalTrackId.parse(extractLabeledTrackId(track.contentId.orEmpty()))
  return fromCanonicalTrackId ?: fromTrackField ?: fromLabeledContentField
}

internal fun resolveSongTrackId(track: MusicTrack): String? {
  val fromCanonicalTrackId = CanonicalTrackId.parse(track.canonicalTrackId)?.value
  val fromTrackField = CanonicalTrackId.parse(track.id)?.value
  val fromLabeledContentField = CanonicalTrackId.parse(extractLabeledTrackId(track.contentId.orEmpty()))?.value
  val fromMetadata = deriveMetaTrackId(track)
  val resolved = fromCanonicalTrackId ?: fromTrackField ?: fromLabeledContentField ?: fromMetadata
  val source =
    when {
      fromCanonicalTrackId != null -> "track.canonicalTrackId"
      fromTrackField != null -> "track.id"
      fromLabeledContentField != null -> "contentId(trackId label)"
      fromMetadata != null -> "metadata(title+artist+album)"
      else -> "none"
    }
  Log.d(
    "SongTrackIdDebug",
    "resolveSongTrackId raw candidates: canonicalTrackId='${track.canonicalTrackId}' track.id='${track.id}' contentId='${track.contentId}' title='${track.title}' artist='${track.artist}' album='${track.album}'",
  )
  Log.d("SongTrackIdDebug", "resolveSongTrackId resolved='${resolved ?: "<null>"}' source='$source'")
  return resolved
}

private val LABELED_TRACK_ID_SUBSTRING =
  Regex("(?i)(?:track\\s*id|track_id|mbid)\\s*[:=]\\s*(0x[a-f0-9]{64}|[a-f0-9]{64})")

private fun extractLabeledTrackId(raw: String): String? {
  val trimmed = raw.trim()
  val hit = LABELED_TRACK_ID_SUBSTRING.find(trimmed) ?: return null
  val value = hit.groupValues.getOrNull(1)?.lowercase().orEmpty()
  if (value.isBlank()) return null
  return if (value.startsWith("0x")) value else "0x$value"
}

private fun deriveMetaTrackId(track: MusicTrack): String? {
  val title = track.title.trim()
  val artist = track.artist.trim()
  if (title.isBlank() || artist.isBlank()) return null
  return runCatching {
    TrackIds.computeMetaTrackId(
      title = title,
      artist = artist,
      album = track.album,
    ).lowercase()
  }.getOrNull()
}

internal fun sharedOwnerLabel(
  ownerAddress: String,
  sharedOwnerLabels: Map<String, String>,
): String {
  val key = ownerAddress.trim().lowercase()
  if (key.isBlank()) return "unknown"
  return sharedOwnerLabels[key] ?: shortAddress(key, minLengthToShorten = 10)
}

internal fun buildSharedTrackForPlayer(
  track: SharedCloudTrack,
  uri: String,
  filename: String,
): MusicTrack {
  val coverUri = CoverRef.resolveCoverUrl(track.coverCid, width = 192, height = 192, format = "webp", quality = 80)
  return MusicTrack(
    id = track.contentId.ifBlank { track.trackId },
    canonicalTrackId = track.trackId,
    title = track.title,
    artist = track.artist,
    album = track.album,
    durationSec = track.durationSec,
    uri = uri,
    filename = filename,
    artworkUri = coverUri,
    contentId = track.contentId,
    pieceCid = track.pieceCid,
    datasetOwner = track.datasetOwner,
    algo = track.algo,
    lyricsRef = track.lyricsRef,
  )
}

internal suspend fun findCachedSharedAudio(
  context: Context,
  contentId: String,
): CachedSharedAudio? = withContext(Dispatchers.IO) {
  val safe = contentId.removePrefix("0x").trim().lowercase()
  if (safe.isBlank()) return@withContext null

  val dir = File(context.cacheDir, "heaven_cloud")
  if (!dir.exists()) return@withContext null

  val existing =
    dir.listFiles()
      ?.firstOrNull { f -> f.isFile && f.name.startsWith("content_${safe}.") && f.length() > 0L }
      ?: return@withContext null

  CachedSharedAudio(
    file = existing,
    uri = Uri.fromFile(existing).toString(),
    filename = existing.name,
    mimeType = audioMimeFromExtension(existing.extension),
  )
}

internal suspend fun decryptSharedAudioToCache(
  context: Context,
  ownerEthAddress: String?,
  track: SharedCloudTrack,
): CachedSharedAudio = withContext(Dispatchers.IO) {
  if (track.algo != ContentCryptoConfig.ALGO_AES_GCM_256) {
    throw IllegalStateException("Plaintext shared track is not supported. Ask owner to re-upload encrypted.")
  }
  val grantee = ownerEthAddress?.trim()?.lowercase().orEmpty()
  if (grantee.isBlank()) {
    throw IllegalStateException("Missing active wallet for shared-track decrypt.")
  }

  val contentKey = ContentKeyManager.load(context)
    ?: throw IllegalStateException("No content encryption key — upload a track first to generate one")

  var wrappedKey = ContentKeyManager.loadWrappedKey(context, track.contentId)
  if (wrappedKey == null) {
    UploadedTrackActions.ensureWrappedKeyFromArweave(
      context = context,
      contentId = track.contentId,
      ownerAddress = track.owner,
      granteeAddress = grantee,
    )
    wrappedKey = ContentKeyManager.loadWrappedKey(context, track.contentId)
  }
  val resolvedWrappedKey =
    wrappedKey
      ?: throw IllegalStateException(
        "Missing encrypted key for this shared track. It may not be shared to this wallet yet.",
      )

  val blob = UploadedTrackActions.fetchResolvePayload(track.pieceCid)
  if (blob.size < 13) throw IllegalStateException("Encrypted blob too small: ${blob.size} bytes")
  val iv = blob.copyOfRange(0, 12)
  val ciphertext = blob.copyOfRange(12, blob.size)

  val aesKey = EciesContentCrypto.eciesDecrypt(contentKey.privateKey, resolvedWrappedKey)
  val audio = EciesContentCrypto.decryptFile(aesKey, iv, ciphertext)
  aesKey.fill(0)

  val dir = File(context.cacheDir, "heaven_cloud").also { it.mkdirs() }
  val safe = track.contentId.removePrefix("0x").trim().lowercase()
  val ext = if (track.title.contains(".")) track.title.substringAfterLast('.') else "mp3"
  val cacheFile = File(dir, "content_${safe}.${ext}")
  cacheFile.writeBytes(audio)

  CachedSharedAudio(
    file = cacheFile,
    uri = Uri.fromFile(cacheFile).toString(),
    filename = cacheFile.name,
    mimeType = audioMimeFromExtension(ext),
  )
}
