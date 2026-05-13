package com.cresensolutions.document_search_springai_service.service.Impl;

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
 * Unified Chat History implementation that stores all user chats in a single JSONB entry.
 * Matches the requested 'chat_history' table structure.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatHistoryServiceImpl implements ChatHistoryService {

    private final ChatHistoryRepository chatHistoryRepository;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Override
    @Transactional
    public String startNewChat(Long userId) {
        String chatId = "chat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        startNewChatWithId(chatId, userId);
        return chatId;
    }

    @Override
    @Transactional
    public void startNewChatWithId(String chatId, Long userId) {
        ChatHistory history = getOrCreateChatHistory(userId);
        Map<String, Object> conversations = history.getConversations();

        if (!conversations.containsKey(chatId)) {
            Map<String, Object> chatEntry = new LinkedHashMap<>();
            chatEntry.put("conversation_id", chatId);
            chatEntry.put("user_id", userId);
            chatEntry.put("chatDate", OffsetDateTime.now().format(DATE_FORMATTER));
            chatEntry.put("messages", new ArrayList<Map<String, Object>>());
            chatEntry.put("profile", "default");
            chatEntry.put("chatTitle", "New Conversation");
            chatEntry.put("username", history.getUserName());
            
            conversations.put(chatId, chatEntry);
            history.setTotalSessions(conversations.size());
            chatHistoryRepository.save(history);
        }
    }

    @Override
    @Transactional
    public void appendMessage(String chatId, Long userId, String messageType, String content, Map<String, Object> metadata) {
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
            Long userId,
            String userQuestion,
            String standaloneQuery,
            String assistantAnswer,
            Integer questionId,
            Map<String, Object> metadata
    ) {
        Map<String, Object> messageEntry = new LinkedHashMap<>();
        messageEntry.put("question", userQuestion);
        messageEntry.put("answer", assistantAnswer);
        messageEntry.put("standalone_query", standaloneQuery != null ? standaloneQuery : "");
        messageEntry.put("question_id", questionId != null ? questionId : 0);
        messageEntry.put("request_timestamp", OffsetDateTime.now().toString());
        
        if (metadata != null) {
            messageEntry.putAll(metadata);
        }

        appendMessageToChat(chatId, userId, messageEntry);
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public String getRecentContext(String chatId, Long userId, int messageLimit) {
        Long id = userId == null ? -1L : userId;
        ChatHistory history = chatHistoryRepository.findByUserId(id).orElse(null);
        if (history == null) return "None (This is the first interaction)";

        Map<String, Object> conversations = history.getConversations();
        if (!conversations.containsKey(chatId)) return "None (This is the first interaction)";

        Map<String, Object> chatEntry = (Map<String, Object>) conversations.get(chatId);
        List<Map<String, Object>> messages = (List<Map<String, Object>>) chatEntry.get("messages");

        if (messages.isEmpty()) return "None (This is the first interaction)";

        StringBuilder context = new StringBuilder();
        int start = Math.max(0, messages.size() - messageLimit);
        for (int i = start; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            if (i > start) context.append("\n\n");
            
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
    private void appendMessageToChat(String chatId, Long userId, Map<String, Object> message) {
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

    private ChatHistory getOrCreateChatHistory(Long userId) {
        Long id = userId == null ? -1L : userId;
        return chatHistoryRepository.findByUserId(id)
                .orElseGet(() -> {
                    ChatHistory newHist = ChatHistory.builder()
                            .userId(id)
                            .userName("User_" + id) // Default or fetch from user service
                            .conversations(new LinkedHashMap<>())
                            .totalSessions(0)
                            .totalQaPairs(0)
                            .build();
                    return chatHistoryRepository.save(newHist);
                });
    }
}
