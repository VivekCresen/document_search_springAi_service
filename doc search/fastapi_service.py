# Here is the merged `fastapi_service.py`.

# **Merge Strategy:**
# 1.  **Base Architecture (v3):** Retained the `SafeWorkflowManager` (async, thread pool, conversation caching), `lifespan` context manager, `/api/v1/query` endpoint, and `schema_api` router integration.
# 2.  **RAG & Citation Logic (v4):** Integrated the **blob-backed highlighting initialization** (PDFHighlightManager, ConsolidatedCitationManager, SecuredRAGPipeline) into the `SafeWorkflowManager.__init__` to ensure citations and SAS URLs are generated correctly.
# 3.  **Response Handling:** Updated the `/api/v1/query` endpoint to extract and populate the `citations` field in the response (using v4's logic) while keeping v3's response models.
# 4.  **Permissions:** Added the `/permissions/*` endpoints from v4, as they rely on the `UserPermissionManager` which is now initialized globally during startup.
# 5.  **Utilities:** Included v4's `sanitize_error_message` for better error handling.


# fastapi_service.py — MM Chatbot FastAPI Service
# v5 — MERGED: Schema API Base + Blob-backed Citations + Permissions
#
# Changes:
# - Base: v3 (SafeWorkflowManager, Schema API, Async handling)
# - Features: v4 (Blob-backed highlighting, Citations in Response, Permission Endpoints)
# - Init: RAG Pipeline configured with ConsolidatedCitationManager for SAS URLs
# - Endpoint: /api/v1/query now populates 'citations' field from workflow result

# =============================================================================
# FIX WINDOWS CONSOLE ENCODING ISSUE
# =============================================================================
import sys
import os
import functools
import httpx
if sys.platform == 'win32':
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    if hasattr(sys.stderr, 'reconfigure'):
        sys.stderr.reconfigure(encoding='utf-8')
    os.environ['PYTHONIOENCODING'] = 'utf-8'
    try:
        import ctypes
        kernel32 = ctypes.windll.kernel32
        kernel32.SetConsoleCP(65001)
        kernel32.SetConsoleOutputCP(65001)
    except Exception:
        pass
    print("✅ UTF-8 encoding enabled")

from fastapi import FastAPI, HTTPException, Request, BackgroundTasks, Header, Depends
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from typing import Optional, List, Dict, Any, Union
from datetime import datetime
import uvicorn
import asyncio
import traceback
import logging
from concurrent.futures import ThreadPoolExecutor
import uuid
from dotenv import load_dotenv
from contextlib import asynccontextmanager

# Application Imports
from application import (
    SecuredUnifiedQueryWorkflow,
    SecuredEnhancedUnifiedWorkflow,
)
from utils import ChatHistoryTracker
from config import (
    DB_CONFIG,
    AZURE_SEARCH_INDEX_NAME,
    TARGET_ENDPOINT,
    AZURE_SEARCH_ENDPOINT, 
    AZURE_SEARCH_KEY,
    AZURE_API_VERSION, 
    AZURE_OPENAI_RAG_DEPLOYMENT,
    AZURE_OPENAI_ENDPOINT, 
    AZURE_OPENAI_KEY,
)
from UserPermissionManager import UserPermissionManager
from doc_search import PDFHighlightManager, ConsolidatedCitationManager
from doc_search_secured import SecuredRAGPipeline
from azure.search.documents import SearchClient
from azure.core.credentials import AzureKeyCredential
from langchain_openai import AzureChatOpenAI

# Schema API Router (v3 Feature)
from schema_api import router as schema_router

load_dotenv()

# =============================================================================
# LOGGING
# =============================================================================
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# =============================================================================
# CONFIGURATION
# =============================================================================
class Config:
    MAX_WORKERS              = 50
    MAX_REQUESTS_PER_SECOND  = 50
    CONVERSATION_CACHE_SIZE  = 500
    CONVERSATION_TIMEOUT     = 3600   # 1 hour
    REQUEST_TIMEOUT          = 500    # 2 minutes

# =============================================================================
# REQUEST MODELS
# =============================================================================
class Metadata(BaseModel):
    serviceReferenceId: Optional[str] = None

