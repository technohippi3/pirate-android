package sc.pirate.app.music

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.fragment.app.FragmentActivity
import sc.pirate.app.BuildConfig
import sc.pirate.app.player.PlayerLyricsRepository
import sc.pirate.app.player.PlayerPresentationRepository
import sc.pirate.app.tempo.SessionKeyManager
import sc.pirate.app.tempo.TempoPasskeyManager
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.UUID
import kotlin.math.ceil

/**
 * Song publish flow via api-core:
 * 1) Stage audio upload to private R2 via api-core
 * 2) Stage supporting artifacts (cover + lyrics + instrumental/vocals stems)
 * 3) Run preflight checks
 * 4) Upload Story metadata JSON (IP + NFT metadata)
 * 5) Register on Story
 * 6) Finalize on Tempo and persist purchase asset metadata
 */
object SongPublishService {

  private const val TAG = "SongPublish"
  val API_CORE_URL: String
    get() {
      val configured = BuildConfig.API_CORE_URL.trim().trimEnd('/')
      if (configured.startsWith("https://") || configured.startsWith("http://")) {
        return configured
      }
      throw IllegalStateException("API_CORE_URL is missing or invalid")
    }
  private const val MAX_AUDIO_BYTES = 50 * 1024 * 1024 // Matches backend /api/music/publish/start limit.
  private const val PREFLIGHT_MAX_RETRIES = 3
  private const val PREFLIGHT_RETRY_DELAY_MS = 2_000L
  private const val REGISTER_CONFIRM_MAX_RETRIES = 90
  private const val REGISTER_CONFIRM_RETRY_DELAY_MS = 2_000L
  private const val RECENT_COVERS_DIR = "recent_release_covers"
  private const val MAX_COVER_BYTES = 10 * 1024 * 1024
  private const val MAX_CANVAS_BYTES = 20 * 1024 * 1024
  private const val MAX_LYRICS_BYTES = 256 * 1024
  private const val MAX_PREVIEW_CLIP_BYTES = 20 * 1024 * 1024
  private const val PURCHASE_TOKEN_DECIMALS = 6
  private const val TRACK_PRESENTATION_DELEGATE_PERMISSIONS = 3
  private const val TRACK_PRESENTATION_DELEGATE_TTL_SECONDS = 14L * 24L * 60L * 60L
  private const val LYRICS_READY_POLL_MAX_RETRIES = 8
  private const val LYRICS_READY_POLL_DELAY_MS = 3_000L
  private const val DONATION_TARGET_CHAIN_ID = 84532
  private const val DONATION_TEST_RECIPIENT = "0x39839FB90820846e020EAdBFA9af626163274e30"

  data class FeaturedDonationOrg(
    val slug: String,
    val name: String,
    val orgId: String,
    val destinationChainId: Int,
    val destinationRecipient: String,
  )

  val FEATURED_DONATION_ORGS = listOf(
    FeaturedDonationOrg(
      slug = "internet-archive",
      name = "Internet Archive",
      orgId = "dd7abaa8-82a1-4b1f-ae05-b54c62cee707",
      destinationChainId = DONATION_TARGET_CHAIN_ID,
      destinationRecipient = DONATION_TEST_RECIPIENT,
    ),
    FeaturedDonationOrg(
      slug = "doctors-without-borders",
      name = "Doctors Without Borders",
      orgId = "04a7653e-5be6-480e-8f4b-cb92f72ccf22",
      destinationChainId = DONATION_TARGET_CHAIN_ID,
      destinationRecipient = DONATION_TEST_RECIPIENT,
    ),
    FeaturedDonationOrg(
      slug = "watsi",
      name = "WATSI",
      orgId = "663170d4-6242-45cd-8b37-aa187bbaf495",
      destinationChainId = DONATION_TARGET_CHAIN_ID,
      destinationRecipient = DONATION_TEST_RECIPIENT,
    ),
  )

  data class SongFormData(
    val title: String = "",
    val artist: String = "",
    val genre: String = "pop",
    val primaryLanguage: String = "en",
    val secondaryLanguage: String = "",
    val lyrics: String = "",
    val coverUri: Uri? = null,
    val audioUri: Uri? = null,
    val vocalsUri: Uri? = null,
    val instrumentalUri: Uri? = null,
    val canvasUri: Uri? = null,
    val license: String = "non-commercial", // "non-commercial" | "commercial-use" | "commercial-remix"
    val revShare: Int = 10,
    val donationEnabled: Boolean = false,
    val donationOrgId: String = "",
    val donationOrgName: String = "",
    val donationRecipient: String = "",
    val donationChainId: Int = DONATION_TARGET_CHAIN_ID,
    val donationSharePercent: Int = 10,
    val donationCompliant: Boolean = false,
    val attestation: Boolean = false,
    val purchasePriceUsd: String = "1",
    val openEdition: Boolean = true,
    val maxSupply: String = "",
    val previewStartSec: Float = 0f,
    val previewEndSec: Float = 30f,
    val trackDurationSec: Float = 0f,
  )

