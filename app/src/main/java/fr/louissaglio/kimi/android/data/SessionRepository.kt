package fr.louissaglio.kimi.android.data

import fr.louissaglio.kimi.android.data.model.AssistantDeltaEvent
import fr.louissaglio.kimi.android.data.model.ContentBlock
import fr.louissaglio.kimi.android.data.model.Message
import fr.louissaglio.kimi.android.data.model.MessageCreatedEvent
import fr.louissaglio.kimi.android.data.model.MessageUpdatedEvent
import fr.louissaglio.kimi.android.data.model.PromptRequest
import fr.louissaglio.kimi.android.data.model.ServerEvent
import fr.louissaglio.kimi.android.data.model.Session
import fr.louissaglio.kimi.android.data.model.SessionStatusChangedEvent
import fr.louissaglio.kimi.android.data.model.TextBlock
import fr.louissaglio.kimi.android.data.model.ThinkingBlock
import fr.louissaglio.kimi.android.data.model.ToolOutputEvent
import fr.louissaglio.kimi.android.data.model.ToolResultBlock
import fr.louissaglio.kimi.android.data.model.ToolUseBlock
import fr.louissaglio.kimi.android.data.model.UnknownEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionRepository(
    private val api: KimiApi,
    private val webSocketManager: WebSocketManager
) {
    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())

    val events: Flow<ServerEvent> = webSocketManager.events
    val errors: Flow<String> = webSocketManager.errors
    val connectionState: Flow<Boolean> = webSocketManager.connectionState

    suspend fun listSessions(): List<Session> = api.listSessions()

    suspend fun createSession(): Session = api.createSession()

    suspend fun loadMessages(sessionId: String): List<Message> {
        val messages = api.listMessages(sessionId)
        _messages.value = _messages.value.toMutableMap().apply {
            put(sessionId, messages)
        }
        return messages
    }

    fun messages(sessionId: String): StateFlow<List<Message>> {
        val flow = MutableStateFlow(_messages.value[sessionId] ?: emptyList())
        // Keep in sync is handled by applyEvent; this is a simple snapshot.
        return flow.asStateFlow()
    }

    suspend fun sendMessage(sessionId: String, text: String) {
        api.submitPrompt(
            sessionId,
            PromptRequest(content = listOf(TextBlock(text = text)))
        )
    }

    suspend fun abortPrompt(sessionId: String, promptId: String) {
        api.abortPrompt(sessionId, promptId)
    }

    fun subscribe(sessionId: String) {
        webSocketManager.subscribe(sessionId)
    }

    fun unsubscribe(sessionId: String) {
        webSocketManager.unsubscribe(sessionId)
    }

    fun connectWebSocket() {
        webSocketManager.connect()
    }

    fun disconnectWebSocket() {
        webSocketManager.disconnect()
    }

    fun applyEvent(sessionId: String, event: ServerEvent): List<Message> {
        val current = _messages.value[sessionId] ?: emptyList()
        val updated = when (event) {
            is MessageCreatedEvent -> {
                if (event.message.sessionId == sessionId) {
                    current + event.message
                } else current
            }

            is MessageUpdatedEvent -> {
                current.map { message ->
                    if (message.id == event.messageId) {
                        message.copy(content = event.content, status = event.status ?: message.status)
                    } else message
                }
            }

            is AssistantDeltaEvent -> {
                current.map { message ->
                    if (message.id == event.messageId) {
                        val content = message.content.toMutableList()
                        val index = event.contentIndex.coerceIn(0, content.size)
                        if (index == content.size) {
                            content.add(TextBlock(text = event.delta))
                        } else {
                            val block = content[index]
                            content[index] = when (block) {
                                is TextBlock -> block.copy(text = block.text + event.delta)
                                is ThinkingBlock -> block.copy(thinking = block.thinking + event.delta)
                                else -> block
                            }
                        }
                        message.copy(content = content)
                    } else message
                }
            }

            is ToolOutputEvent -> {
                current.map { message ->
                    val content = message.content.toMutableList()
                    val existingIndex = content.indexOfFirst {
                        it is ToolResultBlock && it.toolCallId == event.toolCallId
                    }
                    if (existingIndex >= 0) {
                        val existing = content[existingIndex] as ToolResultBlock
                        content[existingIndex] = existing.copy(output = existing.output + event.outputChunk)
                    } else {
                        val toolUseIndex = content.indexOfFirst {
                            it is ToolUseBlock && it.toolCallId == event.toolCallId
                        }
                        if (toolUseIndex >= 0) {
                            content.add(
                                toolUseIndex + 1,
                                ToolResultBlock(
                                    toolCallId = event.toolCallId,
                                    output = event.outputChunk
                                )
                            )
                        }
                    }
                    message.copy(content = content)
                }
            }

            is SessionStatusChangedEvent -> current
            is UnknownEvent -> current
            else -> current
        }
        _messages.value = _messages.value.toMutableMap().apply {
            put(sessionId, updated)
        }
        return updated
    }
}
