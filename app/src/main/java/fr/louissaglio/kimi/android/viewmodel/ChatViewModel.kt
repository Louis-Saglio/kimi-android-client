package fr.louissaglio.kimi.android.viewmodel

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.louissaglio.kimi.android.data.KimiApi
import fr.louissaglio.kimi.android.data.SessionRepository
import fr.louissaglio.kimi.android.data.UploadRepository
import fr.louissaglio.kimi.android.data.model.ContentBlock
import fr.louissaglio.kimi.android.data.model.FileBlock
import fr.louissaglio.kimi.android.data.model.Message
import fr.louissaglio.kimi.android.data.model.PromptRequest
import fr.louissaglio.kimi.android.data.model.ServerEvent
import fr.louissaglio.kimi.android.data.model.SessionStatusChangedEvent
import fr.louissaglio.kimi.android.data.model.TextBlock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(
    private val sessionId: String,
    private val api: KimiApi,
    private val repository: SessionRepository,
    private val uploadRepository: UploadRepository
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _pendingAttachments = MutableStateFlow<List<FileBlock>>(emptyList())
    val pendingAttachments: StateFlow<List<FileBlock>> = _pendingAttachments.asStateFlow()

    init {
        repository.connectWebSocket()
        repository.subscribe(sessionId)
        loadMessages()
        collectEvents()
        collectErrors()
    }

    private fun collectEvents() {
        viewModelScope.launch {
            repository.events.collect { event ->
                val updated = repository.applyEvent(sessionId, event)
                _messages.value = updated
                if (event.sessionId == sessionId) {
                    when (event) {
                        is SessionStatusChangedEvent -> {
                            _isLoading.value = event.status == "running"
                            if (event.status == "idle" && event.previousStatus == "running") {
                                refreshMessages()
                            }
                        }

                        else -> {}
                    }
                }
            }
        }
    }

    private fun collectErrors() {
        viewModelScope.launch {
            repository.errors.collect { error ->
                _error.value = error
            }
        }
    }

    fun loadMessages() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val loaded = repository.loadMessages(sessionId)
                _messages.value = loaded
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load messages"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun refreshMessages() {
        viewModelScope.launch {
            try {
                val loaded = repository.loadMessages(sessionId)
                _messages.value = loaded
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to refresh messages"
            }
        }
    }

    fun onInputChange(value: String) {
        _inputText.value = value
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        val attachments = _pendingAttachments.value
        if (text.isEmpty() && attachments.isEmpty()) return

        val content = mutableListOf<ContentBlock>()
        if (text.isNotEmpty()) {
            content.add(TextBlock(text = text))
        }
        content.addAll(attachments)

        _inputText.value = ""
        _pendingAttachments.value = emptyList()
        _isLoading.value = true

        viewModelScope.launch {
            try {
                api.submitPrompt(
                    sessionId,
                    PromptRequest(content = content)
                )
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to send message"
                _isLoading.value = false
            }
        }
    }

    fun attachFile(uri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            try {
                val inputStream = contentResolver.openInputStream(uri) ?: return@launch
                val bytes = inputStream.use { it.readBytes() }
                val name = uri.lastPathSegment ?: "upload"
                val mediaType = contentResolver.getType(uri) ?: "application/octet-stream"
                val upload = uploadRepository.uploadFile(name, bytes, mediaType)
                _pendingAttachments.value = _pendingAttachments.value + uploadRepository.toContentBlock(upload)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to upload file"
            }
        }
    }

    fun removeAttachment(fileBlock: FileBlock) {
        _pendingAttachments.value = _pendingAttachments.value.filter { it.fileId != fileBlock.fileId }
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        super.onCleared()
        repository.unsubscribe(sessionId)
    }

    class Factory(
        private val sessionId: String,
        private val api: KimiApi,
        private val repository: SessionRepository,
        private val uploadRepository: UploadRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(sessionId, api, repository, uploadRepository) as T
        }
    }
}
