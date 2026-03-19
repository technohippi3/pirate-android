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
import sc.pirate.app.auth.PirateAuthUiState
import sc.pirate.app.chat.XmtpChatService
import sc.pirate.app.assistant.AgoraVoiceController
import sc.pirate.app.assistant.AssistantVoiceBar
import sc.pirate.app.assistant.AssistantService
import sc.pirate.app.assistant.VoiceCallState
import sc.pirate.app.assistant.clearWorkerAuthCache
import sc.pirate.app.schedule.ScheduledSessionVoiceController
import sc.pirate.app.scrobble.ScrobbleService
import sc.pirate.app.tempo.TempoPasskeyManager
import sc.pirate.app.post.PostStoryEnqueueSync
import sc.pirate.app.ui.PirateTopBar
import sc.pirate.app.player.PlayerController
import sc.pirate.app.onboarding.OnboardingStep
import sc.pirate.app.onboarding.checkOnboardingStatus
import sc.pirate.app.profile.TempoNameRecordsApi
import sc.pirate.app.tempo.SessionKeyManager
import sc.pirate.app.tempo.TempoAccountFactory
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
        passkeyRpId = saved.tempoRpId.ifBlank { TempoPasskeyManager.DEFAULT_RP_ID },
        tempoRpId = saved.tempoRpId.ifBlank { TempoPasskeyManager.DEFAULT_RP_ID },
      ),
    )
  }

  val scrobbleService =
    remember {
      ScrobbleService(
        appContext = appContext,
        activity = activity,
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
  val activeAddress = authState.activeAddress()

  // Profile identity — hoisted so drawer + profile screen can share
  var heavenName by remember { mutableStateOf<String?>(null) }
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
      val account =
        TempoAccountFactory.fromSession(
          tempoAddress = authState.tempoAddress,
          tempoCredentialId = authState.tempoCredentialId,
          tempoPubKeyX = authState.tempoPubKeyX,
          tempoPubKeyY = authState.tempoPubKeyY,
          tempoRpId = authState.tempoRpId.ifBlank { TempoPasskeyManager.DEFAULT_RP_ID },
        )?.takeIf { it.address.equals(normalizedAddress, ignoreCase = true) }
      if (account != null) {
        val sessionKey = SessionKeyManager.load(activity)
        if (SessionKeyManager.isValid(sessionKey, ownerAddress = account.address)) {
          val inboxId = chatService.currentInboxId()
          if (!inboxId.isNullOrBlank()) {
            runCatching {
              val publishResult =
                TempoNameRecordsApi.upsertXmtpInboxId(
                  activity = activity,
                  account = account,
                  inboxId = inboxId,
                  rpId = account.rpId,
                  sessionKey = sessionKey,
                )
              if (!publishResult.success) {
                Log.w(TAG, "XMTP inbox mapping publish failed ($source): ${publishResult.error}")
              }
            }.onFailure { err ->
              Log.w(TAG, "XMTP inbox mapping publish failed ($source): ${err.message}")
            }
          }
        }
      }
      xmtpBootstrapKey = key
    }.onFailure { err ->
      xmtpBootstrapKey = null
      Log.w(TAG, "XMTP bootstrap failed ($source): ${err.message}")
    }
  }

  LaunchedEffect(activeAddress) {
    val addr = activeAddress
    if (addr.isNullOrBlank()) {
      heavenName = null; avatarUri = null; return@LaunchedEffect
    }
    val (name, avatar) = resolveProfileIdentityWithRetry(addr, attempts = 2, retryDelayMs = 700)
    heavenName = name
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
  val miniPlayerVisible = miniPlayerTrack != null && currentRoute != PirateRoute.Home.route
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

  suspend fun doRegister() {
    navController.navigate(PirateRoute.AuthResolving.route) {
      popUpTo(navController.graph.startDestinationId) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
    authState = authState.copy(busy = true, output = "Creating Tempo passkey account...")
    val result = runCatching {
      val rpId = authState.passkeyRpId.ifBlank { authState.tempoRpId }
      TempoPasskeyManager.createAccount(
        activity = activity,
        rpId = rpId,
        rpName = "Pirate",
      )
    }

    result.onFailure { err ->
      authState = authState.copy(busy = false, output = "sign up failed: ${err.message}")
      navController.navigate(PirateRoute.Home.route) {
        popUpTo(navController.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
      }
      snackbarHostState.showSnackbar("Sign up failed: ${err.message ?: "unknown error"}")
    }

    result.onSuccess { account ->
      chatService.disconnect()
      clearWorkerAuthCache()
      xmtpBootstrapKey = null
      heavenName = null
      avatarUri = null
      val nextState =
        authState.copy(
          busy = false,
          passkeyRpId = account.rpId,
          tempoRpId = account.rpId,
          tempoAddress = account.address,
          tempoCredentialId = account.credentialId,
          tempoPubKeyX = account.pubKey.xHex,
          tempoPubKeyY = account.pubKey.yHex,
          signerType = PirateAuthUiState.SignerType.PASSKEY,
          selfVerified = false,
          output = "Tempo account ready: ${account.address.take(10)}...",
        )
      authState = nextState
      PirateAuthUiState.save(appContext, nextState)
      scope.launch { snackbarHostState.showSnackbar("Signed up with passkey.") }
      resolvePostAuthDestination(
        account.address,
        alreadyResolving = true,
        onboardingOverride = OnboardingStep.NAME,
      )
    }
  }

  suspend fun doLogin() {
    navController.navigate(PirateRoute.AuthResolving.route) {
      popUpTo(navController.graph.startDestinationId) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
    authState = authState.copy(busy = true, output = "Authenticating with Tempo passkey...")
    val result = runCatching {
      val rpId = authState.passkeyRpId.ifBlank { authState.tempoRpId }
      TempoPasskeyManager.login(
        activity = activity,
        rpId = rpId,
      )
    }

    result.onFailure { err ->
      Log.e(TAG, "Passkey login failed", err)
      authState = authState.copy(busy = false, output = "login failed: ${err.message}")
      navController.navigate(PirateRoute.Home.route) {
        popUpTo(navController.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
      }
      val reason = err.message ?: "unknown error"
      val message =
        if (reason.contains("not registered in this app", ignoreCase = true)) {
          "Selected passkey is not registered on this device yet. Use Sign Up to register it."
        } else if (
          reason.contains(
            "No local passkey account found and remote recovery failed",
            ignoreCase = true,
          )
        ) {
          "This passkey has no account metadata on this device or key manager. If this is an older passkey, sign in once on a device that still has it cached to back it up, or use Sign Up to create a recoverable passkey account."
        } else {
          "Log in failed: $reason"
        }
      snackbarHostState.showSnackbar(message)
    }

    result.onSuccess { account ->
      chatService.disconnect()
      clearWorkerAuthCache()
      xmtpBootstrapKey = null
      heavenName = null
      avatarUri = null
      val nextState =
        authState.copy(
          busy = false,
          passkeyRpId = account.rpId,
          tempoRpId = account.rpId,
          tempoAddress = account.address,
          tempoCredentialId = account.credentialId,
          tempoPubKeyX = account.pubKey.xHex,
          tempoPubKeyY = account.pubKey.yHex,
          signerType = PirateAuthUiState.SignerType.PASSKEY,
          selfVerified = false,
          output = "Logged in: ${account.address.take(10)}...",
        )
      authState = nextState
      PirateAuthUiState.save(appContext, nextState)
      scope.launch { snackbarHostState.showSnackbar("Logged in with passkey.") }
      resolvePostAuthDestination(account.address, alreadyResolving = true)
    }
  }

  suspend fun doLogout() {
    chatService.disconnect()
    clearWorkerAuthCache()
    xmtpBootstrapKey = null
    heavenName = null
    avatarUri = null
    SessionKeyManager.clear(appContext)

    PirateAuthUiState.clear(appContext)
    authState =
      authState.copy(
        tempoAddress = null,
        tempoCredentialId = null,
        tempoPubKeyX = null,
        tempoPubKeyY = null,
        signerType = null,
        selfVerified = false,
        output = "Logged out.",
      )
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
        heavenName = heavenName,
        avatarUri = avatarUri,
        selfVerified = authState.selfVerified,
        onRegister = { doRegister() },
        onLogin = { doLogin() },
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
            heavenName = heavenName,
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
        heavenName = heavenName,
        avatarUri = avatarUri,
        onAuthStateChange = { next ->
          authState = next
          PirateAuthUiState.save(appContext, next)
        },
        onRegister = { scope.launch { doRegister() } },
        onLogin = { scope.launch { doLogin() } },
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
              heavenName = null
              avatarUri = null
              return@launch
            }
            runCatching {
              val (name, avatar) = resolveProfileIdentityWithRetry(currentAddress, forceRefresh = true)
              heavenName = name
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
}
