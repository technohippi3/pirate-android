package com.pirate.app.learn

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.pirate.app.music.fetchTrackMeta
import com.pirate.app.music.resolveReleaseCoverUrl
import com.pirate.app.R
import com.pirate.app.tempo.SessionKeyManager
import com.pirate.app.tempo.TempoPasskeyManager
import com.pirate.app.tempo.TempoSessionKeyApi
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val BYTES32_HEX_REGEX = Regex("^0x[a-fA-F0-9]{64}$")
private const val MCQ_REQUEUE_DELAY = 2
private const val MCQ_MAX_REQUEUES_PER_QUESTION = 2
private const val SAY_IT_BACK_MAX_ATTEMPTS = 2
private const val SAY_IT_BACK_LOG_TAG = "LearnSayItBack"

private data class SongStreakSnapshot(
  val studySetKey: String,
  val streakDays: Int,
  val qualifiedDays: Set<LocalDate>,
  val queue: StudyQueueSnapshot,
  val pack: LearnStudySetPack?,
)

private data class SessionQuestionSource(
  val studySetKey: String,
  val trackId: String,
  val version: Int,
  val language: String,
)

private data class SessionQueueEntry(
  val questionIndex: Int,
  val exposure: Int,
  val requeueCount: Int,
  val source: SessionQuestionSource,
)

private data class LearnExerciseLaunchTarget(
  val studySetRef: String?,
  val trackId: String?,
  val language: String?,
  val version: Int,
  val title: String?,
  val artist: String?,
)

private data class LearnExerciseResolutionContext(
  val studySetRef: String?,
  val trackId: String?,
  val language: String,
  val version: Int,
  val song: LearnSongSummaryRow?,
  val catalog: RecentStudySetCatalogEntry?,
  val anchor: StudySetAnchorRow?,
)

private fun seededRng(seedInput: String): () -> Double {
  var seed = 2166136261u
  for (char in seedInput) {
    seed = seed xor char.code.toUInt()
    seed *= 16777619u
  }
  var state = if (seed != 0u) seed else 0x9e3779b9u
  return {
    state += 0x6d2b79f5u
    var t = state
    t = (t xor (t shr 15)) * (t or 1u)
    t = t xor (t + ((t xor (t shr 7)) * (t or 61u)))
    ((t xor (t shr 14)).toLong() and 0xffffffffL).toDouble() / 4294967296.0
  }
}

private fun shuffledIndexes(
  size: Int,
  rng: () -> Double,
): List<Int> {
  val order = MutableList(size) { it }
  for (i in (size - 1) downTo 1) {
    val j = (rng() * (i + 1)).toInt().coerceIn(0, i)
    val temp = order[i]
    order[i] = order[j]
    order[j] = temp
  }
  if (size > 1 && order.withIndex().all { (idx, value) -> idx == value }) {
    val first = order.removeAt(0)
    order.add(first)
  }
  return order
}

private fun createSessionNonce(): String = "${System.currentTimeMillis()}:${(Math.random() * 1_000_000_000).toLong()}"

