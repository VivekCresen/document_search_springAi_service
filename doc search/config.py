# config.py — MM Chatbot Configuration
# v3 — DB-BACKED SCHEMA REGISTRY + BACKGROUND REFRESH
#
# Changes from v2:
#   - VIEW_SCHEMAS / VIEW_DESCRIPTIONS / VIEW_ROUTING_METADATA now loaded from
#     the database (prestage.db_search_sources + prestage.db_search_schema_versions)
#     via schema_registry.py on every startup.
#   - _registry_lock (RLock) guards all reads/writes to those three dicts so a
#     background refresh and an in-flight question never race on the same dict.
#   - _refresh_loop() reloads the registry every REGISTRY_REFRESH_HOURS (default
#     24 h, overridable via env var).  The slow DB read happens OUTSIDE the lock;
#     only the instant dict-swap is held under the lock (~1 ms).
#   - start_registry_refresh() spawns a daemon thread at startup.
#   - Removed: MM_VIEWS, LIFESCIENCE_SCHEMA_COMPLETE, LIFESCIENCE_ESSENTIAL_COLUMNS,
#     COLUMN_PROXY_MAP, and all column-proxy helper functions.
#     These were the last hardcoded artefacts; all schema knowledge now lives in
#     the database and is managed via the Schema Config UI or onboard_views.py.
#   - DB_CONFIG retained unchanged; AZURE config unchanged.

import os
import threading
import time
# config.py
import os
import threading
import time
import pickle


from dotenv import load_dotenv
load_dotenv()

# =============================================================================
# DATABASE CONFIGURATION
# =============================================================================

DB_CONFIG = {
    'dbname':   os.getenv('DB_NAME'),
    'user':     os.getenv('DB_USER'),
    'password': os.getenv('DB_PASSWORD'),
    'host':     os.getenv('DB_HOST'),
    'port':     os.getenv('DB_PORT'),
}

# =============================================================================
# AZURE OPENAI CONFIGURATION
# =============================================================================

AZURE_OPENAI_ENDPOINT      = os.getenv('AZURE_OPENAI_ENDPOINT')
AZURE_OPENAI_KEY           = os.getenv('AZURE_OPENAI_KEY')
AZURE_OPENAI_DEPLOYMENT    = os.getenv('AZURE_OPENAI_DEPLOYMENT', 'o4-mini')
AZURE_API_VERSION          = os.getenv('AZURE_API_VERSION', '2024-02-01')
AZURE_OPENAI_RAG_DEPLOYMENT = os.getenv('AZURE_OPENAI_RAG_DEPLOYMENT', 'gpt-4o-mini')
AZURE_OPENAI_EMBEDDING_DEPLOYMENT = os.getenv('AZURE_OPENAI_EMBEDDING_DEPLOYMENT', 'text-embedding-ada-002')

# =============================================================================
# AZURE SEARCH CONFIGURATION
# =============================================================================

AZURE_SEARCH_ENDPOINT   = os.getenv('AZURE_SEARCH_ENDPOINT')
AZURE_SEARCH_KEY        = os.getenv('AZURE_SEARCH_ADMIN_KEY')
AZURE_SEARCH_INDEX_NAME = 'cresendemo_mmchatbot_v1'

TARGET_ENDPOINT = os.getenv('TARGET_ENDPOINT')

# =============================================================================
# LOCAL CONFIGURATION
# =============================================================================

LOCAL_DOCS_PATH      = 'docs'
HIGHLIGHTED_DOCS_DIR = 'highlighted_documents'
os.makedirs(HIGHLIGHTED_DOCS_DIR, exist_ok=True)

TOKEN_COSTS       = {'input': 0.25, 'output': 2.0}
COST_TRACKING_CSV = 'mm_cost_tracking.csv'
CHAT_HISTORY_DIR  = 'chat_histories'

# =============================================================================
# SCHEMA REGISTRY — DB-BACKED LOADER
#
# On first startup:    tables are created, seeded from schemas/mm_schemas.py
#                      (the old static file, used as a one-time migration seed).
# On subsequent starts: schemas loaded from DB (fast dict lookup at query time).
# Falls back to static file if DB is unreachable.
#
# _registry_lock guards all reads AND writes to these three dicts.
# Rule:
#   - Writers (initial load / background refresh / reload_registry_globals)
#     must hold the lock ONLY for the dict-swap — never while doing a DB query.
#   - Readers (db_search.py) must hold the lock while taking a local snapshot,
#     then release before doing any further work.
# =============================================================================

# Guards VIEW_SCHEMAS / VIEW_DESCRIPTIONS / VIEW_ROUTING_METADATA
_registry_lock: threading.RLock = threading.RLock()

VIEW_SCHEMAS:          dict = {}
VIEW_DESCRIPTIONS:     dict = {}
VIEW_ROUTING_METADATA: dict = {}

