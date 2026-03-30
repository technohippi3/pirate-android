package sc.pirate.app

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.composable
import sc.pirate.app.assistant.VoiceCallState
import sc.pirate.app.chat.ChatScreen
import sc.pirate.app.community.CommunityScreen
import sc.pirate.app.home.HomeFeedScreen
import sc.pirate.app.learn.LearnScreen
import sc.pirate.app.music.MusicScreen
import sc.pirate.app.schedule.ScheduleAvailabilityScreen
import sc.pirate.app.schedule.ScheduleScreen
import sc.pirate.app.song.ArtistScreen
import sc.pirate.app.song.SongScreen
import sc.pirate.app.store.NameStoreScreen
import sc.pirate.app.wallet.WalletScreen

internal fun NavGraphBuilder.registerPrimaryRoutes(context: PirateNavHostContext) {
  composable(PirateRoute.AuthResolving.route) {
    Column(
      modifier = Modifier.fillMaxSize(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      CircularProgressIndicator()
      Text(
        text = "Preparing your account...",
        style = MaterialTheme.typography.bodyLarge,
      )
    }
  }

  composable(PirateRoute.Home.route) {
    val assistantCallState by context.voiceController.state.collectAsState()
    val scheduledCallState by context.scheduledSessionVoiceController.state.collectAsState()
    val isCallActive =
      assistantCallState == VoiceCallState.Connecting ||
        assistantCallState == VoiceCallState.Connected ||
        scheduledCallState == VoiceCallState.Connecting ||
        scheduledCallState == VoiceCallState.Connected
    HomeFeedScreen(
      musicPlayer = context.player,
      isCallActive = isCallActive,
      onOpenDrawer = context.onOpenDrawer,
      onOpenPost = {
        if (!context.authState.hasAnyCredentials()) {
          context.onShowMessage("Sign in to create a post")
          context.navController.navigate(PirateRoute.Onboarding.route) { launchSingleTop = true }
          return@HomeFeedScreen
        }
        if (!context.authState.selfVerified) {
          context.navController.navigate(PirateRoute.Post.route) { launchSingleTop = true }
          return@HomeFeedScreen
        }
        context.navController.navigate(PirateRoute.PostCapture.route) { launchSingleTop = true }
      },
      isAuthenticated = context.authState.hasAnyCredentials(),
      ownerAddress = context.authState.activeAddress(),
      primaryName = context.primaryName,
      avatarUri = context.avatarUri,
      tempoAccount = context.legacySignerAccount,
      hostActivity = context.activity,
      onRequireVerification = {
        context.navController.navigate(PirateRoute.VerifyIdentity.route) { launchSingleTop = true }
      },
      onOpenProfile = { address ->
        context.navController.navigate(PirateRoute.PublicProfile.buildRoute(address)) { launchSingleTop = true }
      },
      onOpenSongPage = { trackId, title, artist ->
        context.navController.navigate(
          PirateRoute.Song.buildRoute(trackId = trackId, title = title, artist = artist),
        ) { launchSingleTop = true }
      },
      onOpenLearn = { studySetRef, trackId, language, version, title, artist ->
        context.navController.navigate(
          PirateRoute.LearnExercises.buildRoute(
            studySetRef = studySetRef,
            trackId = trackId,
            language = language,
            version = version,
            title = title,
            artist = artist,
          ),
        ) { launchSingleTop = true }
      },
      onShowMessage = context.onShowMessage,
    )
  }

  composable(PirateRoute.Music.route) {
    val isAuthenticated = context.legacySignerAccount != null
    MusicScreen(
      player = context.player,
      ownerEthAddress = context.authState.activeAddress(),
      primaryName = context.primaryName,
      avatarUri = context.avatarUri,
      isAuthenticated = isAuthenticated,
      onShowMessage = context.onShowMessage,
      onOpenPlayer = {
        context.navController.navigate(PirateRoute.Player.route) { launchSingleTop = true }
      },
      onOpenDrawer = context.onOpenDrawer,
      onOpenLiveRoom = { room ->
        context.navController.navigate(
          PirateRoute.LiveRoom.buildRoute(
            roomId = room.roomId,
            title = room.title,
            subtitle = room.subtitle,
            hostWallet = room.hostWallet,
            coverRef = room.coverRef,
            liveAmount = room.liveAmount,
            listenerCount = room.listenerCount,
            status = room.status,
          ),
        ) { launchSingleTop = true }
      },
      onRootViewChange = context.onMusicRootViewChange,
      onOpenSongPage = { trackId, title, artist ->
        context.navController.navigate(
          PirateRoute.Song.buildRoute(trackId = trackId, title = title, artist = artist),
        ) { launchSingleTop = true }
      },
      onOpenArtistPage = { artistName ->
        context.navController.navigate(PirateRoute.Artist.buildRoute(artistName)) { launchSingleTop = true }
      },
      hostActivity = context.activity,
      tempoAccount = context.legacySignerAccount,
    )
  }

  composable(PirateRoute.Chat.route) {
    val isAuthenticated = context.authState.hasAnyCredentials()
    ChatScreen(
      chatService = context.chatService,
      assistantService = context.assistantService,
      voiceController = context.voiceController,
      isAuthenticated = isAuthenticated,
      userAddress = context.activeAddress,
      onOpenDrawer = context.onOpenDrawer,
      onShowMessage = context.onShowMessage,
      onNavigateToCall = {
        context.navController.navigate(PirateRoute.VoiceCall.route) { launchSingleTop = true }
      },
      onNavigateToCredits = {
        context.navController.navigate(PirateRoute.StudyCredits.route) { launchSingleTop = true }
      },
      onNavigateToVerifyIdentity = {
        context.navController.navigate(PirateRoute.VerifyIdentity.route) { launchSingleTop = true }
      },
      onOpenProfile = { address ->
        context.navController.navigate(PirateRoute.PublicProfile.buildRoute(address)) { launchSingleTop = true }
      },
      onThreadVisibilityChange = context.onChatThreadVisibilityChange,
    )
  }

  composable(PirateRoute.Wallet.route) {
    val isAuthenticated = context.authState.hasAnyCredentials()
    WalletScreen(
      activity = context.activity,
      isAuthenticated = isAuthenticated,
      walletAddress = context.activeAddress,
      account = context.legacySignerAccount,
      onClose = { context.navController.popBackStack() },
      onShowMessage = context.onShowMessage,
    )
  }

  composable(PirateRoute.NameStore.route) {
    val isAuthenticated = context.authState.hasAnyCredentials()
    NameStoreScreen(
      activity = context.activity,
      isAuthenticated = isAuthenticated,
      account = context.legacySignerAccount,
      onClose = { context.navController.popBackStack() },
      onShowMessage = context.onShowMessage,
    )
  }

  composable(PirateRoute.StudyCredits.route) {
    val isAuthenticated = context.authState.hasAnyCredentials()
    WalletScreen(
      activity = context.activity,
      isAuthenticated = isAuthenticated,
      walletAddress = context.activeAddress,
      account = context.legacySignerAccount,
      onClose = { context.navController.popBackStack() },
      onCreditsPurchased = { context.navController.popBackStack() },
      onShowMessage = context.onShowMessage,
    )
  }

  composable(PirateRoute.Learn.route) {
    val isAuthenticated = context.authState.hasAnyCredentials()
    LearnScreen(
      isAuthenticated = isAuthenticated,
      userAddress = context.activeAddress,
      authBusy = context.authState.busy,
      miniPlayerVisible = context.miniPlayerVisible,
      hostActivity = context.activity,
      tempoAccount = context.legacySignerAccount,
      onLearnSessionVisibilityChange = context.onLearnSessionVisibilityChange,
      onOpenStudySet = { studySetRef, trackId, language, version, title, artist ->
        context.navController.navigate(
          PirateRoute.LearnExercises.buildRoute(
            studySetRef = studySetRef,
            trackId = trackId,
            language = language,
            version = version,
            title = title,
            artist = artist,
          ),
        ) { launchSingleTop = true }
      },
      onRegister = context.onRegister,
      onLogin = context.onLogin,
      onOpenDrawer = context.onOpenDrawer,
      onShowMessage = context.onShowMessage,
    )
  }

  composable(
    route = PirateRoute.LearnExercises.route,
    arguments = listOf(
      navArgument(PirateRoute.LearnExercises.ARG_STUDY_SET_REF) {
        type = NavType.StringType
        nullable = true
        defaultValue = ""
      },
      navArgument(PirateRoute.LearnExercises.ARG_TRACK_ID) {
        type = NavType.StringType
        nullable = true
        defaultValue = ""
      },
      navArgument(PirateRoute.LearnExercises.ARG_LANG) {
        type = NavType.StringType
        nullable = true
        defaultValue = ""
      },
      navArgument(PirateRoute.LearnExercises.ARG_VERSION) {
        type = NavType.IntType
        defaultValue = 1
      },
      navArgument(PirateRoute.LearnExercises.ARG_TITLE) {
        type = NavType.StringType
        nullable = true
        defaultValue = ""
      },
      navArgument(PirateRoute.LearnExercises.ARG_ARTIST) {
        type = NavType.StringType
        nullable = true
        defaultValue = ""
      },
    ),
  ) { backStackEntry ->
    val initialStudySetRef = backStackEntry.arguments
      ?.getString(PirateRoute.LearnExercises.ARG_STUDY_SET_REF)
      ?.takeIf { it.isNotBlank() }
    val initialTrackId = backStackEntry.arguments
      ?.getString(PirateRoute.LearnExercises.ARG_TRACK_ID)
      ?.takeIf { it.isNotBlank() }
    val initialLanguage = backStackEntry.arguments
      ?.getString(PirateRoute.LearnExercises.ARG_LANG)
      ?.takeIf { it.isNotBlank() }
    val initialVersion = backStackEntry.arguments
      ?.getInt(PirateRoute.LearnExercises.ARG_VERSION)
      ?.coerceIn(1, 255)
      ?: 1
    val initialTitle = backStackEntry.arguments
      ?.getString(PirateRoute.LearnExercises.ARG_TITLE)
      ?.takeIf { it.isNotBlank() }
    val initialArtist = backStackEntry.arguments
      ?.getString(PirateRoute.LearnExercises.ARG_ARTIST)
      ?.takeIf { it.isNotBlank() }
    Log.d(
      "LearnLaunch",
      "Nav -> LearnExercises ref='$initialStudySetRef' trackId='$initialTrackId' lang='$initialLanguage' v=$initialVersion title='$initialTitle' artist='$initialArtist'",
    )
    val isAuthenticated = context.authState.hasAnyCredentials()
    LearnScreen(
      isAuthenticated = isAuthenticated,
      userAddress = context.activeAddress,
      initialStudySetRef = initialStudySetRef,
      initialTrackId = initialTrackId,
      initialLanguage = initialLanguage,
      initialVersion = initialVersion,
      initialTitle = initialTitle,
      initialArtist = initialArtist,
      miniPlayerVisible = context.miniPlayerVisible,
      hostActivity = context.activity,
      tempoAccount = context.legacySignerAccount,
      onLearnSessionVisibilityChange = context.onLearnSessionVisibilityChange,
      onExitToLearn = {
        context.navController.navigate(PirateRoute.Learn.route) {
          popUpTo(PirateRoute.LearnExercises.route) { inclusive = true }
          launchSingleTop = true
        }
      },
      onOpenDrawer = context.onOpenDrawer,
      onShowMessage = context.onShowMessage,
    )
  }

  composable(
    route = PirateRoute.Song.route,
    arguments = listOf(
      navArgument(PirateRoute.Song.ARG_TRACK_ID) { type = NavType.StringType },
      navArgument(PirateRoute.Song.ARG_TITLE) {
        type = NavType.StringType
        nullable = true
        defaultValue = ""
      },
      navArgument(PirateRoute.Song.ARG_ARTIST) {
        type = NavType.StringType
        nullable = true
        defaultValue = ""
      },
    ),
  ) { backStackEntry ->
    val trackId = backStackEntry.arguments?.getString(PirateRoute.Song.ARG_TRACK_ID).orEmpty()
    val title = backStackEntry.arguments?.getString(PirateRoute.Song.ARG_TITLE)?.takeIf { it.isNotBlank() }
    val artist = backStackEntry.arguments?.getString(PirateRoute.Song.ARG_ARTIST)?.takeIf { it.isNotBlank() }
    val isAuthenticated = context.authState.hasAnyCredentials()
    SongScreen(
      trackId = trackId,
      initialTitle = title,
      initialArtist = artist,
      player = context.player,
      isAuthenticated = isAuthenticated,
      userAddress = context.activeAddress,
      onBack = {
        context.navController.navigateUp()
      },
      onOpenArtist = { artistName ->
        context.navController.navigate(PirateRoute.Artist.buildRoute(artistName)) { launchSingleTop = true }
      },
      onOpenProfile = { address ->
        context.navController.navigate(PirateRoute.PublicProfile.buildRoute(address)) { launchSingleTop = true }
      },
      onOpenPlayer = {
        context.navController.navigate(PirateRoute.Player.route) { launchSingleTop = true }
      },
      onOpenLearn = { studySetRef, learnTrackId, learnLanguage, learnVersion, title, artist ->
        context.navController.navigate(
          PirateRoute.LearnExercises.buildRoute(
            studySetRef = studySetRef,
            trackId = learnTrackId,
            language = learnLanguage,
            version = learnVersion,
            title = title,
            artist = artist,
          ),
        ) { launchSingleTop = true }
      },
      onShowMessage = context.onShowMessage,
    )
  }

  composable(
    route = PirateRoute.Artist.route,
    arguments = listOf(navArgument(PirateRoute.Artist.ARG_ARTIST_NAME) { type = NavType.StringType }),
  ) { backStackEntry ->
    val artistName = backStackEntry.arguments?.getString(PirateRoute.Artist.ARG_ARTIST_NAME).orEmpty().trim()
    ArtistScreen(
      artistName = artistName,
      userAddress = context.activeAddress,
      onBack = { context.navController.popBackStack() },
      onOpenSong = { trackId, title, artist ->
        context.navController.navigate(
          PirateRoute.Song.buildRoute(
            trackId = trackId,
            title = title,
            artist = artist ?: artistName,
          ),
        ) { launchSingleTop = true }
      },
      onOpenProfile = { address ->
        context.navController.navigate(PirateRoute.PublicProfile.buildRoute(address)) { launchSingleTop = true }
      },
    )
  }

  composable(PirateRoute.Rooms.route) {
    CommunityScreen(
      viewerAddress = context.activeAddress,
      onMemberClick = { address ->
        context.navController.navigate(PirateRoute.PublicProfile.buildRoute(address)) {
          launchSingleTop = true
        }
      },
    )
  }

  composable(PirateRoute.Schedule.route) {
    val isAuthenticated = context.authState.hasAnyCredentials()
    ScheduleScreen(
      isAuthenticated = isAuthenticated,
      userAddress = context.activeAddress,
      tempoAccount = context.legacySignerAccount,
      onOpenDrawer = context.onOpenDrawer,
      onOpenAvailability = {
        context.navController.navigate(PirateRoute.ScheduleAvailability.route) { launchSingleTop = true }
      },
      onJoinBooking = { bookingId ->
        val wallet = context.activeAddress
        if (wallet.isNullOrBlank()) {
          context.onShowMessage("Sign in to join this session.")
          return@ScheduleScreen
        }
        context.scheduledSessionVoiceController.startSession(
          bookingId = bookingId,
          userAddress = wallet,
        )
        context.navController.navigate(PirateRoute.ScheduledSessionCall.route) { launchSingleTop = true }
      },
      onShowMessage = context.onShowMessage,
    )
  }

  composable(PirateRoute.ScheduleAvailability.route) {
    val isAuthenticated = context.authState.hasAnyCredentials()
    ScheduleAvailabilityScreen(
      isAuthenticated = isAuthenticated,
      userAddress = context.activeAddress,
      tempoAccount = context.legacySignerAccount,
      onClose = { context.navController.popBackStack() },
      onShowMessage = context.onShowMessage,
    )
  }
}
