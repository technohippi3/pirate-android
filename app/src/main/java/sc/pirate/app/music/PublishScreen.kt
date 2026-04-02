package sc.pirate.app.music

import android.content.Intent
import android.util.Log
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import sc.pirate.app.ui.PirateIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import sc.pirate.app.ui.PirateOutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import sc.pirate.app.auth.PirateAuthUiState
import sc.pirate.app.identity.SelfVerificationGate
import sc.pirate.app.song.SongArtistApi
import sc.pirate.app.ui.PiratePrimaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Step enum ───────────────────────────────────────────────────

private enum class PublishStep { SONG, PREVIEW, DETAILS, LICENSE, DONATION, SALES, FINALIZE, PUBLISHING, SUCCESS, ERROR }

private data class PublishFooterAction(
  val label: String,
  val enabled: Boolean = true,
  val supportingText: String? = null,
  val secondaryLabel: String? = null,
  val onSecondaryClick: (() -> Unit)? = null,
  val onClick: () -> Unit,
)

// ── Constants ───────────────────────────────────────────────────

internal data class DropdownOption(val value: String, val label: String)

private val HIDDEN_GENRE_OPTIONS = listOf(
  DropdownOption("kpop", "K-Pop"),
  DropdownOption("jpop", "J-Pop"),
)

internal val GENRE_OPTIONS = listOf(
  DropdownOption("pop", "Pop"), DropdownOption("rock", "Rock"),
  DropdownOption("hip-hop", "Hip-Hop / Rap"), DropdownOption("rnb", "R&B / Soul"),
  DropdownOption("electronic", "Electronic / Dance"), DropdownOption("blues", "Blues"),
  DropdownOption("jazz", "Jazz"), DropdownOption("classical", "Classical"),
  DropdownOption("country", "Country"), DropdownOption("folk", "Folk / Acoustic"),
  DropdownOption("metal", "Metal"), DropdownOption("punk", "Punk"),
  DropdownOption("indie", "Indie"), DropdownOption("latin", "Latin"),
  DropdownOption("reggae", "Reggae / Dancehall"), DropdownOption("afrobeats", "Afrobeats"),
  DropdownOption("ambient", "Ambient"), DropdownOption("soundtrack", "Soundtrack / Score"),
  DropdownOption("other", "Other"),
)

internal val ALL_GENRE_OPTIONS = GENRE_OPTIONS + HIDDEN_GENRE_OPTIONS

internal val LANGUAGE_OPTIONS = listOf(
  DropdownOption("en", "English"), DropdownOption("es", "Spanish"),
  DropdownOption("fr", "French"), DropdownOption("de", "German"),
  DropdownOption("it", "Italian"), DropdownOption("pt", "Portuguese"),
  DropdownOption("ru", "Russian"), DropdownOption("ja", "Japanese"),
  DropdownOption("ko", "Korean"), DropdownOption("zh", "Mandarin Chinese"),
  DropdownOption("ar", "Arabic"), DropdownOption("hi", "Hindi"),
  DropdownOption("tr", "Turkish"), DropdownOption("th", "Thai"),
  DropdownOption("vi", "Vietnamese"), DropdownOption("id", "Indonesian"),
  DropdownOption("tl", "Tagalog"), DropdownOption("sw", "Swahili"),
)

internal val SECONDARY_LANGUAGE_OPTIONS = listOf(DropdownOption("", "None")) + LANGUAGE_OPTIONS

internal val LICENSE_OPTIONS = listOf(
  DropdownOption("non-commercial", "Non-commercial only"),
  DropdownOption("commercial-use", "Commercial use (no remix)"),
  DropdownOption("commercial-remix", "Commercial use + remix"),
)

private const val GENIUS_PACKAGE = "com.genius.android"
private const val PUBLISH_SUCCESS_GENIUS_SUPPORTING_TEXT =
  "Add annotations on Genius.com so users can study the trivia and lore behind your song."

