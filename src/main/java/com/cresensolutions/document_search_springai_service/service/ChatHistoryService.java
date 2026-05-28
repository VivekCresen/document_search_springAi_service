package com.cresensolutions.document_search_springai_service.service;

import java.util.Map;
import java.util.UUID;

/**
 * Service interface for managing chat history and conversation context.
 * Provides methods to start new chats, append messages, and retrieve context for RAG.
 */
public interface ChatHistoryService {

    String USER_QUESTION = "user_question";
    String ASSISTANT_ANSWER = "assistant_answer";

    /**
     * Creates a new chat id for the user and persists the backing session.
     */
    String startNewChat(UUID userId);

    /**
     * Ensures a specific chat id exists, useful when the client already created the id.
     */
    void startNewChatWithId(String chatId, UUID userId);

    /**
     * Appends one typed chat message with optional metadata.
     */
    void appendMessage(String chatId, UUID userId, String messageType, String content, Map<String, Object> metadata);

    /**
     * Stores the user question and generated standalone query as one conversation turn.
     */
    void appendExchange(
            String chatId,
            UUID userId,
            String userQuestion,
            String standaloneQuery,
            String assistantAnswer,
            Integer questionId,
            Map<String, Object> metadata
    );

    /**
     * Builds the recent conversation text that is sent to the standalone query generator.
     */
    String getRecentContext(String chatId, UUID userId, int messageLimit);

    /**
     * Retrieves the entire conversation history map for the user.
     */
    Map<String, Object> getConversations(UUID userId);

    /**
     * Clears all conversation history for the user.
     */
    void clearConversations(UUID userId);
}
