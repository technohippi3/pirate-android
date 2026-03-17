package com.pirate.app.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pirate.app.theme.PiratePalette
import com.pirate.app.ui.PirateMobileHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class FollowListMode { Followers, Following }

@Composable
fun FollowListScreen(
  mode: FollowListMode,
  ethAddress: String,
  onClose: () -> Unit,
  onMemberClick: (String) -> Unit,
) {
  var members by remember { mutableStateOf<List<FollowListMember>>(emptyList()) }
  var loading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(ethAddress, mode) {
    loading = true
    error = null
    runCatching {
      withContext(Dispatchers.IO) {
        when (mode) {
          FollowListMode.Followers -> FollowListApi.fetchFollowers(ethAddress)
          FollowListMode.Following -> FollowListApi.fetchFollowing(ethAddress)
        }
      }
    }.onSuccess {
      members = it
      loading = false
    }.onFailure {
      error = it.message ?: "Failed to load"
      loading = false
    }
  }

  val title = when (mode) {
    FollowListMode.Followers -> "Followers"
    FollowListMode.Following -> "Following"
  }

  Column(
    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
  ) {
    PirateMobileHeader(
      title = title,
      onClosePress = onClose,
    )

    when {
      loading -> {
        Box(
          modifier = Modifier.fillMaxWidth().weight(1f),
          contentAlignment = Alignment.Center,
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(32.dp))
            Spacer(Modifier.height(12.dp))
            Text("Loading...", color = PiratePalette.TextMuted)
          }
        }
      }
      error != null -> {
        Box(
          modifier = Modifier.fillMaxWidth().weight(1f),
          contentAlignment = Alignment.Center,
        ) {
          Text(error!!, color = MaterialTheme.colorScheme.error)
        }
      }
      members.isEmpty() -> {
        Box(
          modifier = Modifier.fillMaxWidth().weight(1f),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            if (mode == FollowListMode.Followers) "No followers yet" else "Not following anyone yet",
            color = PiratePalette.TextMuted,
            style = MaterialTheme.typography.bodyLarge,
          )
        }
      }
      else -> {
        LazyColumn(
          modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
          items(members, key = { it.address }) { member ->
            FollowMemberRow(
              member = member,
              onClick = { onMemberClick(member.address) },
            )
            HorizontalDivider(
              modifier = Modifier.padding(horizontal = 16.dp),
              color = Color(0xFF2A2A2A),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun FollowMemberRow(
  member: FollowListMember,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (!member.avatarUrl.isNullOrBlank()) {
      AsyncImage(
        model = member.avatarUrl,
        contentDescription = "Avatar",
        modifier = Modifier.size(52.dp).clip(CircleShape),
        contentScale = ContentScale.Crop,
      )
    } else {
      Box(
        modifier = Modifier
          .size(52.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          member.name.take(1).ifBlank { "?" }.uppercase(),
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Spacer(Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = member.name,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onBackground,
      )
      if (member.followedAtSec > 0) {
        Text(
          text = "Followed ${formatFollowedAgo(member.followedAtSec)}",
          style = MaterialTheme.typography.bodyMedium,
          color = PiratePalette.TextMuted,
          maxLines = 1,
        )
      }
    }
  }
}

private fun formatFollowedAgo(timestampSec: Long): String {
  val nowSec = System.currentTimeMillis() / 1000
  if (timestampSec >= nowSec) return "just now"
  val delta = nowSec - timestampSec
  return when {
    delta < 60 -> "${delta}s ago"
    delta < 3600 -> "${delta / 60}m ago"
    delta < 86400 -> "${delta / 3600}h ago"
    delta < 604800 -> "${delta / 86400}d ago"
    delta < 2592000 -> "${delta / 604800}w ago"
    else -> "${delta / 2592000}mo ago"
  }
}
