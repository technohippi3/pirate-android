package sc.pirate.app.onboarding.steps

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sc.pirate.app.R
import sc.pirate.app.theme.PirateTokens
import kotlinx.coroutines.delay

@Composable
fun DoneStep(
  claimedName: String,
  onFinished: () -> Unit,
) {
  LaunchedEffect(Unit) {
    delay(1500)
    onFinished()
  }

  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    horizontalAlignment = Alignment.Start,
  ) {
    Spacer(Modifier.height(48.dp))
    Icon(
      PhosphorIcons.Regular.CheckCircle,
      contentDescription = stringResource(R.string.onboarding_done_icon_description),
      modifier = Modifier.size(64.dp),
      tint = PirateTokens.colors.accentSuccess,
    )
    Spacer(Modifier.height(24.dp))
    Text(
      stringResource(R.string.onboarding_done_title),
      fontSize = 28.sp,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(8.dp))
    Text(
      if (claimedName.isNotBlank()) {
        stringResource(R.string.onboarding_done_welcome_named, claimedName)
      } else {
        stringResource(R.string.onboarding_done_welcome_generic)
      },
      fontSize = 18.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
