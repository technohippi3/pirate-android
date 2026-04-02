package sc.pirate.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PirateTopBar(
  title: String,
  isAuthenticated: Boolean,
  ethAddress: String?,
  primaryName: String?,
  avatarUri: String?,
  onAvatarClick: () -> Unit,
) {
  val bg = if (isAuthenticated) {
    MaterialTheme.colorScheme.primaryContainer
  } else {
    MaterialTheme.colorScheme.surfaceVariant
  }
  val fg = if (isAuthenticated) {
    MaterialTheme.colorScheme.onPrimaryContainer
  } else {
    MaterialTheme.colorScheme.onSurfaceVariant
  }

  CenterAlignedTopAppBar(
    title = { Text(title, fontWeight = FontWeight.SemiBold) },
    navigationIcon = {
      PirateIconButton(onClick = onAvatarClick) {
        val fallbackInitial = when {
          !primaryName.isNullOrBlank() -> primaryName.take(1)
          !ethAddress.isNullOrBlank() -> ethAddress.take(2).removePrefix("0x").ifEmpty { "?" }
          else -> "P"
        }.uppercase()

        PirateAvatarBadge(
          avatarUri = avatarUri,
          fallbackLabel = fallbackInitial,
          size = 36.dp,
          shape = RoundedCornerShape(10.dp),
          containerColor = bg,
          contentColor = fg,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        )
      }
    },
    actions = {
      Box(modifier = Modifier.size(48.dp))
    },
  )
}
