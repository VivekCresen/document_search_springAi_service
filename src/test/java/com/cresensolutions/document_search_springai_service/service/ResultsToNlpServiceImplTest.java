package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.config.ChatClientConfig;
import com.cresensolutions.document_search_springai_service.service.Impl.ResultsToNlpServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResultsToNlpServiceImpl Tests")
class ResultsToNlpServiceImplTest {

    @Mock
    Map<String, ChatClient> chatClients;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    ChatClient chatClient;

    ObjectMapper objectMapper = new ObjectMapper();

    ResultsToNlpServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ResultsToNlpServiceImpl(chatClients, objectMapper);
    }

    @Test
    @DisplayName("generateNlpAnswer: returns empty message if rows are null or empty")
    void generateNlpAnswer_emptyRows() {
        assertThat(service.generateNlpAnswer("q", null, false))
                .isEqualTo("No records were found matching your query.");
        
        assertThat(service.generateNlpAnswer("q", List.of(), false))
                .isEqualTo("No records were found matching your query.");
    }

    @Test
    @DisplayName("generateNlpAnswer: uses LLM to generate answer successfully")
    void generateNlpAnswer_success() {
        when(chatClients.get(ChatClientConfig.SECURED_RAG_CHAT_CLIENT)).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn("LLM Answer");

        List<Map<String, Object>> rows = List.of(Map.of("col1", "val1"));
        String result = service.generateNlpAnswer("q", rows, false);

        assertThat(result).isEqualTo("LLM Answer");
    }

    @Test
    @DisplayName("generateNlpAnswer: falls back to generic string if LLM call fails")
    void generateNlpAnswer_fallbackOnFailure() {
        when(chatClients.get(ChatClientConfig.SECURED_RAG_CHAT_CLIENT)).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().content()).thenThrow(new RuntimeException("API error"));

        List<Map<String, Object>> rows = List.of(Map.of("col1", "val1"));
        String result = service.generateNlpAnswer("q", rows, false);

        assertThat(result).contains("Found 1 record");
    }

    @Test
    @DisplayName("generateTableIntro: generates string via LLM successfully")
    void generateTableIntro_success() {
        when(chatClients.get(ChatClientConfig.SECURED_RAG_CHAT_CLIENT)).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn("Here is the data.");

        String result = service.generateTableIntro("q", 5);

        assertThat(result).isEqualTo("Here is the data.");
    }

    @Test
    @DisplayName("generateTableIntro: falls back on exception")
    void generateTableIntro_fallback() {
        when(chatClients.get(ChatClientConfig.SECURED_RAG_CHAT_CLIENT)).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().content()).thenThrow(new RuntimeException("Error"));

        String result = service.generateTableIntro("q", 5);

        assertThat(result).isEqualTo("Here are the 5 record(s) matching your query.");
    }
}
