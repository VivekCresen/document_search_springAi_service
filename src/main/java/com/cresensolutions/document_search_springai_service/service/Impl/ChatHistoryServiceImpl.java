package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.commons.Common;
import com.cresensolutions.document_search_springai_service.domain.ChatHistory;
import com.cresensolutions.document_search_springai_service.repository.ChatHistoryRepository;
import com.cresensolutions.document_search_springai_service.service.ChatHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Implementation of ChatHistoryService that manages chat history in a unified JSONB structure.
 * This service handles the mapping of individual chats within a single user-level ChatHistory record.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatHistoryServiceImpl implements ChatHistoryService {

    private final ChatHistoryRepository chatHistoryRepository;
    private final com.cresensolutions.document_search_springai_service.repository.UserRepository userRepository;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(Common.CHAT_DATE_PATTERN);

    /**
     * Starts a new chat session with a generated unique ID.
     *
     * @param userId the ID of the user starting the chat
     * @return the generated chat ID
     */
    @Override
    @Transactional
    public String startNewChat(UUID userId) {
        // Generate a random chat ID with a specific prefix and length
        String chatId = Common.CHAT_ID_PREFIX
                + UUID.randomUUID().toString().replace("-", "").substring(0, Common.CHAT_ID_LENGTH);
        startNewChatWithId(chatId, userId);
        return chatId;
    }

    /**
     * Initializes a chat session with a specific ID if it doesn't already exist.
     *
     * @param chatId the chat ID to initialize
     * @param userId the ID of the user
     */
    @Override
    @Transactional
    public void startNewChatWithId(String chatId, UUID userId) {
        ChatHistory history = getOrCreateChatHistory(userId);
        Map<String, Object> conversations = history.getConversations();

        // Only initialize if this chat ID is new for the user
        if (!conversations.containsKey(chatId)) {
            Map<String, Object> chatEntry = new LinkedHashMap<>();
            chatEntry.put("conversation_id", chatId);
            chatEntry.put("user_id", userId);
            chatEntry.put("chatDate", OffsetDateTime.now().format(DATE_FORMATTER));
            chatEntry.put("messages", new ArrayList<Map<String, Object>>());
            chatEntry.put("profile", Common.DEFAULT_CHAT_PROFILE);
            chatEntry.put("chatTitle", Common.DEFAULT_CHAT_TITLE);
            chatEntry.put("username", history.getUserName());
            chatEntry.put("response_type", "text");
            
            conversations.put(chatId, chatEntry);
            history.setTotalSessions(conversations.size());
            chatHistoryRepository.save(history);
        }
    }

    @Override
    @Transactional
    public void appendMessage(String chatId, UUID userId, String messageType, String content, Map<String, Object> metadata) {
        Map<String, Object> messageEntry = new LinkedHashMap<>();
        messageEntry.put("type", messageType);
        messageEntry.put("content", content);
        messageEntry.put("timestamp", OffsetDateTime.now().toString());
        messageEntry.put("metadata", metadata != null ? metadata : Map.of());
        
        appendMessageToChat(chatId, userId, messageEntry);
    }

    @Override
    @Transactional
    public void appendExchange(
            String chatId,
            UUID userId,
            String userQuestion,
            String standaloneQuery,
            String assistantAnswer,
            Integer questionId,
            Map<String, Object> metadata
    ) {
        Map<String, Object> messageEntry = new LinkedHashMap<>();

        // 1. table
        Object tableData = null;
        if (metadata != null) {
            tableData = metadata.get(Common.RESULT_DATA_PAYLOAD);
        }
        messageEntry.put("table", tableData);

        // 2. answer
        messageEntry.put("answer", assistantAnswer);

        // 3. source
        String source = "general";
        if (metadata != null) {
            if (metadata.containsKey("workflow")) {
                source = metadata.get("workflow").toString();
            } else if (metadata.containsKey("intent")) {
                source = metadata.get("intent").toString();
            }
        }
        messageEntry.put("source", source);

        // 4. question
        messageEntry.put("question", userQuestion);

        // 5. latency_ms
        long latencyMs = 0;
        if (metadata != null && metadata.containsKey("latency_ms")) {
            try {
                latencyMs = ((Number) metadata.get("latency_ms")).longValue();
            } catch (Exception e) {
                // Ignore parsing errors
            }
        }
        messageEntry.put("latency_ms", latencyMs);

        // 6. request_id
        String reqId = "";
        if (metadata != null && metadata.containsKey("request_id")) {
            reqId = metadata.get("request_id").toString();
        }
        messageEntry.put("request_id", reqId);

        // 7. question_id
        messageEntry.put("question_id", questionId != null ? questionId : 0);

        // 8. request_timestamp
        String reqTimestamp = OffsetDateTime.now().toString();
        if (metadata != null && metadata.containsKey("request_timestamp")) {
            reqTimestamp = metadata.get("request_timestamp").toString();
        }
        messageEntry.put("request_timestamp", reqTimestamp);

        // 9. response_timestamp
        messageEntry.put("response_timestamp", OffsetDateTime.now().toString());

        appendMessageToChat(chatId, userId, messageEntry);
    }

    /**
     * Retrieves recent conversation context to provide context for the LLM.
     *
     * @param chatId the chat ID
     * @param userId the user ID
     * @param messageLimit maximum number of messages to include in context
     * @return formatted context string or a default "No recent context" message
     */
    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public String getRecentContext(String chatId, UUID userId, int messageLimit) {
        if (userId == null) return Common.NO_RECENT_CONTEXT;
        ChatHistory history = chatHistoryRepository.findByUserId(userId).orElse(null);
        if (history == null) return Common.NO_RECENT_CONTEXT;

        Map<String, Object> conversations = history.getConversations();
        if (!conversations.containsKey(chatId)) return Common.NO_RECENT_CONTEXT;

        Map<String, Object> chatEntry = (Map<String, Object>) conversations.get(chatId);
        List<Map<String, Object>> messages = (List<Map<String, Object>>) chatEntry.get("messages");

        if (messages.isEmpty()) return Common.NO_RECENT_CONTEXT;

        StringBuilder context = new StringBuilder();
        // Calculate the starting index based on the message limit
        int start = Math.max(0, messages.size() - messageLimit);
        for (int i = start; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            if (i > start) context.append("\n\n");
            
            // Extract question/content and answer
            String q = (String) msg.getOrDefault("question", msg.getOrDefault("content", ""));
            String a = (String) msg.getOrDefault("answer", "");
            
            context.append("[TURN -").append(messages.size() - i).append(" | User]: ").append(q);
            if (!a.isEmpty()) {
                context.append("\n[TURN -").append(messages.size() - i).append(" | Assistant]: ").append(a);
            }
        }
        return context.toString();
    }

    @SuppressWarnings("unchecked")
    private void appendMessageToChat(String chatId, UUID userId, Map<String, Object> message) {
        ChatHistory history = getOrCreateChatHistory(userId);
        Map<String, Object> conversations = history.getConversations();

        if (!conversations.containsKey(chatId)) {
            startNewChatWithId(chatId, userId);
            history = getOrCreateChatHistory(userId);
            conversations = history.getConversations();
        }

        Map<String, Object> chatEntry = (Map<String, Object>) conversations.get(chatId);
        List<Map<String, Object>> messages = (List<Map<String, Object>>) chatEntry.get("messages");
        messages.add(message);

        // Update response_type at conversation level based on message type/payload
        if (message.containsKey("table") && message.get("table") != null) {
            chatEntry.put("response_type", "table");
        } else {
            chatEntry.put("response_type", "text");
        }
        
        // Update total QA pairs
        if (message.containsKey("question") && message.containsKey("answer")) {
            history.setTotalQaPairs(history.getTotalQaPairs() + 1);
        }

        // Update chat title if it's the first message
        if (messages.size() == 1) {
            String title = (String) message.getOrDefault("question", message.getOrDefault("content", "New Chat"));
            if (title.length() > 50) title = title.substring(0, 47) + "...";
            chatEntry.put("chatTitle", title);
        }

        chatHistoryRepository.save(history);
    }

    private ChatHistory getOrCreateChatHistory(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("Valid User ID required for chat history");
        }
        
        return chatHistoryRepository.findByUserId(userId)
                .orElseGet(() -> {
                    com.cresensolutions.document_search_springai_service.domain.User user = userRepository.findById(userId)
                            .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));

                    ChatHistory newHist = ChatHistory.builder()
                            .user(user)
                            .userName(user.getUserName())
                            .emailId(user.getEmail())
                            .conversations(new LinkedHashMap<>())
                            .totalSessions(0)
                            .totalQaPairs(0)
                            .build();
                    return chatHistoryRepository.save(newHist);
                });
    }
}
