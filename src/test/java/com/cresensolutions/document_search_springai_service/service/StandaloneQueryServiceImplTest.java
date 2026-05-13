package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.config.ChatClientConfig;
import com.cresensolutions.document_search_springai_service.service.Impl.StandaloneQueryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StandaloneQueryServiceImpl Tests")
class StandaloneQueryServiceImplTest {

    @Mock ChatClient chatClient;

    // We supply the map ourselves so we can control which client is returned.
    StandaloneQueryServiceImpl service;

    // -------------------------------------------------------------------------
    // createStandaloneQuery — null / blank guards
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createStandaloneQuery: null question returns empty string")
    void createStandaloneQuery_nullQuestion_returnsEmpty() {
        service = new StandaloneQueryServiceImpl(Map.of());
        assertThat(service.createStandaloneQuery(null, "ctx")).isEmpty();
    }

    @Test
    @DisplayName("createStandaloneQuery: blank question returns empty string")
    void createStandaloneQuery_blankQuestion_returnsEmpty() {
        service = new StandaloneQueryServiceImpl(Map.of());
        assertThat(service.createStandaloneQuery("   ", "ctx")).isEmpty();
    }

    // -------------------------------------------------------------------------
    // createStandaloneQuery — no chat client
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createStandaloneQuery: returns original question when no client configured")
    void createStandaloneQuery_noClient_returnsOriginal() {
        service = new StandaloneQueryServiceImpl(Map.of()); // empty map → no client
        String result = service.createStandaloneQuery("What is X?", "some context");
        assertThat(result).isEqualTo("What is X?");
    }

    // -------------------------------------------------------------------------
    // createStandaloneQuery — with chat client returning clean text
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createStandaloneQuery: strips REFORMULATED QUESTION: prefix")
    void createStandaloneQuery_stripsPrefix() {
        mockChatClientResponse("REFORMULATED QUESTION: What is the capital of France?");
        String result = service.createStandaloneQuery("capital of france?", "");
        assertThat(result).isEqualTo("What is the capital of France?");
    }

    @Test
    @DisplayName("createStandaloneQuery: strips STANDALONE QUESTION: prefix (case-insensitive)")
    void createStandaloneQuery_stripsStandalonePrefix() {
        mockChatClientResponse("Standalone Question: What is the GDP of Germany?");
        String result = service.createStandaloneQuery("their GDP?", "prev context");
        assertThat(result).isEqualTo("What is the GDP of Germany?");
    }

    @Test
    @DisplayName("createStandaloneQuery: handles English translation format")
    void createStandaloneQuery_englishTranslation() {
        mockChatClientResponse(
                "REFORMULATED QUESTION: Quelle est la capitale?\nENGLISH TRANSLATION: What is the capital?");
        String result = service.createStandaloneQuery("la capitale?", "");
        assertThat(result).contains("Quelle est la capitale?");
        assertThat(result).contains("English: What is the capital?");
    }

    @Test
    @DisplayName("createStandaloneQuery: returns original question when LLM throws")
    void createStandaloneQuery_llmException_returnsFallback() {
        service = buildServiceWithBrokenClient();
        String result = service.createStandaloneQuery("my question?", "ctx");
        assertThat(result).isEqualTo("my question?");
    }

    @Test
    @DisplayName("createStandaloneQuery: returns original question when LLM returns blank")
    void createStandaloneQuery_blankLlmResponse_returnsFallback() {
        mockChatClientResponse("  ");
        String result = service.createStandaloneQuery("my question?", "ctx");
        assertThat(result).isEqualTo("my question?");
    }

    @Test
    @DisplayName("createStandaloneQuery: returns original question when LLM returns null")
    void createStandaloneQuery_nullLlmResponse_returnsFallback() {
        mockChatClientResponse(null);
        String result = service.createStandaloneQuery("my question?", "ctx");
        assertThat(result).isEqualTo("my question?");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void mockChatClientResponse(String response) {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(response);

        service = new StandaloneQueryServiceImpl(
                Map.of(ChatClientConfig.STANDALONE_QUERY_CHAT_CLIENT, chatClient));
    }

    private StandaloneQueryServiceImpl buildServiceWithBrokenClient() {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("LLM unavailable"));

        return new StandaloneQueryServiceImpl(
                Map.of(ChatClientConfig.STANDALONE_QUERY_CHAT_CLIENT, chatClient));
    }
}
