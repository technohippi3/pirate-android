package sc.pirate.app.schedule

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import sc.pirate.app.assistant.VoiceCallState

private const val FDROID_SESSION_UNAVAILABLE_MESSAGE =
  "Session voice calls are unavailable in the F-Droid build."

class ScheduledSessionVoiceController(@Suppress("UNUSED_PARAMETER") private val appContext: Context) {

  private val _state = MutableStateFlow(VoiceCallState.Idle)
  val state: StateFlow<VoiceCallState> = _state

  private val _isMuted = MutableStateFlow(false)
  val isMuted: StateFlow<Boolean> = _isMuted

  private val _durationSeconds = MutableStateFlow(0)
  val durationSeconds: StateFlow<Int> = _durationSeconds

  private val _peerConnected = MutableStateFlow(false)
  val peerConnected: StateFlow<Boolean> = _peerConnected

  private val _errorMessage = MutableStateFlow<String?>(null)
  val errorMessage: StateFlow<String?> = _errorMessage

  private val _activeBookingId = MutableStateFlow<Long?>(null)
  val activeBookingId: StateFlow<Long?> = _activeBookingId

  fun startSession(
    bookingId: Long,
    userAddress: String,
  ) {
    if (bookingId <= 0L || userAddress.isBlank()) return
    _activeBookingId.value = bookingId
    _isMuted.value = false
    _durationSeconds.value = 0
    _peerConnected.value = false
    _errorMessage.value = FDROID_SESSION_UNAVAILABLE_MESSAGE
    _state.value = VoiceCallState.Error
  }

  fun endCall() {
    _state.value = VoiceCallState.Idle
    _errorMessage.value = null
    _activeBookingId.value = null
    _isMuted.value = false
    _durationSeconds.value = 0
    _peerConnected.value = false
  }

  fun toggleMute() {
    _isMuted.value = !_isMuted.value
  }

  fun release() {
    endCall()
  }
}
