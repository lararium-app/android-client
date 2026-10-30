package app.lararium.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import app.lararium.AssistantApplication
import app.lararium.MainActivity
import app.lararium.R
import app.lararium.audio.AudioCaptureManager
import app.lararium.audio.SpeechRecognizerManager
import app.lararium.audio.VoiceActivityDetector
import app.lararium.audio.VoskTranscriber
import app.lararium.hearth.ChatLane
import app.lararium.hearth.ConnectionEvent
import app.lararium.hearth.ConnectionMachine
import app.lararium.hearth.ConnectionState
import app.lararium.hearth.Endpoint
import app.lararium.hearth.HearthChatClient
import app.lararium.hearth.LarariumClient
import app.lararium.hearth.SendGate
import app.lararium.hearth.TurnRetryPolicy
import app.lararium.hearth.chatLaneFor
import app.lararium.hearth.watch.ReplyPreview
import app.lararium.hearth.watch.WatchLogic
import app.lararium.hearth.watch.WatchStore
import app.lararium.llm.ChatSession
import app.lararium.security.SecureKeyManager
import app.lararium.storage.ChatHistoryManager
import app.lararium.tts.TTSManager
import app.lararium.ui.CompactAssistantActivity
import app.lararium.ui.DrawerLogic
import app.lararium.ui.FailedSend
import app.lararium.ui.SettingsManager
import app.lararium.ui.ChatStateLogic
import app.lararium.util.LinkPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Foreground service managing the Lararium service lifecycle.
 * Hearth is the sole model provider.
 */
class AssistantService : Service() {

    private val binder = AssistantBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private lateinit var audioCaptureManager: AudioCaptureManager
    private lateinit var voskTranscriber: VoskTranscriber
    private lateinit var speechRecognizerManager: SpeechRecognizerManager
    lateinit var hearthChatClient: HearthChatClient
        private set
    val isHearthReady: Boolean get() = ::hearthChatClient.isInitialized
    lateinit var ttsManager: TTSManager
        private set
    lateinit var settingsManager: SettingsManager
        private set
    lateinit var chatHistoryManager: ChatHistoryManager
    
    private var speechRecognitionJob: Job? = null
    private var audioCaptureJob: Job? = null
    private var activeResponseJob: Job? = null

    private enum class VoiceCaptureMode { NONE, SYSTEM, VOSK }
    private var activeVoiceCaptureMode = VoiceCaptureMode.NONE

    // State flow for UI binding
    private val _assistantState = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val assistantState: StateFlow<AssistantState> = _assistantState.asStateFlow()

    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription.asStateFlow()

    private val _response = MutableStateFlow("")
    val response: StateFlow<String> = _response.asStateFlow()
    
    // Chat session messages for UI
    private val _chatMessages = MutableStateFlow<List<ChatSession.Message>>(emptyList())
    val chatMessages: StateFlow<List<ChatSession.Message>> = _chatMessages.asStateFlow()
    
    // Pending URL to open (detected from AI response)
    private val _pendingUrl = MutableStateFlow<String?>(null)
    val pendingUrl: StateFlow<String?> = _pendingUrl.asStateFlow()

