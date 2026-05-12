package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.domain.DbSearchHistMessage;
import com.cresensolutions.document_search_springai_service.domain.DbSearchHistSession;
import com.cresensolutions.document_search_springai_service.repository.DbSearchHistMessageRepository;
import com.cresensolutions.document_search_springai_service.repository.DbSearchHistSessionRepository;
import com.cresensolutions.document_search_springai_service.service.ChatHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persists chat sessions/messages and formats recent history for workflow prompts.
 */
@Service
@RequiredArgsConstructor
public class ChatHistoryServiceImpl implements ChatHistoryService {

    private final DbSearchHistSessionRepository sessionRepository;
    private final DbSearchHistMessageRepository messageRepository;

    @Override
    @Transactional
    public String startNewChat(Long userId) {
        // Keep ids short enough for API clients while still avoiding practical collisions.
        String chatId = "chat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        startNewChatWithId(chatId, userId);
        return chatId;
    }

    @Override
    @Transactional
    public void startNewChatWithId(String chatId, Long userId) {
        ensureSession(chatId, userId);
    }

    @Override
    @Transactional
    public void appendMessage(String chatId, Long userId, String messageType, String content, Map<String, Object> metadata) {
        ensureSession(chatId, userId);
        messageRepository.save(buildMessage(chatId, userId, messageType, content, metadata));
        sessionRepository.touchAndIncrement(chatId, userId, 1);
    }

    @Override
    @Transactional
    public void appendExchange(
            String chatId,
            Long userId,
            String userQuestion,
            String standaloneQuery,
            Integer questionId
    ) {
        ensureSession(chatId, userId);

        // Save both sides together so the history always represents complete turns.
        List<DbSearchHistMessage> messages = new ArrayList<>(2);
        messages.add(buildMessage(chatId, userId, USER_QUESTION, userQuestion, Map.of(
                "workflow", "phase_0_to_3",
                "question_id", questionId == null ? "" : questionId
        )));
        messages.add(buildMessage(chatId, userId, ASSISTANT_ANSWER, standaloneQuery, Map.of(
                "workflow", "phase_0_to_3",
                "standalone_query", standaloneQuery == null ? "" : standaloneQuery
        )));

        messageRepository.saveAll(messages);
        sessionRepository.touchAndIncrement(chatId, userId, messages.size());
    }

    @Override
    @Transactional(readOnly = true)
    public String getRecentContext(String chatId, Long userId, int messageLimit) {
        List<DbSearchHistMessage> messages = messageRepository.findRecentContext(chatId, userId, messageLimit);
        if (messages.isEmpty()) {
            return "None (This is the first interaction)";
        }

        StringBuilder context = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            DbSearchHistMessage message = messages.get(i);
            String role = USER_QUESTION.equals(message.getMessageType()) ? "User" : "Assistant";
            if (i > 0) {
                context.append("\n\n");
            }
            context.append("[TURN -")
                    .append(i + 1)
                    .append(" | ")
                    .append(role)
                    .append("]: ")
                    .append(message.getContent());
        }
        return context.toString();
    }

    /**
     * Centralizes message construction so all persisted messages keep the same required fields.
     */
    private DbSearchHistMessage buildMessage(
            String chatId,
            Long userId,
            String messageType,
            String content,
            Map<String, Object> metadata
    ) {
        return DbSearchHistMessage.builder()
                .chatId(chatId)
                .userId(userId)
                .messageType(messageType)
                .content(content)
                .metadata(metadata)
                .build();
    }

    private void ensureSession(String chatId, Long userId) {
        if (userId != null) {
            // Repository insert is idempotent for authenticated users and avoids duplicate sessions.
            sessionRepository.insertIfMissing(chatId, userId);
            return;
        }
        sessionRepository.findByChatIdAndUserId(chatId, userId)
                .orElseGet(() -> sessionRepository.save(DbSearchHistSession.builder()
                        .chatId(chatId)
                        .userId(userId)
                        .startedAt(OffsetDateTime.now())
                        .lastActivityAt(OffsetDateTime.now())
                        .messageCount(0)
                        .build()));
    }
}
