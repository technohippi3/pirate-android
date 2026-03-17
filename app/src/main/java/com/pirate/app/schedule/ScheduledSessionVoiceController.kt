package com.pirate.app.schedule

import android.content.Context
import android.util.Log
import com.pirate.app.assistant.VoiceCallState
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ScheduledSessionVoiceController(private val appContext: Context) {

  companion object {
    private const val TAG = "ScheduledSessionVoice"
    private const val AGORA_APP_ID = "3260ad15ace147c88a8bf32da798a114"
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

  private var engine: RtcEngine? = null
  private var timerJob: Job? = null
  private var activeUserAddress: String? = null

  private val eventHandler = object : IRtcEngineEventHandler() {
    override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
      Log.d(TAG, "Joined channel=$channel uid=$uid")
      scope.launch {
        _state.value = VoiceCallState.Connected
      }
    }

    override fun onUserJoined(uid: Int, elapsed: Int) {
      Log.d(TAG, "Peer joined uid=$uid")
      scope.launch { _peerConnected.value = true }
    }

    override fun onUserOffline(uid: Int, reason: Int) {
      Log.d(TAG, "Peer left uid=$uid reason=$reason")
      scope.launch { _peerConnected.value = false }
    }

    override fun onError(err: Int) {
      Log.e(TAG, "Agora error=$err")
      scope.launch {
        if (_state.value == VoiceCallState.Connecting) {
          _errorMessage.value = "Voice connection failed (code $err)"
          _state.value = VoiceCallState.Error
        }
      }
    }
  }

  fun startSession(
    bookingId: Long,
    userAddress: String,
  ) {
    if (_state.value == VoiceCallState.Connecting || _state.value == VoiceCallState.Connected) return
    if (bookingId <= 0L) return

    _state.value = VoiceCallState.Connecting
    _errorMessage.value = null
    _durationSeconds.value = 0
    _isMuted.value = false
    _peerConnected.value = false
    _activeBookingId.value = bookingId
    activeUserAddress = userAddress

    scope.launch {
      try {
        val join = SessionVoiceApi.joinSession(
          appContext = appContext,
          userAddress = userAddress,
          bookingId = bookingId,
        )

        val config = RtcEngineConfig().apply {
          mContext = appContext
          mAppId = AGORA_APP_ID
          mEventHandler = eventHandler
        }

        val rtc = RtcEngine.create(config)
        rtc.enableAudioVolumeIndication(200, 3, true)
        rtc.setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
        rtc.setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
        engine = rtc

        val options = ChannelMediaOptions().apply {
          autoSubscribeAudio = true
          publishMicrophoneTrack = true
        }
        val joinResult = rtc.joinChannel(join.agoraToken, join.channel, join.userUid, options)
        if (joinResult != 0) {
          throw IllegalStateException("joinChannel returned $joinResult")
        }

        timerJob = scope.launch {
          while (true) {
            delay(1_000)
            _durationSeconds.value++
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "startSession failed", e)
        _errorMessage.value = e.message ?: "Failed to join session."
        _state.value = VoiceCallState.Error
        cleanupRtc()
      }
    }
  }

  fun endCall() {
    val bookingId = _activeBookingId.value
    val userAddress = activeUserAddress

    cleanupRtc()
    _state.value = VoiceCallState.Idle
    _errorMessage.value = null
    _activeBookingId.value = null
    activeUserAddress = null

    if (bookingId != null && !userAddress.isNullOrBlank()) {
      scope.launch {
        runCatching {
          SessionVoiceApi.leaveSession(
            appContext = appContext,
            userAddress = userAddress,
            bookingId = bookingId,
          )
        }.onFailure { err ->
          Log.w(TAG, "leaveSession failed for booking=$bookingId: ${err.message}")
        }
      }
    }
  }

  fun toggleMute() {
    val nextMuted = !_isMuted.value
    _isMuted.value = nextMuted
    engine?.muteLocalAudioStream(nextMuted)
  }

  fun release() {
    cleanupRtc()
    _state.value = VoiceCallState.Idle
    _activeBookingId.value = null
    activeUserAddress = null
  }

  private fun cleanupRtc() {
    timerJob?.cancel()
    timerJob = null
    _peerConnected.value = false
    try {
      engine?.leaveChannel()
      engine?.let { RtcEngine.destroy() }
    } catch (e: Exception) {
      Log.w(TAG, "cleanupRtc failed", e)
    }
    engine = null
    _isMuted.value = false
    _durationSeconds.value = 0
  }
}
