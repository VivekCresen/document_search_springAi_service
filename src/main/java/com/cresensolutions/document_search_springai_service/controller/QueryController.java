package com.cresensolutions.document_search_springai_service.controller;

import com.cresensolutions.document_search_springai_service.dto.EnvelopeRequest;
import com.cresensolutions.document_search_springai_service.dto.EnvelopeResponse;
import com.cresensolutions.document_search_springai_service.service.QueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/**
 * REST Controller for handling document query operations.
 * Processes user questions about documents using AI-powered search and retrieval.
 * Manages conversation context and provides asynchronous query processing.
 */
@RestController
@RequestMapping
@RequiredArgsConstructor
public class QueryController {

    private final QueryService queryService;

    /**
     * Processes a document query asynchronously.
     * Takes a user question, processes it through the AI workflow,
     * and returns relevant document information and answers.
     *
     * @param request the envelope containing query data
     * @return CompletableFuture with the response containing query results
     */
    @PostMapping("/query")
    public CompletableFuture<ResponseEntity<EnvelopeResponse>> query(@RequestBody EnvelopeRequest request) {
        return queryService.processQuery(request).thenApply(ResponseEntity::ok);
    }
}