class RequestData(BaseModel):
    request_id:       Optional[str] = None
    Authorization:    Optional[str] = None
    xtenantid:        Optional[str] = None
    email:            str
    username:         str
    question:         str
    question_id:      Optional[int] = None
    conversation_id:  Optional[str] = None
    product_name:     Optional[str] = "MM"
    profile:          Optional[str] = "dev"
    user_id:          Optional[int] = None

class EnvelopeRequest(BaseModel):
    metadata:    Optional[Metadata] = None
    requestData: RequestData
    model_config = {
        "json_schema_extra": {
            "example": {
                "metadata": { "serviceReferenceId": "test-ref-123" },
                "requestData": {
                    "request_id":       "REQ-001",
                    "Authorization":    "Bearer mock-token",
                    "xtenantid":        "tenant-456",
                    "email":            "test@example.com",
                    "username":         "erin.vales",
                    "question":         "what is monitormate?",
                    "question_id":      101,
                    "conversation_id":  "CONV-16",
                    "product_name":     "MM",
                    "profile":          "dev",
                    "user_id":          12345,
                },
            }
        }
    }

# =============================================================================
# RESPONSE MODELS
# =============================================================================
class AnswerItem(BaseModel):
    """Single answer item with Text and Table fields."""
    Text:  Optional[str]                    = None
    Table: Optional[List[Dict[str, Any]]]   = None

class CitationItem(BaseModel):
    file_name:         str
    download_link:     str               # SAS download URL (forced download)
    view_link:         str = ""          # SAS view URL (inline, browser renders PDF)
    highlighted_pages: List[int] = []  # SAS URL — valid for HIGHLIGHTED_SAS_EXPIRY_HOURS (default 1h)

class ResponseData(BaseModel):
    """Response data matching client specification."""
    request_id:          Optional[str]                      = None
    Authorization:       Optional[str]                      = None
    xtenantid:           Optional[str]                      = None
    email:               str
    username:            str
    question_id:         Optional[int]                      = None
    response_type:       str                                 = "text"
    answer:              List[AnswerItem]
    citations:           Optional[Dict[str, CitationItem]]  = None
    conversation_id:     Optional[str]                      = None
    product_name:        str                                = "MM"
    profile:             str                                = "dev"
    user_id:             Optional[int]                      = None
    response_timeStamp:  str

class EnvelopeResponse(BaseModel):
    responseData: ResponseData

class HealthResponse(BaseModel):
    status:          str
    timestamp:       str
    version:         str = "5.0.0"
    db_pool_status:  str = "unknown"
    worker_pid:      int = 0

# =============================================================================
# UTILITY FUNCTIONS
# =============================================================================
def get_current_timestamp() -> str:
    return datetime.now().strftime("%m/%d/%Y %H:%M:%S")

def sanitize_error_message(error_msg: str) -> str:
    generic = {
        "database":       "We encountered an issue retrieving the requested data. Please try again later.",
        "processing":     "We encountered an issue processing your request. Please try again later.",
        "authentication": "Authentication failed. Please check your credentials.",
        "permission":     "You don't have permission to access this resource.",
        "timeout":        "The request took too long to process. Please try again.",
        "general":        "An unexpected error occurred. Please try again later.",
    }
    el = error_msg.lower()
    if any(w in el for w in ["database", "sql", "query", "table", "column"]):
        return generic["database"]
    if any(w in el for w in ["timeout", "timed out"]):
        return generic["timeout"]
    if any(w in el for w in ["permission", "access", "denied", "forbidden"]):
        return generic["permission"]
    if any(w in el for w in ["auth", "token", "credential"]):
        return generic["authentication"]
    return generic["general"]

async def forward_response_to_backend(payload: Dict[str, Any]):
    try:
        flattened_payload = payload.get('responseData', payload)
        async with httpx.AsyncClient(timeout=10.0) as client:
            logger.info(f"Forwarding response to: {TARGET_ENDPOINT}")
            response = await client.post(TARGET_ENDPOINT, json=flattened_payload)
            if response.status_code == 200:
                logger.info(f"✅ Successfully forwarded. Status: {response.status_code}")
            else:
                logger.warning(f"⚠️ Backend error: {response.status_code} - {response.text}")
    except Exception as e:
        logger.error(f"❌ Failed to forward response: {str(e)}")

