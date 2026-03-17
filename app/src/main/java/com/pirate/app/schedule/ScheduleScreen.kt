package com.pirate.app.schedule

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import com.pirate.app.ui.PirateIconButton
import androidx.compose.material3.MaterialTheme
import com.pirate.app.ui.PirateOutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pirate.app.theme.PiratePalette
import com.pirate.app.tempo.SessionKeyManager
import com.pirate.app.tempo.TempoPasskeyManager
import com.pirate.app.tempo.TempoSessionKeyApi
import com.pirate.app.ui.PirateMobileHeader
import com.pirate.app.util.shortAddress
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val DEFAULT_BASE_PRICE = "25.00"
internal const val SLOT_DURATION_MINS = 20

internal enum class BookingStatus {
  Live,
  Upcoming,
  Completed,
  Cancelled,
}

internal enum class SlotStatus {
  Open,
  Booked,
  Cancelled,
  Settled,
}

internal enum class AvailabilityFilter(val label: String) {
  All("All slots"),
  Available("Available"),
  Booked("Booked"),
}

internal data class BookingRow(
  val id: Long,
  val bookingId: Long? = null,
  val peerName: String,
  val peerAddress: String,
  val startTimeMillis: Long,
  val durationMinutes: Int,
  val status: BookingStatus,
  val isHost: Boolean,
  val amountUsd: String,
)

internal data class SlotRow(
  val id: Int,
  val slotId: Long? = null,
  val startTimeMillis: Long,
  val durationMinutes: Int,
  val status: SlotStatus,
  val guestName: String? = null,
  val priceUsd: String = DEFAULT_BASE_PRICE,
)

@Composable
fun ScheduleScreen(
  isAuthenticated: Boolean,
  userAddress: String?,
  tempoAccount: TempoPasskeyManager.PasskeyAccount?,
  onOpenDrawer: () -> Unit,
  onOpenAvailability: () -> Unit,
  onJoinBooking: (Long) -> Unit,
  onShowMessage: (String) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var upcomingBookings by remember { mutableStateOf<List<BookingRow>>(emptyList()) }
  var bookingsLoading by remember { mutableStateOf(false) }
  var pendingJoinBookingId by remember { mutableStateOf<Long?>(null) }
  var pendingCancelBookingId by remember { mutableStateOf<Long?>(null) }

  suspend fun refreshBookings() {
    if (!isAuthenticated || userAddress.isNullOrBlank()) {
      bookingsLoading = false
      upcomingBookings = emptyList()
      return
    }

    bookingsLoading = true
    try {
      val rows = withContext(Dispatchers.IO) {
        TempoSessionEscrowApi.fetchUpcomingUserBookings(userAddress, maxResults = 20)
      }
      upcomingBookings = rows.map { row ->
        BookingRow(
          id = row.bookingId,
          bookingId = row.bookingId,
          peerName = shortAddress(row.counterpartyAddress, minLengthToShorten = 10),
          peerAddress = row.counterpartyAddress,
          startTimeMillis = row.startTimeSec * 1_000L,
          durationMinutes = row.durationMins,
          status = if (row.isLive) BookingStatus.Live else BookingStatus.Upcoming,
          isHost = row.isHost,
          amountUsd = row.amountUsd,
        )
      }
    } catch (err: Throwable) {
      if (err is CancellationException) throw err
      onShowMessage("Failed to load schedule: ${err.message ?: "unknown error"}")
      upcomingBookings = emptyList()
    } finally {
      bookingsLoading = false
    }
  }

  val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    val bookingId = pendingJoinBookingId
    pendingJoinBookingId = null
    if (bookingId == null) return@rememberLauncherForActivityResult
    if (!granted) {
      onShowMessage("Microphone permission is required for session calls.")
      return@rememberLauncherForActivityResult
    }
    onJoinBooking(bookingId)
  }

  LaunchedEffect(isAuthenticated, userAddress) {
    refreshBookings()
  }

  Column(
    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
  ) {
    PirateMobileHeader(
      title = "Schedule",
      isAuthenticated = isAuthenticated,
      onAvatarPress = onOpenDrawer,
      rightSlot = {
        PirateIconButton(onClick = onOpenAvailability) {
          Icon(
            PhosphorIcons.Regular.CalendarPlus,
            contentDescription = "Edit availability",
            tint = MaterialTheme.colorScheme.onBackground,
          )
        }
      },
    )

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(bottom = 12.dp),
    ) {
      // ── Upcoming Sessions ──
      item {
        UpcomingSessionsSection(
          bookings = upcomingBookings,
          loading = bookingsLoading,
          isAuthenticated = isAuthenticated,
          cancellingBookingId = pendingCancelBookingId,
          onJoin = join@{ bookingId ->
            val selected = upcomingBookings.firstOrNull { it.id == bookingId } ?: return@join
            val targetBookingId = selected.bookingId ?: selected.id
            val hasMicPermission =
              ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (hasMicPermission) {
              onJoinBooking(targetBookingId)
            } else {
              pendingJoinBookingId = targetBookingId
              permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            upcomingBookings = upcomingBookings.map { row ->
              if (row.id != bookingId) row
              else if (row.status == BookingStatus.Upcoming) row.copy(status = BookingStatus.Live)
              else row
            }
          },
          onCancel = cancel@{ bookingId ->
            val booking = upcomingBookings.firstOrNull { it.id == bookingId } ?: return@cancel
            val chainBookingId = booking.bookingId ?: booking.id
            if (chainBookingId <= 0L) {
              upcomingBookings = upcomingBookings.filterNot { it.id == bookingId }
              return@cancel
            }

            if (!isAuthenticated || userAddress.isNullOrBlank() || tempoAccount == null) {
              onShowMessage("Sign in with Tempo to cancel bookings.")
              return@cancel
            }

            pendingCancelBookingId = bookingId
            scope.launch {
              val sessionKey = ensureScheduleSessionKey(
                context = context,
                account = tempoAccount,
                onShowMessage = onShowMessage,
              )
              if (sessionKey == null) {
                pendingCancelBookingId = null
                return@launch
              }

              val result = TempoSessionEscrowApi.cancelBooking(
                userAddress = tempoAccount.address,
                sessionKey = sessionKey,
                bookingId = chainBookingId,
                asHost = booking.isHost,
              )

              if (result.success) {
                val fundingPath = if (result.usedSelfPayFallback) "self-pay fallback" else "sponsored"
                onShowMessage("Cancel submitted ($fundingPath): ${shortAddress(result.txHash ?: "", minLengthToShorten = 10)}")
                refreshBookings()
              } else {
                onShowMessage("Cancel failed: ${result.error ?: "unknown error"}")
              }
              pendingCancelBookingId = null
            }
          },
        )
      }
    }
  }
}
