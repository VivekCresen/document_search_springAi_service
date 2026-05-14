package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.domain.ChatHistory;
import com.cresensolutions.document_search_springai_service.repository.ChatHistoryRepository;
import com.cresensolutions.document_search_springai_service.service.Impl.ChatHistoryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatHistoryServiceImpl Tests")
class ChatHistoryServiceImplTest {

    @Mock
    ChatHistoryRepository chatHistoryRepository;

    @InjectMocks
    ChatHistoryServiceImpl service;

    // -------------------------------------------------------------------------
    // startNewChat
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("startNewChat: creates a chat_XXXX id and initialises session")
    void startNewChat_returnsChatId() {
        Long userId = 42L;
        ChatHistory history = createEmptyHistory(userId);
        when(chatHistoryRepository.findByUserId(userId)).thenReturn(Optional.of(history));

        String chatId = service.startNewChat(userId);

        assertThat(chatId).startsWith("chat_");
        assertThat(chatId).hasSize(17); // "chat_" + 12 chars
        verify(chatHistoryRepository).save(history);
        assertThat(history.getConversations()).containsKey(chatId);
    }

    // -------------------------------------------------------------------------
    // startNewChatWithId
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("startNewChatWithId: calls save for authenticated user")
    void startNewChatWithId_authenticated() {
        ChatHistory history = createEmptyHistory(1L);
        when(chatHistoryRepository.findByUserId(1L)).thenReturn(Optional.of(history));

        service.startNewChatWithId("chat_abc123", 1L);

        verify(chatHistoryRepository).save(history);
        assertThat(history.getConversations()).containsKey("chat_abc123");
    }

    @Test
    @DisplayName("startNewChatWithId: falls back to fetch with -1 for null userId")
    void startNewChatWithId_nullUserId() {
        ChatHistory history = createEmptyHistory(-1L);
        when(chatHistoryRepository.findByUserId(-1L)).thenReturn(Optional.of(history));

        service.startNewChatWithId("c1", null);

        verify(chatHistoryRepository).save(history);
        assertThat(history.getConversations()).containsKey("c1");
    }

    // -------------------------------------------------------------------------
    // appendMessage
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("appendMessage: saves message to chat history")
    void appendMessage_savesAndTouches() {
        ChatHistory history = createHistoryWithChat("c1", 1L);
        when(chatHistoryRepository.findByUserId(1L)).thenReturn(Optional.of(history));

        service.appendMessage("c1", 1L, "USER", "Hello", Map.of("k", "v"));

        verify(chatHistoryRepository).save(history);
        
        @SuppressWarnings("unchecked")
        Map<String, Object> chat = (Map<String, Object>) history.getConversations().get("c1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) chat.get("messages");
        
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("type")).isEqualTo("USER");
        assertThat(messages.get(0).get("content")).isEqualTo("Hello");
        assertThat(messages.get(0).get("metadata")).isEqualTo(Map.of("k", "v"));
    }

    // -------------------------------------------------------------------------
    // appendExchange
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("appendExchange: persists USER + ASSISTANT messages together")
    void appendExchange_savesBothMessages() {
        ChatHistory history = createHistoryWithChat("c1", 2L);
        when(chatHistoryRepository.findByUserId(2L)).thenReturn(Optional.of(history));

        service.appendExchange("c1", 2L,
                "What is X?", "What is X? standalone",
                "X is Y.", 7, Map.of("workflow", "document"));

        verify(chatHistoryRepository).save(history);
        
        @SuppressWarnings("unchecked")
        Map<String, Object> chat = (Map<String, Object>) history.getConversations().get("c1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) chat.get("messages");

        assertThat(messages).hasSize(1);
        Map<String, Object> exchange = messages.get(0);
        assertThat(exchange.get("question")).isEqualTo("What is X?");
        assertThat(exchange.get("answer")).isEqualTo("X is Y.");
        assertThat(exchange.get("standalone_query")).isEqualTo("What is X? standalone");
        assertThat(exchange.get("workflow")).isEqualTo("document");
    }

    // -------------------------------------------------------------------------
    // getRecentContext
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getRecentContext: returns 'None' string when no messages exist")
    void getRecentContext_noMessages_returnsNone() {
        ChatHistory history = createEmptyHistory(1L);
        when(chatHistoryRepository.findByUserId(1L)).thenReturn(Optional.of(history));

        String ctx = service.getRecentContext("c1", 1L, 10);

        assertThat(ctx).contains("None");
    }

    @Test
    @DisplayName("getRecentContext: formats turns with [TURN -N | Role] prefix")
    void getRecentContext_withMessages_formatsCorrectly() {
        ChatHistory history = createHistoryWithChat("c1", 1L);
        
        @SuppressWarnings("unchecked")
        Map<String, Object> chat = (Map<String, Object>) history.getConversations().get("c1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) chat.get("messages");
        
        messages.add(Map.of("question", "Hello?", "answer", "Hi!"));
        
        when(chatHistoryRepository.findByUserId(1L)).thenReturn(Optional.of(history));

        String ctx = service.getRecentContext("c1", 1L, 10);

        assertThat(ctx).contains("[TURN -1 | User]: Hello?");
        assertThat(ctx).contains("[TURN -1 | Assistant]: Hi!");
    }
    
    // -------------------------------------------------------------------------
    // Helper Methods
    // -------------------------------------------------------------------------
    
    private ChatHistory createEmptyHistory(Long userId) {
        return ChatHistory.builder()
                .userId(userId)
                .conversations(new LinkedHashMap<>())
                .totalSessions(0)
                .totalQaPairs(0)
                .build();
    }
    
    private ChatHistory createHistoryWithChat(String chatId, Long userId) {
        ChatHistory history = createEmptyHistory(userId);
        Map<String, Object> chatEntry = new LinkedHashMap<>();
        chatEntry.put("conversation_id", chatId);
        chatEntry.put("messages", new ArrayList<Map<String, Object>>());
        history.getConversations().put(chatId, chatEntry);
        return history;
    }
}