# =============================================================================
# WORKFLOW MANAGER
# =============================================================================
class SafeWorkflowManager:
    """
    Manages per-conversation workflow instances backed by
    SecuredUnifiedQueryWorkflow (MM's security-aware orchestrator).
    """
    def __init__(self, permission_manager: UserPermissionManager = None):
        self.base_workflow: Optional[SecuredUnifiedQueryWorkflow] = None
        self.active_conversations: Dict[str, tuple]               = {}
        self.conversation_locks:   Dict[str, asyncio.Lock]        = {}

        self.executor = ThreadPoolExecutor(
            max_workers=Config.MAX_WORKERS,
            thread_name_prefix="query_worker"
        )
        self.rate_limiter = asyncio.Semaphore(Config.MAX_REQUESTS_PER_SECOND)
        self.cleanup_task = None

        try:
            logger.info("Initializing MM base workflow...")
            self.base_workflow = SecuredUnifiedQueryWorkflow(
                DB_CONFIG, AZURE_SEARCH_INDEX_NAME,
                permission_manager=permission_manager 
            )
            
            logger.info("Initializing Secured RAG Pipeline with Blob Highlighting...")
            search_client = SearchClient(
                AZURE_SEARCH_ENDPOINT,
                AZURE_SEARCH_INDEX_NAME,
                AzureKeyCredential(AZURE_SEARCH_KEY),
            )

            pdf_highlighter = PDFHighlightManager()
            citation_manager = ConsolidatedCitationManager(pdf_highlighter)

            deployment_name = AZURE_OPENAI_RAG_DEPLOYMENT
            reasoning_models = ["o1", "o3", "o4", "o5", "gpt-5", "reasoning"]
            is_reasoning = any(x in deployment_name.lower() for x in reasoning_models)

            if is_reasoning:
                rag_llm = AzureChatOpenAI(
                    azure_endpoint=AZURE_OPENAI_ENDPOINT,
                    api_key=AZURE_OPENAI_KEY,
                    azure_deployment=deployment_name,
                    api_version=AZURE_API_VERSION,
                    max_tokens=4000,
                )
            else:
                rag_llm = AzureChatOpenAI(
                    azure_endpoint=AZURE_OPENAI_ENDPOINT,
                    api_key=AZURE_OPENAI_KEY,
                    azure_deployment=deployment_name,
                    api_version=AZURE_API_VERSION,
                    temperature=0.1,
                    max_tokens=4000,
                )

            self.base_workflow.rag_pipeline = SecuredRAGPipeline(
                search_client=search_client,
                llm=rag_llm,
                citation_manager=citation_manager,
                permission_manager=permission_manager,
            )
            
            logger.info("✅ MM base workflow & RAG Pipeline initialized successfully")
        except Exception as e:
            logger.error(f"❌ Failed to initialize base workflow: {e}")
            raise

    def _get_cache_key(self, conversation_id: str, user_id: str) -> str:
        """Create a globally unique key for the memory cache combining Conv+user_id"""
        return f"{conversation_id}::{user_id}"

    def get_or_create_conversation(
        self, conversation_id: str, user_id: str
    ) -> SecuredEnhancedUnifiedWorkflow:
        current_time = datetime.now()
        cache_key = self._get_cache_key(conversation_id, user_id)

        if cache_key in self.active_conversations:
            workflow, _ = self.active_conversations[cache_key]
            self.active_conversations[cache_key] = (workflow, current_time)
            return workflow

        history_tracker = ChatHistoryTracker(db_config=DB_CONFIG, user_id=user_id)
        history_tracker.start_new_chat_with_id(conversation_id, user_id)

        workflow = SecuredEnhancedUnifiedWorkflow(self.base_workflow, history_tracker)

        self.active_conversations[cache_key] = (workflow, current_time)
        self.conversation_locks[cache_key]   = asyncio.Lock()

        if len(self.active_conversations) > Config.CONVERSATION_CACHE_SIZE:
            self._cleanup_old_conversations()
        
        logger.info(f"Cache {'HIT' if cache_key in self.active_conversations else 'MISS'} for key={cache_key}")
        return workflow

    def _cleanup_old_conversations(self):
        if len(self.active_conversations) <= Config.CONVERSATION_CACHE_SIZE:
            return
        sorted_conversations = sorted(
            self.active_conversations.items(), key=lambda x: x[1][1]
        )
        remove_count = len(sorted_conversations) // 10
        for cache_key, _ in sorted_conversations[:remove_count]:
            del self.active_conversations[cache_key]
            self.conversation_locks.pop(cache_key, None)
        logger.info(f"Cleaned up {remove_count} old conversations")

    async def cleanup_inactive_conversations(self):
        while True:
            try:
                await asyncio.sleep(300)
                current_time = datetime.now()
                to_remove = [
                    cache_key
                    for cache_key, (_, last_access) in self.active_conversations.items()
                    if (current_time - last_access).total_seconds() > Config.CONVERSATION_TIMEOUT
                ]
                for cache_key in to_remove:
                    del self.active_conversations[cache_key]
                    self.conversation_locks.pop(cache_key, None)
                if to_remove:
                    logger.info(f"Cleaned up {len(to_remove)} inactive conversations")
            except Exception as e:
                logger.error(f"Cleanup error: {e}")

    async def process_question_async(
        self,
        conversation_id: str,
        question:        str,
        username:        str = "",
        question_id:     Optional[int] = None,
        user_id:         Optional[int] = None,
    ):
        async with self.rate_limiter:
            # Note: `username` parameter holds the `req.email`
            workflow = self.get_or_create_conversation(conversation_id, user_id = user_id)
            cache_key = self._get_cache_key(conversation_id, username)

            if cache_key not in self.conversation_locks:
                self.conversation_locks[cache_key] = asyncio.Lock()

            async with self.conversation_locks[cache_key]:
                loop = asyncio.get_event_loop()
                try:
                    fn = functools.partial(
                        workflow.process_question_with_history,
                        question,
                        username, 
                        conv_id=str(conversation_id or ""),
                        q_id=str(question_id or ""),
                        user_id=str(user_id or ""),
                    )
                    result = await asyncio.wait_for(
                        loop.run_in_executor(self.executor, fn),
                        timeout=Config.REQUEST_TIMEOUT
                    )
                    return result
                except asyncio.TimeoutError:
                    logger.error(f"Request timeout for conversation {conversation_id}")
                    raise HTTPException(
                        status_code=504,
                        detail="Request timeout - query took too long"
                    )

    def get_stats(self) -> Dict[str, Any]:
        return {
            "active_conversations": len(self.active_conversations),
            "thread_pool_active":   (
                self.executor._work_queue.qsize()
                if hasattr(self.executor._work_queue, 'qsize') else 0
            ),
            "worker_pid": os.getpid()
        }

    async def shutdown(self):
        logger.info("Shutting down workflow manager...")
        if self.cleanup_task:
            self.cleanup_task.cancel()
        self.executor.shutdown(wait=True)
        logger.info("✅ Workflow manager shutdown complete")

