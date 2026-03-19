package sc.pirate.app

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import sc.pirate.app.auth.PirateAuthUiState
import sc.pirate.app.chat.XmtpChatService
import sc.pirate.app.player.PlayerController
import sc.pirate.app.profile.FollowListMode
import sc.pirate.app.profile.PublishedSongRow
import sc.pirate.app.assistant.AgoraVoiceController
import sc.pirate.app.assistant.AssistantService
import sc.pirate.app.schedule.ScheduledSessionVoiceController
import sc.pirate.app.tempo.TempoAccountFactory
import sc.pirate.app.tempo.TempoPasskeyManager
import kotlinx.coroutines.CoroutineScope

internal data class PirateNavHostContext(
  val activity: androidx.fragment.app.FragmentActivity,
  val navController: androidx.navigation.NavHostController,
  val authState: PirateAuthUiState,
  val activeAddress: String?,
  val heavenName: String?,
  val avatarUri: String?,
  val tempoAccount: TempoPasskeyManager.PasskeyAccount?,
  val onAuthStateChange: (PirateAuthUiState) -> Unit,
  val onRegister: () -> Unit,
  val onLogin: () -> Unit,
  val onLogout: () -> Unit,
  val onOpenDrawer: () -> Unit,
  val onMusicRootViewChange: (Boolean) -> Unit,
  val onShowMessage: (String) -> Unit,
  val onRefreshProfileIdentity: () -> Unit,
  val onChatThreadVisibilityChange: (Boolean) -> Unit,
  val onLearnSessionVisibilityChange: (Boolean) -> Unit,
  val onboardingInitialStep: sc.pirate.app.onboarding.OnboardingStep,
  val miniPlayerVisible: Boolean,
  val player: PlayerController,
  val chatService: XmtpChatService,
  val assistantService: AssistantService,
  val voiceController: AgoraVoiceController,
  val scheduledSessionVoiceController: ScheduledSessionVoiceController,
  val scope: CoroutineScope,
  val openSongRoute: (String, String?, String?) -> Unit,
  val openArtistRoute: (String) -> Unit,
  val openPublicProfileRoute: (String) -> Unit,
  val openFollowListRoute: (FollowListMode, String) -> Unit,
  val playPublishedSong: (PublishedSongRow) -> Unit,
)