  data class PublishResult(
    val jobId: String,
    val status: String,
    val stagedAudioId: String?,
    val stagedAudioUrl: String?,
    val audioSha256: String,
    val coverCid: String? = null,
    val reusedExistingRelease: Boolean = false,
  )

  fun buildDonationPolicy(formData: SongFormData): JSONObject? {
    if (!formData.donationEnabled) return null
    val orgId = formData.donationOrgId.trim()
    val orgName = formData.donationOrgName.trim()
    val recipient = formData.donationRecipient.trim()
    if (
      orgId.isBlank() ||
      orgName.isBlank() ||
      !formData.donationCompliant ||
      formData.donationChainId != DONATION_TARGET_CHAIN_ID ||
      !recipient.matches(Regex("^0x[a-fA-F0-9]{40}$")) ||
      formData.donationSharePercent !in 1..50
    ) {
      return null
    }
    return JSONObject().apply {
      put("provider", "endaoment")
      put("orgId", orgId)
      put("orgName", orgName)
      put("isCompliant", true)
      put("destinationChainId", formData.donationChainId)
      put("destinationToken", "USDC")
      put("destinationRecipient", recipient)
      put("sharePercent", formData.donationSharePercent)
    }
  }

  suspend fun publish(
    context: Context,
    formData: SongFormData,
    ownerAddress: String,
    hostActivity: FragmentActivity?,
    tempoAccount: TempoPasskeyManager.PasskeyAccount?,
    onProgress: (Int) -> Unit,
  ): PublishResult {
    val userAddress = songPublishNormalizeUserAddress(ownerAddress)
    if (formData.title.isBlank()) throw IllegalStateException("Song title is required")
    if (formData.artist.isBlank()) throw IllegalStateException("Artist is required")
    if (formData.lyrics.isBlank()) throw IllegalStateException("Lyrics are required")
    val vocalsUri = formData.vocalsUri ?: throw IllegalStateException("Vocals stem is required")
    val instrumentalUri = formData.instrumentalUri ?: throw IllegalStateException("Instrumental stem is required")
    val purchasePriceUnits = parsePurchasePriceUnits(formData.purchasePriceUsd)
    val maxSupply = parseMaxSupply(formData.openEdition, formData.maxSupply)

    onProgress(2)

    val audioUri = formData.audioUri ?: throw IllegalStateException("Audio file is required")
    val coverUri = formData.coverUri ?: throw IllegalStateException("Cover image is required")

    val audioBytes = songPublishReadUriWithMaxBytes(context, audioUri, MAX_AUDIO_BYTES)
    val coverBytes = songPublishReadCoverUriWithMaxBytes(context, coverUri, MAX_COVER_BYTES)
    val instrumentalUpload = run {
      val bytes = songPublishReadUriWithMaxBytes(context, instrumentalUri, MAX_AUDIO_BYTES)
      val mime = songPublishGetMimeType(context, instrumentalUri)
      val fileName = songPublishGetFileName(context, instrumentalUri)
      if (!songPublishIsLikelyAudioContent(mime, fileName)) {
        throw IllegalStateException("Instrumental stem must be an audio file")
      }
      val normalizedMime = if (mime.startsWith("audio/")) mime else "audio/mpeg"
      bytes to normalizedMime
    }
    val vocalsUpload = run {
      val bytes = songPublishReadUriWithMaxBytes(context, vocalsUri, MAX_AUDIO_BYTES)
      val mime = songPublishGetMimeType(context, vocalsUri)
      val fileName = songPublishGetFileName(context, vocalsUri)
      if (!songPublishIsLikelyAudioContent(mime, fileName)) {
        throw IllegalStateException("Vocals stem must be an audio file")
      }
      val normalizedMime = if (mime.startsWith("audio/")) mime else "audio/mpeg"
      bytes to normalizedMime
    }

    val detectedAudioMime = songPublishGetMimeType(context, audioUri)
    val detectedAudioFileName = songPublishGetFileName(context, audioUri)
    if (!songPublishIsLikelyMp3(detectedAudioMime, detectedAudioFileName)) {
      throw IllegalStateException("Only MP3 uploads are supported right now")
    }
    val audioMime = "audio/mpeg"
    val detectedCoverMime = songPublishGetMimeType(context, coverUri)
    val coverMime = if (detectedCoverMime.startsWith("image/")) detectedCoverMime else "image/jpeg"
    val canvasUpload = formData.canvasUri?.let { canvasUri ->
      val bytes = songPublishReadCanvasUriWithMaxBytes(context, canvasUri, MAX_CANVAS_BYTES)
      val mime = songPublishGetMimeType(context, canvasUri)
      val fileName = songPublishGetFileName(context, canvasUri)
      if (!songPublishIsLikelyVideoContent(mime, fileName)) {
        throw IllegalStateException("Canvas must be a video file")
      }
      val normalizedMime = if (mime.startsWith("video/")) mime else "video/mp4"
      bytes to normalizedMime
    }
    val audioSha256 = songPublishSha256Hex(audioBytes)
    val rawTrackDurationSec =
      formData.trackDurationSec.takeIf { it > 0f } ?: songPublishGetAudioDurationSec(context, audioUri)
    if (rawTrackDurationSec <= 0f) {
      throw IllegalStateException("Could not determine track duration")
    }
    val durationSec = ceil(rawTrackDurationSec.toDouble()).toInt().coerceAtLeast(1)
    val previewWindow =
      songPublishNormalizePreviewWindow(
        rawStartSec = formData.previewStartSec,
        rawEndSec = formData.previewEndSec,
        rawDurationSec = durationSec.toFloat(),
      )
    val previewStartSec = previewWindow.startSec.toInt()
    val previewEndSec = previewWindow.endSec.toInt().coerceIn(previewStartSec + 1, durationSec)
    if (previewEndSec - previewStartSec > 30) {
      throw IllegalStateException("Preview clip cannot exceed 30 seconds")
    }
    val lyricsBytes = formData.lyrics.toByteArray(Charsets.UTF_8)
    if (lyricsBytes.size > MAX_LYRICS_BYTES) {
      throw IllegalStateException("Lyrics exceed 256KB limit (${lyricsBytes.size} bytes)")
    }
    val previewClipBytes =
      songPublishBuildPreviewClip(
        context = context,
        sourceAudioUri = audioUri,
        previewStartSec = previewStartSec,
        previewEndSec = previewEndSec,
        maxBytes = MAX_PREVIEW_CLIP_BYTES,
      )

    onProgress(15)

    val idempotencyKey = "music-android-${UUID.randomUUID()}"
    val startResponse = songPublishStageAudioForMusicPublish(
      audioBytes = audioBytes,
      audioMime = audioMime,
      audioSha256 = audioSha256,
      durationSec = durationSec,
      userAddress = userAddress,
      idempotencyKey = idempotencyKey,
    )
    if (startResponse.status !in 200..299) {
      throw IllegalStateException(songPublishErrorMessageFromApi("Audio staging upload", startResponse))
    }

    val startJob = songPublishRequireJobObject("Audio staging upload", startResponse)
    val jobId = startJob.optString("jobId", "").trim()
    if (jobId.isBlank()) throw IllegalStateException("Audio staging upload failed: missing jobId")
    val startStatus = startJob.optString("status", "").trim()
    val startExisting = startResponse.json?.optBoolean("existing", false) == true
    val resumeFromRegistration = startStatus == "registering" || startStatus == "registered"
    val reusedExistingRegistration = startExisting && startStatus == "registered"
    Log.i(TAG, "start jobId=$jobId status=$startStatus existing=$startExisting resumeFromRegistration=$resumeFromRegistration")

    var stagedCoverGatewayUrl = songPublishExtractStagedCoverGatewayUrl(startJob)
    var stagedCoverDataitemId = songPublishExtractStagedCoverDataitemId(startJob)
    var stagedInstrumentalDataitemId = songPublishExtractStagedInstrumentalDataitemId(startJob)
    var stagedVocalsDataitemId = songPublishExtractStagedVocalsDataitemId(startJob)
    var stagedAudioId = songPublishExtractDataitemId(startJob)
    var stagedAudioUrl = startJob
      .optJSONObject("upload")
      ?.optString("stagedGatewayUrl", "")
      ?.trim()
      ?.ifBlank { null }

    if (resumeFromRegistration) {
      if (stagedInstrumentalDataitemId == null) {
        throw IllegalStateException("Cannot resume publish: selected instrumental stem is missing from staged artifacts. Restart publish.")
      }
      if (stagedVocalsDataitemId == null) {
        throw IllegalStateException("Cannot resume publish: selected vocals stem is missing from staged artifacts. Restart publish.")
      }
    }

    onProgress(35)

    val signingActivity = hostActivity ?: throw IllegalStateException("Host activity is required for Tempo publish signing")
    val account = tempoAccount ?: throw IllegalStateException("Tempo passkey account is required for Tempo publish signing")
    if (!account.address.equals(userAddress, ignoreCase = true)) {
      throw IllegalStateException("Active passkey account does not match publish owner")
    }
    val publishSessionKey = songPublishEnsureAuthorizedSessionKey(
      context = context,
      activity = signingActivity,
      account = account,
    )

    if (!resumeFromRegistration) {
      val artifactsResponse = songPublishStageArtifactsForMusicPublish(
        jobId = jobId,
        userAddress = userAddress,
        coverBytes = coverBytes,
        coverMime = coverMime,
        lyricsText = formData.lyrics,
        previewClipBytes = previewClipBytes,
        previewClipMime = "audio/mp4",
        previewStartSec = previewStartSec,
        previewEndSec = previewEndSec,
        canvasBytes = canvasUpload?.first,
        canvasMime = canvasUpload?.second,
        instrumentalBytes = instrumentalUpload.first,
        instrumentalMime = instrumentalUpload.second,
        vocalsBytes = vocalsUpload.first,
        vocalsMime = vocalsUpload.second,
      )
      if (artifactsResponse.status !in 200..299) {
        if (artifactsResponse.status == 404) {
          throw IllegalStateException(
            "Artifact staging endpoint not found. Backend is outdated; deploy latest api-core.",
          )
        }
        throw IllegalStateException(songPublishErrorMessageFromApi("Artifact staging", artifactsResponse))
      }
      val artifactsJob = songPublishRequireJobObject("Artifact staging", artifactsResponse)
      stagedCoverGatewayUrl = songPublishExtractStagedCoverGatewayUrl(artifactsJob)
      stagedCoverDataitemId = songPublishExtractStagedCoverDataitemId(artifactsJob)
      stagedInstrumentalDataitemId = songPublishExtractStagedInstrumentalDataitemId(artifactsJob)
      stagedVocalsDataitemId = songPublishExtractStagedVocalsDataitemId(artifactsJob)
      if (stagedInstrumentalDataitemId == null) {
        throw IllegalStateException("Artifact staging failed: missing staged instrumental stem")
      }
      if (stagedVocalsDataitemId == null) {
        throw IllegalStateException("Artifact staging failed: missing staged vocals stem")
      }

      onProgress(50)

      var preflightResponse: SongPublishApiResponse? = null
      for (attempt in 1..PREFLIGHT_MAX_RETRIES) {
        val preflightBody = JSONObject().apply {
          put("jobId", jobId)
          put("publishType", "original")
          put("fingerprint", "sha256:$audioSha256")
        }
        val response = songPublishPostJsonToMusicApi(
          path = "/api/music/preflight",
          userAddress = userAddress,
          body = preflightBody,
        )
        val policyReasonCode = response.json
          ?.optJSONObject("job")
          ?.optJSONObject("policy")
          ?.optString("reasonCode", "")
          ?.trim()
          .orEmpty()
        val retryable = response.status == 502 && (
          policyReasonCode == "hash_verification_unavailable" ||
            response.json?.optString("error", "")
              ?.lowercase()
              ?.contains("hash verification unavailable") == true
          )
        if (!retryable || attempt == PREFLIGHT_MAX_RETRIES) {
          preflightResponse = response
          break
        }
        delay(PREFLIGHT_RETRY_DELAY_MS)
      }

      val finalPreflight = preflightResponse ?: throw IllegalStateException("Preflight checks failed unexpectedly")
      if (finalPreflight.status !in 200..299) {
        throw IllegalStateException(songPublishErrorMessageFromApi("Preflight checks", finalPreflight))
      }
      val preflightJob = songPublishRequireJobObject("Preflight checks", finalPreflight)
      val preflightStatus = preflightJob.optString("status", "").trim()
      if (preflightStatus != "policy_passed") {
        val reason = preflightJob
          .optJSONObject("policy")
          ?.optString("reason", "")
          ?.trim()
          .orEmpty()
        val reasonCode = preflightJob
          .optJSONObject("policy")
          ?.optString("reasonCode", "")
          ?.trim()
          .orEmpty()
        throw IllegalStateException(
          "Upload policy did not pass (status=$preflightStatus${if (reasonCode.isNotBlank()) ", reasonCode=$reasonCode" else ""}${if (reason.isNotBlank()) ", reason=$reason" else ""})",
        )
      }

      stagedAudioId = songPublishExtractDataitemId(preflightJob)
      stagedAudioUrl = preflightJob
        .optJSONObject("upload")
        ?.optString("stagedGatewayUrl", "")
        ?.trim()
        ?.ifBlank { null }
      if (stagedCoverGatewayUrl.isNullOrBlank()) {
        stagedCoverGatewayUrl = songPublishExtractStagedCoverGatewayUrl(preflightJob)
      }
      if (stagedCoverDataitemId.isNullOrBlank()) {
        stagedCoverDataitemId = songPublishExtractStagedCoverDataitemId(preflightJob)
      }
      if (stagedCoverDataitemId.isNullOrBlank()) {
        throw IllegalStateException("Artifact staging succeeded but staged cover dataitem ID is missing")
      }

      onProgress(70)

      val ipMetadataJson = JSONObject().apply {
        put("jobId", jobId)
        put("publishType", "original")
        put("title", formData.title.trim())
        put("artist", formData.artist.trim())
        put("genre", formData.genre.trim())
        put("primaryLanguage", formData.primaryLanguage.trim())
        if (formData.secondaryLanguage.trim().isNotEmpty()) {
          put("secondaryLanguage", formData.secondaryLanguage.trim())
        }
        put("previewStartSec", previewStartSec)
        put("previewEndSec", previewEndSec)
        put("durationSec", durationSec)
        put("isInstrumentalSong", false)
      }
      val nftMetadataJson = JSONObject().apply {
        put("name", "${formData.title.trim()} - ${formData.artist.trim()}")
        put("description", "Pirate music publish: ${formData.title.trim()} by ${formData.artist.trim()}")
        put("audioSha256", audioSha256)
      }

      val metadataResponse = songPublishUploadMetadataMusicPublish(
        jobId = jobId,
        userAddress = userAddress,
        ipMetadataJson = ipMetadataJson,
        nftMetadataJson = nftMetadataJson,
      )
      if (metadataResponse.status !in 200..299) {
        throw IllegalStateException(songPublishErrorMessageFromApi("Story metadata upload", metadataResponse))
      }

      val metadataJson = metadataResponse.json
        ?: throw IllegalStateException("Story metadata upload failed: missing JSON payload")
      val ipMetadataURI = metadataJson.optString("ipMetadataURI", "").trim()
      val ipMetadataHash = metadataJson.optString("ipMetadataHash", "").trim()
      val nftMetadataURI = metadataJson.optString("nftMetadataURI", "").trim()
      val nftMetadataHash = metadataJson.optString("nftMetadataHash", "").trim()
      if (
        ipMetadataURI.isBlank() ||
        ipMetadataHash.isBlank() ||
        nftMetadataURI.isBlank() ||
        nftMetadataHash.isBlank()
      ) {
        throw IllegalStateException("Story metadata upload failed: missing metadata URI/hash values")
      }
      Log.i(
        TAG,
        "metadata jobId=$jobId ip=${ipMetadataURI.take(48)} nft=${nftMetadataURI.take(48)}",
      )

      var registerResponse = songPublishRegisterMusicPublish(
        jobId = jobId,
        userAddress = userAddress,
        recipient = ownerAddress,
        ipMetadataURI = ipMetadataURI,
        ipMetadataHash = ipMetadataHash,
        nftMetadataURI = nftMetadataURI,
        nftMetadataHash = nftMetadataHash,
        license = formData.license,
        commercialRevShare = formData.revShare,
        donationPolicy = buildDonationPolicy(formData),
        defaultMintingFee = "0",
        sessionKey = publishSessionKey,
      )
      registerResponse = submitPreparedStoryIntentIfNeeded(
        response = registerResponse,
        userAddress = userAddress,
        sessionKey = publishSessionKey,
      ) { operationId, userSig, intent, keyAuthorization ->
        songPublishRegisterMusicPublish(
          jobId = jobId,
          userAddress = userAddress,
          recipient = ownerAddress,
          ipMetadataURI = ipMetadataURI,
          ipMetadataHash = ipMetadataHash,
          nftMetadataURI = nftMetadataURI,
          nftMetadataHash = nftMetadataHash,
          license = formData.license,
          commercialRevShare = formData.revShare,
          donationPolicy = buildDonationPolicy(formData),
          defaultMintingFee = "0",
          sessionKey = publishSessionKey,
          storyIntentOperationId = operationId,
          storyIntentUserSig = userSig,
          storyIntent = intent,
          storyIntentKeyAuthorization = keyAuthorization,
        )
      }
      if (registerResponse.status !in 200..299) {
        throw IllegalStateException(songPublishErrorMessageFromApi("Story register", registerResponse))
      }
    }

    var confirmed = false
    for (attempt in 1..REGISTER_CONFIRM_MAX_RETRIES) {
      var confirmResponse = songPublishConfirmRegisterMusicPublish(
        jobId = jobId,
        userAddress = userAddress,
        sessionKey = publishSessionKey,
      )
      confirmResponse = submitPreparedStoryIntentIfNeeded(
        response = confirmResponse,
        userAddress = userAddress,
        sessionKey = publishSessionKey,
      ) { operationId, userSig, intent, keyAuthorization ->
        songPublishConfirmRegisterMusicPublish(
          jobId = jobId,
          userAddress = userAddress,
          sessionKey = publishSessionKey,
          storyIntentOperationId = operationId,
          storyIntentUserSig = userSig,
          storyIntent = intent,
          storyIntentKeyAuthorization = keyAuthorization,
        )
      }
      if (confirmResponse.status == 202) {
        if (attempt == REGISTER_CONFIRM_MAX_RETRIES) {
          throw IllegalStateException("Story register confirm timed out while waiting for chain confirmation")
        }
        delay(REGISTER_CONFIRM_RETRY_DELAY_MS)
        continue
      }
      if (confirmResponse.status !in 200..299) {
        throw IllegalStateException(songPublishErrorMessageFromApi("Story register confirm", confirmResponse))
      }
      val registration = confirmResponse.json?.optJSONObject("registration")
      val isConfirmed = registration?.optBoolean("confirmed", false) == true
      if (!isConfirmed) {
        throw IllegalStateException("Story register confirm returned success without confirmation")
      }
      confirmed = true
      break
    }
    if (!confirmed) {
      throw IllegalStateException("Story register confirm did not complete")
    }

    onProgress(88)

    val coordinatorAddress = BuildConfig.TEMPO_PUBLISH_COORDINATOR.trim()
    if (coordinatorAddress.isBlank() || coordinatorAddress.equals("0x0000000000000000000000000000000000000000", ignoreCase = true)) {
      throw IllegalStateException("Tempo publish coordinator is not configured in the app")
    }
    val datasetOwner = userAddress
    val algo = 1
    val visibility = 0
    val replaceIfActive = false
    val stagedCoverRef = stagedCoverDataitemId
      ?.trim()
      ?.ifBlank { null }
      ?.let { "ipfs://$it" }
      ?: throw IllegalStateException("Cover CID missing from staged artifacts")
    val stagedAudioPieceCid =
      stagedAudioId?.trim()?.ifBlank { null }
        ?: throw IllegalStateException("Audio piece CID missing from staged upload")
    val tempoSignedTx = songPublishBuildSignedTempoPublishTx(
      context = context,
      activity = signingActivity,
      account = account,
      coordinatorAddress = coordinatorAddress,
      ownerAddress = userAddress,
      title = formData.title,
      artist = formData.artist,
      album = "",
      durationSec = durationSec,
      coverRef = stagedCoverRef,
      datasetOwner = datasetOwner,
      pieceCid = stagedAudioPieceCid,
      algo = algo,
      visibility = visibility,
      replaceIfActive = replaceIfActive,
    )

    val finalizeResponse = songPublishFinalizeMusicPublish(
      jobId = jobId,
      userAddress = userAddress,
      title = formData.title,
      artist = formData.artist,
      durationSec = durationSec,
      album = "",
      tempoSignedTx = tempoSignedTx,
      purchasePrice = purchasePriceUnits,
      maxSupply = maxSupply,
    )
    if (finalizeResponse.status !in 200..299) {
      if (finalizeResponse.status == 404) {
        throw IllegalStateException(
          "Finalize endpoint not found. Backend is outdated; deploy latest api-core.",
        )
      }
      throw IllegalStateException(songPublishErrorMessageFromApi("Tempo finalize", finalizeResponse))
    }
    val finalizeJob = songPublishRequireJobObject("Tempo finalize", finalizeResponse)
    val finalizedStatus = finalizeJob.optString("status", "").trim()
    if (finalizedStatus != "registered") {
      throw IllegalStateException("Tempo finalize did not complete (status=$finalizedStatus)")
    }
    val finalizeCached = finalizeResponse.json
      ?.optJSONObject("registration")
      ?.optBoolean("cached", false) == true
    val reusedExistingRelease = reusedExistingRegistration || finalizeCached

    val publishId = finalizeResponse.json
      ?.optJSONObject("registration")
      ?.optString("publishId", "")
      ?.trim()
      ?.ifBlank { null }
      ?: finalizeJob.optString("publishId", "").trim().ifBlank { null }
      ?: throw IllegalStateException("Tempo finalize missing publishId")
    val canonicalTrackId = finalizeResponse.json
      ?.optJSONObject("registration")
      ?.optString("trackId", "")
      ?.trim()
      ?.ifBlank { null }
      ?: finalizeJob
        .optJSONObject("registration")
        ?.optString("trackId", "")
        ?.trim()
        ?.ifBlank { null }
      ?: finalizeJob.optJSONObject("job")
        ?.optJSONObject("registration")
        ?.optString("trackId", "")
        ?.trim()
        ?.ifBlank { null }
    val lyricsRegistration = finalizeResponse.json?.optJSONObject("registration")?.optJSONObject("lyrics")
    val lyricsQueued = lyricsRegistration?.optBoolean("queued", false) == true
    val initialLyricsManifestRef = lyricsRegistration
      ?.optString("manifestRef", "")
      ?.trim()
      ?.ifBlank { null }

    val presentationRegistryAddress = BuildConfig.TEMPO_TRACK_PRESENTATION_REGISTRY.trim()
    if (presentationRegistryAddress.isBlank() || presentationRegistryAddress.equals("0x0000000000000000000000000000000000000000", ignoreCase = true)) {
      throw IllegalStateException("Track presentation registry is not configured in the app")
    }
    val presentationDelegateAddress = BuildConfig.TEMPO_TRACK_PRESENTATION_DELEGATE.trim()
    if (presentationDelegateAddress.isBlank() || presentationDelegateAddress.equals("0x0000000000000000000000000000000000000000", ignoreCase = true)) {
      throw IllegalStateException("Track presentation delegate is not configured in the app")
    }
    val delegateExpiresAtSec = (System.currentTimeMillis() / 1_000L) + TRACK_PRESENTATION_DELEGATE_TTL_SECONDS
    val presentationDelegateSignedTx = songPublishBuildSignedTempoSetPublishDelegateTx(
      context = context,
      activity = signingActivity,
      account = account,
      registryAddress = presentationRegistryAddress,
      ownerAddress = userAddress,
      publishId = publishId,
      delegateAddress = presentationDelegateAddress,
      permissions = TRACK_PRESENTATION_DELEGATE_PERMISSIONS,
      expiresAtSec = delegateExpiresAtSec,
    )
    val attachPresentationResponse = songPublishAttachPresentationForMusicPublish(
      jobId = jobId,
      userAddress = userAddress,
      delegateSignedTx = presentationDelegateSignedTx,
    )
    if (attachPresentationResponse.status !in 200..299) {
      throw IllegalStateException(songPublishErrorMessageFromApi("Track presentation attach", attachPresentationResponse))
    }

    val publishedTrackReadiness =
      if (canonicalTrackId != null) {
        onProgress(96)
        awaitPublishedTrackReadiness(
          trackId = canonicalTrackId,
          waitForLyrics = lyricsQueued && initialLyricsManifestRef == null,
        )
      } else {
        null
      }

    PlayerPresentationRepository.invalidateTrack(canonicalTrackId)
    if (publishedTrackReadiness != null && !publishedTrackReadiness.presentation.ready) {
      Log.w(TAG, "published track status did not confirm presentation readiness trackId=$canonicalTrackId")
    }
    if (publishedTrackReadiness?.lyrics?.manifestRef != null || initialLyricsManifestRef != null || lyricsQueued) {
      PlayerLyricsRepository.invalidateTrack(canonicalTrackId)
    }

    onProgress(100)

    songPublishCacheRecentCoverRef(
      context = context,
      coverUri = coverUri,
      audioSha256 = audioSha256,
      recentCoversDir = RECENT_COVERS_DIR,
      maxCoverBytes = MAX_COVER_BYTES,
    )

    val canonicalCoverRef = stagedCoverRef

    val result = PublishResult(
      jobId = jobId,
      status = finalizedStatus,
      stagedAudioId = stagedAudioId,
      stagedAudioUrl = stagedAudioUrl,
      audioSha256 = audioSha256,
      coverCid = canonicalCoverRef,
      reusedExistingRelease = reusedExistingRelease,
    )
    val storyIpId = finalizeJob
      .optJSONObject("registration")
      ?.optString("storyIpId", "")
      ?.trim()
      ?.ifBlank { null }
    Log.i(
      TAG,
      "finalize jobId=$jobId status=$finalizedStatus cached=$finalizeCached reusedExisting=$reusedExistingRelease storyIpId=${storyIpId ?: "unknown"}",
    )

    if (!result.reusedExistingRelease) {
      runCatching {
        RecentlyPublishedSongsStore.record(
          context = context,
          title = formData.title,
          artist = formData.artist,
          audioCid = result.stagedAudioUrl ?: result.stagedAudioId,
          coverCid = result.coverCid,
        )
      }.onFailure { err ->
        Log.w(TAG, "Failed to cache recently published song", err)
      }
    } else {
      Log.i(TAG, "Skipped recent publish cache for reused existing release jobId=$jobId")
    }

    return result
  }

