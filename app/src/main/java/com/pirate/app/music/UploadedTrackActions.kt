package com.pirate.app.music

import android.content.Context
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.pirate.app.arweave.Ans104DataItem
import com.pirate.app.profile.TempoNameRecordsApi
import com.pirate.app.security.LocalSecp256k1Store
import com.pirate.app.tempo.ContentKeyManager
import com.pirate.app.tempo.EciesContentCrypto
import com.pirate.app.tempo.P256Utils
import com.pirate.app.tempo.SessionKeyManager
import com.pirate.app.tempo.TempoPasskeyManager
import com.pirate.app.tempo.TempoSessionKeyApi
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class UploadedTrackDownloadResult(
  val success: Boolean,
  val alreadyDownloaded: Boolean = false,
  val mediaUri: String? = null,
  val error: String? = null,
)

data class UploadedTrackShareResult(
  val success: Boolean,
  val recipientAddress: String? = null,
  val envelopeId: String? = null,
  val grantTxHash: String? = null,
  val error: String? = null,
)

object UploadedTrackActions {
  suspend fun downloadUploadedTrackToDevice(
    context: Context,
    track: MusicTrack,
    ownerAddress: String? = null,
    granteeAddress: String? = null,
  ): UploadedTrackDownloadResult = withContext(Dispatchers.IO) {
    val contentId = track.contentId?.trim()?.lowercase().orEmpty()
    if (contentId.isEmpty()) {
      return@withContext UploadedTrackDownloadResult(success = false, error = "Track has no contentId.")
    }
    val pieceCid = track.pieceCid?.trim().orEmpty()
    if (pieceCid.isEmpty()) {
      return@withContext UploadedTrackDownloadResult(success = false, error = "Track has no pieceCid.")
    }
    if (isUnsupportedUploadedTrackAlgo(track.algo)) {
      return@withContext UploadedTrackDownloadResult(success = false, error = uploadedTrackUnsupportedUploadError())
    }

    val existing = DownloadedTracksStore.load(context)[contentId]
    if (existing != null && MediaStoreAudioDownloads.uriExists(context, existing.mediaUri)) {
      return@withContext UploadedTrackDownloadResult(
        success = true,
        alreadyDownloaded = true,
        mediaUri = existing.mediaUri,
      )
    }

    val contentKey = ContentKeyManager.load(context)
      ?: return@withContext UploadedTrackDownloadResult(
        success = false,
        error = "No content encryption key found on this device.",
      )

    var wrappedKey = ContentKeyManager.loadWrappedKey(context, contentId)
    if (wrappedKey == null) {
      val owner = ownerAddress?.trim().orEmpty()
      val grantee = granteeAddress?.trim().orEmpty()
      if (owner.isNotBlank() && grantee.isNotBlank()) {
        ensureWrappedKeyFromArweave(
          context = context,
          contentId = contentId,
          ownerAddress = owner,
          granteeAddress = grantee,
        )
        wrappedKey = ContentKeyManager.loadWrappedKey(context, contentId)
      }
    }
    if (wrappedKey == null) {
      return@withContext UploadedTrackDownloadResult(
        success = false,
        error = uploadedTrackMissingWrappedKeyError(),
      )
    }
    val resolvedWrappedKey = wrappedKey

    return@withContext runCatching {
      val blob = fetchUploadedBlob(pieceCid)
      if (blob.size < 13) {
        throw IllegalStateException("Encrypted payload too small (${blob.size} bytes).")
      }
      val iv = blob.copyOfRange(0, 12)
      val ciphertext = blob.copyOfRange(12, blob.size)

      val aesKey = EciesContentCrypto.eciesDecrypt(contentKey.privateKey, resolvedWrappedKey)
      val audio = try {
        EciesContentCrypto.decryptFile(aesKey, iv, ciphertext)
      } finally {
        aesKey.fill(0)
      }

      val ext = uploadedTrackPreferredExtension(track)
      val cacheDir = File(context.cacheDir, "heaven_download_tmp").also { it.mkdirs() }
      val tmp = File.createTempFile("content_", ".$ext", cacheDir)
      tmp.writeBytes(audio)

      val preferredName =
        listOf(track.artist.trim(), track.title.trim())
          .filter { it.isNotBlank() }
          .joinToString(" - ")
          .ifBlank { contentId.removePrefix("0x") }

      val mediaUri =
        MediaStoreAudioDownloads.saveAudio(
          context = context,
          sourceFile = tmp,
          title = track.title,
          artist = track.artist,
          album = track.album,
          mimeType = audioMimeFromExtension(ext),
          preferredName = preferredName,
        )
      runCatching { tmp.delete() }

      val entry =
        DownloadedTrackEntry(
          contentId = contentId,
          mediaUri = mediaUri,
          title = track.title,
          artist = track.artist,
          album = track.album,
          filename = tmp.name,
          mimeType = audioMimeFromExtension(ext),
          pieceCid = pieceCid,
          datasetOwner = track.datasetOwner,
          algo = track.algo,
          coverCid = null,
          downloadedAtMs = System.currentTimeMillis(),
        )
      DownloadedTracksStore.upsert(context, entry)

      UploadedTrackDownloadResult(success = true, mediaUri = mediaUri)
    }.getOrElse { err ->
      UploadedTrackDownloadResult(success = false, error = err.message ?: "Download failed.")
    }
  }

