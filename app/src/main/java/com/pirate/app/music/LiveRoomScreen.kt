package com.pirate.app.music

import android.view.SurfaceView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import coil.compose.AsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.MusicNote
import com.adamglin.phosphoricons.regular.X
import com.pirate.app.assistant.VoiceCallState
import com.pirate.app.assistant.getTempoWorkerAuthSession
import com.pirate.app.resolvePublicProfileIdentity
import com.pirate.app.tempo.SessionKeyAuthorizationProgress
import com.pirate.app.tempo.SessionKeyManager
import com.pirate.app.tempo.TempoPasskeyManager
import com.pirate.app.tempo.TempoSessionKeyApi
import com.pirate.app.ui.PirateIconButton
import com.pirate.app.ui.PiratePrimaryButton
import com.pirate.app.util.shortAddress
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.VideoCanvas
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class LiveRoomJoinMode {
  Free,
  Authenticated,
}

private enum class LiveRoomVideoScaleMode(
  val agoraRenderMode: Int,
  val label: String,
) {
  Fit(Constants.RENDER_MODE_FIT, "Fit"),
  Fill(Constants.RENDER_MODE_HIDDEN, "Fill"),
}

private enum class LiveRoomTicketPurchaseState {
  None,
  Confirming,
  Confirmed,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiveRoomScreen(
  roomId: String,
  initialTitle: String?,
  initialSubtitle: String?,
  initialHostWallet: String?,
  initialCoverRef: String?,
  initialLiveAmount: String?,
  initialListenerCount: Int?,
  initialStatus: String?,
  ownerEthAddress: String?,
  tempoAccount: TempoPasskeyManager.PasskeyAccount?,
  hostActivity: FragmentActivity,
  onBack: () -> Unit,
  onShowMessage: (String) -> Unit,
) {
  val appContext = hostActivity.applicationContext
  val scope = rememberCoroutineScope()
  val normalizedTempoAddress = tempoAccount?.address?.trim()?.lowercase()?.ifBlank { null }
  val initialHostAddress = initialHostWallet?.trim()?.lowercase()?.ifBlank { null }

  var roomInfo by remember(roomId) { mutableStateOf<LiveRoomPublicInfo?>(null) }
  var infoError by remember(roomId) { mutableStateOf<String?>(null) }
  var infoLoading by remember(roomId) { mutableStateOf(true) }
  var hostLabel by remember(roomId, initialSubtitle) { mutableStateOf(normalizeHostLabel(initialSubtitle)) }
  var ticketPurchaseState by rememberSaveable(roomId) { mutableStateOf(LiveRoomTicketPurchaseState.None) }
  var joinRequested by rememberSaveable(roomId) { mutableStateOf(false) }
  var viewerControlsVisible by rememberSaveable(roomId) { mutableStateOf(true) }
  var videoScaleModeName by rememberSaveable(roomId) { mutableStateOf(LiveRoomVideoScaleMode.Fit.name) }
  var showAudioOnlyHint by remember(roomId) { mutableStateOf(false) }

  var callState by remember(roomId) { mutableStateOf(VoiceCallState.Idle) }
  var actionBusy by remember(roomId) { mutableStateOf(false) }
  var transientError by remember(roomId) { mutableStateOf<String?>(null) }

  var rtcEngine by remember(roomId) { mutableStateOf<RtcEngine?>(null) }
  var remoteVideoUid by remember(roomId) { mutableStateOf<Int?>(null) }
  var remoteVideoActive by remember(roomId) { mutableStateOf(false) }
  var remoteVideoSurfaceView by remember(roomId) { mutableStateOf<SurfaceView?>(null) }
  var tokenRenewJob by remember(roomId) { mutableStateOf<Job?>(null) }
  var activeJoinMode by remember(roomId) { mutableStateOf<LiveRoomJoinMode?>(null) }

  var authToken by remember(roomId, normalizedTempoAddress) { mutableStateOf<String?>(null) }
  val videoScaleMode =
    remember(videoScaleModeName) {
      LiveRoomVideoScaleMode.entries.firstOrNull { it.name == videoScaleModeName }
        ?: LiveRoomVideoScaleMode.Fit
    }

  fun clearRuntimeState() {
    tokenRenewJob?.cancel()
    tokenRenewJob = null

    try {
      rtcEngine?.leaveChannel()
      rtcEngine?.let { RtcEngine.destroy() }
    } catch (_: Throwable) {
    }
    rtcEngine = null
    remoteVideoUid = null
    remoteVideoActive = false
    remoteVideoSurfaceView = null
    activeJoinMode = null
  }

  fun stopCall() {
    joinRequested = false
    clearRuntimeState()
    callState = VoiceCallState.Idle
  }

  suspend fun refreshInfo() {
    val cachedToken = authToken?.trim()?.takeIf { it.isNotBlank() }
    suspend fun loadAuthToken(forceRefresh: Boolean): String? {
      if (!forceRefresh) {
        authToken?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
      }
      val account = tempoAccount ?: return null
      val sessionKey =
        SessionKeyManager.load(hostActivity)?.takeIf {
          SessionKeyManager.isValid(it, ownerAddress = account.address) &&
            it.keyAuthorization?.isNotEmpty() == true
        } ?: return null
      return try {
        val base = com.pirate.app.BuildConfig.VOICE_CONTROL_PLANE_URL.trim().trimEnd('/')
        val auth =
          getTempoWorkerAuthSession(
            workerUrl = base,
            walletAddress = account.address,
            sessionKey = sessionKey,
          )
        authToken = auth.token
        auth.token
      } catch (_: Throwable) {
        null
      }
    }
    val token =
      when {
        cachedToken != null -> cachedToken
        normalizedTempoAddress == null -> null
        else -> loadAuthToken(forceRefresh = false)
      }

    try {
      val info =
        try {
          LiveRoomEntryApi.fetchPublicInfo(roomId = roomId, bearerToken = token)
        } catch (error: Throwable) {
          val apiError = error as? LiveRoomApiException
          if (token != null && apiError?.status == 401 && normalizedTempoAddress != null) {
            val refreshed = loadAuthToken(forceRefresh = true)
            if (!refreshed.isNullOrBlank()) {
              LiveRoomEntryApi.fetchPublicInfo(roomId = roomId, bearerToken = refreshed)
            } else {
              LiveRoomEntryApi.fetchPublicInfo(roomId = roomId)
            }
          } else {
            throw error
          }
        }
      roomInfo = info
      infoError = null
    } catch (error: Throwable) {
      if (roomInfo == null) {
        infoError = error.message ?: "Failed to load room status."
      }
    }
    infoLoading = false
  }

  suspend fun ensureAuthToken(forceRefresh: Boolean = false): String {
    if (!forceRefresh) {
      authToken?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    }

    val account = tempoAccount ?: throw IllegalStateException("Sign in with Tempo passkey to continue.")
    val sessionKey =
      SessionKeyManager.load(hostActivity)?.takeIf {
        SessionKeyManager.isValid(it, ownerAddress = account.address) &&
          it.keyAuthorization?.isNotEmpty() == true
      } ?: run {
        val authorization = TempoSessionKeyApi.authorizeSessionKey(
          activity = hostActivity,
          account = account,
          onProgress = { progress ->
            when (progress) {
              SessionKeyAuthorizationProgress.SIGNATURE_1 ->
                onShowMessage("Authorize your Tempo session key.")
              SessionKeyAuthorizationProgress.SIGNATURE_2 ->
                onShowMessage("Approve Tempo session activation.")
              SessionKeyAuthorizationProgress.FINALIZING ->
                onShowMessage("Finalizing Tempo session key...")
            }
          },
        )
        authorization.sessionKey
          ?.takeIf { authorization.success }
          ?: throw IllegalStateException(
            authorization.error ?: "Failed to authorize Tempo session key.",
          )
      }

    val base = com.pirate.app.BuildConfig.VOICE_CONTROL_PLANE_URL.trim().trimEnd('/')
    val auth =
      getTempoWorkerAuthSession(
        workerUrl = base,
        walletAddress = account.address,
        sessionKey = sessionKey,
      )
    authToken = auth.token
    return auth.token
  }

  suspend fun authenticatedEnterWithRetry(): LiveRoomJoinInfo {
    val token = ensureAuthToken(forceRefresh = false)
    return runCatching { LiveRoomEntryApi.authenticatedEnter(roomId = roomId, bearerToken = token) }
      .getOrElse { error ->
        val apiError = error as? LiveRoomApiException
        if (apiError?.status == 401) {
          val refreshedToken = ensureAuthToken(forceRefresh = true)
          return LiveRoomEntryApi.authenticatedEnter(roomId = roomId, bearerToken = refreshedToken)
        }
        throw error
      }
  }

  suspend fun ticketStartWithRetry(): LiveRoomTicketStartResult {
    val token = ensureAuthToken(forceRefresh = false)
    return runCatching { LiveRoomEntryApi.ticketStart(roomId = roomId, bearerToken = token) }
      .getOrElse { error ->
        val apiError = error as? LiveRoomApiException
        if (apiError?.status == 401) {
          val refreshedToken = ensureAuthToken(forceRefresh = true)
          return LiveRoomEntryApi.ticketStart(roomId = roomId, bearerToken = refreshedToken)
        }
        throw error
      }
  }

  suspend fun ticketConfirmWithRetry(
    quoteId: String,
    txHash: String,
  ): LiveRoomTicketConfirmResult {
    var lastPending: LiveRoomApiException? = null
    repeat(10) { _ ->
      val token = ensureAuthToken(forceRefresh = false)
      val attempt = runCatching {
        LiveRoomEntryApi.ticketConfirm(
          roomId = roomId,
          bearerToken = token,
          quoteId = quoteId,
          txHash = txHash,
        )
      }
      if (attempt.isSuccess) return attempt.getOrThrow()

      val error = attempt.exceptionOrNull()
      val apiError = error as? LiveRoomApiException
      if (apiError?.status == 401) {
        ensureAuthToken(forceRefresh = true)
      }
      if (apiError?.code == "payment_pending") {
        lastPending = apiError
        delay(1_500)
        return@repeat
      }
      throw (error ?: IllegalStateException("Ticket confirmation failed."))
    }
    throw(lastPending ?: IllegalStateException("Payment is still pending. Try again in a few seconds."))
  }

  fun startTokenRenewal(
    loopScope: CoroutineScope,
    mode: LiveRoomJoinMode,
  ) {
    tokenRenewJob?.cancel()
    tokenRenewJob =
      loopScope.launch {
        while (isActive) {
          delay(45_000)
          val engine = rtcEngine ?: break
          runCatching {
            val refreshed =
              when (mode) {
                LiveRoomJoinMode.Free -> LiveRoomEntryApi.publicEnter(roomId = roomId)
                LiveRoomJoinMode.Authenticated -> authenticatedEnterWithRetry()
              }
            engine.renewToken(refreshed.agoraViewerToken)
          }.onFailure { error ->
            transientError = resolveRoomErrorMessage(error)
          }
        }
      }
  }

  suspend fun joinAgora(
    join: LiveRoomJoinInfo,
    mode: LiveRoomJoinMode,
  ) {
    clearRuntimeState()
    callState = VoiceCallState.Connecting
    transientError = null

    val handler =
      object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
          scope.launch { callState = VoiceCallState.Connected }
        }

        override fun onError(err: Int) {
          scope.launch {
            if (callState == VoiceCallState.Connecting) {
              joinRequested = false
              transientError = "Voice connection failed (code $err)."
              callState = VoiceCallState.Error
            }
          }
        }

        override fun onRemoteVideoStateChanged(
          uid: Int,
          state: Int,
          reason: Int,
          elapsed: Int,
        ) {
          scope.launch {
            when (state) {
              Constants.REMOTE_VIDEO_STATE_STARTING,
              Constants.REMOTE_VIDEO_STATE_DECODING,
              Constants.REMOTE_VIDEO_STATE_FROZEN,
              -> {
                if (remoteVideoUid == null || remoteVideoUid == uid) {
                  remoteVideoUid = uid
                  remoteVideoActive = true
                }
              }

              Constants.REMOTE_VIDEO_STATE_STOPPED,
              Constants.REMOTE_VIDEO_STATE_FAILED,
              -> {
                if (remoteVideoUid == uid) {
                  remoteVideoActive = false
                  remoteVideoUid = null
                }
              }
            }
          }
        }

        override fun onUserMuteVideo(uid: Int, muted: Boolean) {
          scope.launch {
            if (muted) {
              if (remoteVideoUid == uid) {
                remoteVideoActive = false
              }
            } else {
              if (remoteVideoUid == null || remoteVideoUid == uid) {
                remoteVideoUid = uid
                remoteVideoActive = true
              }
            }
          }
        }

        override fun onUserOffline(uid: Int, reason: Int) {
          scope.launch {
            if (remoteVideoUid == uid) {
              remoteVideoActive = false
              remoteVideoUid = null
            }
          }
        }
      }

