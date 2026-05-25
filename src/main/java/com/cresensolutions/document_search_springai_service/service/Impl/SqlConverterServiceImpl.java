package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.config.ChatClientConfig;
import com.cresensolutions.document_search_springai_service.dto.SqlGenerationResult;
import com.cresensolutions.document_search_springai_service.dto.ViewRegistryEntry;
import com.cresensolutions.document_search_springai_service.service.SchemaRegistryService;
import com.cresensolutions.document_search_springai_service.service.SqlConverterService;
import com.cresensolutions.document_search_springai_service.utils.CommonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Generates SQL from a natural-language question using the LLM + schema context.
 * Mirrors Python db_search.MMSQLConverter.generate_sql_query().
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SqlConverterServiceImpl implements SqlConverterService {

    private static final int NLP_MAX_ROWS = 15;
    private static final TypeReference<List<Map<String, String>>> COL_LIST_TYPE = new TypeReference<>() {};

    private final SchemaRegistryService schemaRegistryService;
    private final Map<String, ChatClient> chatClients;
    private final ObjectMapper objectMapper;

    @Override
    public SqlGenerationResult generateSql(
            String question,
            String responseType,
            String viewName,
            Map<String, List<String>> semanticHints
    ) {
        if (viewName == null || viewName.isBlank()) {
            return SqlGenerationResult.builder()
                    .explanation("No database view is registered for this query.")
                    .isClarification(false)
                    .build();
        }

        ViewRegistryEntry view = schemaRegistryService.getActiveViews().stream()
                .filter(v -> v.viewName().equals(viewName))
                .findFirst()
                .orElse(null);

        String schemaContext = view != null && !view.schemaJson().isEmpty()
                ? buildSchemaContext(view.schemaJson())
                : "Schema not available.";

        String prompt = buildPrompt(question, responseType, viewName, schemaContext, semanticHints);

        ChatClient chatClient = chatClients.get(ChatClientConfig.INTENT_CLASSIFIER_CHAT_CLIENT);
        if (chatClient == null) {
            return SqlGenerationResult.builder()
                    .explanation("No LLM configured for SQL generation.")
                    .isClarification(false)
                    .build();
        }

        try {
            String content = chatClient.prompt().user(prompt).call().content();
            return parseResponse(content, responseType);
        } catch (Exception e) {
            log.warn("SQL generation failed for view {}", viewName, e);
            return SqlGenerationResult.builder()
                    .explanation("SQL generation encountered an error.")
                    .isClarification(false)
                    .build();
        }
    }

    // -------------------------------------------------------------------------
    // Prompt builder
    // -------------------------------------------------------------------------

    /**
     * Constructs a comprehensive PostgreSQL expert instruction prompt supplying schema metadata,
     * semantic value filter hints, query rules, and layout constraints to the SQL generator.
     *
     * @param question user query
     * @param responseType layout format representation
     * @param viewName target view name
     * @param schemaContext pretty schema JSON context
     * @param semanticHints ranked categorical filter values
     * @return complete prompt string
     */
    private String buildPrompt(
            String question,
            String responseType,
            String viewName,
            String schemaContext,
            Map<String, List<String>> semanticHints
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a PostgreSQL expert. Generate a SQL query for the user's question.\n\n");
        sb.append("USER QUESTION: \"").append(question).append("\"\n\n");
        sb.append("DATABASE VIEW: ").append(viewName).append("\n\n");
        sb.append("SCHEMA:\n").append(schemaContext).append("\n\n");

        if (semanticHints != null && !semanticHints.isEmpty()) {
            sb.append("SEMANTIC HINTS (top matching values per column — use these for WHERE clauses):\n");
            semanticHints.forEach((col, vals) -> {
                if (!vals.isEmpty()) {
                    sb.append("  ").append(col).append(": ").append(String.join(", ", vals.subList(0, Math.min(5, vals.size())))).append("\n");
                }
            });
            sb.append("\n");
        }

        sb.append("RESPONSE TYPE: ").append(responseType).append("\n");
        sb.append("- nlp_summary: single aggregate or lookup query, LIMIT ").append(NLP_MAX_ROWS).append("\n");
        sb.append("- detailed_records: full record list query, LIMIT 200\n");
        sb.append("- hybrid: produce BOTH an aggregate SQL and a detail SQL\n\n");

        sb.append("RULES:\n");
        sb.append("1. Use only columns that exist in the schema above.\n");
        sb.append("2. Always qualify the table as ").append(viewName).append(".\n");
        sb.append("3. If the question is ambiguous and you need clarification, set is_clarification=true and put the question in 'explanation'.\n");
        sb.append("4. selected_columns must list every column in SELECT as {\"name\": \"db_col\", \"proxy\": \"Human Label\"}.\n\n");

        if ("hybrid".equals(responseType)) {
            sb.append("Return ONLY valid JSON:\n");
            sb.append("{\n");
            sb.append("  \"primary_sql\": \"<aggregate SQL>\",\n");
            sb.append("  \"detail_sql\": \"<detail SQL>\",\n");
            sb.append("  \"selected_columns\": [{\"name\": \"col\", \"proxy\": \"Label\"}, ...],\n");
            sb.append("  \"explanation\": \"brief explanation\",\n");
            sb.append("  \"is_clarification\": false\n");
            sb.append("}");
        } else {
            sb.append("Return ONLY valid JSON:\n");
            sb.append("{\n");
            sb.append("  \"primary_sql\": \"<SQL query>\",\n");
            sb.append("  \"selected_columns\": [{\"name\": \"col\", \"proxy\": \"Label\"}, ...],\n");
            sb.append("  \"explanation\": \"brief explanation\",\n");
            sb.append("  \"is_clarification\": false\n");
            sb.append("}");
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Response parser
    // -------------------------------------------------------------------------

    /**
     * Parses the LLM string response content, mapping it to a structured SqlGenerationResult.
     * Handles clarification requests.
     *
     * @param content raw string content from LLM
     * @param responseType requested formatting format
     * @return constructed SqlGenerationResult
     */
    private SqlGenerationResult parseResponse(String content, String responseType) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = CommonUtils.parseLlmJson(content, Map.class, objectMapper);

            boolean isClarification = Boolean.TRUE.equals(parsed.get("is_clarification"));
            String explanation = CommonUtils.stringValue(parsed.get("explanation"));

            if (isClarification) {
                return SqlGenerationResult.builder()
                        .explanation(explanation)
                        .isClarification(true)
                        .selectedColumns(Collections.emptyList())
                        .build();
            }

            String primarySql = CommonUtils.stringValue(parsed.get("primary_sql"));
            String detailSql = "hybrid".equals(responseType)
                    ? CommonUtils.stringValue(parsed.get("detail_sql"))
                    : null;

            List<Map<String, String>> selectedColumns = parseSelectedColumns(parsed.get("selected_columns"));

            return SqlGenerationResult.builder()
                    .primarySql(primarySql)
                    .detailSql(detailSql)
                    .selectedColumns(selectedColumns)
                    .explanation(explanation)
                    .isClarification(false)
                    .build();

        } catch (Exception e) {
            log.warn("Failed to parse SQL generation response", e);
            return SqlGenerationResult.builder()
                    .explanation("Could not parse SQL generation response.")
                    .isClarification(false)
                    .build();
        }
    }

    /**
     * Parses and formats selected columns metadata returned by SQL prompt generator, ensuring fallback human labels exist.
     *
     * @param raw raw JSON object
     * @return formatted metadata list representing selected columns
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseSelectedColumns(Object raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        try {
            List<Map<String, String>> parsed = objectMapper.convertValue(raw, COL_LIST_TYPE);
            return parsed.stream().map(col -> {
                Map<String, String> sanitizedCol = new java.util.HashMap<>(col);
                String name = sanitizedCol.getOrDefault("name", "");
                String proxy = sanitizedCol.getOrDefault("proxy", "").trim();
                sanitizedCol.put("proxy", sanitizeColumnLabel(CommonUtils.hasText(proxy) ? proxy : name));
                return sanitizedCol;
            }).toList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Sanitizes snake_case database column names into clean Human Readable Labels.
     *
     * @param label database name
     * @return capitalized human readable label
     */
    private String sanitizeColumnLabel(String label) {
        if (label == null || label.isBlank()) return "";
        if (label.contains(" ") || (!label.equals(label.toLowerCase()) && !label.equals(label.toUpperCase()))) {
            return label;
        }
        String[] words = label.replace("_", " ").split(" ");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    sb.append(word.substring(1).toLowerCase());
                }
                sb.append(" ");
            }
        }
        return sb.toString().trim();
    }

    /**
     * Formats schema maps safely into structured JSON strings.
     *
     * @param schemaJson database view column schema
     * @return string metadata structure
     */
    private String buildSchemaContext(Map<String, Object> schemaJson) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schemaJson);
        } catch (Exception e) {
            return schemaJson.toString();
        }
    }
}
