package sc.pirate.app.schedule

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import sc.pirate.app.ui.PirateOutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import sc.pirate.app.theme.PirateTokens
import sc.pirate.app.ui.PiratePrimaryButton

@Composable
internal fun UpcomingSessionsSection(
  bookings: List<BookingRow>,
  loading: Boolean,
  isAuthenticated: Boolean,
  cancellingBookingId: Long?,
  onJoin: (Long) -> Unit,
  onCancel: (Long) -> Unit,
) {
  if (loading) {
    Text(
      "Loading schedule...",
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
      color = PirateTokens.colors.textSecondary,
      style = MaterialTheme.typography.bodyLarge,
      textAlign = TextAlign.Center,
    )
    return
  }

  if (bookings.isEmpty()) {
    Text(
      "No scheduled sessions right now.",
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
      color = PirateTokens.colors.textSecondary,
      style = MaterialTheme.typography.bodyLarge,
      textAlign = TextAlign.Center,
    )
    return
  }

  bookings.forEach { booking ->
    val canJoin = isAuthenticated && (booking.status == BookingStatus.Upcoming || booking.status == BookingStatus.Live)
    val isCancelling = cancellingBookingId == booking.id
    val canCancel = isAuthenticated && !isCancelling && (booking.status == BookingStatus.Upcoming || booking.status == BookingStatus.Live)
    Card(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
      shape = RoundedCornerShape(PirateTokens.radius.lg),
      colors = CardDefaults.cardColors(containerColor = PirateTokens.colors.bgSurface),
      border = BorderStroke(1.dp, PirateTokens.colors.borderSoft),
    ) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text(booking.peerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = PirateTokens.colors.textPrimary)
          Surface(
            color = statusBgColor(booking.status),
            shape = RoundedCornerShape(999.dp),
          ) {
            Text(
              bookingStatusLabel(booking.status),
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Medium,
              color = statusFgColor(booking.status),
            )
          }
        }

        Text(
          text = formatDateTime(booking.startTimeMillis),
          style = MaterialTheme.typography.bodyLarge,
          color = PirateTokens.colors.textPrimary,
        )
        Text(
          text = "${booking.durationMinutes} min · $${booking.amountUsd}",
          style = MaterialTheme.typography.bodyLarge,
          color = PirateTokens.colors.textSecondary,
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          PirateOutlinedButton(onClick = { onCancel(booking.id) }, enabled = canCancel, modifier = Modifier.weight(1f)) {
            Text(if (isCancelling) "Canceling..." else "Cancel")
          }
          PiratePrimaryButton(
            text = if (booking.status == BookingStatus.Live) "In call" else "Join",
            onClick = { onJoin(booking.id) },
            enabled = canJoin,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
  }
}

// ── Availability ──

@Composable
internal fun AvailabilityHeaderCard(
  isAuthenticated: Boolean,
  basePrice: String?,
  editingPrice: Boolean,
  editValue: String,
  loading: Boolean,
  busy: Boolean,
  onEditStart: () -> Unit,
  onPriceChange: (String) -> Unit,
  onCancelEdit: () -> Unit,
  onSavePrice: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    shape = RoundedCornerShape(PirateTokens.radius.lg),
    colors = CardDefaults.cardColors(containerColor = PirateTokens.colors.bgSurface),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Base Price", style = MaterialTheme.typography.bodyLarge, color = PirateTokens.colors.textSecondary)
      if (editingPrice) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(value = editValue, onValueChange = onPriceChange, singleLine = true, label = { Text("aUSD") }, enabled = isAuthenticated && !busy, modifier = Modifier.weight(1f))
          PirateOutlinedButton(onClick = onCancelEdit, enabled = isAuthenticated && !busy) { Text("Cancel") }
          PiratePrimaryButton(
            text = "Save",
            onClick = onSavePrice,
            enabled = isAuthenticated && !busy,
          )
        }
      } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          if (loading) {
            Text("Loading...", style = MaterialTheme.typography.headlineSmall, color = PirateTokens.colors.textSecondary)
          } else {
            Text("$${basePrice ?: DEFAULT_BASE_PRICE}", style = MaterialTheme.typography.headlineSmall)
          }
          PirateOutlinedButton(
            onClick = onEditStart,
            enabled = isAuthenticated && !busy && !loading,
          ) { Text("Edit") }
        }
      }
    }
  }
}
