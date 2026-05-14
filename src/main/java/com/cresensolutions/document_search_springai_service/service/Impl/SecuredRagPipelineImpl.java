package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.commons.Common;
import com.cresensolutions.document_search_springai_service.config.ChatClientConfig;
import com.cresensolutions.document_search_springai_service.config.WorkflowProperty;
import com.cresensolutions.document_search_springai_service.dto.DiPageSpan;
import com.cresensolutions.document_search_springai_service.dto.DocumentAnswer;
import com.cresensolutions.document_search_springai_service.dto.LlmExtraction;
import com.cresensolutions.document_search_springai_service.dto.RagSourceDocument;
import com.cresensolutions.document_search_springai_service.dto.RagLlmResponse;
import com.cresensolutions.document_search_springai_service.dto.SearchResultDocument;
import com.cresensolutions.document_search_springai_service.dto.SupportingPassage;
import com.cresensolutions.document_search_springai_service.service.ConsolidatedCitationManager;
import com.cresensolutions.document_search_springai_service.service.SecuredRagPipeline;
import com.cresensolutions.document_search_springai_service.service.UserAccessService;
import com.cresensolutions.document_search_springai_service.utils.CommonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementation of the Secured RAG Pipeline.
 * 
 * This service handles the Retrieval-Augmented Generation (RAG) process with:
 * 1. Security filtering (folders and file stability).
 * 2. Language-locked prompting for multi-lingual support.
 * 3. Verbatim extraction validation against source documents.
 * 4. Coordinate-aware citation consolidation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SecuredRagPipelineImpl implements SecuredRagPipeline {

    private static final String USER_PROMPT = """
            You are an expert compliance assistant for LifeScience.

            LANGUAGE LOCK — THIS OVERRIDES EVERYTHING:
            1. Read the USER QUESTION and identify its language.
            2. Every word of the "answer" and "explains" fields MUST be in that SAME language.
            3. Context documents may be in a different language — translate your answer to the user's language.
            4. Do NOT translate "exact_text" extractions; keep those verbatim in their original language.

            ===== CONTEXT DOCUMENTS =====
            %s

            ===== USER QUESTION =====
            %s

            ===== TASK =====
            PART 1 — YOUR ANSWER:
            - Write the "answer" field 100%% in the detected user language.
            - Synthesize and paraphrase naturally for clarity.

            PART 2 — EXACT EXTRACTIONS:
            - Copy "exact_text" VERBATIM from the CONTEXT DOCUMENTS — word-for-word.
            - Do NOT paraphrase or translate "exact_text".
            - Write "explains" in the same language as the USER QUESTION.

            ===== RESPONSE FORMAT (valid JSON only) =====
            {
                "answer": "Your paraphrased answer in user language",
                "raw_extractions": [
                    {
                        "exact_text": "Verbatim text from source",
                        "source": "filename.pdf",
                        "page": "page_number",
                        "explains": "Brief explanation in user language"
                    }
                ]
            }

            SELF-CHECK:
            - Is the answer in the same language as the question? (Must be YES)
            - Is "exact_text" copied verbatim without translation? (Must be YES)
            - Is the response valid JSON? (Must be YES)
            """;

    private final UserAccessService userAccessService;
    private final ConsolidatedCitationManager citationManager;
    private final WorkflowProperty workflowProperty;
    private final ObjectMapper objectMapper;
    private final Map<String, ChatClient> chatClients;
    @Qualifier(Common.TASK_EXECUTOR)
    private final Executor taskExecutor;

    /**
     * Primary entry point for answering a question using the secured RAG pipeline.
     * 
     * @param question        The user's original question.
     * @param username        The username of the requester for permission checking.
     * @param prefetchedDocs  Optional list of documents already retrieved from the index.
     * @param conversationId  ID of the current conversation (for citation metadata).
     * @param questionId      ID of the current question (for citation metadata).
     * @param userId          ID of the user (for citation metadata).
     * @return A DocumentAnswer containing the LLM-generated response and citations.
     */
    @Override
    public DocumentAnswer answerQuestionWithSecurity(
            String question,
            String username,
            List<SearchResultDocument> prefetchedDocs,
            String conversationId,
            Integer questionId,
            java.util.UUID userId
    ) {
        // 1. Apply folder-level and file-stability filters to ensure user only sees what they should
        List<SearchResultDocument> filteredDocs = filterSearchResults(username, prefetchedDocs);
        
        // 2. Map filtered results to RAG source documents with necessary metadata
        List<RagSourceDocument> sourceDocuments = getSourceDocuments(filteredDocs);

        // 3. Handle case where no documents are accessible after security filtering
        if (sourceDocuments.isEmpty()) {
            return DocumentAnswer.builder()
                    .answer(Common.NO_ACCESSIBLE_DOCUMENTS_RESPONSE)
                    .citations(Map.of())
                    .build();
        }

        // 4. Build the context string and format the full LLM prompt
        String context = buildContext(sourceDocuments);
        String prompt = USER_PROMPT.formatted(context, question);

        try {
            // 5. Invoke the LLM (Spring AI ChatClient) to generate a response in JSON format
            String rawResponse = invokeLlm(prompt);
            
            // 6. Parse the JSON response from the LLM into a structured DTO
            RagLlmResponse parsed = parseLlmResponse(rawResponse);
            String answer = CommonUtils.hasText(parsed.getAnswer()) ? parsed.getAnswer() : rawResponse;
            
            // 7. Validate that LLM-claimed extractions actually exist in the source text (hallucination check)
            List<ValidatedExtraction> validated = validateExtractions(parsed.getRawExtractions(), sourceDocuments);
            
            // 8. Consolidate validated extractions by PDF source to prevent redundant highlights
            List<SupportingPassage> consolidated = consolidateByPdf(validated);
            
            // 9. Generate citations with secure, time-limited highlighted PDF links
            Map<String, Object> citations = citationManager.createCitationsFromPassages(
                    consolidated,
                    sourceDocuments,
                    conversationId,
                    questionId,
                    userId
            );

            return DocumentAnswer.builder()
                    .answer(answer)
                    .citations(citations)
                    .build();
        } catch (Exception e) {
            log.warn("Secured RAG pipeline failed: {}", e.getMessage());
            return DocumentAnswer.builder()
                    .answer(Common.DOCUMENT_RESPONSE_ERROR)
                    .citations(Map.of())
                    .build();
        }
    }

    /**
     * Filters search results based on user's restricted folders and file stability state.
     */
    private List<SearchResultDocument> filterSearchResults(String username, List<SearchResultDocument> docs) {
        if (docs == null || docs.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Fetch restrictions in parallel
        CompletableFuture<Set<String>> restrictedFoldersFuture = CompletableFuture.supplyAsync(
                () -> Set.copyOf(userAccessService.getRestrictedFolders(username)),
                taskExecutor
        );
        CompletableFuture<Set<String>> unstableUrisFuture = CompletableFuture.supplyAsync(
                () -> Set.copyOf(userAccessService.getUnstableFileUris()),
                taskExecutor
        );

        Set<String> restrictedFolders = joinSet(restrictedFoldersFuture, "restricted folders");
        Set<String> unstableUris = joinSet(unstableUrisFuture, "unstable file URIs");
        
        if (restrictedFolders.isEmpty() && unstableUris.isEmpty()) {
            return docs;
        }
        
        // Filter out restricted folders and unstable (deleted/ingesting) files
        return docs.stream()
                .filter(doc -> !restrictedFolders.contains(CommonUtils.defaultString(doc.getFolderId(), "0")))
                .filter(doc -> !unstableUris.contains(CommonUtils.defaultString(doc.getBlobUri(), doc.getFilepath())))
                .toList();
    }

    /**
     * Maps DTO results to RAG-friendly source documents, carrying over metadata like diPageSpans.
     */
    private List<RagSourceDocument> getSourceDocuments(List<SearchResultDocument> prefetchedDocs) {
        return prefetchedDocs.stream()
                .filter(doc -> CommonUtils.hasText(doc.getContent()))
                .map(doc -> RagSourceDocument.builder()
                        .content(doc.getContent())
                        .source(CommonUtils.defaultString(doc.getSource(), Common.UNKNOWN))
                        .filepath(CommonUtils.defaultString(doc.getFilepath(), Common.EMPTY))
                        .blobUri(CommonUtils.defaultString(doc.getBlobUri(), doc.getFilepath()))
                        .page(doc.getPage() == null ? Common.NOT_AVAILABLE : doc.getPage().toString())
                        .diPageSpans(doc.getDiPageSpans() == null ? Collections.emptyList() : doc.getDiPageSpans())
                        .build())
                .toList();
    }

    /**
     * Builds the string context for the LLM prompt, truncating documents to stay within token limits.
     */
    private String buildContext(List<RagSourceDocument> sourceDocuments) {
        int maxContextChars = Math.max(4000, workflowProperty.getRagMaxContextChars());
        int maxDocumentChars = Math.max(1000, workflowProperty.getRagMaxDocumentChars());
        StringBuilder context = new StringBuilder(Math.min(maxContextChars, 8192));

        for (RagSourceDocument doc : sourceDocuments) {
            String chunk = "--- BEGIN Source: %s | Page: %s ---%n%s%n--- END Source: %s | Page: %s ---"
                    .formatted(doc.source(), doc.page(), truncate(doc.content(), maxDocumentChars), doc.source(), doc.page());
            if (context.length() + chunk.length() + 2 > maxContextChars) {
                break;
            }
            if (!context.isEmpty()) {
                context.append("\n\n");
            }
            context.append(chunk);
        }
        return context.toString();
    }

    private String invokeLlm(String prompt) {
        ChatClient chatClient = chatClients.get(ChatClientConfig.SECURED_RAG_CHAT_CLIENT);
        if (chatClient != null) {
            try {
                String content = chatClient.prompt()
                        .user(prompt)
                        .call()
                        .content();
                if (CommonUtils.hasText(content)) {
                    return content;
                }
            } catch (Exception e) {
                log.warn("Spring AI RAG response failed", e);
            }
        }
        return Common.NO_LLM_CONFIGURED_JSON;
    }

    private RagLlmResponse parseLlmResponse(String rawResponse) {
        try {
            return CommonUtils.parseLlmJson(rawResponse, RagLlmResponse.class, objectMapper);
        } catch (Exception e) {
            return RagLlmResponse.builder()
                    .answer(rawResponse)
                    .rawExtractions(Collections.emptyList())
                    .build();
        }
    }

    private List<ValidatedExtraction> validateExtractions(
            List<LlmExtraction> rawExtractions,
            List<RagSourceDocument> sourceDocuments
    ) {
        if (rawExtractions == null || rawExtractions.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, List<RagSourceDocument>> docsBySource = sourceDocuments.stream()
                .collect(Collectors.groupingBy(RagSourceDocument::source));

        return rawExtractions.stream()
                .map(extraction -> validateExtraction(extraction, docsBySource))
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<ValidatedExtraction> validateExtraction(
            LlmExtraction extraction,
            Map<String, List<RagSourceDocument>> docsBySource
    ) {
        String exactText = extraction.getExactText() == null ? Common.EMPTY : extraction.getExactText().trim();
        if (!CommonUtils.hasText(exactText) || !CommonUtils.hasText(extraction.getSource())) {
            return Optional.empty();
        }

        return docsBySource.getOrDefault(extraction.getSource(), Collections.emptyList()).stream()
                .filter(candidate -> textExistsInDocument(exactText, candidate.content()))
                .findFirst()
                .map(candidate -> new ValidatedExtraction(
                        exactText,
                        extraction.getSource(),
                        CommonUtils.hasText(extraction.getPage()) ? extraction.getPage() : candidate.page(),
                        CommonUtils.defaultString(extraction.getExplains(), "Supporting evidence"),
                        candidate.diPageSpans()
                ));
    }

    private boolean textExistsInDocument(String needle, String haystack) {
        if (!CommonUtils.hasText(needle) || !CommonUtils.hasText(haystack)) {
            return false;
        }
        if (haystack.contains(needle)) {
            return true;
        }
        String normalizedNeedle = CommonUtils.normalizeWhitespace(needle);
        String normalizedHaystack = CommonUtils.normalizeWhitespace(haystack);
        if (normalizedHaystack.contains(normalizedNeedle)) {
            return true;
        }
        return normalizedNeedle.length() > 30 && CommonUtils.tokenOverlapRatio(normalizedNeedle, normalizedHaystack) >= 0.90;
    }

    private List<SupportingPassage> consolidateByPdf(List<ValidatedExtraction> validatedExtractions) {
        Map<String, List<ValidatedExtraction>> byPdf = validatedExtractions.stream()
                .collect(Collectors.groupingBy(ValidatedExtraction::source, LinkedHashMap::new, Collectors.toList()));

        List<SupportingPassage> consolidated = new ArrayList<>();
        for (Map.Entry<String, List<ValidatedExtraction>> entry : byPdf.entrySet()) {
            List<ValidatedExtraction> extractions = entry.getValue();
            List<String> passages = extractions.stream().map(ValidatedExtraction::text).toList();
            String pages = extractions.stream()
                    .map(ValidatedExtraction::page)
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .distinct()
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.joining(", "));
            String relevance = extractions.stream()
                    .map(ValidatedExtraction::relevance)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.joining("; "));
            List<DiPageSpan> spans = unionDiPageSpans(extractions);

            consolidated.add(SupportingPassage.builder()
                    .text(String.join("\n\n", passages))
                    .source(entry.getKey())
                    .page(pages)
                    .relevance(relevance)
                    .individualPassages(passages)
                    .diPageSpans(spans)
                    .build());
        }
        return consolidated;
    }

    private List<DiPageSpan> unionDiPageSpans(List<ValidatedExtraction> extractions) {
        Set<String> seen = new LinkedHashSet<>();
        List<DiPageSpan> union = new ArrayList<>();
        for (ValidatedExtraction extraction : extractions) {
            for (DiPageSpan span : extraction.diPageSpans()) {
                String paragraphKey = span.getParagraphText() == null
                        ? Common.EMPTY
                        : span.getParagraphText().substring(0, Math.min(50, span.getParagraphText().length()));
                String key = span.getPage() + "::" + paragraphKey;
                if (seen.add(key)) {
                    union.add(span);
                }
            }
        }
        return union;
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars).trim() + "\n[truncated]";
    }

    private Set<String> joinSet(CompletableFuture<Set<String>> future, String label) {
        try {
            return future.join();
        } catch (CompletionException e) {
            log.warn("Could not load {}; continuing without that filter", label, e);
            return Set.of();
        }
    }

    private record ValidatedExtraction(
            String text,
            String source,
            String page,
            String relevance,
            List<DiPageSpan> diPageSpans
    ) {
    }
}
