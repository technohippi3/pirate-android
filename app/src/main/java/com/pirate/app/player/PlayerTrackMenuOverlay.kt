package com.pirate.app.player

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.pirate.app.arweave.ArweaveTurboConfig
import com.pirate.app.arweave.TurboCreditsApi
import com.pirate.app.music.DownloadedTrackEntry
import com.pirate.app.music.DownloadedTracksStore
import com.pirate.app.music.MusicLibrary
import com.pirate.app.music.MusicTrack
import com.pirate.app.music.PurchasedTrackDownloadResult
import com.pirate.app.music.TrackMenuPolicyResolver
import com.pirate.app.music.TrackSaveForeverService
import com.pirate.app.music.UploadedTrackActions
import com.pirate.app.music.downloadLookupIdForTrack
import com.pirate.app.music.downloadPurchasedTrackToDevice
import com.pirate.app.music.resolveSongTrackId
import com.pirate.app.music.ui.AddToPlaylistSheet
import com.pirate.app.music.ui.TrackMenuSheet
import com.pirate.app.music.ui.TurboCreditsSheet
import com.pirate.app.tempo.SessionKeyManager
import com.pirate.app.tempo.TempoPasskeyManager
import kotlinx.coroutines.launch

private const val TURBO_CREDITS_COPY = "Save this song forever on Arweave for ~\$0.03."

