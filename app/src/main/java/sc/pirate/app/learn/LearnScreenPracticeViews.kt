package sc.pirate.app.learn

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Microphone
import sc.pirate.app.ui.PirateIconButton
import sc.pirate.app.ui.PirateOutlinedButton
import sc.pirate.app.ui.PiratePrimaryButton
import sc.pirate.app.ui.PirateSheetTitle
import kotlinx.coroutines.launch

internal data class PracticeChoiceOption(
  val text: String,
  val isCorrect: Boolean,
)

private object ExerciseUiPalette {
  val BgSurface = Color(0xFF2C2D2F)
  val BgSurfaceSoft = Color(0xFF37393B)
  val BorderSubtle = Color(0xFF4B4D51)
  val TextMuted = Color(0xFF9DA5AD)
  val AccentBlue = Color(0xFF4F7CFF)
  val AccentGreen = Color(0xFF34C98A)
  val AccentRed = Color(0xFFFF7A88)
  val AccentBlueBg = Color(0xFF323B4A)
  val AccentGreenBg = Color(0xFF31463F)
  val AccentRedBg = Color(0xFF4B353A)
  val IncorrectCalloutBg = Color(0xFF2B2D30)
}

private const val SAY_IT_BACK_UI_LOG_TAG = "LearnSayItBack"

