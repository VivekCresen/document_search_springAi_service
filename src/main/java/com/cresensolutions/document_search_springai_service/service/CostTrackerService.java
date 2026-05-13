package com.cresensolutions.document_search_springai_service.service;

public interface CostTrackerService {
    void logUsage(String operationType, int inputTokens, int outputTokens, int reasoningTokens);
    double getCumulativeTotal();
}
