# doc_search_secured.py - Secured Document Search with User Permissions
# v4 — Retrieves metadata + blob_uri from index so di_page_spans and blob
#       path flow through to the highlighter. Added real-time file state 
#       filtering to ensure only 'stable' files are returned.

import json
from typing import List, Dict, Tuple, Optional
from azure.search.documents import SearchClient
from langchain_core.documents import Document

from doc_search import (
    PDFHighlightManager,
    ConsolidatedCitationManager,
    EnhancedRAGPipeline,
)
from UserPermissionManager import UserPermissionManager


# =============================================================================
# SECURED RAG PIPELINE WITH USER PERMISSIONS
# =============================================================================

class SecuredRAGPipeline(EnhancedRAGPipeline):
    """
    Extended RAG Pipeline with folder-level security and file stability state.

    Filters search results based on user's folder access permissions and ensures
    only fully ingested/stable files are retrieved.
    conv_id / q_id / user_id are threaded through to ConsolidatedCitationManager
    so that highlighted PDFs are named correctly in blob storage.
    """

    def __init__(
        self,
        search_client: SearchClient,
        llm,
        citation_manager: ConsolidatedCitationManager,
        permission_manager: UserPermissionManager,
    ):
        super().__init__(
            search_client=search_client,
            llm=llm,
            citation_manager=citation_manager,
        )
        self.permission_manager = permission_manager
        print("🔒 Secured RAG Pipeline initialized with user permissions & file state checks")

    # ------------------------------------------------------------------
    # Primary entry point (called by application.py)
    # ------------------------------------------------------------------

    def answer_question_with_security(
        self,
        question: str,
        username: str,
        prefetched_docs: List[Dict] = None,
        conv_id: str = "",
        q_id: str = "",
        user_id: str = "",
    ) -> Tuple[str, Dict[str, Dict]]:
        """
        Answer question with security filtering applied.

        Args:
            question        : user's question
            username        : username/email for permission checking
            prefetched_docs : optional pre-fetched documents to filter
            conv_id         : conversation ID — threaded into blob filename
            q_id            : question ID    — threaded into blob filename
            user_id         : user ID        — threaded into blob filename

        Returns:
            Tuple of (answer_text, citations_dict)
            citations_dict: Dict[str, Dict] → {"1": {file_name, download_link}, ...}
        """
        print(f"🔒 Processing question for user: {username}")

        if prefetched_docs:
            filtered_docs = self.permission_manager.filter_search_results(
                prefetched_docs, username
            )
            print(
                f"   Original docs: {len(prefetched_docs)}, "
                f"After security/state filter: {len(filtered_docs)}"
            )
            return self.answer_question_with_prefetched_docs(
                question,
                filtered_docs,
                conv_id=conv_id,
                q_id=q_id,
                user_id=user_id,
            )
        else:
            return self._secured_search_and_answer(
                question, username,
                conv_id=conv_id,
                q_id=q_id,
                user_id=user_id,
            )

    # ------------------------------------------------------------------
    # Internal: search + answer with security filter
    # ------------------------------------------------------------------

    def _secured_search_and_answer(
        self,
        question: str,
        username: str,
        conv_id: str = "",
        q_id: str = "",
        user_id: str = "",
    ) -> Tuple[str, Dict[str, Dict]]:
        """
        Perform document search with folder-level security filtering and state check,
        then run the RAG pipeline.

        v4: Uses combined filter for security and stability.
        """
        # Use the combined security + stability filter
        search_filter    = self.permission_manager.create_search_filter(username)
        source_documents = []

        try:
            search_params = {
                "search_text": question,
                "top": 5,
                # v3: added metadata and blob_uri
                "select": [
                    "content", "source", "filepath", "blob_uri",
                    "page_number", "folder_id", "metadata",
                ],
            }

            if search_filter:
                search_params["filter"] = search_filter
                print(f"   🔍 Searching with security/state filter: {search_filter[:100]}...")
            else:
                print("   🔍 Searching without restrictions (user has full access)")

            results = self.search_client.search(**search_params)

            for r in results:
                di_spans = self._extract_di_spans(r.get("metadata", ""))
                source_documents.append(
                    Document(
                        page_content=r.get("content", ""),
                        metadata={
                            "source":        r.get("source", "Unknown"),
                            "filepath":      r.get("filepath", ""),
                            # v3: blob_uri needed by _resolve_blob_path()
                            "blob_uri":      r.get("blob_uri", r.get("filepath", "")),
                            "page":          r.get("page_number", "N/A"),
                            "folder_id":     r.get("folder_id", "0"),
                            # v3: di_page_spans for coordinate-based highlighting
                            "di_page_spans": di_spans,
                        },
                    )
                )

            print(f"   ✅ Found {len(source_documents)} accessible & stable documents")

        except Exception as e:
            print(f"   ⚠️  Secured search error: {e}")
            return "I encountered an error while searching for documents.", {}

        if not source_documents:
            return (
                "I couldn't find any accessible documents to answer your question. "
                "This might be due to access restrictions or files currently updating.",
                {},
            )

        context_text     = self._build_context(source_documents)
        formatted_prompt = self.prompt.format_messages(
            context=context_text,
            question=question,
        )

        try:
            raw_response    = self._invoke_llm(formatted_prompt)
            parsed_response = self._parse_llm_response(raw_response)

            paraphrased_answer = parsed_response.get("answer", raw_response)
            raw_extractions    = parsed_response.get("raw_extractions", [])

            validated_extractions = self._validate_extractions(
                raw_extractions, source_documents
            )

            consolidated_citations = self._consolidate_by_pdf(
                validated_extractions, source_documents
            )

            enhanced_citations = self.citation_manager.create_citations_from_passages(
                supporting_passages=consolidated_citations,
                source_documents=source_documents,
                answer=paraphrased_answer,
                conv_id=conv_id,
                q_id=q_id,
                user_id=user_id,
            )

            return paraphrased_answer, enhanced_citations

        except Exception as e:
            print(f"❌ Error in secured RAG pipeline: {e}")
            return "I encountered an error generating the response.", {}

    # ------------------------------------------------------------------
    # Helper
    # ------------------------------------------------------------------

    def _extract_di_spans(self, metadata_raw) -> List[Dict]:
        """
        Parse di_page_spans from the metadata JSON string stored in the index.
        Returns an empty list if metadata is absent or malformed — in that case
        the highlighter falls back to text search automatically.
        """
        if not metadata_raw:
            return []
        try:
            meta = json.loads(metadata_raw) if isinstance(metadata_raw, str) else metadata_raw
            return meta.get("di_page_spans", [])
        except (json.JSONDecodeError, AttributeError):
            return []


