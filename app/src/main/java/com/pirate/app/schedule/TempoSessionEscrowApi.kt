package com.pirate.app.schedule

import com.pirate.app.tempo.SessionKeyManager
import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

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
  val usedSelfPayFallback: Boolean = false,
  val error: String? = null,
)

object TempoSessionEscrowApi {
  const val SUPPORTS_BATCH_SLOT_CREATE = false
  const val SUPPORTS_BATCH_SLOT_CANCEL = false

  private const val MAX_BOOKING_SCAN = 300
  private const val MAX_SLOT_SCAN = 400

  private const val GAS_LIMIT_SET_BASE_PRICE = 180_000L
  private const val GAS_LIMIT_CREATE_SLOT = 260_000L
  private const val GAS_LIMIT_CREATE_SLOTS_BATCH = 1_400_000L
  private const val GAS_LIMIT_CANCEL_SLOT = 180_000L
  private const val GAS_LIMIT_CANCEL_SLOTS_BATCH = 550_000L
  private const val GAS_LIMIT_CANCEL_BOOKING = 220_000L
  private const val SLOT_BATCH_MAX = 64

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
    userAddress: String,
    sessionKey: SessionKeyManager.SessionKey,
    priceUsd: String,
  ): EscrowTxResult {
    val priceRaw = parseUsdToRaw(priceUsd)
      ?: return EscrowTxResult(success = false, error = "Enter a valid positive base price.")

    val callData = encodeFunctionCall(
      signature = "setHostBasePrice(uint256)",
      uintArgs = listOf(priceRaw),
    )
    return submitEscrowWriteTx(
      userAddress = userAddress,
      sessionKey = sessionKey,
      callData = callData,
      minimumGasLimit = GAS_LIMIT_SET_BASE_PRICE,
      opLabel = "set base price",
    )
  }

  suspend fun createSlot(
    userAddress: String,
    sessionKey: SessionKeyManager.SessionKey,
    startTimeSec: Long,
    durationMins: Int,
    graceMins: Int,
    minOverlapMins: Int,
    cancelCutoffMins: Int,
  ): EscrowTxResult {
    if (durationMins <= 0) return EscrowTxResult(success = false, error = "Duration must be positive.")
    if (startTimeSec <= nowSec()) return EscrowTxResult(success = false, error = "Slot start time must be in the future.")

    val callData = encodeFunctionCall(
      signature = "createSlot(uint48,uint32,uint32,uint32,uint32)",
      uintArgs = listOf(
        BigInteger.valueOf(startTimeSec),
        BigInteger.valueOf(durationMins.toLong()),
        BigInteger.valueOf(graceMins.toLong()),
        BigInteger.valueOf(minOverlapMins.toLong()),
        BigInteger.valueOf(cancelCutoffMins.toLong()),
      ),
    )

    return submitEscrowWriteTx(
      userAddress = userAddress,
      sessionKey = sessionKey,
      callData = callData,
      minimumGasLimit = GAS_LIMIT_CREATE_SLOT,
      opLabel = "create slot",
    )
  }

  suspend fun createSlotWithPrice(
    userAddress: String,
    sessionKey: SessionKeyManager.SessionKey,
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
      userAddress = userAddress,
      sessionKey = sessionKey,
      callData = callData,
      minimumGasLimit = GAS_LIMIT_CREATE_SLOT,
      opLabel = "create slot with price",
    )
  }

  suspend fun createSlotsWithPrices(
    userAddress: String,
    sessionKey: SessionKeyManager.SessionKey,
    entries: List<SlotPlanEntry>,
    durationMins: Int,
    graceMins: Int,
    minOverlapMins: Int,
    cancelCutoffMins: Int,
  ): EscrowTxResult {
    if (!SUPPORTS_BATCH_SLOT_CREATE) {
      return EscrowTxResult(success = false, error = "Batch slot creation is unavailable on current escrow.")
    }
    if (entries.isEmpty()) return EscrowTxResult(success = false, error = "No slots selected.")
    if (entries.size > SLOT_BATCH_MAX) return EscrowTxResult(success = false, error = "Too many slots in one batch.")
    if (durationMins <= 0) return EscrowTxResult(success = false, error = "Duration must be positive.")

    val now = nowSec()
    val inputs =
      entries.map { entry ->
        if (entry.startTimeSec <= now) {
          return EscrowTxResult(success = false, error = "All slot times must be in the future.")
        }
        val rawPrice = parseUsdToRaw(entry.priceUsd)
          ?: return EscrowTxResult(success = false, error = "All prices must be valid positive values.")
        EscrowSlotInputCall(
          startTimeSec = entry.startTimeSec,
          durationMins = durationMins,
          graceMins = graceMins,
          minOverlapMins = minOverlapMins,
          cancelCutoffMins = cancelCutoffMins,
          priceRaw = rawPrice,
        )
      }

    val callData = encodeCreateSlotsWithPricesCall(inputs)
    return submitEscrowWriteTx(
      userAddress = userAddress,
      sessionKey = sessionKey,
      callData = callData,
      minimumGasLimit = GAS_LIMIT_CREATE_SLOTS_BATCH,
      opLabel = "create slots batch",
    )
  }

  suspend fun cancelSlot(
    userAddress: String,
    sessionKey: SessionKeyManager.SessionKey,
    slotId: Long,
  ): EscrowTxResult {
    if (slotId <= 0L) return EscrowTxResult(success = false, error = "Invalid slot id.")

    val callData = encodeFunctionCall(
      signature = "cancelSlot(uint256)",
      uintArgs = listOf(BigInteger.valueOf(slotId)),
    )

    return submitEscrowWriteTx(
      userAddress = userAddress,
      sessionKey = sessionKey,
      callData = callData,
      minimumGasLimit = GAS_LIMIT_CANCEL_SLOT,
      opLabel = "cancel slot",
    )
  }

  suspend fun cancelSlotsBestEffort(
    userAddress: String,
    sessionKey: SessionKeyManager.SessionKey,
    slotIds: List<Long>,
  ): EscrowTxResult {
    if (!SUPPORTS_BATCH_SLOT_CANCEL) {
      return EscrowTxResult(success = false, error = "Batch slot cancellation is unavailable on current escrow.")
    }
    if (slotIds.isEmpty()) return EscrowTxResult(success = false, error = "No slots selected.")
    if (slotIds.size > SLOT_BATCH_MAX) return EscrowTxResult(success = false, error = "Too many slots in one batch.")
    if (slotIds.any { it <= 0L }) return EscrowTxResult(success = false, error = "Invalid slot id.")

    val callData = encodeUintArrayFunctionCall(
      signature = "cancelSlotsBestEffort(uint256[])",
      values = slotIds.map { BigInteger.valueOf(it) },
    )

    return submitEscrowWriteTx(
      userAddress = userAddress,
      sessionKey = sessionKey,
      callData = callData,
      minimumGasLimit = GAS_LIMIT_CANCEL_SLOTS_BATCH,
      opLabel = "cancel slots batch",
    )
  }

  suspend fun cancelBooking(
    userAddress: String,
    sessionKey: SessionKeyManager.SessionKey,
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
      userAddress = userAddress,
      sessionKey = sessionKey,
      callData = callData,
      minimumGasLimit = GAS_LIMIT_CANCEL_BOOKING,
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