// ── Main Screen ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublishScreen(
  authState: PirateAuthUiState,
  ownerAddress: String?,
  primaryName: String?,
  isAuthenticated: Boolean,
  onSelfVerifiedChange: (Boolean) -> Unit = {},
  onClose: () -> Unit,
  onShowMessage: (String) -> Unit,
) {
  // Gate: require Self.xyz identity verification before showing publish form.
  if (!isAuthenticated || ownerAddress == null) {
    LaunchedEffect(Unit) {
      onShowMessage("Please sign in first")
      onClose()
    }
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
      Text("Redirecting…", style = MaterialTheme.typography.bodyLarge)
    }
    return
  }

  SelfVerificationGate(
    userAddress = ownerAddress,
    cachedVerified = authState.selfVerified,
    onVerified = { onSelfVerifiedChange(true) },
  ) {
    PublishFormContent(
      ownerAddress = ownerAddress,
      primaryName = primaryName,
      onClose = onClose,
      onShowMessage = onShowMessage,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PublishFormContent(
  ownerAddress: String,
  primaryName: String?,
  onClose: () -> Unit,
  onShowMessage: (String) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  // Publish identity is account-derived, not user-entered.
  val defaultArtist = primaryName?.trim().takeUnless { it.isNullOrEmpty() } ?: ownerAddress

  var step by remember { mutableStateOf(PublishStep.SONG) }
  var formData by remember { mutableStateOf(SongPublishService.SongFormData(artist = defaultArtist)) }
  var progress by remember { mutableFloatStateOf(0f) }
  var errorMessage by remember { mutableStateOf("") }
  var pendingCoverCrop by remember { mutableStateOf<SongPublishCoverCropSource?>(null) }
  var savingCroppedCover by remember { mutableStateOf(false) }

  // File pickers
  val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    if (uri != null) {
      val detectedAudioMime = songPublishGetMimeType(context, uri)
      val detectedAudioFileName = songPublishGetFileName(context, uri)
      if (!songPublishIsLikelyMp3(detectedAudioMime, detectedAudioFileName)) {
        onShowMessage("Only MP3 files are supported right now.")
        return@rememberLauncherForActivityResult
      }
      val duration = songPublishGetAudioDurationSec(context, uri)
      if (duration <= 0f) {
        formData =
          formData.copy(
            audioUri = uri,
            trackDurationSec = 0f,
            previewStartSec = 0f,
            previewEndSec = 0f,
          )
        onShowMessage("Could not read track duration for this file.")
      } else {
        val preview =
          songPublishNormalizePreviewWindow(
            rawStartSec = 0f,
            rawEndSec = minOf(duration, 30f),
            rawDurationSec = duration,
          )
        formData =
          formData.copy(
            audioUri = uri,
            trackDurationSec = preview.durationSec,
            previewStartSec = preview.startSec,
            previewEndSec = preview.endSec,
          )
      }
    }
  }
  val vocalsPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    if (uri != null) formData = formData.copy(vocalsUri = uri)
  }
  val instrumentalPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    if (uri != null) formData = formData.copy(instrumentalUri = uri)
  }
  val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    uri ?: return@rememberLauncherForActivityResult
    scope.launch {
      runCatching {
        songPublishLoadCoverCropSource(context, uri)
      }.onSuccess { source ->
        if (source.isSquare) {
          val normalizedUri = songPublishWriteSquareCoverBitmap(context, source.bitmap)
          formData = formData.copy(coverUri = normalizedUri)
          pendingCoverCrop = null
        } else {
          pendingCoverCrop = source
        }
      }.onFailure { error ->
        pendingCoverCrop = null
        onShowMessage(error.message ?: "Failed to load cover image")
      }
    }
  }
  val canvasPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    if (uri != null) formData = formData.copy(canvasUri = uri)
  }

  fun getFileName(uri: Uri?): String? {
    if (uri == null) return null
    return songPublishGetFileName(context, uri) ?: "file"
  }

  fun openGeniusAfterPublish() {
    val targetUrl =
      SongArtistApi.buildGeniusSongSearchUrl(
        title = formData.title,
        artist = formData.artist,
      )
    val webIntent =
      Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
      }
    val geniusIntent = Intent(webIntent).setPackage(GENIUS_PACKAGE)
    runCatching { context.startActivity(geniusIntent) }
      .recoverCatching { context.startActivity(webIntent) }
      .onFailure {
        onShowMessage("Unable to open Genius. Visit $targetUrl")
      }
  }

  fun doPublish() {
    fun buildErrorSummary(error: Throwable): String {
      val parts = ArrayList<String>(4)
      var cur: Throwable? = error
      while (cur != null && parts.size < 4) {
        val msg = cur.message?.trim().orEmpty()
        if (msg.isNotEmpty()) parts.add(msg)
        cur = cur.cause
      }
      return parts.distinct().joinToString(" | ").ifBlank { "Unknown error" }
    }

    step = PublishStep.PUBLISHING
    progress = 0f

    scope.launch {
      try {
        val publishResult = withContext(Dispatchers.IO) {
          SongPublishService.publish(
            context = context,
            formData = formData,
            ownerAddress = ownerAddress,
            onProgress = { pct -> progress = pct / 100f },
          )
        }
        if (publishResult.reusedExistingRelease) {
          onShowMessage("This audio is already published on Story. Existing release reused.")
        }
        step = PublishStep.SUCCESS
      } catch (e: Exception) {
        Log.e("PublishScreen", "Publish failed", e)
        errorMessage = buildErrorSummary(e)
        step = PublishStep.ERROR
      }
    }
  }

  val cropSource = pendingCoverCrop
  if (cropSource != null) {
    SongPublishCoverCropScreen(
      source = cropSource,
      saving = savingCroppedCover,
      onCancel = {
        if (!savingCroppedCover) pendingCoverCrop = null
      },
      onConfirm = { selection ->
        scope.launch {
          savingCroppedCover = true
          runCatching {
            songPublishWriteCroppedCover(
              context = context,
              source = cropSource,
              selection = selection,
            )
          }.onSuccess { croppedUri ->
            formData = formData.copy(coverUri = croppedUri)
            pendingCoverCrop = null
          }.onFailure { error ->
            onShowMessage(error.message ?: "Failed to crop cover image")
          }
          savingCroppedCover = false
        }
      },
    )
    return
  }

  val songStepValid =
    formData.title.isNotBlank() &&
      formData.artist.isNotBlank() &&
      formData.audioUri != null &&
      formData.vocalsUri != null &&
      formData.instrumentalUri != null &&
      formData.coverUri != null
  val previewStepValid = formData.audioUri != null && formData.trackDurationSec > 0f
  val detailsStepValid = formData.primaryLanguage.isNotBlank() && formData.lyrics.isNotBlank()
  val purchasePriceUnits = SongPublishService.purchasePriceUnitsOrNull(formData.purchasePriceUsd)
  val maxSupplyValue = formData.maxSupply.trim().toIntOrNull()?.takeIf { it in 1..1_000_000 }
  val salesStepValid = purchasePriceUnits != null && (formData.openEdition || maxSupplyValue != null)
  val donationStepValid = !formData.donationEnabled || SongPublishService.buildDonationPolicy(formData) != null
  val finalizeStepValid = formData.attestation && salesStepValid && donationStepValid
  val wizardSteps =
    listOf(
      PublishStep.SONG,
      PublishStep.PREVIEW,
      PublishStep.DETAILS,
      PublishStep.LICENSE,
      PublishStep.DONATION,
      PublishStep.SALES,
      PublishStep.FINALIZE,
    )
  val wizardIndex = wizardSteps.indexOf(step)
  val showWizardProgress = step != PublishStep.SUCCESS && step != PublishStep.ERROR && step != PublishStep.PUBLISHING
  val wizardProgress =
    when {
      wizardIndex >= 0 -> (wizardIndex + 1).toFloat() / wizardSteps.size.toFloat()
      else -> 1f
    }
  val topProgress = if (showWizardProgress) wizardProgress.coerceIn(0f, 1f) else null
  val usesCloseIcon =
    step == PublishStep.FINALIZE || step == PublishStep.PUBLISHING || step == PublishStep.SUCCESS
  val footerAction =
    when (step) {
      PublishStep.SONG -> PublishFooterAction(label = "Next", enabled = songStepValid) { step = PublishStep.PREVIEW }
      PublishStep.PREVIEW -> PublishFooterAction(label = "Next", enabled = previewStepValid) { step = PublishStep.DETAILS }
      PublishStep.DETAILS -> PublishFooterAction(label = "Next", enabled = detailsStepValid) { step = PublishStep.LICENSE }
      PublishStep.LICENSE -> PublishFooterAction(label = "Next") { step = PublishStep.DONATION }
      PublishStep.DONATION -> PublishFooterAction(label = "Next", enabled = donationStepValid) { step = PublishStep.SALES }
      PublishStep.SALES -> PublishFooterAction(label = "Next", enabled = salesStepValid) { step = PublishStep.FINALIZE }
      PublishStep.FINALIZE -> PublishFooterAction(label = "Publish", enabled = finalizeStepValid) { doPublish() }
      PublishStep.SUCCESS ->
        PublishFooterAction(
          label = "Done",
          supportingText = PUBLISH_SUCCESS_GENIUS_SUPPORTING_TEXT,
          secondaryLabel = "Annotate on Genius",
          onSecondaryClick = { openGeniusAfterPublish() },
        ) { onClose() }
      PublishStep.ERROR -> PublishFooterAction(label = "Try Again") { step = PublishStep.FINALIZE }
      PublishStep.PUBLISHING -> null
    }

  Scaffold(
    topBar = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .statusBarsPadding()
          .padding(horizontal = 16.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          PirateIconButton(
            onClick = {
              when (step) {
                PublishStep.SONG -> onClose()
                PublishStep.PREVIEW -> step = PublishStep.SONG
                PublishStep.DETAILS -> step = PublishStep.PREVIEW
                PublishStep.LICENSE -> step = PublishStep.DETAILS
                PublishStep.DONATION -> step = PublishStep.LICENSE
                PublishStep.SALES -> step = PublishStep.DONATION
                PublishStep.FINALIZE -> onClose()
                PublishStep.PUBLISHING -> onClose()
                PublishStep.SUCCESS -> onClose()
                PublishStep.ERROR -> step = PublishStep.FINALIZE
              }
            },
          ) {
            Icon(
              if (usesCloseIcon) PhosphorIcons.Regular.X else PhosphorIcons.Regular.CaretLeft,
              contentDescription =
                if (usesCloseIcon) {
                  "Close"
                } else {
                  "Previous screen"
                },
              tint = MaterialTheme.colorScheme.onBackground,
            )
          }

          if (topProgress != null) {
            LinearProgressIndicator(
              progress = { topProgress },
              modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
              color = MaterialTheme.colorScheme.primary,
              trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
          }
        }

      }
    },
    bottomBar = {
      if (footerAction != null) {
        Surface(
          modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
          tonalElevation = 3.dp,
          shadowElevation = 8.dp,
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            footerAction.supportingText?.let { supportingText ->
              Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
              )
            }
            if (footerAction.secondaryLabel != null && footerAction.onSecondaryClick != null) {
              PirateOutlinedButton(
                onClick = footerAction.onSecondaryClick,
                modifier = Modifier.fillMaxWidth().height(48.dp),
              ) {
                Text(footerAction.secondaryLabel)
              }
            }
            PiratePrimaryButton(
              text = footerAction.label,
              onClick = footerAction.onClick,
              enabled = footerAction.enabled,
              modifier = Modifier.fillMaxWidth().height(48.dp),
            )
          }
        }
      }
    },
  ) { padding ->
    AnimatedContent(
      targetState = step,
      modifier = Modifier.padding(padding),
      label = "publish-step",
    ) { currentStep ->
      when (currentStep) {
        PublishStep.SONG -> SongStep(
          formData = formData,
          onFormChange = { formData = it },
          onPickAudio = { audioPicker.launch("audio/mpeg") },
          onClearAudio = { formData = formData.copy(audioUri = null, trackDurationSec = 0f, previewStartSec = 0f, previewEndSec = 0f) },
          onPickVocals = { vocalsPicker.launch("audio/*") },
          onClearVocals = { formData = formData.copy(vocalsUri = null) },
          onPickInstrumental = { instrumentalPicker.launch("audio/*") },
          onClearInstrumental = { formData = formData.copy(instrumentalUri = null) },
          onPickCover = { coverPicker.launch("image/*") },
          getFileName = ::getFileName,
        )

        PublishStep.PREVIEW -> PreviewStep(
          formData = formData,
          onFormChange = { formData = it },
          onPickCanvas = { canvasPicker.launch("video/*") },
          onClearCanvas = { formData = formData.copy(canvasUri = null) },
          getFileName = ::getFileName,
        )

        PublishStep.DETAILS -> DetailsStep(
          formData = formData,
          onFormChange = { formData = it },
        )

        PublishStep.LICENSE -> LicenseStep(
          formData = formData,
          onFormChange = { formData = it },
        )

        PublishStep.DONATION -> DonationStep(
          formData = formData,
          onFormChange = { formData = it },
        )

        PublishStep.SALES -> SalesStep(
          formData = formData,
          onFormChange = { formData = it },
        )

        PublishStep.FINALIZE -> FinalizeStep(
          formData = formData,
          onFormChange = { formData = it },
          getFileName = ::getFileName,
        )

        PublishStep.PUBLISHING -> PublishingStep(progress = progress)

        PublishStep.SUCCESS -> SuccessStep(
          formData = formData,
        )

        PublishStep.ERROR -> ErrorStep(
          error = errorMessage,
        )
      }
    }
  }
}