@Composable
internal fun PracticeQuestionPane(
  question: LearnStudyQuestion,
  index: Int,
  total: Int,
  selectedChoiceIndex: Int?,
  answerRevealed: Boolean,
  isRecording: Boolean,
  isScoring: Boolean,
  sayItBackTranscript: String?,
  sayItBackScore: Double?,
  sayItBackPassed: Boolean?,
  sayItBackGradeMessage: String?,
  mcqOptions: List<PracticeChoiceOption>,
  onSelectChoice: (Int) -> Unit,
  onPrimaryAction: (LearnStudyQuestion) -> Unit,
  exitEnabled: Boolean,
  onExit: () -> Unit,
) {
  val latestOnPrimaryAction by rememberUpdatedState(onPrimaryAction)
  val latestOnExit by rememberUpdatedState(onExit)
  val safeTotal = total.coerceAtLeast(1)
  val progress = index.coerceIn(0, safeTotal).toFloat() / safeTotal.toFloat()
  val showMicAction = !question.isMcq && !answerRevealed
  val navigationLabel = if (index >= safeTotal - 1) "Finish" else "Continue"
  val actionEnabled =
    when {
      question.isMcq -> answerRevealed
      answerRevealed -> true
      else -> !isScoring
    }
  val actionLabel = navigationLabel
  val instruction =
    when (question.type) {
      "say_it_back" -> "Say it back:"
      "translation_mcq" -> "Translate:"
      "trivia_mcq" -> "Trivia:"
      else -> "Exercise"
    }
  val selectedCorrect = selectedChoiceIndex?.let { mcqOptions.getOrNull(it)?.isCorrect } == true
  val showCorrectFeedback = question.isMcq && answerRevealed && selectedChoiceIndex != null && selectedCorrect

  Column(
    modifier = Modifier.fillMaxSize().padding(top = 48.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PirateIconButton(
        onClick = { latestOnExit() },
        enabled = exitEnabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
      ) {
        Icon(
          Icons.Filled.Close,
          contentDescription = "Close",
          modifier = Modifier.size(24.dp),
        )
      }
      LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.weight(1f).padding(start = 8.dp),
        color = ExerciseUiPalette.AccentBlue,
        trackColor = ExerciseUiPalette.BgSurfaceSoft,
      )
    }

    Column(
      modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text(
        text = instruction,
        style = MaterialTheme.typography.titleMedium,
        color = ExerciseUiPalette.TextMuted,
        fontWeight = FontWeight.Medium,
      )

      if (question.isMcq) {
        val promptText = question.prompt.ifBlank { question.excerpt }.ifBlank { "Choose the correct answer." }
        Text(
          text = promptText,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.SemiBold,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          mcqOptions.forEachIndexed { idx, option ->
            ChoiceRow(
              text = option.text,
              selected = selectedChoiceIndex == idx,
              reveal = answerRevealed,
              correct = option.isCorrect,
              incorrect = answerRevealed && selectedChoiceIndex == idx && !option.isCorrect,
              onClick = { onSelectChoice(idx) },
            )
          }
        }

        if (
          answerRevealed &&
            selectedChoiceIndex != null &&
            !selectedCorrect &&
            !question.explanation.isNullOrBlank()
        ) {
          Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            color = ExerciseUiPalette.IncorrectCalloutBg,
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(14.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  Icons.Filled.Cancel,
                  contentDescription = null,
                  tint = ExerciseUiPalette.AccentRed,
                  modifier = Modifier.size(18.dp),
                )
                Text(
                  text = "Incorrect",
                  style = MaterialTheme.typography.titleMedium,
                  color = ExerciseUiPalette.TextMuted,
                  fontWeight = FontWeight.SemiBold,
                )
              }
              Text(
                text = question.explanation,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
          }
        }
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = question.excerpt,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
          )

          if (!isScoring && sayItBackPassed != null && !sayItBackTranscript.isNullOrBlank()) {
            val correct = sayItBackPassed == true
            val gradeLabel =
              sayItBackGradeMessage
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: if (correct) "Great job" else null
            val transcriptLabel =
              if (gradeLabel != null) {
                "$gradeLabel, you said:"
              } else {
                "You said:"
              }
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              if (!correct) {
                Icon(
                  Icons.Filled.Cancel,
                  contentDescription = null,
                  tint = ExerciseUiPalette.AccentRed,
                  modifier = Modifier.size(18.dp),
                )
              }
              Text(
                text = transcriptLabel,
                style = MaterialTheme.typography.titleMedium,
                color = ExerciseUiPalette.TextMuted,
                fontWeight = FontWeight.SemiBold,
              )
            }

            Text(
              text = sayItBackTranscript.orEmpty(),
              style = MaterialTheme.typography.headlineSmall,
              color = MaterialTheme.colorScheme.onSurface,
              fontWeight = FontWeight.SemiBold,
            )
          }
        }
      }
    }

    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
      HorizontalDivider(color = ExerciseUiPalette.BorderSubtle)
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        AnimatedVisibility(
          visible = showCorrectFeedback,
          enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
          exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        ) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              Icons.Filled.CheckCircle,
              contentDescription = null,
              tint = ExerciseUiPalette.AccentGreen,
              modifier = Modifier.size(20.dp),
            )
            Text(
              text = "Correct!",
              style = MaterialTheme.typography.titleMedium,
              color = ExerciseUiPalette.TextMuted,
              fontWeight = FontWeight.SemiBold,
            )
          }
        }

        if (showMicAction) {
          val voiceActionLabel =
            when {
              isScoring -> "Scoring..."
              isRecording -> "Stop"
              else -> "Record"
            }
          PiratePrimaryButton(
            text = voiceActionLabel,
            onClick = {
              Log.d(
                SAY_IT_BACK_UI_LOG_TAG,
                "ui: primary button clicked label=$voiceActionLabel recording=$isRecording scoring=$isScoring questionId=${question.id}",
              )
              latestOnPrimaryAction(question)
            },
            enabled = !isScoring,
            containerColor = if (isRecording) ExerciseUiPalette.AccentRed else ExerciseUiPalette.AccentBlue,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            leadingIcon =
              if (isScoring) {
                null
              } else {
                {
                  Icon(
                    if (isRecording) Icons.Filled.Close else PhosphorIcons.Regular.Microphone,
                    contentDescription = if (isRecording) "Stop recording" else "Record answer",
                    modifier = Modifier.size(20.dp),
                  )
                }
              },
          )
        } else {
          PiratePrimaryButton(
            text = actionLabel,
            onClick = { latestOnPrimaryAction(question) },
            enabled = actionEnabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
          )
        }
      }
    }
  }
}

private enum class ChoiceState {
  Default,
  Selected,
  Correct,
  Incorrect,
  Disabled,
}

