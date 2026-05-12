package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.config.AzureOpenAiProperty;
import com.cresensolutions.document_search_springai_service.service.StandaloneQueryService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class StandaloneQueryServiceImpl implements StandaloneQueryService {

    private static final String SYSTEM_PROMPT = "You rewrite follow-up questions into standalone questions.";
    private static final String USER_PROMPT_TEMPLATE = """
            Rewrite the current user question into a complete standalone question.
            Use the conversation history only to resolve pronouns, follow-ups, and missing context.
            Do not answer the question. Return only the rewritten question.

            Conversation history:
            %s

            Current question:
            %s
            """;

    private final AzureOpenAiProperty azureOpenAiProperty;
    private final RestClient.Builder restClientBuilder;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private RestClient restClient;
    private ChatClient chatClient;
    private String chatCompletionsUrl;

    @PostConstruct
    void initialize() {
        this.restClient = restClientBuilder.build();
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel != null) {
            this.chatClient = ChatClient.builder(chatModel)
                    .defaultSystem(SYSTEM_PROMPT)
                    .build();
        }
        this.chatCompletionsUrl = hasAzureOpenAiConfig() ? buildChatCompletionsUrl() : "";
    }

    public String createStandaloneQuery(String currentQuestion, String conversationContext) {
        if (currentQuestion == null || currentQuestion.isBlank()) {
            return "";
        }
        String prompt = USER_PROMPT_TEMPLATE.formatted(conversationContext, currentQuestion);

        if (chatClient != null) {
            return createWithSpringAi(prompt, currentQuestion);
        }
        if (!hasAzureOpenAiConfig()) {
            return currentQuestion;
        }
        return createWithAzureRest(prompt, currentQuestion);
    }

    private String createWithSpringAi(String prompt, String fallback) {
        try {
            String content = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            return cleanStandaloneResponse(content, fallback);
        } catch (Exception e) {
            log.warn("Spring AI standalone query generation failed; trying Azure REST fallback", e);
            if (hasAzureOpenAiConfig()) {
                return createWithAzureRest(prompt, fallback);
            }
            return fallback;
        }
    }

    private String createWithAzureRest(String prompt, String fallback) {
        try {
            Map<String, Object> request = Map.of(
                    "messages", List.of(
                            Map.of("role", "system", "content", SYSTEM_PROMPT),
                            Map.of("role", "user", "content", prompt)
                    ),
                    "temperature", 0,
                    "max_tokens", 300
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient
                    .post()
                    .uri(chatCompletionsUrl)
                    .header("api-key", azureOpenAiProperty.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(Map.class);

            return extractContent(response, fallback);
        } catch (Exception e) {
            log.warn("Standalone query generation failed; using original question", e);
            return fallback;
        }
    }

    private boolean hasAzureOpenAiConfig() {
        return hasText(azureOpenAiProperty.getEndpoint())
                && hasText(azureOpenAiProperty.getApiKey())
                && hasText(azureOpenAiProperty.getDeployment());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String buildChatCompletionsUrl() {
        String endpoint = azureOpenAiProperty.getEndpoint();
        if (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        return endpoint + "/openai/deployments/" + azureOpenAiProperty.getDeployment()
                + "/chat/completions?api-version=" + azureOpenAiProperty.getApiVersion();
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> response, String fallback) {
        if (response == null) {
            return fallback;
        }
        Object choicesValue = response.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()) {
            return fallback;
        }
        Object first = choices.get(0);
        if (!(first instanceof Map<?, ?> choice)) {
            return fallback;
        }
        Object messageValue = choice.get("message");
        if (!(messageValue instanceof Map<?, ?> message)) {
            return fallback;
        }
        Object content = message.get("content");
        return cleanStandaloneResponse(content == null ? "" : content.toString(), fallback);
    }

    private String cleanStandaloneResponse(String content, String fallback) {
        String text = content == null ? "" : content.trim();
        if (text.isBlank()) {
            return fallback;
        }
        return text
                .replaceFirst("(?i)^standalone question\\s*:\\s*", "")
                .replaceFirst("(?i)^reformulated question\\s*:\\s*", "")
                .trim();
    }
}
