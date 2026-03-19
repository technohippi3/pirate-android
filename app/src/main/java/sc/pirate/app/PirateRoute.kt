package sc.pirate.app

import android.net.Uri
import sc.pirate.app.profile.FollowListMode

internal sealed class PirateRoute(val route: String, val label: String) {
  data object Home : PirateRoute("home", "Home")
  data object Music : PirateRoute("music", "Music")
  data object Chat : PirateRoute("chat", "Chat")
  data object Wallet : PirateRoute("wallet", "Wallet")
  data object NameStore : PirateRoute("name_store", "Domains")
  data object StudyCredits : PirateRoute("study_credits", "Credits")
  data object Learn : PirateRoute("learn", "Learn")
  data object LearnExercises : PirateRoute(
    "learn/exercises?studySetRef={studySetRef}&trackId={trackId}&lang={lang}&v={v}&title={title}&artist={artist}",
    "Learn",
  ) {
    const val ARG_STUDY_SET_REF = "studySetRef"
    const val ARG_TRACK_ID = "trackId"
    const val ARG_LANG = "lang"
    const val ARG_VERSION = "v"
    const val ARG_TITLE = "title"
    const val ARG_ARTIST = "artist"

    fun buildRoute(
      studySetRef: String?,
      trackId: String?,
      language: String?,
      version: Int = 1,
      title: String? = null,
      artist: String? = null,
    ): String {
      val ref = studySetRef?.trim().orEmpty()
      val tid = trackId?.trim().orEmpty()
      val lang = language?.trim().orEmpty()
      val resolvedTitle = title?.trim().orEmpty()
      val resolvedArtist = artist?.trim().orEmpty()
      val encodedRef = Uri.encode(ref)
      val encodedTrackId = Uri.encode(tid)
      val encodedLang = Uri.encode(lang)
      val encodedTitle = Uri.encode(resolvedTitle)
      val encodedArtist = Uri.encode(resolvedArtist)
      return "learn/exercises?studySetRef=$encodedRef&trackId=$encodedTrackId&lang=$encodedLang&v=$version&title=$encodedTitle&artist=$encodedArtist"
    }
  }
  data object Schedule : PirateRoute("schedule", "Schedule")
  data object ScheduleAvailability : PirateRoute("schedule/availability", "Availability")
  data object Rooms : PirateRoute("rooms", "Community")
  data object Profile : PirateRoute("profile", "Profile")
  data object PublicProfile : PirateRoute("profile/public/{address}", "Profile") {
    const val ARG_ADDRESS = "address"
    fun buildRoute(address: String): String = "profile/public/${address.trim()}"
  }

  data object Song : PirateRoute("song/{trackId}?title={title}&artist={artist}", "Song") {
    const val ARG_TRACK_ID = "trackId"
    const val ARG_TITLE = "title"
    const val ARG_ARTIST = "artist"

    fun buildRoute(trackId: String, title: String? = null, artist: String? = null): String {
      val normalizedTrackId = trackId.trim()
      val titleQuery = Uri.encode(title?.trim().orEmpty())
      val artistQuery = Uri.encode(artist?.trim().orEmpty())
      return "song/$normalizedTrackId?title=$titleQuery&artist=$artistQuery"
    }
  }

  data object Artist : PirateRoute("artist/{artistName}", "Artist") {
    const val ARG_ARTIST_NAME = "artistName"
    fun buildRoute(artistName: String): String = "artist/${Uri.encode(artistName.trim())}"
  }

  data object FollowList : PirateRoute("profile/follow_list/{address}/{mode}", "Follow List") {
    const val ARG_ADDRESS = "address"
    const val ARG_MODE = "mode"

    fun buildRoute(address: String, mode: FollowListMode): String =
      "profile/follow_list/${address.trim()}/${mode.name}"
  }

  data object EditProfile : PirateRoute("profile/edit", "Edit Profile")
  data object Player : PirateRoute("player", "Player")
  data object VoiceCall : PirateRoute("voice_call", "Voice Call")
  data object ScheduledSessionCall : PirateRoute("scheduled_session_call", "Session Call")
  data object LiveRoom : PirateRoute(
    "live_room/{roomId}?title={title}&subtitle={subtitle}&hostWallet={hostWallet}&cover={cover}&price={price}&listeners={listeners}&status={status}",
    "Live Room",
  ) {
    const val ARG_ROOM_ID = "roomId"
    const val ARG_TITLE = "title"
    const val ARG_SUBTITLE = "subtitle"
    const val ARG_HOST_WALLET = "hostWallet"
    const val ARG_COVER = "cover"
    const val ARG_PRICE = "price"
    const val ARG_LISTENERS = "listeners"
    const val ARG_STATUS = "status"

    fun buildRoute(
      roomId: String,
      title: String?,
      subtitle: String?,
      hostWallet: String?,
      coverRef: String?,
      liveAmount: String?,
      listenerCount: Int?,
      status: String?,
    ): String {
      val normalizedRoomId = Uri.encode(roomId.trim())
      val encodedTitle = Uri.encode(title?.trim().orEmpty())
      val encodedSubtitle = Uri.encode(subtitle?.trim().orEmpty())
      val encodedHostWallet = Uri.encode(hostWallet?.trim().orEmpty())
      val encodedCover = Uri.encode(coverRef?.trim().orEmpty())
      val encodedPrice = Uri.encode(liveAmount?.trim().orEmpty())
      val encodedListeners = Uri.encode(listenerCount?.toString().orEmpty())
      val encodedStatus = Uri.encode(status?.trim().orEmpty())
      return "live_room/$normalizedRoomId?title=$encodedTitle&subtitle=$encodedSubtitle&hostWallet=$encodedHostWallet&cover=$encodedCover&price=$encodedPrice&listeners=$encodedListeners&status=$encodedStatus"
    }
  }
  data object AuthResolving : PirateRoute("auth_resolving", "Loading")
  data object Onboarding : PirateRoute("onboarding", "Onboarding")
  data object Post : PirateRoute("post", "Post")
  data object PostCapture : PirateRoute("post/capture", "Capture")
  data object PostPreview : PirateRoute("post/preview?video={videoUri}", "Preview") {
    const val ARG_VIDEO_URI = "videoUri"
    const val ARG_VIDEO_URI_PARAM = "video"
    fun buildRoute(videoUri: String): String = "post/preview?video=${Uri.encode(videoUri)}"
  }
  data object Publish : PirateRoute("publish", "Publish Song")
  data object VerifyIdentity : PirateRoute("verify_identity", "Verify Identity")
}