  suspend fun shareUploadedTrack(
    context: Context,
    track: MusicTrack,
    recipient: String,
    ownerAddress: String,
    hostActivity: FragmentActivity? = null,
    tempoAccount: TempoPasskeyManager.PasskeyAccount? = null,
    onStatusMessage: ((String) -> Unit)? = null,
  ): UploadedTrackShareResult = withContext(Dispatchers.IO) {
    val contentId = track.contentId?.trim()?.lowercase().orEmpty()
    val normalizedOwner = ownerAddress.trim().lowercase()
    val normalizedRecipientInput = recipient.trim().removePrefix("@").lowercase()
    Log.d(
      SHARED_WITH_YOU_TAG,
      "share start contentId=$contentId owner=$normalizedOwner recipientInput=$normalizedRecipientInput",
    )
    if (contentId.isEmpty()) {
      return@withContext UploadedTrackShareResult(success = false, error = "Track has no contentId.")
    }
    if (isUnsupportedUploadedTrackAlgo(track.algo)) {
      return@withContext UploadedTrackShareResult(success = false, error = uploadedTrackUnsupportedUploadError())
    }
    val recipientAddress = resolveUploadedTrackRecipientAddress(normalizedRecipientInput)
      ?: return@withContext UploadedTrackShareResult(
        success = false,
        error = "Recipient must be a wallet, .heaven, or .pirate name.",
      )
    if (recipientAddress.equals(ownerAddress, ignoreCase = true)) {
      return@withContext UploadedTrackShareResult(success = false, error = "Cannot share to your own address.")
    }

    val sessionKeyError =
      resolveShareSessionKey(
        context = context,
        owner = normalizedOwner,
        hostActivity = hostActivity,
        tempoAccount = tempoAccount,
        onStatusMessage = onStatusMessage,
      )
    if (sessionKeyError != null) {
      return@withContext UploadedTrackShareResult(
        success = false,
        recipientAddress = recipientAddress,
        error = sessionKeyError,
      )
    }

    val contentKey = ContentKeyManager.load(context)
      ?: return@withContext UploadedTrackShareResult(
        success = false,
        error = "No content encryption key found on this device.",
      )
    var wrappedKey = ContentKeyManager.loadWrappedKey(context, contentId)
    if (wrappedKey == null) {
      ensureWrappedKeyFromArweave(
        context = context,
        contentId = contentId,
        ownerAddress = ownerAddress,
        granteeAddress = ownerAddress,
      )
      wrappedKey = ContentKeyManager.loadWrappedKey(context, contentId)
    }
    if (wrappedKey == null) {
      return@withContext UploadedTrackShareResult(
        success = false,
        error = uploadedTrackMissingWrappedKeyError(),
      )
    }
    val resolvedWrappedKey = wrappedKey

    val recipientPub =
      if (uploadedTrackAddressRegex.matches(normalizedRecipientInput)) {
        TempoNameRecordsApi.getContentPubKeyForAddress(recipientAddress)
      } else {
        TempoNameRecordsApi.getContentPubKeyForName(normalizedRecipientInput)
          ?: TempoNameRecordsApi.getContentPubKeyForAddress(recipientAddress)
      }
      ?: return@withContext UploadedTrackShareResult(
        success = false,
        error = "Recipient has no published contentPubKey. They must set a primary name and upload once to publish encryption keys.",
      )

    return@withContext runCatching {
      val ownerAesKey = EciesContentCrypto.eciesDecrypt(contentKey.privateKey, resolvedWrappedKey)
      val envelope = try {
        EciesContentCrypto.eciesEncrypt(recipientPub, ownerAesKey)
      } finally {
        ownerAesKey.fill(0)
      }

      val payloadJson =
        JSONObject()
          .put("version", 1)
          .put("contentId", contentId)
          .put("owner", ownerAddress.trim().lowercase())
          .put("grantee", recipientAddress)
          .put("algo", track.algo ?: ContentCryptoConfig.ALGO_AES_GCM_256)
          .put("ephemeralPub", P256Utils.bytesToHex(envelope.ephemeralPub))
          .put("iv", P256Utils.bytesToHex(envelope.iv))
          .put("ciphertext", P256Utils.bytesToHex(envelope.ciphertext))
      val payload = payloadJson.toString().toByteArray(Charsets.UTF_8)

      val signed = run {
        val identity = LocalSecp256k1Store.getOrCreateIdentity(context, ownerAddress)
        Ans104DataItem.buildAndSign(
          payload = payload,
          tags =
            listOf(
              Ans104DataItem.Tag(name = "Content-Type", value = "application/json"),
              Ans104DataItem.Tag(name = "App-Name", value = "Pirate"),
              Ans104DataItem.Tag(name = "Data-Type", value = "content-key-envelope"),
              Ans104DataItem.Tag(name = "Content-Id", value = contentId),
              Ans104DataItem.Tag(name = "Owner", value = ownerAddress.trim().lowercase()),
              Ans104DataItem.Tag(name = "Grantee", value = recipientAddress),
              Ans104DataItem.Tag(name = "Upload-Source", value = "heaven-android"),
            ),
          signingKeyPair = identity.keyPair,
        )
      }
      val envelopeId =
        runCatching { Ans104DataItem.uploadSignedDataItem(signed.bytes).trim() }
          .getOrElse { arweaveError ->
            Log.w(
              SHARED_WITH_YOU_TAG,
              "share envelope upload arweave failed; falling back to api-core filebase contentId=$contentId owner=${ownerAddress.trim().lowercase()} grantee=$recipientAddress",
              arweaveError,
            )
            uploadShareEnvelopeViaApiCore(
              ownerAddress = ownerAddress,
              granteeAddress = recipientAddress,
              contentId = contentId,
              envelopePayload = payloadJson,
            ).trim()
          }
      if (envelopeId.isEmpty()) throw IllegalStateException("Envelope upload returned empty ref.")

      val grantTxHash =
        ensureUploadedTrackGrantOnChainInternal(
          context = context,
          track = track,
          ownerAddress = ownerAddress,
          granteeAddress = recipientAddress,
        )

      UploadedTrackShareResult(
        success = true,
        recipientAddress = recipientAddress,
        envelopeId = envelopeId,
        grantTxHash = grantTxHash,
      )
    }.getOrElse { err ->
      Log.w(
        SHARED_WITH_YOU_TAG,
        "share failed contentId=$contentId owner=$normalizedOwner recipient=$recipientAddress error=${err.message}",
        err,
      )
      UploadedTrackShareResult(
        success = false,
        recipientAddress = recipientAddress,
        error = err.message ?: "Share failed.",
      )
    }
  }

