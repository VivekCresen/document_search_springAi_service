-- PostgreSQL schema for Doc Search / Spring AI demo tables.
-- Source model: doc search/schemas.java

CREATE SCHEMA IF NOT EXISTS demo;

-- TABLE 1: demo.documents
-- File/folder registry. Folder rows have is_file=false.
CREATE TABLE IF NOT EXISTS demo.documents (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(512) NOT NULL,
    is_file     BOOLEAN      NOT NULL DEFAULT FALSE,
    parent_id   INTEGER,
    created_at  TIMESTAMPTZ  DEFAULT NOW(),
    CONSTRAINT fk_demo_documents_parent
        FOREIGN KEY (parent_id) REFERENCES demo.documents(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_docs_is_file
    ON demo.documents(is_file);

-- TABLE 2: demo.document_repository_user_mapping
-- Folder-level access control. folders_access stores ONE restricted folder id per row.
CREATE TABLE IF NOT EXISTS demo.document_repository_user_mapping (
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
        FOREIGN KEY (folders_access) REFERENCES demo.documents(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_drm_user_name
    ON demo.document_repository_user_mapping(user_name);

CREATE INDEX IF NOT EXISTS idx_drm_folders_access
    ON demo.document_repository_user_mapping(folders_access);

-- TABLE 3: demo.files_in_index
-- Real-time file stability tracking. Only status='stable' files are served.
CREATE TABLE IF NOT EXISTS demo.files_in_index (
    id          SERIAL PRIMARY KEY,
    blob_uri    TEXT         NOT NULL UNIQUE,
    status      VARCHAR(50)  NOT NULL DEFAULT 'ingestion_inp',
    folder_id   INTEGER,
    updated_at  TIMESTAMPTZ  DEFAULT NOW(),
    CONSTRAINT fk_fii_folder
        FOREIGN KEY (folder_id) REFERENCES demo.documents(id)
        ON UPDATE CASCADE
        ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_fii_status
    ON demo.files_in_index(status);

CREATE INDEX IF NOT EXISTS idx_fii_blob_uri
    ON demo.files_in_index(blob_uri);

CREATE INDEX IF NOT EXISTS idx_fii_folder_id
    ON demo.files_in_index(folder_id);

-- TABLE 4: public.file_metadata
-- Upload metadata. filepath is JSONB and stores {"filePath": [...]} exactly as supplied.
CREATE TABLE IF NOT EXISTS file_metadata (
    id               SERIAL PRIMARY KEY,
    filepath         JSONB        NOT NULL,
    created_by       VARCHAR(255) NOT NULL,
    create_date      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    azure_blob_url   TEXT         NOT NULL,
    blob_name        TEXT         NOT NULL,
    file_size_in_mb  NUMERIC(12, 2),
    content_type     VARCHAR(255),
    status           VARCHAR(50)  NOT NULL DEFAULT 'UPLOADED'
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_file_metadata_filepath
    ON file_metadata USING btree (filepath);

CREATE INDEX IF NOT EXISTS idx_file_metadata_blob_name
    ON file_metadata(blob_name);

CREATE INDEX IF NOT EXISTS idx_file_metadata_created_by
    ON file_metadata(created_by);

CREATE INDEX IF NOT EXISTS idx_file_metadata_status
    ON file_metadata(status);

CREATE INDEX IF NOT EXISTS idx_file_metadata_folder
    ON file_metadata ((filepath->'filePath'->>0));

CREATE INDEX IF NOT EXISTS idx_file_metadata_document_id
    ON file_metadata ((filepath->'filePath'->>1));

-- TABLE 5/6: DB-backed schema registry referenced by Python config.py.
CREATE TABLE IF NOT EXISTS demo.db_search_sources (
    id                    BIGSERIAL PRIMARY KEY,
    view_name             VARCHAR(255) NOT NULL UNIQUE,
    description           TEXT,
    routing_metadata      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS demo.db_search_schema_versions (
    id                    BIGSERIAL PRIMARY KEY,
    source_id             BIGINT       NOT NULL,
    schema_json           JSONB        NOT NULL,
    version               INTEGER      NOT NULL DEFAULT 1,
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_db_search_schema_source
        FOREIGN KEY (source_id) REFERENCES demo.db_search_sources(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,
    CONSTRAINT uq_db_search_schema_source_version
        UNIQUE (source_id, version)
);

CREATE INDEX IF NOT EXISTS idx_db_search_sources_active
    ON demo.db_search_sources(active);

CREATE INDEX IF NOT EXISTS idx_db_search_schema_source_active
    ON demo.db_search_schema_versions(source_id, active);

-- TABLE 7: demo.db_search_hist_sessions
-- One row per conversation session.
CREATE TABLE IF NOT EXISTS demo.db_search_hist_sessions (
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
    ON demo.db_search_hist_sessions(chat_id);

CREATE INDEX IF NOT EXISTS idx_sess_user_id
    ON demo.db_search_hist_sessions(user_id);

-- TABLE 8: demo.db_search_hist_messages
-- User questions and assistant answers per conversation.
CREATE TABLE IF NOT EXISTS demo.db_search_hist_messages (
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
        REFERENCES demo.db_search_hist_sessions(chat_id, user_id)
        ON UPDATE CASCADE
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_msg_chat_id
    ON demo.db_search_hist_messages(chat_id);

CREATE INDEX IF NOT EXISTS idx_msg_user_id
    ON demo.db_search_hist_messages(user_id);

CREATE INDEX IF NOT EXISTS idx_msg_chat_user
    ON demo.db_search_hist_messages(chat_id, user_id);
