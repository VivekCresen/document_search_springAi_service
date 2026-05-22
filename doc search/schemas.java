// =============================================================================
// schemas.java — Complete Schema Definitions for Document Search Chatbot
// Covers: REST API (inbound/outbound), internal service models,
//         Azure Search metadata, Azure Blob metadata, and PostgreSQL DDL.
// =============================================================================

package com.chatbot.docsearch.schemas;

import java.time.Instant;
import java.util.List;
import java.util.Map;


// ═════════════════════════════════════════════════════════════════════════════
// SECTION 1 — INBOUND REQUEST  (Angular UI → Spring Boot)
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Outer envelope the Angular UI sends for every chat message.
 * Angular wraps requestData inside this to keep header metadata separate.
 */
class EnvelopeRequest {
    RequestMetadata metadata;   // optional trace / correlation fields
    RequestData     requestData;
}

/** Tracing / correlation fields — populated by API Gateway or Angular interceptor. */
class RequestMetadata {
    String serviceReferenceId;  // optional correlation id from gateway
}

/**
 * Core payload from Angular chat UI.
 * All fields except email, username, question are optional on first message.
 */
class RequestData {
    String  requestId;       // client-generated UUID for this request
    String  authorization;   // Bearer token from Azure AD / OAuth
    String  xTenantId;       // tenant identifier for multi-tenant deployments
    String  email;           // user's email — used as security principal for folder ACL
    String  username;        // display name (cosmetic, not used for auth)
    String  question;        // raw question text typed by the user
    Integer questionId;      // auto-increment question sequence within a conversation
    String  conversationId;  // UUID — null on first message, echoed back by server
    String  productName;     // e.g. "MM" — identifies which chatbot product
    String  profile;         // e.g. "dev" / "prod" — environment tag
    Integer userId;          // internal numeric user id for blob naming & history
}


// ═════════════════════════════════════════════════════════════════════════════
// SECTION 2 — OUTBOUND RESPONSE  (Spring Boot → Angular UI)
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Outer envelope returned to Angular.
 * Angular unwraps responseData and renders answer + citations.
 */
class EnvelopeResponse {
    ResponseData responseData;
}

/**
 * Full response payload.
 * Angular uses responseType to decide whether to render a text bubble or a table.
 * citations map is rendered as a clickable source panel beside the chat bubble.
 */
class ResponseData {
    String  requestId;
    String  authorization;
    String  xTenantId;
    String  email;
    String  username;
    Integer questionId;
    String  responseType;          // "text" | "table" | "text/table"
    List<AnswerItem>          answer;      // always one item; list kept for extensibility
    Map<String, CitationItem> citations;  // key = "1","2",… — ordered citation references
    String  conversationId;
    String  productName;
    String  profile;
    Integer userId;
    String  responseTimestamp;     // "MM/dd/yyyy HH:mm:ss"
}

/** One answer bubble — either plain text, a table payload, or both (hybrid). */
class AnswerItem {
    String                    text;   // NLP answer string; null for pure-table responses
    List<Map<String, Object>> table;  // row list; null for pure-text responses
}

/**
 * Single citation entry rendered in the Angular source panel.
 * Both SAS URLs expire after configurable hours (default 720 h).
 */
class CitationItem {
    String       fileName;          // original PDF filename shown to user
    String       downloadLink;      // SAS URL — browser triggers file download
    String       viewLink;          // SAS URL with content-disposition=inline (browser renders PDF)
    List<Integer> highlightedPages; // 1-based page numbers that contain highlights
}


// ═════════════════════════════════════════════════════════════════════════════
// SECTION 3 — INTERNAL SERVICE MODELS  (passed between Spring services)
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Standalone (rewritten) question produced by StandaloneQueryService.
 * Passed downstream so every service works with the resolved question,
 * not the raw follow-up text the user typed.
 */
class StandaloneQuery {
    String original;    // raw text from RequestData.question
    String rewritten;   // LLM-rewritten, fully self-contained question
    String english;     // English translation (same as rewritten if already English)
}