@Composable
internal fun PirateNavHost(
  activity: androidx.fragment.app.FragmentActivity,
  innerPadding: PaddingValues,
  navController: androidx.navigation.NavHostController,
  authState: PirateAuthUiState,
  heavenName: String?,
  avatarUri: String?,
  onAuthStateChange: (PirateAuthUiState) -> Unit,
  onRegister: () -> Unit,
  onLogin: () -> Unit,
  onLogout: () -> Unit,
  player: PlayerController,
  chatService: XmtpChatService,
  assistantService: AssistantService,
  voiceController: AgoraVoiceController,
  scheduledSessionVoiceController: ScheduledSessionVoiceController,
  onOpenDrawer: () -> Unit,
  onMusicRootViewChange: (Boolean) -> Unit,
  onShowMessage: (String) -> Unit,
  onRefreshProfileIdentity: () -> Unit,
  onChatThreadVisibilityChange: (Boolean) -> Unit,
  onLearnSessionVisibilityChange: (Boolean) -> Unit,
  miniPlayerVisible: Boolean,
  onboardingInitialStep: sc.pirate.app.onboarding.OnboardingStep = sc.pirate.app.onboarding.OnboardingStep.NAME,
) {
  val scope = rememberCoroutineScope()
  val layoutDirection = LocalLayoutDirection.current
  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = navBackStackEntry?.destination?.route
  val navHostModifier =
    if (currentRoute == PirateRoute.Home.route) {
      Modifier
        .fillMaxSize()
        .padding(
          start = innerPadding.calculateLeftPadding(layoutDirection),
          top = innerPadding.calculateTopPadding(),
          end = innerPadding.calculateRightPadding(layoutDirection),
          bottom = 0.dp,
        )
    } else {
      Modifier.fillMaxSize().padding(innerPadding)
    }
  val activeAddress = authState.activeAddress()
  val tempoAccount = remember(
    authState.tempoAddress,
    authState.tempoCredentialId,
    authState.tempoPubKeyX,
    authState.tempoPubKeyY,
    authState.tempoRpId,
  ) {
    TempoAccountFactory.fromSession(
      tempoAddress = authState.tempoAddress,
      tempoCredentialId = authState.tempoCredentialId,
      tempoPubKeyX = authState.tempoPubKeyX,
      tempoPubKeyY = authState.tempoPubKeyY,
      tempoRpId = authState.tempoRpId.ifBlank { TempoPasskeyManager.DEFAULT_RP_ID },
    )
  }

  val openSongRoute: (String, String?, String?) -> Unit = { trackId, title, artist ->
    navController.navigate(
      PirateRoute.Song.buildRoute(trackId = trackId, title = title, artist = artist),
    ) { launchSingleTop = true }
  }
  val openArtistRoute: (String) -> Unit = { artistName ->
    navController.navigate(PirateRoute.Artist.buildRoute(artistName)) { launchSingleTop = true }
  }
  val openPublicProfileRoute: (String) -> Unit = { address ->
    navController.navigate(PirateRoute.PublicProfile.buildRoute(address)) { launchSingleTop = true }
  }
  val openFollowListRoute: (FollowListMode, String) -> Unit = { mode, address ->
    navController.navigate(PirateRoute.FollowList.buildRoute(address, mode)) { launchSingleTop = true }
  }
  val playPublishedSong: (PublishedSongRow) -> Unit = { song ->
    val audioUrl = sc.pirate.app.music.CoverRef.resolveCoverUrl(
      song.pieceCid,
      width = null,
      height = null,
      format = null,
      quality = null,
    )
    if (audioUrl.isNullOrBlank()) {
      onShowMessage("No audio available for this track")
    } else {
      val coverUrl = sc.pirate.app.music.CoverRef.resolveCoverUrl(
        song.coverCid,
        width = 192,
        height = 192,
        format = "webp",
        quality = 80,
      )
      val track = sc.pirate.app.music.MusicTrack(
        id = song.contentId,
        title = song.title.ifBlank { "Unknown Track" },
        artist = song.artist.ifBlank { "Unknown Artist" },
        album = song.album,
        durationSec = song.durationSec,
        uri = audioUrl,
        filename = song.title.ifBlank { "track" },
        artworkUri = coverUrl,
        contentId = song.contentId,
        pieceCid = song.pieceCid,
      )
      player.playTrack(track, listOf(track))
    }
  }

  val context = PirateNavHostContext(
    activity = activity,
    navController = navController,
    authState = authState,
    activeAddress = activeAddress,
    heavenName = heavenName,
    avatarUri = avatarUri,
    tempoAccount = tempoAccount,
    onAuthStateChange = onAuthStateChange,
    onRegister = onRegister,
    onLogin = onLogin,
    onLogout = onLogout,
    onOpenDrawer = onOpenDrawer,
    onMusicRootViewChange = onMusicRootViewChange,
    onShowMessage = onShowMessage,
    onRefreshProfileIdentity = onRefreshProfileIdentity,
    onChatThreadVisibilityChange = onChatThreadVisibilityChange,
    onLearnSessionVisibilityChange = onLearnSessionVisibilityChange,
    onboardingInitialStep = onboardingInitialStep,
    miniPlayerVisible = miniPlayerVisible,
    player = player,
    chatService = chatService,
    assistantService = assistantService,
    voiceController = voiceController,
    scheduledSessionVoiceController = scheduledSessionVoiceController,
    scope = scope,
    openSongRoute = openSongRoute,
    openArtistRoute = openArtistRoute,
    openPublicProfileRoute = openPublicProfileRoute,
    openFollowListRoute = openFollowListRoute,
    playPublishedSong = playPublishedSong,
  )

  NavHost(
    navController = navController,
    startDestination = PirateRoute.Home.route,
    modifier = navHostModifier,
    // nav-compose ships with default fades; opt out so Player can feel like a real "page open".
    enterTransition = { EnterTransition.None },
    exitTransition = { ExitTransition.None },
    popEnterTransition = { EnterTransition.None },
    popExitTransition = { ExitTransition.None },
  ) {
    registerPrimaryRoutes(context)
    registerProfileRoutes(context)
    registerModalRoutes(context)
  }
}
