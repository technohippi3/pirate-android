package sc.pirate.app

import android.util.Log
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.composable
import sc.pirate.app.identity.VerifyIdentityScreen
import sc.pirate.app.music.LiveRoomScreen
import sc.pirate.app.music.PublishScreen
import sc.pirate.app.onboarding.OnboardingScreen
import sc.pirate.app.post.PostCaptureScreen
import sc.pirate.app.post.PostPreviewScreen
import sc.pirate.app.profile.TempoNameRecordsApi
import sc.pirate.app.assistant.AssistantCallScreen
import sc.pirate.app.schedule.ScheduledSessionCallScreen
import sc.pirate.app.songpicker.SongPickerSong

private const val TAG = "PirateApp"
private const val CAPTURE_SELECTED_SONG_TRACK_ID_KEY = "capture_selected_song_track_id"
private const val CAPTURE_SELECTED_SONG_STORY_IP_ID_KEY = "capture_selected_song_story_ip_id"
private const val CAPTURE_SELECTED_SONG_TITLE_KEY = "capture_selected_song_title"
private const val CAPTURE_SELECTED_SONG_ARTIST_KEY = "capture_selected_song_artist"
private const val CAPTURE_SELECTED_SONG_COVER_CID_KEY = "capture_selected_song_cover_cid"
private const val CAPTURE_SELECTED_SONG_DURATION_SEC_KEY = "capture_selected_song_duration_sec"
private const val CAPTURE_SELECTED_SONG_PIECE_CID_KEY = "capture_selected_song_piece_cid"

