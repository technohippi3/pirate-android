package com.pirate.app.ui

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pirate.app.util.resolveAvatarUrl
import com.pirate.app.util.shortAddress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PirateSideMenuDrawer(
  isAuthenticated: Boolean,
  busy: Boolean,
  ethAddress: String?,
  heavenName: String?,
  avatarUri: String?,
  selfVerified: Boolean,
  onNavigateProfile: () -> Unit,
  onNavigateWallet: () -> Unit,
  onNavigateNameStore: () -> Unit,
  onNavigateStudyCredits: () -> Unit,
  onNavigateVerifyIdentity: () -> Unit,
  onNavigatePublish: () -> Unit,
  onSignUp: () -> Unit,
  onSignIn: () -> Unit,
  onLogout: () -> Unit,
) {
  ModalDrawerSheet(
    modifier = Modifier.fillMaxHeight(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxHeight()
        .padding(start = 16.dp, end = 16.dp, top = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Header: avatar + name (clickable → profile) or branding
      if (isAuthenticated && ethAddress != null) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onNavigateProfile() }
            .padding(vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          val avatarUrl = resolveAvatarUrl(avatarUri)
          if (avatarUrl != null) {
            AsyncImage(
              model = avatarUrl,
              contentDescription = "Avatar",
              modifier = Modifier.size(40.dp).clip(CircleShape),
              contentScale = ContentScale.Crop,
            )
          } else {
            Surface(
              modifier = Modifier.size(40.dp),
              shape = CircleShape,
              color = MaterialTheme.colorScheme.primaryContainer,
            ) {
              Box(contentAlignment = Alignment.Center) {
                Text(
                  (heavenName?.take(1) ?: ethAddress.take(2).removePrefix("0x").ifEmpty { "?" }).uppercase(),
                  fontWeight = FontWeight.Bold,
                  style = MaterialTheme.typography.bodyLarge,
                )
              }
            }
          }
          Column {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Text(
                heavenName ?: shortAddress(ethAddress, minLengthToShorten = 14),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyLarge,
              )
              if (selfVerified) {
                VerifiedSealBadge(size = 16.dp)
              }
            }
            Text(
              "View profile",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Surface(
            modifier = Modifier.size(32.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
          ) {}
          Text("Pirate", fontWeight = FontWeight.Bold)
        }
      }

      HorizontalDivider()

      NavigationDrawerItem(
        label = { Text("Wallet") },
        selected = false,
        onClick = onNavigateWallet,
        modifier = Modifier.fillMaxWidth(),
        colors = NavigationDrawerItemDefaults.colors(),
      )

      if (isAuthenticated) {
        NavigationDrawerItem(
          label = { Text("Domains") },
          selected = false,
          onClick = onNavigateNameStore,
          modifier = Modifier.fillMaxWidth(),
          colors = NavigationDrawerItemDefaults.colors(),
        )
        NavigationDrawerItem(
          label = { Text("Credits") },
          selected = false,
          onClick = onNavigateStudyCredits,
          modifier = Modifier.fillMaxWidth(),
          colors = NavigationDrawerItemDefaults.colors(),
        )

        HorizontalDivider()
        NavigationDrawerItem(
          label = { Text("Publish Song") },
          selected = false,
          onClick = onNavigatePublish,
          modifier = Modifier.fillMaxWidth(),
          colors = NavigationDrawerItemDefaults.colors(),
        )
      }

      Spacer(modifier = Modifier.weight(1f, fill = true))

      if (isAuthenticated) {
        if (!selfVerified) {
          PiratePrimaryButton(
            text = "Verify Identity",
            modifier = Modifier.fillMaxWidth(),
            onClick = onNavigateVerifyIdentity,
            enabled = !busy,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            disabledContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
            leadingIcon = {
              Icon(PhosphorIcons.Regular.ShieldCheck, contentDescription = null)
            },
          )
          Spacer(Modifier.size(8.dp))
        }
        PirateOutlinedButton(
          modifier = Modifier.fillMaxWidth(),
          onClick = onLogout,
          enabled = !busy,
        ) {
          Text("Log Out")
        }
      } else {
        PiratePrimaryButton(
          text = "Sign Up",
          modifier = Modifier.fillMaxWidth(),
          onClick = onSignUp,
          enabled = !busy,
        )
        PirateOutlinedButton(
          modifier = Modifier.fillMaxWidth(),
          onClick = onSignIn,
          enabled = !busy,
        ) {
          Text("Log In")
        }
      }
    }
  }
}
