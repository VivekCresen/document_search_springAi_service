-- =============================================================================
-- Document Search Spring AI Service — Complete Database Schema
-- Database : PostgreSQL 14+
-- Generated: 2026-05-13
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 0. Extensions
-- ---------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()

-- ---------------------------------------------------------------------------
-- 1. Schemas
-- ---------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS prestage;

-- =============================================================================
-- PUBLIC SCHEMA TABLES
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 2. file_metadata
--    Stores every file uploaded through the DocumentService.
--    filepath is a JSONB array that acts as a hierarchical blob path:
--      {"filePath":["CMRUS","USA-2023-651","NGRD","<hash>","Nominee","ABC.png"]}
--    filePath[0]  → folder / tenant segment
--    filePath[1]  → document-ID segment (UUID used by getBlobNameByDocumentId)
--    filePath[-1] → filename  (getFileName())
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS file_metadata (
    id               SERIAL PRIMARY KEY,
    filepath         JSONB           NOT NULL,
    created_by       VARCHAR(255)    NOT NULL,
    create_date      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    azure_blob_url   TEXT            NOT NULL,
    blob_name        TEXT            NOT NULL,
    file_size_in_mb  NUMERIC(12, 2),
    content_type     VARCHAR(255),
    status           VARCHAR(50)     NOT NULL DEFAULT 'UPLOADED',

    CONSTRAINT chk_file_metadata_status
        CHECK (status IN ('UPLOADED','INDEXING','INDEXED','FAILED','DELETED'))
);

-- Exact JSONB match — used by findByFilePathJson() / findByFilePath()
CREATE UNIQUE INDEX IF NOT EXISTS ux_file_metadata_filepath
    ON file_metadata USING btree (filepath);

-- Blob name lookup — used by getBlobNameByDocumentId()
CREATE INDEX IF NOT EXISTS idx_file_metadata_blob_name
    ON file_metadata (blob_name);

-- Uploader filter — used by findByCreatedBy()
CREATE INDEX IF NOT EXISTS idx_file_metadata_created_by
    ON file_metadata (created_by);

-- Status filter — used by findByStatus()
CREATE INDEX IF NOT EXISTS idx_file_metadata_status
    ON file_metadata (status);

-- Folder filter — filePath[0] — used by findByFolderId()
CREATE INDEX IF NOT EXISTS idx_file_metadata_folder
    ON file_metadata ((filepath -> 'filePath' ->> 0));

-- Document-ID filter — filePath[1] — used by findByDocumentId()
CREATE INDEX IF NOT EXISTS idx_file_metadata_document_id
    ON file_metadata ((filepath -> 'filePath' ->> 1));