@Composable
internal fun PlayerTrackMenuOverlay(
  track: MusicTrack,
  player: PlayerController,
  menuOpen: Boolean,
  onMenuOpenChange: (Boolean) -> Unit,
  ownerEthAddress: String?,
  isAuthenticated: Boolean,
  onShowMessage: (String) -> Unit,
  onOpenShare: () -> Unit,
  onOpenSongPage: ((trackId: String, title: String?, artist: String?) -> Unit)? = null,
  onOpenArtistPage: ((String) -> Unit)? = null,
  hostActivity: androidx.fragment.app.FragmentActivity? = null,
  tempoAccount: TempoPasskeyManager.PasskeyAccount? = null,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var addToPlaylistOpen by remember { mutableStateOf(false) }
  var uploadBusy by remember { mutableStateOf(false) }
  var downloadedByContentId by remember { mutableStateOf<Map<String, DownloadedTrackEntry>>(emptyMap()) }
  var turboCreditsSheetOpen by remember { mutableStateOf(false) }
  var turboCreditsSheetMessage by remember { mutableStateOf(TURBO_CREDITS_COPY) }

  fun promptTurboTopUp(message: String) {
    turboCreditsSheetMessage = message
    turboCreditsSheetOpen = true
  }

  fun openTurboTopUp() {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ArweaveTurboConfig.TOP_UP_URL))
    runCatching { context.startActivity(intent) }
      .onFailure {
        onShowMessage("Unable to open browser. Visit ${ArweaveTurboConfig.TOP_UP_URL}")
      }
  }

  fun isTrackDownloaded(t: MusicTrack): Boolean {
    val key = downloadLookupIdForTrack(t) ?: return false
    return downloadedByContentId.containsKey(key)
  }

  LaunchedEffect(Unit) {
    downloadedByContentId = DownloadedTracksStore.load(context)
  }

  val trackMenuPolicy =
    TrackMenuPolicyResolver.resolve(
      track = track,
      ownerEthAddress = ownerEthAddress,
      alreadyDownloaded = isTrackDownloaded(track),
    )
  val isPurchasedTrack = !track.purchaseId.isNullOrBlank()
  val showSaveAction = !isPurchasedTrack && (trackMenuPolicy.canSaveForever || !track.permanentRef.isNullOrBlank())

  TrackMenuSheet(
    open = menuOpen,
    track = track,
    onClose = { onMenuOpenChange(false) },
    onSaveForever = { t ->
      val policy = TrackMenuPolicyResolver.resolve(t, ownerEthAddress)
      if (!policy.canSaveForever) {
        onShowMessage("This track can't be saved forever")
        return@TrackMenuSheet
      }
      if (uploadBusy) {
        onShowMessage("Save Forever already in progress")
        return@TrackMenuSheet
      }
      if (!isAuthenticated || ownerEthAddress.isNullOrBlank()) {
        onShowMessage("Sign in to save forever")
        return@TrackMenuSheet
      }
      if (!t.permanentRef.isNullOrBlank()) {
        onShowMessage("Already saved forever")
        return@TrackMenuSheet
      }

      uploadBusy = true
      scope.launch {
        val sessionKey = SessionKeyManager.load(context)?.takeIf {
          SessionKeyManager.isValid(it, ownerAddress = ownerEthAddress)
        }
        if (sessionKey == null) {
          uploadBusy = false
          onShowMessage("Session expired. Sign in again to save forever.")
          return@launch
        }

        val shouldSkipTurboGate = TrackSaveForeverService.isLocalFilebaseTestPathEnabled()
        if (!shouldSkipTurboGate) {
          val balanceResult = runCatching { TurboCreditsApi.fetchBalance(sessionKey.address) }
          val balanceError = balanceResult.exceptionOrNull()
          if (balanceError != null) {
            uploadBusy = false
            if (TurboCreditsApi.isLikelyInsufficientBalanceError(balanceError.message)) {
              promptTurboTopUp(TURBO_CREDITS_COPY)
            } else {
              onShowMessage("Couldn't check Turbo balance. Try again.")
            }
            return@launch
          }
          val balance = balanceResult.getOrNull()
          if (balance == null || !balance.hasCredits) {
            uploadBusy = false
            promptTurboTopUp(TURBO_CREDITS_COPY)
            return@launch
          }
        }

        onShowMessage("Saving Forever...")
        val result =
          runCatching {
            TrackSaveForeverService.saveForever(
              context = context,
              ownerEthAddress = ownerEthAddress,
              track = t,
            )
          }
        uploadBusy = false

        result.onFailure { err ->
          if (!TrackSaveForeverService.isLocalFilebaseTestPathEnabled() &&
            TurboCreditsApi.isLikelyInsufficientBalanceError(err.message)
          ) {
            promptTurboTopUp(TURBO_CREDITS_COPY)
            return@onFailure
          }
          onShowMessage("Save Forever failed: ${err.message ?: "unknown error"}")
        }
        result.onSuccess { ok ->
          val updated =
            t.copy(
              contentId = ok.contentId,
              datasetOwner = ok.datasetOwner,
              algo = ok.algo,
              permanentRef = ok.permanentRef,
              permanentGatewayUrl = ok.permanentGatewayUrl,
              permanentSavedAtMs = ok.permanentSavedAtMs,
              savedForever = true,
            )

          player.updateTrack(updated)

          val cached = MusicLibrary.loadCachedTracks(context)
          val next =
            if (cached.any { it.id == updated.id }) cached.map { if (it.id == updated.id) updated else it }
            else cached + updated
          MusicLibrary.saveCachedTracks(context, next)

          onShowMessage("Saved forever on Arweave.")
        }
      }
    },
    onDownload = { t ->
      val policy =
        TrackMenuPolicyResolver.resolve(
          track = t,
          ownerEthAddress = ownerEthAddress,
          alreadyDownloaded = isTrackDownloaded(t),
        )
      if (!policy.canDownload) {
        onShowMessage("Already on device")
        return@TrackMenuSheet
      }
      scope.launch {
        val owner = ownerEthAddress?.trim()
        val isPurchasedTrack = !t.purchaseId.isNullOrBlank()
        val result =
          if (isPurchasedTrack) {
            downloadPurchasedTrackToDevice(
              context = context,
              track = t,
              ownerEthAddress = owner,
              purchaseIdsByTrackId = emptyMap(),
              activity = hostActivity,
              tempoAccount = tempoAccount,
            )
          } else {
            val uploadedResult =
              UploadedTrackActions.downloadUploadedTrackToDevice(
                context = context,
                track = t,
                ownerAddress = owner ?: t.datasetOwner,
                granteeAddress = owner,
              )
            PurchasedTrackDownloadResult(
              success = uploadedResult.success,
              alreadyDownloaded = uploadedResult.alreadyDownloaded,
              mediaUri = uploadedResult.mediaUri,
              error = uploadedResult.error,
            )
          }
        if (!result.success) {
          onShowMessage("Download failed: ${result.error ?: "unknown error"}")
          return@launch
        }
        downloadedByContentId = DownloadedTracksStore.load(context)
        onShowMessage(if (result.alreadyDownloaded) "Already downloaded" else "Downloaded to device")
      }
    },
    onShare = { _ -> onOpenShare() },
    onAddToPlaylist = {
      onMenuOpenChange(false)
      addToPlaylistOpen = true
    },
    onAddToQueue = { onShowMessage("Add to queue coming soon") },
    onGoToSong = { t ->
      val trackId = resolveSongTrackId(t)
      if (trackId.isNullOrBlank()) {
        Log.d(
          "SongTrackIdDebug",
          "PlayerTrackMenuOverlay onGoToSong blocked: resolvedTrackId=<null> title='${t.title}' artist='${t.artist}' id='${t.id}' contentId='${t.contentId}'",
        )
        onShowMessage("Song page unavailable for this track")
        return@TrackMenuSheet
      }
      Log.d(
        "SongTrackIdDebug",
        "PlayerTrackMenuOverlay onGoToSong tapped: resolvedTrackId=$trackId title='${t.title}' artist='${t.artist}' id='${t.id}' contentId='${t.contentId}'",
      )
      val navigator = onOpenSongPage
      if (navigator == null) {
        onShowMessage("Song view coming soon")
        return@TrackMenuSheet
      }
      navigator(trackId, t.title, t.artist)
    },
    onGoToAlbum = { onShowMessage("Album view coming soon") },
    onGoToArtist = { t ->
      val artist = t.artist.trim()
      if (artist.isBlank() || artist.equals("Unknown Artist", ignoreCase = true)) {
        onShowMessage("Artist unavailable for this track")
        return@TrackMenuSheet
      }
      val navigator = onOpenArtistPage
      if (navigator == null) {
        onShowMessage("Artist view coming soon")
        return@TrackMenuSheet
      }
      navigator(artist)
    },
    showSaveAction = showSaveAction,
    showDownloadAction = trackMenuPolicy.canDownload,
    showShareAction = trackMenuPolicy.canShare,
    saveActionLabel = "Save Forever",
    savedActionLabel = "Saved Forever",
    downloadLabel = "Download",
  )

  TurboCreditsSheet(
    open = turboCreditsSheetOpen,
    message = turboCreditsSheetMessage,
    onDismiss = { turboCreditsSheetOpen = false },
    onGetCredits = {
      turboCreditsSheetOpen = false
      openTurboTopUp()
    },
  )

  AddToPlaylistSheet(
    open = addToPlaylistOpen,
    track = track,
    isAuthenticated = isAuthenticated,
    ownerEthAddress = ownerEthAddress,
    tempoAccount = tempoAccount,
    hostActivity = hostActivity,
    onClose = { addToPlaylistOpen = false },
    onShowMessage = onShowMessage,
    onSuccess = { _, _, _ -> },
  )
}
