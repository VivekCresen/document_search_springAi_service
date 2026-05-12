# application.py — MM Chatbot Application Orchestrator
# v3 — FLAT SOURCE CLASSIFIER + DB-BACKED SCHEMA REGISTRY
#
# Changes from v2:
#   - MMViewRouter replaced by FlatSourceClassifier (peer-source routing)
#   - MMSQLConverter now fetches schema from VIEW_SCHEMAS under registry lock
#     (no static LIFESCIENCE_SCHEMA_COMPLETE fallback)
#   - apply_column_proxies() calls removed — column proxy system retired;
#     column names in query results are as returned by the DB (the LLM is
#     instructed to include friendly "proxy" labels in selected_columns)
#   - VIEW_SCHEMAS imported directly for schema_context lookup in _handle_database
#   - Security wrappers (UserPermissionManager, SecuredRAGPipeline, etc.) unchanged
#   - StandaloneQueryGenerator / ChatHistoryTracker / history workflow unchanged

import json
import time
import functools
from typing import Dict, List, Tuple, Optional

from azure.core.credentials import AzureKeyCredential
from azure.search.documents import SearchClient
from langchain_openai import AzureChatOpenAI

from utils import (
    AzureOpenAIClient,
    ChatHistoryTracker,
    StandaloneQueryGenerator,
    safe_parse_json,
    convert_results_to_json_serializable,
    NLP_MAX_ROWS,                    # ← add
    sanitize_selected_columns,       # ← add
)

# In application.py

from config import (
    DB_CONFIG,
    AZURE_OPENAI_ENDPOINT, AZURE_OPENAI_KEY,
    AZURE_API_VERSION, AZURE_SEARCH_ENDPOINT, AZURE_SEARCH_KEY,
    AZURE_SEARCH_INDEX_NAME, LOCAL_DOCS_PATH, HIGHLIGHTED_DOCS_DIR,
    AZURE_OPENAI_RAG_DEPLOYMENT,
    VIEW_SCHEMAS, VIEW_DESCRIPTIONS, VIEW_ROUTING_METADATA,
    _registry_lock,
    GLOBAL_SEMANTIC_CACHE,    
    _semantic_cache_lock      
)

from db_search import (
    FlatSourceClassifier,
    MMSQLConverter,
    SQLQueryExecutor,
    ResultsToNLPConverter,
)
from utils import SemanticCacheManager
from db_search import SemanticRanker

from doc_search import PDFHighlightManager, ConsolidatedCitationManager
from UserPermissionManager import UserPermissionManager
from doc_search_secured import SecuredRAGPipeline, SecuredIntentClassifier


def timing_decorator(func):
    @functools.wraps(func)
    def wrapper(*args, **kwargs):
        start = time.time()
        print(f"\n⏳ Starting: {func.__name__}")
        result = func(*args, **kwargs)
        elapsed = time.time() - start
        print(f"✅ Completed: {func.__name__} in {elapsed*1000:.0f}ms ({elapsed:.1f}s)")
        return result
    return wrapper


# =============================================================================
# ERROR SANITIZATION
# =============================================================================

def sanitize_error_for_user(error_msg: str) -> str:
    generic_messages = {
        "database":   "We encountered an issue retrieving the requested data.",
        "processing": "We encountered an issue processing your request.",
        "search":     "We encountered an issue searching for documents.",
        "general":    "An unexpected error occurred.",
    }
    error_lower = error_msg.lower()
    if any(w in error_lower for w in
           ['database', 'sql', 'query', 'table', 'column',
            'postgres', 'psycopg', 'connection', 'syntax']):
        return generic_messages["database"]
    if any(w in error_lower for w in ['search', 'index', 'azure', 'document']):
        return generic_messages["search"]
    if any(w in error_lower for w in ['llm', 'openai', 'timeout', 'token']):
        return generic_messages["processing"]
    return generic_messages["general"]


# =============================================================================
# SECURED UNIFIED QUERY WORKFLOW
# =============================================================================