  private suspend fun submitPreparedStoryIntentIfNeeded(
    response: SongPublishApiResponse,
    userAddress: String,
    sessionKey: SessionKeyManager.SessionKey,
    submit: (operationId: String, userSig: String, intent: Any, keyAuthorization: String) -> SongPublishApiResponse,
  ): SongPublishApiResponse {
    val preparedIntent = songPublishParsePreparedStoryIntent(response) ?: return response
    val keyAuthorization = songPublishKeyAuthorizationHex(sessionKey)
    val userSig = songPublishSignDigestWithSessionKey(
      sessionKey = sessionKey,
      userAddress = userAddress,
      digestHex = preparedIntent.typedDataDigest,
    )
    return submit(
      preparedIntent.operationId,
      userSig,
      preparedIntent.intent,
      keyAuthorization,
    )
  }

  private suspend fun awaitPublishedTrackReadiness(
    trackId: String,
    waitForLyrics: Boolean,
  ): PublishedTrackReadiness? {
    repeat(LYRICS_READY_POLL_MAX_RETRIES) { attempt ->
      if (attempt > 0) delay(LYRICS_READY_POLL_DELAY_MS)
      val response = runCatching {
        songPublishGetPublishedTrackStatus(trackId = trackId)
      }.getOrElse { err ->
        Log.w(TAG, "published track readiness poll failed trackId=$trackId attempt=${attempt + 1}", err)
        return@repeat
      }
      if (response.status == 404) {
        Log.i(TAG, "published track readiness pending trackId=$trackId attempt=${attempt + 1}")
        return@repeat
      }
      if (response.status !in 200..299) {
        Log.w(TAG, "published track readiness returned status=${response.status} trackId=$trackId attempt=${attempt + 1}")
        return@repeat
      }
      val readiness = songPublishParsePublishedTrackReadiness(response.json) ?: run {
        Log.w(TAG, "published track readiness payload was invalid trackId=$trackId attempt=${attempt + 1}")
        return@repeat
      }
      val lyricsReady = readiness.lyrics.ready || !waitForLyrics
      val lyricsFailed = readiness.lyrics.status == "failed"
      if (readiness.presentation.ready && (lyricsReady || lyricsFailed)) {
        if (readiness.lyrics.ready) {
          Log.i(TAG, "published track lyrics became ready trackId=$trackId attempt=${attempt + 1}")
        }
        if (lyricsFailed) {
          Log.w(TAG, "published track lyrics failed trackId=$trackId attempt=${attempt + 1}")
        }
        return readiness
      }
    }
    Log.i(TAG, "published track readiness still pending after poll trackId=$trackId waitForLyrics=$waitForLyrics")
    return null
  }

