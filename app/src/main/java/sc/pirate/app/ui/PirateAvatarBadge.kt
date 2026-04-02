package sc.pirate.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import sc.pirate.app.util.resolveAvatarUrl

@Composable
fun PirateAvatarBadge(
  avatarUri: String?,
  fallbackLabel: String,
  modifier: Modifier = Modifier,
  size: Dp,
  shape: Shape = CircleShape,
  containerColor: androidx.compose.ui.graphics.Color,
  contentColor: androidx.compose.ui.graphics.Color,
  border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  contentDescription: String? = "Avatar",
) {
  val avatarUrl = resolveAvatarUrl(avatarUri)
  val painter = rememberAsyncImagePainter(model = avatarUrl)
  val imageState = painter.state

  Surface(
    modifier = modifier.size(size),
    shape = shape,
    color = containerColor,
    border = border,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(
        text = fallbackLabel,
        color = contentColor,
        fontWeight = FontWeight.Bold,
      )

      if (!avatarUrl.isNullOrBlank() && imageState !is AsyncImagePainter.State.Error) {
        Image(
          painter = painter,
          contentDescription = contentDescription,
          modifier = Modifier.fillMaxSize().clip(shape),
          contentScale = ContentScale.Crop,
        )
      }
    }
  }
}