    // Connection machine & state for Hearth connection monitoring (#21)
    private val connectionMachine = ConnectionMachine()
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Unknown)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    val connectionEpoch: Long
        get() = connectionMachine.epoch

    val sendGate = SendGate()
    private val _failedSend = MutableStateFlow<FailedSend?>(null)
    val failedSend: StateFlow<FailedSend?> = _failedSend.asStateFlow()

    fun clearFailedSend() {
        sendGate.succeed()
        _failedSend.value = null
        if (_assistantState.value is AssistantState.Error) {
            _assistantState.value = AssistantState.Idle
        }
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var probeJob: Job? = null
    private var endpointPrefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    
    // Currently loaded chat ID (null = new chat)
    private var _currentChatId: String? = null

    /**
     * Open URL in default browser.
     */
    fun openUrl(url: String) {
        if (!LinkPolicy.isSafeExternal(url)) {
            // H9: agent-supplied links are browser links or nothing.
            _pendingUrls.value = emptyList()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            _pendingUrls.value = emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URL: $url", e)
        }
    }
    
    // Multiple pending URLs for user selection
    private val _pendingUrls = MutableStateFlow<List<String>>(emptyList())
    val pendingUrls: StateFlow<List<String>> = _pendingUrls.asStateFlow()
    
    fun clearPendingUrls() {
        _pendingUrls.value = emptyList()
    }
    
    companion object {
        private const val TAG = "AssistantService"
        private const val NOTIFICATION_ID = 1001
        const val RESTORE_COMMIT_BUDGET_MS = 150_000L
        
        const val ACTION_ACTIVATE = "app.lararium.ACTIVATE"
        const val ACTION_START_VOICE = "app.lararium.START_VOICE"
        const val ACTION_STOP_VOICE = "app.lararium.STOP_VOICE"
        const val ACTION_QUERY_TEXT = "app.lararium.QUERY_TEXT"
        const val ACTION_STOP = "app.lararium.STOP"
        
        const val EXTRA_TRIGGER = "trigger"
        const val EXTRA_QUERY = "query"
        const val EXTRA_LAUNCH_OVERLAY = "launch_overlay"

        @Volatile
        var activeSessionId: String? = null

        @Volatile
        private var runningInstance: AssistantService? = null

        fun writeThroughReplies(sessionId: String, replies: List<ReplyPreview>) {
            val svc = runningInstance ?: return
            if (!WatchLogic.shouldWriteThrough(sessionId, activeSessionId, replies)) return
            svc.mergeWatchedReplies(sessionId, replies)
        }
    }

    inner class AssistantBinder : Binder() {
        fun getService(): AssistantService = this@AssistantService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AssistantService created")
        
        val app = application as AssistantApplication
        
        audioCaptureManager = AudioCaptureManager(this)
        voskTranscriber = VoskTranscriber(this)
        speechRecognizerManager = SpeechRecognizerManager(this)
        settingsManager = SettingsManager(this)
        chatHistoryManager = ChatHistoryManager(this)

        // Initialize hearth client
        hearthChatClient = HearthChatClient(this, app.secureKeyManager)
        runningInstance = this
        activeSessionId = hearthChatClient.getStoredSessionId()

        // Restore the chat after process death (e.g. Android reclaiming the app
        // while backgrounded): the message list lives in memory, the hearth's
        // event log is the durable source of truth.
        maybeRestoreHearthChat()

        ttsManager = TTSManager(this)

        // Apply saved TTS language preference
        val savedTtsLang = settingsManager.ttsLanguage
        ttsManager.setLanguage(savedTtsLang)
        Log.i(TAG, "TTS language set to: $savedTtsLang")
        
        // Initialize Vosk with selected language (and secondary for multilingual)
        serviceScope.launch(Dispatchers.IO) {
            val language = settingsManager.voiceLanguage
            if (!voskTranscriber.needsModelDownload(language)) {
                Log.i(TAG, "Vosk model found for $language, initializing...")
                
                // Check for multilingual mode
                if (settingsManager.multilingualEnabled) {
                    val secondaryLang = settingsManager.secondaryVoiceLanguage
                    if (!voskTranscriber.needsModelDownload(secondaryLang)) {
                        val initialized = voskTranscriber.initializeMultilingual(language, secondaryLang)
                        Log.i(TAG, "Vosk multilingual initialization ($language + $secondaryLang): $initialized")
                    } else {
                        val initialized = voskTranscriber.initialize(language)
                        Log.i(TAG, "Vosk initialization (secondary model not downloaded): $initialized")
                    }
                } else {
                    val initialized = voskTranscriber.initialize(language)
                    Log.i(TAG, "Vosk initialization result: $initialized")
                }
            } else {
                Log.i(TAG, "Vosk model not downloaded for $language")
            }
        }

        initConnectionMonitoring()

        // Pairing writes the endpoint from the UI process; the fresh
        // connection state machine in here would otherwise stay Unknown
        // (gray dot) until a turn, a network flip, or a restart. React
        // to the write directly: strong field ref (prefs listeners are
        // held weakly) so the listener is never GC'd out from under us.
        endpointPrefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == SecureKeyManager.PREF_HEARTH_ENDPOINT) {
                retryConnectionNow()
            }
        }
        (application as? AssistantApplication)?.secureKeyManager
            ?.addEndpointChangeListener(endpointPrefListener!!)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACTIVATE -> {
                startForeground(NOTIFICATION_ID, createNotification("Ready"))
                // Clear session on each activation for fresh start
                clearSession()
                if (intent.getBooleanExtra(EXTRA_LAUNCH_OVERLAY, true)) {
                    launchOverlay()
                }
            }
            ACTION_START_VOICE -> startVoiceCapture()
            ACTION_STOP_VOICE -> stopVoiceCapture()
            ACTION_QUERY_TEXT -> {
                val query = intent.getStringExtra(EXTRA_QUERY) ?: ""
                if (query.isNotBlank()) {
                    processTextQuery(query)
                }
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "AssistantService destroyed")
        if (runningInstance === this) {
            runningInstance = null
            activeSessionId = null
        }
        serviceScope.cancel()
        ttsManager.shutdown()
        voskTranscriber.release()
        speechRecognizerManager.destroy()
        networkCallback?.let {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            try { cm?.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        endpointPrefListener?.let {
            (application as? AssistantApplication)?.secureKeyManager
                ?.removeEndpointChangeListener(it)
        }
        endpointPrefListener = null
    }

    private fun launchOverlay() {
        val overlayIntent = Intent(this, CompactAssistantActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(overlayIntent)
    }

    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AssistantApplication.CHANNEL_SERVICE)
            .setContentTitle("Lararium")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_assistant)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Start voice capture based on settings (Vosk or system speech recognizer).
     */
    fun startVoiceCapture() {
        // If already listening, check if recognizer is actually active
        if (_assistantState.value is AssistantState.Listening) {
            if (activeVoiceCaptureMode == VoiceCaptureMode.SYSTEM && speechRecognizerManager.isCurrentlyListening()) {
                Log.d(TAG, "Voice capture already active and listening, ignoring request")
                return
            }
            if (activeVoiceCaptureMode == VoiceCaptureMode.VOSK && audioCaptureManager.isCapturing()) {
                Log.d(TAG, "Vosk voice capture already active, ignoring request")
                return
            }
            Log.w(TAG, "Resetting stuck listening state")
            resetVoiceCapture()
        } else {
            resetVoiceCapture()
        }
        
        _assistantState.value = AssistantState.Listening
        _transcription.value = ""
        
        val voiceMethod = settingsManager.voiceInputMethod
        val preferVosk = voiceMethod == SettingsManager.VOICE_INPUT_VOSK
        // A downloaded model may still be initialising after the service starts.
        // Capture with Vosk in that case; transcribe() will wait for initialization
        // after the recording ends instead of falling back to an online recognizer.
        val voskInstalled = !voskTranscriber.needsModelDownload(settingsManager.voiceLanguage)
        val systemAvailable = speechRecognizerManager.isAvailable()
        
        when {
            // Vosk preferred and ready
            preferVosk && voskInstalled -> {
                Log.i(TAG, "Using Vosk transcription (preferred)")
                startVoskCapture()
            }
            // System preferred and available
            !preferVosk && systemAvailable -> {
                Log.i(TAG, "Using system speech recognition (preferred)")
                startSystemSpeechRecognition()
            }
            // Vosk preferred but not ready, try system as fallback
            preferVosk && !voskInstalled && systemAvailable -> {
                Log.i(TAG, "Using system speech recognition (Vosk not ready, fallback)")
                startSystemSpeechRecognition()
            }
            // Nothing available
            else -> {
                val message = when {
                    preferVosk && !voskInstalled -> "Please download Vosk model in Settings for offline voice input."
                    !preferVosk && !systemAvailable -> systemRecognitionUnavailableMessage()
                    else -> "Voice recognition unavailable. Please check Settings."
                }
                _transcription.value = ""
                _response.value = message
                _assistantState.value = AssistantState.Error("No voice input available")
            }
        }
    }
    
    private fun resetVoiceCapture() {
        speechRecognitionJob?.cancel()
        speechRecognitionJob = null
        audioCaptureJob?.cancel()
        audioCaptureJob = null
        speechRecognizerManager.stopListening()
        audioCaptureManager.cancelCapture()
        activeVoiceCaptureMode = VoiceCaptureMode.NONE
    }

    private fun startSystemSpeechRecognition() {
        Log.i(TAG, "Starting system speech recognition")
        activeVoiceCaptureMode = VoiceCaptureMode.SYSTEM
        
        speechRecognitionJob = serviceScope.launch(Dispatchers.Main) {
            try {
                val partialResult = StringBuilder()
                var shouldSwitchToVosk = false
                
                speechRecognizerManager.startListening()
                    .catch { e ->
                        Log.e(TAG, "System speech recognition error", e)
                        _transcription.value = ""
                        _response.value = "Voice recognition error: ${e.message}"
                        _assistantState.value = AssistantState.Error(e.message ?: "Recognition failed")
                    }
                    .collect { result ->
                        when (result) {
                            SpeechRecognizerManager.RecognitionResult.ReadyForSpeech -> {
                                Log.d(TAG, "Ready for speech")
                            }
                            SpeechRecognizerManager.RecognitionResult.EndOfSpeech -> {
                                Log.d(TAG, "End of speech")
                            }
                            is SpeechRecognizerManager.RecognitionResult.Partial -> {
                                partialResult.clear()
                                partialResult.append(result.text)
                                _transcription.value = result.text
                            }
                            is SpeechRecognizerManager.RecognitionResult.Final -> {
                                _transcription.value = result.text
                                activeVoiceCaptureMode = VoiceCaptureMode.NONE
                                
                                if (settingsManager.autoSendVoice && result.text.isNotBlank()) {
                                    _assistantState.value = AssistantState.Processing
                                    serviceScope.launch(Dispatchers.IO) {
                                        processVoiceQuery(result.text)
                                    }
                                } else if (result.text.isBlank()) {
                                    _assistantState.value = AssistantState.Idle
                                }
                            }
                            is SpeechRecognizerManager.RecognitionResult.Error -> {
                                Log.e(TAG, "System recognizer error: ${result.message} (code: ${result.code})")
                                
                                val usingSystemMethod = settingsManager.voiceInputMethod == SettingsManager.VOICE_INPUT_SYSTEM
                                val voskInstalled = !voskTranscriber.needsModelDownload(settingsManager.voiceLanguage)
                                
                                if (usingSystemMethod && voskInstalled) {
                                    Log.i(TAG, "System recognizer failed, switching to Vosk fallback")
                                    shouldSwitchToVosk = true
                                } else {
                                    _transcription.value = ""
                                    val errorMessage = if (usingSystemMethod) {
                                        systemRecognitionUnavailableMessage()
                                    } else {
                                        result.message
                                    }
                                    _response.value = errorMessage
                                    _assistantState.value = AssistantState.Error(result.message)
                                }
                            }
                        }
                    }
                
                if (shouldSwitchToVosk) {
                    resetVoiceCapture()
                    _assistantState.value = AssistantState.Listening
                    _transcription.value = ""
                    Log.i(TAG, "Falling back to Vosk after system recognizer failure")
                    startVoskCapture()
                }
            } catch (e: Exception) {
                Log.e(TAG, "System recognition job error", e)
                val usingSystemMethod = settingsManager.voiceInputMethod == SettingsManager.VOICE_INPUT_SYSTEM
                if (usingSystemMethod) {
                    if (!voskTranscriber.needsModelDownload(settingsManager.voiceLanguage)) {
                        Log.i(TAG, "System recognizer crashed, attempting Vosk fallback")
                        resetVoiceCapture()
                        _assistantState.value = AssistantState.Listening
                        _transcription.value = ""
                        startVoskCapture()
                        return@launch
                    }
                }
                _transcription.value = ""
                _response.value = "Voice recognition failed: ${e.message}"
                _assistantState.value = AssistantState.Error(e.message ?: "Recognition failed")
            }
        }
    }

    private fun systemRecognitionUnavailableMessage(): String {
        return "No system speech recognition service found.\n\n" +
            "Some AOSP-based ROMs do not include a speech recognition service by default.\n\n" +
            "To use voice input:\n" +
            "1. Switch to Vosk (Offline) in Settings → Voice Input\n" +
            "2. Download the language model for offline use\n" +
            "3. Or install a speech recognition app"
    }

    private fun startVoskCapture() {
        Log.i(TAG, "Starting Vosk voice capture")
        activeVoiceCaptureMode = VoiceCaptureMode.VOSK
        startAudioCaptureJob("Vosk")
    }

    private fun startAudioCaptureJob(label: String) {
        audioCaptureJob?.cancel()
        audioCaptureJob = serviceScope.launch(Dispatchers.IO) {
            val voiceActivityDetector = if (settingsManager.autoSendVoice) VoiceActivityDetector() else null
            var autoStopRequested = false
            try {
                audioCaptureManager.startCapture()
                    .collect { chunk ->
                        if (!autoStopRequested && voiceActivityDetector?.addPcmChunk(chunk) == true) {
                            autoStopRequested = true
                            Log.i(TAG, "$label detected end of speech; sending automatically")
                            serviceScope.launch(Dispatchers.Main) {
                                stopVoiceCapture()
                            }
                        }
                    }
            } catch (e: CancellationException) {
                Log.d(TAG, "$label audio capture cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "$label audio capture error", e)
                if (_assistantState.value is AssistantState.Listening) {
                    _assistantState.value = AssistantState.Error(e.message ?: "Audio capture failed")
                }
            } finally {
                if (audioCaptureJob === coroutineContext[Job]) {
                    audioCaptureJob = null
                }
            }
        }
    }

    /**
     * Stop voice capture and process transcription.
     */
    fun stopVoiceCapture() {
        if (_assistantState.value != AssistantState.Listening) return

        val captureMode = activeVoiceCaptureMode

        speechRecognitionJob?.cancel()
        speechRecognitionJob = null
        speechRecognizerManager.stopListening()

        if (captureMode == VoiceCaptureMode.SYSTEM) {
            activeVoiceCaptureMode = VoiceCaptureMode.NONE
            if (!settingsManager.autoSendVoice && _transcription.value.isNotBlank()) {
                _assistantState.value = AssistantState.Processing
                serviceScope.launch(Dispatchers.IO) {
                    processVoiceQuery(_transcription.value)
                }
            } else if (_transcription.value.isBlank()) {
                _assistantState.value = AssistantState.Idle
            }
            return
        }

        if (captureMode != VoiceCaptureMode.VOSK) {
            audioCaptureJob?.cancel()
            audioCaptureJob = null
            audioCaptureManager.cancelCapture()
            activeVoiceCaptureMode = VoiceCaptureMode.NONE
            _assistantState.value = AssistantState.Idle
            return
        }

        _assistantState.value = AssistantState.Processing

        audioCaptureJob?.cancel()
        audioCaptureJob = null

        serviceScope.launch(Dispatchers.IO) {
            try {
                val audioFile = if (audioCaptureManager.isCapturing()) {
                    audioCaptureManager.stopCapture()
                } else {
                    Log.w(TAG, "No active audio capture on stop, skipping transcription")
                    activeVoiceCaptureMode = VoiceCaptureMode.NONE
                    _assistantState.value = AssistantState.Idle
                    return@launch
                }
                activeVoiceCaptureMode = VoiceCaptureMode.NONE
                
                val transcribedText = voskTranscriber.transcribe(audioFile)
                
                // Don't send error messages to LLM
                if (transcribedText.startsWith("[") && transcribedText.endsWith("]")) {
                    _transcription.value = ""
                    _response.value = transcribedText.removeSurrounding("[", "]")
                    _assistantState.value = AssistantState.Complete
                    return@launch
                }
                
                _transcription.value = transcribedText
                processVoiceQuery(transcribedText)
            } catch (e: Exception) {
                Log.e(TAG, "Transcription error", e)
                _response.value = "Voice input error: ${e.message}"
                _assistantState.value = AssistantState.Error(e.message ?: "Transcription failed")
            }
        }
    }

    /**
     * Process a text query directly (text input mode).
     * Immediately adds user message to chat before AI starts responding.
     */
    fun processTextQuery(query: String, imageBase64: String? = null) {
        val effectiveQuery = query.ifBlank { 
            if (imageBase64 != null) "Describe this image" else {
                _assistantState.value = AssistantState.Idle
                return
            }
        }
        
        stopResponse()
        _assistantState.value = AssistantState.Processing
        
        Log.i(TAG, "Processing query: '$effectiveQuery'")
        
        // Immediately add user message to chat UI
        sendGate.record(effectiveQuery)
        addUserMessageToChat(effectiveQuery, imageBase64)
        
        activeResponseJob = serviceScope.launch(Dispatchers.IO) {
            processQueryInternal(effectiveQuery, imageBase64)
        }
    }
    
    /**
     * Helper to add user message to chat and update UI immediately.
     */
    private fun addUserMessageToChat(query: String, imageBase64: String? = null) {
        val existing = hearthChatClient.chatSession.getAllMessages().lastOrNull()
        if (existing?.role != "user" || existing.content != query || imageBase64 != null) {
            hearthChatClient.chatSession.addUserMessage(query, imageBase64)
        }
        _chatMessages.value = hearthChatClient.chatSession.getAllMessages()
        Log.d(TAG, "Added to Hearth session: '$query'")
    }
    
    /**
     * Process voice query - adds user message and processes.
     * Called from voice capture paths.
     */
    private suspend fun processVoiceQuery(query: String) {
        stopResponse()
        withContext(Dispatchers.Main) {
            addUserMessageToChat(query, null)
        }
        processQueryInternal(query, null)
    }

    /**
     * Main query processing pipeline through the paired hearth.
     */
    private suspend fun processQueryInternal(query: String, imageBase64: String? = null) {
        try {
            val sanitizedQuery = sanitizeQuery(query)
            val app = application as AssistantApplication
            val endpoint = app.secureKeyManager.getHearthEndpoint()

            when (chatLaneFor(endpoint != null)) {
                ChatLane.NotPaired -> {
                    val notPairedError = "Not paired with your hearth yet — use Pair hearth in Settings"
                    _response.value = notPairedError
                    _assistantState.value = AssistantState.Error(notPairedError)
                    return
                }
                ChatLane.Ready -> {
                    streamHearth(sanitizedQuery)
                }
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Query processing cancelled")
            if (_assistantState.value !is AssistantState.Complete) {
                _assistantState.value = AssistantState.Idle
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Query processing error", e)
            _assistantState.value = AssistantState.Error(e.message ?: "Processing failed")
        }
    }

    /**
     * Direct streaming turn through the paired hearth.
     * Wraps the send path with [TurnRetryPolicy]: retries once on transient failure
     * (after checking the session event log first so a delivered user message waits for
     * commit instead of resending) and enforces a 45 s first-token watchdog.
     */
    private suspend fun streamHearth(query: String) {
        _response.value = ""
        _assistantState.value = AssistantState.Responding
        activeSessionId = hearthChatClient.lastActiveSessionId()

        val policy = TurnRetryPolicy()
        var lastErrorMsg: String? = null

        while (true) {
            policy.onSendStart()
            val fullResponse = StringBuilder()
            val epochAtSend = connectionMachine.epoch
            var receivedAnyToken = false
            var doneText: String? = null
            var attemptError: String? = null
            var watchdogFired = false

            try {
                coroutineScope {
                    val streamJob = launch {
                        hearthChatClient.streamChat(
                            message = query,
                            onTurnEvent = {
                                activeSessionId = hearthChatClient.lastActiveSessionId()
                                if (!connectionMachine.isStale(epochAtSend)) {
                                    dispatchConnectionEvent(ConnectionEvent.TurnEvent)
                                }
                            },
                            onDelta = { chunk ->
                                if (connectionMachine.isStale(epochAtSend)) {
                                    Log.w(TAG, "Ignoring late delta on stale epoch")
                                    return@streamChat
                                }
                                receivedAnyToken = true
                                policy.onFirstToken()
                                fullResponse.append(chunk)
                                _response.value = fullResponse.toString()
                            },
                            onDone = { accumulated ->
                                if (connectionMachine.isStale(epochAtSend)) {
                                    Log.w(TAG, "Ignoring late onDone on stale epoch")
                                    return@streamChat
                                }
                                if (accumulated.isNotEmpty() && accumulated != fullResponse.toString()) {
                                    fullResponse.setLength(0)
                                    fullResponse.append(accumulated)
                                    receivedAnyToken = true
                                    policy.onFirstToken()
                                }
                                doneText = fullResponse.toString()
                                // The user just watched this reply arrive live in the
                                // app: advance the watcher's cursor past the committed
                                // msg event (turn_done carries its seq) so the periodic
                                // watcher does not re-notify a reply already read.
                                val seenSession = hearthChatClient.lastActiveSessionId()
                                val seenSeq = hearthChatClient.lastTurnSeq
                                if (seenSession != null && seenSeq != null) {
                                    WatchStore(applicationContext).markSeen(seenSession, seenSeq)
                                }
                            },
                            onError = { friendlyError ->
                                if (connectionMachine.isStale(epochAtSend)) {
                                    Log.w(TAG, "Ignoring late onError on stale epoch")
                                    return@streamChat
                                }
                                Log.e(TAG, "Hearth streaming error: $friendlyError")
                                attemptError = friendlyError
                                val code = if (friendlyError.contains("authentication", ignoreCase = true) ||
                                    friendlyError.contains("unauthorized", ignoreCase = true) ||
                                    friendlyError.contains("401") || friendlyError.contains("403")) 401 else null
                                dispatchConnectionEvent(ConnectionEvent.ProbeFailure(code))
                            }
                        )
                    }

                    val watchdogJob = launch {
                        delay(policy.watchdogTimeoutMs)
                        if (!receivedAnyToken && doneText == null) {
                            watchdogFired = true
                            streamJob.cancel(CancellationException(HearthChatClient.WATCHDOG_CANCEL_MESSAGE))
                        }
                    }

                    try {
                        streamJob.join()
                    } finally {
                        watchdogJob.cancel()
                    }
                }
            } catch (e: CancellationException) {
                if (!watchdogFired && e.message != HearthChatClient.WATCHDOG_CANCEL_MESSAGE) {
                    Log.i(TAG, "Hearth streaming cancelled")
                    hearthChatClient.cancel()
                    if (_assistantState.value !is AssistantState.Complete) {
                        _assistantState.value = AssistantState.Idle
                    }
                    throw e
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in streamHearth", e)
                val code = extractStatusCode(e)
                dispatchConnectionEvent(ConnectionEvent.ProbeFailure(code))
                attemptError = HearthChatClient.friendlyErrorMessage(e.message ?: "")
            }

            activeSessionId = hearthChatClient.lastActiveSessionId()
            val isStaleEpoch = connectionMachine.isStale(epochAtSend)

            val stepAction: TurnRetryPolicy.Action = when {
                watchdogFired -> {
                    val rawEvents = hearthChatClient.replayStoredSession()
                    policy.onWatchdogTick(policy.watchdogTimeoutMs, rawEvents, query)
                }
                isStaleEpoch -> {
                    policy.onFailure(attemptError)
                }
                attemptError != null -> {
                    lastErrorMsg = attemptError
                    policy.onFailure(attemptError)
                }
                doneText != null && fullResponse.isNotEmpty() -> {
                    policy.onTurnDone(fullResponse.toString())
                }
                !receivedAnyToken -> {
                    dispatchConnectionEvent(ConnectionEvent.ProbeFailure(null))
                    policy.onZeroTokenClose()
                }
                else -> {
                    policy.onTurnDone(fullResponse.toString())
                }
            }

            when (val resolved = resolveRetryStep(policy, stepAction, query, lastErrorMsg)) {
                is TurnRetryPolicy.Action.Complete -> {
                    finalizeResponse(resolved.responseText, emptyList())
                    sendGate.succeed()
                    _failedSend.value = null
                    return
                }
                is TurnRetryPolicy.Action.Resend -> {
                    _response.value = ""
                    _assistantState.value = AssistantState.Responding
                    continue
                }
                is TurnRetryPolicy.Action.Fail -> {
                    if (!receivedAnyToken || isStaleEpoch) {
                        _failedSend.value = sendGate.fail(query)
                        _response.value = ""
                        val err = resolved.reason ?: lastErrorMsg
                        _assistantState.value = if (err != null && !isStaleEpoch && attemptError != null) {
                            AssistantState.Error(err)
                        } else {
                            AssistantState.Idle
                        }
                    } else {
                        val err = resolved.reason ?: lastErrorMsg ?: HearthChatClient.friendlyErrorMessage("")
                        _response.value = err
                        _assistantState.value = AssistantState.Error(err)
                    }
                    return
                }
                else -> return
            }
        }
    }

    private suspend fun resolveRetryStep(
        policy: TurnRetryPolicy,
        action: TurnRetryPolicy.Action,
        query: String,
        lastErrorMsg: String?,
    ): TurnRetryPolicy.Action {
        return when (action) {
            is TurnRetryPolicy.Action.CheckEventLogBeforeRetry -> {
                if (action.delayMs > 0L) {
                    delay(action.delayMs)
                }
                val rawEvents = hearthChatClient.replayStoredSession()
                when (val next = policy.onEventLogFetched(rawEvents, query)) {
                    is TurnRetryPolicy.Action.WaitForCommit -> {
                        val sid = hearthChatClient.lastActiveSessionId()
                        val committed = if (sid != null) {
                            hearthChatClient.waitForTurnCommit(sid, next.deadlineMs, query)
                        } else null
                        if (sid != null && committed != null) {
                            val seenSeq = hearthChatClient.lastTurnSeq
                            if (seenSeq != null) {
                                WatchStore(applicationContext).markSeen(sid, seenSeq)
                            }
                        }
                        policy.onCommitWaitResult(committed, lastErrorMsg)
                    }
                    else -> next
                }
            }
            else -> action
        }
    }

    /**
     * Common post-processing after any response: UI update and TTS.
     */
    private suspend fun finalizeResponse(
        responseText: String,
        sources: List<String> = emptyList()
    ) {
        val cleaned = responseText.replace(Regex("<\\|[^>]*>"), "").trim()

        if (cleaned.isEmpty()) {
            _response.value = "No response from hearth."
            _assistantState.value = AssistantState.Error("Empty response")
            return
        }

        _response.value = cleaned
        hearthChatClient.chatSession.addAssistantMessage(cleaned, seq = hearthChatClient.lastTurnSeq)

        withContext(Dispatchers.Main) {
            _chatMessages.value = hearthChatClient.chatSession.getAllMessages()
            _response.value = ""
        }

        if (settingsManager.ttsEnabled) {
            _assistantState.value = AssistantState.Speaking
            ttsManager.speakAndWait(cleaned)
        }

        _assistantState.value = AssistantState.Complete
    }

    /**
     * Clear chat session and start fresh.
     * Saves current chat to history if it has messages.
     */
    fun clearSession(saveToHistory: Boolean = true) {
        if (saveToHistory) {
            val messages = _chatMessages.value
            if (messages.size >= 2) { // At least one exchange
                if (_currentChatId != null) {
                    chatHistoryManager.updateChat(_currentChatId!!, messages)
                } else {
                    chatHistoryManager.saveChat(messages)
                }
            }
        }
        
        _currentChatId = null
        activeSessionId = null
        
        if (::hearthChatClient.isInitialized) {
            hearthChatClient.clearSession()
        }
        _chatMessages.value = emptyList()
        _response.value = ""
        _transcription.value = ""
        _pendingUrls.value = emptyList()
        _assistantState.value = AssistantState.Idle
        Log.i(TAG, "Chat session cleared")
    }
    
    /**
     * Load a chat from history.
     */
    fun loadChatFromHistory(chatId: String) {
        val messages = chatHistoryManager.loadChat(chatId) ?: return
        
        clearSession(saveToHistory = false)
        _currentChatId = chatId
        
        val session = hearthChatClient.chatSession
        messages.forEach { msg ->
            when (msg.role) {
                "user" -> session.addUserMessage(msg.content, msg.imageBase64)
                "assistant" -> session.addAssistantMessage(msg.content)
            }
        }
        
        _chatMessages.value = session.getAllMessages()
        Log.i(TAG, "Loaded chat $chatId with ${messages.size} messages (continuing existing)")
    }

    /**
     * Load an existing session from the paired hearth into active session.
     */
    fun loadHearthSession(sessionId: String, messages: List<ChatSession.Message>) {
        _currentChatId = null
        activeSessionId = sessionId
        if (::hearthChatClient.isInitialized) {
            val isSameSession = hearthChatClient.getStoredSessionId() == sessionId
            if (isSameSession) {
                val current = hearthChatClient.chatSession.getAllMessages()
                val merged = DrawerLogic.mergeMessages(current, messages)
                hearthChatClient.chatSession.setMessages(merged)
            } else {
                hearthChatClient.clearSession()
                hearthChatClient.setStoredSessionId(sessionId)
                hearthChatClient.chatSession.setMessages(messages)
            }
            _chatMessages.value = hearthChatClient.chatSession.getAllMessages()
            if (!isSameSession || _chatMessages.value.lastOrNull()?.role == "assistant") {
                clearFailedSend()
            }
        } else {
            getSharedPreferences("assistant_settings", MODE_PRIVATE)
                .edit()
                .putString(HearthChatClient.KEY_SESSION_ID, sessionId)
                .apply()
            _chatMessages.value = messages
        }
        _response.value = ""
        Log.i(TAG, "Loaded hearth session $sessionId with ${messages.size} messages")
    }

    internal fun mergeWatchedReplies(sessionId: String, replies: List<ReplyPreview>) {
        if (!::hearthChatClient.isInitialized) return
        if (activeSessionId != sessionId) return
        val incoming = replies
            .filter { it.text.isNotBlank() }
            .map { ChatSession.Message(role = "assistant", content = it.text, seq = it.seq) }
        if (incoming.isEmpty()) return

        serviceScope.launch(Dispatchers.Main.immediate) {
            if (activeSessionId != sessionId) return@launch
            val current = hearthChatClient.chatSession.getAllMessages()
            val merged = DrawerLogic.mergeMessages(current, incoming)
            hearthChatClient.chatSession.setMessages(merged)
            _chatMessages.value = hearthChatClient.chatSession.getAllMessages()
            _response.value = ""
            clearFailedSend()
            if (ChatStateLogic.isInFlight(_assistantState.value, _response.value)) {
                _assistantState.value = AssistantState.Complete
            }
        }
    }

    /**
     * Cold-start restore: after the process was killed (e.g. Android reclaiming
     * the app while backgrounded), rehydrate the active chat from the stored
     * session's event log. No-op when unpaired, without a stored session, or
     * when messages have already arrived.
     *
     * When the replay indicates `in_flight = true`, keeps the pending/typing UI
     * state, suppresses `failedSend`, awaits turn commit with ~150 s budget via
     * `waitForTurnCommit`, and merges the committed reply via [DrawerLogic.mergeMessages].
     */
    private fun maybeRestoreHearthChat() {
        serviceScope.launch(Dispatchers.IO) {
            val sid = HearthChatClient.getStoredSessionId(this@AssistantService) ?: return@launch
            if (!ChatStateLogic.shouldRehydrate(_chatMessages.value, _assistantState.value, _response.value)) return@launch
            val raw = hearthChatClient.replayStoredSession() ?: return@launch
            val replay = DrawerLogic.parseSessionReplay(raw)
            val restored = replay.messages
            if ((restored.isNotEmpty() || replay.inFlight) &&
                ChatStateLogic.shouldRehydrate(_chatMessages.value, _assistantState.value, _response.value)
            ) {
                loadHearthSession(sid, restored)
                Log.i(TAG, "Restored ${restored.size} messages from hearth event log (inFlight=${replay.inFlight})")
                if (replay.inFlight) {
                    clearFailedSend()
                    _response.value = ""
                    _assistantState.value = AssistantState.Processing
                    activeResponseJob?.cancel()
                    activeResponseJob = serviceScope.launch(Dispatchers.IO) {
                        val lastUserText = restored.lastOrNull { it.role == "user" }?.content
                        val committed = hearthChatClient.waitForTurnCommit(
                            sessionId = sid,
                            deadlineMs = RESTORE_COMMIT_BUDGET_MS,
                            sentText = lastUserText
                        )
                        if (committed != null) {
                            val seq = hearthChatClient.lastTurnSeq
                            val incoming = listOf(
                                ChatSession.Message(role = "assistant", content = committed, seq = seq)
                            )
                            val merged = DrawerLogic.mergeMessages(
                                hearthChatClient.chatSession.getAllMessages(),
                                incoming
                            )
                            hearthChatClient.chatSession.setMessages(merged)
                            if (seq != null) {
                                WatchStore(applicationContext).markSeen(sid, seq)
                            }
                            withContext(Dispatchers.Main) {
                                _chatMessages.value = hearthChatClient.chatSession.getAllMessages()
                                _response.value = ""
                                sendGate.succeed()
                                _failedSend.value = null
                                _assistantState.value = AssistantState.Complete
                            }
                        } else {
                            val alreadyMerged = hearthChatClient.chatSession
                                .getAllMessages()
                                .lastOrNull()?.role == "assistant"
                            withContext(Dispatchers.Main) {
                                _response.value = ""
                                if (alreadyMerged) {
                                    sendGate.succeed()
                                    _failedSend.value = null
                                    _assistantState.value = AssistantState.Complete
                                } else {
                                    _failedSend.value = sendGate.fail(lastUserText ?: "")
                                    _assistantState.value = AssistantState.Error(HearthChatClient.STILL_ANSWERING_HINT)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Reload settings - call this when settings are changed.
     */
    fun reloadSettings() {
        Log.i(TAG, "Reloading settings...")
        
        // Reload voice language
        serviceScope.launch(Dispatchers.IO) {
            val language = settingsManager.voiceLanguage
            val currentLang = voskTranscriber.getCurrentLanguage()
            
            if (currentLang != language) {
                Log.i(TAG, "Switching voice language from $currentLang to $language")
                
                if (!voskTranscriber.needsModelDownload(language)) {
                    if (settingsManager.multilingualEnabled) {
                        val secondaryLang = settingsManager.secondaryVoiceLanguage
                        if (!voskTranscriber.needsModelDownload(secondaryLang)) {
                            voskTranscriber.initializeMultilingual(language, secondaryLang)
                        } else {
                            voskTranscriber.initialize(language)
                        }
                    } else {
                        voskTranscriber.initialize(language)
                    }
                } else {
                    Log.w(TAG, "Cannot switch to $language - model not downloaded")
                }
            }
        }

        // Reload TTS language
        val savedTtsLang = settingsManager.ttsLanguage
        if (ttsManager.getCurrentLanguageTag() != savedTtsLang) {
            Log.i(TAG, "Switching TTS language to: $savedTtsLang")
            ttsManager.setLanguage(savedTtsLang)
        }

        // Reload hearth client with updated settings
        val secureKeys = (application as AssistantApplication).secureKeyManager
        hearthChatClient = HearthChatClient(this, secureKeys)
        activeSessionId = hearthChatClient.getStoredSessionId()

        if (isMonitoringEligible()) {
            retryConnectionNow()
        } else {
            _connectionState.value = ConnectionState.Unknown
        }
    }

    /**
     * Remove device identifiers and PII from query before sending.
     */
    private fun sanitizeQuery(query: String): String {
        var sanitized = query
        sanitized = sanitized.replace(
            Regex("(?i)(device\\s*id|android\\s*id|id\\s*is)\\s*[:=]?\\s*\\b[a-f0-9]{16}\\b"),
            "$1 [ID]"
        )
        sanitized = sanitized.replace(Regex("\\+?\\d{10,15}"), "[PHONE]")
        sanitized = sanitized.replace(
            Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),
            "[EMAIL]"
        )
        return sanitized.trim()
    }

    fun cancelOperation() {
        stopResponse()
        resetVoiceCapture()
        ttsManager.stop()
        if (::hearthChatClient.isInitialized) {
            hearthChatClient.cancel()
        }
        _assistantState.value = AssistantState.Idle
    }

    fun stopResponse() {
        val currentState = _assistantState.value
        val wasSpeakingOnly = currentState is AssistantState.Speaking
        val wasActive = currentState is AssistantState.Processing ||
            currentState is AssistantState.Searching ||
            currentState is AssistantState.Responding ||
            wasSpeakingOnly

        activeResponseJob?.cancel()
        activeResponseJob = null
        ttsManager.stop()
        if (::hearthChatClient.isInitialized) {
            hearthChatClient.cancel()
        }

        if (wasSpeakingOnly) {
            // TTS was in progress — stop speech silently, don't modify the response
            _assistantState.value = AssistantState.Complete
            return
        }

        val hadPartialResponse = wasActive && _response.value.isNotBlank()
        if (hadPartialResponse) {
            val partial = _response.value.trim() + "\n\n[Stopped]"
            if (::hearthChatClient.isInitialized) {
                hearthChatClient.chatSession.addAssistantMessage(partial)
                _chatMessages.value = hearthChatClient.chatSession.getAllMessages()
            }
            _response.value = ""
        }

        if (wasActive) {
            _assistantState.value = if (hadPartialResponse) AssistantState.Complete else AssistantState.Idle
        }
    }

    private fun isMonitoringEligible(): Boolean {
        val app = application as? AssistantApplication ?: return false
        val endpoint = app.secureKeyManager.getHearthEndpoint()
        return endpoint != null
    }

    fun retryConnectionNow() {
        if (!isMonitoringEligible()) {
            _connectionState.value = ConnectionState.Unknown
            return
        }
        dispatchConnectionEvent(ConnectionEvent.ManualRetry)
    }

    private fun initConnectionMonitoring() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (isMonitoringEligible()) {
                    dispatchConnectionEvent(ConnectionEvent.NetworkAvailable)
                }
            }

            override fun onLost(network: Network) {
                if (isMonitoringEligible()) {
                    dispatchConnectionEvent(ConnectionEvent.NetworkLost)
                }
            }
        }
        try {
            cm?.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }

        if (isMonitoringEligible()) {
            retryConnectionNow()
        }
    }

    private fun dispatchConnectionEvent(event: ConnectionEvent) {
        val transition = connectionMachine.process(event)
        _connectionState.value = transition.state
        probeJob?.cancel()
        if (transition.nextProbeDelayMs != null) {
            probeJob = serviceScope.launch(Dispatchers.IO) {
                if (transition.nextProbeDelayMs > 0) {
                    delay(transition.nextProbeDelayMs)
                }
                runProbe()
            }
        }
    }

    private suspend fun runProbe() {
        if (!isMonitoringEligible()) return
        val app = application as? AssistantApplication ?: return
        val epTriple = app.secureKeyManager.getHearthEndpoint() ?: return
        val ep = Endpoint(epTriple.first, epTriple.second, epTriple.third)

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNet = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNet)
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        if (!hasInternet) {
            dispatchConnectionEvent(ConnectionEvent.NetworkLost)
            return
        }

        try {
            withContext(Dispatchers.IO) {
                withTimeout(5_000L) {
                    val client = LarariumClient(ep)
                    client.health()
                }
            }
            dispatchConnectionEvent(ConnectionEvent.ProbeSuccess)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val stillHasInternet = cm?.getNetworkCapabilities(cm.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            if (!stillHasInternet) {
                dispatchConnectionEvent(ConnectionEvent.NetworkLost)
            } else {
                val code = extractStatusCode(e)
                dispatchConnectionEvent(ConnectionEvent.ProbeFailure(code))
            }
        }
    }

    private fun extractStatusCode(e: Throwable): Int? {
        val msg = e.message ?: return null
        return when {
            msg.contains("401") -> 401
            msg.contains("403") -> 403
            else -> Regex("""\b(4\d\d|5\d\d)\b""").find(msg)?.value?.toIntOrNull()
        }
    }
}

sealed class AssistantState {
    object Idle : AssistantState()
    object Listening : AssistantState()
    object Processing : AssistantState()
    object Searching : AssistantState()
    object Responding : AssistantState()
    object Speaking : AssistantState()
    object Complete : AssistantState()
    data class Error(val message: String) : AssistantState()
}
