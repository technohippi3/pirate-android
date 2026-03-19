package sc.pirate.app.ui

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.*
import com.adamglin.phosphoricons.regular.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val VerifiedBlue = Color(0xFF1D9BF0)

@Composable
fun VerifiedSealBadge(
  modifier: Modifier = Modifier,
  size: Dp = 18.dp,
) {
  Box(
    modifier = modifier.size(size),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = PhosphorIcons.Fill.Seal,
      contentDescription = null,
      tint = VerifiedBlue,
      modifier = Modifier.size(size),
    )
    Icon(
      imageVector = PhosphorIcons.Regular.Check,
      contentDescription = "Verified",
      tint = Color.White,
      modifier = Modifier.size(size * 0.52f),
    )
  }
}