class SecuredUnifiedQueryWorkflow:
    """
    Main workflow orchestrator with:
    - FlatSourceClassifier  (peer-source routing, ready for multiple MM views)
    - Folder-level document security  (UserPermissionManager)
    - Standalone query generation     (StandaloneQueryGenerator)
    - Semantic categorical ranking    (SemanticRanker & Cache)
    - Generic error handling
    """

    def __init__(self, db_config: Dict, azure_search_index_name: str, 
                 permission_manager: UserPermissionManager = None):
        # ── LLM clients ──────────────────────────────────────────────────
        self.llm_client         = AzureOpenAIClient()
        self.summary_llm_client = AzureOpenAIClient(
            deployment_name=AZURE_OPENAI_RAG_DEPLOYMENT
        )

        # ── Standalone query generator ────────────────────────────────────
        self.standalone_query_gen = StandaloneQueryGenerator(self.llm_client)

        # ── Azure Search ──────────────────────────────────────────────────
        search_client = SearchClient(
            AZURE_SEARCH_ENDPOINT,
            azure_search_index_name,
            AzureKeyCredential(AZURE_SEARCH_KEY)
        )

        # ── Permission manager ────────────────────────────────────────────
        self.permission_manager = UserPermissionManager(db_config)

        # ── Intent classifier (document pre-fetch with security) ──────────
        self.classifier = SecuredIntentClassifier(
            self.summary_llm_client,
            search_client,
            self.permission_manager
        )

        # ── Flat source classifier (DB-driven, zero hardcoding) ───────────
        self.source_classifier = FlatSourceClassifier(self.summary_llm_client)

        # ── Database search components ────────────────────────────────────
        self.sql_converter  = MMSQLConverter(self.llm_client)
        self.sql_executor   = SQLQueryExecutor(db_config)
        self.results_to_nlp = ResultsToNLPConverter(self.summary_llm_client)
        
        self.semantic_cache = SemanticCacheManager()
        self.semantic_ranker = SemanticRanker(self.llm_client)

        # ── Document pipeline ─────────────────────────────────────────────
        pdf_highlighter  = PDFHighlightManager(LOCAL_DOCS_PATH, HIGHLIGHTED_DOCS_DIR)
        citation_manager = ConsolidatedCitationManager(pdf_highlighter)

        rag_llm = AzureChatOpenAI(
            azure_endpoint=AZURE_OPENAI_ENDPOINT,
            api_key=AZURE_OPENAI_KEY,
            azure_deployment=AZURE_OPENAI_RAG_DEPLOYMENT,
            api_version=AZURE_API_VERSION,
        )

        self.rag_pipeline = SecuredRAGPipeline(
            search_client=search_client,
            llm=rag_llm,
            citation_manager=citation_manager,
            permission_manager=self.permission_manager,
        )

        # ── Permission manager ────────────────────────────────────────
        if permission_manager:
            self.permission_manager = permission_manager  # ← Reuse existing
            print("🔒 Using provided PermissionManager instance")
        else:
            self.permission_manager = UserPermissionManager(db_config)  # ← Fallback
            print("🔒 Created new PermissionManager (not recommended for startup)")

        print("✅ Secured Unified Query Workflow initialized (v3 — Flat Source Classifier)")
        print("   📋 Features: FlatSourceClassifier, DB-backed registry, Security, Semantic Cache")

    @timing_decorator
    def process_question(
        self,
        user_question:        str,
        username:             str,
        conversation_context: str = "",
        conv_id:              str = "",
        q_id:                 str = "",
        user_id:              str = "",
    ) -> Dict:
        """Process user question with security context and conversation history."""
        result = {
            'question':  user_question,
            'username':  username,
            'timestamp': time.time(),
            'success':   False,
            'workflow':  None,
            'internal_type': None,
        }

        try:
            standalone_query = self.standalone_query_gen.create_standalone_query(
                user_question, conversation_context
            )
            result['original_question'] = user_question
            result['standalone_query']  = standalone_query

            classification = self._classify_intent_with_security(
                standalone_query, username
            )
            result.update(classification)

            intent = classification.get('intent', 'document')

            if intent == 'database':
                return self._handle_database(standalone_query, result)
            elif intent == 'document':
                return self._handle_document_with_security(
                    standalone_query,
                    username,
                    result,
                    classification.get('prefetched_docs', []),
                    conv_id=conv_id,
                    q_id=q_id,
                    user_id=user_id,
                )
            else:
                return self._handle_general(standalone_query, result)

        except Exception as e:
            print(f"⚠️  Exception in process_question: {e}")
            sanitized = sanitize_error_for_user(str(e))
            result.update({
                'success':       False,
                'error':         'processing_error',
                'error_details': sanitized,
                'nlp_answer':    sanitized,
            })
            return result

    def _classify_intent_with_security(
        self, user_question: str, username: str
    ) -> Dict:
        """Classify intent with security-aware document pre-fetching."""
        print(f"🎯 Classifying intent for user '{username}': '{user_question}'")

        if self._is_greeting(user_question):
            return {
                'intent':          'general',
                'response_type':   None,
                'confidence':      1.0,
                'prefetched_docs': [],
            }

        try:
            relevant_docs = self.classifier.search_relevant_documents_with_security(
                user_question, username, top_k=5
            )
        except Exception as e:
            print(f"⚠️  Document pre-fetch error: {e}")
            relevant_docs = []

        with _registry_lock:
            desc_snapshot = dict(VIEW_DESCRIPTIONS)
            meta_snapshot = dict(VIEW_ROUTING_METADATA)

        if desc_snapshot:
            db_contents = "; ".join(
                f"{vname.split('.')[-1]}: {desc}"
                for vname, desc in desc_snapshot.items()
            )
        else:
            db_contents = "structured compliance and monitoring activity data records"

        if meta_snapshot:
            all_kws = []
            for meta in meta_snapshot.values():
                all_kws.extend(meta.get("confidence_keywords", [])[:6])
            kw_hint = ", ".join(dict.fromkeys(all_kws[:15]))
            db_keyword_hint = (
                f"  Keywords that indicate a database question: {kw_hint}"
                if kw_hint else ""
            )
        else:
            db_keyword_hint = ""

        prompt = f"""Analyze the user's question and classify it.

USER QUESTION: "{user_question}"

PRIMARY INTENT:
- "database": The user wants to query, count, list, filter, or aggregate structured data records.
  Available data: {db_contents}
{db_keyword_hint}
document: Questions about policies, regulations, definitions, explanations, contacts, URLs,
          or "what is" questions about concepts.
general:  Casual conversation, greetings, questions about your AI capabilities, DB metadata (e.g., how many tables/schemas),
          requests to generate physical files (PDFs/exports), or random gibberish/unintelligible strings.

RESPONSE TYPE (ONLY for 'database' intent):
1. "nlp_summary":      Specific lookups, facts, or counts — user wants a text answer
2. "detailed_records": User explicitly asks for a list, table, or export
3. "hybrid":           User asks for both a summary and the underlying records

Return ONLY a JSON object:
{{
    "intent": "database" | "document" | "general",
    "response_type": "detailed_records" | "nlp_summary" | "hybrid" | null,
    "confidence": 0.0 to 1.0,
    "reasoning": "brief explanation"
}}
"""
        try:
            response_text, _ = self.llm_client.generate(prompt, temperature=0.0)
            parsed = safe_parse_json(response_text)
            return {
                'intent':          parsed.get('intent', 'document'),
                'response_type':   parsed.get('response_type'),
                'confidence':      parsed.get('confidence', 0.5),
                'reasoning':       parsed.get('reasoning', ''),
                'prefetched_docs': relevant_docs,
            }
        except Exception as e:
            print(f"⚠️  Classification error: {e}")
            return {
                'intent':          'document',
                'response_type':   None,
                'confidence':      0.3,
                'prefetched_docs': relevant_docs,
            }

    def _is_greeting(self, text: str) -> bool:
        greetings = ['hello', 'hi', 'hey', 'greetings', 'good morning',
                     'good afternoon', 'good evening']
        return text.lower().strip() in greetings

    # In db_search.py

    from config import GLOBAL_SEMANTIC_CACHE, _semantic_cache_lock

    @timing_decorator
    def _handle_database(self, question: str, result: Dict) -> Dict:
        result['workflow'] = 'database'
        response_type      = result.get('response_type', 'nlp_summary')

        routing        = self.source_classifier.classify(question)
        view_name      = routing.get("view_name")
        routing_method = routing.get("routing_method", "unknown")

        result['routed_view']    = view_name
        result['routing_method'] = routing_method

        semantic_hints = None
        try:
            with _registry_lock:
                meta_snapshot = dict(VIEW_ROUTING_METADATA)
            
            view_meta = meta_snapshot.get(view_name, {})
            categorical_columns = view_meta.get('categorical_columns', [])

            if categorical_columns:
                with _semantic_cache_lock:
                    view_cache = GLOBAL_SEMANTIC_CACHE.get(view_name, {})
                
                all_vals = {col: data[0] for col, data in view_cache.items()}
                all_embeds = {col: data[1] for col, data in view_cache.items()}
                
                semantic_hints = self.semantic_ranker.rank_candidates(
                    question, all_vals, all_embeds
                )
        except Exception as e:
            print(f"  ⚠️  Semantic candidate ranking failed: {e}")

        try:
            # Unpack the new 5th variable (is_clarification)
            sql_output, cols, explanation, _, is_clarification = self.sql_converter.generate_sql_query(
                question, response_type,
                view_name=view_name,
                semantic_hints=semantic_hints
            )

            # ── SHORT-CIRCUIT: Return the clarification directly to the user ──
            if is_clarification:
                print(f"  🗣️ Clarification triggered: {explanation}")
                result.update({
                    'success':       True,
                    'internal_type': 'text',
                    'text_payload':  explanation, 
                    'nlp_answer':    explanation,
                })
                return result

            if not sql_output:
                sanitized = sanitize_error_for_user(explanation)
                result.update({
                    'success':       False,
                    'error':         'sql_generation_error',
                    'error_details': sanitized,
                    'nlp_answer':    sanitized,
                })
                return result

        except Exception as e:
            print(f"⚠️  SQL generation error: {e}")
            sanitized = sanitize_error_for_user(str(e))
            result.update({
                'success':       False,
                'error':         'sql_generation_error',
                'error_details': sanitized,
                'nlp_answer':    sanitized,
            })
            return result

        if response_type == 'hybrid':
            return self._handle_hybrid_query(question, sql_output, cols, result)
        elif response_type == 'detailed_records':
            return self._handle_detailed_records(question, sql_output, cols, result)
        else:
            return self._handle_nlp_summary(question, sql_output, cols, result)
        
        
    def _handle_hybrid_query(
        self, question: str, sql_output: Tuple, cols: List, result: Dict
    ) -> Dict:
        result['internal_type'] = 'hybrid'
        sql_agg, sql_detail = sql_output
        cols = sanitize_selected_columns(cols)
        result.update({
            'sql_query_agg':    sql_agg,
            'sql_query_detail': sql_detail,
            'selected_columns': cols,
        })

        try:
            agg_data, agg_status, _ = self.sql_executor.execute_query(sql_agg)
            if agg_status != "SUCCESS":
                sanitized = sanitize_error_for_user(agg_status)
                result.update({
                    'nlp_answer':    sanitized,
                    'error':         'database_execution_error',
                    'error_details': sanitized,
                })
                return result

            detail_data, detail_status, count = self.sql_executor.execute_query(sql_detail)
            result['row_count'] = count
            if detail_status != "SUCCESS":
                sanitized = sanitize_error_for_user(detail_status)
                result.update({
                    'nlp_answer':    sanitized,
                    'error':         'database_execution_error',
                    'error_details': sanitized,
                })
                return result

            nlp_part, _ = self.results_to_nlp.generate_nlp_answer(
                question, agg_data, len(agg_data), is_hybrid=True
            )

            raw_data = convert_results_to_json_serializable(detail_data)

            result.update({
                'data_payload': raw_data,
                'nlp_answer':   f"{nlp_part}\n\n[Detailed Records Attached in API Response]",
                'success':      True,
            })
            return result

        except Exception as e:
            print(f"⚠️  Hybrid query execution error: {e}")
            sanitized = sanitize_error_for_user(str(e))
            result.update({
                'nlp_answer':    sanitized,
                'error':         'database_execution_error',
                'error_details': sanitized,
            })
            return result

    def _handle_detailed_records(
        self, question: str, sql_output: str, cols: List, result: Dict
    ) -> Dict:
        result['internal_type'] = 'table'
        cols = sanitize_selected_columns(cols)
        result.update({'sql_query': sql_output, 'selected_columns': cols})

        try:
            data, status, count = self.sql_executor.execute_query(sql_output)
            result['row_count'] = count

            if status != "SUCCESS":
                sanitized = sanitize_error_for_user(status)
                result.update({
                    'text_payload':  sanitized,
                    'nlp_answer':    sanitized,
                    'error':         'database_execution_error',
                    'error_details': sanitized,
                })
                return result

            intro_text, _ = self.results_to_nlp.generate_table_intro(question, count)
            result['text_payload'] = intro_text

            raw_data = convert_results_to_json_serializable(data)

            result.update({
                'data_payload': raw_data,
                'nlp_answer':   result['text_payload'],
                'success':      True,
            })
            return result

        except Exception as e:
            print(f"⚠️  Detailed records execution error: {e}")
            sanitized = sanitize_error_for_user(str(e))
            result.update({
                'text_payload':  sanitized,
                'nlp_answer':    sanitized,
                'error':         'database_execution_error',
                'error_details': sanitized,
            })
            return result

    def _handle_nlp_summary(
        self, question: str, sql: str, cols: List, result: Dict
    ) -> Dict:
        result['internal_type'] = 'text'
        cols = sanitize_selected_columns(cols)
        result.update({'sql_query': sql, 'selected_columns': cols})

        try:
            data, status, count = self.sql_executor.execute_query(sql)
            result['row_count'] = count

            if status != "SUCCESS":
                sanitized = sanitize_error_for_user(status)
                result.update({
                    'text_payload':  sanitized,
                    'nlp_answer':    sanitized,
                    'error':         'database_execution_error',
                    'error_details': sanitized,
                })
                return result

            if count > NLP_MAX_ROWS:
                print(f"  ℹ️  NLP fallback → table (row_count={count} > {NLP_MAX_ROWS})")
                result['internal_type'] = 'table'
                intro_text, _ = self.results_to_nlp.generate_table_intro(question, count)
                result.update({
                    'text_payload':        intro_text,
                    'nlp_answer':          intro_text,
                    'data_payload':        convert_results_to_json_serializable(data),
                    'success':             True,
                    'nlp_fallback_reason': 'row_count_exceeded',
                })
                return result

            ans, _ = self.results_to_nlp.generate_nlp_answer(question, data, count)
            result.update({'text_payload': ans, 'nlp_answer': ans, 'success': True})
            return result

        except Exception as e:
            print(f"⚠️  NLP summary execution error: {e}")
            sanitized = sanitize_error_for_user(str(e))
            result.update({
                'text_payload':  sanitized,
                'nlp_answer':    sanitized,
                'error':         'database_execution_error',
                'error_details': sanitized,
            })
            return result

    @timing_decorator
    def _handle_document_with_security(
        self,
        question:       str,
        username:       str,
        result:         Dict,
        prefetched_docs: List[Dict],
        conv_id:        str = "",
        q_id:           str = "",
        user_id:        str = "",
    ) -> Dict:
        """
        Handle document queries.
        """
        result["workflow"]      = "document"
        result["internal_type"] = "text"
        try:
            ans, citations = self.rag_pipeline.answer_question_with_security(
                question=question,
                username=username,
                prefetched_docs=prefetched_docs,
                conv_id=conv_id,
                q_id=q_id,
                user_id=user_id,
            )
            result.update(
                {
                    "nlp_answer":   ans,
                    "text_payload": ans,
                    "citations":    citations,
                    "success":      True,
                }
            )
            return result

        except Exception as e:
            print(f"⚠️  Document search error: {e}")
            sanitized = sanitize_error_for_user(str(e))
            result.update(
                {
                    "nlp_answer":    sanitized,
                    "text_payload":  sanitized,
                    "error":         "document_search_error",
                    "error_details": sanitized,
                }
            )
            return result

    def _handle_general(self, question: str, result: Dict) -> Dict:
        result['workflow']      = 'general'
        result['internal_type'] = 'text'
        try:
            ans, _ = self.llm_client.generate(f"Reply helpfully to: {question}")
            result.update({'nlp_answer': ans, 'text_payload': ans, 'success': True})
            return result
        except Exception as e:
            print(f"⚠️  General conversation error: {e}")
            sanitized = sanitize_error_for_user(str(e))
            result.update({
                'nlp_answer':    sanitized,
                'text_payload':  sanitized,
                'error':         'general_error',
                'error_details': sanitized,
            })
            return result

    def get_cumulative_cost(self):
        return self.llm_client.get_cumulative_total()
    
    
