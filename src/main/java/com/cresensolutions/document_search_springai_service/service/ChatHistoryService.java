package com.cresensolutions.document_search_springai_service.service;

import java.util.Map;

/**
 * Contract for storing chat sessions and building recent conversation context.
 */
public interface ChatHistoryService {

    String USER_QUESTION = "user_question";
    String ASSISTANT_ANSWER = "assistant_answer";

    /**
     * Creates a new chat id for the user and persists the backing session.
     */
    String startNewChat(Long userId);

    /**
     * Ensures a specific chat id exists, useful when the client already created the id.
     */
    void startNewChatWithId(String chatId, Long userId);

    /**
     * Appends one typed chat message with optional metadata.
     */
    void appendMessage(String chatId, Long userId, String messageType, String content, Map<String, Object> metadata);

    /**
     * Stores the user question and generated standalone query as one conversation turn.
     */
    void appendExchange(
            String chatId,
            Long userId,
            String userQuestion,
            String standaloneQuery,
            String assistantAnswer,
            Integer questionId,
            Map<String, Object> metadata
    );

    /**
     * Builds the recent conversation text that is sent to the standalone query generator.
     */
    String getRecentContext(String chatId, Long userId, int messageLimit);
}
