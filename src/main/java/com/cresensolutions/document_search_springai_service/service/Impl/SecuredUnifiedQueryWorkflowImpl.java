package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.commons.Common;
import com.cresensolutions.document_search_springai_service.config.ChatClientConfig;
import com.cresensolutions.document_search_springai_service.dto.DocumentAnswer;
import com.cresensolutions.document_search_springai_service.dto.IntentClassification;
import com.cresensolutions.document_search_springai_service.dto.SearchResultDocument;
import com.cresensolutions.document_search_springai_service.dto.SqlExecutionResult;
import com.cresensolutions.document_search_springai_service.dto.SqlGenerationResult;
import com.cresensolutions.document_search_springai_service.service.CommonResponseService;
import com.cresensolutions.document_search_springai_service.service.FlatSourceClassifier;
import com.cresensolutions.document_search_springai_service.service.ResultsToNlpService;
import com.cresensolutions.document_search_springai_service.service.SecuredIntentClassifier;
import com.cresensolutions.document_search_springai_service.service.SecuredRagPipeline;
import com.cresensolutions.document_search_springai_service.service.SecuredUnifiedQueryWorkflow;
import com.cresensolutions.document_search_springai_service.service.SchemaRegistryService;
import com.cresensolutions.document_search_springai_service.service.SemanticRankerService;
import com.cresensolutions.document_search_springai_service.service.SqlConverterService;
import com.cresensolutions.document_search_springai_service.service.SqlExecutorService;
import com.cresensolutions.document_search_springai_service.service.StandaloneQueryService;
import com.cresensolutions.document_search_springai_service.utils.CommonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the Secured Unified Query Workflow.
 * 
 * This service orchestrates the multi-phase workflow (Phase 0 to 4):
 * Phase 0: Standalone Query Generation (context-aware).
 * Phase 1: Security-aware Document Pre-fetching.
 * Phase 2: Intent Classification (Document vs Database vs General).
 * Phase 3: Routing to the appropriate sub-pipeline (RAG, SQL, or General Chat).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SecuredUnifiedQueryWorkflowImpl implements SecuredUnifiedQueryWorkflow {

    private static final int NLP_MAX_ROWS = 15;

    private final StandaloneQueryService standaloneQueryService;
    private final SecuredIntentClassifier securedIntentClassifier;
    private final SecuredRagPipeline securedRagPipeline;
    private final FlatSourceClassifier flatSourceClassifier;
    private final SqlConverterService sqlConverterService;
    private final SqlExecutorService sqlExecutorService;
    private final ResultsToNlpService resultsToNlpService;
    private final SchemaRegistryService schemaRegistryService;
    private final SemanticRankerService semanticRankerService;
    private final Map<String, ChatClient> chatClients;
    private final com.cresensolutions.document_search_springai_service.service.UserAccessService userAccessService;
    private final CommonResponseService commonResponseService;
    @org.springframework.beans.factory.annotation.Qualifier(Common.TASK_EXECUTOR)
    private final java.util.concurrent.Executor taskExecutor;

    /**
     * Primary entry point for processing a user question through the unified workflow.
     * 
     * @param question             The user's original question.
     * @param username             Requester's username (for security filtering).
     * @param conversationContext  Chat history context to resolve references (e.g., "it", "them").
     * @param conversationId       Unique ID for the conversation.
     * @param questionId           Unique ID for the question.
     * @param userId               Unique ID for the user.
     * @return A map containing the structured response, including answers, citations, or data payloads.
     */
    @Override
    public Map<String, Object> processQuestion(
            String question,
            String username,
            String conversationContext,
            String conversationId,
            Integer questionId,
            java.util.UUID userId
    ) {
        // Step 0: Check for hardcoded common responses to save tokens and time
        java.util.Optional<String> commonAnswer = commonResponseService.getCommonResponse(question);
        if (commonAnswer.isPresent()) {
            log.info("Handled common question '{}' without LLM", question);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put(Common.RESULT_SUCCESS, true);
            result.put(Common.WORKFLOW, "hardcoded_common_response");
            result.put(Common.RESULT_ORIGINAL_QUESTION, question);
            result.put(Common.RESULT_INTENT, Common.INTENT_GENERAL);
            result.put(Common.RESULT_NLP_ANSWER, commonAnswer.get());
            result.put(Common.RESULT_TEXT_PAYLOAD, commonAnswer.get());
            result.put(Common.RESULT_INTERNAL_TYPE, Common.TEXT_RESPONSE_TYPE);
            result.put(Common.RESULT_CITATIONS, Collections.emptyMap());
            result.put(Common.RESULT_CONVERSATION_ID, conversationId);
            result.put(Common.RESULT_QUESTION_ID, questionId);
            return result;
        }

        // Phase 0: Generate a standalone query in parallel with security filter retrieval
        java.util.concurrent.CompletableFuture<String> standaloneQueryFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> standaloneQueryService.createStandaloneQuery(question, conversationContext),
                taskExecutor
        );

        java.util.concurrent.CompletableFuture<String> searchFilterFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> userAccessService.createSearchFilter(username),
                taskExecutor
        );

        String standaloneQuery = standaloneQueryFuture.join();
        String searchFilter = searchFilterFuture.join();

        // Phase 1: Security-aware pre-fetch of documents (metadata search)
        List<SearchResultDocument> prefetchedDocs = securedIntentClassifier.searchRelevantDocumentsWithSecurity(
                standaloneQuery,
                searchFilter,
                10
        );
        
        // Phase 2: Classify intent based on the query and prefetched document metadata
        IntentClassification classification = securedIntentClassifier.classifyIntent(
                standaloneQuery,
                username,
                prefetchedDocs
        );

        // LinkedHashMap keeps response fields in a predictable order for clients and logs.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(Common.RESULT_SUCCESS, true);
        result.put(Common.WORKFLOW, Common.WORKFLOW_PHASE_0_TO_4);
        result.put(Common.RESULT_ORIGINAL_QUESTION, question);
        result.put(Common.RESULT_STANDALONE_QUERY, standaloneQuery);
        result.put(Common.RESULT_INTENT, classification.getIntent());
        result.put(Common.RESULT_RESPONSE_TYPE, classification.getResponseType());
        result.put(Common.RESULT_CONFIDENCE, classification.getConfidence());
        result.put(Common.RESULT_REASONING, classification.getReasoning());
        result.put(Common.RESULT_PREFETCHED_DOCS, classification.getPrefetchedDocs());
        result.put(Common.RESULT_CONVERSATION_CONTEXT, conversationContext);
        result.put(Common.RESULT_CONVERSATION_ID, conversationId);
        result.put(Common.RESULT_QUESTION_ID, questionId);
        result.put(Common.RESULT_USER_ID, userId);
        result.put(Common.RESULT_USERNAME, username);

        // Phase 3: Route to the sub-pipeline based on the detected intent
        String effectiveIntent = classification.getIntent();

        // Treat any general intent question that is not a basic greeting as a document query to enforce search rules
        if (Common.INTENT_GENERAL.equals(effectiveIntent) && !isGreeting(standaloneQuery)) {
            effectiveIntent = Common.INTENT_DOCUMENT;
        }

        if (Common.INTENT_DOCUMENT.equals(effectiveIntent)) {
            // Document RAG Path
            DocumentAnswer documentAnswer = securedRagPipeline.answerQuestionWithSecurity(
                    standaloneQuery,
                    username,
                    classification.getPrefetchedDocs(),
                    conversationId,
                    questionId,
                    userId
            );

            // Strict Document-based RAG: never fall back to general knowledge if no documents match
            result.put(Common.WORKFLOW, Common.WORKFLOW_DOCUMENT);
            result.put(Common.RESULT_NLP_ANSWER, documentAnswer.getAnswer());
            result.put(Common.RESULT_TEXT_PAYLOAD, documentAnswer.getAnswer());
            result.put(Common.RESULT_INTERNAL_TYPE, Common.TEXT_RESPONSE_TYPE);
            result.put(Common.RESULT_CITATIONS, documentAnswer.getCitations());
        } else if (Common.INTENT_GENERAL.equals(effectiveIntent)) {
            // General Greeting Conversation Path
            String answer = generateGeneralAnswer(standaloneQuery);
            result.put(Common.WORKFLOW, Common.WORKFLOW_GENERAL);
            result.put(Common.RESULT_INTERNAL_TYPE, Common.TEXT_RESPONSE_TYPE);
            result.put(Common.RESULT_NLP_ANSWER, answer);
            result.put(Common.RESULT_TEXT_PAYLOAD, answer);
            result.put(Common.RESULT_CITATIONS, Map.of());
        } else {
            // Database Query Path
            handleDatabase(standaloneQuery, classification.getResponseType(), result);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Database query path
    // -------------------------------------------------------------------------

    /**
     * Full database query path: classify view → generate SQL → execute → convert to NLP/table.
     * Mirrors Python SecuredUnifiedQueryWorkflow._handle_database().
     */
    private void handleDatabase(String question, String responseType, Map<String, Object> result) {
        result.put(Common.WORKFLOW, Common.WORKFLOW_DATABASE);
        result.put(Common.RESULT_CITATIONS, Map.of());

        String effectiveResponseType = responseType != null ? responseType : Common.RESPONSE_TYPE_NLP_SUMMARY;

        // Step 1: route to the correct DB view
        String viewName = flatSourceClassifier.classify(question);
        result.put("routed_view", viewName);

        if (viewName == null) {
            String msg = "No database view is registered to answer this question.";
            result.put(Common.RESULT_INTERNAL_TYPE, Common.TEXT_RESPONSE_TYPE);
            result.put(Common.RESULT_NLP_ANSWER, msg);
            result.put(Common.RESULT_TEXT_PAYLOAD, msg);
            result.put(Common.RESULT_SUCCESS, false);
            return;
        }

        // Step 1.5: rank categorical values for semantic hints
        Map<String, Object> routingMetadata = schemaRegistryService.getViewRoutingMetadata().get(viewName);
        Map<String, List<String>> semanticHints = semanticRankerService.rankCategoricalValues(question, viewName, routingMetadata);

        // Step 2: generate SQL
        SqlGenerationResult sqlResult = sqlConverterService.generateSql(
                question, effectiveResponseType, viewName, semanticHints);

        // Clarification short-circuit
        if (sqlResult.isClarification()) {
            log.debug("SQL generation returned clarification: {}", sqlResult.explanation());
            result.put(Common.RESULT_INTERNAL_TYPE, Common.TEXT_RESPONSE_TYPE);
            result.put(Common.RESULT_NLP_ANSWER, sqlResult.explanation());
            result.put(Common.RESULT_TEXT_PAYLOAD, sqlResult.explanation());
            result.put(Common.RESULT_SUCCESS, true);
            return;
        }

        if (!CommonUtils.hasText(sqlResult.primarySql())) {
            String msg = sanitizeDbError(sqlResult.explanation());
            result.put(Common.RESULT_INTERNAL_TYPE, Common.TEXT_RESPONSE_TYPE);
            result.put(Common.RESULT_NLP_ANSWER, msg);
            result.put(Common.RESULT_TEXT_PAYLOAD, msg);
            result.put(Common.RESULT_SUCCESS, false);
            return;
        }

        result.put("selected_columns", sqlResult.selectedColumns());

        // Step 3: dispatch by response type
        switch (effectiveResponseType) {
            case Common.RESPONSE_TYPE_HYBRID -> handleHybrid(question, sqlResult, result);
            case Common.RESPONSE_TYPE_DETAILED_RECORDS -> handleDetailedRecords(question, sqlResult.primarySql(), result);
            default -> handleNlpSummary(question, sqlResult.primarySql(), result);
        }
    }

    /**
     * Processes natural language SQL summary requests, falling back to tabular format if the dataset exceeds NLP threshold.
     *
     * @param question natural language user question
     * @param sql target aggregate query SQL
     * @param result output map accumulating results
     */
    private void handleNlpSummary(String question, String sql, Map<String, Object> result) {
        result.put(Common.RESULT_INTERNAL_TYPE, Common.TEXT_RESPONSE_TYPE);
        SqlExecutionResult exec = sqlExecutorService.execute(sql);
        if (!exec.success()) {
            String msg = sanitizeDbError(exec.errorMessage());
            result.put(Common.RESULT_NLP_ANSWER, msg);
            result.put(Common.RESULT_TEXT_PAYLOAD, msg);
            result.put(Common.RESULT_SUCCESS, false);
            return;
        }
        result.put("row_count", exec.rowCount());

        // Fallback to table when result set is too large for NLP
        if (exec.rowCount() > NLP_MAX_ROWS) {
            log.debug("NLP fallback → table (row_count={} > {})", exec.rowCount(), NLP_MAX_ROWS);
            result.put(Common.RESULT_INTERNAL_TYPE, Common.TABLE_RESPONSE_TYPE);
            String intro = resultsToNlpService.generateTableIntro(question, exec.rowCount());
            result.put(Common.RESULT_TEXT_PAYLOAD, intro);
            result.put(Common.RESULT_NLP_ANSWER, intro);
            result.put(Common.RESULT_DATA_PAYLOAD, exec.rows());
            result.put(Common.RESULT_SUCCESS, true);
            return;
        }

        String answer = resultsToNlpService.generateNlpAnswer(question, exec.rows(), false);
        result.put(Common.RESULT_NLP_ANSWER, answer);
        result.put(Common.RESULT_TEXT_PAYLOAD, answer);
        result.put(Common.RESULT_SUCCESS, true);
    }

    /**
     * Executes queries expecting tabular datasets, returning a dynamic introduction and the raw records.
     *
     * @param question natural language user question
     * @param sql target detail query SQL
     * @param result output map accumulating results
     */
    private void handleDetailedRecords(String question, String sql, Map<String, Object> result) {
        result.put(Common.RESULT_INTERNAL_TYPE, Common.TABLE_RESPONSE_TYPE);
        SqlExecutionResult exec = sqlExecutorService.execute(sql);
        if (!exec.success()) {
            String msg = sanitizeDbError(exec.errorMessage());
            result.put(Common.RESULT_NLP_ANSWER, msg);
            result.put(Common.RESULT_TEXT_PAYLOAD, msg);
            result.put(Common.RESULT_SUCCESS, false);
            return;
        }
        result.put("row_count", exec.rowCount());
        String intro = resultsToNlpService.generateTableIntro(question, exec.rowCount());
        result.put(Common.RESULT_TEXT_PAYLOAD, intro);
        result.put(Common.RESULT_NLP_ANSWER, intro);
        result.put(Common.RESULT_DATA_PAYLOAD, exec.rows());
        result.put(Common.RESULT_SUCCESS, true);
    }

    /**
     * Executes both summary and detail SQL queries to compile a hybrid textual summary accompanied by tabular dataset rows.
     *
     * @param question natural language user question
     * @param sqlResult SQL results container
     * @param result output map accumulating results
     */
    private void handleHybrid(String question, SqlGenerationResult sqlResult, Map<String, Object> result) {
        result.put(Common.RESULT_INTERNAL_TYPE, Common.HYBRID_RESPONSE_TYPE);

        // Aggregate SQL for NLP summary
        SqlExecutionResult aggExec = sqlExecutorService.execute(sqlResult.primarySql());
        if (!aggExec.success()) {
            String msg = sanitizeDbError(aggExec.errorMessage());
            result.put(Common.RESULT_NLP_ANSWER, msg);
            result.put(Common.RESULT_TEXT_PAYLOAD, msg);
            result.put(Common.RESULT_SUCCESS, false);
            return;
        }

        // Detail SQL for table
        String detailSql = CommonUtils.hasText(sqlResult.detailSql())
                ? sqlResult.detailSql()
                : sqlResult.primarySql();
        SqlExecutionResult detailExec = sqlExecutorService.execute(detailSql);
        if (!detailExec.success()) {
            String msg = sanitizeDbError(detailExec.errorMessage());
            result.put(Common.RESULT_NLP_ANSWER, msg);
            result.put(Common.RESULT_TEXT_PAYLOAD, msg);
            result.put(Common.RESULT_SUCCESS, false);
            return;
        }

        result.put("row_count", detailExec.rowCount());
        String nlpPart = resultsToNlpService.generateNlpAnswer(question, aggExec.rows(), true);
        result.put(Common.RESULT_NLP_ANSWER, nlpPart + "\n\n[Detailed Records Attached in API Response]");
        result.put(Common.RESULT_TEXT_PAYLOAD, nlpPart);
        result.put(Common.RESULT_DATA_PAYLOAD, detailExec.rows());
        result.put(Common.RESULT_SUCCESS, true);
    }

    /** Maps technical DB errors to user-friendly messages. Mirrors Python sanitize_error_for_user(). */
    private String sanitizeDbError(String errorMessage) {
        if (errorMessage == null) {
            return "An unexpected error occurred.";
        }
        String lower = errorMessage.toLowerCase();
        if (lower.contains("database") || lower.contains("sql") || lower.contains("query")
                || lower.contains("table") || lower.contains("column")
                || lower.contains("postgres") || lower.contains("syntax")) {
            return "We encountered an issue retrieving the requested data.";
        }
        if (lower.contains("search") || lower.contains("index") || lower.contains("azure")) {
            return "We encountered an issue searching for documents.";
        }
        if (lower.contains("timeout") || lower.contains("llm") || lower.contains("openai")) {
            return "We encountered an issue processing your request.";
        }
        return "An unexpected error occurred.";
    }

    // -------------------------------------------------------------------------
    // General conversation path
    // -------------------------------------------------------------------------

    /**
     * Resolves general chit-chat queries using the default general chat client.
     *
     * @param question conversational query
     * @return conversational NLP text response
     */
    private String generateGeneralAnswer(String question) {
        ChatClient chatClient = chatClients.get(ChatClientConfig.GENERAL_CHAT_CLIENT);
        if (chatClient == null) {
            return Common.GENERAL_GREETING_RESPONSE;
        }
        try {
            String answer = chatClient.prompt()
                    .user(question)
                    .call()
                    .content();
            return answer == null || answer.isBlank() ? Common.GENERAL_GREETING_RESPONSE : answer.trim();
        } catch (Exception e) {
            log.warn("Spring AI general response failed; using fallback response", e);
            return Common.GENERAL_GREETING_RESPONSE;
        }
    }

    /**
     * Resolves if the user question is a standard polite greeting.
     *
     * @param text raw question text
     * @return true if matches common greetings
     */
    private boolean isGreeting(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.toLowerCase(java.util.Locale.ROOT).trim();
        return List.of("hello", "hi", "hey", "greetings", "good morning", "good afternoon", "good evening")
                .contains(normalized);
    }
}
