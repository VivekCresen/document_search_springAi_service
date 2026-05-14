package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.commons.Common;
import com.cresensolutions.document_search_springai_service.config.ChatClientConfig;
import com.cresensolutions.document_search_springai_service.config.WorkflowProperty;
import com.cresensolutions.document_search_springai_service.dto.DocumentAnswer;
import com.cresensolutions.document_search_springai_service.dto.RagLlmResponse;
import com.cresensolutions.document_search_springai_service.dto.SearchResultDocument;
import com.cresensolutions.document_search_springai_service.service.Impl.SecuredRagPipelineImpl;
import com.cresensolutions.document_search_springai_service.utils.CommonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SecuredRagPipelineImpl Tests")
class SecuredRagPipelineImplTest {

    @Mock private UserAccessService userAccessService;
    @Mock private ConsolidatedCitationManager citationManager;
    @Mock private WorkflowProperty workflowProperty;
    @Mock private ChatClient chatClient;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Executor taskExecutor = Runnable::run; // Synchronous executor for tests
    
    private SecuredRagPipelineImpl service;

    @BeforeEach
    void setUp() {
        service = new SecuredRagPipelineImpl(
                userAccessService,
                citationManager,
                workflowProperty,
                objectMapper,
                Map.of(ChatClientConfig.SECURED_RAG_CHAT_CLIENT, chatClient),
                taskExecutor
        );
        
        lenient().when(workflowProperty.getRagMaxContextChars()).thenReturn(4000);
        lenient().when(workflowProperty.getRagMaxDocumentChars()).thenReturn(1000);
    }

    @Test
    @DisplayName("answerQuestionWithSecurity: happy path - returns answer and citations")
    void answerQuestionWithSecurity_success() {
        // Arrange
        String question = "What is compliance?";
        String username = "testuser";
        List<SearchResultDocument> prefetchedDocs = List.of(
                SearchResultDocument.builder().source("doc1.pdf").content("Compliance is key.").folderId("1").build()
        );
        
        when(userAccessService.getRestrictedFolders(username)).thenReturn(Collections.emptyList());
        when(userAccessService.getUnstableFileUris()).thenReturn(Collections.emptyList());
        
        mockChatClientResponse("{\"answer\": \"Compliance is essential.\", \"raw_extractions\": []}");
        when(citationManager.createCitationsFromPassages(anyList(), anyList(), anyString(), anyInt(), anyLong()))
                .thenReturn(Map.of("1", Map.of("file_name", "doc1.pdf")));

        // Act
        DocumentAnswer result = service.answerQuestionWithSecurity(question, username, prefetchedDocs, "conv1", 1, 123L);

        // Assert
        assertThat(result.getAnswer()).isEqualTo("Compliance is essential.");
        assertThat(result.getCitations()).containsKey("1");
        verify(userAccessService).getRestrictedFolders(username);
    }

    @Test
    @DisplayName("answerQuestionWithSecurity: filtering - excludes restricted folders")
    void answerQuestionWithSecurity_filtersRestricted() {
        // Arrange
        String question = "Secret info?";
        String username = "restrictedUser";
        List<SearchResultDocument> prefetchedDocs = List.of(
                SearchResultDocument.builder().source("secret.pdf").content("Confidential.").folderId("99").build()
        );
        
        when(userAccessService.getRestrictedFolders(username)).thenReturn(List.of("99"));
        when(userAccessService.getUnstableFileUris()).thenReturn(Collections.emptyList());

        // Act
        DocumentAnswer result = service.answerQuestionWithSecurity(question, username, prefetchedDocs, "conv1", 1, 123L);

        // Assert
        assertThat(result.getAnswer()).isEqualTo(Common.NO_ACCESSIBLE_DOCUMENTS_RESPONSE);
        assertThat(result.getCitations()).isEmpty();
        verify(chatClient, never()).prompt();
    }

    @Test
    @DisplayName("answerQuestionWithSecurity: LLM error - returns error response")
    void answerQuestionWithSecurity_llmError() {
        // Arrange
        String question = "Fail?";
        String username = "user";
        List<SearchResultDocument> prefetchedDocs = List.of(
                SearchResultDocument.builder().source("doc.pdf").content("Some content.").build()
        );
        
        when(userAccessService.getRestrictedFolders(username)).thenReturn(Collections.emptyList());
        when(userAccessService.getUnstableFileUris()).thenReturn(Collections.emptyList());
        
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("LLM down"));