/**
 * Document chunk returned by Azure AI Search.
 * Carries everything needed for RAG context-building and PDF highlighting.
 */
class SearchResultDocument {
    String          source;       // PDF filename  e.g. "policy_v2.pdf"
    String          filepath;     // relative blob path inside container
    String          blobUri;      // full Azure Blob URI — used to resolve download path
    String          content;      // chunk text sent to LLM as RAG context
    Object          pageNumber;   // page number from index (int or "N/A")
    String          folderId;     // folder the file belongs to — checked against ACL
    double          score;        // Azure Search relevance score
    List<String>    topics;           // metadata field used during intent pre-fetch
    List<String>    exampleQueries;   // metadata field used during intent pre-fetch
    List<String>    intentSignals;    // metadata field used during intent pre-fetch
    List<DiPageSpan> diPageSpans;     // coordinate spans from Azure Document Intelligence
}

/**
 * A single Document Intelligence page span.
 * Carries the polygon (in inches) needed for coordinate-based PDF highlighting.
 */
class DiPageSpan {
    int    page;           // 1-based page number
    String paragraphText;  // paragraph text — used for fuzzy matching to LLM extractions
    List<Double> polygon;  // [x1,y1,x2,y2,x3,y3,x4,y4] in inches — convert ×72 → PDF points
}

/**
 * One verbatim extraction from the LLM JSON response (raw_extractions[]).
 * Validated against actual document content before being used for highlighting.
 */
class LlmExtraction {
    String exactText;  // verbatim quote from source document — NOT translated
    String source;     // filename — must match a SearchResultDocument.source
    String page;       // page number string as returned by LLM
    String explains;   // brief explanation in the question's language
}

/**
 * Validated extraction after fuzzy-match check against document content.
 * DiPageSpans are copied from the matched document chunk for highlighting.
 */
class ValidatedExtraction {
    String           text;          // exact_text confirmed to exist in document
    String           source;        // confirmed source filename
    String           page;          // page from extraction or document chunk
    String           relevance;     // explains field
    List<DiPageSpan> diPageSpans;   // spans from the matched chunk
}

/**
 * Grouped extractions for one PDF — one entry per unique source file.
 * Used by CitationService to produce one citation + highlighted PDF per file.
 */
class ConsolidatedPassage {
    String           source;               // PDF filename
    String           page;                 // comma-separated page numbers
    String           text;                 // all passages joined with newlines
    List<String>     individualPassages;   // each extraction separately (for multi-highlight)
    String           relevance;            // joined explains strings
    List<DiPageSpan> diPageSpans;          // union of spans across all extractions for this PDF
}

/**
 * Result from the PDF highlighting step.
 * Returned by PdfHighlightService before SAS URL generation.
 */
class HighlightResult {
    byte[]       pdfBytes;          // highlighted PDF binary
    List<Integer> annotatedPages;   // pages where highlights were actually drawn
    String       destBlobName;      // target blob path  e.g. "highlighted_docs/file_conv1_q2.pdf"
    boolean      hasHighlights;     // false → uploaded without highlights (no match found)
}


// ═════════════════════════════════════════════════════════════════════════════
// SECTION 4 — AZURE AI SEARCH INDEX DOCUMENT SCHEMA
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Represents one document chunk in the Azure AI Search index.
 * Ingestion pipeline (not in scope here) writes these fields;
 * RAG pipeline reads them via SearchResultDocument above.
 *
 * OData filter examples used in security:
 *   folder_id ne '1231' and blob_uri ne 'https://…/file.pdf'
 */
class AzureSearchIndexDocument {
    // ── Core content ──────────────────────────────────────────────────────
    String  id;             // index key — typically "filename_pageN_chunkM"
    String  content;        // chunk text
    String  source;         // PDF filename
    String  filepath;       // relative path inside blob container
    String  blobUri;        // full Azure Blob URI  — filterable for stability check
    int     pageNumber;     // 1-based page number  — filterable

