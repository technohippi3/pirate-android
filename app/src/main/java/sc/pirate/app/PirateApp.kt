package sc.pirate.app

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import sc.pirate.app.auth.PirateAuthMethod
import sc.pirate.app.auth.PirateAuthRequest
import sc.pirate.app.auth.PirateAuthUiState
import sc.pirate.app.auth.PirateAuthProviderFactory
import sc.pirate.app.auth.PirateAuthSheet
import sc.pirate.app.auth.PiratePasskeyDefaults
import sc.pirate.app.chat.XmtpChatService
import sc.pirate.app.assistant.AgoraVoiceController
import sc.pirate.app.assistant.AssistantVoiceBar
import sc.pirate.app.assistant.AssistantService
import sc.pirate.app.assistant.VoiceCallState
import sc.pirate.app.assistant.clearWorkerAuthCache
import sc.pirate.app.schedule.ScheduledSessionVoiceController
import sc.pirate.app.scrobble.ScrobbleService
import sc.pirate.app.post.PostStoryEnqueueSync
import sc.pirate.app.ui.PirateTopBar
import sc.pirate.app.player.PlayerController
import sc.pirate.app.onboarding.OnboardingStep
import sc.pirate.app.onboarding.checkOnboardingStatus
import sc.pirate.app.tempo.SessionKeyManager
import kotlinx.coroutines.launch

private const val TAG = "PirateApp"

