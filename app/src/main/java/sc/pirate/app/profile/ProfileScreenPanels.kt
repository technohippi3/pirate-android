package sc.pirate.app.profile

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import sc.pirate.app.ui.PirateIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import sc.pirate.app.ui.PirateOutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import sc.pirate.app.ui.PirateTextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import sc.pirate.app.BuildConfig
import coil.compose.AsyncImage
import sc.pirate.app.music.OnChainPlaylist
import sc.pirate.app.music.OnChainPlaylistsApi
import sc.pirate.app.onboarding.OnboardingRpcHelpers
import sc.pirate.app.R
import sc.pirate.app.theme.PirateTokens
import sc.pirate.app.ui.VerifiedSealBadge
import sc.pirate.app.ui.PirateSheetTitle
import sc.pirate.app.util.resolveAvatarUrl
import sc.pirate.app.util.shortAddress
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(
  ethAddress: String,
  onDismiss: () -> Unit,
  onLogout: () -> Unit,
) {
  val context = LocalContext.current
  val clipboardManager = LocalClipboardManager.current
  val scope = rememberCoroutineScope()
  var copied by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState()

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = PirateTokens.colors.bgSurface,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
    ) {
      PirateSheetTitle(
        text = stringResource(R.string.profile_settings_title),
        color = MaterialTheme.colorScheme.onBackground,
      )
      Spacer(Modifier.height(24.dp))

      Text(stringResource(R.string.profile_settings_wallet_address), style = MaterialTheme.typography.labelLarge, color = PirateTokens.colors.textSecondary)
      Spacer(Modifier.height(8.dp))
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(PirateTokens.colors.bgElevated, RoundedCornerShape(PirateTokens.radius.lg))
          .clickable {
            clipboardManager.setText(AnnotatedString(ethAddress))
            copied = true
            scope.launch {
              kotlinx.coroutines.delay(2000)
              copied = false
            }
          }
          .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          ethAddress,
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onBackground,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Icon(
          PhosphorIcons.Regular.Copy,
          contentDescription = stringResource(R.string.profile_settings_copy),
          modifier = Modifier.size(20.dp),
          tint = if (copied) PirateTokens.colors.accentSuccess else PirateTokens.colors.textSecondary,
        )
      }
      if (copied) {
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.profile_settings_copied), style = MaterialTheme.typography.bodySmall, color = PirateTokens.colors.accentSuccess)
      }

      Spacer(Modifier.height(24.dp))
      HorizontalDivider(color = PirateTokens.colors.borderSoft)
      Spacer(Modifier.height(16.dp))

      Text(stringResource(R.string.profile_settings_app), style = MaterialTheme.typography.labelLarge, color = PirateTokens.colors.textSecondary)
      Spacer(Modifier.height(8.dp))
      Text(
        stringResource(R.string.profile_settings_app_version, BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
      )

      Spacer(Modifier.height(24.dp))
      HorizontalDivider(color = PirateTokens.colors.borderSoft)
      Spacer(Modifier.height(16.dp))

      PirateOutlinedButton(
        onClick = {
          onDismiss()
          onLogout()
        },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(R.string.profile_settings_sign_out), color = MaterialTheme.colorScheme.error)
      }

      Spacer(Modifier.height(12.dp))

      PirateOutlinedButton(
        onClick = {
          val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(DELETE_ACCOUNT_URL)).apply {
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
          runCatching { context.startActivity(intent) }
        },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(R.string.profile_settings_delete_account), color = MaterialTheme.colorScheme.error)
      }
    }
  }
}

private const val DELETE_ACCOUNT_URL = "https://pirate.sc/delete-account"

// ── Shared ──

@Composable
internal fun EmptyTabPanel(label: String) {
  CenteredStatus { Text(stringResource(R.string.profile_tab_coming_soon, label), color = PirateTokens.colors.textSecondary) }
}

@Composable
internal fun CenteredStatus(content: @Composable () -> Unit) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
  }
}

@Composable
internal fun FollowStat(count: String, label: String, onClick: (() -> Unit)? = null) {
  Row(
    modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(count, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
    Text(label, style = MaterialTheme.typography.bodyLarge, color = PirateTokens.colors.textSecondary)
  }
}
