package sc.pirate.app.profile

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import sc.pirate.app.ui.PirateIconButton
import androidx.compose.material3.MaterialTheme
import sc.pirate.app.ui.PirateOutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import sc.pirate.app.theme.PiratePalette
import sc.pirate.app.ui.VerifiedSealBadge
import sc.pirate.app.ui.PiratePrimaryButton
import sc.pirate.app.util.resolveAvatarUrl
import sc.pirate.app.util.resolveProfileCoverUrl

private val BannerGradient = Brush.verticalGradient(
  colors = listOf(Color(0xFF2D1B4E), Color(0xFF1A1040), Color(0xFF171717)),
)

@Composable
internal fun ProfileScreenHeaderSection(
  ethAddress: String?,
  handleText: String,
  profileName: String?,
  effectiveAvatarRef: String?,
  effectiveCoverRef: String?,
  selfVerified: Boolean,
  isOwnProfile: Boolean,
  followerCount: Int,
  followingCount: Int,
  hasTargetAddress: Boolean,
  canFollow: Boolean,
  canMessage: Boolean,
  followBusy: Boolean,
  followStateLoaded: Boolean,
  pendingFollowTarget: Boolean?,
  effectiveFollowing: Boolean,
  followError: String?,
  onBack: (() -> Unit)?,
  onOpenSettings: () -> Unit,
  onEditProfile: (() -> Unit)?,
  onNavigateFollowList: ((FollowListMode, String) -> Unit)?,
  onToggleFollow: () -> Unit,
  onMessageClick: (() -> Unit)?,
) {
  val coverUrl = resolveProfileCoverUrl(effectiveCoverRef)
  Box(modifier = Modifier.fillMaxWidth().height(100.dp)) {
    if (coverUrl != null) {
      AsyncImage(
        model = coverUrl,
        contentDescription = null,
        modifier = Modifier.fillMaxWidth().height(100.dp),
        contentScale = ContentScale.Crop,
      )
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .height(100.dp)
            .background(
              Brush.verticalGradient(
                colors = listOf(Color(0x14090D18), Color(0x3D0C1018), Color(0x99171717)),
              ),
            ),
      )
    } else {
      Box(modifier = Modifier.fillMaxWidth().height(100.dp).background(BannerGradient))
    }
    if (onBack != null) {
      PirateIconButton(
        onClick = onBack,
        modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 8.dp),
      ) {
        Icon(PhosphorIcons.Regular.ArrowLeft, contentDescription = "Previous screen", tint = Color.White, modifier = Modifier.size(24.dp))
      }
    }
    if (isOwnProfile) {
      PirateIconButton(
        onClick = onOpenSettings,
        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 8.dp),
      ) {
        Icon(PhosphorIcons.Regular.Gear, contentDescription = "Settings", tint = Color.White, modifier = Modifier.size(24.dp))
      }
    }
  }

  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val avatarUrl = resolveAvatarUrl(effectiveAvatarRef)
    if (avatarUrl != null) {
      AsyncImage(
        model = avatarUrl,
        contentDescription = "Avatar",
        modifier = Modifier.size(92.dp).clip(CircleShape),
        contentScale = ContentScale.Crop,
      )
    } else {
      Surface(
        modifier = Modifier.size(92.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
      ) {
        Box(contentAlignment = Alignment.Center) {
          Text(
            (profileName?.take(1) ?: handleText.take(1)).uppercase(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
          )
        }
      }
    }
    Spacer(Modifier.width(16.dp))
    Column(
      modifier = Modifier.weight(1f).height(92.dp),
      verticalArrangement = Arrangement.Center,
    ) {
      if (profileName != null) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            profileName,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (selfVerified) {
            VerifiedSealBadge(size = 20.dp)
          }
        }
        Text(
          handleText,
          style = MaterialTheme.typography.bodyLarge,
          color = PiratePalette.TextMuted,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      } else {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            handleText,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (selfVerified) {
            VerifiedSealBadge(size = 20.dp)
          }
        }
      }

      Spacer(Modifier.height(8.dp))

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FollowStat("$followerCount", "followers") {
          ethAddress?.let { onNavigateFollowList?.invoke(FollowListMode.Followers, it) }
        }
        Text("•", style = MaterialTheme.typography.bodyLarge, color = PiratePalette.TextMuted)
        FollowStat("$followingCount", "following") {
          ethAddress?.let { onNavigateFollowList?.invoke(FollowListMode.Following, it) }
        }
      }
    }
  }

  if (isOwnProfile && onEditProfile != null) {
    Spacer(Modifier.height(12.dp))
    PirateOutlinedButton(
      onClick = onEditProfile,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    ) {
      Text("Edit Profile")
    }
    Spacer(Modifier.height(12.dp))
  } else if (!isOwnProfile && hasTargetAddress) {
    Spacer(Modifier.height(12.dp))
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      PiratePrimaryButton(
        text =
          when {
            !followStateLoaded -> "..."
            followBusy && pendingFollowTarget == true -> "Following..."
            followBusy && pendingFollowTarget == false -> "Unfollowing..."
            effectiveFollowing -> "Following"
            else -> "Follow"
          },
        modifier = if (canMessage) Modifier.weight(1f) else Modifier.fillMaxWidth(),
        onClick = onToggleFollow,
        enabled = canFollow && !followBusy && followStateLoaded,
      )
      if (canMessage) {
        PirateOutlinedButton(
          modifier = Modifier.weight(1f),
          onClick = { onMessageClick?.invoke() },
          enabled = !followBusy,
        ) {
          Text("Message")
        }
      }
    }
    Spacer(Modifier.height(12.dp))
  } else {
    Spacer(Modifier.height(12.dp))
  }
  if (!followError.isNullOrBlank()) {
    Text(
      followError,
      modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
      color = MaterialTheme.colorScheme.error,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}
