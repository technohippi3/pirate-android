package sc.pirate.app.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import sc.pirate.app.fetchPublicProfileReadModel
import sc.pirate.app.music.OnChainPlaylist
import sc.pirate.app.music.OnChainPlaylistsApi
import sc.pirate.app.onboarding.OnboardingRpcHelpers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ProfileScreenLaunchEffects(
  ethAddress: String,
  viewerEthAddress: String?,
  canFollow: Boolean,
  pendingFollowTarget: Boolean?,
  scrobbleRetryKey: Int,
  contractRetryKey: Int,
  heavenName: String?,
  usePublicProfileReadModel: Boolean,
  onSetFollowerCount: (Int) -> Unit,
  onSetFollowingCount: (Int) -> Unit,
  onSetFollowError: (String?) -> Unit,
  onSetServerFollowing: (Boolean) -> Unit,
  onSetOptimisticFollowing: (Boolean?) -> Unit,
  onSetPendingFollowTarget: (Boolean?) -> Unit,
  onSetFollowStateLoaded: (Boolean) -> Unit,
  onSetScrobbles: (List<ScrobbleRow>) -> Unit,
  onSetScrobblesLoading: (Boolean) -> Unit,
  onSetScrobblesError: (String?) -> Unit,
  onSetPlaylists: (List<OnChainPlaylist>) -> Unit,
  onSetPlaylistsLoading: (Boolean) -> Unit,
  onSetPlaylistsError: (String?) -> Unit,
  onSetPublishedSongs: (List<PublishedSongRow>) -> Unit,
  onSetPublishedSongsLoading: (Boolean) -> Unit,
  onSetPublishedSongsError: (String?) -> Unit,
  onSetContractProfile: (ContractProfileData?) -> Unit,
  onSetContractLoading: (Boolean) -> Unit,
  onSetContractError: (String?) -> Unit,
  onSetCoverRecord: (String?) -> Unit,
  onSetLocationRecord: (String?) -> Unit,
  onSetSchoolRecord: (String?) -> Unit,
) {
  // Fetch follow counts
  LaunchedEffect(ethAddress) {
    withContext(Dispatchers.IO) {
      val (followers, following) = OnboardingRpcHelpers.getFollowCounts(ethAddress)
      onSetFollowerCount(followers)
      onSetFollowingCount(following)
    }
  }

  // Fetch follow state for viewer -> profile target
  LaunchedEffect(viewerEthAddress, ethAddress, canFollow) {
    onSetFollowError(null)
    if (!canFollow) {
      onSetServerFollowing(false)
      onSetOptimisticFollowing(null)
      onSetPendingFollowTarget(null)
      onSetFollowStateLoaded(true)
      return@LaunchedEffect
    }
    onSetFollowStateLoaded(false)
    val remoteFollowing = withContext(Dispatchers.IO) {
      OnboardingRpcHelpers.getFollowState(viewerEthAddress!!, ethAddress!!)
    }
    if (pendingFollowTarget == null) {
      onSetServerFollowing(remoteFollowing)
      onSetOptimisticFollowing(null)
    }
    onSetFollowStateLoaded(true)
  }

  // Fetch scrobbles (retries when scrobbleRetryKey increments)
  LaunchedEffect(ethAddress, scrobbleRetryKey) {
    onSetScrobblesLoading(true)
    onSetScrobblesError(null)
    runCatching { ProfileScrobbleApi.fetchScrobbles(ethAddress) }
      .onSuccess {
        onSetScrobbles(it)
        onSetScrobblesLoading(false)
      }
      .onFailure {
        onSetScrobblesError(it.message)
        onSetScrobblesLoading(false)
      }
  }

  // Fetch playlists
  LaunchedEffect(ethAddress) {
    onSetPlaylistsLoading(true)
    onSetPlaylistsError(null)
    runCatching { OnChainPlaylistsApi.fetchUserPlaylists(ethAddress) }
      .onSuccess {
        onSetPlaylists(it)
        onSetPlaylistsLoading(false)
      }
      .onFailure {
        onSetPlaylistsError(it.message ?: "Failed to load playlists")
        onSetPlaylistsLoading(false)
      }
  }

  // Fetch published songs from content entries for this profile owner
  LaunchedEffect(ethAddress) {
    onSetPublishedSongsLoading(true)
    onSetPublishedSongsError(null)
    runCatching { ProfileMusicApi.fetchPublishedSongs(ethAddress) }
      .onSuccess {
        onSetPublishedSongs(it)
        onSetPublishedSongsLoading(false)
      }
      .onFailure {
        onSetPublishedSongsError(it.message ?: "Failed to load published songs")
        onSetPublishedSongsLoading(false)
      }
  }

  // Fetch full contract profile for About/Edit
  LaunchedEffect(ethAddress, contractRetryKey, usePublicProfileReadModel) {
    onSetContractLoading(true)
    onSetContractError(null)
    if (usePublicProfileReadModel) {
      runCatching { fetchPublicProfileReadModel(ethAddress) }
        .onSuccess {
          onSetContractProfile(it?.contractProfile)
          onSetCoverRecord(it?.records?.coverRef)
          onSetLocationRecord(it?.records?.location)
          onSetSchoolRecord(it?.records?.school)
          onSetContractLoading(false)
        }
        .onFailure {
          onSetContractError(it.message ?: "Failed to load profile")
          onSetContractLoading(false)
        }
    } else {
      runCatching { ProfileContractApi.fetchProfile(ethAddress) }
        .onSuccess {
          onSetContractProfile(it)
          onSetContractLoading(false)
        }
        .onFailure {
          onSetContractError(it.message ?: "Failed to load profile")
          onSetContractLoading(false)
        }
    }
  }

  // Fetch user-friendly name records (location/school) when a primary name exists.
  LaunchedEffect(heavenName, usePublicProfileReadModel) {
    if (usePublicProfileReadModel) return@LaunchedEffect
    if (heavenName.isNullOrBlank()) {
      onSetCoverRecord(null)
      onSetLocationRecord(null)
      onSetSchoolRecord(null)
      return@LaunchedEffect
    }
    val node = TempoNameRecordsApi.computeNode(heavenName)
    withContext(Dispatchers.IO) {
      onSetCoverRecord(TempoNameRecordsApi.getTextRecord(node, TempoNameRecordsApi.PROFILE_COVER_RECORD_KEY))
      onSetLocationRecord(TempoNameRecordsApi.getTextRecord(node, "heaven.location"))
      onSetSchoolRecord(TempoNameRecordsApi.getTextRecord(node, "heaven.school"))
    }
  }
}
