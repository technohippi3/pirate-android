package com.pirate.app.schedule

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.pirate.app.tempo.SessionKeyManager
import com.pirate.app.tempo.TempoPasskeyManager
import com.pirate.app.tempo.TempoSessionKeyApi
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.compose.ui.graphics.Color

internal fun bookingStatusLabel(status: BookingStatus): String = when (status) {
  BookingStatus.Live -> "Live"
  BookingStatus.Upcoming -> "Upcoming"
  BookingStatus.Completed -> "Completed"
  BookingStatus.Cancelled -> "Cancelled"
}

internal fun statusBgColor(status: BookingStatus): Color = when (status) {
  BookingStatus.Live -> Color(0xFF89B4FA).copy(alpha = 0.15f)
  BookingStatus.Upcoming -> Color(0xFFA3A3A3).copy(alpha = 0.15f)
  BookingStatus.Completed -> Color(0xFF404040)
  BookingStatus.Cancelled -> Color(0xFF404040)
}

internal fun statusFgColor(status: BookingStatus): Color = when (status) {
  BookingStatus.Live -> Color(0xFF89B4FA)
  BookingStatus.Upcoming -> Color(0xFFD4D4D4)
  BookingStatus.Completed -> Color(0xFFA3A3A3)
  BookingStatus.Cancelled -> Color(0xFFA3A3A3)
}

internal fun hostSlotStatusToUi(status: HostSlotStatus): SlotStatus = when (status) {
  HostSlotStatus.Open -> SlotStatus.Open
  HostSlotStatus.Booked -> SlotStatus.Booked
  HostSlotStatus.Cancelled -> SlotStatus.Cancelled
  HostSlotStatus.Settled -> SlotStatus.Settled
}

internal fun normalizePriceInput(value: String): String? {
  val trimmed = value.trim()
  if (trimmed.isBlank()) return null
  val parsed = trimmed.toBigDecimalOrNull() ?: return null
  if (parsed <= java.math.BigDecimal.ZERO) return null
  return parsed.setScale(2, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString()
}

internal fun formatDateTime(millis: Long): String =
  SimpleDateFormat("EEE, MMM d • h:mm a", Locale.getDefault()).format(Date(millis))

internal fun formatTime(hour: Int, minute: Int): String {
  val suffix = if (hour >= 12) "PM" else "AM"
  val displayHour = ((hour + 11) % 12) + 1
  return String.format(Locale.getDefault(), "%d:%02d %s", displayHour, minute, suffix)
}

internal fun slotStartMillis(
  selectedDay: Long,
  hour: Int,
  minute: Int,
): Long {
  val cal = Calendar.getInstance()
  cal.timeInMillis = selectedDay
  cal.set(Calendar.HOUR_OF_DAY, hour)
  cal.set(Calendar.MINUTE, minute)
  cal.set(Calendar.SECOND, 0)
  cal.set(Calendar.MILLISECOND, 0)
  return cal.timeInMillis
}

internal fun todayWeekdayIndex(): Int {
  val cal = Calendar.getInstance()
  return (cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY).coerceIn(0, 6)
}

internal fun buildWeekDates(weekOffset: Int): List<Long> {
  val now = Calendar.getInstance()
  val sundayIndex = (now.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY).coerceIn(0, 6)
  now.add(Calendar.DAY_OF_MONTH, -sundayIndex)
  now.add(Calendar.WEEK_OF_YEAR, weekOffset)

  val out = ArrayList<Long>(7)
  repeat(7) {
    out.add(now.timeInMillis)
    now.add(Calendar.DAY_OF_MONTH, 1)
  }
  return out
}

internal fun formatWeekLabel(weekDates: List<Long>): String {
  if (weekDates.size != 7) return ""
  val start = weekDates.first()
  val end = weekDates.last()
  val startMonth = SimpleDateFormat("MMM", Locale.getDefault()).format(Date(start))
  val endMonth = SimpleDateFormat("MMM", Locale.getDefault()).format(Date(end))
  val startDay = SimpleDateFormat("d", Locale.getDefault()).format(Date(start))
  val endDay = SimpleDateFormat("d", Locale.getDefault()).format(Date(end))
  val year = SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(start))
  return if (startMonth == endMonth) {
    "$startMonth $startDay - $endDay, $year"
  } else {
    "$startMonth $startDay - $endMonth $endDay, $year"
  }
}

