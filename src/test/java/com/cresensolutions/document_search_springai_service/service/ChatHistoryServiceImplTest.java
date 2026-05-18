package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.domain.ChatHistory;
import com.cresensolutions.document_search_springai_service.domain.User;
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

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

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
        ChatHistory history = createEmptyHistory(USER_ID);
        when(chatHistoryRepository.findByUserId(USER_ID)).thenReturn(Optional.of(history));

        String chatId = service.startNewChat(USER_ID);

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
        ChatHistory history = createEmptyHistory(USER_ID);
        when(chatHistoryRepository.findByUserId(USER_ID)).thenReturn(Optional.of(history));

        service.startNewChatWithId("chat_abc123", USER_ID);

        verify(chatHistoryRepository).save(history);
        assertThat(history.getConversations()).containsKey("chat_abc123");
    }

    @Test
    @DisplayName("startNewChatWithId: rejects null userId")
    void startNewChatWithId_nullUserId() {
        assertThatThrownBy(() -> service.startNewChatWithId("c1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Valid User ID required");
    }

    // -------------------------------------------------------------------------
    // appendMessage
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("appendMessage: saves message to chat history")
    void appendMessage_savesAndTouches() {
        ChatHistory history = createHistoryWithChat("c1", USER_ID);
        when(chatHistoryRepository.findByUserId(USER_ID)).thenReturn(Optional.of(history));

        service.appendMessage("c1", USER_ID, "USER", "Hello", Map.of("k", "v"));

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
        ChatHistory history = createHistoryWithChat("c1", OTHER_USER_ID);
        when(chatHistoryRepository.findByUserId(OTHER_USER_ID)).thenReturn(Optional.of(history));

        service.appendExchange("c1", OTHER_USER_ID,
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
        ChatHistory history = createEmptyHistory(USER_ID);
        when(chatHistoryRepository.findByUserId(USER_ID)).thenReturn(Optional.of(history));

        String ctx = service.getRecentContext("c1", USER_ID, 10);

        assertThat(ctx).contains("None");
    }

    @Test
    @DisplayName("getRecentContext: formats turns with [TURN -N | Role] prefix")
    void getRecentContext_withMessages_formatsCorrectly() {
        ChatHistory history = createHistoryWithChat("c1", USER_ID);
        
        @SuppressWarnings("unchecked")
        Map<String, Object> chat = (Map<String, Object>) history.getConversations().get("c1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) chat.get("messages");
        
        messages.add(Map.of("question", "Hello?", "answer", "Hi!"));
        
        when(chatHistoryRepository.findByUserId(USER_ID)).thenReturn(Optional.of(history));

        String ctx = service.getRecentContext("c1", USER_ID, 10);

        assertThat(ctx).contains("[TURN -1 | User]: Hello?");
        assertThat(ctx).contains("[TURN -1 | Assistant]: Hi!");
    }
    
    // -------------------------------------------------------------------------
    // Private Method Tests via Reflection
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Reflection: appendMessageToChat creates new chat if not exists")
    void reflection_appendMessageToChat_createsNewChat() throws Exception {
        ChatHistory history = createEmptyHistory(USER_ID);
        when(chatHistoryRepository.findByUserId(USER_ID)).thenReturn(Optional.of(history));

        java.lang.reflect.Method method = ChatHistoryServiceImpl.class.getDeclaredMethod("appendMessageToChat", String.class, UUID.class, Map.class);
        method.setAccessible(true);
        
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("content", "Reflection Test");
        
        method.invoke(service, "c_new", USER_ID, msg);
        
        verify(chatHistoryRepository, atLeastOnce()).save(history);
        assertThat(history.getConversations()).containsKey("c_new");
    }

    @Test
    @DisplayName("Reflection: getOrCreateChatHistory throws if user null")
    void reflection_getOrCreateChatHistory_nullUser() throws Exception {
        java.lang.reflect.Method method = ChatHistoryServiceImpl.class.getDeclaredMethod("getOrCreateChatHistory", UUID.class);
        method.setAccessible(true);
        
        assertThatThrownBy(() -> method.invoke(service, (UUID) null))
                .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Reflection: getOrCreateChatHistory fetches user if history missing")
    void reflection_getOrCreateChatHistory_missingHistory() throws Exception {
        com.cresensolutions.document_search_springai_service.repository.UserRepository userRepository = mock(com.cresensolutions.document_search_springai_service.repository.UserRepository.class);
        java.lang.reflect.Field field = ChatHistoryServiceImpl.class.getDeclaredField("userRepository");
        field.setAccessible(true);
        field.set(service, userRepository);

        when(chatHistoryRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
        when(chatHistoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        java.lang.reflect.Method method = ChatHistoryServiceImpl.class.getDeclaredMethod("getOrCreateChatHistory", UUID.class);
        method.setAccessible(true);
        
        ChatHistory result = (ChatHistory) method.invoke(service, USER_ID);
        
        assertThat(result).isNotNull();
        assertThat(result.getUserName()).isEqualTo("vivek");
    }

    // -------------------------------------------------------------------------
    // Helper Methods
    // -------------------------------------------------------------------------
    
    private ChatHistory createEmptyHistory(UUID userId) {
        return ChatHistory.builder()
                .user(user(userId))
                .userName("vivek")
                .emailId("vivek@example.com")
                .conversations(new LinkedHashMap<>())
                .totalSessions(0)
                .totalQaPairs(0)
                .build();
    }
    
    private ChatHistory createHistoryWithChat(String chatId, UUID userId) {
        ChatHistory history = createEmptyHistory(userId);
        Map<String, Object> chatEntry = new LinkedHashMap<>();
        chatEntry.put("conversation_id", chatId);
        chatEntry.put("messages", new ArrayList<Map<String, Object>>());
        history.getConversations().put(chatId, chatEntry);
        return history;
    }

    private User user(UUID userId) {
        return User.builder()
                .id(userId)
                .userName("vivek")
                .fullName("Vivek")
                .email("vivek@example.com")
                .password("password")
                .build();
    }
}
