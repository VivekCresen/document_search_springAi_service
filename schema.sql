-- Database schema for Document Search Service
-- This schema defines tables for document management, user access control,
-- file indexing, and chat history in a PostgreSQL database.

-- Create schema for prestage data
CREATE SCHEMA IF NOT EXISTS prestage;

-- Documents table: Hierarchical structure for folders and files
CREATE TABLE IF NOT EXISTS prestage.documents (
    id          SERIAL PRIMARY KEY,        -- Auto-incrementing primary key
    name        VARCHAR(512) NOT NULL,     -- Document or folder name
    is_file     BOOLEAN      NOT NULL DEFAULT FALSE,  -- True if file, false if folder
    parent_id   INTEGER,                   -- Parent folder ID (null for root)
    created_at  TIMESTAMPTZ  DEFAULT NOW() -- Creation timestamp
);

-- Index for faster queries on file/folder type
CREATE INDEX IF NOT EXISTS idx_docs_is_file
    ON prestage.documents(is_file);

-- User mapping table: Controls user access to document repositories
CREATE TABLE IF NOT EXISTS prestage.document_repository_user_mapping (
    id              SERIAL PRIMARY KEY,        -- Auto-incrementing primary key
    user_name       VARCHAR(255) NOT NULL,     -- Username for access control
    folders_access  INTEGER,                   -- Folder access permissions
    created_at      TIMESTAMPTZ DEFAULT NOW(), -- Record creation timestamp
    updated_at      TIMESTAMPTZ DEFAULT NOW()  -- Last update timestamp
);

-- Indexes for user access queries
CREATE INDEX IF NOT EXISTS idx_drm_user_name
    ON prestage.document_repository_user_mapping(user_name);

CREATE INDEX IF NOT EXISTS idx_drm_folders_access
    ON prestage.document_repository_user_mapping(folders_access);

-- Files in index table: Tracks files being processed for search indexing
CREATE TABLE IF NOT EXISTS prestage.files_in_index (
    id          SERIAL PRIMARY KEY,        -- Auto-incrementing primary key
    blob_uri    TEXT        NOT NULL UNIQUE,  -- Azure Blob Storage URI
    status      VARCHAR(50) NOT NULL DEFAULT 'ingestion_inp',  -- Processing status
    folder_id   INTEGER,                   -- Associated folder ID
    updated_at  TIMESTAMPTZ DEFAULT NOW()  -- Last update timestamp
);

-- Indexes for file indexing operations
CREATE INDEX IF NOT EXISTS idx_fii_status
    ON prestage.files_in_index(status);

CREATE INDEX IF NOT EXISTS idx_fii_blob_uri
    ON prestage.files_in_index(blob_uri);

CREATE INDEX IF NOT EXISTS idx_fii_folder_id
    ON prestage.files_in_index(folder_id);

-- Chat sessions table: Tracks conversation sessions for document queries
CREATE TABLE IF NOT EXISTS prestage.db_search_hist_sessions (
    id                SERIAL PRIMARY KEY,        -- Auto-incrementing primary key
    chat_id           VARCHAR(50) NOT NULL,      -- Unique chat session identifier
    user_id           INTEGER,                   -- Associated user ID
    started_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),  -- Session start time
    last_activity_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),  -- Last activity timestamp
    message_count     INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT db_search_hist_sessions_chat_user_key UNIQUE (chat_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_sess_chat_id
    ON prestage.db_search_hist_sessions(chat_id);

CREATE INDEX IF NOT EXISTS idx_sess_user_id
    ON prestage.db_search_hist_sessions(user_id);

CREATE TABLE IF NOT EXISTS prestage.db_search_hist_messages (
    id            BIGSERIAL PRIMARY KEY,
    chat_id       VARCHAR(50) NOT NULL,
    user_id       INTEGER,
    timestamp     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    message_type  VARCHAR(30) NOT NULL,
    content       TEXT,
    metadata      JSONB
);

CREATE INDEX IF NOT EXISTS idx_msg_chat_id
    ON prestage.db_search_hist_messages(chat_id);

CREATE INDEX IF NOT EXISTS idx_msg_user_id
    ON prestage.db_search_hist_messages(user_id);

CREATE INDEX IF NOT EXISTS idx_msg_chat_user
    ON prestage.db_search_hist_messages(chat_id, user_id);
