package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.config.AzureSearchProperty;
import com.cresensolutions.document_search_springai_service.config.ChatClientConfig;
import com.cresensolutions.document_search_springai_service.dto.IntentClassification;
import com.cresensolutions.document_search_springai_service.dto.SearchResultDocument;
import com.cresensolutions.document_search_springai_service.service.Impl.SecuredIntentClassifierImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SecuredIntentClassifierImpl Tests")
class SecuredIntentClassifierImplTest {

    @Mock private AzureSearchProperty azureSearchProperty;
    @Mock private UserAccessService userAccessService;
    @Mock private RestClient.Builder restClientBuilder;
    @Mock private RestClient restClient;
    @Mock private ChatClient chatClient;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private SecuredIntentClassifierImpl service;

    @BeforeEach
    void setUp() {
        when(restClientBuilder.build()).thenReturn(restClient);
        
        service = new SecuredIntentClassifierImpl(
                azureSearchProperty,
                userAccessService,
                restClientBuilder,
                objectMapper,
                Map.of(ChatClientConfig.INTENT_CLASSIFIER_CHAT_CLIENT, chatClient)
        );
        service.initialize();
    }

    @Test
    @DisplayName("searchRelevantDocumentsWithSecurity: happy path - returns list of documents")
    void searchRelevantDocumentsWithSecurity_success() {
        // Arrange
        String question = "test question";
        String filter = "folderId eq '1'";
        
        when(azureSearchProperty.getEndpoint()).thenReturn("https://test.search.windows.net");
        when(azureSearchProperty.getApiKey()).thenReturn("key");
        when(azureSearchProperty.getIndexName()).thenReturn("index");
        
        RestClient.RequestBodyUriSpec bodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        
        when(restClient.post()).thenReturn(bodyUriSpec);
        when(bodyUriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.body(any(Map.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        
        Map<String, Object> mockResponse = Map.of(
                "value", List.of(
                        Map.of("source", "doc1.pdf", "content", "content 1", "score", 0.9)
                )
        );
        when(responseSpec.body(Map.class)).thenReturn(mockResponse);

        // Act
        List<SearchResultDocument> result = service.searchRelevantDocumentsWithSecurity(question, filter, 5);

        // Assert
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSource()).isEqualTo("doc1.pdf");
        verify(restClient).post();
    }

    @Test
    @DisplayName("classifyIntent: happy path - returns classification")
    void classifyIntent_success() {
        // Arrange
        String question = "What is the policy?";
        String username = "user";
        List<SearchResultDocument> docs = Collections.emptyList();
        
        mockChatClientResponse("{\"intent\": \"document\", \"confidence\": 0.95, \"reasoning\": \"Asking about policy\"}");

        // Act
        IntentClassification result = service.classifyIntent(question, username, docs);

        // Assert
        assertThat(result.getIntent()).isEqualTo("document");
        assertThat(result.getConfidence()).isEqualTo(0.95);
        assertThat(result.getReasoning()).isEqualTo("Asking about policy");
    }

    @Test
    @DisplayName("classifyIntent: greeting - returns general intent")
    void classifyIntent_greeting() {
        // Arrange
        String question = "Hello";
        
        // Act
        IntentClassification result = service.classifyIntent(question, "user", Collections.emptyList());

        // Assert
        assertThat(result.getIntent()).isEqualTo("general");
        assertThat(result.getReasoning()).isEqualTo("Greeting");
        verify(chatClient, never()).prompt();
    }

    @Test
    @DisplayName("classifyIntent: malformed LLM response - fallbacks to document intent")
    void classifyIntent_malformedFallback() {
        // Arrange
        mockChatClientResponse("invalid json");

        // Act
        IntentClassification result = service.classifyIntent("query", "user", Collections.emptyList());

        // Assert
        assertThat(result.getIntent()).isEqualTo("document");
        assertThat(result.getConfidence()).isEqualTo(0.3);
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
    }
}
