package sc.pirate.app.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import sc.pirate.app.fetchPublicProfileReadModel
import sc.pirate.app.music.OnChainPlaylist
import sc.pirate.app.music.OnChainPlaylistsApi
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
  primaryName: String?,
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
  LaunchedEffect(ethAddress) {
    withContext(Dispatchers.IO) {
      val summary = EfpFollowApi.fetchProfileFollowSummary(ethAddress)
      onSetFollowerCount(summary.followerCount)
      onSetFollowingCount(summary.followingCount)
    }
  }

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
      EfpFollowApi.fetchViewerFollowState(viewerEthAddress!!, ethAddress)
    }
    if (pendingFollowTarget == null) {
      onSetServerFollowing(remoteFollowing)
      onSetOptimisticFollowing(null)
    }
    onSetFollowStateLoaded(true)
  }

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

  LaunchedEffect(primaryName, usePublicProfileReadModel) {
    if (usePublicProfileReadModel) return@LaunchedEffect
    if (primaryName.isNullOrBlank()) {
      onSetCoverRecord(null)
      onSetLocationRecord(null)
      onSetSchoolRecord(null)
      return@LaunchedEffect
    }
    val node = PirateNameRecordsApi.computeNode(primaryName)
    withContext(Dispatchers.IO) {
      onSetCoverRecord(PirateNameRecordsApi.getTextRecord(node, PirateNameRecordsApi.PROFILE_COVER_RECORD_KEY))
      onSetLocationRecord(PirateNameRecordsApi.getTextRecord(node, "heaven.location"))
      onSetSchoolRecord(PirateNameRecordsApi.getTextRecord(node, "heaven.school"))
    }
  }
}
