package sc.pirate.app.ui

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*


import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sc.pirate.app.auth.PirateAuthUiState
import sc.pirate.app.resolveProfileIdentityWithRetry
import sc.pirate.app.util.resolveAvatarUrl

@Composable
fun PirateMobileHeader(
  title: String,
  isAuthenticated: Boolean = false,
  ethAddress: String? = null,
  primaryName: String? = null,
  avatarUri: String? = null,
  onAvatarPress: (() -> Unit)? = null,
  onBackPress: (() -> Unit)? = null,
  onClosePress: (() -> Unit)? = null,
  titleAvatarUri: String? = null,
  @DrawableRes titleAvatarDrawableRes: Int? = null,
  titleAvatarDisplayName: String? = null,
  onTitlePress: (() -> Unit)? = null,
  rightSlot: (@Composable () -> Unit)? = null,
) {
  val appContext = LocalContext.current.applicationContext
  val activeAddress = remember(isAuthenticated, ethAddress) {
    if (!isAuthenticated) null else ethAddress?.trim()?.takeIf { it.isNotBlank() }
      ?: PirateAuthUiState.load(appContext).activeAddress()
  }
  var resolvedName by remember(activeAddress) { mutableStateOf<String?>(null) }
  var resolvedAvatarUri by remember(activeAddress) { mutableStateOf<String?>(null) }

  LaunchedEffect(activeAddress) {
    val addr = activeAddress
    if (addr.isNullOrBlank()) {
      resolvedName = null
      resolvedAvatarUri = null
      return@LaunchedEffect
    }
    val (name, avatar) = runCatching {
      resolveProfileIdentityWithRetry(addr, attempts = 1)
    }.getOrDefault(null to null)
    resolvedName = name
    resolvedAvatarUri = avatar
  }

  Surface(color = MaterialTheme.colorScheme.background) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(horizontal = 16.dp)
        .padding(top = 8.dp, bottom = 6.dp)
        .heightIn(min = 56.dp),
    ) {
      if (onBackPress != null) {
        PirateIconButton(
          modifier = Modifier.align(Alignment.CenterStart),
          onClick = onBackPress,
        ) {
          Icon(
            PhosphorIcons.Regular.ArrowLeft,
            contentDescription = "Previous screen",
            tint = MaterialTheme.colorScheme.onBackground,
          )
        }
      } else if (onClosePress != null) {
        PirateIconButton(
          modifier = Modifier.align(Alignment.CenterStart),
          onClick = onClosePress,
        ) {
          Icon(
            PhosphorIcons.Regular.X,
            contentDescription = "Close",
            tint = MaterialTheme.colorScheme.onBackground,
          )
        }
      } else {
        PirateIconButton(
          modifier = Modifier.align(Alignment.CenterStart),
          enabled = onAvatarPress != null,
          onClick = { onAvatarPress?.invoke() },
        ) {
          val bg = if (isAuthenticated) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
          val fg = if (isAuthenticated) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
          val displayAvatarUri = avatarUri?.trim()?.takeIf { it.isNotBlank() } ?: resolvedAvatarUri
          val displayName = primaryName?.trim()?.takeIf { it.isNotBlank() } ?: resolvedName
          val fallbackInitial = when {
            !displayName.isNullOrBlank() -> displayName.take(1)
            !activeAddress.isNullOrBlank() -> activeAddress.take(2).removePrefix("0x").ifEmpty { "?" }
            else -> "P"
          }.uppercase()
          PirateAvatarBadge(
            avatarUri = displayAvatarUri,
            fallbackLabel = fallbackInitial,
            size = 36.dp,
            shape = CircleShape,
            containerColor = bg,
            contentColor = fg,
          )
        }
      }

      val titleModifier =
        Modifier
          .align(Alignment.Center)
          .let { modifier ->
            if (onTitlePress != null) modifier.clickable(onClick = onTitlePress) else modifier
          }
      val centerAvatarUri = titleAvatarUri?.trim()?.takeIf { it.isNotBlank() }
      val centerDisplayName = titleAvatarDisplayName?.trim()?.takeIf { it.isNotBlank() } ?: title
      if (!centerAvatarUri.isNullOrBlank() || !titleAvatarDisplayName.isNullOrBlank() || titleAvatarDrawableRes != null) {
        Row(
          modifier = titleModifier,
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (titleAvatarDrawableRes != null) {
            Image(
              painter = painterResource(titleAvatarDrawableRes),
              contentDescription = centerDisplayName,
              modifier = Modifier.size(24.dp).clip(CircleShape),
              contentScale = ContentScale.Crop,
            )
          } else {
            val avatarUrl = resolveAvatarUrl(centerAvatarUri)
            if (!avatarUrl.isNullOrBlank()) {
              PirateAvatarBadge(
                avatarUri = centerAvatarUri,
                fallbackLabel = centerDisplayName.take(1).ifBlank { "?" }.uppercase(),
                size = 24.dp,
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                contentDescription = centerDisplayName,
              )
            } else {
              Surface(
                modifier = Modifier.size(24.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Text(
                    (centerDisplayName.take(1).ifBlank { "?" }).uppercase(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                  )
                }
              }
            }
          }
          Text(
            title,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleLarge,
          )
        }
      } else {
        Text(
          title,
          modifier = titleModifier,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onBackground,
          style = MaterialTheme.typography.titleLarge,
        )
      }

      Box(modifier = Modifier.align(Alignment.CenterEnd)) {
        rightSlot?.invoke() ?: Spacer(modifier = Modifier.size(36.dp))
      }
    }
  }
}
