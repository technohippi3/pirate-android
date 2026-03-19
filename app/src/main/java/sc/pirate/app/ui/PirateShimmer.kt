package sc.pirate.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush

@Composable
fun PirateShimmer(
  modifier: Modifier = Modifier,
  content: (@Composable BoxScope.() -> Unit)? = null,
) {
  val base = MaterialTheme.colorScheme.surfaceVariant
  val highlight = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
  val transition = rememberInfiniteTransition(label = "pirate-shimmer")
  val offset = transition.animateFloat(
    initialValue = -1f,
    targetValue = 2f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1150, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "pirate-shimmer-offset",
  )

  val brush = Brush.linearGradient(
    colors = listOf(base.copy(alpha = 0.84f), highlight, base.copy(alpha = 0.84f)),
    start = androidx.compose.ui.geometry.Offset(offset.value * 800f, 0f),
    end = androidx.compose.ui.geometry.Offset((offset.value + 1f) * 800f, 800f),
  )

  Box(modifier = modifier.background(brush)) {
    content?.invoke(this)
  }
}

@Composable
fun PirateShimmerFullscreen() {
  PirateShimmer(modifier = Modifier.fillMaxSize())
}