    // ── Security / ACL ───────────────────────────────────────────────────
    String  folderId;       // folder this file belongs to — filterable for ACL

    // ── Intent pre-fetch metadata ─────────────────────────────────────────
    List<String> topics;           // searchable in metadata pre-fetch
    List<String> exampleQueries;   // searchable in metadata pre-fetch
    List<String> intentSignals;    // searchable in metadata pre-fetch

    // ── DI coordinate metadata (stored as JSON string) ───────────────────
    /**
     * Stored as a JSON string in the index.
     * Parsed into DiPageSpan list during search result processing.
     *
     * JSON shape:
     * {
     *   "di_page_spans": [
     *     {
     *       "page": 1,
     *       "paragraph_text": "Lorem ipsum…",
     *       "polygon": [1.2, 2.3, 3.4, 2.3, 3.4, 4.5, 1.2, 4.5]
     *     }
     *   ]
     * }
     */
    String  metadata;
}


// ═════════════════════════════════════════════════════════════════════════════
// SECTION 5 — AZURE BLOB STORAGE METADATA
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Custom metadata tags stored on each blob (original + highlighted).
 * Azure Blob SDK sets these as key-value string pairs on the blob object.
 */
class BlobMetadata {
    // ── Original document blob ───────────────────────────────────────────
    String folderId;        // mirrors index folderId for consistency
    String uploadedBy;      // email of uploader
    String uploadedAt;      // ISO-8601 timestamp of upload
    String originalFileName; // human-friendly display name
    String status;          // "stable" | "ingestion_inp" | "to_be_deleted"
                            // only "stable" blobs are served to users

    // ── Highlighted document blob ────────────────────────────────────────
    String sourceBlob;      // path of the original PDF this was derived from
    String convId;          // conversation ID used in blob naming
    String qId;             // question ID used in blob naming
    String userId;          // user ID used in blob naming
    String createdAt;       // ISO-8601 timestamp of highlight generation
}

/**
 * Highlighted blob naming convention (deterministic — same inputs → same name).
 *
 * Pattern:  highlighted_docs/{baseName}[_conv{convId}_q{qId}_user{userId}].pdf
 * Example:  highlighted_docs/policy_v2_conv16_q3_user42.pdf
 *
 * Deterministic naming means re-running the same question overwrites the
 * previous highlighted blob instead of accumulating orphan files.
 */
class HighlightedBlobName {
    String folder;      // always "highlighted_docs"
    String baseName;    // original filename without extension
    String convId;      // conversation id suffix component
    String qId;         // question id suffix component
    String userId;      // user id suffix component
    // final path = folder + "/" + baseName + "_conv" + convId + "_q" + qId + "_user" + userId + ".pdf"
}

/**
 * SAS (Shared Access Signature) token parameters.
 * Two variants are generated per citation: download and view.
 */
class SasTokenParams {
    String  accountName;
    String  containerName;
    String  blobName;
    String  accountKey;
    boolean readPermission;      // always true
    Instant expiry;              // now + HIGHLIGHTED_SAS_EXPIRY_HOURS (default 720 h)
    String  contentType;         // null for download; "application/pdf" for view
    String  contentDisposition;  // null for download; "inline" for view
    Integer pageFragment;        // appended as #page=N to the final URL
}


// ═════════════════════════════════════════════════════════════════════════════
// SECTION 6 — LLM PROMPT / RESPONSE CONTRACT
// ═════════════════════════════════════════════════════════════════════════════

/**
 * The JSON object the LLM is instructed to return for every RAG question.
 * Spring parses this with Jackson after stripping any accidental markdown fences.
 */
class LlmRagResponse {
    String             answer;          // paraphrased answer in question's language
    List<LlmExtraction> rawExtractions; // verbatim quotes with source attribution
}

/**
 * The JSON object the LLM returns for standalone query rewriting.
 * Extracted from "REFORMULATED QUESTION: …\nENGLISH TRANSLATION: …" format.
 */
class LlmStandaloneResponse {
    String reformulatedQuestion; // self-contained question (original language)
    String englishTranslation;   // English version for downstream processing
}