internal fun NavGraphBuilder.registerModalRoutes(context: PirateNavHostContext) {
  composable(
    route = PirateRoute.Onboarding.route,
    enterTransition = { modalEnterTransition() },
    popExitTransition = { modalPopExitTransition() },
  ) {
    OnboardingScreen(
      activity = context.activity,
      userEthAddress = context.activeAddress.orEmpty(),
      tempoAddress = context.authState.tempoAddress,
      tempoCredentialId = context.authState.tempoCredentialId,
      tempoPubKeyX = context.authState.tempoPubKeyX,
      tempoPubKeyY = context.authState.tempoPubKeyY,
      tempoRpId = context.authState.tempoRpId.ifBlank { sc.pirate.app.tempo.TempoPasskeyManager.DEFAULT_RP_ID },
      initialStep = context.onboardingInitialStep,
      onEnsureMessagingInbox = { sessionKey ->
        val address = context.activeAddress
        val account = context.tempoAccount
        if (address.isNullOrBlank() || account == null) {
          return@OnboardingScreen "Missing account for messaging setup."
        }

        runCatching {
          if (!context.chatService.connected.value) {
            context.chatService.connect(address)
          }
          val inboxId =
            context.chatService.currentInboxId()
              ?: throw IllegalStateException("XMTP inbox unavailable")
          val publishResult =
            TempoNameRecordsApi.upsertXmtpInboxId(
              activity = context.activity,
              account = account,
              inboxId = inboxId,
              rpId = account.rpId,
              sessionKey = sessionKey,
            )
          if (!publishResult.success) {
            throw IllegalStateException(publishResult.error ?: "Failed to publish XMTP inbox mapping")
          }
        }.fold(
          onSuccess = { null },
          onFailure = { err ->
            Log.w(TAG, "XMTP bootstrap failed (onboarding): ${err.message}")
            "Failed to enable messaging inbox. Please retry."
          },
        )
      },
      onComplete = {
        context.onRefreshProfileIdentity()
        context.navController.navigate(PirateRoute.Home.route) {
          popUpTo(context.navController.graph.startDestinationId) { saveState = true }
          launchSingleTop = true
          restoreState = true
        }
        AppLocaleManager.applyStoredLocale(context.activity)
      },
    )
  }

  composable(
    route = PirateRoute.VerifyIdentity.route,
    enterTransition = { modalEnterTransition() },
    popExitTransition = { modalPopExitTransition() },
  ) {
    val isAuthenticated = context.authState.hasAnyCredentials()
    VerifyIdentityScreen(
      authState = context.authState,
      ownerAddress = context.authState.activeAddress(),
      isAuthenticated = isAuthenticated,
      onSelfVerifiedChange = { verified ->
        if (context.authState.selfVerified != verified) {
          context.onAuthStateChange(context.authState.copy(selfVerified = verified))
        }
      },
      onClose = { context.navController.popBackStack() },
      onShowMessage = context.onShowMessage,
    )
  }

  composable(
    route = PirateRoute.Post.route,
    enterTransition = { modalEnterTransition() },
    popExitTransition = { modalPopExitTransition() },
  ) {
    val isAuthenticated = context.authState.hasAnyCredentials()
    VerifyIdentityScreen(
      authState = context.authState,
      ownerAddress = context.authState.activeAddress(),
      isAuthenticated = isAuthenticated,
      onSelfVerifiedChange = { verified ->
        if (context.authState.selfVerified != verified) {
          context.onAuthStateChange(context.authState.copy(selfVerified = verified))
        }
      },
      onVerified = {
        context.navController.navigate(PirateRoute.PostCapture.route) {
          popUpTo(PirateRoute.Post.route) { inclusive = true }
          launchSingleTop = true
        }
      },
      onClose = { context.navController.popBackStack() },
      onShowMessage = context.onShowMessage,
    )
  }

  composable(
    route = PirateRoute.PostCapture.route,
    enterTransition = { modalEnterTransition() },
    popExitTransition = { modalPopExitTransition() },
  ) {
    PostCaptureScreen(
      ownerAddress = context.authState.activeAddress(),
      onClose = { context.navController.popBackStack() },
      onShowMessage = context.onShowMessage,
      onVideoSelected = { uri, selectedSong ->
        val state = context.navController.currentBackStackEntry?.savedStateHandle
        state?.set(CAPTURE_SELECTED_SONG_TRACK_ID_KEY, selectedSong?.trackId)
        state?.set(CAPTURE_SELECTED_SONG_STORY_IP_ID_KEY, selectedSong?.songStoryIpId)
        state?.set(CAPTURE_SELECTED_SONG_TITLE_KEY, selectedSong?.title)
        state?.set(CAPTURE_SELECTED_SONG_ARTIST_KEY, selectedSong?.artist)
        state?.set(CAPTURE_SELECTED_SONG_COVER_CID_KEY, selectedSong?.coverCid)
        state?.set(CAPTURE_SELECTED_SONG_DURATION_SEC_KEY, selectedSong?.durationSec)
        state?.set(CAPTURE_SELECTED_SONG_PIECE_CID_KEY, selectedSong?.pieceCid)
        context.navController.navigate(PirateRoute.PostPreview.buildRoute(uri.toString())) { launchSingleTop = true }
      },
    )
  }

  composable(
    route = PirateRoute.PostPreview.route,
    arguments = listOf(
      navArgument(PirateRoute.PostPreview.ARG_VIDEO_URI) { type = NavType.StringType },
    ),
    enterTransition = { modalEnterTransition() },
    popExitTransition = { modalPopExitTransition() },
  ) { backStackEntry ->
    val videoUriString = backStackEntry.arguments?.getString(PirateRoute.PostPreview.ARG_VIDEO_URI)
    if (videoUriString != null) {
      val videoUri = android.net.Uri.parse(videoUriString)
      val captureState = context.navController.previousBackStackEntry?.savedStateHandle
      val trackId = captureState?.get<String>(CAPTURE_SELECTED_SONG_TRACK_ID_KEY)?.trim().orEmpty()
      val initialSong =
        if (trackId.isBlank()) {
          null
        } else {
          SongPickerSong(
            trackId = trackId,
            songStoryIpId = captureState?.get<String>(CAPTURE_SELECTED_SONG_STORY_IP_ID_KEY),
            title = captureState?.get<String>(CAPTURE_SELECTED_SONG_TITLE_KEY).orEmpty(),
            artist = captureState?.get<String>(CAPTURE_SELECTED_SONG_ARTIST_KEY).orEmpty(),
            coverCid = captureState?.get<String>(CAPTURE_SELECTED_SONG_COVER_CID_KEY),
            durationSec = captureState?.get<Int>(CAPTURE_SELECTED_SONG_DURATION_SEC_KEY) ?: 0,
            pieceCid = captureState?.get<String>(CAPTURE_SELECTED_SONG_PIECE_CID_KEY),
          )
        }
      PostPreviewScreen(
        videoUri = videoUri,
        initialSong = initialSong,
        ownerAddress = context.authState.activeAddress() ?: "",
        tempoAccount = context.tempoAccount,
        onBack = { context.navController.popBackStack() },
        onClose = {
          context.navController.popBackStack()
          context.navController.popBackStack()
        },
        onShowMessage = context.onShowMessage,
      )
    } else {
      context.navController.popBackStack()
    }
  }

  composable(
    route = PirateRoute.Publish.route,
    enterTransition = { modalEnterTransition() },
    popExitTransition = { modalPopExitTransition() },
  ) {
    val isAuthenticated = context.authState.hasAnyCredentials()
    PublishScreen(
      authState = context.authState,
      ownerAddress = context.authState.activeAddress(),
      hostActivity = context.activity,
      tempoAccount = context.tempoAccount,
      heavenName = context.heavenName,
      isAuthenticated = isAuthenticated,
      onSelfVerifiedChange = { verified ->
        if (context.authState.selfVerified != verified) {
          context.onAuthStateChange(context.authState.copy(selfVerified = verified))
        }
      },
      onClose = { context.navController.popBackStack() },
      onShowMessage = context.onShowMessage,
    )
  }

  composable(
    route = PirateRoute.Player.route,
    // Slide the player up from the bottom (Spotify-style) instead of fading.
    enterTransition = { modalEnterTransition() },
    popExitTransition = { modalPopExitTransition() },
  ) {
    val isAuthenticated = context.authState.hasTempoCredentials()
    sc.pirate.app.player.PlayerScreen(
      player = context.player,
      ownerEthAddress = context.authState.activeAddress(),
      isAuthenticated = isAuthenticated,
      onClose = { context.navController.popBackStack() },
      onShowMessage = context.onShowMessage,
      onOpenSongPage = { trackId, title, artist ->
        context.navController.navigate(
          PirateRoute.Song.buildRoute(trackId = trackId, title = title, artist = artist),
        ) { launchSingleTop = true }
      },
      onOpenArtistPage = { artistName ->
        context.navController.navigate(PirateRoute.Artist.buildRoute(artistName)) { launchSingleTop = true }
      },
      hostActivity = context.activity,
      tempoAccount = context.tempoAccount,
    )
  }

  composable(
    route = PirateRoute.VoiceCall.route,
    enterTransition = { modalEnterTransition() },
    popExitTransition = { modalPopExitTransition() },
  ) {
    AssistantCallScreen(
      controller = context.voiceController,
      onMinimize = { context.navController.popBackStack() },
    )
  }

  composable(
    route = PirateRoute.ScheduledSessionCall.route,
    enterTransition = { modalEnterTransition() },
    popExitTransition = { modalPopExitTransition() },
  ) {
    ScheduledSessionCallScreen(
      controller = context.scheduledSessionVoiceController,
      onMinimize = { context.navController.popBackStack() },
    )
  }

  composable(
    route = PirateRoute.LiveRoom.route,
    arguments = listOf(
      navArgument(PirateRoute.LiveRoom.ARG_ROOM_ID) { type = NavType.StringType },
      navArgument(PirateRoute.LiveRoom.ARG_TITLE) {
        type = NavType.StringType
        nullable = true
        defaultValue = ""
      },
      navArgument(PirateRoute.LiveRoom.ARG_SUBTITLE) {
        type = NavType.StringType
        nullable = true
        defaultValue = ""
      },
      navArgument(PirateRoute.LiveRoom.ARG_HOST_WALLET) {
        type = NavType.StringType
        nullable = true
        defaultValue = ""
      },
      navArgument(PirateRoute.LiveRoom.ARG_COVER) {
        type = NavType.StringType
        nullable = true
        defaultValue = ""
      },
      navArgument(PirateRoute.LiveRoom.ARG_PRICE) {
        type = NavType.StringType
        nullable = true
        defaultValue = ""
      },
      navArgument(PirateRoute.LiveRoom.ARG_LISTENERS) {
        type = NavType.StringType
        nullable = true
        defaultValue = ""
      },
      navArgument(PirateRoute.LiveRoom.ARG_STATUS) {
        type = NavType.StringType
        nullable = true
        defaultValue = ""
      },
    ),
    enterTransition = { modalEnterTransition() },
    popExitTransition = { modalPopExitTransition() },
  ) { backStackEntry ->
    val roomId =
      backStackEntry.arguments
        ?.getString(PirateRoute.LiveRoom.ARG_ROOM_ID)
        ?.trim()
        .orEmpty()
    if (roomId.isBlank()) {
      context.navController.popBackStack()
      return@composable
    }

    LiveRoomScreen(
      roomId = roomId,
      initialTitle =
        backStackEntry.arguments
          ?.getString(PirateRoute.LiveRoom.ARG_TITLE)
          ?.trim()
          ?.ifBlank { null },
      initialSubtitle =
        backStackEntry.arguments
          ?.getString(PirateRoute.LiveRoom.ARG_SUBTITLE)
          ?.trim()
          ?.ifBlank { null },
      initialHostWallet =
        backStackEntry.arguments
          ?.getString(PirateRoute.LiveRoom.ARG_HOST_WALLET)
          ?.trim()
          ?.ifBlank { null },
      initialCoverRef =
        backStackEntry.arguments
          ?.getString(PirateRoute.LiveRoom.ARG_COVER)
          ?.trim()
          ?.ifBlank { null },
      initialLiveAmount =
        backStackEntry.arguments
          ?.getString(PirateRoute.LiveRoom.ARG_PRICE)
          ?.trim()
          ?.ifBlank { null },
      initialListenerCount =
        backStackEntry.arguments
          ?.getString(PirateRoute.LiveRoom.ARG_LISTENERS)
          ?.trim()
          ?.toIntOrNull(),
      initialStatus =
        backStackEntry.arguments
          ?.getString(PirateRoute.LiveRoom.ARG_STATUS)
          ?.trim()
          ?.ifBlank { null },
      ownerEthAddress = context.activeAddress,
      tempoAccount = context.tempoAccount,
      hostActivity = context.activity,
      onBack = { context.navController.popBackStack() },
      onShowMessage = context.onShowMessage,
    )
  }
}

private fun modalEnterTransition() =
  slideInVertically(
    animationSpec = tween(durationMillis = 220),
    initialOffsetY = { fullHeight -> fullHeight },
  )

private fun modalPopExitTransition() =
  slideOutVertically(
    animationSpec = tween(durationMillis = 200),
    targetOffsetY = { fullHeight -> fullHeight },
  )