internal fun formatSelectedDay(selectedDay: Long): String =
  SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(selectedDay))

internal fun isSameDay(
  first: Long,
  second: Long,
): Boolean {
  val a = Calendar.getInstance().apply { timeInMillis = first }
  val b = Calendar.getInstance().apply { timeInMillis = second }
  return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
    a.get(Calendar.MONTH) == b.get(Calendar.MONTH) &&
    a.get(Calendar.DAY_OF_MONTH) == b.get(Calendar.DAY_OF_MONTH)
}

internal fun availabilitySlotStatusLabel(status: SlotStatus?): String = when (status) {
  SlotStatus.Open -> "Available"
  SlotStatus.Booked -> "Booked"
  SlotStatus.Cancelled -> "Unavailable"
  SlotStatus.Settled -> "Settled"
  null -> "Unavailable"
}

internal enum class SlotApplyScope(val label: String) {
  ThisSlot("This slot"),
  SameWeekday("This weekday"),
  Weekdays("All weekdays"),
  AllDays("All days"),
}

internal enum class SlotApplyRange(val label: String, val weeks: Int) {
  OneWeek("This week", 1),
  FourWeeks("Next 4 weeks", 4),
  EightWeeks("Next 8 weeks", 8),
}

internal data class ChinaDemandHint(
  val label: String,
  val recommendedPriceUsd: String?,
)

private val SHANGHAI_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

internal fun buildChinaDemandHint(
  slotStartMillis: Long,
  basePriceUsd: String?,
): ChinaDemandHint {
  val shanghai = Instant.ofEpochMilli(slotStartMillis).atZone(SHANGHAI_ZONE)
  val weekend = shanghai.dayOfWeek == DayOfWeek.SATURDAY || shanghai.dayOfWeek == DayOfWeek.SUNDAY
  val hour = shanghai.hour

  val (tier, multiplier) =
    when {
      weekend && hour in 9..22 -> "Peak China demand" to BigDecimal("1.25")
      !weekend && hour in 18..21 -> "Peak China demand" to BigDecimal("1.25")
      weekend && hour in 7..8 -> "Shoulder China demand" to BigDecimal("1.10")
      !weekend && (hour in 12..17 || hour in 22..23) -> "Shoulder China demand" to BigDecimal("1.10")
      else -> "Off-peak China demand" to BigDecimal("1.00")
    }

  val recommended =
    basePriceUsd
      ?.toBigDecimalOrNull()
      ?.takeIf { it > BigDecimal.ZERO }
      ?.multiply(multiplier)
      ?.setScale(2, RoundingMode.HALF_UP)
      ?.stripTrailingZeros()
      ?.toPlainString()

  val label = "$tier · ${shanghai.dayOfWeek.name.take(3)} ${"%02d".format(shanghai.hour)}:${"%02d".format(shanghai.minute)} CST"
  return ChinaDemandHint(label = label, recommendedPriceUsd = recommended)
}

internal fun addDaysKeepingLocalMidnight(
  baseDayMillis: Long,
  days: Int,
): Long {
  val cal = Calendar.getInstance()
  cal.timeInMillis = baseDayMillis
  cal.add(Calendar.DAY_OF_MONTH, days)
  return cal.timeInMillis
}

internal fun Context.findActivity(): Activity? {
  var current: Context? = this
  while (current is ContextWrapper) {
    if (current is Activity) return current
    current = current.baseContext
  }
  return null
}

internal suspend fun ensureScheduleSessionKey(
  context: Context,
  account: TempoPasskeyManager.PasskeyAccount,
  onShowMessage: (String) -> Unit,
): SessionKeyManager.SessionKey? {
  val existing =
    SessionKeyManager.load(context)?.takeIf {
      SessionKeyManager.isValid(it, ownerAddress = account.address)
    }
  if (existing != null) return existing

  val activity = context.findActivity()
  if (activity == null) {
    onShowMessage("Unable to open passkey prompt in this context.")
    return null
  }

  onShowMessage("Authorizing Tempo session key...")
  val auth =
    TempoSessionKeyApi.authorizeSessionKey(
      activity = activity,
      account = account,
    )
  val authorized =
    auth.sessionKey?.takeIf {
      auth.success && SessionKeyManager.isValid(it, ownerAddress = account.address)
    }
  if (authorized == null) {
    onShowMessage(auth.error ?: "Session key authorization failed.")
    return null
  }

  onShowMessage("Session key authorized.")
  return authorized
}
