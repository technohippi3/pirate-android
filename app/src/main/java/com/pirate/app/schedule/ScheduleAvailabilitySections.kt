package com.pirate.app.schedule

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import com.pirate.app.ui.PirateOutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import com.pirate.app.theme.PiratePalette
import com.pirate.app.ui.PiratePrimaryButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun AvailabilityControls(
  acceptingBookings: Boolean,
  onAcceptingChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("Accepting bookings", style = MaterialTheme.typography.bodyLarge)
    Switch(checked = acceptingBookings, onCheckedChange = onAcceptingChange)
  }
}

@Composable
internal fun WeekHeader(
  weekLabel: String,
  onPreviousWeek: () -> Unit,
  onNextWeek: () -> Unit,
  onToday: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      modifier = Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)).padding(8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(modifier = Modifier.size(28.dp).clickable(onClick = onPreviousWeek), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(contentAlignment = Alignment.Center) { Text("◀", color = MaterialTheme.colorScheme.onSurfaceVariant) }
      }
      Text(weekLabel, fontWeight = FontWeight.SemiBold)
      Surface(modifier = Modifier.size(28.dp).clickable(onClick = onNextWeek), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(contentAlignment = Alignment.Center) { Text("▶", color = MaterialTheme.colorScheme.onSurfaceVariant) }
      }
    }
    PirateOutlinedButton(onClick = onToday) { Text("Today") }
  }
}

@Composable
internal fun DayStrip(
  weekDates: List<Long>,
  selectedDayIndex: Int,
  onDaySelected: (Int) -> Unit,
  onWeekSwipe: (Int) -> Unit,
) {
  var dragDelta by remember(weekDates) { mutableStateOf(0f) }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 10.dp)
      .pointerInput(weekDates) {
        detectHorizontalDragGestures(
          onHorizontalDrag = { _, dragAmount ->
            dragDelta += dragAmount
          },
          onDragCancel = {
            dragDelta = 0f
          },
          onDragEnd = {
            when {
              dragDelta > 72f -> onWeekSwipe(-1)
              dragDelta < -72f -> onWeekSwipe(1)
            }
            dragDelta = 0f
          },
        )
      },
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    weekDates.forEachIndexed { index, day ->
      val dayFormatter = SimpleDateFormat("EEE", Locale.getDefault())
      val dateFormatter = SimpleDateFormat("d", Locale.getDefault())
      val isSelected = index == selectedDayIndex
      val isToday = isSameDay(day, System.currentTimeMillis())

      Column(
        modifier = Modifier.weight(1f).height(94.dp).clickable { onDaySelected(index) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          dayFormatter.format(Date(day)),
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
          modifier = Modifier.size(44.dp),
          shape = CircleShape,
          color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
          border = BorderStroke(
            if (isSelected) 2.dp else 1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
          ),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Text(
              dateFormatter.format(Date(day)),
              style = MaterialTheme.typography.bodyLarge,
              fontWeight = FontWeight.SemiBold,
              color =
                if (isSelected) {
                  MaterialTheme.colorScheme.onPrimary
                } else if (isToday) {
                  MaterialTheme.colorScheme.secondary
                } else {
                  MaterialTheme.colorScheme.onSurface
                },
            )
          }
        }
        Spacer(modifier = Modifier.height(12.dp))
      }
    }
  }
}

@Composable
internal fun AvailabilityFilterBar(
  filterLabel: String,
  onOpenFilter: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.End,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    PirateOutlinedButton(onClick = onOpenFilter) { Text("Filter · $filterLabel") }
  }
}

@Composable
internal fun AvailabilitySectionHeader(
  title: String,
  subtitle: String,
  filterLabel: String,
  onOpenFilter: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = PiratePalette.TextMuted)
    }
    PirateOutlinedButton(onClick = onOpenFilter) { Text("Filter · $filterLabel") }
  }
}

@Composable
internal fun AvailabilitySwitchRow(
  timeLabel: String,
  status: SlotStatus?,
  checked: Boolean,
  enabled: Boolean,
  onRowClick: () -> Unit,
) {
  val statusLabel = availabilitySlotStatusLabel(status)
  val statusColor =
    when (status) {
      SlotStatus.Booked -> MaterialTheme.colorScheme.tertiary
      SlotStatus.Open -> MaterialTheme.colorScheme.primary
      else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
  Surface(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).clickable(enabled = enabled) { onRowClick() },
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(timeLabel, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(statusLabel, style = MaterialTheme.typography.bodyMedium, color = statusColor)
      }
      Switch(
        checked = checked,
        onCheckedChange = { onRowClick() },
        enabled = enabled,
      )
    }
  }
}