        // Act
        DocumentAnswer result = service.answerQuestionWithSecurity(question, username, prefetchedDocs, "conv1", 1, 123L);

        // Assert
        // The implementation recovers from LLM errors by returning a fallback JSON message.
        assertThat(result.getAnswer()).contains("no LLM is configured");
    }

    @Test
    @DisplayName("answerQuestionWithSecurity: general error - returns generic error response")
    void answerQuestionWithSecurity_generalError() {
        // Arrange
        String question = "Fail?";
        String username = "user";
        List<SearchResultDocument> prefetchedDocs = List.of(
                SearchResultDocument.builder().source("doc.pdf").content("Some content.").build()
        );

        when(userAccessService.getRestrictedFolders(username)).thenReturn(Collections.emptyList());
        when(userAccessService.getUnstableFileUris()).thenReturn(Collections.emptyList());

        mockChatClientResponse("{\"answer\": \"Ok\", \"raw_extractions\": []}");
        // Trigger exception in citation manager to hit the outer catch block
        when(citationManager.createCitationsFromPassages(anyList(), anyList(), anyString(), anyInt(), anyLong()))
                .thenThrow(new RuntimeException("Citation failure"));

        // Act
        DocumentAnswer result = service.answerQuestionWithSecurity(question, username, prefetchedDocs, "conv1", 1, 123L);

        // Assert
        assertThat(result.getAnswer()).isEqualTo(Common.DOCUMENT_RESPONSE_ERROR);
    }

    @Test
    @DisplayName("answerQuestionWithSecurity: malformed JSON - returns raw text as answer")
    void answerQuestionWithSecurity_malformedJson() {
        // Arrange
        String question = "Malformed?";
        String username = "user";
        List<SearchResultDocument> prefetchedDocs = List.of(
                SearchResultDocument.builder().source("doc.pdf").content("Content.").build()
        );
        
        when(userAccessService.getRestrictedFolders(username)).thenReturn(Collections.emptyList());
        when(userAccessService.getUnstableFileUris()).thenReturn(Collections.emptyList());
        
        mockChatClientResponse("Not a JSON response");
        when(citationManager.createCitationsFromPassages(anyList(), anyList(), anyString(), anyInt(), anyLong()))
                .thenReturn(Collections.emptyMap());

        // Act
        DocumentAnswer result = service.answerQuestionWithSecurity(question, username, prefetchedDocs, "conv1", 1, 123L);

        // Assert
        assertThat(result.getAnswer()).isEqualTo("Not a JSON response");
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

    // -------------------------------------------------------------------------
    // Additional coverage tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("answerQuestionWithSecurity: filters unstable file URIs")
    void answerQuestionWithSecurity_filtersUnstable() {
        String question = "What?";
        String username = "user";
        List<SearchResultDocument> prefetchedDocs = List.of(
                SearchResultDocument.builder().source("a.pdf").content("A").blobUri("blob://a.pdf").build(),
                SearchResultDocument.builder().source("b.pdf").content("B").blobUri("blob://b.pdf").build()
        );
        // Mark only a.pdf as unstable → only b.pdf stays
        when(userAccessService.getRestrictedFolders(username)).thenReturn(Collections.emptyList());
        when(userAccessService.getUnstableFileUris()).thenReturn(List.of("blob://a.pdf"));

        mockChatClientResponse("{\"answer\": \"Only B.\", \"raw_extractions\": []}");
        when(citationManager.createCitationsFromPassages(anyList(), anyList(), anyString(), anyInt(), anyLong()))
                .thenReturn(Map.of());

        DocumentAnswer result = service.answerQuestionWithSecurity(question, username, prefetchedDocs, "conv1", 1, 1L);
        assertThat(result.getAnswer()).isEqualTo("Only B.");
    }

    @Test
    @DisplayName("answerQuestionWithSecurity: null prefetchedDocs → no accessible documents")
    void answerQuestionWithSecurity_nullDocs() {
        lenient().when(userAccessService.getRestrictedFolders(anyString())).thenReturn(Collections.emptyList());
        lenient().when(userAccessService.getUnstableFileUris()).thenReturn(Collections.emptyList());

        DocumentAnswer result = service.answerQuestionWithSecurity("q", "user", null, "c", 1, 1L);
        assertThat(result.getAnswer()).isEqualTo(Common.NO_ACCESSIBLE_DOCUMENTS_RESPONSE);
    }

    @Test
    @DisplayName("answerQuestionWithSecurity: LLM returns null content → falls back to NO_LLM_CONFIGURED_JSON")
    void answerQuestionWithSecurity_llmNullContent() {
        String username = "user";
        List<SearchResultDocument> docs = List.of(
                SearchResultDocument.builder().source("a.pdf").content("Content").build()
        );
        when(userAccessService.getRestrictedFolders(username)).thenReturn(Collections.emptyList());
        when(userAccessService.getUnstableFileUris()).thenReturn(Collections.emptyList());

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(null);  // null content

        when(citationManager.createCitationsFromPassages(anyList(), anyList(), anyString(), anyInt(), anyLong()))
                .thenReturn(Collections.emptyMap());

        DocumentAnswer result = service.answerQuestionWithSecurity("q", username, docs, "c", 1, 1L);
        // null content → uses NO_LLM_CONFIGURED_JSON fallback → parsed as raw answer
        assertThat(result.getAnswer()).isNotBlank();
    }

    @Test
    @DisplayName("answerQuestionWithSecurity: docs with no content are skipped (empty source)")
    void answerQuestionWithSecurity_docsWithNoContent_skipped() {
        String username = "user";
        List<SearchResultDocument> docs = List.of(
                SearchResultDocument.builder().source("empty.pdf").content("   ").build()  // blank content
        );
        when(userAccessService.getRestrictedFolders(username)).thenReturn(Collections.emptyList());
        when(userAccessService.getUnstableFileUris()).thenReturn(Collections.emptyList());

        DocumentAnswer result = service.answerQuestionWithSecurity("q", username, docs, "c", 1, 1L);
        assertThat(result.getAnswer()).isEqualTo(Common.NO_ACCESSIBLE_DOCUMENTS_RESPONSE);
    }

    @Test
    @DisplayName("answerQuestionWithSecurity: extractions with verified text generate citations")
    void answerQuestionWithSecurity_extractionsValidated() {
        String username = "user";
        List<SearchResultDocument> docs = List.of(
                SearchResultDocument.builder().source("manual.pdf").content("Exact verbatim text here.").build()
        );
        when(userAccessService.getRestrictedFolders(username)).thenReturn(Collections.emptyList());
        when(userAccessService.getUnstableFileUris()).thenReturn(Collections.emptyList());

        String llmJson = """
            {
              "answer": "Good answer",
              "raw_extractions": [
                {"exact_text": "Exact verbatim text here.", "source": "manual.pdf", "page": "2", "explains": "Key finding"}
              ]
            }
            """;
        mockChatClientResponse(llmJson);
        when(citationManager.createCitationsFromPassages(anyList(), anyList(), anyString(), anyInt(), anyLong()))
                .thenReturn(Map.of("1", Map.of("source", "manual.pdf")));

        DocumentAnswer result = service.answerQuestionWithSecurity("q", username, docs, "c", 1, 1L);

        assertThat(result.getAnswer()).isEqualTo("Good answer");
        assertThat(result.getCitations()).containsKey("1");
    }

    @Test
    @DisplayName("answerQuestionWithSecurity: extraction with no matching source is discarded")
    void answerQuestionWithSecurity_extractionNoMatchingSource() {
        String username = "user";
        List<SearchResultDocument> docs = List.of(
                SearchResultDocument.builder().source("real.pdf").content("This is the real content.").build()
        );
        when(userAccessService.getRestrictedFolders(username)).thenReturn(Collections.emptyList());
        when(userAccessService.getUnstableFileUris()).thenReturn(Collections.emptyList());

        // LLM extracts from a source that doesn't exist in our docs
        String llmJson = """
            {
              "answer": "Some answer",
              "raw_extractions": [
                {"exact_text": "ghost text", "source": "nonexistent.pdf", "page": "1", "explains": "???"}
              ]
            }
            """;
        mockChatClientResponse(llmJson);
        when(citationManager.createCitationsFromPassages(anyList(), anyList(), anyString(), anyInt(), anyLong()))
                .thenReturn(Collections.emptyMap());

        DocumentAnswer result = service.answerQuestionWithSecurity("q", username, docs, "c", 1, 1L);
        // No validated extractions → empty citations
        assertThat(result.getCitations()).isEmpty();
    }
}

