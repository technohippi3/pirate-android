package com.pirate.app.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CopySimple
import com.adamglin.phosphoricons.regular.FacebookLogo
import com.adamglin.phosphoricons.regular.ShareNetwork
import com.adamglin.phosphoricons.regular.TelegramLogo
import com.adamglin.phosphoricons.regular.TwitterLogo
import com.adamglin.phosphoricons.regular.WechatLogo
import com.pirate.app.ui.PirateSheetTitle

private const val FEED_SHARE_BASE_URL = "https://pirate.sc"

private const val FACEBOOK_PACKAGE = "com.facebook.katana"
private const val TELEGRAM_PACKAGE = "org.telegram.messenger"
private const val X_PACKAGE = "com.twitter.android"
private const val WECHAT_PACKAGE = "com.tencent.mm"

private data class FeedSharePayload(
  val url: String,
  val headline: String,
) {
  val plainText: String
    get() = "$headline\n$url"
}

private fun buildFeedShareHeadline(post: FeedPostResolved): String {
  val artist = post.songArtist?.trim().orEmpty()
  if (artist.isNotBlank()) {
    return "Prove you're a fan of $artist. Join the Pirate Social Club."
  }
  return "Join the Pirate Social Club."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeFeedShareSheet(
  sharePost: FeedPostResolved?,
  onDismiss: () -> Unit,
  onShowMessage: (String) -> Unit,
) {
  val post = sharePost ?: return
  val context = LocalContext.current
  val payload =
    remember(post.id, post.songTrackId, post.songTitle, post.songArtist) {
      buildFeedSharePayload(post)
    }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .padding(horizontal = 20.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      PirateSheetTitle(text = "Share")

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        ShareTargetAction(
          modifier = Modifier.weight(1f),
          icon = PhosphorIcons.Regular.FacebookLogo,
          label = "Facebook",
          onClick = {
            val launched =
              launchPackageShare(
                context = context,
                packageName = FACEBOOK_PACKAGE,
                payload = payload,
                fallbackUrl = buildFacebookShareUrl(payload),
              )
            if (!launched) {
              launchSystemShare(context, payload)
              onShowMessage("Facebook unavailable, opened system share")
            }
            onDismiss()
          },
        )
        ShareTargetAction(
          modifier = Modifier.weight(1f),
          icon = PhosphorIcons.Regular.TelegramLogo,
          label = "Telegram",
          onClick = {
            val launched =
              launchPackageShare(
                context = context,
                packageName = TELEGRAM_PACKAGE,
                payload = payload,
                fallbackUrl = buildTelegramShareUrl(payload),
              )
            if (!launched) {
              launchSystemShare(context, payload)
              onShowMessage("Telegram unavailable, opened system share")
            }
            onDismiss()
          },
        )
        ShareTargetAction(
          modifier = Modifier.weight(1f),
          icon = PhosphorIcons.Regular.TwitterLogo,
          label = "X",
          onClick = {
            val launched =
              launchPackageShare(
                context = context,
                packageName = X_PACKAGE,
                payload = payload,
                fallbackUrl = buildXShareUrl(payload),
              )
            if (!launched) {
              launchSystemShare(context, payload)
              onShowMessage("X unavailable, opened system share")
            }
            onDismiss()
          },
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        ShareTargetAction(
          modifier = Modifier.weight(1f),
          icon = PhosphorIcons.Regular.WechatLogo,
          label = "WeChat",
          onClick = {
            val launched =
              launchPackageShare(
                context = context,
                packageName = WECHAT_PACKAGE,
                payload = payload,
                fallbackUrl = null,
              )
            if (!launched) {
              launchSystemShare(context, payload)
              onShowMessage("WeChat unavailable, opened system share")
            }
            onDismiss()
          },
        )
        ShareTargetAction(
          modifier = Modifier.weight(1f),
          icon = PhosphorIcons.Regular.CopySimple,
          label = "Copy Link",
          onClick = {
            copyTextToClipboard(context, payload.url)
            onShowMessage("Link copied")
            onDismiss()
          },
        )
        ShareTargetAction(
          modifier = Modifier.weight(1f),
          icon = PhosphorIcons.Regular.ShareNetwork,
          label = "More",
          onClick = {
            val launched = launchSystemShare(context, payload)
            if (!launched) onShowMessage("No share apps available")
            onDismiss()
          },
        )
      }
    }
  }
}

@Composable
private fun ShareTargetAction(
  modifier: Modifier,
  icon: ImageVector,
  label: String,
  onClick: () -> Unit,
) {
  Column(
    modifier = modifier
      .clickable(onClick = onClick)
      .padding(vertical = 4.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(7.dp),
  ) {
    Box(
      modifier = Modifier
        .size(50.dp)
        .background(
          color = MaterialTheme.colorScheme.surfaceVariant,
          shape = CircleShape,
        ),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

private fun buildFeedSharePayload(post: FeedPostResolved): FeedSharePayload {
  val url = buildFeedShareUrl(post)
  return FeedSharePayload(
    url = url,
    headline = buildFeedShareHeadline(post),
  )
}

private fun buildFeedShareUrl(post: FeedPostResolved): String {
  val encodedPostId = Uri.encode(post.id.trim())
  if (encodedPostId.isBlank()) return FEED_SHARE_BASE_URL
  return "$FEED_SHARE_BASE_URL/post/$encodedPostId"
}

private fun buildFacebookShareUrl(payload: FeedSharePayload): String {
  val encodedUrl = Uri.encode(payload.url)
  val encodedHeadline = Uri.encode(payload.headline)
  return "https://www.facebook.com/sharer/sharer.php?u=$encodedUrl&quote=$encodedHeadline"
}

private fun buildTelegramShareUrl(payload: FeedSharePayload): String {
  val encodedUrl = Uri.encode(payload.url)
  val encodedHeadline = Uri.encode(payload.headline)
  return "https://t.me/share/url?url=$encodedUrl&text=$encodedHeadline"
}

private fun buildXShareUrl(payload: FeedSharePayload): String {
  val encodedUrl = Uri.encode(payload.url)
  val encodedHeadline = Uri.encode(payload.headline)
  return "https://x.com/intent/post?url=$encodedUrl&text=$encodedHeadline"
}

private fun launchPackageShare(
  context: Context,
  packageName: String,
  payload: FeedSharePayload,
  fallbackUrl: String?,
): Boolean {
  val packageIntent =
    Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_TEXT, payload.plainText)
      setPackage(packageName)
    }
  if (canLaunch(context, packageIntent)) {
    return launchIntent(context, packageIntent)
  }

  if (!fallbackUrl.isNullOrBlank()) {
    val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))
    if (canLaunch(context, fallbackIntent)) {
      return launchIntent(context, fallbackIntent)
    }
  }

  return false
}

private fun launchSystemShare(
  context: Context,
  payload: FeedSharePayload,
): Boolean {
  val intent =
    Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_TEXT, payload.plainText)
    }
  val chooser = Intent.createChooser(intent, "Share")
  return launchIntent(context, chooser)
}

private fun canLaunch(
  context: Context,
  intent: Intent,
): Boolean {
  return intent.resolveActivity(context.packageManager) != null
}

private fun launchIntent(
  context: Context,
  intent: Intent,
): Boolean {
  return try {
    if (context !is Activity) {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    true
  } catch (_: ActivityNotFoundException) {
    false
  }
}

private fun copyTextToClipboard(
  context: Context,
  text: String,
) {
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
  clipboard.setPrimaryClip(ClipData.newPlainText("Pirate share link", text))
}