# =============================================================================
# FASTAPI APPLICATION
# =============================================================================
workflow_manager: Optional[SafeWorkflowManager] = None
permission_manager: Optional[UserPermissionManager] = None

# fastapi_service.py - lifespan function
# In fastapi_service.py

from semantic_worker import load_semantic_cache_from_disk, rebuild_semantic_cache
from config import GLOBAL_SEMANTIC_CACHE

@asynccontextmanager
async def lifespan(app: FastAPI):
    global workflow_manager, permission_manager
    try:
        logger.info(f"🚀 Starting Ethosline FastAPI application...")
        
        # 1. Try to load from disk first
        load_semantic_cache_from_disk()
        
        # 2. If it's still empty, force a build BEFORE the server accepts requests
        if not GLOBAL_SEMANTIC_CACHE:
            logger.info("⚠️ Cache is empty. Forcing initial build before startup...")
            rebuild_semantic_cache()
            
        # Initialize ONCE globally
        permission_manager = UserPermissionManager(DB_CONFIG)
        
        # Pass the SAME instance to workflow
        workflow_manager = SafeWorkflowManager(permission_manager=permission_manager)
        
        workflow_manager.cleanup_task = asyncio.create_task(
            workflow_manager.cleanup_inactive_conversations()
        )
        logger.info("✅ Application started successfully")
        yield
    finally:
        if workflow_manager:
            await workflow_manager.shutdown()