  private fun parsePurchasePriceUnits(raw: String): String {
    val value = raw.trim()
    if (value.isBlank()) throw IllegalStateException("Purchase price is required")
    val amount = value.toBigDecimalOrNull()
      ?: throw IllegalStateException("Purchase price must be a valid number")
    if (amount <= BigDecimal.ZERO) {
      throw IllegalStateException("Purchase price must be greater than 0")
    }
    val scaled = amount.movePointRight(PURCHASE_TOKEN_DECIMALS)
    val units = try {
      scaled.toBigIntegerExact()
    } catch (_: ArithmeticException) {
      throw IllegalStateException("Purchase price supports up to $PURCHASE_TOKEN_DECIMALS decimal places")
    }
    if (units <= BigInteger.ZERO) {
      throw IllegalStateException("Purchase price must be greater than 0")
    }
    return units.toString()
  }

  private fun parseMaxSupply(openEdition: Boolean, raw: String): Int? {
    if (openEdition) return null
    val value = raw.trim()
    if (value.isBlank()) throw IllegalStateException("Max supply is required for capped editions")
    val n = value.toIntOrNull() ?: throw IllegalStateException("Max supply must be a whole number")
    if (n <= 0) throw IllegalStateException("Max supply must be greater than 0")
    if (n > 1_000_000) throw IllegalStateException("Max supply must be <= 1000000")
    return n
  }

  internal fun purchasePriceUnitsOrNull(raw: String): String? {
    val value = raw.trim()
    if (value.isBlank()) return null
    val amount = value.toBigDecimalOrNull() ?: return null
    if (amount <= BigDecimal.ZERO) return null
    return runCatching {
      amount.movePointRight(PURCHASE_TOKEN_DECIMALS).toBigIntegerExact().toString()
    }.getOrNull()
  }

  internal fun formatTokenUnitsAsUsd(rawUnits: String): String {
    // AlphaUSD uses 6 decimals.
    val units = rawUnits.toBigIntegerOrNull() ?: return "—"
    val scale = BigDecimal.TEN.pow(PURCHASE_TOKEN_DECIMALS)
    val amount = BigDecimal(units).divide(scale, PURCHASE_TOKEN_DECIMALS, RoundingMode.DOWN)
    return "$" + amount.stripTrailingZeros().toPlainString()
  }
}
