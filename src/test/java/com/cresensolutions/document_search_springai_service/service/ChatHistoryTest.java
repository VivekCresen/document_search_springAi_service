package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.domain.ChatHistory;
import com.cresensolutions.document_search_springai_service.domain.User;
import com.cresensolutions.document_search_springai_service.repository.ChatHistoryRepository;
import com.cresensolutions.document_search_springai_service.service.Impl.ChatHistoryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatHistoryTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock ChatHistoryRepository chatHistoryRepository;
    @InjectMocks ChatHistoryServiceImpl service;

    @Test
    @DisplayName("startNewChat: creates entry in conversations map")
    void startNewChat_createsEntry() {
        ChatHistory history = ChatHistory.builder()
                .user(user())
                .userName("vivek_1")
                .conversations(new HashMap<>())
                .build();
        when(chatHistoryRepository.findByUserId(USER_ID)).thenReturn(Optional.of(history));

        String chatId = service.startNewChat(USER_ID);

        assertThat(chatId).startsWith("chat_");
        assertThat(history.getConversations()).containsKey(chatId);
        assertThat(history.getTotalSessions()).isEqualTo(1);
        verify(chatHistoryRepository).save(history);
    }

    @Test
    @DisplayName("appendExchange: adds to nested list and increments QA pairs")
    void appendExchange_addsToNestedList() {
        String chatId = "chat_123";
        Map<String, Object> chatEntry = new HashMap<>();
        chatEntry.put("messages", new ArrayList<Map<String, Object>>());
        
        Map<String, Object> historyMap = new HashMap<>();
        historyMap.put(chatId, chatEntry);
        
        ChatHistory history = ChatHistory.builder()
                .user(user())
                .conversations(historyMap)
                .totalQaPairs(0)
                .build();
        
        when(chatHistoryRepository.findByUserId(USER_ID)).thenReturn(Optional.of(history));

        service.appendExchange(chatId, USER_ID, "Hi", "query", "Hello", 1, Map.of("source", "test"));

        List<Map<String, Object>> messages = (List<Map<String, Object>>) chatEntry.get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("question")).isEqualTo("Hi");
        assertThat(messages.get(0).get("answer")).isEqualTo("Hello");
        assertThat(history.getTotalQaPairs()).isEqualTo(1);
        verify(chatHistoryRepository).save(history);
    }

    private static User user() {
        return User.builder()
                .id(USER_ID)
                .userName("vivek_1")
                .fullName("Vivek")
                .email("vivek@example.com")
                .password("password")
                .build();
    }
}