_registry_source = 'none'   # 'db' | 'static' | 'none'  — for health endpoint

try:
    from schema_registry import init_tables, is_registry_empty, seed_from_static, load_registry

    init_tables(DB_CONFIG)

    if is_registry_empty(DB_CONFIG):
        print('Registry empty — seeding from static schemas...')
        seed_from_static(DB_CONFIG)

    _schemas, _descriptions, _metadata = load_registry(DB_CONFIG)
    with _registry_lock:
        VIEW_SCHEMAS.update(_schemas)
        VIEW_DESCRIPTIONS.update(_descriptions)
        VIEW_ROUTING_METADATA.update(_metadata)
    _registry_source = 'db'
    print(f'Schema registry loaded from DB: {len(VIEW_SCHEMAS)} view(s).')

except Exception as _reg_err:
    print(f'DB registry unavailable ({_reg_err}), falling back to static schemas.')
    try:
        from schemas.mm_sc import (
            VIEW_SCHEMAS          as _s,
            VIEW_DESCRIPTIONS     as _d,
            VIEW_ROUTING_METADATA as _m,
        )
        with _registry_lock:
            VIEW_SCHEMAS.update(_s)
            VIEW_DESCRIPTIONS.update(_d)
            VIEW_ROUTING_METADATA.update(_m)
        _registry_source = 'static'
        print(f'Loaded {len(VIEW_SCHEMAS)} view(s) from static file.')
    except ImportError:
        print('No static schemas found either. VIEW_SCHEMAS is empty.')


# =============================================================================
# BACKGROUND REGISTRY REFRESH
# =============================================================================

def _refresh_registry() -> None:
    """
    Reload registry from DB into the in-memory dicts.
    DB query runs OUTSIDE the lock (slow); dict-swap runs INSIDE (instant ~1 ms).
    Safe to call from any thread at any time.
    """
    from schema_registry import load_registry
    try:
        # Slow part — DB query — no lock held here
        new_schemas, new_desc, new_meta = load_registry(DB_CONFIG)

        # Fast part — dict swap — lock held for ~1 ms
        with _registry_lock:
            VIEW_SCHEMAS.clear();          VIEW_SCHEMAS.update(new_schemas)
            VIEW_DESCRIPTIONS.clear();     VIEW_DESCRIPTIONS.update(new_desc)
            VIEW_ROUTING_METADATA.clear(); VIEW_ROUTING_METADATA.update(new_meta)

        print(f'Registry refreshed: {len(new_schemas)} view(s).')
    except Exception as e:
        # Never crash the background thread — just log
        print(f'Background registry refresh failed: {e}')


def _refresh_loop(interval_seconds: int) -> None:
    """Sleep, then refresh, forever.  Runs as a daemon thread."""
    while True:
        time.sleep(interval_seconds)
        print('Scheduled registry refresh starting...')
        _refresh_registry()
        print('Scheduled registry refresh complete.')


def start_registry_refresh(interval_hours: int = 6) -> None:
    """Spawn the background refresh daemon.  Called once at module load."""
    interval = interval_hours * 3600
    t = threading.Thread(
        target=_refresh_loop,
        args=(interval,),
        daemon=True,   # dies automatically when the main process exits
        name='registry-refresh',
    )
    t.start()
    print(f'Registry auto-refresh scheduled every {interval_hours}h.')


# Read interval from env — set REGISTRY_REFRESH_HOURS=1 in dev to test quickly
_REFRESH_HOURS = int(os.getenv('REGISTRY_REFRESH_HOURS', '24'))
start_registry_refresh(interval_hours=_REFRESH_HOURS)




# =============================================================================
# SEMANTIC CACHE CONFIGURATION
# =============================================================================

CACHE_IN_MEMORY = os.getenv('CACHE_IN_MEMORY', 'True').lower() in ('true', '1', 't')
SEMANTIC_CACHE_FILE = 'semantic_cache.pkl'

_semantic_cache_lock: threading.RLock = threading.RLock()
GLOBAL_SEMANTIC_CACHE: dict = {}

def start_semantic_cache_refresh(interval_hours: int = 48) -> None:
    """Spawns the background refresh daemon for semantic embeddings."""
    interval = interval_hours * 3600
    t = threading.Thread(
        target=_semantic_cache_loop,
        args=(interval,),
        daemon=True,
        name='semantic-cache-refresh',
    )
    t.start()
    print(f'🧠 Semantic Cache auto-refresh scheduled every {interval_hours}h.')

# In config.py

def _semantic_cache_loop(interval_seconds: int) -> None:
    """Sleep, then rebuild semantic cache from scratch, forever."""
    from semantic_worker import rebuild_semantic_cache 
    
    while True:
        time.sleep(interval_seconds)
        rebuild_semantic_cache()


# Trigger the background thread
start_semantic_cache_refresh(interval_hours=168)
