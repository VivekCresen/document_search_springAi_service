package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.config.ChatClientConfig;
import com.cresensolutions.document_search_springai_service.service.StandaloneQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class StandaloneQueryServiceImpl implements StandaloneQueryService {

    private static final String USER_PROMPT_TEMPLATE = """
            Given the conversation log below, reformulate the user's latest reply into a standalone,
            fully self-contained question.

            Conversation log:
            %s

            Current user reply:
            %s

            Rules:
            1. If the reply is a chained follow-up such as "what about X?" or "and in 2025?",
               scan the entire conversation history to recover the core subject, metric, and earlier filters.
            2. If the previous assistant message asked a clarification question and this reply confirms it,
               fuse the confirmed term into the original user intent.
            3. If the reply corrects a clarification, fuse the correction into the original user intent.
            4. If the reply is already standalone, keep it exactly as-is.
            5. Translate the final standalone question into English if it is in another language.

            Return exactly:
            REFORMULATED QUESTION: <standalone question>
            """;

    private final Map<String, ChatClient> chatClients;

    public String createStandaloneQuery(String currentQuestion, String conversationContext) {
        if (currentQuestion == null || currentQuestion.isBlank()) {
            return "";
        }
        String prompt = USER_PROMPT_TEMPLATE.formatted(conversationContext, currentQuestion);

        ChatClient chatClient = chatClients.get(ChatClientConfig.STANDALONE_QUERY_CHAT_CLIENT);
        if (chatClient != null) {
            return createWithSpringAi(chatClient, prompt, currentQuestion);
        }
        return currentQuestion;
    }

    private String createWithSpringAi(ChatClient chatClient, String prompt, String fallback) {
        try {
            String content = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            return cleanStandaloneResponse(content, fallback);
        } catch (Exception e) {
            log.warn("Spring AI standalone query generation failed; using original question", e);
            return fallback;
        }
    }

    private String cleanStandaloneResponse(String content, String fallback) {
        String text = content == null ? "" : content.trim();
        if (text.isBlank()) {
            return fallback;
        }
        // Handle "REFORMULATED QUESTION: ... ENGLISH TRANSLATION: ..." format
        // Python keeps the reformulated question and appends the English translation
        // when they differ. We just return the English translation when present,
        // since the downstream pipeline works in English.
        if (text.toUpperCase().contains("ENGLISH TRANSLATION:")) {
            String[] parts = text.split("(?i)ENGLISH TRANSLATION:\\s*");
            if (parts.length >= 2) {
                String reformulated = parts[0]
                        .replaceFirst("(?i)^standalone question\\s*:\\s*", "")
                        .replaceFirst("(?i)^reformulated question\\s*:\\s*", "")
                        .trim();
                String englishTranslation = parts[parts.length - 1].trim();
                if (!englishTranslation.isBlank()) {
                    return reformulated + " (English: " + englishTranslation + ")";
                }
            }
        }
        return text
                .replaceFirst("(?i)^standalone question\\s*:\\s*", "")
                .replaceFirst("(?i)^reformulated question\\s*:\\s*", "")
                .trim();
    }
}
