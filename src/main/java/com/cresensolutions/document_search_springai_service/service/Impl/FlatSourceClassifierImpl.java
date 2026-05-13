package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.config.ChatClientConfig;
import com.cresensolutions.document_search_springai_service.dto.ViewRegistryEntry;
import com.cresensolutions.document_search_springai_service.service.FlatSourceClassifier;
import com.cresensolutions.document_search_springai_service.service.SchemaRegistryService;
import com.cresensolutions.document_search_springai_service.utils.CommonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Uses the LLM to pick the best-matching DB view for a question.
 * Falls back to the first registered view when only one exists, or when the LLM fails.
 * Mirrors Python db_search.FlatSourceClassifier.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FlatSourceClassifierImpl implements FlatSourceClassifier {

    private final SchemaRegistryService schemaRegistryService;
    private final Map<String, ChatClient> chatClients;
    private final ObjectMapper objectMapper;

    @Override
    public String classify(String question) {
        List<ViewRegistryEntry> views = schemaRegistryService.getActiveViews();
        if (views.isEmpty()) {
            return null;
        }
        if (views.size() == 1) {
            return views.get(0).viewName();
        }

        String prompt = buildPrompt(question, views);
        ChatClient chatClient = chatClients.get(ChatClientConfig.INTENT_CLASSIFIER_CHAT_CLIENT);
        if (chatClient == null) {
            return views.get(0).viewName();
        }

        try {
            String content = chatClient.prompt().user(prompt).call().content();
            String json = CommonUtils.extractJsonObject(content);
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            String viewName = CommonUtils.stringValue(parsed.get("view_name"));
            boolean valid = views.stream().anyMatch(v -> v.viewName().equals(viewName));
            if (valid) {
                log.debug("FlatSourceClassifier → {}", viewName);
                return viewName;
            }
        } catch (Exception e) {
            log.warn("FlatSourceClassifier LLM call failed; using first view", e);
        }
        return views.get(0).viewName();
    }

    private String buildPrompt(String question, List<ViewRegistryEntry> views) {
        StringBuilder sb = new StringBuilder();
        sb.append("Select the most relevant database view for the user's question.\n\n");
        sb.append("USER QUESTION: \"").append(question).append("\"\n\n");
        sb.append("AVAILABLE VIEWS:\n");
        for (ViewRegistryEntry v : views) {
            String shortName = v.viewName().contains(".") ? v.viewName().split("\\.")[1] : v.viewName();
            sb.append("- ").append(v.viewName()).append(": ").append(v.description());
            if (!v.confidenceKeywords().isEmpty()) {
                sb.append(" [keywords: ").append(String.join(", ", v.confidenceKeywords().subList(0, Math.min(6, v.confidenceKeywords().size())))).append("]");
            }
            sb.append("\n");
        }
        sb.append("\nReturn ONLY a JSON object:\n{\"view_name\": \"<exact view_name from the list above>\"}");
        return sb.toString();
    }
}
