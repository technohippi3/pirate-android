package sc.pirate.app

import androidx.compose.material3.DrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavHostController
import sc.pirate.app.ui.PirateSideMenuDrawer
import kotlinx.coroutines.launch

@Composable
internal fun PirateDrawerContent(
  drawerState: DrawerState,
  navController: NavHostController,
  isAuthenticated: Boolean,
  busy: Boolean,
  ethAddress: String?,
  primaryName: String?,
  avatarUri: String?,
  selfVerified: Boolean,
  onContinue: suspend () -> Unit,
  onLogout: suspend () -> Unit,
) {
  val scope = rememberCoroutineScope()

  suspend fun closeDrawer() {
    drawerState.close()
  }

  PirateSideMenuDrawer(
    isAuthenticated = isAuthenticated,
    busy = busy,
    ethAddress = ethAddress,
    primaryName = primaryName,
    avatarUri = avatarUri,
    selfVerified = selfVerified,
    onNavigateProfile = {
      scope.launch {
        closeDrawer()
        navController.navigate(PirateRoute.Profile.route) {
          popUpTo(navController.graph.startDestinationId) { saveState = true }
          launchSingleTop = true
          restoreState = true
        }
      }
    },
    onNavigateWallet = {
      scope.launch {
        closeDrawer()
        navController.navigate(PirateRoute.Wallet.route) { launchSingleTop = true }
      }
    },
    onNavigateNameStore = {
      scope.launch {
        closeDrawer()
        navController.navigate(PirateRoute.NameStore.route) { launchSingleTop = true }
      }
    },
    onNavigateStudyCredits = {
      scope.launch {
        closeDrawer()
        navController.navigate(PirateRoute.StudyCredits.route) { launchSingleTop = true }
      }
    },
    onNavigateVerifyIdentity = {
      scope.launch {
        closeDrawer()
        navController.navigate(PirateRoute.VerifyIdentity.route) { launchSingleTop = true }
      }
    },
    onNavigatePublish = {
      scope.launch {
        closeDrawer()
        navController.navigate(PirateRoute.Publish.route) { launchSingleTop = true }
      }
    },
    onContinue = {
      scope.launch {
        closeDrawer()
        onContinue()
      }
    },
    onLogout = {
      scope.launch {
        closeDrawer()
        onLogout()
      }
    },
  )
}