@Composable
private fun ChoiceRow(
  text: String,
  selected: Boolean,
  reveal: Boolean,
  correct: Boolean,
  incorrect: Boolean,
  onClick: () -> Unit,
) {
  val state =
    when {
      reveal && correct -> ChoiceState.Correct
      reveal && incorrect -> ChoiceState.Incorrect
      reveal -> ChoiceState.Disabled
      selected -> ChoiceState.Selected
      else -> ChoiceState.Default
    }

  val backgroundColor =
    when {
      state == ChoiceState.Selected -> ExerciseUiPalette.AccentBlueBg
      state == ChoiceState.Correct -> ExerciseUiPalette.AccentGreenBg
      state == ChoiceState.Incorrect -> ExerciseUiPalette.AccentRedBg
      else -> ExerciseUiPalette.BgSurface
    }
  val borderColor =
    when {
      state == ChoiceState.Selected -> ExerciseUiPalette.AccentBlue
      state == ChoiceState.Correct -> ExerciseUiPalette.AccentGreen
      state == ChoiceState.Incorrect -> ExerciseUiPalette.AccentRed
      else -> ExerciseUiPalette.BorderSubtle
    }
  val indicatorFillColor =
    when {
      state == ChoiceState.Selected -> ExerciseUiPalette.AccentBlue
      state == ChoiceState.Correct -> ExerciseUiPalette.AccentGreen
      state == ChoiceState.Incorrect -> ExerciseUiPalette.AccentRed
      else -> Color.Transparent
    }
  val indicatorBorderColor =
    when {
      state == ChoiceState.Selected || state == ChoiceState.Correct || state == ChoiceState.Incorrect -> Color.Transparent
      else -> ExerciseUiPalette.BorderSubtle
    }
  val textAlpha =
    when {
      state == ChoiceState.Disabled -> 0.5f
      else -> 1f
    }

  Surface(
    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    color = backgroundColor,
    border = BorderStroke(2.dp, borderColor),
    modifier = Modifier.fillMaxWidth().clickable(enabled = !reveal, onClick = onClick),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier
            .size(20.dp)
            .background(indicatorFillColor, CircleShape)
            .padding(3.5.dp),
      ) {
        Surface(
          modifier = Modifier.fillMaxSize(),
          shape = CircleShape,
          color = Color.Transparent,
          border = BorderStroke(2.dp, indicatorBorderColor),
        ) {}
        when (state) {
          ChoiceState.Selected,
          ChoiceState.Correct,
          ->
            Icon(
              imageVector = Icons.Filled.Check,
              contentDescription = null,
              tint = Color(0xFF041120),
              modifier = Modifier.fillMaxSize(),
            )
          ChoiceState.Incorrect ->
            Icon(
              imageVector = Icons.Filled.Close,
              contentDescription = null,
              tint = Color(0xFF3B0A10),
              modifier = Modifier.fillMaxSize(),
            )
          else -> Unit
        }
      }
      Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha),
      )
    }
  }
}

@Composable
internal fun PracticeCompletedPane(
  total: Int,
  correct: Int,
  streakDays: Int?,
  onPracticeAgain: () -> Unit,
  onClose: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
    verticalArrangement = Arrangement.SpaceBetween,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().weight(1f),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Surface(
        shape = CircleShape,
        color = ExerciseUiPalette.AccentGreen,
        modifier = Modifier.size(88.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.Check,
          contentDescription = null,
          tint = Color(0xFF041120),
          modifier = Modifier.fillMaxSize().padding(20.dp),
        )
      }
      Spacer(modifier = Modifier.size(24.dp))
      Text(
        "Complete!",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
      )
      Text(
        "$correct / $total correct",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (streakDays != null && streakDays > 0) {
        Surface(
          shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
          color = Color(0xFF3E2B12),
          modifier = Modifier.padding(top = 20.dp),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Filled.LocalFireDepartment,
              contentDescription = null,
              tint = Color(0xFFFF8A3D),
              modifier = Modifier.size(22.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(
                text = if (streakDays == 1) "1 day streak" else "$streakDays day streak",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
              )
              Text(
                text = "Streak updated",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }

    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        PirateOutlinedButton(
          onClick = onPracticeAgain,
          modifier = Modifier.weight(1f).heightIn(min = 56.dp),
        ) {
          Text(text = "Study Again", style = MaterialTheme.typography.labelLarge)
        }
        PiratePrimaryButton(
          text = "Done",
          onClick = onClose,
          modifier = Modifier.weight(1f).heightIn(min = 56.dp),
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LearnExitConfirmationSheet(
  willDiscardRecording: Boolean,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()

  fun hideSheet(afterHide: (() -> Unit)? = null) {
    scope.launch {
      runCatching { sheetState.hide() }
      onDismiss()
      afterHide?.invoke()
    }
  }

  ModalBottomSheet(
    onDismissRequest = { hideSheet() },
    sheetState = sheetState,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 20.dp, vertical = 12.dp)
          .navigationBarsPadding(),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      PirateSheetTitle(text = "Exit session?")
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          "Your progress will be saved in the background.",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (willDiscardRecording) {
          Text(
            "Your current recording will be discarded.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        PirateOutlinedButton(
          onClick = { hideSheet() },
          modifier = Modifier.weight(1f).heightIn(min = 56.dp),
        ) {
          Text("Keep studying", style = MaterialTheme.typography.labelLarge)
        }
        PiratePrimaryButton(
          text = "Exit session",
          onClick = { hideSheet(afterHide = onConfirm) },
          modifier = Modifier.weight(1f).heightIn(min = 56.dp),
        )
      }
    }
  }
}
