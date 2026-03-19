package sc.pirate.app.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.Tag
import sc.pirate.app.ui.PirateSheetTitle
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeFeedTaggedItemsSheet(
  post: FeedPostResolved?,
  onDismiss: () -> Unit,
  onShowMessage: (String) -> Unit,
) {
  val sheetPost = post ?: return
  val context = LocalContext.current
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
      PirateSheetTitle(text = "Items in video")

      sheetPost.taggedItems.forEach { item ->
        TaggedItemSheetCard(
          item = item,
          onOpen = {
            if (!openTaggedItem(context, item)) {
              onShowMessage("Unable to open item link")
            }
          },
        )
      }
    }
  }
}

@Composable
private fun TaggedItemSheetCard(
  item: FeedTaggedItem,
  onOpen: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onOpen)
        .padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(68.dp)
          .clip(MaterialTheme.shapes.medium)
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        val imageModel = item.imageUrl ?: item.images.firstOrNull()
        if (!imageModel.isNullOrBlank()) {
          AsyncImage(
            model = imageModel,
            contentDescription = item.title,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          Icon(
            imageVector = PhosphorIcons.Regular.Tag,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = item.title,
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        val detailLine = listOfNotNull(item.brand, item.size, merchantLabel(item.merchant)).joinToString(" • ")
        if (detailLine.isNotBlank()) {
          Text(
            text = detailLine,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Text(
          text = formatTaggedItemPrice(item),
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Icon(
        imageVector = PhosphorIcons.Regular.CaretRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun openTaggedItem(
  context: Context,
  item: FeedTaggedItem,
): Boolean {
  val url = item.canonicalUrl.trim().ifBlank { item.requestedUrl.trim() }
  if (url.isBlank()) return false
  val intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
      addCategory(Intent.CATEGORY_BROWSABLE)
      if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  return runCatching {
    context.startActivity(intent)
    true
  }.getOrElse { error ->
    if (error is ActivityNotFoundException) return false
    false
  }
}

private fun merchantLabel(raw: String): String =
  when (raw.trim().lowercase()) {
    "vestiaire" -> "Vestiaire"
    "therealreal" -> "The RealReal"
    else -> raw.trim()
  }

private fun formatTaggedItemPrice(item: FeedTaggedItem): String {
  val amount = item.price
  if (amount == null) return item.condition ?: "Price unavailable"
  val currency = item.currency?.trim().orEmpty()
  if (currency.isBlank() || currency.equals("USD", ignoreCase = true)) {
    val numeric =
      if (amount % 1.0 == 0.0) {
        amount.toLong().toString()
      } else {
        String.format(Locale.US, "%.2f", amount).trimEnd('0').trimEnd('.')
      }
    return "$$numeric"
  }
  return runCatching {
    val formatter = NumberFormat.getCurrencyInstance()
    formatter.currency = Currency.getInstance(currency)
    formatter.format(amount)
  }.getOrElse {
    amount.toString()
  }
}