-- =============================================================================
-- PRESTAGE SCHEMA TABLES
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 3. prestage.documents
--    Hierarchical folder / file tree for the pre-stage pipeline.
--    Self-referencing: parent_id → id (NULL for root nodes).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS prestage.documents (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(512)    NOT NULL,
    is_file     BOOLEAN         NOT NULL DEFAULT FALSE,
    parent_id   INTEGER         REFERENCES prestage.documents(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_docs_is_file
    ON prestage.documents (is_file);

CREATE INDEX IF NOT EXISTS idx_docs_parent_id
    ON prestage.documents (parent_id);

CREATE INDEX IF NOT EXISTS idx_docs_name
    ON prestage.documents (name);


-- ---------------------------------------------------------------------------
-- 4. prestage.document_repository_user_mapping
--    Controls which folders a user is RESTRICTED from accessing.
--    One row per (user, restricted-folder) pair.
--    Used by UserAccessServiceImpl.getRestrictedFolders()
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS prestage.document_repository_user_mapping (
    id              SERIAL PRIMARY KEY,
    user_name       VARCHAR(200)    NOT NULL,
    folders_access  INTEGER,                         -- restricted folder ID
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- Fast lookup by username (most common query)
CREATE INDEX IF NOT EXISTS idx_drm_user_name
    ON prestage.document_repository_user_mapping (user_name);

-- Join / filter by folder
CREATE INDEX IF NOT EXISTS idx_drm_folders_access
    ON prestage.document_repository_user_mapping (folders_access);


-- ---------------------------------------------------------------------------
-- 5. prestage.files_in_index
--    Tracks the Azure AI Search indexing lifecycle per blob URI.
--    status lifecycle:
--      ingestion_inp → processing → stable
--                               └→ failed
--    Used by UserAccessServiceImpl.getUnstableFileUris()
--          & DocumentServiceImpl.getAccessibleStableFiles()
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS prestage.files_in_index (
    id          BIGSERIAL PRIMARY KEY,
    blob_uri    TEXT            NOT NULL UNIQUE,
    status      VARCHAR(50)     NOT NULL DEFAULT 'ingestion_inp',
    folder_id   INTEGER         REFERENCES prestage.documents(id) ON DELETE SET NULL,
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_fii_status
    ON prestage.files_in_index (status);

CREATE INDEX IF NOT EXISTS idx_fii_blob_uri
    ON prestage.files_in_index (blob_uri);

CREATE INDEX IF NOT EXISTS idx_fii_folder_id
    ON prestage.files_in_index (folder_id);


-- ---------------------------------------------------------------------------
-- 6. prestage.db_search_sources
--    Registry of database views available for NL-to-SQL routing.
--    One row per named SQL view the LLM can query.
--    routing_metadata (JSONB) carries intent signals used by the classifier.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS prestage.db_search_sources (
    id                BIGSERIAL PRIMARY KEY,
    view_name         VARCHAR(255)    NOT NULL UNIQUE,
    description       TEXT,
    routing_metadata  JSONB           NOT NULL DEFAULT '{}'::jsonb,
    active            BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_db_search_sources_active
    ON prestage.db_search_sources (active);

CREATE INDEX IF NOT EXISTS idx_db_search_sources_view_name
    ON prestage.db_search_sources (view_name);


-- ---------------------------------------------------------------------------
-- 7. prestage.db_search_schema_versions
--    Versioned JSON column schema for each db_search_sources entry.
--    The SchemaRegistryService loads the latest active version per source.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS prestage.db_search_schema_versions (
    id          BIGSERIAL PRIMARY KEY,
    source_id   BIGINT          NOT NULL,
    schema_json JSONB           NOT NULL,
    version     INTEGER         NOT NULL DEFAULT 1,
    active      BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_db_search_schema_source
        FOREIGN KEY (source_id)
        REFERENCES prestage.db_search_sources(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    CONSTRAINT uq_db_search_schema_source_version
        UNIQUE (source_id, version)
);

CREATE INDEX IF NOT EXISTS idx_db_search_schema_source_active
    ON prestage.db_search_schema_versions (source_id, active);


-- ---------------------------------------------------------------------------
-- 8. prestage.db_search_hist_sessions
--    One session row per (chat_id, user_id) pair.
--    Created / fetched by ChatHistoryServiceImpl.getOrCreateSession()
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS prestage.db_search_hist_sessions (
    id               SERIAL PRIMARY KEY,
    chat_id          VARCHAR(50)     NOT NULL,
    user_id          BIGINT,
    started_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    last_activity_at TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    message_count    INTEGER         NOT NULL DEFAULT 0,

    CONSTRAINT db_search_hist_sessions_chat_user_key
        UNIQUE (chat_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_sess_chat_id
    ON prestage.db_search_hist_sessions (chat_id);

CREATE INDEX IF NOT EXISTS idx_sess_user_id
    ON prestage.db_search_hist_sessions (user_id);


-- ---------------------------------------------------------------------------
-- 9. prestage.db_search_hist_messages
--    Append-only chat message log.
--    message_type: 'USER' | 'ASSISTANT' | 'SYSTEM'
--    metadata (JSONB): intent, standalone_query, citations, token counts, etc.
--    Used by ChatHistoryServiceImpl.appendMessage() / getRecentContext()
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS prestage.db_search_hist_messages (
    id            BIGSERIAL PRIMARY KEY,
    chat_id       VARCHAR(50)     NOT NULL,
    user_id       BIGINT,
    timestamp     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    message_type  VARCHAR(30)     NOT NULL,
    content       TEXT,
    metadata      JSONB,

    CONSTRAINT chk_hist_message_type
        CHECK (message_type IN ('USER','ASSISTANT','SYSTEM'))
);

CREATE INDEX IF NOT EXISTS idx_msg_chat_id
    ON prestage.db_search_hist_messages (chat_id);

CREATE INDEX IF NOT EXISTS idx_msg_user_id
    ON prestage.db_search_hist_messages (user_id);

CREATE INDEX IF NOT EXISTS idx_msg_chat_user_ts
    ON prestage.db_search_hist_messages (chat_id, user_id, timestamp DESC);


-- ---------------------------------------------------------------------------
-- 10. prestage.chat_history
--    Unified JSON storage for all chat history per user.
--    Columns: id, user_id, user_name, email_id, total_sessions, total_qa_pairs, conversations (jsonb), created_at, updated_at
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS prestage.chat_history (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT NOT NULL UNIQUE,
    user_name        VARCHAR(255),
    email_id         VARCHAR(255),
    total_sessions   INTEGER NOT NULL DEFAULT 0,
    total_qa_pairs   INTEGER NOT NULL DEFAULT 0,
    conversations    JSONB NOT NULL DEFAULT '{}',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);


-- =============================================================================
-- SAMPLE DATA — schema registry seed
-- (Remove before production if populated by the application)
-- =============================================================================

-- Example: register one view for NL-to-SQL routing
-- INSERT INTO prestage.db_search_sources (view_name, description, routing_metadata)
-- VALUES (
--     'v_monitoring_activity',
--     'Monitoring activities and their statuses',
--     '{"intent_signals":["monitoring","activity","status"],"topics":["compliance"]}'::jsonb
-- );
--
-- INSERT INTO prestage.db_search_schema_versions (source_id, schema_json, version, active)
-- VALUES (
--     (SELECT id FROM prestage.db_search_sources WHERE view_name = 'v_monitoring_activity'),
--     '{"columns":[{"name":"id","type":"INTEGER"},{"name":"status","type":"VARCHAR"}]}'::jsonb,
--     1,
--     TRUE
-- );

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    fullname VARCHAR(100) NOT NULL,
    email VARCHAR(100) NOT NULL UNIQUE,
    password VARCHAR(120) NOT NULL
);


-- Index for faster lookups during login
CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);