    val rtc =
      RtcEngine.create(
        RtcEngineConfig().apply {
          mContext = appContext
          mAppId = join.agoraAppId
          mEventHandler = handler
        },
      )

    rtc.setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
    rtc.setClientRole(Constants.CLIENT_ROLE_AUDIENCE)
    rtc.enableVideo()

    val options =
      ChannelMediaOptions().apply {
        autoSubscribeAudio = true
        autoSubscribeVideo = true
        publishMicrophoneTrack = false
      }

    val joinResult = rtc.joinChannel(join.agoraViewerToken, join.agoraChannel, join.agoraUid, options)
    if (joinResult != 0) {
      clearRuntimeState()
      throw IllegalStateException("Failed to join room audio (join code $joinResult).")
    }

    rtcEngine = rtc
    activeJoinMode = mode
    startTokenRenewal(loopScope = scope, mode = mode)
  }

  fun bindRemoteVideoIfReady() {
    val engine = rtcEngine ?: return
    val uid = remoteVideoUid ?: return
    val surface = remoteVideoSurfaceView ?: return
    runCatching {
      engine.setupRemoteVideo(
        VideoCanvas(
          surface,
          videoScaleMode.agoraRenderMode,
          uid,
        ),
      )
    }
  }

  fun enterLiveRoom() {
    if (actionBusy) return
    scope.launch {
      actionBusy = true
      transientError = null
      try {
        val info = roomInfo ?: LiveRoomEntryApi.fetchPublicInfo(roomId)
        roomInfo = info

        val join =
          if (info.audienceMode == "free" && info.gateType == "none") {
            LiveRoomEntryApi.publicEnter(roomId = roomId)
          } else {
            authenticatedEnterWithRetry()
          }

        val mode =
          if (info.audienceMode == "free" && info.gateType == "none") {
            LiveRoomJoinMode.Free
          } else {
            LiveRoomJoinMode.Authenticated
          }
        joinAgora(join = join, mode = mode)
      } catch (error: Throwable) {
        val apiError = error as? LiveRoomApiException
        if (apiError?.code == "ticket_required") {
          joinRequested = false
          transientError = null
          callState = VoiceCallState.Idle
          return@launch
        }
        joinRequested = false
        transientError = resolveRoomErrorMessage(error)
        callState = VoiceCallState.Error
      } finally {
        actionBusy = false
      }
    }
  }

  fun buyTicket() {
    if (actionBusy) return
    scope.launch {
      actionBusy = true
      transientError = null
      try {
        val account = tempoAccount ?: throw IllegalStateException("Sign in with Tempo passkey to buy tickets.")
        val ticketStart = ticketStartWithRetry()

        if (ticketStart.idempotent || ticketStart.ticketPurchasedAt != null) {
          ticketPurchaseState = LiveRoomTicketPurchaseState.Confirmed
          onShowMessage("Ticket already purchased.")
          refreshInfo()
          return@launch
        }

        val quoteId = ticketStart.quoteId?.trim().orEmpty()
        if (quoteId.isBlank()) {
          throw IllegalStateException("Ticket quote unavailable.")
        }

        val paymentRequirements = ticketStart.paymentRequirements
          ?: throw IllegalStateException("Ticket payment requirements missing.")

        onShowMessage("Submitting ticket payment...")
        val sessionKey =
          SessionKeyManager.load(hostActivity)?.takeIf {
            SessionKeyManager.isValid(it, ownerAddress = account.address) &&
              it.keyAuthorization?.isNotEmpty() == true
          }
        val txHash =
          submitLiveTicketPayment(
            activity = hostActivity,
            account = account,
            requirements = paymentRequirements,
            sessionKey = sessionKey,
            preferSelfPay = false,
          )

        onShowMessage("Confirming payment...")
        ticketPurchaseState = LiveRoomTicketPurchaseState.Confirming
        ticketConfirmWithRetry(quoteId = quoteId, txHash = txHash)

        ticketPurchaseState = LiveRoomTicketPurchaseState.Confirmed
        onShowMessage("Ticket purchased.")
        refreshInfo()
      } catch (error: Throwable) {
        ticketPurchaseState = LiveRoomTicketPurchaseState.None
        transientError = resolveRoomErrorMessage(error)
      } finally {
        actionBusy = false
      }
    }
  }

  LaunchedEffect(roomId) {
    infoLoading = true
    refreshInfo()
  }

  LaunchedEffect(roomId) {
    while (isActive) {
      refreshInfo()
      delay(5_000)
    }
  }

  LaunchedEffect(rtcEngine, remoteVideoUid, remoteVideoSurfaceView, videoScaleModeName) {
    bindRemoteVideoIfReady()
  }

  LaunchedEffect(roomInfo?.hostWallet, initialSubtitle, initialHostAddress) {
    val presetLabel = normalizeHostLabel(initialSubtitle)
    if (!presetLabel.isNullOrBlank()) {
      hostLabel = presetLabel
    }

    val wallet = roomInfo?.hostWallet ?: initialHostAddress ?: return@LaunchedEffect
    val resolved =
      runCatching { resolvePublicProfileIdentity(wallet).first }
        .getOrNull()
        ?.trim()
        ?.ifBlank { null }
        ?: shortAddress(wallet, prefixChars = 5, suffixChars = 3, minLengthToShorten = 12)
    hostLabel = resolved
  }

  DisposableEffect(roomId) {
    onDispose {
      clearRuntimeState()
    }
  }

  val info = roomInfo
  val coverImage = resolveReleaseCoverUrl(info?.coverRef ?: initialCoverRef)
  val roomTitle = info?.title?.ifBlank { null } ?: initialTitle?.ifBlank { null } ?: "Live Room"
  val hasConfirmedTicket = ticketPurchaseState == LiveRoomTicketPurchaseState.Confirmed
  val isConfirmingTicketPayment = ticketPurchaseState == LiveRoomTicketPurchaseState.Confirming
  val hostWallet = info?.hostWallet ?: initialHostAddress
  val hostDisplayName =
    normalizeHostLabel(hostLabel)
      ?: hostWallet?.let { shortAddress(it, prefixChars = 5, suffixChars = 3, minLengthToShorten = 12) }
  val effectiveAudienceMode =
    info?.audienceMode
      ?: when (initialLiveAmount?.trim()) {
        null, "" -> null
        "0" -> "free"
        else -> "ticketed"
      }
  val isBroadcastLive =
    callState == VoiceCallState.Connecting ||
      callState == VoiceCallState.Connected ||
      info?.broadcasterOnline == true
  val isTicketedRoom = effectiveAudienceMode == "ticketed"
  val isGatedRoom = info?.gateType?.let { it != "none" } == true
  val canEnterLive =
    info != null &&
      isBroadcastLive &&
      (info.audienceMode == "free" || info.canEnter)
  val canBuyLiveTicket =
    info != null &&
      info.audienceMode == "ticketed" &&
      (info.status == "live" || isBroadcastLive) &&
      !hasConfirmedTicket &&
      !info.canEnter
  val nowEpochSeconds = System.currentTimeMillis() / 1000L
  val isTicketSalesNotStarted =
    info?.status == "created" &&
      !isBroadcastLive &&
      info.audienceMode == "ticketed" &&
      (info.salesStartAt?.let { nowEpochSeconds < it } == true)
  val isTicketSalesClosed =
    info?.status == "created" &&
      !isBroadcastLive &&
      info.audienceMode == "ticketed" &&
      (info.salesEndAt?.let { nowEpochSeconds > it } == true)
  val canBuyScheduledTicket =
    info != null &&
      info.status == "created" &&
      info.audienceMode == "ticketed" &&
      !isTicketSalesNotStarted &&
      !isTicketSalesClosed &&
      !hasConfirmedTicket

  val entryAction =
    when {
      info == null -> null
      canEnterLive -> "enter"
      canBuyLiveTicket -> "buy"
      canBuyScheduledTicket -> "buy"
      else -> null
    }
  val holdViewerWhileReconnecting =
    joinRequested &&
      callState == VoiceCallState.Idle &&
      (info == null || infoLoading || actionBusy || entryAction == "enter")
  val actionLabel =
    when (entryAction) {
      "buy" -> "Buy Ticket"
      "enter" -> "Watch Live"
      else -> "Unavailable"
    }
  val showPrimaryAction =
    (entryAction != null || actionBusy) &&
      callState != VoiceCallState.Connecting &&
      callState != VoiceCallState.Connected
  val showRemoteVideo = callState == VoiceCallState.Connected && remoteVideoActive
  val showCoverImage = !showRemoteVideo && !coverImage.isNullOrBlank()
  val priceLabel = liveRoomPriceLabel(liveAmount = info?.liveAmount ?: initialLiveAmount, audienceMode = effectiveAudienceMode)
  val baseAudienceCount = info?.listenerCount ?: initialListenerCount ?: 0
  val audienceLabel =
    when (val audienceCount = (baseAudienceCount + 1).coerceAtLeast(1)) {
      1 -> "1 in room"
      else -> "$audienceCount in room"
    }
  val showAudienceRow = isBroadcastLive
  val statusLabel =
    when {
      callState == VoiceCallState.Connected && remoteVideoActive -> "Watching"
      callState == VoiceCallState.Connected -> "Audio Only"
      callState == VoiceCallState.Connecting -> "Joining"
      isBroadcastLive -> "Live"
      info?.status == "live" -> "Awaiting Host"
      isConfirmingTicketPayment -> "Confirming Payment"
      hasConfirmedTicket && info?.status == "created" -> "Waiting on Host"
      isTicketSalesNotStarted -> "Sales Not Open"
      isTicketSalesClosed -> "Sales Closed"
      info?.status == "created" -> "Scheduled"
      info?.status == "ended" -> "Ended"
      info?.status == "canceled" -> "Canceled"
      info != null -> formatLiveRoomStatus(info.status)
      !initialStatus.isNullOrBlank() -> formatLiveRoomStatus(initialStatus)
      else -> null
    }
  val ticketResaleValue =
    if (isTicketedRoom && info?.ticketTransferable != null) {
      if (info.ticketTransferable == true) "Yes" else "No"
    } else {
      null
    }
  val statusText =
    when {
      isConfirmingTicketPayment ->
        "Confirming your payment. This can take a few seconds."
      hasConfirmedTicket && !isBroadcastLive ->
        "Ticket confirmed. You can join once the host starts the room."
      callState == VoiceCallState.Connected && isBroadcastLive && !remoteVideoActive ->
        "Live now in audio-only mode."
      isBroadcastLive && isGatedRoom && normalizedTempoAddress == null ->
        "Sign in with Tempo to access this room."
      isBroadcastLive && isGatedRoom && info?.canEnter == false ->
        "This room requires a qualifying Tempo NFT."
      !isBroadcastLive && isTicketSalesNotStarted ->
        info?.salesStartAt?.let { "Ticket sales open ${formatEpochLocal(it)}" } ?: null
      !isBroadcastLive && isTicketSalesClosed ->
        "Ticket sales are closed."
      else -> null
    }
  val liveBadgeLabel = if (isBroadcastLive) "Live" else null
  val showViewerScreen =
    holdViewerWhileReconnecting ||
      callState == VoiceCallState.Connecting ||
      callState == VoiceCallState.Connected
  val showViewerLoading =
    holdViewerWhileReconnecting ||
      callState == VoiceCallState.Connecting ||
      (callState == VoiceCallState.Connected && !remoteVideoActive && !showAudioOnlyHint)
  val viewerOverlayMessage =
    when {
      holdViewerWhileReconnecting -> "Reconnecting"
      callState == VoiceCallState.Connecting -> "Joining stream..."
      callState == VoiceCallState.Connected && !remoteVideoActive && !showAudioOnlyHint -> "Loading video..."
      callState == VoiceCallState.Connected && !remoteVideoActive -> "Audio only"
      else -> null
    }

  LaunchedEffect(joinRequested, entryAction, callState, actionBusy) {
    if (!joinRequested) return@LaunchedEffect
    if (callState != VoiceCallState.Idle) return@LaunchedEffect
    if (actionBusy) return@LaunchedEffect
    if (entryAction == "enter") {
      enterLiveRoom()
    }
  }

  LaunchedEffect(showViewerScreen) {
    if (showViewerScreen) {
      viewerControlsVisible = true
    }
  }

  LaunchedEffect(showViewerScreen, callState, remoteVideoActive) {
    showAudioOnlyHint = false
    if (!showViewerScreen) return@LaunchedEffect
    if (callState == VoiceCallState.Connecting) return@LaunchedEffect
    if (callState == VoiceCallState.Connected && !remoteVideoActive) {
      delay(2_500)
      showAudioOnlyHint = true
    }
  }

  LaunchedEffect(showViewerScreen, viewerControlsVisible, callState, remoteVideoActive) {
    if (!showViewerScreen) return@LaunchedEffect
    if (!viewerControlsVisible) return@LaunchedEffect
    if (callState != VoiceCallState.Connected || !remoteVideoActive) return@LaunchedEffect
    delay(2_500)
    viewerControlsVisible = false
  }

  if (showViewerScreen) {
    val viewerTapSource = remember { MutableInteractionSource() }
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.scrim)
          .clickable(
            interactionSource = viewerTapSource,
            indication = null,
          ) {
            viewerControlsVisible = !viewerControlsVisible
          },
    ) {
      if (showRemoteVideo) {
        AndroidView(
          modifier = Modifier.fillMaxSize(),
          factory = { context: android.content.Context ->
            SurfaceView(context).also { view: SurfaceView ->
              remoteVideoSurfaceView = view
              bindRemoteVideoIfReady()
            }
          },
          update = { view: SurfaceView ->
            if (remoteVideoSurfaceView !== view) {
              remoteVideoSurfaceView = view
            }
            bindRemoteVideoIfReady()
          },
        )
      } else {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            PhosphorIcons.Regular.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
          )
        }
      }

      if (viewerControlsVisible) {
        PirateIconButton(
          onClick = {
            stopCall()
            onBack()
          },
          modifier =
            Modifier
              .align(Alignment.TopStart)
              .statusBarsPadding()
              .padding(12.dp),
        ) {
          Icon(
            PhosphorIcons.Regular.X,
            contentDescription = "Close",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(26.dp),
          )
        }

        if (showRemoteVideo) {
          LiveRoomPill(
            text = "Scale: ${videoScaleMode.label}",
            onClick = {
              videoScaleModeName =
                if (videoScaleMode == LiveRoomVideoScaleMode.Fit) {
                  LiveRoomVideoScaleMode.Fill.name
                } else {
                  LiveRoomVideoScaleMode.Fit.name
                }
            },
            modifier =
              Modifier
                .width(104.dp)
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp),
          )
        }
      }

      if (showViewerLoading && !viewerOverlayMessage.isNullOrBlank()) {
        LiveRoomLoadingSurface(
          text = viewerOverlayMessage,
          modifier =
            Modifier
              .align(Alignment.BottomCenter)
              .navigationBarsPadding()
              .padding(16.dp),
        )
      } else if (!viewerOverlayMessage.isNullOrBlank()) {
        LiveRoomMessageSurface(
          text = viewerOverlayMessage,
          isSubtle = true,
          modifier =
            Modifier
              .align(Alignment.BottomCenter)
              .navigationBarsPadding()
              .padding(16.dp),
        )
      }
    }
    return
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      CenterAlignedTopAppBar(
        title = {
          Text(
            text = roomTitle,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        },
        navigationIcon = {
          PirateIconButton(
            onClick = {
              stopCall()
              onBack()
            },
          ) {
            Icon(
              PhosphorIcons.Regular.X,
              contentDescription = "Close",
              tint = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.size(26.dp),
            )
          }
        },
        actions = {
          Box(modifier = Modifier.size(48.dp))
        },
      )
    },
    bottomBar = {
      if (showPrimaryAction) {
        Surface(
          modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
          color = MaterialTheme.colorScheme.surface,
          shadowElevation = 8.dp,
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
          ) {
            PiratePrimaryButton(
              text = actionLabel,
              onClick = {
                when (entryAction) {
                  "buy" -> buyTicket()
                  "enter" -> {
                    joinRequested = true
                    enterLiveRoom()
                  }
                  else -> {}
                }
              },
              modifier = Modifier.fillMaxWidth(),
              enabled = entryAction != null,
              loading = actionBusy,
            )
          }
        }
      }
    },
  ) { innerPadding ->
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .background(
            Brush.verticalGradient(
              colors =
                listOf(
                  MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                  MaterialTheme.colorScheme.background,
                ),
            ),
          )
          .padding(innerPadding)
          .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Surface(
        modifier =
          if (showRemoteVideo) {
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
          } else {
            Modifier.fillMaxWidth().aspectRatio(1f)
          },
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
      ) {
        Box(contentAlignment = Alignment.Center) {
          if (showRemoteVideo) {
            AndroidView(
              modifier = Modifier.fillMaxSize(),
              factory = { context: android.content.Context ->
                SurfaceView(context).also { view: SurfaceView ->
                  remoteVideoSurfaceView = view
                  bindRemoteVideoIfReady()
                }
              },
              update = { view: SurfaceView ->
                if (remoteVideoSurfaceView !== view) {
                  remoteVideoSurfaceView = view
                }
                bindRemoteVideoIfReady()
              },
            )
          } else if (showCoverImage) {
            AsyncImage(
              model = coverImage,
              contentDescription = "Room cover",
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
          } else {
            Icon(
              PhosphorIcons.Regular.MusicNote,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(40.dp),
            )
          }

          if (!liveBadgeLabel.isNullOrBlank()) {
            LiveRoomPill(
              text = liveBadgeLabel,
              modifier =
                Modifier
                  .align(Alignment.TopStart)
                  .padding(14.dp),
            )
          }
        }
      }

      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            if (!statusLabel.isNullOrBlank()) {
              LiveRoomDetailRow("Status", statusLabel, emphasizeValue = true)
            }
            if (!hostDisplayName.isNullOrBlank()) {
              LiveRoomDetailRow(
                label = "Host",
                value = hostDisplayName,
                emphasizeValue = true,
              )
            }
            if (!priceLabel.isNullOrBlank()) {
              LiveRoomDetailRow("Price", priceLabel)
            }
            if (showAudienceRow) {
              LiveRoomDetailRow("Audience", audienceLabel)
            }
            if (!ticketResaleValue.isNullOrBlank()) {
              LiveRoomDetailRow("Ticket Resale", ticketResaleValue)
            }
          }

          if (!statusText.isNullOrBlank()) {
            LiveRoomMessageSurface(text = statusText)
          }

          if (!infoError.isNullOrBlank()) {
            LiveRoomMessageSurface(
              text = infoError.orEmpty(),
              isError = true,
            )
          }

          if (!transientError.isNullOrBlank()) {
            LiveRoomMessageSurface(
              text = transientError.orEmpty(),
              isError = true,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun LiveRoomDetailRow(
  label: String,
  value: String,
  emphasizeValue: Boolean = false,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
      fontWeight = if (emphasizeValue) FontWeight.SemiBold else FontWeight.Medium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun LiveRoomPill(
  text: String,
  onClick: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier =
      modifier.then(
        if (onClick != null) {
          Modifier.clickable(onClick = onClick)
        } else {
          Modifier
        },
      ),
    shape = RoundedCornerShape(999.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun LiveRoomLoadingSurface(
  text: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CircularProgressIndicator(
        modifier = Modifier.size(18.dp),
        strokeWidth = 2.dp,
      )
      Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

@Composable
private fun LiveRoomMessageSurface(
  text: String,
  modifier: Modifier = Modifier,
  isSubtle: Boolean = false,
  isError: Boolean = false,
) {
  val containerColor =
    when {
      isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f)
      isSubtle -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
      else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
    }
  val contentColor =
    when {
      isError -> MaterialTheme.colorScheme.onErrorContainer
      isSubtle -> MaterialTheme.colorScheme.onSurfaceVariant
      else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(16.dp),
    color = containerColor,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.titleMedium,
      color = contentColor,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
    )
  }
}

private fun normalizeHostLabel(value: String?): String? {
  val trimmed = value?.trim().orEmpty()
  if (trimmed.isBlank()) return null
  if (trimmed.equals("host", ignoreCase = true)) return null
  return trimmed
}

private fun formatLiveRoomStatus(value: String?): String {
  return value
    ?.trim()
    ?.lowercase()
    ?.split('_', '-', ' ')
    ?.filter { it.isNotBlank() }
    ?.joinToString(" ") { token ->
      token.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString() }
    }
    ?.ifBlank { "Unknown" }
    ?: "Unknown"
}

private fun resolveRoomErrorMessage(error: Throwable): String {
  val apiError = error as? LiveRoomApiException
  if (apiError != null) {
    return when (apiError.code) {
      "room_not_live" -> "Room is not live yet."
      "wallet_not_linked" -> "Link the wallet that holds the required NFT."
      "ownership_not_found" -> "Required NFT ownership was not found."
      "policy_not_satisfied" -> "This Tempo account does not satisfy the NFT gate."
      "ticket_sales_not_started" -> "Ticket sales have not started yet."
      "ticket_sales_not_open" -> "Ticket sales are not open right now."
      "ticket_sales_closed" -> "Ticket sales are closed for this room."
      "wallet_required" -> "Sign in to enter this room."
      "unauthorized" -> "Sign in to continue."
      "quote_expired" -> "Ticket quote expired. Try again."
      "quote_not_found" -> "Ticket quote not found. Try again."
      "quote_already_used" -> "Ticket quote already used."
      "payment_pending" -> "Payment is pending confirmation."
      "payment_tx_not_found" -> "Payment not found on-chain yet."
      else -> apiError.reason ?: apiError.code
    }
  }
  return error.message ?: "Room action failed."
}

private fun formatEpochLocal(epochSeconds: Long): String {
  val formatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")
  return runCatching {
    Instant.ofEpochSecond(epochSeconds)
      .atZone(ZoneId.systemDefault())
      .format(formatter)
  }.getOrElse { "later" }
}

private fun formatUsdFromMicros(amountRaw: String?): String? {
  val micros = amountRaw?.trim()?.toLongOrNull() ?: return null
  val dollars = micros.toDouble() / 1_000_000.0
  return "$" + String.format(Locale.US, "%.2f", dollars)
}

private fun liveRoomPriceLabel(
  liveAmount: String?,
  audienceMode: String?,
): String? =
  when {
    audienceMode == "free" -> "Free"
    liveAmount.isNullOrBlank() -> null
    else -> formatUsdFromMicros(liveAmount)
  }