# =============================================================================
# SECURED WORKFLOW WITH HISTORY  (unchanged)
# =============================================================================

class SecuredEnhancedUnifiedWorkflow:
    """Workflow with conversation history tracking and security."""

    def __init__(self, base_workflow, history_tracker):
        self.base_workflow = base_workflow
        self.history       = history_tracker

    def process_question_with_history(
        self,
        question: str,
        username: str,
        conv_id:  str = "",
        q_id:     str = "",
        user_id:  str = "",
    ):
        self.history.append_message('user_question', question)
        context = self.history.get_recent_context()

        try:
            result = self.base_workflow.process_question(
                question, username, context,
                conv_id=conv_id,
                q_id=q_id,
                user_id=user_id,
            )

            metadata = {k: v for k, v in result.items()
                        if k not in ['nlp_answer', 'citations', 'data_payload']}
            if result.get('error'):
                metadata.update({
                    'had_error':     True,
                    'error_type':    result.get('error'),
                    'error_details': result.get('error_details', ''),
                })
            self.history.append_message(
                'assistant_answer', result.get('nlp_answer', ''), metadata
            )
            return result

        except Exception as e:
            sanitized = sanitize_error_for_user(str(e))
            error_result = {
                'question':      question,
                'username':      username,
                'success':       False,
                'error':         'exception',
                'error_details': sanitized,
                'nlp_answer':    sanitized,
            }
            try:
                self.history.append_message(
                    'assistant_answer', sanitized,
                    {'had_error': True, 'error_type': 'exception', 'error_details': sanitized}
                )
            except Exception:
                pass
            return error_result