/**
 * The JSON object the LLM returns for intent classification.
 * Only "document" intent is used in this implementation.
 */
class LlmIntentResponse {
    String intent;       // "document" | "database" | "general"  — only "document" processed
    String responseType; // null for document intent
    double confidence;   // 0.0 – 1.0
    String reasoning;    // brief explanation (for logging only)
}


// ═════════════════════════════════════════════════════════════════════════════
// SECTION 7 — POSTGRESQL TABLE DDL
// ═════════════════════════════════════════════════════════════════════════════

/*
────────────────────────────────────────────────────────────────
TABLE 1: demo.document_repository_user_mapping
Purpose : Folder-level access control.
          folders_access lists folder IDs the user is RESTRICTED from.
          User can access ALL folders EXCEPT those listed here.
────────────────────────────────────────────────────────────────
CREATE TABLE demo.document_repository_user_mapping (
    id              SERIAL PRIMARY KEY,
    user_name       VARCHAR(255) NOT NULL,   -- email used as principal
    folders_access  INTEGER,                 -- ONE restricted folder_id per row
                                             -- NULL row = no restrictions
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

-- Index for fast permission lookup on every query
CREATE INDEX idx_drm_user_name ON demo.document_repository_user_mapping(user_name);


────────────────────────────────────────────────────────────────
TABLE 2: demo.documents
Purpose : File/folder registry.
          is_file=false rows are folders; their id is used as folder_id
          in document_repository_user_mapping and the search index.
────────────────────────────────────────────────────────────────
CREATE TABLE demo.documents (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(512) NOT NULL,
    is_file     BOOLEAN      NOT NULL DEFAULT FALSE,  -- false = folder, true = file
    parent_id   INTEGER REFERENCES demo.documents(id),
    created_at  TIMESTAMPTZ  DEFAULT NOW()
);

CREATE INDEX idx_docs_is_file ON demo.documents(is_file);


────────────────────────────────────────────────────────────────
TABLE 3: demo.files_in_index
Purpose : Real-time file stability tracking.
          Only status='stable' blobs are served to users.
          Ingestion pipeline updates this table as files are processed.
────────────────────────────────────────────────────────────────
CREATE TABLE demo.files_in_index (
    id          SERIAL PRIMARY KEY,
    blob_uri    TEXT         NOT NULL UNIQUE,  -- full Azure Blob URI
    status      VARCHAR(50)  NOT NULL          -- 'stable' | 'ingestion_inp' | 'to_be_deleted'
                             DEFAULT 'ingestion_inp',
    folder_id   INTEGER,                       -- denormalised for fast filter building
    updated_at  TIMESTAMPTZ  DEFAULT NOW()
);

CREATE INDEX idx_fii_status   ON demo.files_in_index(status);
CREATE INDEX idx_fii_blob_uri ON demo.files_in_index(blob_uri);


────────────────────────────────────────────────────────────────
TABLE 4: demo.db_search_hist_sessions
Purpose : One row per conversation session.
          Composite unique key (chat_id, user_id) allows the same
          chat_id to be reused across different users safely.
────────────────────────────────────────────────────────────────
CREATE TABLE demo.db_search_hist_sessions (
    id                SERIAL PRIMARY KEY,
    chat_id           VARCHAR(50)  NOT NULL,       -- client-supplied conversation UUID
    user_id           INTEGER,                     -- numeric user id (nullable for anonymous)
    started_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_activity_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    message_count     INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT db_search_hist_sessions_chat_user_key UNIQUE (chat_id, user_id)
);

CREATE INDEX idx_sess_chat_id ON demo.db_search_hist_sessions(chat_id);
CREATE INDEX idx_sess_user_id ON demo.db_search_hist_sessions(user_id);


────────────────────────────────────────────────────────────────
TABLE 5: demo.db_search_hist_messages
Purpose : Every user question and assistant answer per conversation.
          Fetched (DESC, LIMIT 10) to build conversation context for
          standalone query rewriting.
────────────────────────────────────────────────────────────────
CREATE TABLE demo.db_search_hist_messages (
    id            BIGSERIAL PRIMARY KEY,
    chat_id       VARCHAR(50)  NOT NULL,
    user_id       INTEGER,
    timestamp     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    message_type  VARCHAR(30)  NOT NULL,   -- 'user_question' | 'assistant_answer'
    content       TEXT,                    -- raw text of the message
    metadata      JSONB                    -- workflow metadata: intent, workflow, success, etc.
);

CREATE INDEX idx_msg_chat_id ON demo.db_search_hist_messages(chat_id);
CREATE INDEX idx_msg_user_id ON demo.db_search_hist_messages(user_id);
-- Composite index for the common get_recent_context query pattern
CREATE INDEX idx_msg_chat_user ON demo.db_search_hist_messages(chat_id, user_id);


────────────────────────────────────────────────────────────────
METADATA JSONB SHAPE stored in db_search_hist_messages.metadata:
{
  "intent":          "document",
  "workflow":        "document",
  "success":         true,
  "routed_view":     null,
  "had_error":       false,
  "error_type":      null,
  "error_details":   null,
  "standalone_query":"What is the retention policy for financial records?"
}
────────────────────────────────────────────────────────────────
*/


