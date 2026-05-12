-- PostgreSQL schema for Doc Search / Spring AI prestage tables.
-- Source model: doc search/schemas.java

CREATE SCHEMA IF NOT EXISTS prestage;

-- TABLE 1: prestage.documents
-- File/folder registry. Folder rows have is_file=false.
CREATE TABLE IF NOT EXISTS prestage.documents (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(512) NOT NULL,
    is_file     BOOLEAN      NOT NULL DEFAULT FALSE,
    parent_id   INTEGER,
    created_at  TIMESTAMPTZ  DEFAULT NOW(),
    CONSTRAINT fk_prestage_documents_parent
        FOREIGN KEY (parent_id) REFERENCES prestage.documents(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_docs_is_file
    ON prestage.documents(is_file);

-- TABLE 2: prestage.document_repository_user_mapping
-- Folder-level access control. folders_access stores ONE restricted folder id per row.
CREATE TABLE IF NOT EXISTS prestage.document_repository_user_mapping (
    id              SERIAL PRIMARY KEY,
    user_name       VARCHAR(200) NOT NULL,
    folders_access  INTEGER,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT fk_drm_user_email
        FOREIGN KEY (user_name) REFERENCES user_schema.user_profile(email_id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,
    CONSTRAINT fk_drm_restricted_folder
        FOREIGN KEY (folders_access) REFERENCES prestage.documents(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_drm_user_name
    ON prestage.document_repository_user_mapping(user_name);

CREATE INDEX IF NOT EXISTS idx_drm_folders_access
    ON prestage.document_repository_user_mapping(folders_access);

-- TABLE 3: prestage.files_in_index
-- Real-time file stability tracking. Only status='stable' files are served.
CREATE TABLE IF NOT EXISTS prestage.files_in_index (
    id          SERIAL PRIMARY KEY,
    blob_uri    TEXT         NOT NULL UNIQUE,
    status      VARCHAR(50)  NOT NULL DEFAULT 'ingestion_inp',
    folder_id   INTEGER,
    updated_at  TIMESTAMPTZ  DEFAULT NOW(),
    CONSTRAINT fk_fii_folder
        FOREIGN KEY (folder_id) REFERENCES prestage.documents(id)
        ON UPDATE CASCADE
        ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_fii_status
    ON prestage.files_in_index(status);

CREATE INDEX IF NOT EXISTS idx_fii_blob_uri
    ON prestage.files_in_index(blob_uri);

CREATE INDEX IF NOT EXISTS idx_fii_folder_id
    ON prestage.files_in_index(folder_id);

-- TABLE 4: prestage.db_search_hist_sessions
-- One row per conversation session.
CREATE TABLE IF NOT EXISTS prestage.db_search_hist_sessions (
    id                SERIAL PRIMARY KEY,
    chat_id           VARCHAR(50) NOT NULL,
    user_id           BIGINT,
    started_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_activity_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    message_count     INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT db_search_hist_sessions_chat_user_key
        UNIQUE (chat_id, user_id),
    CONSTRAINT fk_db_search_session_user
        FOREIGN KEY (user_id) REFERENCES user_schema.user_profile(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_sess_chat_id
    ON prestage.db_search_hist_sessions(chat_id);

CREATE INDEX IF NOT EXISTS idx_sess_user_id
    ON prestage.db_search_hist_sessions(user_id);

-- TABLE 5: prestage.db_search_hist_messages
-- User questions and assistant answers per conversation.
CREATE TABLE IF NOT EXISTS prestage.db_search_hist_messages (
    id            BIGSERIAL PRIMARY KEY,
    chat_id       VARCHAR(50) NOT NULL,
    user_id       BIGINT,
    timestamp     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    message_type  VARCHAR(30) NOT NULL,
    content       TEXT,
    metadata      JSONB,
    CONSTRAINT fk_db_search_message_user
        FOREIGN KEY (user_id) REFERENCES user_schema.user_profile(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,
    CONSTRAINT fk_db_search_message_session
        FOREIGN KEY (chat_id, user_id)
        REFERENCES prestage.db_search_hist_sessions(chat_id, user_id)
        ON UPDATE CASCADE
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_msg_chat_id
    ON prestage.db_search_hist_messages(chat_id);

CREATE INDEX IF NOT EXISTS idx_msg_user_id
    ON prestage.db_search_hist_messages(user_id);

CREATE INDEX IF NOT EXISTS idx_msg_chat_user
    ON prestage.db_search_hist_messages(chat_id, user_id);
