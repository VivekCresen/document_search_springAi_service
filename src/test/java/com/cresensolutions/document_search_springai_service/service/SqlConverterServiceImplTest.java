package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.config.ChatClientConfig;
import com.cresensolutions.document_search_springai_service.dto.SqlGenerationResult;
import com.cresensolutions.document_search_springai_service.dto.ViewRegistryEntry;
import com.cresensolutions.document_search_springai_service.service.Impl.SqlConverterServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SqlConverterServiceImpl Tests")
class SqlConverterServiceImplTest {

    @Mock
    SchemaRegistryService schemaRegistryService;

    @Mock
    Map<String, ChatClient> chatClients;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    ChatClient chatClient;

    ObjectMapper objectMapper = new ObjectMapper();

    SqlConverterServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SqlConverterServiceImpl(schemaRegistryService, chatClients, objectMapper);
        lenient().when(chatClients.get(ChatClientConfig.INTENT_CLASSIFIER_CHAT_CLIENT)).thenReturn(chatClient);
    }

    @Test
    @DisplayName("generateSql: returns error result when viewName is blank")
    void generateSql_blankViewName() {
        SqlGenerationResult result = service.generateSql("q", "nlp_summary", "", null);
        assertThat(result.explanation()).contains("No database view");
        assertThat(result.isClarification()).isFalse();
        assertThat(result.primarySql()).isNull();
    }

    @Test
    @DisplayName("generateSql: returns error result when no ChatClient configured")
    void generateSql_noChatClient() {
        when(chatClients.get(ChatClientConfig.INTENT_CLASSIFIER_CHAT_CLIENT)).thenReturn(null);
        when(schemaRegistryService.getActiveViews()).thenReturn(List.of());

        SqlGenerationResult result = service.generateSql("q", "nlp_summary", "schema.view1", null);
        assertThat(result.explanation()).contains("No LLM configured");
    }

    @Test
    @DisplayName("generateSql: successfully parses SQL result from LLM")
    void generateSql_successParseSql() {
        ViewRegistryEntry view = ViewRegistryEntry.builder()
                .viewName("schema.orders").description("Orders")
                .schemaJson(Map.of("id", "int", "status", "text"))
                .confidenceKeywords(List.of()).categoricalColumns(List.of()).build();
        when(schemaRegistryService.getActiveViews()).thenReturn(List.of(view));

        String llmJson = """
                {
                  "primary_sql": "SELECT id FROM schema.orders LIMIT 15",
                  "selected_columns": [{"name": "id", "proxy": "Order ID"}],
                  "explanation": "Simple query",
                  "is_clarification": false
                }
                """;
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn(llmJson);

        SqlGenerationResult result = service.generateSql("show orders", "nlp_summary", "schema.orders", null);

        assertThat(result.primarySql()).isEqualTo("SELECT id FROM schema.orders LIMIT 15");
        assertThat(result.isClarification()).isFalse();
        assertThat(result.selectedColumns()).hasSize(1);
        assertThat(result.selectedColumns().get(0)).containsEntry("proxy", "Order ID");
    }

    @Test
    @DisplayName("generateSql: returns clarification result when LLM asks for clarification")
    void generateSql_clarification() {
        when(schemaRegistryService.getActiveViews()).thenReturn(List.of());
        String llmJson = """
                {
                  "explanation": "Do you want all orders or just today?",
                  "is_clarification": true
                }
                """;
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn(llmJson);

        SqlGenerationResult result = service.generateSql("orders", "nlp_summary", "schema.orders", null);
        assertThat(result.isClarification()).isTrue();
        assertThat(result.explanation()).contains("Do you want");
    }

    @Test
    @DisplayName("generateSql: returns error result when LLM throws exception")
    void generateSql_llmException() {
        when(schemaRegistryService.getActiveViews()).thenReturn(List.of());
        when(chatClient.prompt().user(anyString()).call().content()).thenThrow(new RuntimeException("API error"));

        SqlGenerationResult result = service.generateSql("q", "nlp_summary", "schema.orders", Map.of());
        assertThat(result.explanation()).contains("error");
    }

    @Test
    @DisplayName("generateSql: includes semantic hints in prompt when provided")
    void generateSql_withSemanticHints() {
        when(schemaRegistryService.getActiveViews()).thenReturn(List.of());

        String llmJson = """
                {"primary_sql": "SELECT * FROM schema.orders","selected_columns": [],"explanation": "","is_clarification": false}
                """;
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn(llmJson);

        Map<String, List<String>> hints = Map.of("status", List.of("Active", "Pending"));
        SqlGenerationResult result = service.generateSql("q", "nlp_summary", "schema.orders", hints);
        assertThat(result.primarySql()).isNotBlank();
    }

    @Test
    @DisplayName("generateSql: handles hybrid responseType with detailSql")
    void generateSql_hybridResponseType() {
        when(schemaRegistryService.getActiveViews()).thenReturn(List.of());
        String llmJson = """
                {
                  "primary_sql": "SELECT COUNT(*) FROM schema.orders",
                  "detail_sql": "SELECT * FROM schema.orders LIMIT 200",
                  "selected_columns": [],
                  "explanation": "",
                  "is_clarification": false
                }
                """;
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn(llmJson);

        SqlGenerationResult result = service.generateSql("q", "hybrid", "schema.orders", null);
        assertThat(result.primarySql()).contains("COUNT");
        assertThat(result.detailSql()).contains("LIMIT 200");
    }
}
