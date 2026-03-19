package sc.pirate.app.schedule

internal data class SlotEditorState(
  val dayIndex: Int,
  val hour: Int,
  val minute: Int,
  val baseStartMillis: Long,
  val targetAvailable: Boolean,
  val priceUsd: String,
  val scope: SlotApplyScope,
  val range: SlotApplyRange,
)

internal data class SlotEditorPreview(
  val createStartTimesMillis: List<Long>,
  val cancelSlotIds: List<Long>,
  val alreadyMatchingCount: Int,
  val lockedCount: Int,
) {
  val affectedCount: Int get() = createStartTimesMillis.size + cancelSlotIds.size
}

internal fun buildTargetStartTimes(
  editor: SlotEditorState,
  weekDates: List<Long>,
  minEditableStartMillis: Long,
): List<Long> {
  if (editor.scope == SlotApplyScope.ThisSlot) {
    return if (editor.baseStartMillis > minEditableStartMillis) listOf(editor.baseStartMillis) else emptyList()
  }

  val weekStart = weekDates.firstOrNull() ?: return emptyList()
  val dayIndexes =
    when (editor.scope) {
      SlotApplyScope.ThisSlot -> listOf(editor.dayIndex)
      SlotApplyScope.SameWeekday -> listOf(editor.dayIndex)
      SlotApplyScope.Weekdays -> listOf(1, 2, 3, 4, 5)
      SlotApplyScope.AllDays -> listOf(0, 1, 2, 3, 4, 5, 6)
    }

  val starts = mutableListOf<Long>()
  repeat(editor.range.weeks) { weekDelta ->
    dayIndexes.forEach { dayIndex ->
      val dayMillis = addDaysKeepingLocalMidnight(weekStart, (weekDelta * 7) + dayIndex)
      val startMillis = slotStartMillis(dayMillis, editor.hour, editor.minute)
      if (startMillis > minEditableStartMillis) {
        starts += startMillis
      }
    }
  }
  return starts.distinct().sorted()
}

internal fun buildSlotEditorPreview(
  editor: SlotEditorState,
  allSlotsByStart: Map<Long, SlotRow>,
  weekDates: List<Long>,
  minEditableStartMillis: Long,
): SlotEditorPreview {
  val starts = buildTargetStartTimes(editor, weekDates = weekDates, minEditableStartMillis = minEditableStartMillis)
  val createStarts = mutableListOf<Long>()
  val cancelSlotIds = mutableListOf<Long>()
  var already = 0
  var locked = 0

  starts.forEach { startMillis ->
    val existing = allSlotsByStart[startMillis]
    if (editor.targetAvailable) {
      when {
        existing == null -> createStarts += startMillis
        existing.status == SlotStatus.Open -> already += 1
        existing.status == SlotStatus.Booked -> locked += 1
        else -> createStarts += startMillis
      }
    } else {
      when {
        existing?.status == SlotStatus.Open && existing.slotId != null -> cancelSlotIds += existing.slotId
        existing?.status == SlotStatus.Booked -> locked += 1
        else -> already += 1
      }
    }
  }

  return SlotEditorPreview(
    createStartTimesMillis = createStarts,
    cancelSlotIds = cancelSlotIds,
    alreadyMatchingCount = already,
    lockedCount = locked,
  )
}

internal fun previewSummary(
  editor: SlotEditorState,
  preview: SlotEditorPreview,
): String {
  return if (editor.targetAvailable) {
    "Will create ${preview.createStartTimesMillis.size} slots · ${preview.alreadyMatchingCount} already open · ${preview.lockedCount} locked"
  } else {
    "Will cancel ${preview.cancelSlotIds.size} slots · ${preview.alreadyMatchingCount} already unavailable · ${preview.lockedCount} locked"
  }
}
