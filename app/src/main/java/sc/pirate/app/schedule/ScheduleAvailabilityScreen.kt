package sc.pirate.app.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import sc.pirate.app.theme.PiratePalette
import sc.pirate.app.ui.PirateMobileHeader
import sc.pirate.app.util.shortAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ScheduleAvailabilityScreen(
  isAuthenticated: Boolean,
  userAddress: String?,
  onClose: () -> Unit,
  onShowMessage: (String) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var basePrice by remember { mutableStateOf<String?>(null) }
  var basePriceEdit by remember { mutableStateOf("") }
  var editingPrice by rememberSaveable { mutableStateOf(false) }
  var weekOffset by rememberSaveable { mutableStateOf(0) }
  var selectedDayIndex by rememberSaveable { mutableStateOf(todayWeekdayIndex()) }
  var availabilitySlots by remember { mutableStateOf<List<SlotRow>>(emptyList()) }
  var availabilityLoading by remember { mutableStateOf(false) }
  var availabilityBusy by remember { mutableStateOf(false) }
  var availabilityFilter by rememberSaveable { mutableStateOf(AvailabilityFilter.All.name) }
  var showFilterDrawer by rememberSaveable { mutableStateOf(false) }
  var slotEditor by remember { mutableStateOf<SlotEditorState?>(null) }

  suspend fun refreshAvailability() {
    if (!isAuthenticated || userAddress.isNullOrBlank()) {
      availabilitySlots = emptyList()
      availabilityLoading = false
      basePrice = DEFAULT_BASE_PRICE
      if (!editingPrice) basePriceEdit = DEFAULT_BASE_PRICE
      return
    }

    availabilityLoading = true
    try {
      val slots = withContext(Dispatchers.IO) {
        SessionEscrowApi.fetchHostAvailabilitySlots(hostAddress = userAddress, maxResults = 300)
      }
      val chainBasePrice = withContext(Dispatchers.IO) {
        SessionEscrowApi.fetchHostBasePriceUsd(userAddress)
      }
      availabilitySlots = slots.mapIndexed { index, slot ->
        SlotRow(
          id = index + 1,
          slotId = slot.slotId,
          startTimeMillis = slot.startTimeSec * 1_000L,
          durationMinutes = slot.durationMins,
          status = hostSlotStatusToUi(slot.status),
          priceUsd = slot.priceUsd,
        )
      }
      val resolvedBase = chainBasePrice?.takeIf { it.isNotBlank() } ?: basePrice ?: DEFAULT_BASE_PRICE
      basePrice = resolvedBase
      if (!editingPrice) basePriceEdit = resolvedBase
    } catch (err: Throwable) {
      if (err is CancellationException) throw err
      onShowMessage("Failed to load availability: ${err.message ?: "unknown error"}")
      availabilitySlots = emptyList()
    } finally {
      availabilityLoading = false
    }
  }

  LaunchedEffect(isAuthenticated, userAddress) {
    refreshAvailability()
  }

  val halfHourSlots = remember {
    val list = mutableListOf<Pair<Int, Int>>()
    repeat(48) { index ->
      val hour = index / 2
      val minute = if (index % 2 == 0) 0 else 30
      list.add(Pair(hour, minute))
    }
    list.toList()
  }

  val weekDates = remember(weekOffset) { buildWeekDates(weekOffset) }
  val selectedDayIndexClamped = selectedDayIndex.coerceIn(0, 6)
  if (selectedDayIndexClamped != selectedDayIndex) selectedDayIndex = selectedDayIndexClamped
  val selectedDay = weekDates[selectedDayIndex]
  val minEditableStartMillis = System.currentTimeMillis() + (5 * 60 * 1_000L)

  val slotByTime = availabilitySlots
    .filter { isSameDay(it.startTimeMillis, selectedDay) }
    .associateBy { it.startTimeMillis }
  val allSlotsByStart = availabilitySlots.associateBy { it.startTimeMillis }

  val filterMode = AvailabilityFilter.valueOf(availabilityFilter)
  val visibleHalfHourSlots = halfHourSlots.filter { (hour, minute) ->
    val startMillis = slotStartMillis(selectedDay, hour, minute)
    if (startMillis <= minEditableStartMillis) return@filter false
    val status = slotByTime[startMillis]?.status
    when (filterMode) {
      AvailabilityFilter.All -> true
      AvailabilityFilter.Available -> status == SlotStatus.Open
      AvailabilityFilter.Booked -> status == SlotStatus.Booked
    }
  }

  Box(
    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
  ) {
    androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
      PirateMobileHeader(
        title = "",
        onClosePress = onClose,
      )

      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
      ) {
        item {
          DayStrip(
            weekDates = weekDates,
            selectedDayIndex = selectedDayIndex,
            onDaySelected = { selectedDayIndex = it },
            onWeekSwipe = { delta ->
              weekOffset += delta
            },
          )
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }

        item {
          AvailabilityHeaderCard(
            isAuthenticated = isAuthenticated,
            basePrice = basePrice,
            editingPrice = editingPrice,
            editValue = basePriceEdit,
            loading = availabilityLoading && basePrice == null && !editingPrice,
            busy = availabilityBusy,
            onEditStart = { editingPrice = true; basePriceEdit = basePrice ?: DEFAULT_BASE_PRICE },
            onPriceChange = { basePriceEdit = it },
            onCancelEdit = { editingPrice = false; basePriceEdit = basePrice ?: DEFAULT_BASE_PRICE },
            onSavePrice = {
              if (!isAuthenticated || userAddress.isNullOrBlank()) {
                onShowMessage("Sign in to set a base price.")
                return@AvailabilityHeaderCard
              }
              val normalizedPrice = normalizePriceInput(basePriceEdit) ?: run {
                onShowMessage("Enter a valid base price.")
                return@AvailabilityHeaderCard
              }

              availabilityBusy = true
              scope.launch {
                val result = SessionEscrowApi.setHostBasePrice(
                  context = context,
                  userAddress = userAddress,
                  priceUsd = normalizedPrice,
                )
                if (result.success) {
                  basePrice = normalizedPrice
                  basePriceEdit = normalizedPrice
                  editingPrice = false
                  onShowMessage("Base price updated: ${shortAddress(result.txHash ?: "", minLengthToShorten = 10)}")
                  refreshAvailability()
                } else {
                  onShowMessage("Base price update failed: ${result.error ?: "unknown error"}")
                }
                availabilityBusy = false
              }
            },
          )
        }

        item {
          AvailabilityFilterBar(
            filterLabel = filterMode.label,
            onOpenFilter = { showFilterDrawer = true },
          )
        }

        item { Spacer(modifier = Modifier.height(4.dp)) }

        if (visibleHalfHourSlots.isEmpty()) {
          item {
            Text(
              text = if (filterMode == AvailabilityFilter.All) "No upcoming slots for this day." else "No slots match this filter.",
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
              style = MaterialTheme.typography.bodyMedium,
              color = PiratePalette.TextMuted,
              textAlign = TextAlign.Center,
            )
          }
        }

        items(visibleHalfHourSlots) { (hour, minute) ->
          val startTime = slotStartMillis(selectedDay, hour, minute)
          val existingSlot = slotByTime[startTime]
          val status = existingSlot?.status
          val isLocked = status == SlotStatus.Booked || status == SlotStatus.Settled
          val disabled = availabilityLoading || availabilityBusy || isLocked || !isAuthenticated

          AvailabilitySwitchRow(
            timeLabel = formatTime(hour, minute),
            status = status,
            checked = status == SlotStatus.Open || status == SlotStatus.Booked,
            enabled = !disabled,
            onRowClick = {
              if (disabled) return@AvailabilitySwitchRow
              if (userAddress.isNullOrBlank()) {
                onShowMessage("Sign in to edit availability.")
                return@AvailabilitySwitchRow
              }

              val initialPrice = existingSlot?.priceUsd?.takeIf { it.isNotBlank() } ?: basePrice ?: DEFAULT_BASE_PRICE
              val currentlyOpen = status == SlotStatus.Open
              slotEditor = SlotEditorState(
                dayIndex = selectedDayIndex,
                hour = hour,
                minute = minute,
                baseStartMillis = startTime,
                targetAvailable = !currentlyOpen,
                priceUsd = initialPrice,
                scope = SlotApplyScope.ThisSlot,
                range = SlotApplyRange.FourWeeks,
              )
            },
          )
        }
      }
    }

    AvailabilityFilterDrawer(
      visible = showFilterDrawer,
      selectedFilter = filterMode,
      onSelectFilter = {
        availabilityFilter = it.name
        showFilterDrawer = false
      },
      onDismiss = { showFilterDrawer = false },
    )

    val editor = slotEditor
    val editorPreview =
      editor?.let {
        buildSlotEditorPreview(
          editor = it,
          allSlotsByStart = allSlotsByStart,
          weekDates = weekDates,
          minEditableStartMillis = minEditableStartMillis,
        )
      }
    val demandHint = editor?.let { buildChinaDemandHint(it.baseStartMillis, basePrice ?: DEFAULT_BASE_PRICE) }

    SlotEditorSheet(
      visible = editor != null,
      timeLabel = editor?.let { formatTime(it.hour, it.minute) } ?: "",
      targetAvailable = editor?.targetAvailable ?: false,
      onTargetAvailableChange = { next -> slotEditor = slotEditor?.copy(targetAvailable = next) },
      priceUsd = editor?.priceUsd ?: "",
      onPriceUsdChange = { value -> slotEditor = slotEditor?.copy(priceUsd = value) },
      demandHint = demandHint?.label ?: "",
      recommendedPriceUsd = demandHint?.recommendedPriceUsd,
      selectedScope = editor?.scope ?: SlotApplyScope.ThisSlot,
      onScopeChange = { scopeOption -> slotEditor = slotEditor?.copy(scope = scopeOption) },
      selectedRange = editor?.range ?: SlotApplyRange.FourWeeks,
      onRangeChange = { rangeOption -> slotEditor = slotEditor?.copy(range = rangeOption) },
      previewSummary =
        if (editor != null && editorPreview != null) {
          previewSummary(editor, editorPreview)
        } else {
          ""
        },
      busy = availabilityBusy,
      onApply = {
        val currentEditor = slotEditor ?: return@SlotEditorSheet
        if (!isAuthenticated || userAddress.isNullOrBlank()) {
          onShowMessage("Sign in to edit availability.")
          return@SlotEditorSheet
        }

        val normalizedPrice =
          if (currentEditor.targetAvailable) {
            normalizePriceInput(currentEditor.priceUsd) ?: run {
              onShowMessage("Enter a valid slot price.")
              return@SlotEditorSheet
            }
          } else {
            currentEditor.priceUsd
          }

        val editorForApply = currentEditor.copy(priceUsd = normalizedPrice)
        val preview =
          buildSlotEditorPreview(
            editor = editorForApply,
            allSlotsByStart = allSlotsByStart,
            weekDates = weekDates,
            minEditableStartMillis = minEditableStartMillis,
          )
        if (preview.affectedCount == 0) {
          onShowMessage("No slot changes to apply.")
          slotEditor = null
          return@SlotEditorSheet
        }

        availabilityBusy = true
        scope.launch {
          val result =
            if (editorForApply.targetAvailable) {
              executeCreatePlan(
                context = context,
                userAddress = userAddress,
                editor = editorForApply,
                preview = preview,
              )
            } else {
              executeCancelPlan(
                context = context,
                userAddress = userAddress,
                preview = preview,
              )
            }

          if (result.success) {
            onShowMessage(
              "Availability updated: ${shortAddress(result.txHash ?: "", minLengthToShorten = 10)}",
            )
            slotEditor = null
            refreshAvailability()
          } else {
            onShowMessage("Availability update failed: ${result.error ?: "unknown error"}")
          }

          availabilityBusy = false
        }
      },
      onDismiss = {
        if (!availabilityBusy) {
          slotEditor = null
        }
      },
    )
  }
}
