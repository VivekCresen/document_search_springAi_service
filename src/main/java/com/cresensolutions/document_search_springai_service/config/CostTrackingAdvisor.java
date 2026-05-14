package com.cresensolutions.document_search_springai_service.config;

import com.cresensolutions.document_search_springai_service.service.CostTrackerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CostTrackingAdvisor implements CallAdvisor {

    private final CostTrackerService costTrackerService;

    @Override
    public String getName() {
        return "CostTrackingAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        if (response.chatResponse() != null && response.chatResponse().getMetadata().getUsage() != null) {
            org.springframework.ai.chat.metadata.Usage usage = response.chatResponse().getMetadata().getUsage();
            int inputTokens = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
            int outputTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
            costTrackerService.logUsage("chat_completion", inputTokens, outputTokens, 0);
            log.debug("Logged chat_completion usage: input={}, output={}", inputTokens, outputTokens);
        }
        return response;
    }
}