app = FastAPI(
    title="MM Chatbot API",
    description="MM compliance monitoring chatbot with DB-backed schema registry & Blob Citations",
    version="5.0.0",
    lifespan=lifespan
)

# Mount schema management UI + API endpoints (v3 Feature)
app.include_router(schema_router)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# =============================================================================
# MAIN QUERY ENDPOINT
# =============================================================================
@app.post("/query", response_model=EnvelopeResponse)
async def process_query(request: EnvelopeRequest, background_tasks: BackgroundTasks):
    """Process a compliance query and forward result to backend."""
    if workflow_manager is None:
        raise HTTPException(
            status_code=503,
            detail="Service unavailable — workflow manager not initialized."
        )
    
    req = request.requestData
    final_response = None

    try:
        logger.info(f"Processing — ConvID: {req.conversation_id}, QID: {req.question_id}")
        print(f"Processing — ConvID: {req.conversation_id}, QID: {req.question_id}")
        # Pass email as username so UserPermissionManager can apply folder security
        result = await workflow_manager.process_question_async(
            req.conversation_id,
            req.question,
            req.email,          # username for MM folder-level security
            req.question_id,    # Passed for potential blob naming
            req.user_id,        # Passed for potential blob naming
        )

        # ── Determine response type ───────────────────────────────────────
        internal_type = result.get('internal_type', 'text')
        if internal_type == "table":
            api_response_type = "table"
        elif internal_type == "hybrid":
            api_response_type = "text/table"
        else:
            api_response_type = "text"

        # ── Build answer items ────────────────────────────────────────────
        text_content  = result.get('text_payload') or result.get('nlp_answer')
        table_content = result.get('data_payload')

        answer_item: Dict[str, Any] = {}
        if text_content:
            answer_item["Text"] = text_content
        elif api_response_type == "text":
            answer_item["Text"] = "No answer generated."

        if table_content is not None:
            answer_item["Table"] = table_content
        elif api_response_type != "text":
            answer_item["Table"] = []

        # ── Build citations dict (v4 Feature) ─────────────────────────────
        citations: Optional[Dict[str, CitationItem]] = None
        raw_citations = result.get("citations")
        if raw_citations and isinstance(raw_citations, dict):
            citations = {
                num: CitationItem(
                    file_name=info.get("file_name", ""),
                    download_link=info.get("download_link", ""),
                    view_link=info.get("view_link", ""),
                    highlighted_pages=info.get("highlighted_pages", []),
                )
                for num, info in raw_citations.items()
            }

        response_data = ResponseData(
            request_id=req.request_id,
            Authorization=req.Authorization,
            xtenantid=req.xtenantid,
            email=req.email,
            username=req.username,
            question_id=req.question_id,
            response_type=api_response_type,
            answer=[answer_item],
            citations=citations,  # Populated from workflow result
            conversation_id=req.conversation_id,
            product_name=req.product_name,
            profile=req.profile,
            user_id=req.user_id,
            response_timeStamp=get_current_timestamp(),
        )
        
        final_response = EnvelopeResponse(
            responseData=response_data,
        )

    except Exception as e:
        error_message = sanitize_error_message(str(e))
        logger.error(
            f"Error for ConvID {req.conversation_id}: {str(e)}\n{traceback.format_exc()}"
        )
        error_response_data = ResponseData(
            request_id=req.request_id,
            Authorization=req.Authorization,
            xtenantid=req.xtenantid,
            email=req.email,
            username=req.username,
            question_id=req.question_id,
            response_type="text",
            answer=[{"Text": error_message}],
            citations=None,
            conversation_id=req.conversation_id,
            product_name=req.product_name,
            profile=req.profile,
            user_id=req.user_id,
            response_timeStamp=get_current_timestamp(),
        )
        final_response = EnvelopeResponse(
            responseData=error_response_data,
        )

    if final_response:
        background_tasks.add_task(
            forward_response_to_backend,
            final_response.model_dump(mode='json')
        )

    return final_response

# =============================================================================
# PERMISSION ENDPOINTS (v4 Feature)
# =============================================================================
def get_current_user_from_header(
    x_user_email:  Optional[str] = Header(None),
    x_username:    Optional[str] = Header(None),
    authorization: Optional[str] = Header(None),
) -> str:
    if x_username:
        return x_username
    if x_user_email:
        return x_user_email
    raise HTTPException(
        status_code=401,
        detail="Authentication required. Provide X-Username or X-User-Email header.",
    )