@Composable
fun PirateApp(activity: androidx.fragment.app.FragmentActivity) {
  val appContext = LocalContext.current.applicationContext
  val navController = rememberNavController()
  val routes = listOf(PirateRoute.Home, PirateRoute.Music, PirateRoute.Learn, PirateRoute.Schedule, PirateRoute.Chat)
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }
  val drawerState = androidx.compose.material3.rememberDrawerState(initialValue = DrawerValue.Closed)
  val player = remember {
    PlayerController(appContext).also {
      sc.pirate.app.widget.NowPlayingWidgetActionReceiver.playerRef = it
    }
  }
  val miniPlayerTrack by player.currentTrack.collectAsState()
  val playerIsPlaying by player.isPlaying.collectAsState()
  val chatService = remember { XmtpChatService(appContext) }
  val assistantService = remember { AssistantService(appContext) }
  val voiceController = remember { AgoraVoiceController(appContext) }
  val scheduledSessionVoiceController = remember { ScheduledSessionVoiceController(appContext) }
  val assistantCallState by voiceController.state.collectAsState()
  val scheduledCallState by scheduledSessionVoiceController.state.collectAsState()

  var onboardingInitialStep by remember { mutableStateOf(OnboardingStep.NAME) }

  var authState by remember {
    val saved = PirateAuthUiState.load(appContext)
    mutableStateOf(
      saved.copy(
        passkeyRpId = saved.passkeyRpId.ifBlank { PiratePasskeyDefaults.DEFAULT_RP_ID },
      ),
    )
  }
  var authSheetVisible by remember { mutableStateOf(false) }
  var authSheetCodeMethod by remember { mutableStateOf<PirateAuthMethod?>(null) }
  var authSheetIdentifier by remember { mutableStateOf("") }
  var authSheetCode by remember { mutableStateOf("") }
  var authSheetCodeSent by remember { mutableStateOf(false) }
  val authProvider =
    remember(authState.provider) {
      PirateAuthProviderFactory.create(
        context = appContext,
        state = authState,
      )
    }
  val availableAuthMethods = authProvider.availableMethods
  val walletSession = remember(authState) { authProvider.currentSession(authState) }

  val scrobbleService =
    remember {
      ScrobbleService(
        appContext = appContext,
        player = player,
        getAuthState = { authState },
        onShowMessage = { msg ->
          scope.launch {
            snackbarHostState.showSnackbar(
              message = msg,
              withDismissAction = true,
              duration = SnackbarDuration.Long,
            )
          }
        },
      )
    }

  DisposableEffect(Unit) {
    scrobbleService.start()
    onDispose {
      scrobbleService.close()
      player.release()
      voiceController.release()
      scheduledSessionVoiceController.release()
    }
  }

  val isAuthenticated = authState.hasAnyCredentials()
  val activeAddress = walletSession?.walletAddress ?: authState.activeAddress()

  // Profile identity — hoisted so drawer + profile screen can share
  var primaryName by remember { mutableStateOf<String?>(null) }
  var avatarUri by remember { mutableStateOf<String?>(null) }
  var chatThreadOpen by remember { mutableStateOf(false) }
  var musicRootViewActive by remember { mutableStateOf(true) }
  var learnSessionActive by remember { mutableStateOf(false) }
  var xmtpBootstrapKey by remember { mutableStateOf<String?>(null) }

  suspend fun bootstrapXmtpInbox(address: String, source: String) {
    val normalizedAddress = address.trim()
    if (normalizedAddress.isBlank()) return
    val key = normalizedAddress.lowercase()
    if (chatService.connected.value) {
      xmtpBootstrapKey = key
      return
    }
    if (xmtpBootstrapKey == key) return
    xmtpBootstrapKey = key
    runCatching {
      chatService.connect(normalizedAddress)
    }.onSuccess {
      xmtpBootstrapKey = key
    }.onFailure { err ->
      xmtpBootstrapKey = null
      Log.w(TAG, "XMTP bootstrap failed ($source): ${err.message}")
    }
  }

  LaunchedEffect(activeAddress) {
    val addr = activeAddress
    if (addr.isNullOrBlank()) {
      primaryName = null; avatarUri = null; return@LaunchedEffect
    }
    val (name, avatar) = resolveProfileIdentityWithRetry(addr, attempts = 2, retryDelayMs = 700)
    primaryName = name
    avatarUri = avatar
  }

  LaunchedEffect(activeAddress) {
    val address = activeAddress
    if (address.isNullOrBlank()) return@LaunchedEffect
    val preferredLocaleTag = runCatching { ViewerContentLocaleResolver.resolve(appContext) }.getOrNull()
    if (AppLocaleManager.storePreferredLocale(appContext, preferredLocaleTag)) {
      AppLocaleManager.applyStoredLocale(appContext)
    }
  }

  LaunchedEffect(activeAddress) {
    val address = activeAddress
    if (address.isNullOrBlank()) return@LaunchedEffect
    runCatching {
      PostStoryEnqueueSync.retryPendingForOwner(
        context = appContext,
        ownerAddress = address,
      )
    }.onFailure { error ->
      Log.w(TAG, "post-story retry failed: ${error.message}")
    }
  }

  // Ensure each authenticated user gets an XMTP inbox as soon as possible,
  // rather than waiting until they first open the chat screen.
  LaunchedEffect(isAuthenticated, activeAddress) {
    if (!isAuthenticated) {
      xmtpBootstrapKey = null
      return@LaunchedEffect
    }
    val address = activeAddress
    if (address.isNullOrBlank()) return@LaunchedEffect

    bootstrapXmtpInbox(address = address, source = "auth")
  }

  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = navBackStackEntry?.destination?.route
  val assistantCallActive =
    assistantCallState == VoiceCallState.Connecting || assistantCallState == VoiceCallState.Connected
  val scheduledCallActive =
    scheduledCallState == VoiceCallState.Connecting || scheduledCallState == VoiceCallState.Connected
  val anyCallActive = assistantCallActive || scheduledCallActive
  val hideBottomChromeForMusicSubview =
    currentRoute == PirateRoute.Music.route && !musicRootViewActive
  val hideBottomChromeForLearnSession =
    currentRoute == PirateRoute.Learn.route && learnSessionActive
  val showBottomChrome =
    showBottomChromeForRoute(currentRoute = currentRoute, chatThreadOpen = chatThreadOpen) &&
      !hideBottomChromeForMusicSubview &&
      !hideBottomChromeForLearnSession
  val miniPlayerVisible =
    miniPlayerTrack != null &&
      currentRoute != PirateRoute.Home.route &&
      !learnSessionActive
  val showMiniPlayerForMusicSubviewOnly =
    hideBottomChromeForMusicSubview &&
      !hideBottomChromeForLearnSession &&
      miniPlayerVisible
  val showTopChrome = showTopChromeForRoute(currentRoute = currentRoute)
  val drawerGesturesEnabled =
    drawerGesturesEnabledForRoute(currentRoute = currentRoute, chatThreadOpen = chatThreadOpen) &&
      !hideBottomChromeForLearnSession
  val currentTitle = routeTitle(currentRoute = currentRoute)

  LaunchedEffect(currentRoute, anyCallActive, playerIsPlaying) {
    val shouldPauseMusic = currentRoute == PirateRoute.Home.route || anyCallActive
    if (shouldPauseMusic && playerIsPlaying) {
      player.pause()
    }
  }

  suspend fun openDrawer() {
    drawerState.open()
  }

  fun resetAuthSheet() {
    authSheetCodeMethod = null
    authSheetIdentifier = ""
    authSheetCode = ""
    authSheetCodeSent = false
  }

  fun openAuthSheet() {
    resetAuthSheet()
    authSheetVisible = true
  }

  suspend fun resolvePostAuthDestination(
    userAddress: String,
    alreadyResolving: Boolean = false,
    onboardingOverride: OnboardingStep? = null,
  ) {
    if (!alreadyResolving) {
      navController.navigate(PirateRoute.AuthResolving.route) {
        popUpTo(navController.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
      }
    }
    val step = onboardingOverride ?: runCatching { checkOnboardingStatus(appContext, userAddress) }.getOrNull()
    Log.d(TAG, "resolvePostAuthDestination: address=$userAddress step=$step alreadyResolving=$alreadyResolving")
    if (step != null) {
      onboardingInitialStep = step
      navController.navigate(PirateRoute.Onboarding.route) {
        popUpTo(navController.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
      }
    } else {
      navController.navigate(PirateRoute.Home.route) {
        popUpTo(navController.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
      }
    }
  }

  suspend fun doAuthenticate(request: PirateAuthRequest) {
    if (request.method !in availableAuthMethods) {
      val message = "${request.method.label} sign-in is unavailable right now."
      authState = authState.copy(busy = false, output = message)
      snackbarHostState.showSnackbar(message)
      return
    }
    authSheetVisible = false
    navController.navigate(PirateRoute.AuthResolving.route) {
      popUpTo(navController.graph.startDestinationId) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
    authState =
      authState.copy(
        busy = true,
        output = "Signing in with ${request.method.label.lowercase()}...",
      )
    val result = runCatching {
      authProvider.authenticate(
        activity = activity,
        currentState = authState,
        request = request,
      )
    }

    result.onFailure { err ->
      authState = authState.copy(busy = false, output = "authentication failed: ${err.message}")
      navController.navigate(PirateRoute.Home.route) {
        popUpTo(navController.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
      }
      snackbarHostState.showSnackbar("Authentication failed: ${err.message ?: "unknown error"}")
    }

    result.onSuccess { nextState ->
      chatService.disconnect()
      clearWorkerAuthCache()
      xmtpBootstrapKey = null
      primaryName = null
      avatarUri = null
      authState = nextState
      PirateAuthUiState.save(appContext, nextState)
      val accountAddress = nextState.activeAddress() ?: return@onSuccess
      scope.launch { snackbarHostState.showSnackbar("Signed in.") }
      resolvePostAuthDestination(accountAddress, alreadyResolving = true)
    }
  }

  suspend fun doSendOneTimeCode(method: PirateAuthMethod, identifier: String) {
    if (method !in availableAuthMethods) {
      val message = "${method.label} sign-in is unavailable right now."
      authState = authState.copy(busy = false, output = message)
      snackbarHostState.showSnackbar(message)
      return
    }
    val trimmedIdentifier = identifier.trim()
    authState =
      authState.copy(
        busy = true,
        output = "Sending ${method.label.lowercase()} code...",
      )
    val result = runCatching {
      authProvider.sendOneTimeCode(
        method = method,
        identifier = trimmedIdentifier,
      )
    }

    result.onFailure { err ->
      authState = authState.copy(busy = false, output = "code send failed: ${err.message}")
      snackbarHostState.showSnackbar("Could not send code: ${err.message ?: "unknown error"}")
    }

    result.onSuccess {
      authState = authState.copy(busy = false, output = "Verification code sent.")
      authSheetIdentifier = trimmedIdentifier
      authSheetCodeSent = true
      snackbarHostState.showSnackbar("Verification code sent.")
    }
  }

  suspend fun doLogout() {
    authState = authState.copy(busy = true, output = "Signing out...")
    val result = runCatching {
      authProvider.clearSession(authState)
    }

    result.onFailure { err ->
      authState = authState.copy(busy = false, output = "logout failed: ${err.message}")
      snackbarHostState.showSnackbar("Log out failed: ${err.message ?: "unknown error"}")
      return
    }

    chatService.disconnect()
    clearWorkerAuthCache()
    xmtpBootstrapKey = null
    primaryName = null
    avatarUri = null
    SessionKeyManager.clear(appContext)
    PirateAuthUiState.clear(appContext)
    authState = result.getOrThrow()
    snackbarHostState.showSnackbar("Logged out.")
  }

  ModalNavigationDrawer(
    drawerState = drawerState,
    gesturesEnabled = drawerGesturesEnabled,
    drawerContent = {
      PirateDrawerContent(
        drawerState = drawerState,
        navController = navController,
        isAuthenticated = isAuthenticated,
        busy = authState.busy,
        ethAddress = activeAddress,
        primaryName = primaryName,
        avatarUri = avatarUri,
        selfVerified = authState.selfVerified,
        onContinue = { openAuthSheet() },
        onLogout = { doLogout() },
      )
    },
  ) {
    Scaffold(
      // We are not edge-to-edge; avoid double-applying system-bar insets which creates a
      // "mystery gap" above headers.
      contentWindowInsets = WindowInsets(0),
      topBar = {
        if (showTopChrome) {
          PirateTopBar(
            title = currentTitle,
            isAuthenticated = isAuthenticated,
            ethAddress = activeAddress,
            primaryName = primaryName,
            avatarUri = avatarUri,
            onAvatarClick = { scope.launch { openDrawer() } },
          )
        }
      },
      snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
      bottomBar = {
        val showAssistantVoiceBar =
          currentRoute != PirateRoute.VoiceCall.route &&
            currentRoute != PirateRoute.ScheduledSessionCall.route &&
            currentRoute != PirateRoute.LiveRoom.route
        if (showAssistantVoiceBar || showBottomChrome || showMiniPlayerForMusicSubviewOnly) {
          Column {
            if (showAssistantVoiceBar) {
              AssistantVoiceBar(
                controller = voiceController,
                onOpen = {
                  navController.navigate(PirateRoute.VoiceCall.route) { launchSingleTop = true }
                },
              )
            }
            if (showBottomChrome) {
              PirateBottomBar(
                routes = routes,
                navController = navController,
                currentRoute = currentRoute,
                player = player,
                showMiniPlayer = miniPlayerVisible,
                showNavigationBar = true,
              )
            } else if (showMiniPlayerForMusicSubviewOnly) {
              PirateBottomBar(
                routes = routes,
                navController = navController,
                currentRoute = currentRoute,
                player = player,
                showMiniPlayer = true,
                showNavigationBar = false,
              )
            }
          }
        }
      },
    ) { innerPadding ->
      PirateNavHost(
        activity = activity,
        innerPadding = innerPadding,
        navController = navController,
        authState = authState,
        walletSession = walletSession,
        primaryName = primaryName,
        avatarUri = avatarUri,
        onAuthStateChange = { next ->
          authState = next
          PirateAuthUiState.save(appContext, next)
        },
        onRegister = { openAuthSheet() },
        onLogin = { openAuthSheet() },
        onLogout = { scope.launch { doLogout() } },
        player = player,
        chatService = chatService,
        assistantService = assistantService,
        voiceController = voiceController,
        scheduledSessionVoiceController = scheduledSessionVoiceController,
        onOpenDrawer = { scope.launch { openDrawer() } },
        onMusicRootViewChange = { musicRootViewActive = it },
        onShowMessage = { msg ->
          scope.launch {
            snackbarHostState.showSnackbar(
              message = msg,
              withDismissAction = true,
              duration = SnackbarDuration.Long,
            )
          }
        },
        onRefreshProfileIdentity = {
          scope.launch {
            val currentAddress = authState.activeAddress()
            if (currentAddress.isNullOrBlank()) {
              primaryName = null
              avatarUri = null
              return@launch
            }
            runCatching {
              val (name, avatar) = resolveProfileIdentityWithRetry(currentAddress, forceRefresh = true)
              primaryName = name
              avatarUri = avatar
            }
          }
        },
        onChatThreadVisibilityChange = { chatThreadOpen = it },
        onLearnSessionVisibilityChange = { learnSessionActive = it },
        miniPlayerVisible = miniPlayerVisible,
        onboardingInitialStep = onboardingInitialStep,
      )
    }
  }

  if (authSheetVisible) {
    PirateAuthSheet(
      busy = authState.busy,
      enabledMethods = availableAuthMethods,
      codeMethod = authSheetCodeMethod,
      identifier = authSheetIdentifier,
      code = authSheetCode,
      codeSent = authSheetCodeSent,
      onDismiss = {
        authSheetVisible = false
        resetAuthSheet()
      },
      onMethodSelected = { method ->
        if (method.requiresOneTimeCode) {
          authSheetCodeMethod = method
          authSheetCode = ""
          authSheetCodeSent = false
        } else {
          scope.launch {
            doAuthenticate(
              PirateAuthRequest(method = method),
            )
          }
        }
      },
      onIdentifierChange = { authSheetIdentifier = it },
      onCodeChange = { authSheetCode = it },
      onSendCode = {
        val method = authSheetCodeMethod ?: return@PirateAuthSheet
        scope.launch { doSendOneTimeCode(method = method, identifier = authSheetIdentifier) }
      },
      onSubmitCode = {
        val method = authSheetCodeMethod ?: return@PirateAuthSheet
        scope.launch {
          doAuthenticate(
            PirateAuthRequest(
              method = method,
              identifier = authSheetIdentifier,
              code = authSheetCode,
            ),
          )
        }
      },
      onBackToMethods = {
        authSheetCodeMethod = null
        authSheetCode = ""
        authSheetCodeSent = false
      },
    )
  }
}
