package sc.pirate.app.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import sc.pirate.app.ui.PiratePrimaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiveRoomScreen(
  roomId: String,
  initialTitle: String?,
  initialSubtitle: String?,
  initialHostWallet: String?,
  initialCoverRef: String?,
  initialLiveAmount: String?,
  initialListenerCount: Int?,
  initialStatus: String?,
  ownerEthAddress: String?,
  hostActivity: FragmentActivity,
  onBack: () -> Unit,
  onShowMessage: (String) -> Unit,
) {
  val title = initialTitle?.takeIf { it.isNotBlank() } ?: "Live room"
  val subtitle = initialSubtitle?.takeIf { it.isNotBlank() } ?: initialHostWallet?.takeIf { it.isNotBlank() }

  Scaffold { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .padding(innerPadding)
        .padding(horizontal = 24.dp, vertical = 32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
      )
      if (!subtitle.isNullOrBlank()) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
      Text(
        text = "Live audio and video are unavailable in the F-Droid build because the standard app uses a proprietary RTC SDK.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp),
      )
      PiratePrimaryButton(
        text = "Go back",
        onClick = onBack,
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 24.dp),
      )
    }
  }
}
