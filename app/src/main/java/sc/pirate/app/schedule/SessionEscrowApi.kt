package sc.pirate.app.schedule

import android.content.Context
import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal enum class EscrowBookingStatus(val code: Int) {
  None(0),
  Booked(1),
  Cancelled(2),
  Attested(3),
  Disputed(4),
  Resolved(5),
  Finalized(6),
}

internal enum class EscrowSlotStatus(val code: Int) {
  Open(0),
  Booked(1),
  Cancelled(2),
  Settled(3),
}

internal data class EscrowBooking(
  val id: Long,
  val slotId: Long,
  val guest: String,
  val amountRaw: BigInteger,
  val status: EscrowBookingStatus,
)

internal data class EscrowSlot(
  val id: Long,
  val host: String,
  val startTimeSec: Long,
  val durationMins: Int,
  val priceRaw: BigInteger,
  val status: EscrowSlotStatus,
)

data class UpcomingBooking(
  val bookingId: Long,
  val counterpartyAddress: String,
  val startTimeSec: Long,
  val durationMins: Int,
  val isHost: Boolean,
  val amountUsd: String,
  val isLive: Boolean,
)

enum class HostSlotStatus {
  Open,
  Booked,
  Cancelled,
  Settled,
}

data class HostAvailabilitySlot(
  val slotId: Long,
  val startTimeSec: Long,
  val durationMins: Int,
  val status: HostSlotStatus,
  val priceUsd: String,
)

data class SlotPlanEntry(
  val startTimeSec: Long,
  val priceUsd: String,
)

data class EscrowTxResult(
  val success: Boolean,
  val txHash: String? = null,
  val error: String? = null,
)

object SessionEscrowApi {
  private const val MAX_BOOKING_SCAN = 300
  private const val MAX_SLOT_SCAN = 400

  suspend fun fetchUpcomingUserBookings(
    userAddress: String,
    maxResults: Int = 20,
  ): List<UpcomingBooking> = withContext(Dispatchers.IO) {
    val user = normalizeAddress(userAddress) ?: return@withContext emptyList()
    val nextBookingId = getNextBookingId() ?: return@withContext emptyList()
    if (nextBookingId <= 1L) return@withContext emptyList()

    val startId = maxOf(1L, nextBookingId - MAX_BOOKING_SCAN)
    val bookingIds = (startId until nextBookingId).toList()
    if (bookingIds.isEmpty()) return@withContext emptyList()

    val bookings = coroutineScope {
      bookingIds.map { id ->
        async { getBooking(id) }
      }.awaitAll()
    }.filterNotNull()
      .filter { it.status != EscrowBookingStatus.None && it.status != EscrowBookingStatus.Finalized }

    if (bookings.isEmpty()) return@withContext emptyList()

    val slotsById = coroutineScope {
      bookings
        .map { it.slotId }
        .distinct()
        .map { slotId ->
          async { slotId to getSlot(slotId) }
        }
        .awaitAll()
        .mapNotNull { (slotId, slot) -> slot?.let { slotId to it } }
        .toMap()
    }

    val nowSec = nowSec()
    val rows = bookings.mapNotNull { booking ->
      val slot = slotsById[booking.slotId] ?: return@mapNotNull null
      val host = normalizeAddress(slot.host) ?: return@mapNotNull null
      val guest = normalizeAddress(booking.guest) ?: return@mapNotNull null
      val isHost = host == user
      val isGuest = guest == user
      if (!isHost && !isGuest) return@mapNotNull null
      if (booking.status != EscrowBookingStatus.Booked) return@mapNotNull null

      val endSec = slot.startTimeSec + slot.durationMins.toLong() * 60L
      if (endSec < nowSec) return@mapNotNull null

      UpcomingBooking(
        bookingId = booking.id,
        counterpartyAddress = if (isHost) guest else host,
        startTimeSec = slot.startTimeSec,
        durationMins = slot.durationMins,
        isHost = isHost,
        amountUsd = formatTokenAmount(booking.amountRaw),
        isLive = nowSec in slot.startTimeSec until endSec,
      )
    }

    rows.sortedBy { it.startTimeSec }.take(maxResults.coerceAtLeast(1))
  }

  suspend fun fetchHostAvailabilitySlots(
    hostAddress: String,
    maxResults: Int = 200,
  ): List<HostAvailabilitySlot> = withContext(Dispatchers.IO) {
    val host = normalizeAddress(hostAddress) ?: return@withContext emptyList()
    val nextSlotId = getNextSlotId() ?: return@withContext emptyList()
    if (nextSlotId <= 1L) return@withContext emptyList()

    val startId = maxOf(1L, nextSlotId - MAX_SLOT_SCAN)
    val slotIds = (startId until nextSlotId).toList()
    if (slotIds.isEmpty()) return@withContext emptyList()

    val slots = coroutineScope {
      slotIds.map { id ->
        async { getSlot(id) }
      }.awaitAll()
    }.filterNotNull()

    val nowSec = nowSec()
    slots
      .asSequence()
      .filter { normalizeAddress(it.host) == host }
      .filter { slot ->
        val endSec = slot.startTimeSec + slot.durationMins.toLong() * 60L
        endSec >= nowSec
      }
      .map { slot ->
        HostAvailabilitySlot(
          slotId = slot.id,
          startTimeSec = slot.startTimeSec,
          durationMins = slot.durationMins,
          status = mapSlotStatus(slot.status),
          priceUsd = formatTokenAmount(slot.priceRaw),
        )
      }
      .sortedBy { it.startTimeSec }
      .take(maxResults.coerceAtLeast(1))
      .toList()
  }

