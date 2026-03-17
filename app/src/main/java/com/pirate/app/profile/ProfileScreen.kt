package com.pirate.app.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import com.pirate.app.music.OnChainPlaylist
import com.pirate.app.onboarding.OnboardingRpcHelpers
import com.pirate.app.tempo.SessionKeyManager
import com.pirate.app.tempo.TempoPasskeyManager
import com.pirate.app.util.shortAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
  ethAddress: String?,
  heavenName: String?,
  avatarUri: String?,
  isAuthenticated: Boolean,
  busy: Boolean,
  selfVerified: Boolean = false,
  onRegister: () -> Unit,
  onLogin: () -> Unit,
  onLogout: () -> Unit = {},
  onBack: (() -> Unit)? = null,
  onMessage: ((String) -> Unit)? = null,
  onEditProfile: (() -> Unit)? = null,
  activity: FragmentActivity? = null,
  tempoAccount: TempoPasskeyManager.PasskeyAccount? = null,
  viewerEthAddress: String? = ethAddress,
  usePublicProfileReadModel: Boolean = false,
  onPlayPublishedSong: ((PublishedSongRow) -> Unit)? = null,
  onOpenSong: ((trackId: String, title: String?, artist: String?) -> Unit)? = null,
  onOpenArtist: ((String) -> Unit)? = null,
  onViewProfile: ((String) -> Unit)? = null,
  onNavigateFollowList: ((FollowListMode, String) -> Unit)? = null,
) {
  if (!isAuthenticated || ethAddress.isNullOrBlank()) {
    ProfileAuthRequiredContent(
      busy = busy,
      onRegister = onRegister,
      onLogin = onLogin,
    )
    return
  }

  var selectedTab by remember { mutableIntStateOf(0) }
  val tabs = ProfileTab.entries
  val appContext = LocalContext.current.applicationContext
  val scope = rememberCoroutineScope()
  val hasTargetAddress = !ethAddress.isNullOrBlank()
  val isOwnProfile = remember(viewerEthAddress, ethAddress) {
    !viewerEthAddress.isNullOrBlank() &&
      !ethAddress.isNullOrBlank() &&
      viewerEthAddress.equals(ethAddress, ignoreCase = true)
  }
  val hasFollowContract = OnboardingRpcHelpers.hasFollowContract()
  val canFollow = !isOwnProfile &&
    hasTargetAddress &&
    !viewerEthAddress.isNullOrBlank() &&
    activity != null &&
    tempoAccount != null &&
    tempoAccount.address.equals(viewerEthAddress, ignoreCase = true) &&
    hasFollowContract
  val canMessage = !isOwnProfile && hasTargetAddress && onMessage != null

  // Follow counts
  var followerCount by remember { mutableIntStateOf(0) }
  var followingCount by remember { mutableIntStateOf(0) }
  var serverFollowing by remember { mutableStateOf(false) }
  var optimisticFollowing by remember { mutableStateOf<Boolean?>(null) }
  var pendingFollowTarget by remember { mutableStateOf<Boolean?>(null) }
  var followStateLoaded by remember { mutableStateOf(false) }
  var followBusy by remember { mutableStateOf(false) }
  var followError by remember { mutableStateOf<String?>(null) }

  // Scrobble state
  var scrobbles by remember { mutableStateOf<List<ScrobbleRow>>(emptyList()) }
  var scrobblesLoading by remember { mutableStateOf(true) }
  var scrobblesError by remember { mutableStateOf<String?>(null) }
  var scrobbleRetryKey by remember { mutableIntStateOf(0) }

  // Playlist state
  var playlists by remember { mutableStateOf<List<OnChainPlaylist>>(emptyList()) }
  var playlistsLoading by remember { mutableStateOf(true) }
  var playlistsError by remember { mutableStateOf<String?>(null) }

  // Published songs state
  var publishedSongs by remember { mutableStateOf<List<PublishedSongRow>>(emptyList()) }
  var publishedSongsLoading by remember { mutableStateOf(true) }
  var publishedSongsError by remember { mutableStateOf<String?>(null) }

  // Contract profile (all ProfileV2-supported fields)
  var contractProfile by remember { mutableStateOf<ContractProfileData?>(null) }
  var contractLoading by remember { mutableStateOf(true) }
  var contractError by remember { mutableStateOf<String?>(null) }
  var contractRetryKey by remember { mutableIntStateOf(0) }
  var coverRecord by remember { mutableStateOf<String?>(null) }
  var locationRecord by remember { mutableStateOf<String?>(null) }
  var schoolRecord by remember { mutableStateOf<String?>(null) }

  // Playlist detail
  var selectedPlaylist by remember { mutableStateOf<OnChainPlaylist?>(null) }

  // Settings sheet
  var showSettings by remember { mutableStateOf(false) }

  val handleText = heavenName ?: shortAddress(ethAddress, minLengthToShorten = 14)
  val profileName = contractProfile
    ?.displayName
    ?.trim()
    ?.takeIf {
      it.isNotBlank() &&
        !it.equals(handleText, ignoreCase = true) &&
        !it.equals(heavenName ?: "", ignoreCase = true)
    }
  val effectiveAvatarRef = avatarUri?.trim()?.takeIf { it.isNotEmpty() }
    ?: contractProfile?.photoUri?.trim()?.takeIf { it.isNotEmpty() }
  val effectiveCoverRef = coverRecord?.trim()?.takeIf { it.isNotEmpty() }
  val effectiveFollowing = optimisticFollowing ?: serverFollowing
  val onMessageClick: (() -> Unit)? =
    if (canMessage) {
      { onMessage?.invoke(ethAddress!!) }
    } else {
      null
    }

  fun onToggleFollow() {
    if (!canFollow) {
      followError = if (!hasFollowContract) "Follow contract not configured" else "Follow unavailable"
      return
    }
    followError = null
    val target = ethAddress ?: return
    val activeActivity = activity ?: return
    val account = tempoAccount ?: return

    val nextFollowing = !effectiveFollowing
    val previousFollowers = followerCount
    val previousFollowing = effectiveFollowing

    followBusy = true
    pendingFollowTarget = nextFollowing
    optimisticFollowing = nextFollowing
    if (nextFollowing != previousFollowing) {
      followerCount = (previousFollowers + if (nextFollowing) 1 else -1).coerceAtLeast(0)
    }

    scope.launch {
      val loadedSessionKey = SessionKeyManager.load(appContext)
      val activeSessionKey = loadedSessionKey?.takeIf {
        SessionKeyManager.isValid(it, ownerAddress = account.address) &&
          it.keyAuthorization?.isNotEmpty() == true
      }

      val result = if (nextFollowing) {
        TempoFollowContractApi.follow(
          activity = activeActivity,
          account = account,
          targetAddress = target,
          rpId = account.rpId,
          sessionKey = activeSessionKey,
        )
      } else {
        TempoFollowContractApi.unfollow(
          activity = activeActivity,
          account = account,
          targetAddress = target,
          rpId = account.rpId,
          sessionKey = activeSessionKey,
        )
      }

      if (!result.success) {
        followerCount = previousFollowers
        serverFollowing = previousFollowing
        optimisticFollowing = null
        pendingFollowTarget = null
        followError = result.error ?: "Follow transaction failed"
        followBusy = false
        followStateLoaded = true
        return@launch
      }

      serverFollowing = nextFollowing
      optimisticFollowing = null
      pendingFollowTarget = null
      val expectedFollowers = if (nextFollowing != previousFollowing) {
        (previousFollowers + if (nextFollowing) 1 else -1).coerceAtLeast(0)
      } else {
        previousFollowers
      }
      runCatching {
        var latest = expectedFollowers to followingCount
        repeat(4) { attempt ->
          val counts = OnboardingRpcHelpers.getFollowCounts(target)
          latest = counts
          val followerCountSettled =
            counts.first == expectedFollowers || counts.first != previousFollowers || expectedFollowers == previousFollowers
          if (followerCountSettled || attempt == 3) return@runCatching counts
          delay(800)
        }
        latest
      }.onSuccess { (followers, following) ->
        followerCount = followers
        followingCount = following
      }
      runCatching { OnboardingRpcHelpers.getFollowState(account.address, target) }
        .onSuccess { latestFollowing -> serverFollowing = latestFollowing }
      followBusy = false
      followStateLoaded = true
    }
  }

  ProfileScreenLaunchEffects(
    ethAddress = ethAddress,
    viewerEthAddress = viewerEthAddress,
    canFollow = canFollow,
    pendingFollowTarget = pendingFollowTarget,
    scrobbleRetryKey = scrobbleRetryKey,
    contractRetryKey = contractRetryKey,
    heavenName = heavenName,
    usePublicProfileReadModel = usePublicProfileReadModel,
    onSetFollowerCount = { followerCount = it },
    onSetFollowingCount = { followingCount = it },
    onSetFollowError = { followError = it },
    onSetServerFollowing = { serverFollowing = it },
    onSetOptimisticFollowing = { optimisticFollowing = it },
    onSetPendingFollowTarget = { pendingFollowTarget = it },
    onSetFollowStateLoaded = { followStateLoaded = it },
    onSetScrobbles = { scrobbles = it },
    onSetScrobblesLoading = { scrobblesLoading = it },
    onSetScrobblesError = { scrobblesError = it },
    onSetPlaylists = { playlists = it },
    onSetPlaylistsLoading = { playlistsLoading = it },
    onSetPlaylistsError = { playlistsError = it },
    onSetPublishedSongs = { publishedSongs = it },
    onSetPublishedSongsLoading = { publishedSongsLoading = it },
    onSetPublishedSongsError = { publishedSongsError = it },
    onSetContractProfile = { contractProfile = it },
    onSetContractLoading = { contractLoading = it },
    onSetContractError = { contractError = it },
    onSetCoverRecord = { coverRecord = it },
    onSetLocationRecord = { locationRecord = it },
    onSetSchoolRecord = { schoolRecord = it },
  )

  Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    ProfileScreenHeaderSection(
      ethAddress = ethAddress,
      handleText = handleText,
      profileName = profileName,
      effectiveAvatarRef = effectiveAvatarRef,
      effectiveCoverRef = effectiveCoverRef,
      selfVerified = selfVerified,
      isOwnProfile = isOwnProfile,
      followerCount = followerCount,
      followingCount = followingCount,
      hasTargetAddress = hasTargetAddress,
      canFollow = canFollow,
      canMessage = canMessage,
      followBusy = followBusy,
      followStateLoaded = followStateLoaded,
      pendingFollowTarget = pendingFollowTarget,
      effectiveFollowing = effectiveFollowing,
      followError = followError,
      onBack = onBack,
      onOpenSettings = { showSettings = true },
      onEditProfile = onEditProfile,
      onNavigateFollowList = onNavigateFollowList,
      onToggleFollow = ::onToggleFollow,
      onMessageClick = onMessageClick,
    )

    val activePlaylist = selectedPlaylist
    if (activePlaylist != null) {
      ProfilePlaylistDetailPanel(
        playlist = activePlaylist,
        onBack = { selectedPlaylist = null },
        onOpenSong = onOpenSong,
        onOpenArtist = onOpenArtist,
      )
    } else {
      ProfileScreenTabBar(
        selectedTab = selectedTab,
        tabs = tabs,
        onTabSelected = { selectedTab = it },
      )
      ProfileScreenTabContent(
        tab = tabs[selectedTab],
        publishedSongs = publishedSongs,
        publishedSongsLoading = publishedSongsLoading,
        publishedSongsError = publishedSongsError,
        onPlayPublishedSong = onPlayPublishedSong,
        onOpenSong = onOpenSong,
        onOpenArtist = onOpenArtist,
        playlists = playlists,
        playlistsLoading = playlistsLoading,
        playlistsError = playlistsError,
        onOpenPlaylist = { selectedPlaylist = it },
        scrobbles = scrobbles,
        scrobblesLoading = scrobblesLoading,
        scrobblesError = scrobblesError,
        contractProfile = contractProfile,
        contractLoading = contractLoading,
        contractError = contractError,
        locationRecord = locationRecord,
        schoolRecord = schoolRecord,
        isOwnProfile = isOwnProfile,
        onEditProfile = onEditProfile,
        onScrobbleRetry = { scrobbleRetryKey++ },
        onContractRetry = { contractRetryKey++ },
      )
    }
  }

  // Settings bottom sheet
  if (showSettings) {
    SettingsSheet(
      ethAddress = ethAddress,
      onDismiss = { showSettings = false },
      onLogout = onLogout,
    )
  }

}
