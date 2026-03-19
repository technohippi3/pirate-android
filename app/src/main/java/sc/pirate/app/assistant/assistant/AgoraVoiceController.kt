package sc.pirate.app.assistant

import android.content.Context
import android.util.Log
import sc.pirate.app.BuildConfig
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
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class VoiceCallState { Idle, Connecting, Connected, Error }

internal data class AgentStartResponse(
  val sessionId: String,
  val channel: String,
  val agoraToken: String,
  val userUid: Int,
  val quotaStatus: AssistantQuotaStatus? = null,
)

class AgoraVoiceController(private val appContext: Context) {

  companion object {
    private const val TAG = "AgoraVoice"
    private const val AGORA_APP_ID = "3260ad15ace147c88a8bf32da798a114"
    private val JSON_MT = "application/json; charset=utf-8".toMediaType()
  }

  private fun requireChatWorkerUrl(): String {
    return BuildConfig.VOICE_AGENT_URL.trim().trimEnd('/').ifBlank {
      throw IllegalStateException("Missing VOICE_AGENT_URL BuildConfig field. Set gradle property VOICE_AGENT_URL.")
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

  private var engine: RtcEngine? = null
  private var sessionId: String? = null
  private var sessionBearerToken: String? = null
  private var timerJob: Job? = null
  private var botSilenceJob: Job? = null

  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

  private val eventHandler = object : IRtcEngineEventHandler() {
    override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
      Log.d(TAG, "Joined channel $channel uid=$uid")
      scope.launch { _state.value = VoiceCallState.Connected }
    }

    override fun onUserJoined(uid: Int, elapsed: Int) {
      Log.d(TAG, "Remote user joined: $uid")
    }

    override fun onUserOffline(uid: Int, reason: Int) {
      Log.d(TAG, "Remote user offline: $uid reason=$reason")
    }

    override fun onError(err: Int) {
      Log.e(TAG, "Agora error: $err")
      if (_state.value == VoiceCallState.Connecting) {
        scope.launch {
          _errorMessage.value = "Voice connection failed (code $err)"
          _state.value = VoiceCallState.Error
        }
      }
    }

    override fun onAudioVolumeIndication(
      speakers: Array<out AudioVolumeInfo>?,
      totalVolume: Int,
    ) {
      // Detect bot speaking: any remote uid with volume > 25
      val remoteSpeaking = speakers?.any { it.uid != 0 && it.volume > 25 } == true
      if (remoteSpeaking) {
        scope.launch {
          _isBotSpeaking.value = true
          // Reset silence timer
          botSilenceJob?.cancel()
          botSilenceJob = scope.launch {
            delay(600)
            _isBotSpeaking.value = false
          }
        }
      }
    }
  }

  fun startCall(
    userAddress: String,
  ) {
    if (_state.value == VoiceCallState.Connecting || _state.value == VoiceCallState.Connected) return

    _state.value = VoiceCallState.Connecting
    _errorMessage.value = null
    _durationSeconds.value = 0
    _isMuted.value = false
    _isBotSpeaking.value = false
    sessionBearerToken = null

    scope.launch {
      try {
        val chatWorkerUrl = requireChatWorkerUrl()
        // 1. Build authenticated worker session.
        val auth = getWorkerAuthSession(
          appContext = appContext,
          workerUrl = chatWorkerUrl,
          userAddress = userAddress,
        )
        sessionBearerToken = auth.token

        // 2. POST /agent/start
        val startPayload = JSONObject()
          .put("activityWallet", auth.wallet)
          .toString()
          .toRequestBody(JSON_MT)
        val startReq = Request.Builder()
          .url("$chatWorkerUrl/agent/start")
          .post(startPayload)
          .header("Authorization", "Bearer ${auth.token}")
          .build()

        val agentResp: AgentStartResponse = withContext(Dispatchers.IO) {
          httpClient.newCall(startReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
              val failure = parseAssistantApiFailure(resp.code, body)
              if (failure.quotaStatus != null) {
                _quota.value = failure.quotaStatus
              }
              if (failure.code == "insufficient_credits" || failure.code == "no_call_quota") {
                throw AssistantQuotaExceededException(
                  statusCode = failure.statusCode,
                  code = failure.code,
                  quotaStatus = failure.quotaStatus,
                  requiredCredits = failure.requiredCredits,
                  message = failure.error,
                )
              }
              throw IllegalStateException("Agent start failed (${resp.code}): ${failure.error}")
            }
            parseAgentStartResponse(body)
          }
        }

        sessionId = agentResp.sessionId
        if (agentResp.quotaStatus != null) {
          _quota.value = agentResp.quotaStatus
        }

        // 3. Create RtcEngine
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

        // 4. Join channel
        val options = ChannelMediaOptions().apply {
          autoSubscribeAudio = true
          publishMicrophoneTrack = true
        }
        val joinResult = rtc.joinChannel(agentResp.agoraToken, agentResp.channel, agentResp.userUid, options)
        if (joinResult != 0) {
          throw IllegalStateException("joinChannel returned $joinResult")
        }

        // 5. Start duration timer
        timerJob = scope.launch {
          while (true) {
            delay(1000)
            _durationSeconds.value++
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "startCall failed", e)
        _errorMessage.value = e.message ?: "Unknown error"
        _state.value = VoiceCallState.Error
        cleanup()
      }
    }
  }

  fun endCall() {
    val sid = sessionId
    val bearerToken = sessionBearerToken
    cleanup()
    _state.value = VoiceCallState.Idle

    if (sid != null && bearerToken != null) {
      // Fire-and-forget stop agent
      scope.launch {
        try {
          val stopReq = Request.Builder()
            .url("${requireChatWorkerUrl()}/agent/$sid/stop")
            .post("{}".toRequestBody(JSON_MT))
            .header("Authorization", "Bearer $bearerToken")
            .build()
          withContext(Dispatchers.IO) {
            httpClient.newCall(stopReq).execute().use { resp ->
              val body = resp.body?.string().orEmpty()
              val quotaStatus = parseAssistantQuotaStatus(body)
              if (quotaStatus != null) {
                _quota.value = quotaStatus
              }
              if (!resp.isSuccessful) {
                Log.w(TAG, "Stop agent failed (${resp.code}): $body")
              }
            }
          }
        } catch (e: Exception) {
          Log.w(TAG, "Failed to stop agent session $sid", e)
        }
      }
    } else if (sid != null) {
      Log.w(TAG, "Missing bearer token for stop call; session=$sid")
    }
  }

  fun toggleMute() {
    val muted = !_isMuted.value
    _isMuted.value = muted
    engine?.muteLocalAudioStream(muted)
  }

  fun release() {
    cleanup()
  }

  private fun cleanup() {
    timerJob?.cancel()
    timerJob = null
    botSilenceJob?.cancel()
    botSilenceJob = null
    try {
      engine?.leaveChannel()
      engine?.let { RtcEngine.destroy() }
    } catch (e: Exception) {
      Log.w(TAG, "cleanup error", e)
    }
    engine = null
    sessionId = null
    sessionBearerToken = null
    _isBotSpeaking.value = false
  }
}

internal fun parseAgentStartResponse(body: String): AgentStartResponse {
  val json = JSONObject(body)
  val quotaStatus = parseAssistantQuotaStatus(body)
  val sidVal = json.optString("session_id", "").takeIf { it.isNotBlank() }
    ?: throw IllegalStateException("Missing session_id in agent response")
  val channelVal = json.optString("channel", "").takeIf { it.isNotBlank() }
    ?: throw IllegalStateException("Missing channel in agent response")
  val tokenVal = json.optString("agora_token", "").takeIf { it.isNotBlank() }
    ?: throw IllegalStateException("Missing agora_token in agent response")
  val uidVal = json.optInt("user_uid", -1).takeIf { it >= 0 }
    ?: throw IllegalStateException("Invalid user_uid in agent response")
  return AgentStartResponse(sidVal, channelVal, tokenVal, uidVal, quotaStatus = quotaStatus)
}
