package fr.louissaglio.kimi.android.data

import fr.louissaglio.kimi.android.data.model.ApprovalRequestedEvent
import fr.louissaglio.kimi.android.data.model.ApprovalResolvedEvent
import fr.louissaglio.kimi.android.data.model.AssistantDeltaEvent
import fr.louissaglio.kimi.android.data.model.ContentBlock
import fr.louissaglio.kimi.android.data.model.MessageCreatedEvent
import fr.louissaglio.kimi.android.data.model.MessageUpdatedEvent
import fr.louissaglio.kimi.android.data.model.QuestionAnsweredEvent
import fr.louissaglio.kimi.android.data.model.QuestionRequestedEvent
import fr.louissaglio.kimi.android.data.model.ServerEvent
import fr.louissaglio.kimi.android.data.model.SessionStatusChangedEvent
import fr.louissaglio.kimi.android.data.model.ToolOutputEvent
import fr.louissaglio.kimi.android.data.model.UnknownEvent
import fr.louissaglio.kimi.android.data.model.WebSocketEnvelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

fun WebSocketEnvelope.toServerEvent(): ServerEvent {
    val payloadObj = payload ?: JsonObject(emptyMap())
    return when (type) {
        "event.message.created" -> MessageCreatedEvent(
            sessionId = sessionId,
            seq = seq,
            message = kimiJson.decodeFromJsonElement(
                serializer(),
                payloadObj["message"] ?: JsonObject(emptyMap())
            )
        )

        "event.message.updated" -> MessageUpdatedEvent(
            sessionId = sessionId,
            seq = seq,
            messageId = payloadObj["message_id"]?.jsonPrimitive?.content ?: "",
            content = payloadObj["content"]?.jsonArray?.map {
                kimiJson.decodeFromJsonElement(
                    serializer<ContentBlock>(),
                    it
                )
            } ?: emptyList(),
            status = payloadObj["status"]?.jsonPrimitive?.content
        )

        "event.assistant.delta" -> AssistantDeltaEvent(
            sessionId = sessionId,
            seq = seq,
            messageId = payloadObj["message_id"]?.jsonPrimitive?.content ?: "",
            contentIndex = payloadObj["content_index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            delta = payloadObj["delta"]?.jsonPrimitive?.content ?: ""
        )

        "event.session.status_changed" -> SessionStatusChangedEvent(
            sessionId = sessionId,
            seq = seq,
            status = payloadObj["status"]?.jsonPrimitive?.content ?: "",
            previousStatus = payloadObj["previous_status"]?.jsonPrimitive?.content,
            currentPromptId = payloadObj["current_prompt_id"]?.jsonPrimitive?.content
        )

        "event.tool.output" -> ToolOutputEvent(
            sessionId = sessionId,
            seq = seq,
            toolCallId = payloadObj["tool_call_id"]?.jsonPrimitive?.content ?: "",
            outputChunk = payloadObj["chunk"]?.jsonPrimitive?.content ?: "",
            stream = payloadObj["stream"]?.jsonPrimitive?.content
        )

        "event.tool.progress" -> {
            val message = payloadObj["message"]?.jsonPrimitive?.content
                ?: payloadObj["update"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: ""
            ToolOutputEvent(
                sessionId = sessionId,
                seq = seq,
                toolCallId = payloadObj["tool_call_id"]?.jsonPrimitive?.content ?: "",
                outputChunk = message,
                stream = payloadObj["stream"]?.jsonPrimitive?.content ?: "stdout"
            )
        }

        "event.approval.requested" -> ApprovalRequestedEvent(
            sessionId = sessionId,
            seq = seq,
            approval = kimiJson.decodeFromJsonElement(
                serializer(),
                payloadObj
            )
        )

        "event.approval.resolved" -> ApprovalResolvedEvent(
            sessionId = sessionId,
            seq = seq,
            approvalId = payloadObj["approval_id"]?.jsonPrimitive?.content ?: "",
            decision = payloadObj["decision"]?.jsonPrimitive?.content ?: "",
            resolvedAt = payloadObj["resolved_at"]?.jsonPrimitive?.content
        )

        "event.question.requested" -> QuestionRequestedEvent(
            sessionId = sessionId,
            seq = seq,
            question = kimiJson.decodeFromJsonElement(
                serializer(),
                payloadObj
            )
        )

        "event.question.answered" -> QuestionAnsweredEvent(
            sessionId = sessionId,
            seq = seq,
            questionId = payloadObj["question_id"]?.jsonPrimitive?.content ?: "",
            resolvedAt = payloadObj["resolved_at"]?.jsonPrimitive?.content
        )

        else -> UnknownEvent(
            sessionId = sessionId,
            seq = seq,
            rawType = type,
            payload = payloadObj
        )
    }
}