// ═════════════════════════════════════════════════════════════════════════════
// SECTION 8 — PERMISSION SERVICE ENDPOINTS  (Spring REST controllers)
// ═════════════════════════════════════════════════════════════════════════════

/** POST /permissions/check — body */
class PermissionCheckRequest {
    List<String> folderIds;  // folder IDs to test access for
}

/** POST /permissions/check — response */
class PermissionCheckResponse {
    String              username;
    Map<String, Boolean> permissions;  // folderId → hasAccess (true = allowed)
    int                 restrictedCount;
    int                 accessibleCount;
}

/** GET /permissions/my-access — response */
class MyAccessResponse {
    String       username;
    int          totalRestrictedFolders;
    List<String> restrictedFolderIds;
    String       note;  // "You can access all folders except those listed above"
}


// ═════════════════════════════════════════════════════════════════════════════
// SECTION 9 — APPLICATION CONFIGURATION PROPERTIES
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Maps to application.yml / environment variables.
 * Use @ConfigurationProperties(prefix="chatbot") in Spring Boot.
 */
class ChatbotConfigProperties {

    // Azure OpenAI
    String azureOpenAiEndpoint;
    String azureOpenAiKey;
    String azureOpenAiDeployment;          // reasoning/completion model  e.g. "o4-mini"
    String azureOpenAiRagDeployment;       // RAG answer model  e.g. "gpt-4o-mini"
    String azureOpenAiEmbeddingDeployment; // embedding model  e.g. "text-embedding-ada-002"
    String azureApiVersion;                // e.g. "2024-02-01"

    // Azure AI Search
    String  azureSearchEndpoint;
    String  azureSearchKey;
    String  azureSearchIndexName;          // e.g. "demo"

    // Azure Blob Storage
    String  azureStorageAccountName;
    String  azureStorageAccountKey;
    String  azureStorageConnectionString;
    String  indexingContainerName;         // source PDF container  e.g. "destination-docs"
    String  highlightedFolder;             // sub-folder  e.g. "highlighted_docs"
    int     highlightedSasExpiryHours;     // SAS token validity  e.g. 720

    // PostgreSQL
    String  dbHost;
    String  dbName;
    String  dbUser;
    String  dbPassword;
    int     dbPort;

    // Conversation
    int     conversationCacheSize;         // max in-memory cached conversations  e.g. 500
    int     conversationTimeoutSeconds;    // eviction after inactivity  e.g. 3600
    int     requestTimeoutSeconds;         // per-request processing limit  e.g. 500
    int     maxWorkers;                    // thread pool size  e.g. 50
    int     chatHistoryMessages;           // messages fetched for context  e.g. 10

    // Security
    String  targetEndpoint;                // backend URL for response forwarding
}