  suspend fun fetchHostBasePriceUsd(hostAddress: String): String? = withContext(Dispatchers.IO) {
    val host = normalizeAddress(hostAddress) ?: return@withContext null
    val data = "0x${functionSelector("hostBasePrice(address)")}${encodeAddressWord(host)}"
    val result = ethCall(data) ?: return@withContext null
    val words = splitWords(result)
    if (words.isEmpty()) return@withContext null
    val raw = parseWordUint(words[0])
    if (raw <= BigInteger.ZERO) return@withContext null
    formatTokenAmount(raw)
  }

  suspend fun setHostBasePrice(
    context: Context,
    userAddress: String,
    priceUsd: String,
  ): EscrowTxResult {
    val priceRaw = parseUsdToRaw(priceUsd)
      ?: return EscrowTxResult(success = false, error = "Enter a valid positive base price.")

    val callData = encodeFunctionCall(
      signature = "setHostBasePrice(uint256)",
      uintArgs = listOf(priceRaw),
    )
    return submitEscrowWriteTx(
      context = context,
      userAddress = userAddress,
      callData = callData,
      intentType = "pirate.schedule.set_base_price",
      intentArgs = JSONObject().put("priceUsd", priceUsd),
      opLabel = "set base price",
    )
  }

  suspend fun createSlotWithPrice(
    context: Context,
    userAddress: String,
    startTimeSec: Long,
    durationMins: Int,
    graceMins: Int,
    minOverlapMins: Int,
    cancelCutoffMins: Int,
    priceUsd: String,
  ): EscrowTxResult {
    if (durationMins <= 0) return EscrowTxResult(success = false, error = "Duration must be positive.")
    if (startTimeSec <= nowSec()) return EscrowTxResult(success = false, error = "Slot start time must be in the future.")
    val priceRaw = parseUsdToRaw(priceUsd)
      ?: return EscrowTxResult(success = false, error = "Enter a valid positive price.")

    val callData = encodeFunctionCall(
      signature = "createSlotWithPrice(uint48,uint32,uint32,uint32,uint32,uint256)",
      uintArgs = listOf(
        BigInteger.valueOf(startTimeSec),
        BigInteger.valueOf(durationMins.toLong()),
        BigInteger.valueOf(graceMins.toLong()),
        BigInteger.valueOf(minOverlapMins.toLong()),
        BigInteger.valueOf(cancelCutoffMins.toLong()),
        priceRaw,
      ),
    )

    return submitEscrowWriteTx(
      context = context,
      userAddress = userAddress,
      callData = callData,
      intentType = "pirate.schedule.create_slot",
      intentArgs = JSONObject()
        .put("startTimeSec", startTimeSec)
        .put("durationMins", durationMins)
        .put("priceUsd", priceUsd),
      opLabel = "create slot with price",
    )
  }

  suspend fun cancelSlot(
    context: Context,
    userAddress: String,
    slotId: Long,
  ): EscrowTxResult {
    if (slotId <= 0L) return EscrowTxResult(success = false, error = "Invalid slot id.")

    val callData = encodeFunctionCall(
      signature = "cancelSlot(uint256)",
      uintArgs = listOf(BigInteger.valueOf(slotId)),
    )

    return submitEscrowWriteTx(
      context = context,
      userAddress = userAddress,
      callData = callData,
      intentType = "pirate.schedule.cancel_slot",
      intentArgs = JSONObject().put("slotId", slotId),
      opLabel = "cancel slot",
    )
  }

  suspend fun cancelBooking(
    context: Context,
    userAddress: String,
    bookingId: Long,
    asHost: Boolean,
  ): EscrowTxResult {
    if (bookingId <= 0L) return EscrowTxResult(success = false, error = "Invalid booking id.")

    val signature = if (asHost) "cancelBookingAsHost(uint256)" else "cancelBookingAsGuest(uint256)"
    val callData = encodeFunctionCall(
      signature = signature,
      uintArgs = listOf(BigInteger.valueOf(bookingId)),
    )

    return submitEscrowWriteTx(
      context = context,
      userAddress = userAddress,
      callData = callData,
      intentType = "pirate.schedule.cancel_booking",
      intentArgs = JSONObject().put("bookingId", bookingId).put("role", if (asHost) "host" else "guest"),
      opLabel = "cancel booking",
    )
  }

  private fun mapSlotStatus(status: EscrowSlotStatus): HostSlotStatus = when (status) {
    EscrowSlotStatus.Open -> HostSlotStatus.Open
    EscrowSlotStatus.Booked -> HostSlotStatus.Booked
    EscrowSlotStatus.Cancelled -> HostSlotStatus.Cancelled
    EscrowSlotStatus.Settled -> HostSlotStatus.Settled
  }
}
