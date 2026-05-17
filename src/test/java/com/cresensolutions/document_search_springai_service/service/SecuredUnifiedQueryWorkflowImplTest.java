package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.commons.Common;
import com.cresensolutions.document_search_springai_service.config.ChatClientConfig;
import com.cresensolutions.document_search_springai_service.dto.DocumentAnswer;
import com.cresensolutions.document_search_springai_service.dto.IntentClassification;
import com.cresensolutions.document_search_springai_service.dto.SearchResultDocument;
import com.cresensolutions.document_search_springai_service.dto.SqlExecutionResult;
import com.cresensolutions.document_search_springai_service.dto.SqlGenerationResult;
import com.cresensolutions.document_search_springai_service.service.Impl.SecuredUnifiedQueryWorkflowImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.task.SyncTaskExecutor;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SecuredUnifiedQueryWorkflowImpl Tests")
class SecuredUnifiedQueryWorkflowImplTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock StandaloneQueryService standaloneQueryService;
    @Mock SecuredIntentClassifier securedIntentClassifier;
    @Mock SecuredRagPipeline securedRagPipeline;
    @Mock FlatSourceClassifier flatSourceClassifier;
    @Mock SqlConverterService sqlConverterService;
    @Mock SqlExecutorService sqlExecutorService;
    @Mock ResultsToNlpService resultsToNlpService;
    @Mock SchemaRegistryService schemaRegistryService;
    @Mock SemanticRankerService semanticRankerService;
    @Mock UserAccessService userAccessService;
    @Mock CommonResponseService commonResponseService;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) ChatClient chatClient;

    SyncTaskExecutor taskExecutor = new SyncTaskExecutor();
    SecuredUnifiedQueryWorkflowImpl service;

    @BeforeEach
    void setUp() {
        Map<String, ChatClient> chatClients = Map.of(
                ChatClientConfig.GENERAL_CHAT_CLIENT, chatClient
        );
        service = new SecuredUnifiedQueryWorkflowImpl(
                standaloneQueryService, securedIntentClassifier, securedRagPipeline,
                flatSourceClassifier, sqlConverterService, sqlExecutorService,
                resultsToNlpService, schemaRegistryService, semanticRankerService,
                chatClients, userAccessService, commonResponseService, taskExecutor
        );
    }

    private IntentClassification classificationFor(String intent) {
        IntentClassification ic = new IntentClassification();
        ic.setIntent(intent);
        ic.setResponseType(Common.RESPONSE_TYPE_NLP_SUMMARY);
        ic.setConfidence(0.9);
        ic.setReasoning("test");
        if (Common.INTENT_DOCUMENT.equals(intent)) {
            ic.setPrefetchedDocs(List.of(SearchResultDocument.builder().source("test.pdf").build()));
        } else {
            ic.setPrefetchedDocs(List.of());
        }
        return ic;
    }

    private void stubPhase0And1(String intent) {
        when(standaloneQueryService.createStandaloneQuery(anyString(), anyString())).thenReturn("standalone q");
        when(userAccessService.createSearchFilter(anyString())).thenReturn("filter");
        when(securedIntentClassifier.searchRelevantDocumentsWithSecurity(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(securedIntentClassifier.classifyIntent(anyString(), anyString(), anyList()))
                .thenReturn(classificationFor(intent));
        when(commonResponseService.getCommonResponse(anyString())).thenReturn(java.util.Optional.empty());
    }

    @Test
    @DisplayName("processQuestion: routes to document RAG path when intent is DOCUMENT")
    void processQuestion_documentPath() {
        stubPhase0And1(Common.INTENT_DOCUMENT);
        DocumentAnswer docAnswer = new DocumentAnswer("RAG answer", Map.of());
        when(securedRagPipeline.answerQuestionWithSecurity(anyString(), anyString(), anyList(), anyString(), anyInt(), any(java.util.UUID.class)))
                .thenReturn(docAnswer);

        Map<String, Object> result = service.processQuestion("q", "user", "", "conv1", 1, USER_ID);

        assertThat(result.get(Common.RESULT_NLP_ANSWER)).isEqualTo("RAG answer");
        assertThat(result.get(Common.RESULT_INTERNAL_TYPE)).isEqualTo(Common.TEXT_RESPONSE_TYPE);
        assertThat(result.get(Common.WORKFLOW)).isEqualTo(Common.WORKFLOW_DOCUMENT);
    }

    @Test
    @DisplayName("processQuestion: routes to general chat path when intent is GENERAL")
    void processQuestion_generalPath() {
        stubPhase0And1(Common.INTENT_GENERAL);
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn("Hello!");

        Map<String, Object> result = service.processQuestion("hi", "user", "", "conv1", 1, USER_ID);

        assertThat(result.get(Common.RESULT_NLP_ANSWER)).isEqualTo("Hello!");
        assertThat(result.get(Common.WORKFLOW)).isEqualTo(Common.WORKFLOW_GENERAL);
    }

    @Test
    @DisplayName("processQuestion: general path falls back when LLM returns null")
    void processQuestion_generalPath_llmNull() {
        stubPhase0And1(Common.INTENT_GENERAL);
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn(null);

        Map<String, Object> result = service.processQuestion("hi", "user", "", "conv1", 1, USER_ID);
        assertThat(result.get(Common.RESULT_NLP_ANSWER)).isEqualTo(Common.GENERAL_GREETING_RESPONSE);
    }

    @Test
    @DisplayName("processQuestion: general path falls back when LLM throws")
    void processQuestion_generalPath_llmThrows() {
        stubPhase0And1(Common.INTENT_GENERAL);
        when(chatClient.prompt().user(anyString()).call().content()).thenThrow(new RuntimeException("error"));

        Map<String, Object> result = service.processQuestion("hi", "user", "", "conv1", 1, USER_ID);
        assertThat(result.get(Common.RESULT_NLP_ANSWER)).isEqualTo(Common.GENERAL_GREETING_RESPONSE);
    }

    @Test
    @DisplayName("processQuestion: database path - nlp_summary success")
    void processQuestion_databasePath_nlpSummary() {
        stubPhase0And1(Common.INTENT_DATABASE);
        when(flatSourceClassifier.classify(anyString())).thenReturn("schema.orders");
        when(schemaRegistryService.getViewRoutingMetadata()).thenReturn(Map.of("schema.orders", Map.of()));
        when(semanticRankerService.rankCategoricalValues(anyString(), anyString(), any())).thenReturn(Map.of());

        SqlGenerationResult sqlResult = SqlGenerationResult.builder()
                .primarySql("SELECT * FROM schema.orders LIMIT 15")
                .isClarification(false)
                .selectedColumns(List.of())
                .build();
        when(sqlConverterService.generateSql(anyString(), anyString(), anyString(), any())).thenReturn(sqlResult);

        SqlExecutionResult execResult = SqlExecutionResult.builder()
                .success(true).rows(List.of(Map.of("id", 1))).rowCount(1).build();
        when(sqlExecutorService.execute(anyString())).thenReturn(execResult);
        when(resultsToNlpService.generateNlpAnswer(anyString(), anyList(), anyBoolean())).thenReturn("Found 1 order");

        Map<String, Object> result = service.processQuestion("show orders", "user", "", "conv1", 1, USER_ID);

        assertThat(result.get(Common.RESULT_NLP_ANSWER)).isEqualTo("Found 1 order");
        assertThat(result.get(Common.WORKFLOW)).isEqualTo(Common.WORKFLOW_DATABASE);
    }

    @Test
    @DisplayName("processQuestion: database path - returns error when no view found")
    void processQuestion_databasePath_noView() {
        stubPhase0And1(Common.INTENT_DATABASE);
        when(flatSourceClassifier.classify(anyString())).thenReturn(null);

        Map<String, Object> result = service.processQuestion("q", "user", "", "conv1", 1, USER_ID);

        assertThat(result.get(Common.RESULT_SUCCESS)).isEqualTo(false);
        assertThat(result.get(Common.RESULT_NLP_ANSWER).toString()).contains("No database view");
    }

    @Test
    @DisplayName("processQuestion: database path - clarification short-circuit")
    void processQuestion_databasePath_clarification() {
        stubPhase0And1(Common.INTENT_DATABASE);
        when(flatSourceClassifier.classify(anyString())).thenReturn("schema.orders");
        when(schemaRegistryService.getViewRoutingMetadata()).thenReturn(Map.of("schema.orders", Map.of()));
        when(semanticRankerService.rankCategoricalValues(anyString(), anyString(), any())).thenReturn(Map.of());

        SqlGenerationResult sqlResult = SqlGenerationResult.builder()
                .isClarification(true)
                .explanation("Do you mean today or this week?")
                .build();
        when(sqlConverterService.generateSql(anyString(), anyString(), anyString(), any())).thenReturn(sqlResult);

        Map<String, Object> result = service.processQuestion("orders", "user", "", "conv1", 1, USER_ID);
        assertThat(result.get(Common.RESULT_NLP_ANSWER)).isEqualTo("Do you mean today or this week?");
    }

    @Test
    @DisplayName("processQuestion: database path - SQL execution failure")
    void processQuestion_databasePath_sqlExecFails() {
        stubPhase0And1(Common.INTENT_DATABASE);
        when(flatSourceClassifier.classify(anyString())).thenReturn("schema.orders");
        when(schemaRegistryService.getViewRoutingMetadata()).thenReturn(Map.of("schema.orders", Map.of()));
        when(semanticRankerService.rankCategoricalValues(anyString(), anyString(), any())).thenReturn(Map.of());

        SqlGenerationResult sqlResult = SqlGenerationResult.builder()
                .primarySql("SELECT * FROM orders")
                .isClarification(false).selectedColumns(List.of()).build();
        when(sqlConverterService.generateSql(anyString(), anyString(), anyString(), any())).thenReturn(sqlResult);

        when(sqlExecutorService.execute(anyString()))
                .thenReturn(SqlExecutionResult.failure("syntax error"));

        Map<String, Object> result = service.processQuestion("q", "user", "", "conv1", 1, USER_ID);
        assertThat(result.get(Common.RESULT_SUCCESS)).isEqualTo(false);
    }

    @Test
    @DisplayName("processQuestion: database path - detailed_records type")
    void processQuestion_databasePath_detailedRecords() {
        IntentClassification ic = classificationFor(Common.INTENT_DATABASE);
        ic.setResponseType(Common.RESPONSE_TYPE_DETAILED_RECORDS);
        ic.setPrefetchedDocs(List.of()); // Database intent doesn't need docs
        when(standaloneQueryService.createStandaloneQuery(anyString(), anyString())).thenReturn("q");
        when(userAccessService.createSearchFilter(anyString())).thenReturn("filter");
        when(securedIntentClassifier.searchRelevantDocumentsWithSecurity(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(securedIntentClassifier.classifyIntent(anyString(), anyString(), anyList())).thenReturn(ic);

        when(flatSourceClassifier.classify(anyString())).thenReturn("schema.orders");
        when(schemaRegistryService.getViewRoutingMetadata()).thenReturn(Map.of("schema.orders", Map.of()));
        when(semanticRankerService.rankCategoricalValues(anyString(), anyString(), any())).thenReturn(Map.of());

        SqlGenerationResult sqlResult = SqlGenerationResult.builder()
                .primarySql("SELECT * FROM orders LIMIT 200")
                .isClarification(false).selectedColumns(List.of()).build();
        when(sqlConverterService.generateSql(anyString(), anyString(), anyString(), any())).thenReturn(sqlResult);

        SqlExecutionResult execResult = SqlExecutionResult.builder()
                .success(true).rows(List.of(Map.of("id", 1))).rowCount(1).build();
        when(sqlExecutorService.execute(anyString())).thenReturn(execResult);
        when(resultsToNlpService.generateTableIntro(anyString(), anyInt())).thenReturn("Here are the records.");

        Map<String, Object> result = service.processQuestion("list all", "user", "", "conv1", 1, USER_ID);
        assertThat(result.get(Common.RESULT_INTERNAL_TYPE)).isEqualTo(Common.TABLE_RESPONSE_TYPE);
        assertThat(result.get(Common.RESULT_TEXT_PAYLOAD)).isEqualTo("Here are the records.");
    }

    @Test
    @DisplayName("processQuestion: hardcoded common response bypasses all LLM logic")
    void processQuestion_commonResponseBypass() {
        when(commonResponseService.getCommonResponse("hi")).thenReturn(java.util.Optional.of("Hello!"));

        Map<String, Object> result = service.processQuestion("hi", "user", "", "conv1", 1, USER_ID);

        assertThat(result.get(Common.RESULT_NLP_ANSWER)).isEqualTo("Hello!");
        assertThat(result.get(Common.WORKFLOW)).isEqualTo("hardcoded_common_response");
        verify(standaloneQueryService, never()).createStandaloneQuery(anyString(), anyString());
        verify(securedIntentClassifier, never()).classifyIntent(anyString(), anyString(), anyList());
    }
}
