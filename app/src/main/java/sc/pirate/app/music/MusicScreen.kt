package sc.pirate.app.music
import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import sc.pirate.app.player.PlayerController
import sc.pirate.app.scrobble.SpotifyNotificationAccess
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun MusicScreen(
  player: PlayerController,
  ownerEthAddress: String?,
  heavenName: String?,
  avatarUri: String?,
  isAuthenticated: Boolean,
  onShowMessage: (String) -> Unit,
  onOpenPlayer: () -> Unit,
  onOpenDrawer: () -> Unit,
  onOpenLiveRoom: (LiveRoomCardModel) -> Unit,
  onRootViewChange: (Boolean) -> Unit = {},
  onOpenSongPage: ((trackId: String, title: String?, artist: String?) -> Unit)? = null,
  onOpenArtistPage: ((artistName: String) -> Unit)? = null,
  hostActivity: androidx.fragment.app.FragmentActivity? = null,
  tempoAccount: sc.pirate.app.tempo.TempoPasskeyManager.PasskeyAccount? = null,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val initialCachedTracks =
    remember(context) {
      val cached = MusicScreenCache.tracks
      if (cached.isNotEmpty()) {
        cached
      } else {
        MusicLibrary.loadCachedTracksBlocking(context).also { restored ->
          if (restored.isNotEmpty()) {
            MusicScreenCache.tracks = restored
          }
        }
      }
    }
  val currentTrack by player.currentTrack.collectAsState()
  val isPlaying by player.isPlaying.collectAsState()
  var view by rememberSaveable { mutableStateOf(MusicView.Home) }
  LaunchedEffect(view) {
    onRootViewChange(view == MusicView.Home)
  }
  BackHandler(enabled = view != MusicView.Home) {
    view =
      when (view) {
        MusicView.PlaylistDetail -> MusicView.Playlists
        MusicView.SharedPlaylistDetail -> MusicView.Shared
        else -> MusicView.Home
      }
  }
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var recentPublishedReleases by remember { mutableStateOf(MusicScreenCache.recentPublishedReleases) }
  var recentPublishedReleasesLoading by remember { mutableStateOf(MusicScreenCache.recentPublishedReleases.isEmpty()) }
  var recentPublishedReleasesError by remember { mutableStateOf(MusicScreenCache.recentPublishedReleasesError) }
  var recentPublishedLastFetchAtMs by remember { mutableStateOf(MusicScreenCache.recentPublishedLastFetchAtMs) }
  var liveRooms by remember { mutableStateOf(MusicScreenCache.liveRooms) }
  var liveRoomsLoading by remember { mutableStateOf(MusicScreenCache.liveRooms.isEmpty()) }
  var liveRoomsError by remember { mutableStateOf(MusicScreenCache.liveRoomsError) }
  var liveRoomsLastFetchAtMs by remember { mutableStateOf(MusicScreenCache.liveRoomsLastFetchAtMs) }
  var homeRefreshNonce by remember { mutableStateOf(0) }
  val permission = if (Build.VERSION.SDK_INT >= 33) {
    Manifest.permission.READ_MEDIA_AUDIO
  } else {
    @Suppress("DEPRECATION")
    Manifest.permission.READ_EXTERNAL_STORAGE
  }
  fun computeHasPermission(): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
  var hasPermission by remember { mutableStateOf(computeHasPermission()) }
  var hasSpotifyNotificationAccess by remember { mutableStateOf(SpotifyNotificationAccess.hasNotificationAccess(context)) }
  val lifecycleOwner = LocalLifecycleOwner.current
  var tracks by remember { mutableStateOf(initialCachedTracks) }
  var scanning by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf(MusicScreenCache.libraryError) }
  var playlistsLoading by remember { mutableStateOf(false) }
  var localPlaylists by remember { mutableStateOf(MusicScreenCache.localPlaylists) }
  var onChainPlaylists by remember { mutableStateOf(MusicScreenCache.onChainPlaylists) }
  val optimisticOnChainTrackCounts =
    remember {
      mutableStateMapOf<String, Int>().apply {
        putAll(MusicScreenCache.optimisticOnChainTrackCounts)
      }
    }
  var selectedPlaylist by remember { mutableStateOf<PlaylistDisplayItem?>(null) }
  var selectedPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
  var playlistDetailLoading by remember { mutableStateOf(false) }
  var playlistDetailError by remember {
    mutableStateOf(MusicScreenCache.getPlaylistDetail(selectedPlaylistId)?.error)
  }
  var playlistDetailTracks by remember {
    mutableStateOf(MusicScreenCache.getPlaylistDetail(selectedPlaylistId)?.tracks ?: emptyList())
  }
  var sharedLoading by remember { mutableStateOf(false) }
  var sharedError by remember { mutableStateOf<String?>(null) }
  var sharedPlaylists by remember { mutableStateOf(SharedWithYouCache.playlists) }
  var sharedTracks by remember { mutableStateOf(SharedWithYouCache.tracks) }
  var sharedSelectedPlaylist by remember { mutableStateOf(MusicScreenCache.sharedSelectedPlaylist) }
  var sharedPlaylistLoading by remember { mutableStateOf(false) }
  var sharedPlaylistRefreshing by remember { mutableStateOf(false) }
  var sharedPlaylistError by remember { mutableStateOf(MusicScreenCache.sharedPlaylistError) }
  var sharedPlaylistTracks by remember {
    mutableStateOf(
      MusicScreenCache.sharedPlaylistKey?.let { key ->
        SharedWithYouCache.getPlaylistTracks(key)?.second
      } ?: emptyList(),
    )
  }
  var purchasedCloudTracks by remember { mutableStateOf(MusicScreenCache.purchasedCloudTracks) }
  var sharedPlaylistKey by remember { mutableStateOf(MusicScreenCache.sharedPlaylistKey) }
  var sharedPlaylistMenuOpen by remember { mutableStateOf(false) }
  var cloudPlayBusy by remember { mutableStateOf(false) }
  var cloudPlayLabel by remember { mutableStateOf<String?>(null) }
  var trackMenuOpen by remember { mutableStateOf(false) }
  var selectedTrack by remember { mutableStateOf<MusicTrack?>(null) }
  var addToPlaylistOpen by remember { mutableStateOf(false) }
  var createPlaylistOpen by remember { mutableStateOf(false) }
  var shareTrack by remember { mutableStateOf<MusicTrack?>(null) }
  var uploadBusy by remember { mutableStateOf(false) }
  var turboCreditsSheetOpen by remember { mutableStateOf(false) }
  var turboCreditsSheetMessage by remember { mutableStateOf(TURBO_CREDITS_COPY) }
  var autoSyncJob by remember { mutableStateOf<Job?>(null) }
  var sharedLastFetchAtMs by remember { mutableStateOf(SharedWithYouCache.lastFetchAtMs) }
  val sharedOwnerLabels =
    remember {
      mutableStateMapOf<String, String>().apply {
        putAll(MusicScreenCache.sharedOwnerLabels)
      }
    }
  var sharedSeenItemIds by remember { mutableStateOf(MusicScreenCache.sharedSeenItemIds) }
  var downloadedTracksByContentId by remember { mutableStateOf(MusicScreenCache.downloadedTracksByContentId) }
  val sharedItemIds = remember(sharedPlaylists, sharedTracks) { computeSharedItemIds(sharedPlaylists, sharedTracks) }
  val sharedUnreadCount = remember(sharedItemIds, sharedSeenItemIds) { sharedItemIds.count { !sharedSeenItemIds.contains(it) } }

  LaunchedEffect(tracks, error) {
    MusicScreenCache.tracks = tracks
    MusicScreenCache.libraryError = error
  }

  LaunchedEffect(downloadedTracksByContentId) {
    MusicScreenCache.downloadedTracksByContentId = downloadedTracksByContentId
  }

  LaunchedEffect(localPlaylists, onChainPlaylists) {
    MusicScreenCache.localPlaylists = localPlaylists
    MusicScreenCache.onChainPlaylists = onChainPlaylists
  }

  LaunchedEffect(purchasedCloudTracks) {
    MusicScreenCache.purchasedCloudTracks = purchasedCloudTracks
  }

  LaunchedEffect(sharedSeenItemIds) {
    MusicScreenCache.sharedSeenItemIds = sharedSeenItemIds
  }

  LaunchedEffect(sharedOwnerLabels.toMap()) {
    MusicScreenCache.sharedOwnerLabels = sharedOwnerLabels.toMap()
  }

  LaunchedEffect(optimisticOnChainTrackCounts.toMap()) {
    MusicScreenCache.optimisticOnChainTrackCounts = optimisticOnChainTrackCounts.toMap()
  }

  LaunchedEffect(sharedSelectedPlaylist, sharedPlaylistKey, sharedPlaylistError) {
    MusicScreenCache.sharedSelectedPlaylist = sharedSelectedPlaylist
    MusicScreenCache.sharedPlaylistKey = sharedPlaylistKey
    MusicScreenCache.sharedPlaylistError = sharedPlaylistError
  }

  LaunchedEffect(selectedPlaylistId) {
    if (playlistDetailTracks.isNotEmpty()) return@LaunchedEffect
    val cached = MusicScreenCache.getPlaylistDetail(selectedPlaylistId) ?: return@LaunchedEffect
    playlistDetailTracks = cached.tracks
    playlistDetailError = cached.error
  }

  LaunchedEffect(selectedPlaylistId, playlistDetailTracks, playlistDetailError) {
    val playlistId = selectedPlaylistId
    if (playlistId.isNullOrBlank()) return@LaunchedEffect
    MusicScreenCache.putPlaylistDetail(
      playlistId = playlistId,
      result =
        PlaylistDetailLoadResult(
          tracks = playlistDetailTracks,
          error = playlistDetailError,
        ),
    )
  }

  suspend fun runLibraryScan(
    silent: Boolean,
    force: Boolean = false,
  ) {
    if (silent && !force && !MusicScreenCache.shouldRefreshLibrary(hasData = tracks.isNotEmpty())) {
      return
    }
    runScan(
      context = context,
      onShowMessage = onShowMessage,
      silent = silent,
      setScanning = { scanning = it },
      setTracks = {
        tracks = it
        MusicScreenCache.tracks = it
      },
      setError = {
        error = it
        MusicScreenCache.libraryError = it
      },
    )
    if (error == null) {
      MusicScreenCache.lastLibraryScanAtMs = System.currentTimeMillis()
    }
  }

  suspend fun downloadSharedTrackToDevice(
    t: SharedCloudTrack,
    notify: Boolean = true,
  ): Boolean {
    return downloadSharedTrackToDeviceWithUi(
      context = context,
      track = t,
      notify = notify,
      isAuthenticated = isAuthenticated,
      ownerEthAddress = ownerEthAddress,
      downloadedEntries = downloadedTracksByContentId,
      cloudPlayBusy = cloudPlayBusy,
      onSetCloudPlayBusy = { cloudPlayBusy = it },
      onSetCloudPlayLabel = { cloudPlayLabel = it },
      onSetDownloadedEntries = { downloadedTracksByContentId = it },
      onShowMessage = onShowMessage,
      onRunScan = { runLibraryScan(silent = true, force = true) },
    )
  }
  val requestPermission = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
    onResult = { ok ->
      hasPermission = ok
      if (ok) {
        scope.launch { runLibraryScan(silent = true, force = true) }
      }
    },
  )
  DisposableEffect(lifecycleOwner, context) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
          hasPermission = computeHasPermission()
          hasSpotifyNotificationAccess = SpotifyNotificationAccess.hasNotificationAccess(context)
          if (view == MusicView.Home) {
            homeRefreshNonce += 1
          }
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  fun openSpotifyNotificationAccessSettings() {
    val activity = hostActivity ?: (context as? Activity)
    if (activity == null) {
      onShowMessage("Open Notification Access settings to enable Spotify scrobbling.")
      return
    }
    SpotifyNotificationAccess.openNotificationAccessSettings(activity)
  }

  suspend fun loadPlaylists(force: Boolean = false) {
    val hasData = localPlaylists.isNotEmpty() || onChainPlaylists.isNotEmpty()
    if (!force && !MusicScreenCache.shouldRefreshPlaylists(hasData = hasData)) {
      return
    }
    playlistsLoading = true
    val result = loadPlaylistsData(
      context = context,
      isAuthenticated = isAuthenticated,
      ownerEthAddress = ownerEthAddress,
    )
    localPlaylists = result.localPlaylists
    onChainPlaylists = result.onChainPlaylists
    val onChainCountsById =
      result.onChainPlaylists.associate { playlist ->
        playlist.id.trim().lowercase() to playlist.trackCount
      }
    for ((playlistId, optimisticCount) in optimisticOnChainTrackCounts.toMap()) {
      val onChainCount = onChainCountsById[playlistId] ?: continue
      if (onChainCount >= optimisticCount) {
        optimisticOnChainTrackCounts.remove(playlistId)
      }
    }
    MusicScreenCache.localPlaylists = result.localPlaylists
    MusicScreenCache.onChainPlaylists = result.onChainPlaylists
    MusicScreenCache.lastPlaylistsLoadAtMs = System.currentTimeMillis()
    playlistsLoading = false
  }

  suspend fun loadPlaylistDetail(playlist: PlaylistDisplayItem) {
    MusicScreenCache.getPlaylistDetail(playlist.id)?.let { cached ->
      playlistDetailTracks = cached.tracks
      playlistDetailError = cached.error
      if (cached.tracks.isNotEmpty()) return
    }
    playlistDetailLoading = true
    playlistDetailError = null
    val result = loadPlaylistDetailTracks(
      playlist = playlist,
      localPlaylists = localPlaylists,
      libraryTracks = tracks,
    )
    playlistDetailTracks = result.tracks
    playlistDetailError = result.error
    MusicScreenCache.putPlaylistDetail(playlist.id, result)
    playlistDetailLoading = false
  }

  suspend fun loadShared(force: Boolean) {
    refreshSharedLibraryWithUi(
      force = force,
      isAuthenticated = isAuthenticated,
      ownerEthAddress = ownerEthAddress,
      sharedLoading = sharedLoading,
      sharedPlaylists = sharedPlaylists,
      sharedTracks = sharedTracks,
      sharedLastFetchAtMs = sharedLastFetchAtMs,
      ttlMs = SHARED_REFRESH_TTL_MS,
      onSetSharedError = { sharedError = it },
      onSetSharedLoading = { sharedLoading = it },
      onSetSharedPlaylists = { sharedPlaylists = it },
      onSetSharedTracks = { sharedTracks = it },
      onSetSharedLastFetchAtMs = { sharedLastFetchAtMs = it },
    )
  }

  suspend fun loadPurchasedCloudLibrary(force: Boolean = false) {
    if (!isAuthenticated || ownerEthAddress.isNullOrBlank()) {
      purchasedCloudTracks = emptyList()
      MusicScreenCache.purchasedCloudTracks = emptyList()
      MusicScreenCache.lastPurchasedLibraryLoadAtMs = 0L
      return
    }
    if (!force && !MusicScreenCache.shouldRefreshPurchasedLibrary(hasData = purchasedCloudTracks.isNotEmpty())) {
      return
    }
    purchasedCloudTracks =
      runCatching {
        fetchPurchasedCloudLibraryTracks(ownerEthAddress = ownerEthAddress.orEmpty())
      }.getOrElse { emptyList() }
    MusicScreenCache.purchasedCloudTracks = purchasedCloudTracks
    MusicScreenCache.lastPurchasedLibraryLoadAtMs = System.currentTimeMillis()
  }

  suspend fun loadSharedPlaylistTracks(share: PlaylistShareEntry, force: Boolean) {
    refreshSharedPlaylistTracksWithUi(
      share = share,
      force = force,
      sharedPlaylistLoading = sharedPlaylistLoading,
      sharedPlaylistRefreshing = sharedPlaylistRefreshing,
      ttlMs = SHARED_REFRESH_TTL_MS,
      currentSharedPlaylistKey = { sharedPlaylistKey },
      onSetSharedPlaylistError = { sharedPlaylistError = it },
      onSetSharedPlaylistKey = { sharedPlaylistKey = it },
      onSetSharedPlaylistTracks = { sharedPlaylistTracks = it },
      onSetSharedPlaylistLoading = { sharedPlaylistLoading = it },
      onSetSharedPlaylistRefreshing = { sharedPlaylistRefreshing = it },
    )
  }
  val displayPlaylists =
    remember(localPlaylists, onChainPlaylists, optimisticOnChainTrackCounts.toMap()) {
      val optimisticCounts = optimisticOnChainTrackCounts.toMap()
      toDisplayItems(localPlaylists, onChainPlaylists).map { playlist ->
        if (playlist.isLocal) {
          playlist
        } else {
          val optimisticCount = optimisticCounts[playlist.id.trim().lowercase()]
          if (optimisticCount != null && optimisticCount > playlist.trackCount) {
            playlist.copy(trackCount = optimisticCount)
          } else {
            playlist
          }
        }
      }
    }
  val libraryTracks =
    remember(tracks, sharedTracks, purchasedCloudTracks, downloadedTracksByContentId) {
      mergeLibraryTracks(
        localTracks = tracks,
        sharedTracks = sharedTracks,
        purchasedTracks = purchasedCloudTracks,
        downloadedTracksByContentId = downloadedTracksByContentId,
      )
    }
  val purchaseIdsByTrackId =
    remember(purchasedCloudTracks) {
      val out = LinkedHashMap<String, String>(purchasedCloudTracks.size)
      for (track in purchasedCloudTracks) {
        val canonicalTrackId = CanonicalTrackId.parse(track.canonicalTrackId)?.value
          ?: CanonicalTrackId.parse(track.id)?.value
          ?: continue
        val purchaseId = track.purchaseId?.trim().orEmpty()
        if (purchaseId.isBlank()) continue
        out[canonicalTrackId.lowercase()] = purchaseId
      }
      out
    }
  MusicScreenLaunchEffects(
    context = context,
    newReleasesMax = HOME_NEW_RELEASES_MAX,
    view = view,
    ownerEthAddress = ownerEthAddress,
    isAuthenticated = isAuthenticated,
    hasPermission = hasPermission,
    sharedSelectedPlaylist = sharedSelectedPlaylist,
    sharedPlaylists = sharedPlaylists,
    sharedItemIds = sharedItemIds,
    sharedSeenItemIds = sharedSeenItemIds,
    sharedOwnerLabels = sharedOwnerLabels,
    onSetTracks = {
      tracks = it
      MusicScreenCache.tracks = it
    },
    onSetDownloadedTracksByContentId = {
      downloadedTracksByContentId = it
      MusicScreenCache.downloadedTracksByContentId = it
    },
    onSetSharedSeenItemIds = {
      sharedSeenItemIds = it
      MusicScreenCache.sharedSeenItemIds = it
    },
    onSetRecentPublishedReleases = {
      recentPublishedReleases = it
      MusicScreenCache.recentPublishedReleases = it
    },
    onSetRecentPublishedReleasesLoading = { recentPublishedReleasesLoading = it },
    onSetRecentPublishedReleasesError = {
      recentPublishedReleasesError = it
      MusicScreenCache.recentPublishedReleasesError = it
    },
    recentPublishedReleases = recentPublishedReleases,
    recentPublishedLastFetchAtMs = recentPublishedLastFetchAtMs,
    homeRefreshNonce = homeRefreshNonce,
    onSetRecentPublishedLastFetchAtMs = {
      recentPublishedLastFetchAtMs = it
      MusicScreenCache.recentPublishedLastFetchAtMs = it
    },
    onSetLiveRooms = {
      liveRooms = it
      MusicScreenCache.liveRooms = it
    },
    onSetLiveRoomsLoading = { liveRoomsLoading = it },
    onSetLiveRoomsError = {
      liveRoomsError = it
      MusicScreenCache.liveRoomsError = it
    },
    liveRooms = liveRooms,
    liveRoomsLastFetchAtMs = liveRoomsLastFetchAtMs,
    onSetLiveRoomsLastFetchAtMs = {
      liveRoomsLastFetchAtMs = it
      MusicScreenCache.liveRoomsLastFetchAtMs = it
    },
    onLoadPlaylists = { loadPlaylists() },
    onLoadShared = { force -> loadShared(force) },
    onLoadPurchasedCloudLibrary = { loadPurchasedCloudLibrary() },
    onLoadSharedPlaylistTracks = { share, force ->
      sharedSelectedPlaylist = share
      MusicScreenCache.sharedSelectedPlaylist = share
      loadSharedPlaylistTracks(share, force)
    },
    onRunSilentScan = { runLibraryScan(silent = true) },
  )
  MusicScreenMediaObserverEffect(
    context = context,
    scope = scope,
    hasPermission = hasPermission,
    autoSyncJob = autoSyncJob,
    onSetAutoSyncJob = { autoSyncJob = it },
    onRunSilentScan = {
      runLibraryScan(silent = true, force = true)
    },
  )
  MusicScreenRouteHost(
    view = view,
    onViewChange = { view = it },
    sharedPlaylists = sharedPlaylists,
    sharedTracks = sharedTracks,
    sharedUnreadCount = sharedUnreadCount,
    displayPlaylists = displayPlaylists,
    newReleases = mergedNewReleases(recentPublishedReleases),
    newReleasesLoading = recentPublishedReleasesLoading,
    newReleasesError = recentPublishedReleasesError,
    liveRooms = liveRooms,
    liveRoomsLoading = liveRoomsLoading,
    liveRoomsError = liveRoomsError,
    onOpenDrawer = onOpenDrawer,
    onShowMessage = onShowMessage,
    onPlayRelease = { release ->
      scope.launch {
        playNewReleaseWithUi(
          context = context,
          release = release,
          player = player,
          ownerEthAddress = ownerEthAddress,
          purchaseIdByCanonicalTrackId = purchaseIdsByTrackId,
          downloadedTracksByContentId = downloadedTracksByContentId,
          onSetDownloadedTracksByContentId = { downloadedTracksByContentId = it },
          onOpenPlayer = onOpenPlayer,
          onShowMessage = onShowMessage,
          hostActivity = hostActivity,
          tempoAccount = tempoAccount,
        )
      }
    },
    onOpenRoom = { room ->
      onOpenLiveRoom(room)
    },
    player = player,
    currentTrackId = currentTrack?.id,
    currentTrackUri = currentTrack?.uri,
    isPlaying = isPlaying,
    onOpenPlayer = onOpenPlayer,
    hasPermission = hasPermission,
    tracks = libraryTracks,
    scanning = scanning,
    libraryError = error,
    onRequestPermission = { requestPermission.launch(permission) },
    showSpotifyAccessPrompt = isAuthenticated && !hasSpotifyNotificationAccess,
    onOpenSpotifyAccessSettings = { openSpotifyNotificationAccessSettings() },
    onScan = {
      scope.launch {
        runLibraryScan(silent = false)
      }
    },
    onOpenTrackMenu = { track ->
      selectedTrack = track
      trackMenuOpen = true
    },
    searchQuery = searchQuery,
    onSearchQueryChange = { searchQuery = it },
    sharedLoading = sharedLoading,
    sharedError = sharedError,
    isAuthenticated = isAuthenticated,
    ownerEthAddress = ownerEthAddress,
    heavenName = heavenName,
    avatarUri = avatarUri,
    ownerLabelFor = { owner -> sharedOwnerLabel(ownerAddress = owner, sharedOwnerLabels = sharedOwnerLabels) },
    onRefreshShared = { scope.launch { loadShared(force = true) } },
    onOpenSharedPlaylist = { playlist ->
      sharedSelectedPlaylist = playlist
      view = MusicView.SharedPlaylistDetail
    },
    onPlaySharedTrack = { track ->
      scope.launch {
        playSharedCloudTrackWithUi(
          context = context,
          ownerEthAddress = ownerEthAddress,
          track = track,
          isAuthenticated = isAuthenticated,
          downloadedEntries = downloadedTracksByContentId,
          cloudPlayBusy = cloudPlayBusy,
          onSetCloudPlayBusy = { cloudPlayBusy = it },
          onSetCloudPlayLabel = { cloudPlayLabel = it },
          onSetDownloadedEntries = { downloadedTracksByContentId = it },
          onShowMessage = onShowMessage,
          onPlayTrack = { selected ->
            player.playTrack(selected, listOf(selected))
            onOpenPlayer()
          },
        )
      }
    },
    onDownloadSharedTrack = { track -> scope.launch { downloadSharedTrackToDevice(track, notify = true) } },
    playlistsLoading = playlistsLoading,
    onRefreshPlaylists = { scope.launch { loadPlaylists(force = true) } },
    onCreatePlaylist = { createPlaylistOpen = true },
    onOpenPlaylist = { playlist ->
      openPlaylistDetailWithUi(
        playlist = playlist,
        onSetSelectedPlaylist = { selectedPlaylist = it },
        onSetSelectedPlaylistId = { selectedPlaylistId = it },
        onSetView = { view = it },
        onLoadPlaylistDetail = { selected ->
          loadPlaylistDetail(selected)
        },
        scope = scope,
      )
    },
    selectedPlaylist = selectedPlaylist,
    selectedPlaylistId = selectedPlaylistId,
    onSelectedPlaylistChange = { selectedPlaylist = it },
    playlistDetailLoading = playlistDetailLoading,
    playlistDetailError = playlistDetailError,
    playlistDetailTracks = playlistDetailTracks,
    onLoadPlaylistDetail = { playlist -> loadPlaylistDetail(playlist) },
    onChangePlaylistCover = { playlist, coverUri ->
      changePlaylistCoverWithUi(
        context = context,
        hostActivity = hostActivity,
        playlist = playlist,
        coverUri = coverUri,
        ownerEthAddress = ownerEthAddress,
        isAuthenticated = isAuthenticated,
        tempoAccount = tempoAccount,
        onChainPlaylists = onChainPlaylists,
        selectedPlaylistId = selectedPlaylistId,
        selectedPlaylist = selectedPlaylist,
        onSetOnChainPlaylists = { onChainPlaylists = it },
        onSetSelectedPlaylist = { selectedPlaylist = it },
        onShowMessage = onShowMessage,
      )
    },
    onDeletePlaylist = { playlist ->
      deletePlaylistWithUi(
        context = context,
        hostActivity = hostActivity,
        playlist = playlist,
        ownerEthAddress = ownerEthAddress,
        isAuthenticated = isAuthenticated,
        tempoAccount = tempoAccount,
        onChainPlaylists = onChainPlaylists,
        selectedPlaylistId = selectedPlaylistId,
        onSetOnChainPlaylists = { onChainPlaylists = it },
        onSelectedPlaylistDeleted = {
          MusicScreenCache.removePlaylistDetail(selectedPlaylistId)
          selectedPlaylist = null
          selectedPlaylistId = null
          playlistDetailTracks = emptyList()
          playlistDetailError = null
          playlistDetailLoading = false
          view = MusicView.Playlists
        },
        onShowMessage = onShowMessage,
      )
    },
    sharedSelectedPlaylist = sharedSelectedPlaylist,
    sharedPlaylistMenuOpen = sharedPlaylistMenuOpen,
    onSharedPlaylistMenuOpenChange = { sharedPlaylistMenuOpen = it },
    sharedPlaylistTracks = sharedPlaylistTracks,
    sharedPlaylistLoading = sharedPlaylistLoading,
    sharedPlaylistRefreshing = sharedPlaylistRefreshing,
    sharedPlaylistError = sharedPlaylistError,
    sharedByLabel = sharedSelectedPlaylist?.let { sharedOwnerLabel(ownerAddress = it.owner, sharedOwnerLabels = sharedOwnerLabels) },
    onRefreshSharedPlaylist = { share -> scope.launch { loadSharedPlaylistTracks(share, force = true) } },
    onDownloadAllSharedPlaylist = {
      downloadAllSharedPlaylistTracksWithUi(
        tracks = sharedPlaylistTracks,
        onSetCloudPlayLabel = { cloudPlayLabel = it },
        onDownloadTrack = { track ->
          downloadSharedTrackToDevice(track, notify = false)
        },
        onShowMessage = onShowMessage,
      )
    },
    cloudPlayBusy = cloudPlayBusy,
    cloudPlayLabel = cloudPlayLabel,
    onResolveTrackForPlayback = { track ->
      val downloadKey = downloadLookupIdForTrack(track)?.trim()?.lowercase().orEmpty()
      if (downloadKey.isNotBlank()) {
        val downloaded = downloadedTracksByContentId[downloadKey]
        if (downloaded != null) {
          val exists = runCatching { MediaStoreAudioDownloads.uriExists(context, downloaded.mediaUri) }.getOrDefault(false)
          if (exists) {
            return@MusicScreenRouteHost PlaybackResolveResult(
              track =
                track.copy(
                  uri = downloaded.mediaUri,
                  filename = downloaded.filename.ifBlank { track.filename },
                  isPreviewOnly = false,
                ),
              message = null,
            )
          }
          downloadedTracksByContentId = DownloadedTracksStore.remove(context, downloadKey)
        }
      }
      resolvePlayableTrackForUi(
        track = track,
        context = context,
        ownerEthAddress = ownerEthAddress,
        purchaseIdsByTrackId = purchaseIdsByTrackId,
        activity = hostActivity,
        tempoAccount = tempoAccount,
      )
    },
  )
  MusicScreenOverlayHost(
    trackMenuOpen = trackMenuOpen,
    selectedTrack = selectedTrack,
    ownerEthAddress = ownerEthAddress,
    isAuthenticated = isAuthenticated,
    hostActivity = hostActivity,
    tempoAccount = tempoAccount,
    tracks = tracks,
    downloadedTracksByContentId = downloadedTracksByContentId,
    uploadBusy = uploadBusy,
    turboCreditsCopy = TURBO_CREDITS_COPY,
    onUploadBusyChange = { uploadBusy = it },
    onTracksChange = { tracks = it },
    onOpenShare = { shareTrack = it },
    onOpenAddToPlaylist = {
      selectedTrack = it
      addToPlaylistOpen = true
    },
    onCloseTrackMenu = { trackMenuOpen = false },
    onPromptTurboTopUp = {
      turboCreditsSheetMessage = it
      turboCreditsSheetOpen = true
    },
    onShowMessage = onShowMessage,
    onRescanAfterDownload = {
      runLibraryScan(silent = true, force = true)
    },
    onOpenSongPage = onOpenSongPage,
    onOpenArtistPage = onOpenArtistPage,
    createPlaylistOpen = createPlaylistOpen,
    addToPlaylistOpen = addToPlaylistOpen,
    onCreatePlaylistOpenChange = { createPlaylistOpen = it },
    onAddToPlaylistOpenChange = { addToPlaylistOpen = it },
    onCreatePlaylistSuccess = { playlistId, successMessage ->
      scope.launch {
        handleCreatePlaylistSuccessWithUi(
          playlistId = playlistId,
          successMessage = successMessage,
          onLoadPlaylists = { loadPlaylists(force = true) },
          currentDisplayPlaylists = { toDisplayItems(localPlaylists, onChainPlaylists) },
          onSetSelectedPlaylistId = { selectedPlaylistId = it },
          onSetSelectedPlaylist = { selectedPlaylist = it },
          onSetView = { view = it },
          onLoadPlaylistDetail = { selected ->
            loadPlaylistDetail(selected)
          },
          onShowMessage = onShowMessage,
        )
      }
    },
    onAddToPlaylistSuccess = { playlistId, trackAdded ->
      if (trackAdded && playlistId.startsWith("0x", ignoreCase = true)) {
        val key = playlistId.trim().lowercase()
        val currentCount = displayPlaylists.firstOrNull { it.id.equals(playlistId, ignoreCase = true) }?.trackCount ?: 0
        val optimisticCount = (currentCount + 1).coerceAtLeast(1)
        val existingCount = optimisticOnChainTrackCounts[key] ?: 0
        if (optimisticCount > existingCount) {
          optimisticOnChainTrackCounts[key] = optimisticCount
        }
      }
      scope.launch {
        handleAddToPlaylistSuccessWithUi(
          playlistId = playlistId,
          currentView = view,
          currentDisplayPlaylists = { displayPlaylists },
          onLoadPlaylists = { loadPlaylists(force = true) },
          onSetSelectedPlaylistId = { selectedPlaylistId = it },
          onSetSelectedPlaylist = { selectedPlaylist = it },
          selectedPlaylistId = selectedPlaylistId,
          onLoadPlaylistDetail = { selected ->
            loadPlaylistDetail(selected)
          },
        )
      }
    },
    shareTrack = shareTrack,
    onDismissShare = { shareTrack = null },
    turboCreditsSheetOpen = turboCreditsSheetOpen,
    turboCreditsSheetMessage = turboCreditsSheetMessage,
    onDismissTurboCredits = { turboCreditsSheetOpen = false },
    onGetTurboCredits = {
      turboCreditsSheetOpen = false
      openTurboTopUpUrl(
        context = context,
        onShowMessage = onShowMessage,
      )
    },
  )
}
