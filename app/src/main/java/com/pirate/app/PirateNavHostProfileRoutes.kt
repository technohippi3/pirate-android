package com.pirate.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.composable
import com.pirate.app.profile.FollowListMode
import com.pirate.app.profile.FollowListScreen
import com.pirate.app.profile.ProfileEditScreen
import com.pirate.app.profile.ProfileScreen
import com.pirate.app.tempo.TempoPasskeyManager
import kotlinx.coroutines.launch

internal fun NavGraphBuilder.registerProfileRoutes(context: PirateNavHostContext) {
  composable(PirateRoute.Profile.route) {
    val isAuthenticated = context.authState.hasAnyCredentials()
    ProfileScreen(
      ethAddress = context.activeAddress,
      heavenName = context.heavenName,
      avatarUri = context.avatarUri,
      isAuthenticated = isAuthenticated,
      busy = context.authState.busy,
      selfVerified = context.authState.selfVerified,
      onRegister = context.onRegister,
      onLogin = context.onLogin,
      onLogout = context.onLogout,
      onBack = { context.navController.popBackStack() },
      onMessage = null,
      onEditProfile = {
        context.navController.navigate(PirateRoute.EditProfile.route) { launchSingleTop = true }
      },
      activity = context.activity,
      tempoAccount = context.tempoAccount,
      viewerEthAddress = context.activeAddress,
      usePublicProfileReadModel = false,
      onPlayPublishedSong = context.playPublishedSong,
      onOpenSong = context.openSongRoute,
      onOpenArtist = context.openArtistRoute,
      onViewProfile = context.openPublicProfileRoute,
      onNavigateFollowList = context.openFollowListRoute,
    )
  }

  composable(
    route = PirateRoute.PublicProfile.route,
    arguments = listOf(navArgument(PirateRoute.PublicProfile.ARG_ADDRESS) { type = NavType.StringType }),
  ) { backStackEntry ->
    val isAuthenticated = context.authState.hasAnyCredentials()
    val rawAddress = backStackEntry.arguments?.getString(PirateRoute.PublicProfile.ARG_ADDRESS).orEmpty()
    val targetAddress = remember(rawAddress) {
      val trimmed = rawAddress.trim()
      when {
        trimmed.isBlank() -> null
        trimmed.startsWith("0x", ignoreCase = true) -> trimmed
        trimmed.length == 40 -> "0x$trimmed"
        else -> trimmed
      }
    }
    var targetPirateName by remember(targetAddress) { mutableStateOf<String?>(null) }
    var targetAvatarUri by remember(targetAddress) { mutableStateOf<String?>(null) }

    LaunchedEffect(targetAddress) {
      targetPirateName = null
      targetAvatarUri = null
      if (targetAddress.isNullOrBlank()) return@LaunchedEffect
      val (name, avatar) = resolvePublicProfileIdentity(targetAddress)
      targetPirateName = name
      targetAvatarUri = avatar
    }

    ProfileScreen(
      ethAddress = targetAddress,
      heavenName = targetPirateName,
      avatarUri = targetAvatarUri,
      isAuthenticated = isAuthenticated,
      busy = context.authState.busy,
      selfVerified =
        context.authState.selfVerified &&
          context.activeAddress != null &&
          targetAddress != null &&
          context.activeAddress.equals(targetAddress, ignoreCase = true),
      onRegister = context.onRegister,
      onLogin = context.onLogin,
      onLogout = {},
      onBack = { context.navController.popBackStack() },
      onMessage = { address ->
        context.scope.launch {
          runCatching {
            val selfAddress = context.activeAddress
            if (selfAddress.isNullOrBlank()) {
              throw IllegalStateException("Missing chat auth context")
            }
            if (!context.chatService.connected.value) {
              context.chatService.connect(selfAddress)
            }
            val dmId = context.chatService.newDm(address)
            context.chatService.openConversation(dmId)
            context.navController.navigate(PirateRoute.Chat.route) { launchSingleTop = true }
          }.onFailure { err ->
            val message = err.message ?: "unknown error"
            when {
              message.contains("No XMTP inboxId", ignoreCase = true) ->
                context.onShowMessage("This user has not enabled messaging yet.")
              message.contains("Missing chat auth context", ignoreCase = true) ->
                context.onShowMessage("Missing chat auth. Please sign in again.")
              else -> context.onShowMessage("Message failed: $message")
            }
          }
        }
      },
      activity = context.activity,
      tempoAccount = context.tempoAccount,
      viewerEthAddress = context.activeAddress,
      usePublicProfileReadModel = true,
      onPlayPublishedSong = context.playPublishedSong,
      onOpenSong = context.openSongRoute,
      onOpenArtist = context.openArtistRoute,
      onViewProfile = context.openPublicProfileRoute,
      onNavigateFollowList = context.openFollowListRoute,
    )
  }

  composable(
    route = PirateRoute.FollowList.route,
    arguments = listOf(
      navArgument(PirateRoute.FollowList.ARG_ADDRESS) { type = NavType.StringType },
      navArgument(PirateRoute.FollowList.ARG_MODE) { type = NavType.StringType },
    ),
  ) { backStackEntry ->
    val address = backStackEntry.arguments?.getString(PirateRoute.FollowList.ARG_ADDRESS).orEmpty().trim()
    val modeName = backStackEntry.arguments?.getString(PirateRoute.FollowList.ARG_MODE).orEmpty()
    val mode = runCatching { FollowListMode.valueOf(modeName) }.getOrDefault(FollowListMode.Following)
    FollowListScreen(
      mode = mode,
      ethAddress = address,
      onClose = { context.navController.popBackStack() },
      onMemberClick = { memberAddress ->
        context.navController.navigate(PirateRoute.PublicProfile.buildRoute(memberAddress)) { launchSingleTop = true }
      },
    )
  }

  composable(PirateRoute.EditProfile.route) {
    val address = context.activeAddress
    if (!context.authState.hasTempoCredentials() || address.isNullOrBlank()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Sign in with Tempo passkey to edit your profile", color = Color(0xFFA3A3A3))
      }
    } else {
      ProfileEditScreen(
        activity = context.activity,
        ethAddress = address,
        tempoAddress = context.authState.tempoAddress,
        tempoCredentialId = context.authState.tempoCredentialId,
        tempoPubKeyX = context.authState.tempoPubKeyX,
        tempoPubKeyY = context.authState.tempoPubKeyY,
        tempoRpId = context.authState.tempoRpId.ifBlank { TempoPasskeyManager.DEFAULT_RP_ID },
        onBack = { context.navController.popBackStack() },
        onSaved = {
          context.onRefreshProfileIdentity()
          context.onShowMessage("Profile updated")
          context.navController.navigate(PirateRoute.Profile.route) {
            popUpTo(PirateRoute.Profile.route) { inclusive = true }
            launchSingleTop = true
          }
        },
      )
    }
  }
}
