package sc.pirate.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.*
import com.adamglin.phosphoricons.regular.*
import sc.pirate.app.player.PlayerController
import sc.pirate.app.ui.PirateMiniPlayer
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController

@Composable
internal fun PirateBottomBar(
  routes: List<PirateRoute>,
  navController: NavHostController,
  currentRoute: String?,
  player: PlayerController,
  showMiniPlayer: Boolean,
  showNavigationBar: Boolean = true,
) {
  Column {
    if (showMiniPlayer) {
      PirateMiniPlayer(
        player = player,
        onOpen = {
          navController.navigate(PirateRoute.Player.route) { launchSingleTop = true }
        },
      )
    }

    if (showNavigationBar) {
      CompositionLocalProvider(LocalRippleConfiguration provides null) {
        // Keep horizontal system-bar insets so the left-most tab does not sit inside
        // the edge-gesture region, while still avoiding extra bottom inset space.
        NavigationBar(windowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)) {
          val currentDestination = navController.currentBackStackEntry?.destination
          routes.forEach { screen ->
            val selected =
              currentDestination?.hierarchy?.any { destination -> destination.route == screen.route } == true ||
                currentRoute == screen.route
            NavigationBarItem(
              selected = selected,
              onClick = {
                navController.navigate(screen.route) {
                  popUpTo(navController.graph.startDestinationId) { saveState = true }
                  launchSingleTop = true
                  restoreState = true
                }
              },
              label = {},
              colors =
                NavigationBarItemDefaults.colors(
                  indicatorColor = Color.Transparent,
                  selectedIconColor = MaterialTheme.colorScheme.onBackground,
                  selectedTextColor = MaterialTheme.colorScheme.onBackground,
                ),
              icon = {
                when (screen) {
                  PirateRoute.Home ->
                    Icon(
                      imageVector = if (selected) PhosphorIcons.Fill.House else PhosphorIcons.Regular.House,
                      contentDescription = null,
                    )
                  PirateRoute.Music ->
                    Icon(
                      imageVector =
                        if (selected) {
                          PhosphorIcons.Fill.MusicNotes
                        } else {
                          PhosphorIcons.Regular.MusicNotes
                        },
                      contentDescription = null,
                    )
                  PirateRoute.Schedule ->
                    Icon(
                      imageVector = PhosphorIcons.Regular.Calendar,
                      contentDescription = null,
                    )
                  PirateRoute.Chat ->
                    Icon(
                      imageVector = if (selected) PhosphorIcons.Fill.ChatCircle else PhosphorIcons.Regular.ChatCircle,
                      contentDescription = null,
                    )
                  PirateRoute.Learn ->
                    Icon(
                      imageVector =
                        if (selected) {
                          PhosphorIcons.Fill.GraduationCap
                        } else {
                          PhosphorIcons.Regular.GraduationCap
                        },
                      contentDescription = null,
                    )
                  else ->
                    Icon(
                      imageVector = if (selected) PhosphorIcons.Fill.User else PhosphorIcons.Regular.User,
                      contentDescription = null,
                    )
                  }
              },
            )
          }
        }
      }
    }
  }
}