# =============================================================================
# SECURED INTENT CLASSIFIER
# =============================================================================

class SecuredIntentClassifier:
    """Intent classifier with security-aware and file state document pre-fetching."""

    def __init__(
        self,
        llm_client,
        search_client: SearchClient,
        permission_manager: UserPermissionManager,
    ):
        self.llm_client       = llm_client
        self.search_client    = search_client
        self.permission_manager = permission_manager

    def search_relevant_documents_with_security(
        self,
        question: str,
        username: str,
        top_k: int = 5,
    ) -> List[Dict]:
        """
        Search documents with user permissions and stability applied.

        v4: Uses combined filter for security and stability.

        Returns:
            List of accessible document dictionaries
        """
        try:
            print(
                f"  🔍 Searching metadata fields for top {top_k} documents "
                f"(user: {username})..."
            )

            # Use the combined security + stability filter
            search_filter = self.permission_manager.create_search_filter(username)

            search_params = {
                "search_text":   question,
                "top":           top_k,
                "search_fields": ["example_queries", "topics", "intent_signals"],
                # v3: added metadata and blob_uri
                "select": [
                    "source", "filepath", "blob_uri", "content", "page_number",
                    "topics", "example_queries", "intent_signals",
                    "folder_id", "metadata",
                ],
            }

            if search_filter:
                search_params["filter"] = search_filter

            results   = self.search_client.search(**search_params)
            documents = []

            for r in results:
                # Parse di_page_spans eagerly so they travel with the doc dict
                raw_meta = r.get("metadata", "")
                try:
                    meta     = json.loads(raw_meta) if isinstance(raw_meta, str) else (raw_meta or {})
                    di_spans = meta.get("di_page_spans", [])
                except (json.JSONDecodeError, AttributeError):
                    di_spans = []

                documents.append(
                    {
                        "source":          r.get("source", "Unknown"),
                        "filepath":        r.get("filepath", ""),
                        # v3: blob_uri carried through for correct blob path resolution
                        "blob_uri":        r.get("blob_uri", r.get("filepath", "")),
                        "content":         r.get("content", ""),
                        "page":            r.get("page_number", "N/A"),
                        "score":           r.get("@search.score", 0.0),
                        "topics":          r.get("topics", []),
                        "example_queries": r.get("example_queries", []),
                        "intent_signals":  r.get("intent_signals", []),
                        "folder_id":       r.get("folder_id", "0"),
                        # v3: di_page_spans for coordinate highlighting
                        "di_page_spans":   di_spans,
                    }
                )

            print(f"  ✅ Found {len(documents)} accessible & stable documents")
            if documents:
                print(
                    f"  ↳ Top result: {documents[0]['source']} "
                    f"(score: {documents[0]['score']:.2f})"
                )

            return documents

        except Exception as e:
            print(f"  ⚠️  Secured metadata search failed: {e}")
            return []