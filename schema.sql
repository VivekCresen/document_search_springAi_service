-- =============================================================================
-- EXTENSIONS
-- =============================================================================
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- =============================================================================
-- SCHEMA
-- =============================================================================
CREATE SCHEMA IF NOT EXISTS prestage;

-- =============================================================================
-- USERS TABLE
-- =============================================================================
CREATE TABLE IF NOT EXISTS prestage.users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    username       VARCHAR(50)  NOT NULL UNIQUE,
    fullname       VARCHAR(100) NOT NULL,
    email          VARCHAR(100) NOT NULL UNIQUE,
    password       VARCHAR(120) NOT NULL,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_users_username
    ON prestage.users(username);

CREATE INDEX IF NOT EXISTS idx_users_email
    ON prestage.users(email);

-- =============================================================================
-- FILE METADATA
-- =============================================================================
CREATE TABLE IF NOT EXISTS prestage.file_metadata (
    id BIGSERIAL PRIMARY KEY,

    filepath JSONB NOT NULL,

    user_id UUID NOT NULL,

    created_by VARCHAR(255) NOT NULL,

    create_date TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    azure_blob_url TEXT NOT NULL,
    blob_name TEXT NOT NULL,

    file_size_in_mb NUMERIC(12,2),

    content_type VARCHAR(255),

    status VARCHAR(50) NOT NULL DEFAULT 'UPLOADED',

    CONSTRAINT fk_file_metadata_user
        FOREIGN KEY (user_id)
        REFERENCES prestage.users(id)
        ON DELETE CASCADE,

    CONSTRAINT chk_file_metadata_status
        CHECK (
            status IN (
                'UPLOADED',
                'INDEXING',
                'INDEXED',
                'FAILED',
                'DELETED'
            )
        )
);

-- =============================================================================
-- DOCUMENTS
-- =============================================================================
CREATE TABLE IF NOT EXISTS prestage.documents (
    id BIGSERIAL PRIMARY KEY,

    name VARCHAR(512) NOT NULL,

    is_file BOOLEAN NOT NULL DEFAULT FALSE,

    parent_id BIGINT,

    created_by UUID,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_documents_parent
        FOREIGN KEY (parent_id)
        REFERENCES prestage.documents(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_documents_user
        FOREIGN KEY (created_by)
        REFERENCES prestage.users(id)
        ON DELETE SET NULL
);

-- =============================================================================
-- DOCUMENT REPOSITORY USER MAPPING
-- =============================================================================
CREATE TABLE IF NOT EXISTS prestage.document_repository_user_mapping (
    id BIGSERIAL PRIMARY KEY,

    user_id UUID NOT NULL,

    user_name VARCHAR(200) NOT NULL,

    folders_access BIGINT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_drum_user
        FOREIGN KEY (user_id)
        REFERENCES prestage.users(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_drum_folder
        FOREIGN KEY (folders_access)
        REFERENCES prestage.documents(id)
        ON DELETE CASCADE
);

-- =============================================================================
-- FILES IN INDEX
-- =============================================================================
CREATE TABLE IF NOT EXISTS prestage.files_in_index (
    id BIGSERIAL PRIMARY KEY,

    blob_uri TEXT NOT NULL UNIQUE,

    status VARCHAR(50) NOT NULL DEFAULT 'ingestion_inp',

    folder_id BIGINT,

    indexed_by UUID,

    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_files_index_folder
        FOREIGN KEY (folder_id)
        REFERENCES prestage.documents(id)
        ON DELETE SET NULL,

    CONSTRAINT fk_files_index_user
        FOREIGN KEY (indexed_by)
        REFERENCES prestage.users(id)
        ON DELETE SET NULL
);

-- =============================================================================
-- DB SEARCH SOURCES
-- =============================================================================
CREATE TABLE IF NOT EXISTS prestage.db_search_sources (
    id BIGSERIAL PRIMARY KEY,

    view_name VARCHAR(255) NOT NULL UNIQUE,

    description TEXT,

    routing_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,

    active BOOLEAN NOT NULL DEFAULT TRUE,

    created_by UUID,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_db_search_sources_user
        FOREIGN KEY (created_by)
        REFERENCES prestage.users(id)
        ON DELETE SET NULL
);

-- =============================================================================
-- DB SEARCH SCHEMA VERSIONS
-- =============================================================================
CREATE TABLE IF NOT EXISTS prestage.db_search_schema_versions (
    id BIGSERIAL PRIMARY KEY,

    source_id BIGINT NOT NULL,

    schema_json JSONB NOT NULL,

    version INTEGER NOT NULL DEFAULT 1,

    active BOOLEAN NOT NULL DEFAULT TRUE,

    created_by UUID,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_db_search_schema_source
        FOREIGN KEY (source_id)
        REFERENCES prestage.db_search_sources(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_db_search_schema_user
        FOREIGN KEY (created_by)
        REFERENCES prestage.users(id)
        ON DELETE SET NULL,

    CONSTRAINT uq_db_search_schema_source_version
        UNIQUE(source_id, version)
);

-- =============================================================================
-- DB SEARCH HISTORY SESSIONS
-- =============================================================================
CREATE TABLE IF NOT EXISTS prestage.db_search_hist_sessions (
    id BIGSERIAL PRIMARY KEY,

    chat_id VARCHAR(50) NOT NULL,

    user_id UUID NOT NULL,

    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    message_count INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT fk_hist_sessions_user
        FOREIGN KEY (user_id)
        REFERENCES prestage.users(id)
        ON DELETE CASCADE,

    CONSTRAINT uq_hist_chat_user
        UNIQUE(chat_id, user_id)
);

-- =============================================================================
-- DB SEARCH HISTORY MESSAGES
-- =============================================================================
CREATE TABLE IF NOT EXISTS prestage.db_search_hist_messages (
    id BIGSERIAL PRIMARY KEY,

    session_id BIGINT,

    chat_id VARCHAR(50) NOT NULL,

    user_id UUID NOT NULL,

    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    message_type VARCHAR(30) NOT NULL,

    content TEXT,

    metadata JSONB,

    CONSTRAINT fk_hist_messages_session
        FOREIGN KEY (session_id)
        REFERENCES prestage.db_search_hist_sessions(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_hist_messages_user
        FOREIGN KEY (user_id)
        REFERENCES prestage.users(id)
        ON DELETE CASCADE,

    CONSTRAINT chk_hist_message_type
        CHECK (
            message_type IN (
                'USER',
                'ASSISTANT',
                'SYSTEM'
            )
        )
);

-- =============================================================================
-- CHAT HISTORY
-- =============================================================================
CREATE TABLE IF NOT EXISTS prestage.chat_history (
    id BIGSERIAL PRIMARY KEY,

    user_id UUID NOT NULL UNIQUE,

    user_name VARCHAR(255),

    email_id VARCHAR(255),

    total_sessions INTEGER NOT NULL DEFAULT 0,

    total_qa_pairs INTEGER NOT NULL DEFAULT 0,

    conversations JSONB NOT NULL DEFAULT '{}',

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_chat_history_user
        FOREIGN KEY (user_id)
        REFERENCES prestage.users(id)
        ON DELETE CASCADE
);