@Composable
internal fun SlotEditorSheet(
  visible: Boolean,
  timeLabel: String,
  targetAvailable: Boolean,
  onTargetAvailableChange: (Boolean) -> Unit,
  priceUsd: String,
  onPriceUsdChange: (String) -> Unit,
  demandHint: String,
  recommendedPriceUsd: String?,
  selectedScope: SlotApplyScope,
  onScopeChange: (SlotApplyScope) -> Unit,
  selectedRange: SlotApplyRange,
  onRangeChange: (SlotApplyRange) -> Unit,
  previewSummary: String,
  busy: Boolean,
  onApply: () -> Unit,
  onDismiss: () -> Unit,
) {
  if (!visible) return
  val scrimInteraction = remember { MutableInteractionSource() }
  val horizontalScroll = rememberScrollState()

  Box(modifier = Modifier.fillMaxSize()) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.35f))
        .clickable(
          indication = null,
          interactionSource = scrimInteraction,
          onClick = onDismiss,
        ),
    )
    Surface(
      modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(),
      shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 6.dp,
      shadowElevation = 8.dp,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Edit $timeLabel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          PirateOutlinedButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Available", style = MaterialTheme.typography.bodyLarge)
          Switch(checked = targetAvailable, onCheckedChange = onTargetAvailableChange, enabled = !busy)
        }

        if (targetAvailable) {
          OutlinedTextField(
            value = priceUsd,
            onValueChange = onPriceUsdChange,
            singleLine = true,
            enabled = !busy,
            label = { Text("Price (aUSD)") },
            modifier = Modifier.fillMaxWidth(),
          )
          Text(
            demandHint,
            style = MaterialTheme.typography.bodyMedium,
            color = PiratePalette.TextMuted,
          )
          if (!recommendedPriceUsd.isNullOrBlank()) {
            PirateOutlinedButton(
              onClick = { onPriceUsdChange(recommendedPriceUsd) },
              enabled = !busy,
            ) { Text("Use Recommended ($$recommendedPriceUsd)") }
          }
        }

        Text("Apply to", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(horizontalScroll),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          SlotApplyScope.entries.forEach { option ->
            if (option == selectedScope) {
              PiratePrimaryButton(text = option.label, onClick = { onScopeChange(option) }, enabled = !busy)
            } else {
              PirateOutlinedButton(onClick = { onScopeChange(option) }, enabled = !busy) { Text(option.label) }
            }
          }
        }

        if (selectedScope != SlotApplyScope.ThisSlot) {
          Text("Range", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(horizontalScroll),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            SlotApplyRange.entries.forEach { option ->
              if (option == selectedRange) {
                PiratePrimaryButton(text = option.label, onClick = { onRangeChange(option) }, enabled = !busy)
              } else {
                PirateOutlinedButton(onClick = { onRangeChange(option) }, enabled = !busy) { Text(option.label) }
              }
            }
          }
        }

        Text(
          previewSummary,
          style = MaterialTheme.typography.bodyMedium,
          color = PiratePalette.TextMuted,
        )

        PiratePrimaryButton(
          text = "Apply Changes",
          modifier = Modifier.fillMaxWidth(),
          onClick = onApply,
          enabled = !busy,
          loading = busy,
        )
      }
    }
  }
}

@Composable
internal fun AvailabilityFilterDrawer(
  visible: Boolean,
  selectedFilter: AvailabilityFilter,
  onSelectFilter: (AvailabilityFilter) -> Unit,
  onDismiss: () -> Unit,
) {
  if (!visible) return
  val scrimInteraction = remember { MutableInteractionSource() }
  val options =
    listOf(
      AvailabilityFilter.All,
      AvailabilityFilter.Available,
      AvailabilityFilter.Booked,
    )

  Box(modifier = Modifier.fillMaxSize()) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.35f))
        .clickable(
          indication = null,
          interactionSource = scrimInteraction,
          onClick = onDismiss,
        ),
    )
    Surface(
      modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(),
      shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 6.dp,
      shadowElevation = 8.dp,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text("Filter Slots", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
          "Choose which time rows to show.",
          style = MaterialTheme.typography.bodyMedium,
          color = PiratePalette.TextMuted,
        )
        options.forEach { option ->
          Row(
            modifier = Modifier.fillMaxWidth().clickable { onSelectFilter(option) },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = selectedFilter == option,
              onClick = { onSelectFilter(option) },
            )
            Text(option.label, style = MaterialTheme.typography.bodyLarge)
          }
        }
        PirateOutlinedButton(
          modifier = Modifier.fillMaxWidth(),
          onClick = onDismiss,
        ) { Text("Done") }
      }
    }
  }
}
