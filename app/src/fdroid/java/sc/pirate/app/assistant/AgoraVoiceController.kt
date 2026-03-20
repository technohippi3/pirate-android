package sc.pirate.app.assistant

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val FDROID_UNAVAILABLE_MESSAGE =
  "Voice calling is unavailable in the F-Droid build."

class AgoraVoiceController(@Suppress("UNUSED_PARAMETER") private val appContext: Context) {

  private val _state = MutableStateFlow(VoiceCallState.Idle)
  val state: StateFlow<VoiceCallState> = _state

  private val _isMuted = MutableStateFlow(false)
  val isMuted: StateFlow<Boolean> = _isMuted

  private val _durationSeconds = MutableStateFlow(0)
  val durationSeconds: StateFlow<Int> = _durationSeconds

  private val _isBotSpeaking = MutableStateFlow(false)
  val isBotSpeaking: StateFlow<Boolean> = _isBotSpeaking

  private val _errorMessage = MutableStateFlow<String?>(null)
  val errorMessage: StateFlow<String?> = _errorMessage

  private val _quota = MutableStateFlow<AssistantQuotaStatus?>(null)
  val quota: StateFlow<AssistantQuotaStatus?> = _quota

  fun startCall(userAddress: String) {
    if (userAddress.isBlank()) return
    _isMuted.value = false
    _durationSeconds.value = 0
    _isBotSpeaking.value = false
    _errorMessage.value = FDROID_UNAVAILABLE_MESSAGE
    _state.value = VoiceCallState.Error
  }

  fun endCall() {
    _state.value = VoiceCallState.Idle
    _errorMessage.value = null
    _isMuted.value = false
    _durationSeconds.value = 0
    _isBotSpeaking.value = false
  }

  fun toggleMute() {
    _isMuted.value = !_isMuted.value
  }

  fun release() {
    endCall()
    _quota.value = null
  }
}
