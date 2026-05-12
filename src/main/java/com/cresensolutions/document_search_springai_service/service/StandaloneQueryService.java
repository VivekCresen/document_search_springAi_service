package com.cresensolutions.document_search_springai_service.service;

/**
 * Service interface for creating standalone queries from follow-up questions.
 * Uses AI to rewrite questions into complete, context-independent queries.
 */
public interface StandaloneQueryService {

    /**
     * Creates a standalone query from the current question and conversation context.
     * Rewrites follow-up questions into complete questions using conversation history.
     *
     * @param currentQuestion the current user question
     * @param conversationContext the conversation history context
     * @return the rewritten standalone question
     */
    String createStandaloneQuery(String currentQuestion, String conversationContext);
}