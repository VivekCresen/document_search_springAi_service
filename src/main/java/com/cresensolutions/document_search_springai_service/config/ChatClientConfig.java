package com.cresensolutions.document_search_springai_service.config;

import com.cresensolutions.document_search_springai_service.commons.Common;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnBean(ChatClient.Builder.class)
public class ChatClientConfig {

    public static final String STANDALONE_QUERY_CHAT_CLIENT = Common.STANDALONE_QUERY_CHAT_CLIENT;
    public static final String INTENT_CLASSIFIER_CHAT_CLIENT = Common.INTENT_CLASSIFIER_CHAT_CLIENT;
    public static final String SECURED_RAG_CHAT_CLIENT = Common.SECURED_RAG_CHAT_CLIENT;
    public static final String GENERAL_CHAT_CLIENT = Common.GENERAL_CHAT_CLIENT;

    private static final String STANDALONE_SYSTEM_PROMPT =
            "You rewrite follow-up questions into standalone questions.";
    private static final String INTENT_SYSTEM_PROMPT =
            "You classify user questions for a secured enterprise search workflow.";
    private static final String RAG_SYSTEM_PROMPT = """
            You answer questions using only the provided context documents.
            Return valid JSON only. No markdown fences and no extra text.
            """;
    private static final String GENERAL_SYSTEM_PROMPT =
            "You are a helpful assistant for an enterprise workflow. You answer greetings, casual conversation, and broad technical or general knowledge questions accurately and professionally.";

    @Bean(name = STANDALONE_QUERY_CHAT_CLIENT)
    ChatClient standaloneQueryChatClient(ChatClient.Builder builder, CostTrackingAdvisor costTrackingAdvisor) {
        return builder.defaultSystem(STANDALONE_SYSTEM_PROMPT)
                .defaultAdvisors(costTrackingAdvisor)
                .build();
    }

    @Bean(name = INTENT_CLASSIFIER_CHAT_CLIENT)
    ChatClient intentClassifierChatClient(ChatClient.Builder builder, CostTrackingAdvisor costTrackingAdvisor) {
        return builder.defaultSystem(INTENT_SYSTEM_PROMPT)
                .defaultAdvisors(costTrackingAdvisor)
                .build();
    }

    @Bean(name = SECURED_RAG_CHAT_CLIENT)
    ChatClient securedRagChatClient(ChatClient.Builder builder, CostTrackingAdvisor costTrackingAdvisor) {
        return builder.defaultSystem(RAG_SYSTEM_PROMPT)
                .defaultAdvisors(costTrackingAdvisor)
                .build();
    }

    @Bean(name = GENERAL_CHAT_CLIENT)
    ChatClient generalChatClient(ChatClient.Builder builder, CostTrackingAdvisor costTrackingAdvisor) {
        return builder.defaultSystem(GENERAL_SYSTEM_PROMPT)
                .defaultAdvisors(costTrackingAdvisor)
                .build();
    }
}
