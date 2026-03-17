package com.pirate.app

private val bottomChromeHiddenRoutes =
  setOf(
    PirateRoute.Player.route,
    PirateRoute.VoiceCall.route,
    PirateRoute.ScheduledSessionCall.route,
    PirateRoute.LiveRoom.route,
    PirateRoute.ScheduleAvailability.route,
    PirateRoute.Onboarding.route,
    PirateRoute.AuthResolving.route,
    PirateRoute.Post.route,
    PirateRoute.PostCapture.route,
    PirateRoute.PostPreview.route,
    PirateRoute.Publish.route,
    PirateRoute.VerifyIdentity.route,
    PirateRoute.EditProfile.route,
    PirateRoute.Wallet.route,
    PirateRoute.NameStore.route,
    PirateRoute.StudyCredits.route,
    PirateRoute.Song.route,
    PirateRoute.Artist.route,
    PirateRoute.LearnExercises.route,
    PirateRoute.PublicProfile.route,
    PirateRoute.FollowList.route,
  )

private val topChromeHiddenRoutes =
  setOf(
    PirateRoute.Player.route,
    PirateRoute.Home.route,
    PirateRoute.Music.route,
    PirateRoute.Chat.route,
    PirateRoute.Learn.route,
    PirateRoute.Schedule.route,
    PirateRoute.Rooms.route,
    PirateRoute.ScheduleAvailability.route,
    PirateRoute.Profile.route,
    PirateRoute.Wallet.route,
    PirateRoute.NameStore.route,
    PirateRoute.StudyCredits.route,
    PirateRoute.Song.route,
    PirateRoute.Artist.route,
    PirateRoute.LearnExercises.route,
    PirateRoute.PublicProfile.route,
    PirateRoute.EditProfile.route,
    PirateRoute.FollowList.route,
    PirateRoute.VoiceCall.route,
    PirateRoute.ScheduledSessionCall.route,
    PirateRoute.LiveRoom.route,
    PirateRoute.Onboarding.route,
    PirateRoute.AuthResolving.route,
    PirateRoute.Post.route,
    PirateRoute.PostCapture.route,
    PirateRoute.PostPreview.route,
    PirateRoute.Publish.route,
    PirateRoute.VerifyIdentity.route,
  )

private val routeTitles =
  mapOf(
    PirateRoute.Music.route to "Music",
    PirateRoute.Home.route to "Home",
    PirateRoute.Chat.route to "Chat",
    PirateRoute.Wallet.route to "Wallet",
    PirateRoute.NameStore.route to "Domains",
    PirateRoute.StudyCredits.route to "Credits",
    PirateRoute.Song.route to "Song",
    PirateRoute.Artist.route to "Artist",
    PirateRoute.LearnExercises.route to "Learn",
    PirateRoute.Learn.route to "Learn",
    PirateRoute.Schedule.route to "Schedule",
    PirateRoute.ScheduleAvailability.route to "Availability",
    PirateRoute.Rooms.route to "Community",
    PirateRoute.Profile.route to "Profile",
    PirateRoute.PublicProfile.route to "Profile",
    PirateRoute.EditProfile.route to "Edit Profile",
    PirateRoute.Player.route to "Now Playing",
    PirateRoute.ScheduledSessionCall.route to "Session Call",
    PirateRoute.VerifyIdentity.route to "Verify Identity",
    PirateRoute.Post.route to "Post",
    PirateRoute.PostCapture.route to "Capture",
    PirateRoute.PostPreview.route to "Preview",
    PirateRoute.AuthResolving.route to "Loading",
  )

private val drawerGestureEnabledRoutes =
  setOf(
    PirateRoute.Home.route,
    PirateRoute.Music.route,
    PirateRoute.Chat.route,
    PirateRoute.Learn.route,
    PirateRoute.Schedule.route,
    PirateRoute.Rooms.route,
    PirateRoute.Profile.route,
  )

internal fun showBottomChromeForRoute(currentRoute: String?, chatThreadOpen: Boolean): Boolean {
  if (currentRoute == PirateRoute.Chat.route && chatThreadOpen) return false
  return currentRoute !in bottomChromeHiddenRoutes
}

internal fun showTopChromeForRoute(currentRoute: String?): Boolean = currentRoute !in topChromeHiddenRoutes

internal fun routeTitle(currentRoute: String?): String = routeTitles[currentRoute] ?: "Pirate"

internal fun drawerGesturesEnabledForRoute(currentRoute: String?, chatThreadOpen: Boolean): Boolean {
  if (currentRoute == PirateRoute.Chat.route && chatThreadOpen) return false
  return currentRoute in drawerGestureEnabledRoutes
}