class PermissionCheckRequest(BaseModel):
    folder_ids: List[str]

class PermissionCheckResponse(BaseModel):
    username:         str
    permissions:      Dict[str, bool]
    restricted_count: int
    accessible_count: int

@app.post("/permissions/check", response_model=PermissionCheckResponse)
async def check_permissions(
    request:  PermissionCheckRequest,
    username: str = Depends(get_current_user_from_header),
):
    if not permission_manager:
        raise HTTPException(status_code=503, detail="Permission manager not initialized")
    try:
        permissions      = permission_manager.test_permissions(username, request.folder_ids)
        accessible_count = sum(1 for v in permissions.values() if v)
        restricted_count = len(permissions) - accessible_count
        return PermissionCheckResponse(
            username=username,
            permissions=permissions,
            restricted_count=restricted_count,
            accessible_count=accessible_count,
        )
    except Exception:
        raise HTTPException(status_code=500, detail="An error occurred while checking permissions.")

@app.get("/permissions/my-access")
async def get_my_access(username: str = Depends(get_current_user_from_header)):
    if not permission_manager:
        raise HTTPException(status_code=503, detail="Permission manager not initialized")
    try:
        restricted = permission_manager.get_restricted_folder_ids(username)
        return {
            "username":                username,
            "total_restricted_folders": len(restricted),
            "restricted_folder_ids":   sorted(list(restricted)),
            "note":                    "You can access all folders except those listed above",
        }
    except Exception:
        raise HTTPException(status_code=500, detail="An error occurred while retrieving access information.")

@app.post("/permissions/clear-cache")
async def clear_permission_cache(username: str = Depends(get_current_user_from_header)):
    if not permission_manager:
        raise HTTPException(status_code=503, detail="Permission manager not initialized")
    try:
        permission_manager.clear_cache(username)
        return {"success": True, "message": f"Cache cleared for: {username}", "username": username}
    except Exception:
        raise HTTPException(status_code=500, detail="An error occurred while clearing the cache.")

# =============================================================================
# HEALTH + METRICS
# =============================================================================
@app.get("/health", response_model=HealthResponse)
async def health():
    if workflow_manager is None:
        return HealthResponse(
            status="unhealthy",
            timestamp=datetime.now().isoformat(),
            db_pool_status="not_initialized",
            worker_pid=os.getpid()
        )
    return HealthResponse(
        status="healthy",
        timestamp=datetime.now().isoformat(),
        db_pool_status="using_sync_connections",
        worker_pid=os.getpid()
    )

@app.get("/metrics")
async def metrics():
    if workflow_manager is None:
        return { "error":  "Service not initialized" }
    stats = workflow_manager.get_stats()
    return {
        "timestamp": datetime.now().isoformat(),
        "config": {
            "max_workers":             Config.MAX_WORKERS,
            "max_rps":                 Config.MAX_REQUESTS_PER_SECOND,
            "conversation_cache_size": Config.CONVERSATION_CACHE_SIZE,
        },
        "current": stats,
    }

@app.get("/")
async def root():
    return {
        "name":     "MM Chatbot API - Secured (v5)",
        "version":  "5.0.0",
        "features": [
            "Schema Registry UI (/configure)",
            "Blob-backed PDF highlighting (no local disk dependency)",
            "SAS URL download links per citation (1h expiry by default)",
            "Citations dict in response payload: {num → {file_name, download_link}}",
            "Folder-level Access Control",
            "Background Response Forwarding",
        ],
        "endpoints": {
            "query":               "POST /api/v1/query",
            "schema_ui":          "GET /configure",
            "permissions_check":  "POST /permissions/check",
            "health":             "GET  /health",
        },
    }

# =============================================================================
# ENTRY POINT
# =============================================================================
if __name__ == "__main__":
    _workers = 1 if sys.platform == "win32" else 4
    if sys.platform == "win32":
        logger.info("⚠️ Windows detected — running with 1 worker.")

    uvicorn.run(
        "fastapi_service:app",
        host="0.0.0.0",
        port=5100,
        workers=_workers,
        reload=False
    )