@Composable
fun LearnScreen(
  isAuthenticated: Boolean,
  userAddress: String?,
  initialStudySetRef: String? = null,
  initialTrackId: String? = null,
  initialLanguage: String? = null,
  initialVersion: Int = 1,
  initialTitle: String? = null,
  initialArtist: String? = null,
  miniPlayerVisible: Boolean = false,
  hostActivity: FragmentActivity? = null,
  tempoAccount: TempoPasskeyManager.PasskeyAccount? = null,
  onLearnSessionVisibilityChange: (Boolean) -> Unit = {},
  onExitToLearn: () -> Unit = {},
  onOpenStudySet: (String?, String?, String?, Int, String?, String?) -> Unit = { _, _, _, _, _, _ -> },
  onOpenDrawer: () -> Unit,
  onShowMessage: (String) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var songs by remember { mutableStateOf<List<LearnSongSummaryRow>>(emptyList()) }
  var globalStreakDays by remember { mutableIntStateOf(0) }
  var globalQualifiedDays by remember { mutableStateOf<Set<LocalDate>>(emptySet()) }
  var summariesLoading by remember { mutableStateOf(false) }
  var selectedStudySetKey by rememberSaveable { mutableStateOf<String?>(null) }
  var availableStudySetKeys by remember { mutableStateOf<List<String>>(emptyList()) }
  var anchorsByStudySetKey by remember { mutableStateOf<Map<String, StudySetAnchorRow>>(emptyMap()) }
  var prefetchedQueues by remember { mutableStateOf<Map<String, StudyQueueSnapshot>>(emptyMap()) }
  var prefetchedPacks by remember { mutableStateOf<Map<String, LearnStudySetPack>>(emptyMap()) }

  var detailLoading by remember(initialStudySetRef, initialTrackId) {
    mutableStateOf(!initialStudySetRef.isNullOrBlank() || !initialTrackId.isNullOrBlank())
  }
  var queue by remember { mutableStateOf<StudyQueueSnapshot?>(null) }
  var studyPack by remember { mutableStateOf<LearnStudySetPack?>(null) }
  var dashboardActionLoading by remember { mutableStateOf(false) }

  var sessionActive by rememberSaveable { mutableStateOf(false) }
  var sessionIndex by rememberSaveable { mutableIntStateOf(0) }
  var sessionQuestions by remember { mutableStateOf<List<LearnStudyQuestion>>(emptyList()) }
  var sessionQueue by remember { mutableStateOf<List<SessionQueueEntry>>(emptyList()) }
  var sessionSeedQuestions by remember { mutableStateOf<List<LearnStudyQuestion>>(emptyList()) }
  var sessionSeedQueue by remember { mutableStateOf<List<SessionQueueEntry>>(emptyList()) }
  var sessionNonce by rememberSaveable { mutableStateOf(createSessionNonce()) }
  var selectedChoiceIndex by rememberSaveable { mutableStateOf<Int?>(null) }
  var selectedChoiceCorrect by rememberSaveable { mutableStateOf<Boolean?>(null) }
  var answerRevealed by rememberSaveable { mutableStateOf(false) }
  var sessionCorrectCount by rememberSaveable { mutableIntStateOf(0) }
  var sessionAttemptCount by rememberSaveable { mutableIntStateOf(0) }
  var sayItBackTranscript by rememberSaveable { mutableStateOf<String?>(null) }
  var sayItBackScore by rememberSaveable { mutableStateOf<Double?>(null) }
  var sayItBackPassed by rememberSaveable { mutableStateOf<Boolean?>(null) }
  var sayItBackGradeMessage by rememberSaveable { mutableStateOf<String?>(null) }
  var sayItBackAttemptCount by rememberSaveable { mutableIntStateOf(0) }
  var sayItBackRecording by remember { mutableStateOf(false) }
  var sayItBackScoring by remember { mutableStateOf(false) }
  var sayItBackRecordingPath by remember { mutableStateOf<String?>(null) }
  var sayItBackRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
  var sayItBackAttestation by remember { mutableStateOf<SayItBackStreakAttestation?>(null) }

  var pendingAttempts by remember { mutableStateOf<List<TempoStudyAttemptInput>>(emptyList()) }
  var pendingStreakClaims by remember { mutableStateOf<List<TempoStreakClaimInput>>(emptyList()) }
  var sessionStartPendingAttemptCount by remember { mutableIntStateOf(0) }
  var sessionStartPendingClaimCount by remember { mutableIntStateOf(0) }
  var sessionStartDisplayedStreakDays by remember { mutableIntStateOf(0) }
  var attemptsSaving by remember { mutableStateOf(false) }
  var attemptsSaveError by remember { mutableStateOf<String?>(null) }
  var showExitConfirmation by rememberSaveable { mutableStateOf(false) }
  var exitClosing by remember { mutableStateOf(false) }

  val micPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (!granted) onShowMessage("Microphone permission is required for Say it back.")
    }

  val learnerLanguage = remember { java.util.Locale.getDefault().language.ifBlank { "en" } }

  LaunchedEffect(sessionActive, onLearnSessionVisibilityChange) {
    onLearnSessionVisibilityChange(sessionActive)
  }

  DisposableEffect(onLearnSessionVisibilityChange) {
    onDispose {
      onLearnSessionVisibilityChange(false)
    }
  }
  val hasDirectBoot = !initialStudySetRef.isNullOrBlank() || !initialTrackId.isNullOrBlank()

  fun normalizeBytes32(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (!BYTES32_HEX_REGEX.matches(trimmed)) return null
    return trimmed.lowercase()
  }

  fun resolveDirectBootStudySetKey(): String? {
    val pack = studyPack ?: return null
    return StudySetKeyDerivation.derive(
      trackId = pack.trackId,
      language = pack.language,
      version = initialVersion.coerceIn(1, 255),
    )
  }

  fun resolveActiveStudySetKey(): String? {
    val selected = normalizeBytes32(selectedStudySetKey)
    if (!selected.isNullOrBlank()) return selected
    if (!hasDirectBoot) return null
    return resolveDirectBootStudySetKey()
  }

  fun resolveActiveStudySetVersion(): Int {
    val selected = normalizeBytes32(selectedStudySetKey)
    if (!selected.isNullOrBlank()) {
      val anchorVersion = anchorsByStudySetKey[selected.lowercase()]?.version
      if (anchorVersion != null && anchorVersion in 1..255) return anchorVersion
    }
    return initialVersion.coerceIn(1, 255)
  }

  fun recordCatalogEntry(
    studySetKey: String?,
    trackId: String?,
    title: String?,
    artist: String?,
    language: String?,
    version: Int,
    studySetRef: String? = null,
    totalAttempts: Int = 0,
    uniqueQuestionsTouched: Int = 0,
    streakDays: Int = 0,
    pack: LearnStudySetPack? = null,
  ) {
    val address = userAddress?.trim().orEmpty()
    val normalizedStudySetKey = normalizeBytes32(studySetKey) ?: return
    val normalizedTrackId = normalizeBytes32(trackId) ?: normalizeBytes32(pack?.trackId) ?: return
    val resolvedTitle = title?.trim().orEmpty().ifBlank { return }
    val resolvedArtist = artist?.trim().orEmpty().ifBlank { return }
    if (address.isBlank()) return
    RecentStudySetCatalogStore.record(
      context = context,
      userAddress = address,
      studySetKey = normalizedStudySetKey,
      entry =
        RecentStudySetCatalogEntry(
          trackId = normalizedTrackId,
          title = resolvedTitle,
          artist = resolvedArtist,
          language = language?.trim()?.ifBlank { null } ?: pack?.language,
          version = version.coerceIn(1, 255),
          studySetRef = studySetRef?.trim()?.ifBlank { null },
          totalAttempts = totalAttempts,
          uniqueQuestionsTouched = uniqueQuestionsTouched,
          streakDays = streakDays,
          pack = pack,
        ),
    )
  }

  fun resolvePendingOwnerAddress(): String {
    val accountAddress = tempoAccount?.address?.trim().orEmpty()
    if (accountAddress.isNotBlank()) return accountAddress
    return userAddress?.trim().orEmpty()
  }

  fun persistPendingSyncState(
    attempts: List<TempoStudyAttemptInput> = pendingAttempts,
    streakClaims: List<TempoStreakClaimInput> = pendingStreakClaims,
  ) {
    val ownerAddress = resolvePendingOwnerAddress()
    if (ownerAddress.isBlank()) return
    LearnPendingSyncStore.save(
      context = context,
      ownerAddress = ownerAddress,
      attempts = attempts,
      streakClaims = streakClaims,
    )
  }

  fun loadAuthorizedSessionKey(ownerAddress: String): SessionKeyManager.SessionKey? {
    val loaded = SessionKeyManager.load(context) ?: return null
    if (!SessionKeyManager.isValid(loaded, ownerAddress = ownerAddress)) return null
    if (loaded.keyAuthorization == null || loaded.keyAuthorization!!.isEmpty()) return null
    return loaded
  }

  suspend fun ensureLearnBackgroundSavingReady(): SessionKeyManager.SessionKey? {
    val account = tempoAccount ?: return null
    val existing = loadAuthorizedSessionKey(account.address)
    if (existing != null) return existing

    val activity = hostActivity ?: return null
    onShowMessage("Enabling background saving...")
    val auth =
      TempoSessionKeyApi.authorizeSessionKey(
        activity = activity,
        account = account,
        rpId = account.rpId,
      )
    val authorized =
      auth.sessionKey?.takeIf {
        auth.success &&
          SessionKeyManager.isValid(it, ownerAddress = account.address) &&
          it.keyAuthorization != null &&
          it.keyAuthorization!!.isNotEmpty()
      }
    if (authorized != null) {
      onShowMessage("Background saving enabled.")
      return authorized
    }

    onShowMessage("Background saving unavailable. Answers will stay on this device until sync is enabled.")
    return null
  }

  fun resetQuestionState() {
    selectedChoiceIndex = null
    selectedChoiceCorrect = null
    answerRevealed = false
    sayItBackTranscript = null
    sayItBackScore = null
    sayItBackPassed = null
    sayItBackGradeMessage = null
    sayItBackAttemptCount = 0
    sayItBackAttestation = null
    sayItBackScoring = false
  }

  fun optimisticQualifiedDays(): Set<LocalDate> {
    if (pendingStreakClaims.isEmpty()) return globalQualifiedDays
    val combined = LinkedHashSet<LocalDate>(globalQualifiedDays)
    pendingStreakClaims.forEach { claim ->
      if (claim.dayUtc >= 0L) {
        combined.add(LocalDate.ofEpochDay(claim.dayUtc))
      }
    }
    return combined
  }

  fun resolveDisplayedGlobalStreakDays(): Int {
    val today = LocalDate.ofEpochDay((System.currentTimeMillis() / 1_000L) / 86_400L)
    return consecutiveDayStreak(optimisticQualifiedDays(), today)
  }

  fun buildInitialSessionQueue(
    questionCount: Int,
    source: SessionQuestionSource,
  ): List<SessionQueueEntry> =
    List(questionCount) { index ->
      SessionQueueEntry(
        questionIndex = index,
        exposure = 0,
        requeueCount = 0,
        source = source,
      )
    }

  fun startSession(
    questions: List<LearnStudyQuestion>,
    initialQueue: List<SessionQueueEntry>,
  ) {
    if (questions.isEmpty() || initialQueue.isEmpty()) {
      sessionActive = false
      sessionQuestions = emptyList()
      sessionQueue = emptyList()
      sessionSeedQuestions = emptyList()
      sessionSeedQueue = emptyList()
      return
    }
    sessionQuestions = questions
    sessionQueue = initialQueue
    sessionSeedQuestions = questions
    sessionSeedQueue = initialQueue
    sessionNonce = createSessionNonce()
    sessionIndex = 0
    sessionCorrectCount = 0
    sessionAttemptCount = 0
    sessionStartDisplayedStreakDays = resolveDisplayedGlobalStreakDays()
    sessionStartPendingAttemptCount = pendingAttempts.size
    sessionStartPendingClaimCount = pendingStreakClaims.size
    attemptsSaveError = null
    showExitConfirmation = false
    exitClosing = false
    resetQuestionState()
    sessionActive = true
  }

  fun buildLocalPendingAttemptRows(studySetKey: String): List<StudyAttemptRow> {
    val normalizedStudySetKey = normalizeBytes32(studySetKey) ?: return emptyList()
    return pendingAttempts
      .asSequence()
      .filter { it.studySetKey.equals(normalizedStudySetKey, ignoreCase = true) }
      .mapIndexed { index, attempt ->
        StudyAttemptRow(
          questionId = attempt.questionId.trim().lowercase(),
          rating = attempt.rating,
          score = attempt.score,
          canonicalOrder = (attempt.timestampSec.coerceAtLeast(0L) * 1_000_000L) + index + 1L,
          blockTimestampSec = attempt.timestampSec.coerceAtLeast(0L),
          clientTimestampSec = attempt.timestampSec.coerceAtLeast(0L),
        )
      }.sortedWith(
        compareBy<StudyAttemptRow>({ it.canonicalOrder }, { it.blockTimestampSec }, { it.questionId }),
      ).toList()
  }

  fun mergeDisplayedAttempts(
    userAddress: String,
    studySetKey: String,
    onChainAttempts: List<StudyAttemptRow>,
  ): List<StudyAttemptRow> {
    val mergedWithSubmitted =
      RecentStudyAttemptsStore.mergeWithOnChain(
        userAddress = userAddress,
        studySetKey = studySetKey,
        onChainAttempts = onChainAttempts,
      )
    val localPending = buildLocalPendingAttemptRows(studySetKey)
    if (localPending.isEmpty()) return mergedWithSubmitted

    val combined = ArrayList<StudyAttemptRow>(mergedWithSubmitted.size + localPending.size)
    combined.addAll(mergedWithSubmitted)
    combined.addAll(localPending)
    return combined.sortedWith(
      compareBy<StudyAttemptRow>({ it.canonicalOrder }, { it.blockTimestampSec }, { it.questionId }),
    )
  }

  fun advanceQuestion(): Boolean {
    if (sessionIndex + 1 >= sessionQueue.size) {
      sessionIndex = sessionQueue.size
      return true
    }
    sessionIndex += 1
    resetQuestionState()
    return false
  }

  fun queueAttempt(
    question: LearnStudyQuestion,
    entry: SessionQueueEntry?,
    correct: Boolean,
  ): Boolean {
    val studySetKey = normalizeBytes32(entry?.source?.studySetKey) ?: resolveActiveStudySetKey()
    val questionId = normalizeBytes32(question.questionIdHash)
    if (studySetKey.isNullOrBlank() || questionId.isNullOrBlank()) {
      onShowMessage("Progress could not be saved for this question")
      return false
    }

    val nextAttempts =
      pendingAttempts +
        TempoStudyAttemptInput(
          studySetKey = studySetKey,
          questionId = questionId,
          rating = if (correct) 3 else 1,
          score = if (correct) 10_000 else 0,
          timestampSec = System.currentTimeMillis() / 1000L,
        )
    var nextClaims = pendingStreakClaims
    if (question.type == "say_it_back" && correct) {
      val attestation = sayItBackAttestation
      if (attestation != null) {
        if (attestation.studySetKey.equals(studySetKey, ignoreCase = true)) {
          nextClaims =
            nextClaims +
              TempoStreakClaimInput(
                studySetKey = attestation.studySetKey,
                dayUtc = attestation.dayUtc,
                nonce = attestation.nonce,
                expirySec = attestation.expiry,
                signatureHex = attestation.signature,
              )
        } else {
          onShowMessage("Streak attestation mismatch. Day claim skipped.")
        }
      } else if (TempoStreakClaimApi.isConfigured()) {
        onShowMessage("Streak attestation unavailable. Day claim skipped.")
      }
    }
    pendingAttempts = nextAttempts
    pendingStreakClaims = nextClaims
    persistPendingSyncState(nextAttempts, nextClaims)
    attemptsSaveError = null
    return true
  }

  fun requeueCurrentQuestion() {
    val entry = sessionQueue.getOrNull(sessionIndex) ?: return
    if (entry.requeueCount >= MCQ_MAX_REQUEUES_PER_QUESTION) return
    val insertAt = minOf(sessionQueue.size, sessionIndex + 1 + MCQ_REQUEUE_DELAY)
    val next = sessionQueue.toMutableList()
    next.add(
      insertAt,
      SessionQueueEntry(
        questionIndex = entry.questionIndex,
        exposure = entry.exposure + 1,
        requeueCount = entry.requeueCount + 1,
        source = entry.source,
      ),
    )
    sessionQueue = next
  }

  suspend fun flushPendingAttempts(showFailureMessage: Boolean): Boolean {
    val nowSec = System.currentTimeMillis() / 1_000L
    val todayUtc = nowSec / 86_400L
    val dedupedClaims = LinkedHashMap<String, TempoStreakClaimInput>()
    var droppedExpiredClaims = 0
    var droppedDayMismatchClaims = 0
    for (claim in pendingStreakClaims) {
      if (claim.expirySec <= nowSec) {
        droppedExpiredClaims += 1
        continue
      }
      if (claim.dayUtc != todayUtc) {
        droppedDayMismatchClaims += 1
        continue
      }
      dedupedClaims.putIfAbsent("${claim.studySetKey.lowercase()}:${claim.dayUtc}", claim)
    }
    var remainingAttempts = pendingAttempts
    var remainingClaims = dedupedClaims.values.toList()
    if (droppedDayMismatchClaims > 0) {
      Log.i(
        "LearnScreen",
        "Dropped stale streak claims dayMismatch=$droppedDayMismatchClaims expired=$droppedExpiredClaims todayUtc=$todayUtc",
      )
      if (showFailureMessage) {
        onShowMessage("Some streak proofs crossed UTC midnight. Say it back again for today's claim.")
      }
    }

    if (!TempoStreakClaimApi.isConfigured() && remainingClaims.isNotEmpty()) {
      remainingClaims = emptyList()
    }

    if (remainingClaims != pendingStreakClaims) {
      pendingStreakClaims = remainingClaims
      persistPendingSyncState(remainingAttempts, remainingClaims)
    }

    val hasAttemptWork = remainingAttempts.isNotEmpty()
    val hasClaimWork = remainingClaims.isNotEmpty()
    if (!hasAttemptWork && !hasClaimWork) {
      attemptsSaveError = null
      return true
    }
    if (attemptsSaving) return false

    val activity = hostActivity
    val account = tempoAccount
    if (activity == null || account == null) {
      attemptsSaveError = null
      return true
    }

    val sender = account.address.trim()
    val normalizedUserAddress = userAddress?.trim().orEmpty()
    if (normalizedUserAddress.isNotBlank() && !sender.equals(normalizedUserAddress, ignoreCase = true)) {
      attemptsSaveError = null
      return true
    }

    val usableSessionKey = loadAuthorizedSessionKey(sender)
    if (usableSessionKey == null) {
      attemptsSaveError = null
      return true
    }

    attemptsSaving = true
    attemptsSaveError = null
    try {
      if (remainingAttempts.isNotEmpty()) {
        val result =
          TempoStudyAttemptsApi.submitAttempts(
            activity = activity,
            account = account,
            attempts = remainingAttempts,
            sessionKey = usableSessionKey,
          )

        val submittedCount = result.submittedCount.coerceAtMost(remainingAttempts.size)
        val submittedAttempts =
          if (submittedCount > 0) {
            remainingAttempts.take(submittedCount)
          } else {
            emptyList()
          }

        if (result.submittedCount > 0) {
          remainingAttempts = remainingAttempts.drop(result.submittedCount.coerceAtMost(remainingAttempts.size))
          pendingAttempts = remainingAttempts
          persistPendingSyncState(remainingAttempts, remainingClaims)
        }
        if (!result.success) {
          val message = result.error ?: "study progress sync failed"
          attemptsSaveError = "Sync paused: $message"
          if (showFailureMessage) onShowMessage("Background sync paused: $message")
          return false
        }

        if (submittedAttempts.isNotEmpty()) {
          RecentStudyAttemptsStore.recordSubmittedAttempts(
            userAddress = sender,
            attempts = submittedAttempts,
          )
        }
      }

      if (remainingClaims.isNotEmpty()) {
        val claimResult =
          TempoStreakClaimApi.submitClaims(
            activity = activity,
            account = account,
            claims = remainingClaims,
            sessionKey = usableSessionKey,
          )
        if (claimResult.submittedCount > 0) {
          remainingClaims = remainingClaims.drop(claimResult.submittedCount.coerceAtMost(remainingClaims.size))
          pendingStreakClaims = remainingClaims
          persistPendingSyncState(remainingAttempts, remainingClaims)
        }
        if (!claimResult.success) {
          val message = claimResult.error ?: "streak sync failed"
          attemptsSaveError = "Sync paused: $message"
          if (showFailureMessage) onShowMessage("Background sync paused: $message")
          Log.w("LearnScreen", "Streak claim failed: $message")
          return false
        }
      }

      attemptsSaveError = null
      return true
    } catch (err: CancellationException) {
      throw err
    } catch (err: Throwable) {
      val message = err.message ?: "background sync failed"
      attemptsSaveError = "Sync paused: $message"
      if (showFailureMessage) onShowMessage("Background sync paused: $message")
      return false
    } finally {
      attemptsSaving = false
    }
  }

  fun finalizeQuestion(
    question: LearnStudyQuestion,
    entry: SessionQueueEntry?,
    correct: Boolean,
  ) {
    sessionAttemptCount += 1
    if (correct) sessionCorrectCount += 1
    queueAttempt(question, entry, correct)
    attemptsSaveError = null
    if (!correct) requeueCurrentQuestion()
    advanceQuestion()
  }

  fun resetSession() {
    sessionQuestions = emptyList()
    sessionQueue = emptyList()
    sessionSeedQuestions = emptyList()
    sessionSeedQueue = emptyList()
    sessionNonce = createSessionNonce()
    sessionIndex = 0
    sessionCorrectCount = 0
    sessionAttemptCount = 0
    resetQuestionState()
    sayItBackRecording = false
    sayItBackRecordingPath = null
    if (sayItBackRecorder != null || sayItBackRecording) {
      Log.d(
        SAY_IT_BACK_LOG_TAG,
        "resetSession: releasing recorder recording=$sayItBackRecording path=${sayItBackRecordingPath ?: "(none)"}",
      )
    }
    runCatching { sayItBackRecorder?.release() }.onFailure { err ->
      Log.w(SAY_IT_BACK_LOG_TAG, "resetSession: release failed: ${err.message}")
    }
    sayItBackRecorder = null
    sessionStartDisplayedStreakDays = resolveDisplayedGlobalStreakDays()
    sessionStartPendingAttemptCount = pendingAttempts.size
    sessionStartPendingClaimCount = pendingStreakClaims.size
    showExitConfirmation = false
  }

  fun loadSummaries(address: String) {
    scope.launch {
      summariesLoading = true
      try {
        fun normalizedTitle(
          value: String?,
          trackId: String,
        ): String? {
          val trimmed = value?.trim().orEmpty()
          if (trimmed.isBlank()) return null
          if (trimmed.equals(trackId.take(14), ignoreCase = true)) return null
          return trimmed
        }

        fun normalizedArtist(value: String?): String? {
          val trimmed = value?.trim().orEmpty()
          if (trimmed.isBlank()) return null
          if (trimmed.equals("Unknown Artist", ignoreCase = true)) return null
          return trimmed
        }

        fun normalizedCoverRef(value: String?): String? {
          val trimmed = value?.trim().orEmpty()
          if (trimmed.isBlank()) return null
          return trimmed
        }

        val rows = StudyProgressApi.fetchUserStudySetSummaries(address)
        if (rows.isEmpty()) {
          val catalogEntries = RecentStudySetCatalogStore.listForUser(context, address)
          if (catalogEntries.isNotEmpty()) {
            val catalogTrackIds =
              catalogEntries
                .mapNotNull { (_, entry) -> entry.trackId?.trim()?.lowercase().takeUnless { it.isNullOrBlank() } }
                .distinct()
            val catalogTrackMeta =
              if (catalogTrackIds.isEmpty()) {
                emptyMap()
              } else {
                runCatching { fetchTrackMeta(catalogTrackIds) }.getOrElse { emptyMap() }
              }

            val fallbackSongs =
              catalogEntries.mapNotNull { (studySetKey, entry) ->
                val normalizedStudySetKey = normalizeBytes32(studySetKey) ?: return@mapNotNull null
                val normalizedTrackId = entry.trackId?.trim()?.lowercase().takeUnless { it.isNullOrBlank() }
                val localPendingAttempts =
                  pendingAttempts.filter { it.studySetKey.equals(normalizedStudySetKey, ignoreCase = true) }
                val resolvedCoverRef = normalizedCoverRef(catalogTrackMeta[normalizedTrackId]?.coverCid)
                val resolvedCoverUrl = resolveReleaseCoverUrl(resolvedCoverRef)
                LearnSongSummaryRow(
                  id = "catalog:$normalizedStudySetKey",
                  studySetKey = normalizedStudySetKey,
                  trackId = normalizedTrackId,
                  title = entry.title.trim(),
                  artist = entry.artist.trim(),
                  coverUri = resolvedCoverUrl,
                  coverFallbackUri =
                    if (resolvedCoverRef != null && !resolvedCoverRef.equals(resolvedCoverUrl, ignoreCase = true)) {
                      resolvedCoverRef
                    } else {
                      null
                    },
                  totalAttempts = entry.totalAttempts + localPendingAttempts.size,
                  uniqueQuestionsTouched =
                    maxOf(
                      entry.uniqueQuestionsTouched,
                      localPendingAttempts.map { it.questionId.lowercase() }.distinct().size,
                    ),
                  streakDays = entry.streakDays,
                )
              }
            availableStudySetKeys = fallbackSongs.map { it.studySetKey }

            if (fallbackSongs.isNotEmpty()) {
              val previousSelectedKey = selectedStudySetKey?.lowercase()
              val fallbackPacks =
                catalogEntries.mapNotNull { (studySetKey, entry) ->
                  val normalizedStudySetKey = normalizeBytes32(studySetKey)?.lowercase() ?: return@mapNotNull null
                  val pack = entry.pack ?: return@mapNotNull null
                  normalizedStudySetKey to pack
                }.toMap()
              songs = fallbackSongs
              globalStreakDays = fallbackSongs.maxOfOrNull { it.streakDays } ?: 0
              globalQualifiedDays = emptySet()
              anchorsByStudySetKey = emptyMap()
              prefetchedPacks = fallbackPacks
              if (hasDirectBoot) {
                prefetchedQueues = emptyMap()
                selectedStudySetKey = null
                queue = null
              } else {
                val nextSelectedStudySetKey =
                  fallbackSongs.firstOrNull { it.studySetKey.lowercase() == previousSelectedKey }?.studySetKey
                    ?: fallbackSongs.firstOrNull()?.studySetKey
                selectedStudySetKey = nextSelectedStudySetKey
                val selectedQueue =
                  if (
                    queue != null &&
                    !previousSelectedKey.isNullOrBlank() &&
                    !nextSelectedStudySetKey.isNullOrBlank() &&
                    nextSelectedStudySetKey.equals(previousSelectedKey, ignoreCase = true)
                  ) {
                    queue
                  } else {
                    null
                  }
                prefetchedQueues =
                  if (selectedQueue != null && !nextSelectedStudySetKey.isNullOrBlank()) {
                    mapOf(nextSelectedStudySetKey.lowercase() to selectedQueue)
                  } else {
                    emptyMap()
                  }
                queue = selectedQueue
                if (nextSelectedStudySetKey == null) {
                  studyPack = null
                }
              }
              return@launch
            }
          }
          availableStudySetKeys = emptyList()
          songs = emptyList()
          globalStreakDays = 0
          globalQualifiedDays = emptySet()
          selectedStudySetKey = null
          anchorsByStudySetKey = emptyMap()
          prefetchedQueues = emptyMap()
          prefetchedPacks = emptyMap()
          queue = null
          if (!hasDirectBoot) studyPack = null
          return@launch
        }

        val anchors = StudyProgressApi.fetchStudySetAnchors(rows.map { it.studySetKey })
        availableStudySetKeys = rows.map { it.studySetKey }
        anchorsByStudySetKey = anchors

        val trackIds =
          buildSet {
            anchors.values.forEach { add(it.trackId.lowercase()) }
            rows.forEach { row ->
              val catalog = RecentStudySetCatalogStore.lookup(context, address, row.studySetKey)
              val catalogTrackId = catalog?.trackId?.trim()?.lowercase().orEmpty()
              if (catalogTrackId.isNotBlank()) add(catalogTrackId)
            }
          }
        val trackMeta =
          if (trackIds.isEmpty()) {
            emptyMap()
          } else {
            runCatching { fetchTrackMeta(trackIds.toList()) }.getOrElse { emptyMap() }
          }

        data class CandidateSong(
          val summary: UserStudySetSummary,
          val anchor: StudySetAnchorRow?,
          val trackId: String?,
          val trackMetaTitle: String?,
          val trackMetaArtist: String?,
          val trackMetaCoverRef: String?,
          val catalog: RecentStudySetCatalogEntry?,
        )

        val candidates =
          rows.mapNotNull { row ->
            val key = row.studySetKey.lowercase()
            val anchor = anchors[key]
            val catalog = RecentStudySetCatalogStore.lookup(context, address, row.studySetKey)
            val trackId = anchor?.trackId?.lowercase() ?: catalog?.trackId?.trim()?.lowercase().orEmpty().ifBlank { null }

            val meta = if (trackId.isNullOrBlank()) null else trackMeta[trackId]
            CandidateSong(
              summary = row,
              anchor = anchor,
              trackId = trackId,
              trackMetaTitle = meta?.title?.ifBlank { null },
              trackMetaArtist = meta?.artist?.ifBlank { null },
              trackMetaCoverRef = meta?.coverCid?.ifBlank { null },
              catalog = catalog,
            )
          }

        songs = emptyList()
        globalStreakDays = 0
        globalQualifiedDays = emptySet()
        prefetchedQueues = emptyMap()
        prefetchedPacks = emptyMap()

        if (candidates.isEmpty() || hasDirectBoot) {
          selectedStudySetKey = null
          queue = null
          if (!hasDirectBoot) studyPack = null
        } else {
          val current = selectedStudySetKey?.lowercase()
          selectedStudySetKey =
            candidates.firstOrNull { it.summary.studySetKey.lowercase() == current }?.summary?.studySetKey
              ?: candidates.firstOrNull()?.summary?.studySetKey
        }

        val todayUtc = LocalDate.ofEpochDay((System.currentTimeMillis() / 1_000L) / 86_400L)
        val streakDaysByStudySet =
          StudyProgressApi.fetchUserStudySetStreakDays(
            userAddress = address,
            studySetKeys = candidates.map { it.summary.studySetKey },
          )
        data class SummarySnapshot(
          val summary: UserStudySetSummary,
          val streak: SongStreakSnapshot,
          val anchor: StudySetAnchorRow?,
        )

        val summarySnapshots =
          coroutineScope {
            candidates.map { candidate ->
              async {
                val key = candidate.summary.studySetKey.lowercase()
                val detail = runCatching { StudyProgressApi.fetchUserStudySetDetail(address, candidate.summary.studySetKey) }.getOrNull()
                val mergedAttempts =
                  mergeDisplayedAttempts(
                    userAddress = address,
                    studySetKey = candidate.summary.studySetKey,
                    onChainAttempts = detail?.attempts.orEmpty(),
                  )
                val queueSnapshot = StudyScheduler.replay(mergedAttempts)
                val anchor = detail?.anchor ?: candidate.anchor
                val pack =
                  if (anchor != null) {
                    runCatching {
                      LearnStudyPackApi.fetchPack(
                        anchor = anchor,
                        preferredLanguage = learnerLanguage,
                        userAddress = address,
                      )
                    }.getOrNull()
                  } else {
                    candidate.catalog?.pack
                  }

                val qualifiedDays =
                  streakDaysByStudySet[key]
                    .orEmpty()
                    .asSequence()
                    .filter { it >= 0L }
                    .map { LocalDate.ofEpochDay(it) }
                    .toSet()
                SummarySnapshot(
                  summary = candidate.summary,
                  streak =
                    SongStreakSnapshot(
                      studySetKey = key,
                      streakDays = consecutiveDayStreak(qualifiedDays, todayUtc),
                      qualifiedDays = qualifiedDays,
                      queue = queueSnapshot,
                      pack = pack,
                    ),
                  anchor = anchor,
                )
              }
            }.awaitAll()
          }

        val streakByKey = LinkedHashMap<String, Int>(summarySnapshots.size)
        val qualifiedAcrossSongs = LinkedHashSet<LocalDate>()
        val queuesByKey = LinkedHashMap<String, StudyQueueSnapshot>(summarySnapshots.size)
        val packsByKey = LinkedHashMap<String, LearnStudySetPack>()
        for (snapshot in summarySnapshots) {
          val streak = snapshot.streak
          streakByKey[streak.studySetKey] = streak.streakDays
          qualifiedAcrossSongs.addAll(streak.qualifiedDays)
          queuesByKey[streak.studySetKey] = streak.queue
          val pack = streak.pack
          if (pack != null) packsByKey[streak.studySetKey] = pack
        }

        val candidateByKey = candidates.associateBy { it.summary.studySetKey.lowercase() }
        val displaySongs =
          summarySnapshots.mapNotNull { snapshot ->
            val key = snapshot.summary.studySetKey.lowercase()
            val candidate = candidateByKey[key] ?: return@mapNotNull null
            val pack = snapshot.streak.pack
            val trackId = candidate.trackId ?: pack?.trackId?.trim()?.lowercase().orEmpty()
            // Only use catalog title/artist if the catalog entry has a valid trackId.
            // A null trackId means the entry is incomplete or was written by a bad build.
            val trustedCatalog = candidate.catalog?.takeIf { !it.trackId.isNullOrBlank() }
            val resolvedTitle =
              normalizedTitle(candidate.trackMetaTitle, trackId)
                ?: normalizedTitle(trustedCatalog?.title, trackId)
                ?: normalizedTitle(pack?.attributionTrack, trackId)
            val resolvedArtist =
              normalizedArtist(candidate.trackMetaArtist)
                ?: normalizedArtist(trustedCatalog?.artist)
                ?: normalizedArtist(pack?.attributionArtist)
            if (resolvedTitle == null || resolvedArtist == null) return@mapNotNull null
            val resolvedCoverRef = normalizedCoverRef(candidate.trackMetaCoverRef)
            val resolvedCoverUrl = resolveReleaseCoverUrl(resolvedCoverRef)

            LearnSongSummaryRow(
              id = snapshot.summary.id,
              studySetKey = snapshot.summary.studySetKey,
              trackId = trackId.ifBlank { null },
              title = resolvedTitle,
              artist = resolvedArtist,
              coverUri = resolvedCoverUrl,
              coverFallbackUri =
                if (resolvedCoverRef != null && !resolvedCoverRef.equals(resolvedCoverUrl, ignoreCase = true)) {
                  resolvedCoverRef
                } else {
                  null
                },
              totalAttempts = snapshot.summary.totalAttempts,
              uniqueQuestionsTouched = snapshot.summary.uniqueQuestionsTouched,
              streakDays = streakByKey[key] ?: 0,
            )
          }

        songs = displaySongs
        RecentStudySetCatalogStore.recordAll(
          context = context,
          userAddress = address,
          entries =
            summarySnapshots.mapNotNull { snapshot ->
              val key = snapshot.summary.studySetKey.lowercase()
              val song =
                displaySongs.firstOrNull { it.studySetKey.equals(snapshot.summary.studySetKey, ignoreCase = true) }
                  ?: return@mapNotNull null
              val candidate = candidateByKey[key] ?: return@mapNotNull null
              val anchor = snapshot.anchor ?: candidate.anchor
              val pack = snapshot.streak.pack ?: candidate.catalog?.pack
              snapshot.summary.studySetKey to
                RecentStudySetCatalogEntry(
                  trackId = song.trackId,
                  title = song.title,
                  artist = song.artist,
                  language = pack?.language ?: candidate.catalog?.language,
                  version = anchor?.version?.coerceIn(1, 255) ?: candidate.catalog?.version ?: 1,
                  studySetRef = anchor?.studySetRef?.trim()?.ifBlank { null } ?: candidate.catalog?.studySetRef,
                  totalAttempts = snapshot.summary.totalAttempts,
                  uniqueQuestionsTouched = snapshot.summary.uniqueQuestionsTouched,
                  streakDays = streakByKey[key] ?: 0,
                  pack = pack,
                )
            },
        )
        if (!hasDirectBoot) {
          val previousSelectedKey = selectedStudySetKey?.lowercase()
          val nextSelectedStudySetKey =
            displaySongs.firstOrNull { it.studySetKey.lowercase() == previousSelectedKey }?.studySetKey
              ?: displaySongs.firstOrNull()?.studySetKey
              ?: candidates.firstOrNull { it.summary.studySetKey.lowercase() == previousSelectedKey }?.summary?.studySetKey
              ?: candidates.firstOrNull()?.summary?.studySetKey
          selectedStudySetKey = nextSelectedStudySetKey
          queue =
            nextSelectedStudySetKey
              ?.lowercase()
              ?.let { queuesByKey[it] }
              ?: if (
                queue != null &&
                !previousSelectedKey.isNullOrBlank() &&
                !nextSelectedStudySetKey.isNullOrBlank() &&
                nextSelectedStudySetKey.equals(previousSelectedKey, ignoreCase = true)
              ) {
                queue
              } else {
                null
              }
          if (nextSelectedStudySetKey == null) {
            queue = null
            studyPack = null
          }
        }

        globalStreakDays = consecutiveDayStreak(qualifiedAcrossSongs, todayUtc)
        globalQualifiedDays = qualifiedAcrossSongs
        prefetchedQueues = queuesByKey
        prefetchedPacks = packsByKey
      } catch (err: CancellationException) {
        throw err
      } catch (err: Throwable) {
        onShowMessage("Learn load failed: ${err.message ?: "unknown error"}")
      } finally {
        summariesLoading = false
      }
    }
  }

  fun loadDetail(address: String, studySetKey: String) {
    scope.launch {
      detailLoading = true
      val previousQueue = queue
      val previousStudyPack = studyPack
      try {
        val normalizedKey = studySetKey.lowercase()
        val prefetchedQueue = prefetchedQueues[normalizedKey]
        val prefetchedPack = prefetchedPacks[normalizedKey]
        val catalogPack = RecentStudySetCatalogStore.lookup(context, address, studySetKey)?.pack
        if (prefetchedQueue != null) queue = prefetchedQueue
        if (prefetchedPack != null) studyPack = prefetchedPack else if (catalogPack != null) studyPack = catalogPack

        val result = StudyProgressApi.fetchUserStudySetDetail(address, studySetKey)
        val mergedAttempts =
          mergeDisplayedAttempts(
            userAddress = address,
            studySetKey = studySetKey,
            onChainAttempts = result?.attempts.orEmpty(),
          )
        if (result != null || mergedAttempts.isNotEmpty() || prefetchedQueue == null) {
          val replayedQueue = StudyScheduler.replay(mergedAttempts)
          queue = replayedQueue
          prefetchedQueues = prefetchedQueues + (normalizedKey to replayedQueue)
        } else if (prefetchedQueue != null) {
          queue = prefetchedQueue
        }

        val anchor = result?.anchor ?: anchorsByStudySetKey[normalizedKey]
        if (anchor != null) {
          studyPack =
            try {
              prefetchedPack ?: LearnStudyPackApi.fetchPack(
                anchor = anchor,
                preferredLanguage = learnerLanguage,
                userAddress = address,
              )
            } catch (err: CancellationException) {
              throw err
            } catch (err: Throwable) {
              onShowMessage("Learn exercises failed: ${err.message ?: "unknown error"}")
              null
            }
        } else if (studyPack == null) {
          studyPack = null
        }
        val resolvedPack = studyPack
        if (resolvedPack != null) {
          prefetchedPacks = prefetchedPacks + (normalizedKey to resolvedPack)
        }
      } catch (err: CancellationException) {
        throw err
      } catch (err: Throwable) {
        queue = prefetchedQueues[studySetKey.lowercase()] ?: previousQueue
        studyPack =
          prefetchedPacks[studySetKey.lowercase()]
            ?: RecentStudySetCatalogStore.lookup(context, address, studySetKey)?.pack
            ?: previousStudyPack
        onShowMessage("Learn detail failed: ${err.message ?: "unknown error"}")
      } finally {
        detailLoading = false
      }
    }
  }

  LaunchedEffect(isAuthenticated, userAddress) {
    if (!isAuthenticated || userAddress.isNullOrBlank()) {
      songs = emptyList()
      globalStreakDays = 0
      globalQualifiedDays = emptySet()
      summariesLoading = false
      selectedStudySetKey = null
      availableStudySetKeys = emptyList()
      anchorsByStudySetKey = emptyMap()
      prefetchedQueues = emptyMap()
      prefetchedPacks = emptyMap()
      queue = null
      studyPack = null
      dashboardActionLoading = false
      detailLoading = false
      sessionActive = false
      pendingAttempts = emptyList()
      pendingStreakClaims = emptyList()
      attemptsSaving = false
      attemptsSaveError = null
      showExitConfirmation = false
      exitClosing = false
      resetSession()
      return@LaunchedEffect
    }

    val restoredPending = LearnPendingSyncStore.load(context, userAddress)
    pendingAttempts = restoredPending.attempts
    pendingStreakClaims = restoredPending.streakClaims
    attemptsSaveError = null

    if (hasDirectBoot) {
      songs = emptyList()
      globalStreakDays = 0
      globalQualifiedDays = emptySet()
      summariesLoading = false
      selectedStudySetKey = null
      availableStudySetKeys = emptyList()
      anchorsByStudySetKey = emptyMap()
      prefetchedQueues = emptyMap()
      prefetchedPacks = emptyMap()
      queue = null
      dashboardActionLoading = false
      return@LaunchedEffect
    }

    loadSummaries(userAddress)
  }

  LaunchedEffect(isAuthenticated, initialStudySetRef, initialTrackId, initialLanguage, initialVersion, initialTitle, initialArtist) {
    val ref = initialStudySetRef?.trim().orEmpty()
    val trackId = initialTrackId?.trim().orEmpty()
    if (!isAuthenticated || (ref.isBlank() && trackId.isBlank())) return@LaunchedEffect
    val routeTitle = initialTitle?.trim()?.ifBlank { null }
    val routeArtist = initialArtist?.trim()?.ifBlank { null }
    Log.d(
      "LearnLaunch",
      "LearnScreen direct boot start ref='$ref' trackId='$trackId' lang='${initialLanguage ?: learnerLanguage}' v=$initialVersion title='$routeTitle' artist='$routeArtist'",
    )
    detailLoading = true
    val preferredLanguage = initialLanguage?.trim().orEmpty().ifBlank { learnerLanguage }
    val launchStudySetKey =
      StudySetKeyDerivation.derive(
        trackId = trackId,
        language = preferredLanguage,
        version = initialVersion.coerceIn(1, 255),
      )
    recordCatalogEntry(
      studySetKey = launchStudySetKey,
      trackId = trackId,
      title = routeTitle,
      artist = routeArtist,
      language = preferredLanguage,
      version = initialVersion,
      studySetRef = ref.ifBlank { null },
    )
    val resolved =
      if (trackId.isNotBlank()) {
        val ensureUserAddress = userAddress?.trim().orEmpty()
        if (ensureUserAddress.isBlank()) {
          onShowMessage("Learn exercises failed: missing user address")
          null
        } else try {
          LearnStudyPackApi.ensurePackByTrack(
            trackId = trackId,
            preferredLanguage = preferredLanguage,
            version = initialVersion.coerceIn(1, 255),
            userAddress = ensureUserAddress,
          )
        } catch (err: CancellationException) {
          throw err
        } catch (err: Throwable) {
          Log.w(
            "LearnScreen",
            "Track ensure load failed trackId=$trackId lang=$preferredLanguage v=$initialVersion err=${err.message}",
          )
          null
        }
      } else {
        null
      }

    val pack =
      resolved
        ?: if (ref.isNotBlank()) {
          try {
            LearnStudyPackApi.fetchPackByRef(ref)
          } catch (err: CancellationException) {
            throw err
          } catch (err: Throwable) {
            Log.w("LearnScreen", "Direct ref load failed ref=$ref err=${err.message}")
            null
          }
        } else {
          null
        }

    if (pack != null) {
      val normalizedAddress = userAddress?.trim().orEmpty()
      val normalizedTrackId = pack.trackId.trim().lowercase()
      val meta = if (normalizedTrackId.isBlank()) {
        null
      } else {
        runCatching { fetchTrackMeta(listOf(normalizedTrackId)) }.getOrElse { emptyMap() }[normalizedTrackId]
      }
      val metaTitle = meta?.title?.trim().orEmpty()
      val metaArtist = meta?.artist?.trim().orEmpty()
      val resolvedTitle =
        when {
          metaTitle.isNotBlank() && !metaTitle.equals(normalizedTrackId.take(14), ignoreCase = true) -> metaTitle
          !pack.attributionTrack.isNullOrBlank() -> pack.attributionTrack.trim()
          routeTitle != null -> routeTitle
          else -> ""
        }
      val resolvedArtist =
        when {
          metaArtist.isNotBlank() && !metaArtist.equals("Unknown Artist", ignoreCase = true) -> metaArtist
          !pack.attributionArtist.isNullOrBlank() -> pack.attributionArtist.trim()
          routeArtist != null -> routeArtist
          else -> ""
        }
      if (normalizedAddress.isNotBlank() && resolvedTitle.isNotBlank() && resolvedArtist.isNotBlank()) {
        val studySetKey =
          StudySetKeyDerivation.derive(
            trackId = pack.trackId,
            language = pack.language,
            version = initialVersion.coerceIn(1, 255),
          )
        recordCatalogEntry(
          studySetKey = studySetKey,
          trackId = normalizedTrackId,
          title = resolvedTitle,
          artist = resolvedArtist,
          language = pack.language,
          version = initialVersion,
          studySetRef = ref.ifBlank { null },
          pack = pack,
        )
      }
      studyPack = pack
      val directStudySetKey =
        StudySetKeyDerivation.derive(
          trackId = pack.trackId,
          language = pack.language,
          version = initialVersion.coerceIn(1, 255),
        )
      val normalizedDirectStudySetKey = normalizeBytes32(directStudySetKey)
      if (normalizedDirectStudySetKey.isNullOrBlank()) {
        onShowMessage("Learn exercises failed: invalid study set context")
      } else {
        val questions = derivePracticeQuestions(studyPack = pack, queue = queue)
        val source =
          SessionQuestionSource(
            studySetKey = normalizedDirectStudySetKey,
            trackId = pack.trackId.trim().lowercase(),
            version = initialVersion.coerceIn(1, 255),
            language = pack.language,
          )
        ensureLearnBackgroundSavingReady()
        resetSession()
        startSession(
          questions = questions,
          initialQueue = buildInitialSessionQueue(questions.size, source),
        )
      }
      Log.d(
        "LearnLaunch",
        "LearnScreen direct boot success questions=${pack.questions.size} trackId='${pack.trackId}' lang='${pack.language}' say_it_back=${pack.questions.count { it.type == "say_it_back" }} translation=${pack.questions.count { it.type == "translation_mcq" }} trivia=${pack.questions.count { it.type == "trivia_mcq" }}",
      )
    } else {
      Log.e(
        "LearnLaunch",
        "LearnScreen direct boot failed ref='$ref' trackId='$trackId' lang='$preferredLanguage' v=$initialVersion",
      )
      onShowMessage("Learn exercises failed: could not load generated pack")
    }
    detailLoading = false
  }

  LaunchedEffect(isAuthenticated, userAddress, selectedStudySetKey) {
    if (hasDirectBoot) {
      queue = null
      return@LaunchedEffect
    }
    val address = userAddress
    val studySetKey = selectedStudySetKey
    if (!isAuthenticated || address.isNullOrBlank() || studySetKey.isNullOrBlank()) {
      queue = null
      studyPack = null
      detailLoading = false
      return@LaunchedEffect
    }
    detailLoading = true
    loadDetail(address, studySetKey)
  }

  val currentQueueEntry = sessionQueue.getOrNull(sessionIndex)
  val currentQuestion = currentQueueEntry?.let { sessionQuestions.getOrNull(it.questionIndex) }
  val sessionCompleted = sessionActive && sessionQueue.isNotEmpty() && sessionIndex >= sessionQueue.size
  val displayedCompletionStreakDays = resolveDisplayedGlobalStreakDays()
  val awardedCompletionStreakDays =
    displayedCompletionStreakDays.takeIf { it > sessionStartDisplayedStreakDays }
  val currentMcqOptions =
    if (currentQuestion?.isMcq == true) {
      val order =
        shuffledIndexes(
          currentQuestion.choices.size,
          seededRng("${sessionNonce}:${currentQuestion.questionIdHash}:${currentQueueEntry?.exposure ?: 0}"),
        )
      order.map { choiceIndex ->
        PracticeChoiceOption(
          text = currentQuestion.choices.getOrElse(choiceIndex) { "" },
          isCorrect = choiceIndex == currentQuestion.correctIndex,
        )
      }
    } else {
      emptyList()
    }
  val directBootTransitioning = hasDirectBoot && !sessionActive && detailLoading

  LaunchedEffect(isAuthenticated, userAddress, pendingAttempts.size, pendingStreakClaims.size) {
    if (!isAuthenticated || userAddress.isNullOrBlank()) return@LaunchedEffect
    val hasPendingWork = pendingAttempts.isNotEmpty() || pendingStreakClaims.isNotEmpty()
    if (!hasPendingWork || attemptsSaving) return@LaunchedEffect
    delay(1_500L)
    if (!attemptsSaving) {
      flushPendingAttempts(showFailureMessage = false)
    }
  }

  fun resolveStudySetVersion(studySetKey: String): Int =
    anchorsByStudySetKey[studySetKey.lowercase()]?.version?.coerceIn(1, 255) ?: initialVersion.coerceIn(1, 255)

  fun resolveExerciseResolutionContext(studySetKey: String): LearnExerciseResolutionContext? {
    val normalizedStudySetKey = normalizeBytes32(studySetKey) ?: return null
    val lowerKey = normalizedStudySetKey.lowercase()
    val anchor = anchorsByStudySetKey[lowerKey]
    val song = songs.firstOrNull { it.studySetKey.equals(normalizedStudySetKey, ignoreCase = true) }
    val catalog =
      if (userAddress.isNullOrBlank()) {
        null
      } else {
        RecentStudySetCatalogStore.lookup(context, userAddress, normalizedStudySetKey)
      }
    val studySetRef =
      anchor?.studySetRef?.trim()?.takeIf { it.isNotBlank() }
        ?: catalog?.studySetRef?.trim()?.takeIf { it.isNotBlank() }
    val trackId =
      normalizeBytes32(anchor?.trackId)
        ?: normalizeBytes32(catalog?.trackId)
        ?: song?.trackId?.let(::normalizeBytes32)
    val language =
      catalog?.language?.trim()?.takeIf { it.isNotBlank() }
        ?: learnerLanguage
    val version =
      anchor?.version?.coerceIn(1, 255)
        ?: catalog?.version?.coerceIn(1, 255)
        ?: resolveStudySetVersion(normalizedStudySetKey)
    if (studySetRef.isNullOrBlank() && trackId.isNullOrBlank()) return null
    return LearnExerciseResolutionContext(
      studySetRef = studySetRef,
      trackId = trackId,
      language = language,
      version = version,
      song = song,
      catalog = catalog,
      anchor = anchor,
    )
  }

  suspend fun resolvePackForStudySet(studySetKey: String): LearnStudySetPack? {
    val normalizedStudySetKey = normalizeBytes32(studySetKey) ?: return null
    val lowerKey = normalizedStudySetKey.lowercase()
    val selectedKey = normalizeBytes32(selectedStudySetKey)
    if (!selectedKey.isNullOrBlank() && selectedKey.equals(normalizedStudySetKey, ignoreCase = true) && studyPack != null) {
      return studyPack
    }
    prefetchedPacks[lowerKey]?.let { return it }

    val address = userAddress?.trim().orEmpty()
    val cached =
      if (address.isNotBlank()) {
        RecentStudySetCatalogStore.lookup(context, address, normalizedStudySetKey)?.pack
      } else {
        null
      }
    if (cached != null) {
      prefetchedPacks = prefetchedPacks + (lowerKey to cached)
      return cached
    }

    val resolution = resolveExerciseResolutionContext(normalizedStudySetKey)
    val fetched =
      when {
        resolution?.anchor != null -> {
          runCatching {
            LearnStudyPackApi.fetchPack(
              anchor = resolution.anchor,
              preferredLanguage = resolution.language,
              userAddress = address,
            )
          }.getOrNull()
        }
        !resolution?.studySetRef.isNullOrBlank() -> {
          runCatching {
            LearnStudyPackApi.fetchPackByRef(resolution?.studySetRef.orEmpty())
          }.getOrNull()
        }
        !resolution?.trackId.isNullOrBlank() && address.isNotBlank() -> {
          runCatching {
            LearnStudyPackApi.ensurePackByTrack(
              trackId = resolution?.trackId.orEmpty(),
              preferredLanguage = resolution?.language.orEmpty().ifBlank { learnerLanguage },
              version = resolution?.version ?: resolveStudySetVersion(normalizedStudySetKey),
              userAddress = address,
            )
          }.getOrNull()
        }
        else -> null
      }
    if (fetched != null) {
      prefetchedPacks = prefetchedPacks + (lowerKey to fetched)
    }
    return fetched
  }

  fun resolveQueueForStudySet(studySetKey: String): StudyQueueSnapshot? {
    val normalizedStudySetKey = normalizeBytes32(studySetKey) ?: return null
    val lowerKey = normalizedStudySetKey.lowercase()
    val selectedKey = normalizeBytes32(selectedStudySetKey)
    if (!selectedKey.isNullOrBlank() && selectedKey.equals(normalizedStudySetKey, ignoreCase = true)) {
      return queue
    }
    return prefetchedQueues[lowerKey]
  }

  suspend fun resolveExerciseLaunchTarget(studySetKey: String): LearnExerciseLaunchTarget? {
    val normalizedStudySetKey = normalizeBytes32(studySetKey) ?: return null
    val resolution = resolveExerciseResolutionContext(normalizedStudySetKey) ?: return null
    val pack = resolvePackForStudySet(normalizedStudySetKey)
    val trackId =
      resolution.trackId
        ?: normalizeBytes32(pack?.trackId)
    val song = resolution.song
    val catalog = resolution.catalog
    val language =
      pack?.language?.trim()?.takeIf { it.isNotBlank() }
        ?: resolution.language
    val version =
      resolution.version
    return LearnExerciseLaunchTarget(
      studySetRef = resolution.studySetRef,
      trackId = trackId,
      language = language,
      version = version,
      title = song?.title ?: catalog?.title ?: pack?.attributionTrack,
      artist = song?.artist ?: catalog?.artist ?: pack?.attributionArtist,
    )
  }

  fun buildSessionSource(
    studySetKey: String,
    pack: LearnStudySetPack,
  ): SessionQuestionSource? {
    val normalizedStudySetKey = normalizeBytes32(studySetKey) ?: return null
    val normalizedTrackId = normalizeBytes32(pack.trackId) ?: return null
    return SessionQuestionSource(
      studySetKey = normalizedStudySetKey,
      trackId = normalizedTrackId,
      version = resolveStudySetVersion(normalizedStudySetKey),
      language = pack.language,
    )
  }

  suspend fun startSessionForStudySet(studySetKey: String): Boolean {
    val normalizedStudySetKey = normalizeBytes32(studySetKey)
    if (normalizedStudySetKey.isNullOrBlank()) {
      onShowMessage("Learn exercises failed: invalid study set")
      return false
    }
    val pack = resolvePackForStudySet(normalizedStudySetKey)
    if (pack == null) {
      onShowMessage("Learn exercises failed: could not load study set")
      return false
    }
    val songQueue = resolveQueueForStudySet(normalizedStudySetKey)
    val questions = derivePracticeQuestions(studyPack = pack, queue = songQueue)
    if (questions.isEmpty()) {
      onShowMessage("No exercises available for this study set")
      return false
    }
    val source = buildSessionSource(studySetKey = normalizedStudySetKey, pack = pack)
    if (source == null) {
      onShowMessage("Learn exercises failed: invalid study set source")
      return false
    }

    selectedStudySetKey = normalizedStudySetKey
    studyPack = pack
    queue = songQueue
    ensureLearnBackgroundSavingReady()
    resetSession()
    startSession(
      questions = questions,
      initialQueue = buildInitialSessionQueue(questions.size, source),
    )
    return true
  }

  suspend fun startSessionForAllSongs(): Boolean {
    if (songs.isEmpty()) {
      onShowMessage("No study sets available yet")
      return false
    }

    val mergedQuestions = ArrayList<LearnStudyQuestion>()
    val mergedQueue = ArrayList<SessionQueueEntry>()

    for (song in songs) {
      val normalizedStudySetKey = normalizeBytes32(song.studySetKey) ?: continue
      val pack = resolvePackForStudySet(normalizedStudySetKey) ?: continue
      val source = buildSessionSource(studySetKey = normalizedStudySetKey, pack = pack) ?: continue
      val songQueue = resolveQueueForStudySet(normalizedStudySetKey)
      val orderedQuestions = derivePracticeQuestions(studyPack = pack, queue = songQueue)
      if (orderedQuestions.isEmpty()) continue

      val startIndex = mergedQuestions.size
      mergedQuestions.addAll(orderedQuestions)
      for (offset in orderedQuestions.indices) {
        mergedQueue.add(
          SessionQueueEntry(
            questionIndex = startIndex + offset,
            exposure = 0,
            requeueCount = 0,
            source = source,
          ),
        )
      }
    }

    if (mergedQuestions.isEmpty()) {
      onShowMessage("No exercises available to study across your songs")
      return false
    }

    ensureLearnBackgroundSavingReady()
    resetSession()
    startSession(
      questions = mergedQuestions,
      initialQueue = mergedQueue,
    )
    return true
  }

  fun onPrimaryAction(question: LearnStudyQuestion) {
    val activeEntry = sessionQueue.getOrNull(sessionIndex)
    val activeQuestion = activeEntry?.let { sessionQuestions.getOrNull(it.questionIndex) }
    if (activeQuestion == null) {
      Log.w(
        SAY_IT_BACK_LOG_TAG,
        "primaryAction: missing current question uiQuestionId=${question.id} sessionActive=$sessionActive sessionIndex=$sessionIndex queueSize=${sessionQueue.size} questionCount=${sessionQuestions.size}",
      )
    } else if (!activeQuestion.id.equals(question.id, ignoreCase = true)) {
      Log.w(
        SAY_IT_BACK_LOG_TAG,
        "primaryAction: question mismatch uiQuestionId=${question.id} activeQuestionId=${activeQuestion.id} sessionIndex=$sessionIndex",
      )
    }
    if (question.isMcq) {
      if (!answerRevealed) return
      finalizeQuestion(question, activeEntry, selectedChoiceCorrect == true)
      return
    }

    if (answerRevealed) {
      finalizeQuestion(question, activeEntry, sayItBackPassed == true)
      return
    }
    if (sayItBackScoring) return
    Log.d(
      SAY_IT_BACK_LOG_TAG,
      "primaryAction: answerRevealed=$answerRevealed recording=$sayItBackRecording scoring=$sayItBackScoring attempts=$sayItBackAttemptCount hasRecorder=${sayItBackRecorder != null} hasPath=${!sayItBackRecordingPath.isNullOrBlank()}",
    )
    if (!sayItBackRecording) {
      val hasMicPermission =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
      if (!hasMicPermission) {
        Log.d(SAY_IT_BACK_LOG_TAG, "start: mic permission missing, requesting")
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        return
      }

      val file = File(context.cacheDir, "say-it-back-${System.currentTimeMillis()}.m4a")
      Log.d(SAY_IT_BACK_LOG_TAG, "start: creating recorder path=${file.absolutePath}")
      val recorder = runCatching { buildSayItBackRecorder(context, file) }.onFailure { err ->
        Log.e(SAY_IT_BACK_LOG_TAG, "start: recorder creation failed: ${err.message}", err)
      }.getOrElse { err ->
        onShowMessage("Recording failed: ${err.message ?: "unknown error"}")
        return
      }

      sayItBackRecorder = recorder
      sayItBackRecordingPath = file.absolutePath
      sayItBackRecording = true
      sayItBackTranscript = null
      sayItBackScore = null
      sayItBackPassed = null
      sayItBackGradeMessage = null
      sayItBackAttestation = null
      answerRevealed = false
      Log.d(SAY_IT_BACK_LOG_TAG, "start: recorder ready path=${file.absolutePath}")
      return
    }

    val recorder = sayItBackRecorder
    val path = sayItBackRecordingPath
    Log.d(
      SAY_IT_BACK_LOG_TAG,
      "stop: requested hasRecorder=${recorder != null} path=${path ?: "(none)"}",
    )
    sayItBackRecorder = null
    sayItBackRecording = false
    sayItBackRecordingPath = null
    var stopFailure: Throwable? = null
    runCatching { recorder?.stop() }.onFailure { err ->
      stopFailure = err
      Log.e(SAY_IT_BACK_LOG_TAG, "stop: recorder stop failed: ${err.message}", err)
    }
    runCatching { recorder?.release() }.onFailure { err ->
      Log.w(SAY_IT_BACK_LOG_TAG, "stop: recorder release failed: ${err.message}")
    }
    val stopOk =
      if (stopFailure == null) {
        true
      } else {
        sayItBackTranscript = "Recording failed."
        sayItBackScore = 0.0
        sayItBackPassed = false
        sayItBackGradeMessage = "Try again"
        sayItBackAttestation = null
        answerRevealed = false
        false
      }
    if (!stopOk) return
    if (path.isNullOrBlank()) return

    val audioFile = File(path)
    if (!audioFile.exists()) {
      sayItBackTranscript = "Recording unavailable."
      sayItBackScore = 0.0
      sayItBackPassed = false
      sayItBackGradeMessage = "Try again"
      sayItBackAttestation = null
      answerRevealed = false
      return
    }
    Log.d(SAY_IT_BACK_LOG_TAG, "stop: audio captured path=$path bytes=${audioFile.length()}")

    sayItBackScoring = true
    scope.launch {
      Log.d(SAY_IT_BACK_LOG_TAG, "score: start path=$path")
      val source = activeEntry?.source
      val result =
        LearnSayItBackApi.scoreRecording(
          trackId = source?.trackId ?: studyPack?.trackId.orEmpty(),
          questionId = question.id,
          expectedExcerpt = question.excerpt,
          version = source?.version ?: resolveActiveStudySetVersion(),
          language = source?.language ?: studyPack?.language.orEmpty(),
          difficulty = question.difficulty,
          userAddress = userAddress?.trim().orEmpty(),
          audioFile = audioFile,
        )
      sayItBackScoring = false
      Log.d(
        SAY_IT_BACK_LOG_TAG,
        "score: result success=${result.success} passed=${result.passed} score=${result.score} transcript=${result.transcript ?: "(none)"} error=${result.error ?: "(none)"}",
      )
      runCatching { audioFile.delete() }
      if (!result.success) {
        sayItBackTranscript = result.error ?: "Scoring unavailable."
        sayItBackScore = 0.0
        sayItBackPassed = false
        sayItBackGradeMessage = "Try again"
        sayItBackAttestation = null
        answerRevealed = false
        return@launch
      }
      sayItBackTranscript = result.transcript
      sayItBackScore = result.score
      sayItBackPassed = result.passed
      sayItBackAttestation = result.streakAttestation
      if (result.passed && TempoStreakClaimApi.isConfigured() && result.streakAttestation == null) {
        onShowMessage("Streak attestation unavailable. Day claim skipped.")
      }
      if (result.passed) {
        sayItBackGradeMessage = scoreToGrade(result.score, true)
        answerRevealed = true
      } else {
        val nextAttemptCount = sayItBackAttemptCount + 1
        sayItBackAttemptCount = nextAttemptCount
        sayItBackGradeMessage =
          if (nextAttemptCount >= SAY_IT_BACK_MAX_ATTEMPTS) {
            null
          } else {
            "Try again. One more attempt"
          }
        answerRevealed = nextAttemptCount >= SAY_IT_BACK_MAX_ATTEMPTS
      }
      playExerciseFeedbackSound(context = context, isCorrect = result.passed)
    }
  }

  fun shouldConfirmExit(): Boolean {
    if (sessionCompleted) return false
    if (sessionIndex > 0) return true
    if (pendingAttempts.size > sessionStartPendingAttemptCount) return true
    if (pendingStreakClaims.size > sessionStartPendingClaimCount) return true
    if (answerRevealed || !sayItBackTranscript.isNullOrBlank()) return true
    if (sayItBackRecording || sayItBackScoring) return true
    return false
  }

  fun closeSession() {
    if (exitClosing) return
    exitClosing = true
    showExitConfirmation = false

    val activeStudySetKey = resolveActiveStudySetKey()
    val address = userAddress?.trim().orEmpty()
    val packToSave = studyPack
    val summaryToSave =
      if (activeStudySetKey.isNullOrBlank()) {
        null
      } else {
        songs.firstOrNull { it.studySetKey.equals(activeStudySetKey, ignoreCase = true) }
      }
    sessionActive = false
    resetSession()
    exitClosing = false

    val title =
      summaryToSave?.title?.trim()?.ifBlank { null }
        ?: packToSave?.attributionTrack?.trim()?.ifBlank { null }
    val artist =
      summaryToSave?.artist?.trim()?.ifBlank { null }
        ?: packToSave?.attributionArtist?.trim()?.ifBlank { null }
    if (address.isNotBlank() && !activeStudySetKey.isNullOrBlank() && packToSave != null && title != null && artist != null) {
      RecentStudySetCatalogStore.record(
        context = context,
        userAddress = address,
        studySetKey = activeStudySetKey,
        entry =
          RecentStudySetCatalogEntry(
            trackId = packToSave.trackId.trim().lowercase().ifBlank { null },
            title = title,
            artist = artist,
            language = packToSave.language,
            version = resolveActiveStudySetVersion(),
            totalAttempts = summaryToSave?.totalAttempts ?: 0,
            uniqueQuestionsTouched = summaryToSave?.uniqueQuestionsTouched ?: 0,
            streakDays = summaryToSave?.streakDays ?: 0,
            pack = packToSave,
          ),
      )
    }

    if (hasDirectBoot) {
      onExitToLearn()
    } else if (address.isNotBlank()) {
      if (!activeStudySetKey.isNullOrBlank()) {
        loadDetail(address, activeStudySetKey)
      }
      loadSummaries(address)
    }
  }

  fun requestExitSession() {
    if (exitClosing) return
    if (shouldConfirmExit()) {
      showExitConfirmation = true
      return
    }
    closeSession()
  }

  LearnScreenBody(
    isAuthenticated = isAuthenticated,
    userAddress = userAddress,
    onOpenDrawer = onOpenDrawer,
    songs = songs,
    globalStreakDays = globalStreakDays,
    summariesLoading = summariesLoading,
    selectedStudySetKey = selectedStudySetKey,
    onSelectStudySetKey = { selectedStudySetKey = it },
    detailLoading = detailLoading,
    queue = queue,
    currentQuestion = currentQuestion,
    sessionQuestions = sessionQuestions,
    sessionQueueSize = sessionQueue.size,
    sessionActive = sessionActive,
    sessionCompleted = sessionCompleted,
    sessionIndex = sessionIndex,
    sessionCorrectCount = sessionCorrectCount,
    sessionAttemptCount = sessionAttemptCount,
    completionStreakDays = awardedCompletionStreakDays,
    answerRevealed = answerRevealed,
    selectedChoiceIndex = selectedChoiceIndex,
    sayItBackRecording = sayItBackRecording,
    sayItBackScoring = sayItBackScoring,
    sayItBackTranscript = sayItBackTranscript,
    sayItBackScore = sayItBackScore,
    sayItBackPassed = sayItBackPassed,
    sayItBackGradeMessage = sayItBackGradeMessage,
    pendingSyncCount = pendingAttempts.size + pendingStreakClaims.size,
    savingProgress = attemptsSaving,
    saveError = attemptsSaveError,
    exitEnabled = !showExitConfirmation && !exitClosing,
    directBootTransitioning = directBootTransitioning,
    studyAllLoading = dashboardActionLoading || detailLoading || summariesLoading,
    miniPlayerVisible = miniPlayerVisible,
    mcqOptions = currentMcqOptions,
    onSelectChoice = { idx ->
      if (!answerRevealed) {
        selectedChoiceIndex = idx
        val correct = currentMcqOptions.getOrNull(idx)?.isCorrect == true
        selectedChoiceCorrect = correct
        answerRevealed = true
        playExerciseFeedbackSound(context = context, isCorrect = correct)
      }
    },
    onPrimaryAction = ::onPrimaryAction,
    onExitSession = { requestExitSession() },
    onPracticeAgain = {
      scope.launch {
        ensureLearnBackgroundSavingReady()
        if (sessionSeedQuestions.isEmpty() || sessionSeedQueue.isEmpty()) return@launch
        startSession(
          questions = sessionSeedQuestions,
          initialQueue = sessionSeedQueue,
        )
      }
    },
    onStartStudySet = { studySetKey ->
      if (!dashboardActionLoading && !detailLoading) {
        scope.launch {
          dashboardActionLoading = true
          try {
            val launchTarget = resolveExerciseLaunchTarget(studySetKey)
            if (launchTarget == null) {
              onShowMessage("Learn exercises failed: missing study set launch context")
              return@launch
            }
            onOpenStudySet(
              launchTarget.studySetRef,
              launchTarget.trackId,
              launchTarget.language,
              launchTarget.version,
              null,
              null,
            )
          } finally {
            dashboardActionLoading = false
          }
        }
      }
    },
    onStartStudyAll = {
      if (!dashboardActionLoading && !detailLoading) {
        scope.launch {
          dashboardActionLoading = true
          try {
            startSessionForAllSongs()
          } finally {
            dashboardActionLoading = false
          }
        }
      }
    },
  )

  if (showExitConfirmation) {
    LearnExitConfirmationSheet(
      willDiscardRecording = sayItBackRecording || sayItBackScoring,
      onDismiss = { showExitConfirmation = false },
      onConfirm = { closeSession() },
    )
  }
}

