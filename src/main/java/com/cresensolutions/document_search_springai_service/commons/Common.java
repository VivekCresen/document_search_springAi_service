package com.cresensolutions.document_search_springai_service.commons;

import java.util.List;

public class Common {

    protected Common() {
    }

    public static final String EMPTY = "";
    public static final String UNKNOWN = "Unknown";
    public static final String NOT_AVAILABLE = "N/A";
    public static final String DEFAULT_PRODUCT_NAME = "MM";
    public static final String DEFAULT_PROFILE = "dev";
    public static final String DEFAULT_DOCUMENT_FILENAME = "document";
    public static final String TEXT_RESPONSE_TYPE = "text";
    public static final String TABLE_RESPONSE_TYPE = "table";
    public static final String HYBRID_RESPONSE_TYPE = "hybrid";
    public static final String TEXT_TABLE_RESPONSE_TYPE = "text/table";
    public static final String CHAT_ID_PREFIX = "chat_";
    public static final int CHAT_ID_LENGTH = 12;

    public static final String TASK_EXECUTOR = "taskExecutor";
    public static final String DOC_SEARCH_THREAD_PREFIX = "doc-search-";

    public static final String CACHE_USER_RESTRICTIONS = "userRestrictions";
    public static final String CACHE_FILE_METADATA = "fileMetadata";
    public static final String CACHE_BLOB_NAMES = "blobNames";
    public static final String CACHE_STABLE_FILES = "stableFiles";

    public static final String INTENT_DATABASE = "database";
    public static final String INTENT_DOCUMENT = "document";
    public static final String INTENT_GENERAL = "general";
    public static final List<String> VALID_INTENTS = List.of(INTENT_DATABASE, INTENT_DOCUMENT, INTENT_GENERAL);

    public static final String RESPONSE_TYPE_NLP_SUMMARY = "nlp_summary";
    public static final String RESPONSE_TYPE_DETAILED_RECORDS = "detailed_records";
    public static final String RESPONSE_TYPE_HYBRID = "hybrid";
    public static final List<String> VALID_DATABASE_RESPONSE_TYPES = List.of(
            RESPONSE_TYPE_DETAILED_RECORDS,
            RESPONSE_TYPE_NLP_SUMMARY,
            RESPONSE_TYPE_HYBRID
    );

    public static final String WORKFLOW = "workflow";
    public static final String WORKFLOW_PHASE_0_TO_4 = "phase_0_to_4";
    public static final String WORKFLOW_DOCUMENT = "document";
    public static final String WORKFLOW_DATABASE = "database";
    public static final String WORKFLOW_GENERAL = "general";

    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_ORIGINAL_QUESTION = "original_question";
    public static final String RESULT_STANDALONE_QUERY = "standalone_query";
    public static final String RESULT_INTENT = "intent";
    public static final String RESULT_RESPONSE_TYPE = "response_type";
    public static final String RESULT_CONFIDENCE = "confidence";
    public static final String RESULT_REASONING = "reasoning";
    public static final String RESULT_PREFETCHED_DOCS = "prefetched_docs";
    public static final String RESULT_CONVERSATION_CONTEXT = "conversation_context";
    public static final String RESULT_CONVERSATION_ID = "conversation_id";
    public static final String RESULT_QUESTION_ID = "question_id";
    public static final String RESULT_USER_ID = "user_id";
    public static final String RESULT_USERNAME = "username";
    public static final String RESULT_INTERNAL_TYPE = "internal_type";
    public static final String RESULT_NLP_ANSWER = "nlp_answer";
    public static final String RESULT_CITATIONS = "citations";
    public static final String RESULT_TEXT_PAYLOAD = "text_payload";
    public static final String RESULT_DATA_PAYLOAD = "data_payload";

    public static final String SEARCH_FIELD_EXAMPLE_QUERIES = "example_queries";
    public static final String SEARCH_FIELD_TOPICS = "topics";
    public static final String SEARCH_FIELD_INTENT_SIGNALS = "intent_signals";
    public static final String SEARCH_FIELD_SOURCE = "source";
    public static final String SEARCH_FIELD_FILEPATH = "filepath";
    public static final String SEARCH_FIELD_BLOB_URI = "blob_uri";
    public static final String SEARCH_FIELD_CONTENT = "content";
    public static final String SEARCH_FIELD_PAGE_NUMBER = "page_number";
    public static final String SEARCH_FIELD_FOLDER_ID = "folder_id";
    public static final String SEARCH_FIELD_METADATA = "metadata";
    public static final String SEARCH_FIELD_DI_PAGE_SPANS = "di_page_spans";
    public static final String SEARCH_SCORE = "@search.score";

    public static final String AZURE_API_KEY_HEADER = "api-key";
    public static final String AZURE_CHAT_COMPLETIONS_PATH = "/openai/deployments/%s/chat/completions?api-version=%s";
    public static final String AZURE_SEARCH_PATH = "/indexes/%s/docs/search?api-version=%s";

    public static final String JSON_MESSAGES = "messages";
    public static final String JSON_ROLE = "role";
    public static final String JSON_ROLE_SYSTEM = "system";
    public static final String JSON_ROLE_USER = "user";
    public static final String JSON_CONTENT = "content";
    public static final String JSON_TEMPERATURE = "temperature";
    public static final String JSON_MAX_TOKENS = "max_tokens";
    public static final String JSON_RESPONSE_FORMAT = "response_format";
    public static final String JSON_TYPE = "type";
    public static final String JSON_OBJECT = "json_object";
    public static final String JSON_CHOICES = "choices";
    public static final String JSON_MESSAGE = "message";

    public static final String CONTENT_TYPE_PDF = "application/pdf";
    public static final String CONTENT_DISPOSITION_ATTACHMENT = "attachment";
    public static final String CONTENT_DISPOSITION_INLINE = "inline";
    public static final String CONTENT_DISPOSITION_FILENAME_FORMAT = "%s; filename=\"%s\"";
    public static final String HIGHLIGHTED_DOCS_FOLDER = "highlighted_docs";
    public static final String PDF_EXTENSION = ".pdf";
    public static final String PAGE_FRAGMENT_PREFIX = "#page=";

    public static final String GENERAL_GREETING_RESPONSE =
            "Hello! Ask me a question about your documents or data and I can help.";
    public static final String DATABASE_NOT_AVAILABLE_RESPONSE =
            "Database query handling is not available in this Spring workflow yet.";
    public static final String NO_ACCESSIBLE_DOCUMENTS_RESPONSE =
            "I couldn't find any accessible documents to answer your question.";
    public static final String DOCUMENT_RESPONSE_ERROR =
            "I encountered an error generating the document response.";
    public static final String NO_LLM_CONFIGURED_JSON =
            "{\"answer\":\"I could not generate an answer because no LLM is configured.\",\"raw_extractions\":[]}";
}