  private suspend fun resolveShareSessionKey(
    context: Context,
    owner: String,
    hostActivity: FragmentActivity?,
    tempoAccount: TempoPasskeyManager.PasskeyAccount?,
    onStatusMessage: ((String) -> Unit)?,
  ): String? {
    val loaded = SessionKeyManager.load(context)
    val loadedValidForOwner =
      loaded != null &&
        SessionKeyManager.isValid(loaded, ownerAddress = owner) &&
        loaded.keyAuthorization?.isNotEmpty() == true
    if (loadedValidForOwner) return null

    val storedOwner = loaded?.ownerAddress?.trim()?.lowercase().orEmpty()
    if (storedOwner.isNotBlank() && storedOwner != owner) {
      Log.w(
        SHARED_WITH_YOU_TAG,
        "share session key owner mismatch; clearing stale key storedOwner=$storedOwner expectedOwner=$owner",
      )
      SessionKeyManager.clear(context)
    }

    val activity = hostActivity ?: (context as? FragmentActivity)
    val account = tempoAccount
    if (activity == null || account == null) {
      return if (storedOwner.isNotBlank() && storedOwner != owner) {
        "Session key belonged to another account. Please sign in again to re-authorize sharing."
      } else {
        "Session key unavailable. Please sign in again to authorize sharing."
      }
    }
    if (!account.address.equals(owner, ignoreCase = true)) {
      Log.w(
        SHARED_WITH_YOU_TAG,
        "share session key account mismatch account=${account.address.lowercase()} owner=$owner",
      )
      return "Signed-in account does not match this track owner. Switch account and try again."
    }

    onStatusMessage?.invoke("Authorizing Tempo session key...")
    val auth = TempoSessionKeyApi.authorizeSessionKey(activity = activity, account = account)
    val authorized =
      auth.sessionKey?.takeIf {
        auth.success &&
          SessionKeyManager.isValid(it, ownerAddress = owner) &&
          it.keyAuthorization?.isNotEmpty() == true
      }
    if (authorized != null) {
      onStatusMessage?.invoke("Tempo session key authorized.")
      return null
    }
    return auth.error ?: "Session key authorization failed. Please sign in again."
  }

  internal suspend fun ensureWrappedKeyFromArweave(
    context: Context,
    contentId: String,
    ownerAddress: String,
    granteeAddress: String,
  ): Boolean = ensureWrappedKeyFromArweaveInternal(
    context = context,
    contentId = contentId,
    ownerAddress = ownerAddress,
    granteeAddress = granteeAddress,
  )

  internal fun fetchResolvePayload(dataitemId: String): ByteArray {
    return fetchUploadedResolvePayload(dataitemId)
  }

}