private fun playExerciseFeedbackSound(context: android.content.Context, isCorrect: Boolean) {
  val soundRes = if (isCorrect) R.raw.correct else R.raw.incorrect
  val player = runCatching { MediaPlayer.create(context, soundRes) }.getOrNull()
  if (player == null) {
    Log.w("LearnScreen", "Feedback sound player init failed")
    return
  }
  player.setOnCompletionListener { it.release() }
  player.setOnErrorListener { mediaPlayer, _, _ ->
    mediaPlayer.release()
    true
  }
  runCatching { player.start() }.onFailure { err ->
    Log.w("LearnScreen", "Feedback sound playback failed: ${err.message}")
    runCatching { player.release() }
  }
}

private fun scoreToGrade(score: Double?, passed: Boolean): String {
  if (!passed) return "Try again"
  if (score == null) return "Great job"
  if (score >= 0.9) return "Excellent"
  if (score >= 0.75) return "Great job"
  return "Nice try"
}

private fun consecutiveDayStreak(
  qualifiedDays: Set<LocalDate>,
  today: LocalDate,
): Int {
  if (qualifiedDays.isEmpty()) return 0
  var day = today
  var streak = 0
  while (qualifiedDays.contains(day)) {
    streak += 1
    day = day.minusDays(1)
  }
  return streak
}
