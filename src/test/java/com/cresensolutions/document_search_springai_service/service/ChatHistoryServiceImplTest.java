package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.domain.DbSearchHistMessage;
import com.cresensolutions.document_search_springai_service.domain.DbSearchHistSession;
import com.cresensolutions.document_search_springai_service.repository.DbSearchHistMessageRepository;
import com.cresensolutions.document_search_springai_service.repository.DbSearchHistSessionRepository;
import com.cresensolutions.document_search_springai_service.service.Impl.ChatHistoryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatHistoryServiceImpl Tests")
class ChatHistoryServiceImplTest {

    @Mock DbSearchHistSessionRepository sessionRepository;
    @Mock DbSearchHistMessageRepository messageRepository;

    @InjectMocks ChatHistoryServiceImpl service;

    // -------------------------------------------------------------------------
    // startNewChat
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("startNewChat: creates a chat_XXXX id and initialises session")
    void startNewChat_returnsChatId() {
        Long userId = 42L;
        doNothing().when(sessionRepository).insertIfMissing(anyString(), eq(userId));

        String chatId = service.startNewChat(userId);

        assertThat(chatId).startsWith("chat_");
        assertThat(chatId).hasSize(17); // "chat_" + 12 chars
        verify(sessionRepository).insertIfMissing(chatId, userId);
    }

    // -------------------------------------------------------------------------
    // startNewChatWithId
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("startNewChatWithId: calls insertIfMissing for authenticated user")
    void startNewChatWithId_authenticated() {
        service.startNewChatWithId("chat_abc123", 1L);
        verify(sessionRepository).insertIfMissing("chat_abc123", 1L);
    }

    @Test
    @DisplayName("startNewChatWithId: falls back to findOrCreate for null userId")
    void startNewChatWithId_nullUserId() {
        DbSearchHistSession saved = DbSearchHistSession.builder().chatId("c1").build();
        when(sessionRepository.findByChatIdAndUserId("c1", null)).thenReturn(Optional.empty());
        when(sessionRepository.save(any())).thenReturn(saved);

        service.startNewChatWithId("c1", null);

        verify(sessionRepository).save(any(DbSearchHistSession.class));
    }

    // -------------------------------------------------------------------------
    // appendMessage
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("appendMessage: saves message and bumps session counter")
    void appendMessage_savesAndTouches() {
        service.appendMessage("c1", 1L, "USER", "Hello", Map.of("k", "v"));

        verify(messageRepository).save(argThat(m ->
                m.getChatId().equals("c1") &&
                m.getMessageType().equals("USER") &&
                m.getContent().equals("Hello")));
        verify(sessionRepository).touchAndIncrement("c1", 1L, 1);
    }

    // -------------------------------------------------------------------------
    // appendExchange
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("appendExchange: persists USER + ASSISTANT messages together")
    void appendExchange_savesBothMessages() {
        service.appendExchange("c1", 2L,
                "What is X?", "What is X? standalone",
                "X is Y.", 7, Map.of("workflow", "document"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DbSearchHistMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageRepository).saveAll(captor.capture());
        List<DbSearchHistMessage> messages = captor.getValue();

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getMessageType()).isEqualTo(ChatHistoryService.USER_QUESTION);
        assertThat(messages.get(1).getMessageType()).isEqualTo(ChatHistoryService.ASSISTANT_ANSWER);
        assertThat(messages.get(1).getMetadata()).containsKey("standalone_query");
        verify(sessionRepository).touchAndIncrement("c1", 2L, 2);
    }

    // -------------------------------------------------------------------------
    // getRecentContext
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getRecentContext: returns 'None' string when no messages exist")
    void getRecentContext_noMessages_returnsNone() {
        when(messageRepository.findRecentContext("c1", 1L, 10)).thenReturn(List.of());

        String ctx = service.getRecentContext("c1", 1L, 10);

        assertThat(ctx).contains("None");
    }

    @Test
    @DisplayName("getRecentContext: formats turns with [TURN -N | Role] prefix")
    void getRecentContext_withMessages_formatsCorrectly() {
        DbSearchHistMessage userMsg = DbSearchHistMessage.builder()
                .chatId("c1").messageType(ChatHistoryService.USER_QUESTION).content("Hello?").build();
        DbSearchHistMessage aiMsg = DbSearchHistMessage.builder()
                .chatId("c1").messageType(ChatHistoryService.ASSISTANT_ANSWER).content("Hi!").build();
        when(messageRepository.findRecentContext("c1", 1L, 10)).thenReturn(List.of(userMsg, aiMsg));

        String ctx = service.getRecentContext("c1", 1L, 10);

        assertThat(ctx).contains("[TURN -1 | User]: Hello?");
        assertThat(ctx).contains("[TURN -2 | Assistant]: Hi!");
    }
}
