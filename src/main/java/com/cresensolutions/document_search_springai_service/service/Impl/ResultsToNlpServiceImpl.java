package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.config.ChatClientConfig;
import com.cresensolutions.document_search_springai_service.service.ResultsToNlpService;
import com.cresensolutions.document_search_springai_service.utils.CommonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Converts SQL result rows to natural-language answers using the LLM.
 * Mirrors Python db_search.ResultsToNLPConverter.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResultsToNlpServiceImpl implements ResultsToNlpService {

    private static final int MAX_ROWS_IN_PROMPT = 50;

    private final Map<String, ChatClient> chatClients;
    private final ObjectMapper objectMapper;

    /**
     * Translates raw database rows into a clear, natural-language explanation matching the user's question language.
     * Uses the default secured chat client.
     *
     * @param question the user's raw query
     * @param rows the list of database results to summarize
     * @param isHybrid flag indicating if results are rendered in combination with direct text layouts
     * @return the generated conversational summary text
     */
    @Override
    public String generateNlpAnswer(String question, List<Map<String, Object>> rows, boolean isHybrid) {
        if (rows == null || rows.isEmpty()) {
            return "No records were found matching your query.";
        }

        String dataJson = serializeRows(rows);
        String prompt = buildNlpPrompt(question, dataJson, rows.size(), isHybrid);

        ChatClient chatClient = chatClients.get(ChatClientConfig.SECURED_RAG_CHAT_CLIENT);
        if (chatClient == null) {
            return buildFallbackAnswer(question, rows);
        }

        try {
            String content = chatClient.prompt().user(prompt).call().content();
            return CommonUtils.hasText(content) ? content.trim() : buildFallbackAnswer(question, rows);
        } catch (Exception e) {
            log.warn("NLP answer generation failed", e);
            return buildFallbackAnswer(question, rows);
        }
    }

    /**
     * Generates a dynamic, engaging single-sentence introduction for tabular result layouts.
     *
     * @param question user query
     * @param rowCount count of records in table
     * @return the generated introduction sentence
     */
    @Override
    public String generateTableIntro(String question, int rowCount) {
        String prompt = """
                Write a single concise sentence introducing a table of %d record(s) that answers this question: "%s"
                Do not include any table data. Just the intro sentence.
                """.formatted(rowCount, question);

        ChatClient chatClient = chatClients.get(ChatClientConfig.SECURED_RAG_CHAT_CLIENT);
        if (chatClient == null) {
            return "Here are the %d record(s) matching your query.".formatted(rowCount);
        }

        try {
            String content = chatClient.prompt().user(prompt).call().content();
            return CommonUtils.hasText(content) ? content.trim()
                    : "Here are the %d record(s) matching your query.".formatted(rowCount);
        } catch (Exception e) {
            log.warn("Table intro generation failed", e);
            return "Here are the %d record(s) matching your query.".formatted(rowCount);
        }
    }

    /**
     * Constructs a structured prompt supplying query results, row counts, and context to the LLM.
     *
     * @param question user question
     * @param dataJson JSON string of serialized rows
     * @param totalRows total row count
     * @param isHybrid hybrid mode indicator
     * @return formatted prompt string
     */
    private String buildNlpPrompt(String question, String dataJson, int totalRows, boolean isHybrid) {
        String context = isHybrid
                ? "Provide a concise summary answer. The full records will be shown separately."
                : "Provide a complete, helpful answer based on the data.";
        return """
                USER QUESTION: "%s"
                
                QUERY RESULTS (%d row(s)):
                %s
                
                TASK: %s
                Write your answer in the same language as the user question.
                Be concise and factual. Do not include raw JSON or SQL.
                """.formatted(question, totalRows, dataJson, context);
    }

    /**
     * Safely serializes query result rows into a pretty-printed JSON string,
     * truncating up to MAX_ROWS_IN_PROMPT to avoid prompt bloat.
     *
     * @param rows database query rows
     * @return pretty JSON representation of records
     */
    private String serializeRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> sample = rows.size() > MAX_ROWS_IN_PROMPT
                ? rows.subList(0, MAX_ROWS_IN_PROMPT)
                : rows;
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sample);
        } catch (Exception e) {
            return sample.toString();
        }
    }

    /**
     * Fallback formatter when the LLM connection fails.
     *
     * @param question user question
     * @param rows database result rows
     * @return static fallback text
     */
    private String buildFallbackAnswer(String question, List<Map<String, Object>> rows) {
        if (rows.size() == 1) {
            return "Found 1 record: " + rows.get(0).toString();
        }
        return "Found " + rows.size() + " records matching your query.";
    }
}
