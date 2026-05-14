package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.config.ChatClientConfig;
import com.cresensolutions.document_search_springai_service.dto.ViewRegistryEntry;
import com.cresensolutions.document_search_springai_service.service.Impl.FlatSourceClassifierImpl;
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
@DisplayName("FlatSourceClassifierImpl Tests")
class FlatSourceClassifierImplTest {

    @Mock
    SchemaRegistryService schemaRegistryService;

    @Mock
    Map<String, ChatClient> chatClients;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    ChatClient chatClient;

    ObjectMapper objectMapper = new ObjectMapper();

    FlatSourceClassifierImpl service;

    @BeforeEach
    void setUp() {
        service = new FlatSourceClassifierImpl(schemaRegistryService, chatClients, objectMapper);
    }

    @Test
    @DisplayName("classify: returns null if no views are registered")
    void classify_noViews() {
        when(schemaRegistryService.getActiveViews()).thenReturn(List.of());

        String result = service.classify("What is the user count?");
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("classify: returns the only view if only one is registered")
    void classify_oneView() {
        ViewRegistryEntry entry = ViewRegistryEntry.builder().viewName("schema.users").description("User table").confidenceKeywords(List.of()).build();
        when(schemaRegistryService.getActiveViews()).thenReturn(List.of(entry));

        String result = service.classify("What is the user count?");
        assertThat(result).isEqualTo("schema.users");
    }

    @Test
    @DisplayName("classify: returns fallback (first view) if chat client is null")
    void classify_nullChatClient() {
        ViewRegistryEntry v1 = ViewRegistryEntry.builder().viewName("schema.v1").description("v1").confidenceKeywords(List.of()).build();
        ViewRegistryEntry v2 = ViewRegistryEntry.builder().viewName("schema.v2").description("v2").confidenceKeywords(List.of()).build();
        when(schemaRegistryService.getActiveViews()).thenReturn(List.of(v1, v2));
        
        when(chatClients.get(ChatClientConfig.INTENT_CLASSIFIER_CHAT_CLIENT)).thenReturn(null);

        String result = service.classify("question");
        assertThat(result).isEqualTo("schema.v1");
    }

    @Test
    @DisplayName("classify: returns valid view from LLM JSON response")
    void classify_validLlmResponse() {
        ViewRegistryEntry v1 = ViewRegistryEntry.builder().viewName("schema.v1").description("v1").confidenceKeywords(List.of("k1", "k2")).build();
        ViewRegistryEntry v2 = ViewRegistryEntry.builder().viewName("schema.v2").description("v2").confidenceKeywords(List.of()).build();
        when(schemaRegistryService.getActiveViews()).thenReturn(List.of(v1, v2));
        
        when(chatClients.get(ChatClientConfig.INTENT_CLASSIFIER_CHAT_CLIENT)).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().content())
                .thenReturn("```json\n{\"view_name\": \"schema.v2\"}\n```");

        String result = service.classify("question");
        assertThat(result).isEqualTo("schema.v2");
    }

    @Test
    @DisplayName("classify: falls back to first view if LLM returns invalid view name")
    void classify_invalidLlmResponse() {
        ViewRegistryEntry v1 = ViewRegistryEntry.builder().viewName("schema.v1").description("v1").confidenceKeywords(List.of()).build();
        ViewRegistryEntry v2 = ViewRegistryEntry.builder().viewName("schema.v2").description("v2").confidenceKeywords(List.of()).build();
        when(schemaRegistryService.getActiveViews()).thenReturn(List.of(v1, v2));
        
        when(chatClients.get(ChatClientConfig.INTENT_CLASSIFIER_CHAT_CLIENT)).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().content())
                .thenReturn("{\"view_name\": \"schema.v3\"}"); // v3 does not exist

        String result = service.classify("question");
        assertThat(result).isEqualTo("schema.v1");
    }

    @Test
    @DisplayName("classify: falls back to first view if LLM throws exception")
    void classify_llmThrowsException() {
        ViewRegistryEntry v1 = ViewRegistryEntry.builder().viewName("schema.v1").description("v1").confidenceKeywords(List.of()).build();
        ViewRegistryEntry v2 = ViewRegistryEntry.builder().viewName("schema.v2").description("v2").confidenceKeywords(List.of()).build();
        when(schemaRegistryService.getActiveViews()).thenReturn(List.of(v1, v2));
        
        when(chatClients.get(ChatClientConfig.INTENT_CLASSIFIER_CHAT_CLIENT)).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().content())
                .thenThrow(new RuntimeException("API Error"));

        String result = service.classify("question");
        assertThat(result).isEqualTo("schema.v1");
    }
}
