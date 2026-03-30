package sc.pirate.app.learn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import sc.pirate.app.theme.PiratePalette
import sc.pirate.app.ui.PirateMobileHeader
import sc.pirate.app.ui.PiratePrimaryButton

@Composable
internal fun LearnScreenBody(
  isAuthenticated: Boolean,
  userAddress: String?,
  authBusy: Boolean,
  onOpenDrawer: () -> Unit,
  onRegister: () -> Unit,
  onLogin: () -> Unit,
  songs: List<LearnSongSummaryRow>,
  globalStreakDays: Int,
  summariesLoading: Boolean,
  selectedStudySetKey: String?,
  onSelectStudySetKey: (String) -> Unit,
  detailLoading: Boolean,
  queue: StudyQueueSnapshot?,
  currentQuestion: LearnStudyQuestion?,
  sessionQuestions: List<LearnStudyQuestion>,
  sessionQueueSize: Int,
  sessionActive: Boolean,
  sessionCompleted: Boolean,
  sessionIndex: Int,
  sessionCorrectCount: Int,
  sessionAttemptCount: Int,
  completionStreakDays: Int?,
  answerRevealed: Boolean,
  selectedChoiceIndex: Int?,
  sayItBackRecording: Boolean,
  sayItBackScoring: Boolean,
  sayItBackTranscript: String?,
  sayItBackScore: Double?,
  sayItBackPassed: Boolean?,
  sayItBackGradeMessage: String?,
  pendingSyncCount: Int,
  savingProgress: Boolean,
  saveError: String?,
  exitEnabled: Boolean,
  directBootTransitioning: Boolean,
  studyAllLoading: Boolean,
  miniPlayerVisible: Boolean,
  mcqOptions: List<PracticeChoiceOption>,
  onSelectChoice: (Int) -> Unit,
  onPrimaryAction: (LearnStudyQuestion) -> Unit,
  onExitSession: () -> Unit,
  onPracticeAgain: () -> Unit,
  onStartStudySet: (String) -> Unit,
  onStartStudyAll: () -> Unit,
) {
  val studyAllBottomPadding = if (miniPlayerVisible) 8.dp else 12.dp

  Column(modifier = Modifier.fillMaxSize()) {
    if (!sessionActive && !directBootTransitioning) {
      PirateMobileHeader(
        title = "Learn",
        isAuthenticated = isAuthenticated,
        onAvatarPress = onOpenDrawer,
        rightSlot = { LearnGlobalStreakChip(days = globalStreakDays) },
      )
    }

    if (!isAuthenticated || userAddress.isNullOrBlank()) {
      Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          text = "Sign up to start learning lyrics",
          style = MaterialTheme.typography.headlineSmall,
          color = MaterialTheme.colorScheme.onSurface,
          textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = "Practice songs and lessons across the app.",
          style = MaterialTheme.typography.bodyLarge,
          color = PiratePalette.TextMuted,
          textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        PiratePrimaryButton(
          text = "Continue",
          onClick = onRegister,
          enabled = !authBusy,
          modifier = Modifier.fillMaxWidth(0.7f),
        )
        if (authBusy) {
          Spacer(modifier = Modifier.height(16.dp))
          CircularProgressIndicator()
        }
      }
      return@Column
    }

    if (directBootTransitioning) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
      return@Column
    }

    if (sessionActive) {
      if (sessionCompleted) {
        PracticeCompletedPane(
          total = sessionAttemptCount,
          correct = sessionCorrectCount,
          streakDays = completionStreakDays,
          onPracticeAgain = onPracticeAgain,
          onClose = onExitSession,
        )
      } else if (currentQuestion != null) {
        PracticeQuestionPane(
          question = currentQuestion,
          index = sessionIndex,
          total = sessionQueueSize,
          selectedChoiceIndex = selectedChoiceIndex,
          answerRevealed = answerRevealed,
          isRecording = sayItBackRecording,
          isScoring = sayItBackScoring,
          sayItBackTranscript = sayItBackTranscript,
          sayItBackScore = sayItBackScore,
          sayItBackPassed = sayItBackPassed,
          sayItBackGradeMessage = sayItBackGradeMessage,
          mcqOptions = mcqOptions,
          onSelectChoice = onSelectChoice,
          onPrimaryAction = onPrimaryAction,
          exitEnabled = exitEnabled,
          onExit = onExitSession,
        )
      } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      }
      return@Column
    }

    LazyColumn(
      modifier = Modifier.fillMaxWidth().weight(1f),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        LearnQueueSummaryCards(
          queue = queue,
          loading = detailLoading,
        )
      }

      if (summariesLoading) {
        item {
          Text(
            "Loading study sets...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            textAlign = TextAlign.Center,
          )
        }
      } else if (songs.isEmpty()) {
        item {
          Text(
            "No study sets yet.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            textAlign = TextAlign.Center,
          )
        }
      } else {
        items(songs, key = { it.id }) { summary ->
          val selected = summary.studySetKey.equals(selectedStudySetKey, ignoreCase = true)
          StudySongSummaryRow(
            summary = summary,
            selected = selected,
            onSelect = {
              onSelectStudySetKey(summary.studySetKey)
              onStartStudySet(summary.studySetKey)
            },
          )
        }
      }
    }

    if (songs.isNotEmpty()) {
      PiratePrimaryButton(
        text = "Study all",
        onClick = onStartStudyAll,
        enabled = !studyAllLoading,
        loading = studyAllLoading,
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = studyAllBottomPadding)
            .heightIn(min = 56.dp),
      )
    }
  }
}
