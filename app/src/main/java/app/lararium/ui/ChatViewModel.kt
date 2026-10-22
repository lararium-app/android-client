package app.lararium.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lararium.hearth.ConnectionState
import app.lararium.llm.ChatSession
import app.lararium.service.AssistantService
import app.lararium.service.AssistantState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process/configuration-change-stable state holder for the chat interface.
 *
 * Survives Activity recreation mid-turn (e.g. screen rotation) by maintaining
 * the service connection via the application context and keeping the current
 * message history, partial reply tokens, and assistant state in memory.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _assistantService = MutableStateFlow<AssistantService?>(null)
    val assistantService: StateFlow<AssistantService?> = _assistantService.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatSession.Message>>(emptyList())
    val chatMessages: StateFlow<List<ChatSession.Message>> = _chatMessages.asStateFlow()

    private val _assistantState = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val assistantState: StateFlow<AssistantState> = _assistantState.asStateFlow()

    private val _streamingResponse = MutableStateFlow("")
    val streamingResponse: StateFlow<String> = _streamingResponse.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Unknown)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _failedSend = MutableStateFlow<FailedSend?>(null)
    val failedSend: StateFlow<FailedSend?> = _failedSend.asStateFlow()

    private var serviceCollectorsJob: Job? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? AssistantService.AssistantBinder
            val svc = localBinder?.getService()
            _assistantService.value = svc
            isBound = true
            svc?.let { attachToService(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _assistantService.value = null
            _connectionState.value = ConnectionState.Unknown
            _failedSend.value = null
            isBound = false
            serviceCollectorsJob?.cancel()
            serviceCollectorsJob = null
        }
    }

    init {
        bindAssistantService()
    }

    private fun bindAssistantService() {
        val app = getApplication<Application>()
        val intent = Intent(app, AssistantService::class.java)
        // Keep service in started state so unbinding during teardown does not cancel in-flight work
        try {
            app.startService(intent)
        } catch (_: Exception) {}
        app.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun attachToService(svc: AssistantService) {
        serviceCollectorsJob?.cancel()
        // Immediately sync current service values so state is instantly available upon attach
        _chatMessages.value = svc.chatMessages.value
        _assistantState.value = svc.assistantState.value
        _streamingResponse.value = svc.response.value
        _connectionState.value = svc.connectionState.value
        _failedSend.value = svc.failedSend.value

        serviceCollectorsJob = viewModelScope.launch {
            launch {
                svc.chatMessages.collect { messages ->
                    _chatMessages.value = messages
                }
            }
            launch {
                svc.assistantState.collect { state ->
                    _assistantState.value = state
                }
            }
            launch {
                svc.response.collect { resp ->
                    _streamingResponse.value = resp
                }
            }
            launch {
                svc.connectionState.collect { state ->
                    _connectionState.value = state
                }
            }
            launch {
                svc.failedSend.collect { failed ->
                    _failedSend.value = failed
                }
            }
        }
    }

    fun processTextQuery(query: String) {
        _assistantService.value?.processTextQuery(query)
    }

    fun stopResponse() {
        _assistantService.value?.stopResponse()
    }

    fun startVoiceCapture() {
        _assistantService.value?.startVoiceCapture()
    }

    fun stopVoiceCapture() {
        _assistantService.value?.stopVoiceCapture()
    }

    fun clearSession() {
        _chatMessages.value = emptyList()
        _streamingResponse.value = ""
        _assistantState.value = AssistantState.Idle
        _assistantService.value?.clearSession()
    }

    fun loadHearthSession(sessionId: String, messages: List<ChatSession.Message>) {
        _assistantService.value?.loadHearthSession(sessionId, messages)
    }

    fun retryConnectionNow() {
        _assistantService.value?.retryConnectionNow()
    }

    fun clearFailedSend() {
        _failedSend.value = null
        _assistantService.value?.clearFailedSend()
    }

    fun retryFailedSend() {
        val failed = _failedSend.value ?: return
        clearFailedSend()
        processTextQuery(failed.query)
    }

    override fun onCleared() {
        super.onCleared()
        serviceCollectorsJob?.cancel()
        if (isBound) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
            } catch (_: Exception) {}
            isBound = false
        }
    }
}
