package sc.pirate.app.learn

import android.media.MediaRecorder
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sc.pirate.app.theme.PiratePalette
import sc.pirate.app.theme.PirateTokens
import coil.compose.AsyncImage
import java.io.File
import androidx.compose.ui.Alignment

private const val SAY_IT_BACK_LOG_TAG = "LearnSayItBack"

internal data class LearnSongSummaryRow(
  val id: String,
  val studySetKey: String,
  val trackId: String?,
  val title: String,
  val artist: String,
  val coverUri: String? = null,
  val coverFallbackUri: String? = null,
  val totalAttempts: Int,
  val uniqueQuestionsTouched: Int,
  val streakDays: Int,
)

@Composable
internal fun LearnQueueSummaryCards(
  queue: StudyQueueSnapshot?,
  loading: Boolean,
) {
  val showPlaceholder = queue == null
  val learningCount = queue?.learningCount?.toString() ?: "..."
  val reviewCount = queue?.reviewCount?.toString() ?: "..."
  val dueCount = queue?.dueCount?.toString() ?: "..."

  Row(
    modifier =
      Modifier
        .fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    LearnStatCard(modifier = Modifier.weight(1f), value = if (showPlaceholder) "..." else learningCount, title = "Learn")
    LearnStatCard(modifier = Modifier.weight(1f), value = if (showPlaceholder) "..." else reviewCount, title = "Review")
    LearnStatCard(modifier = Modifier.weight(1f), value = if (showPlaceholder) "..." else dueCount, title = "Due")
  }
}

@Composable
private fun LearnStatCard(
  modifier: Modifier = Modifier,
  value: String,
  title: String,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 6.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
      horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
      Text(
        text = value,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        color = PiratePalette.TextMuted,
      )
    }
  }
}

@Composable
internal fun LearnGlobalStreakChip(days: Int) {
  Row(
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.Filled.LocalFireDepartment,
      contentDescription = null,
      tint = PirateTokens.colors.accentBrand,
      modifier = Modifier.size(24.dp),
    )
    Spacer(modifier = Modifier.width(6.dp))
    Text(
      text = days.toString(),
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
      fontWeight = FontWeight.Bold,
    )
  }
}

@Composable
internal fun StudySongSummaryRow(
  summary: LearnSongSummaryRow,
  selected: Boolean,
  onSelect: () -> Unit,
) {
  val container = if (selected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f) else Color.Transparent
  val content = MaterialTheme.colorScheme.onSurface
  val primaryCover = summary.coverUri?.trim().orEmpty().ifBlank { null }
  val fallbackCover = summary.coverFallbackUri?.trim().orEmpty().ifBlank { null }
  var displayCoverUri by remember(summary.id, summary.coverUri, summary.coverFallbackUri) {
    mutableStateOf(primaryCover ?: fallbackCover)
  }
  var coverLoadFailed by remember(summary.id, summary.coverUri, summary.coverFallbackUri) {
    mutableStateOf(false)
  }

  fun handleCoverError() {
    if (displayCoverUri == primaryCover && !fallbackCover.isNullOrBlank() && fallbackCover != displayCoverUri) {
      displayCoverUri = fallbackCover
      return
    }
    coverLoadFailed = true
  }

  Surface(
    modifier = Modifier.fillMaxWidth().clickable { onSelect() },
    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    color = container,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          modifier = Modifier.size(48.dp),
          shape = RoundedCornerShape(10.dp),
          color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
          Box(contentAlignment = Alignment.Center) {
            if (!displayCoverUri.isNullOrBlank() && !coverLoadFailed) {
              AsyncImage(
                model = displayCoverUri,
                contentDescription = "${summary.title} cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { handleCoverError() },
              )
            } else {
              Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = PiratePalette.TextMuted,
                modifier = Modifier.size(20.dp),
              )
            }
          }
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = summary.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = content,
          )
          Text(
            text = summary.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = content.copy(alpha = 0.9f),
          )
        }
      }

      Row(
        modifier = Modifier.padding(start = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.Filled.LocalFireDepartment,
          contentDescription = null,
          tint = content,
          modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
          text = summary.streakDays.toString(),
          style = MaterialTheme.typography.titleMedium,
          color = content,
          fontWeight = FontWeight.Bold,
        )
      }
    }
  }
}

internal fun buildSayItBackRecorder(
  context: android.content.Context,
  outputFile: File,
): MediaRecorder {
  Log.d(SAY_IT_BACK_LOG_TAG, "buildRecorder: create path=${outputFile.absolutePath}")
  val recorder = MediaRecorder(context)
  recorder.setOnErrorListener { _, what, extra ->
    Log.e(SAY_IT_BACK_LOG_TAG, "recorder:onError what=$what extra=$extra path=${outputFile.absolutePath}")
  }
  recorder.setOnInfoListener { _, what, extra ->
    Log.w(SAY_IT_BACK_LOG_TAG, "recorder:onInfo what=$what extra=$extra path=${outputFile.absolutePath}")
  }
  recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
  recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
  recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
  recorder.setAudioEncodingBitRate(96_000)
  recorder.setAudioSamplingRate(44_100)
  recorder.setOutputFile(outputFile.absolutePath)
  Log.d(SAY_IT_BACK_LOG_TAG, "buildRecorder: prepare path=${outputFile.absolutePath}")
  recorder.prepare()
  Log.d(SAY_IT_BACK_LOG_TAG, "buildRecorder: start path=${outputFile.absolutePath}")
  recorder.start()
  Log.d(SAY_IT_BACK_LOG_TAG, "buildRecorder: started path=${outputFile.absolutePath}")
  return recorder
}

internal fun derivePracticeQuestions(
  studyPack: LearnStudySetPack?,
  queue: StudyQueueSnapshot?,
): List<LearnStudyQuestion> {
  val questions = studyPack?.questions.orEmpty()
  if (questions.isEmpty()) return emptyList()

  val byHash = questions.associateBy { it.questionIdHash.lowercase() }
  val dueOrder = queue?.dueCards.orEmpty().mapNotNull { byHash[it.questionId.lowercase()] }.distinctBy { it.questionIdHash }
  if (dueOrder.isEmpty()) return questions

  val dueKeys = dueOrder.map { it.questionIdHash.lowercase() }.toHashSet()
  val remaining = questions.filterNot { dueKeys.contains(it.questionIdHash.lowercase()) }
  return dueOrder + remaining
}
