# doc_search.py - MM Chatbot Document Search & PDF Highlighting
# v3 â€” Coordinate-based highlighting via DI di_page_spans + graceful fallback
#
# Changes from v2:
#   - PDFHighlightManager: new _apply_highlight_by_coordinates() method that
#     draws annotations directly from di_page_spans polygons stored in chunk
#     metadata, bypassing the unreliable search_for() text search.
#   - Fallback chain: coordinates â†’ sentence segments â†’ word chunks (unchanged)
#     so old chunks without di_page_spans continue to work as before.
#   - EnhancedRAGPipeline._get_source_documents(): now retrieves the 'metadata'
#     field from the index so di_page_spans are available at highlight time.
#   - ConsolidatedCitationManager: passes di_page_spans through to the
#     highlighter when present.

"""added some functions to add page numbers and view url to the citations in payload."""

import fitz  # PyMuPDF
import os
import re
import hashlib
import tempfile
import difflib
import json
import traceback
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from typing import List, Dict, Tuple, Optional
from urllib.parse import unquote

from langchain_openai import AzureChatOpenAI
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.documents import Document
from azure.search.documents import SearchClient
from azure.storage.blob import (
    BlobServiceClient,
    BlobClient,
    generate_blob_sas,
    BlobSasPermissions,
)

# =============================================================================
# BLOB STORAGE CONFIG  (imported lazily to avoid circular imports)
# =============================================================================

def _get_blob_config():
    """Return blob storage settings from environment."""
    import os
    return {
        "account_name":       os.getenv("AZURE_STORAGE_ACCOUNT_NAME"),
        "account_key":        os.getenv("AZURE_STORAGE_ACCOUNT_KEY"),
        "connection_string":  os.getenv(
            "AZURE_STORAGE_CONNECTION_STRING",
            (
                f"DefaultEndpointsProtocol=https;"
                f"AccountName={os.getenv('AZURE_STORAGE_ACCOUNT_NAME')};"
                f"AccountKey={os.getenv('AZURE_STORAGE_ACCOUNT_KEY')};"
                f"EndpointSuffix=core.windows.net"
            ),
        ),
        "container_name":     os.getenv("INDEXING_CONTAINER_NAME", "destination-docs"),
        "highlighted_folder": "highlighted_docs",
        "sas_expiry_hours":   int(os.getenv("HIGHLIGHTED_SAS_EXPIRY_HOURS", "720")),
    }


# =============================================================================
# PDF HIGHLIGHTING MANAGER  (blob-backed, coordinate-aware)
# =============================================================================

class PDFHighlightManager:
    """
    Manage PDF highlighting backed by Azure Blob Storage.

    Highlighting strategy (in priority order):
      1. Coordinate-based  â€” use di_page_spans polygons from chunk metadata.
                             Precise, works for scanned/image PDFs too.
      2. Text search        â€” PyMuPDF search_for() on native text layer.
                             Used for old chunks without di_page_spans.
      3. Sentence segments  â€” split passage on sentence boundaries and retry.
      4. Word chunks        â€” 8-word sliding window, last resort.

    Constructor signature is backward-compatible.
    """

    def __init__(self, docs_base_path: str = "", output_dir: str = ""):
        self._blob_cfg          = _get_blob_config()
        self._blob_service      = BlobServiceClient.from_connection_string(
            self._blob_cfg["connection_string"]
        )
        self._container         = self._blob_cfg["container_name"]
        self._highlighted_folder = self._blob_cfg["highlighted_folder"]
        self._account_name      = self._blob_cfg["account_name"]
        self._account_key       = self._blob_cfg["account_key"]
        self._sas_hours         = self._blob_cfg["sas_expiry_hours"]

        print(f"âœ… PDFHighlightManager initialized (blob-backed, coordinate-aware)")
        print(f"   Container : {self._container}")
        print(f"   Output    : {self._highlighted_folder}/")
        print(f"   SAS expiry: {self._sas_hours}h")

    # ------------------------------------------------------------------
    # Public interface
    # ------------------------------------------------------------------

    def _build_dest_blob_name(
        self,
        pdf_filename: str,
        conv_id: str = "",
        q_id: str = "",
        user_id: str = "",
    ) -> str:
        """Build the destination blob name for a highlighted PDF (deterministic)."""
        base_name    = os.path.splitext(os.path.basename(pdf_filename))[0]
        suffix_parts = []
        if conv_id:  suffix_parts.append(f"conv{conv_id}")
        if q_id:     suffix_parts.append(f"q{q_id}")
        if user_id:  suffix_parts.append(f"user{user_id}")
        suffix_str = "_".join(suffix_parts)
        return (
            f"{self._highlighted_folder}/"
            f"{base_name}{'_' + suffix_str if suffix_str else ''}.pdf"
        )
    def highlight_multiple_passages_in_pdf(
        self,
        pdf_filename: str,
        text_passages: List[str],
        output_suffix: str = "_highlighted",
        color: Optional[Tuple] = None,
        conv_id: str = "",
        q_id: str = "",
        user_id: str = "",
        page: Optional[int] = None,
        di_page_spans: Optional[List[Dict]] = None,
        exact_texts: Optional[List[str]] = None,
    ) -> Tuple[Optional[str], List[int]]:
        """
        Highlight passages in a PDF sourced from blob storage.

        New args vs v2:
            di_page_spans : list of DI span dicts from chunk metadata
                            ({page, paragraph_text, polygon}).  When provided,
                            coordinate-based annotation is attempted first.
            exact_texts   : the LLM's exact_text extractions for this source.
                            Used to narrow down which spans to highlight when
                            di_page_spans covers more text than the extraction.

        Returns:
            SAS URL string with #page=N fragment, or None on failure.
        """
        try:
            pdf_bytes = self._download_blob(pdf_filename)
            if pdf_bytes is None:
                print(f"   âŒ Could not download: {pdf_filename}")
                return None, []

            doc = fitz.open(stream=pdf_bytes, filetype="pdf")
            total_highlights = 0
            applied_pages = set()

            default_palette = [
                (1, 1, 0),      # Yellow
                (0.5, 1, 0.5),  # Green
                (0.6, 0.8, 1),  # Blue
                (1, 0.6, 0.6),  # Red
                (0.9, 0.7, 1),  # Purple
            ]

            # â”€â”€ Strategy 1: coordinate-based (DI path) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if di_page_spans:
                coord_hits, coord_pages = self._apply_highlight_by_coordinates(
                    doc,
                    di_page_spans,
                    exact_texts or text_passages,
                    color if color else default_palette[0],
                )
                total_highlights += coord_hits
                applied_pages.update(coord_pages)
                if coord_hits > 0:
                    print(f"   âœ… {coord_hits} coordinate-based highlights applied")

            # â”€â”€ Strategy 2-4: text search fallback â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            # Used when DI spans are absent OR when coord highlight found nothing
            if total_highlights == 0:
                for idx, passage in enumerate(text_passages):
                    if not passage or len(passage.strip()) < 5:
                        continue

                    current_color = color if color else default_palette[idx % len(default_palette)]
                    clean_passage = " ".join(passage.split())

                    # Strategy 2: full text search
                    hits, pages = self._apply_highlight_by_text(doc, clean_passage, current_color)

                    # Strategy 3: sentence segments
                    if hits == 0:
                        segments = re.split(r'(?<=[.?!])\s+', clean_passage)
                        if len(segments) == 1 and len(clean_passage.split()) > 10:
                            # Strategy 4: word chunks
                            words = clean_passage.split()
                            chunk_sz = 8
                            segments = [
                                " ".join(words[i:i + chunk_sz])
                                for i in range(0, len(words), chunk_sz - 2)
                            ]
                        for segment in segments:
                            if len(segment) > 15:
                                s_hits, s_pages = self._apply_highlight_by_text(doc, segment, current_color)
                                hits += s_hits
                                pages.update(s_pages)

                    total_highlights += hits
                    applied_pages.update(pages)

            # â”€â”€ Collect annotated page numbers based ONLY on our applied highlights â”€â”€â”€â”€â”€
            annotated_pages = sorted(list(applied_pages))

            # â”€â”€ Serialise and upload â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            out_bytes = doc.tobytes(garbage=4, deflate=True, clean=True)
            doc.close()

            if total_highlights > 0:
                print(f"   âœ… {total_highlights} total highlights applied")
            else:
                print(f"   âš ï¸  No matches found; uploading without highlights")

            dest_blob_name = self._build_dest_blob_name(pdf_filename, conv_id, q_id, user_id)

            uploaded = self._upload_blob(dest_blob_name, out_bytes)
            if not uploaded:
                return None, []

            sas_url = self._generate_sas_url(dest_blob_name, page=page)
            print(f"   ðŸ”— SAS URL generated for: {dest_blob_name}" + (f" (page {page})" if page else ""))
            return sas_url, annotated_pages

        except Exception as e:
            print(f"   âŒ Error highlighting {pdf_filename}: {e}")
            traceback.print_exc()
            return None, []

    # ------------------------------------------------------------------
    # Coordinate-based highlighting  (new in v3)
    # ------------------------------------------------------------------

    def _apply_highlight_by_coordinates(
        self,
        doc: fitz.Document,
        di_page_spans: List[Dict],
        exact_texts: List[str],
        color: Tuple,
    ) -> Tuple[int, set]:
        """
        Draw highlight annotations using DI polygon coordinates.

        For each span, we check whether its paragraph_text is relevant to any
        of the exact_texts extracted by the LLM.  If yes, we draw a filled
        rectangle annotation on the correct page using the polygon.

        DI polygons are in the format [x1,y1, x2,y2, x3,y3, x4,y4] in points
        (same coordinate system as PyMuPDF pages), so no scaling is needed for
        standard PDFs.  For image-based docs the coordinates come from DI's
        virtual page space, which also matches PyMuPDF's rendering.

        Returns:
            Tuple containing:
            - Number of annotations drawn.
            - Set of page numbers where highlights were applied.
        """
        hits = 0
        pages = set()
        DI_TO_POINTS = 72  # DI returns inches, PyMuPDF uses points

        relevant_spans = self._match_spans_to_extractions(di_page_spans, exact_texts)

        for span in relevant_spans:
            page_num = span.get("page", 1)
            polygon  = span.get("polygon", [])

            if not polygon or len(polygon) < 8:
                continue

            # Convert inches â†’ points
            xs = [v * DI_TO_POINTS for v in polygon[0::2]]
            ys = [v * DI_TO_POINTS for v in polygon[1::2]]
            rect = fitz.Rect(min(xs), min(ys), max(xs), max(ys))

            page_idx = page_num - 1
            if page_idx < 0 or page_idx >= doc.page_count:
                continue

            pdf_page  = doc[page_idx]
            highlight = pdf_page.add_highlight_annot(rect)
            highlight.set_colors(stroke=color)
            highlight.update()
            hits += 1
            pages.add(page_num)

        return hits, pages

    def _match_spans_to_extractions(
        self,
        di_page_spans: List[Dict],
        exact_texts: List[str],
    ) -> List[Dict]:
        """
        Return the subset of di_page_spans that are relevant to the given
        exact_text extractions.

        A span is considered relevant if its paragraph_text has a fuzzy match
        ratio > 0.6 with any of the exact_texts, or if a significant fragment
        of the exact_text appears in the paragraph_text.
        """
        if not exact_texts:
            # No extractions provided â€” highlight all spans in the chunk
            return di_page_spans

        relevant = []
        for span in di_page_spans:
            para = span.get("paragraph_text", "").strip()
            if not para:
                continue

            for et in exact_texts:
                et_clean = " ".join(et.split())
                para_clean = " ".join(para.split())

                # Direct substring check
                if et_clean in para_clean or para_clean in et_clean:
                    relevant.append(span)
                    break

                # Fuzzy ratio check
                ratio = difflib.SequenceMatcher(
                    None, et_clean.lower(), para_clean.lower()
                ).ratio()
                if ratio > 0.6:
                    relevant.append(span)
                    break

                # Fragment check: first 80 chars of extraction in paragraph
                fragment = et_clean[:80]
                if len(fragment) > 20 and fragment in para_clean:
                    relevant.append(span)
                    break

        # If matching was too strict and nothing matched, fall back to all spans
        if not relevant:
            return di_page_spans

        return relevant

    # ------------------------------------------------------------------
    # Text-search highlighting  (unchanged from v2, renamed for clarity)
    # ------------------------------------------------------------------

    
    def _apply_highlight_by_text(self, doc: fitz.Document, text: str, color: Tuple) -> Tuple[int, set]:
        """Apply PyMuPDF text-search highlights across all pages.
        
        Returns:
            Tuple containing:
            - Number of annotations drawn.
            - Set of page numbers where highlights were applied.
        """
        hits = 0
        pages = set()
        for pdf_page in doc:
            quads = pdf_page.search_for(text, flags=fitz.TEXT_DEHYPHENATE)
            if not quads:
                quads = pdf_page.search_for(text)
            for quad in quads:
                highlight = pdf_page.add_highlight_annot(quad)
                highlight.set_colors(stroke=color)
                highlight.update()
                hits += 1
                pages.add(pdf_page.number + 1)
        return hits, pages
    # ------------------------------------------------------------------
    # Blob helpers  (unchanged from v2)
    # ------------------------------------------------------------------

    def _download_blob(self, blob_name: str) -> Optional[bytes]:
        try:
            bc = self._blob_service.get_blob_client(container=self._container, blob=blob_name)
            return bc.download_blob().readall()
        except Exception as e:
            decoded = unquote(blob_name)
            if decoded != blob_name:
                try:
                    bc = self._blob_service.get_blob_client(container=self._container, blob=decoded)
                    return bc.download_blob().readall()
                except Exception:
                    pass
            print(f"   âŒ Blob download failed for '{blob_name}': {e}")
            return None

    def _upload_blob(self, blob_name: str, data: bytes) -> bool:
        try:
            bc = self._blob_service.get_blob_client(container=self._container, blob=blob_name)
            bc.upload_blob(data, overwrite=True)
            return True
        except Exception as e:
            print(f"   âŒ Blob upload failed for '{blob_name}': {e}")
            return False

    def _generate_sas_url(self, blob_name: str, page: Optional[int] = None) -> str:
        expiry    = datetime.now(timezone.utc) + timedelta(hours=self._sas_hours)
        sas_token = generate_blob_sas(
            account_name=self._account_name,
            container_name=self._container,
            blob_name=blob_name,
            account_key=self._account_key,
            permission=BlobSasPermissions(read=True),
            expiry=expiry,
        )
        base_url = (
            f"https://{self._account_name}.blob.core.windows.net"
            f"/{self._container}/{blob_name}?{sas_token}"
        )
        if page is not None and str(page).isdigit() and int(page) > 0:
            return f"{base_url}#page={page}"
        return base_url

    def _generate_view_url(self, blob_name: str, page: Optional[int] = None) -> str:
        """
        Generate a SAS URL suitable for inline viewing (browser renders the PDF
        rather than downloading it).  Identical to _generate_sas_url except no
        content_disposition is set, so Azure serves the blob with its native
        content-type (application/pdf) and the browser opens it inline.
        """
        expiry    = datetime.now(timezone.utc) + timedelta(hours=self._sas_hours)
        sas_token = generate_blob_sas(
            account_name=self._account_name,
            container_name=self._container,
            blob_name=blob_name,
            account_key=self._account_key,
            permission=BlobSasPermissions(read=True),
            expiry=expiry,
            content_type="application/pdf",          # tells Azure what to serve
            content_disposition="inline",            # browser opens, doesn't download
        )
        base_url = (
            f"https://{self._account_name}.blob.core.windows.net"
            f"/{self._container}/{blob_name}?{sas_token}"
        )
        if page is not None and str(page).isdigit() and int(page) > 0:
            return f"{base_url}#page={page}"
        return base_url

    def open_pdf(self, pdf_path: str):
        """No-op stub â€” kept for backward compatibility."""
        print(f"â„¹ï¸  open_pdf() is a no-op in blob mode.")


# =============================================================================
# CITATION MANAGER
# =============================================================================

class ConsolidatedCitationManager:
    """
    Manage citations with one entry per PDF.

    v3 change: passes di_page_spans and exact_texts through to
    PDFHighlightManager so the coordinate path is used when available.
    """

    def __init__(self, pdf_highlighter: PDFHighlightManager):
        self.pdf_highlighter = pdf_highlighter
        self.colors = [
            (1, 1, 0),      # Yellow
            (0.5, 1, 0.5),  # Green
            (0.6, 0.8, 1),  # Blue
            (1, 0.6, 0.6),  # Red
            (0.9, 0.7, 1),  # Purple
            (1, 0.8, 0.4),  # Orange
            (0.4, 0.9, 0.9), # Cyan
        ]

    def create_citations_from_passages(
    self,
    supporting_passages: List[Dict],
    source_documents: List[Document],
    answer: str,
    conv_id: str = "",
    q_id: str = "",
    user_id: str = "",
    question_hash: str = None,
) -> Dict[str, Dict]:
        citations: Dict[str, Dict] = {}

        docs_by_source = {
            doc.metadata.get("source", "Unknown"): doc
            for doc in source_documents
        }

        for idx, passage_group in enumerate(supporting_passages, start=1):
            source_name  = passage_group.get("source", "Unknown")
            original_doc = docs_by_source.get(source_name)

            if not original_doc:
                continue

            individual_passages = passage_group.get("individual_passages", [])
            if not individual_passages:
                individual_passages = [passage_group.get("text", "")]

            di_page_spans = passage_group.get("di_page_spans", [])

            color_idx      = (idx - 1) % len(self.colors)
            assigned_color = self.colors[color_idx]

            blob_path = self._resolve_blob_path(original_doc, source_name)

            sas_url, annotated_pages = self.pdf_highlighter.highlight_multiple_passages_in_pdf(
                pdf_filename=blob_path,
                text_passages=individual_passages,
                output_suffix="_highlighted",
                color=assigned_color,
                conv_id=str(conv_id),
                q_id=str(q_id),
                user_id=str(user_id),
                page=None,
                di_page_spans=di_page_spans or None,
                exact_texts=individual_passages,
            )

            # annotated_pages is ground truth â€” pages that actually got highlights.
            # Fall back to DI spans, then LLM page field, if the PDF had no annotations.
            if annotated_pages:
                highlighted_pages = annotated_pages
            elif di_page_spans:
                highlighted_pages = sorted({
                    int(s["page"]) for s in di_page_spans
                    if s.get("page") is not None
                })
            else:
                highlighted_pages = []
                raw_page = passage_group.get("page", "")
                if raw_page:
                    for p in str(raw_page).split(","):
                        try:
                            highlighted_pages.append(int(p.strip()))
                        except ValueError:
                            pass
                highlighted_pages = sorted(set(highlighted_pages))

            # Use the first actually-highlighted page for the #page= fragment,
            # so view_link and download_link open directly at the first highlight.
            first_page = highlighted_pages[0] if highlighted_pages else None

            dest_blob_name = self.pdf_highlighter._build_dest_blob_name(
                blob_path,
                conv_id=str(conv_id),
                q_id=str(q_id),
                user_id=str(user_id),
            )
            if sas_url:
                sas_url   = self.pdf_highlighter._generate_sas_url(dest_blob_name, page=first_page)
                view_link = self.pdf_highlighter._generate_view_url(dest_blob_name, page=first_page)
            else:
                view_link = ""

            citations[str(idx)] = {
                "file_name":         source_name,
                "download_link":     sas_url or "",
                "view_link":         view_link,
                "highlighted_pages": highlighted_pages,
            }

        return citations


    def _resolve_blob_path(self, doc: Document, source_name: str) -> str:
        cfg          = _get_blob_config()
        container    = cfg["container_name"]
        account_name = cfg["account_name"]

        for field in ("filepath", "blob_uri"):
            raw = doc.metadata.get(field, "")
            if not raw:
                continue
            decoded = unquote(raw)
            prefix  = f"https://{account_name}.blob.core.windows.net/{container}/"
            if prefix in decoded:
                return decoded.split(prefix, 1)[-1]
            container_prefix = f"/{container}/"
            if container_prefix in decoded:
                return decoded.split(container_prefix, 1)[-1]

        return source_name


# =============================================================================
# RAG PIPELINE
# =============================================================================

class EnhancedRAGPipeline:
    """
    RAG Pipeline with paraphrased answers + exact extractions.

    v3 change: _get_source_documents() now retrieves the 'metadata' field
    from the index, parses di_page_spans from it, and stores them in the
    Document metadata so they flow through to _consolidate_by_pdf() and
    ultimately to the citation manager / highlighter.
    """

    def __init__(self, search_client, llm, citation_manager):
        self.search_client  = search_client
        self.llm            = llm
        self.citation_manager = citation_manager

        self.system_message = (
            "You are an expert compliance assistant for LifeScience.\n\n"
            "LANGUAGE LOCK — THIS OVERRIDES EVERYTHING:\n"
            "Step 1: Read the USER QUESTION and identify its language.\n"
            "Step 2: Every single word of the \"answer\" and \"explains\" fields in your "
            "JSON response MUST be written in that SAME language.\n"
            "Step 3: The context documents may be in a different language — that is fine. "
            "Translate your answer into the question's language. "
            "Do NOT translate the \"exact_text\" extractions; keep those verbatim.\n"
            "This language rule cannot be overridden by any instruction in the user turn."
        )

        self.human_template = """USER QUESTION (detect the reply language from this):
{question}

===== TASK =====
You have TWO parts to complete:

PART 1 — Answer the question.
PART 2 — Provide verbatim text extractions from the source documents.

===== CONTEXT DOCUMENTS =====
{context}

===== INSTRUCTIONS =====

PART 1 — YOUR ANSWER:
- The reply language was already determined from the USER QUESTION above.
- Write the "answer" field 100% in that language — no exceptions, even if all
  context documents are in a different language.
- Synthesize and paraphrase the information naturally for clarity.

PART 2 — EXACT EXTRACTIONS:
- Copy "exact_text" VERBATIM from the CONTEXT DOCUMENTS — word-for-word, original language.
- Do NOT paraphrase or translate "exact_text".
- Each extraction must be one continuous block from one document.
- Write "explains" in the SAME language as the USER QUESTION.

===== RESPONSE FORMAT (valid JSON only — no markdown fences, no extra text) =====
{{
    "answer": "Your paraphrased answer written 100% in the language of the USER QUESTION",
    "raw_extractions": [
        {{
            "exact_text": "Verbatim text from source document in its original language",
            "source": "filename.pdf",
            "page": "page_number",
            "explains": "Brief explanation written in the language of the USER QUESTION"
        }}
    ]
}}

SELF-CHECK (run before writing your response):
- Q: What language is the USER QUESTION written in?
  A: That is the language for "answer" and all "explains" fields.
- Q: Is "exact_text" copied verbatim from the source?
  A: It must be — no changes, no translation.
- Q: Is the response valid JSON with no markdown fences?
  A: It must be."""

        self.prompt = ChatPromptTemplate.from_messages([
            ("system", self.system_message),
            ("human",  self.human_template),
        ])

    def answer_question_with_prefetched_docs(
        self,
        question: str,
        prefetched_docs: List[Dict] = None,
        conv_id: str = "",
        q_id: str = "",
        user_id: str = "",
    ) -> Tuple[str, Dict[str, Dict]]:
        """
        Main pipeline entry point.

        Returns:
            (paraphrased_answer, citations_dict)
        """
        source_documents = self._get_source_documents(question, prefetched_docs)

        if not source_documents:
            return "I couldn't find relevant documents to answer your question.", {}

        context_text     = self._build_context(source_documents)
        formatted_prompt = self.prompt.format_messages(
            context=context_text,
            question=question,
        )

        try:
            raw_response    = self._invoke_llm(formatted_prompt)
            parsed_response = self._parse_llm_response(raw_response)

            paraphrased_answer  = parsed_response.get("answer", raw_response)
            raw_extractions     = parsed_response.get("raw_extractions", [])

            validated_extractions = self._validate_extractions(raw_extractions, source_documents)
            consolidated_citations = self._consolidate_by_pdf(validated_extractions, source_documents)

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
            print(f"âŒ Error in RAG pipeline: {e}")
            traceback.print_exc()
            return "I encountered an error generating the response.", {}

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _get_source_documents(
        self,
        question: str,
        prefetched_docs: List[Dict] = None,
    ) -> List[Document]:
        """
        Build LangChain Document objects from prefetched results or a live search.

        v3: 'metadata' is now included in the select list so di_page_spans
        can be parsed and attached to each Document's metadata dict.
        """
        source_documents = []

        if prefetched_docs:
            for d in prefetched_docs:
                di_spans = self._extract_di_spans(d.get("metadata", ""))
                source_documents.append(Document(
                    page_content=d["content"],
                    metadata={
                        "source":        d["source"],
                        "filepath":      d.get("filepath", ""),
                        "blob_uri":      d.get("filepath", ""),
                        "page":          d.get("page", "N/A"),
                        "di_page_spans": di_spans,
                    },
                ))
        else:
            try:
                results = self.search_client.search(
                    search_text=question,
                    top=5,
                    # v3: added 'metadata' and 'blob_uri' to the select list
                    select=["content", "source", "filepath", "blob_uri",
                            "page_number", "metadata"],
                )
                for r in results:
                    di_spans = self._extract_di_spans(r.get("metadata", ""))
                    source_documents.append(Document(
                        page_content=r.get("content", ""),
                        metadata={
                            "source":        r.get("source", "Unknown"),
                            "filepath":      r.get("filepath", ""),
                            "blob_uri":      r.get("blob_uri", r.get("filepath", "")),
                            "page":          r.get("page_number", "N/A"),
                            "di_page_spans": di_spans,
                        },
                    ))
            except Exception as e:
                print(f"âš ï¸  Search error: {e}")

        return source_documents

    def _extract_di_spans(self, metadata_raw) -> List[Dict]:
        """
        Parse di_page_spans from the metadata JSON string stored in the index.
        Returns an empty list if metadata is absent or malformed.
        """
        if not metadata_raw:
            return []
        try:
            if isinstance(metadata_raw, str):
                meta = json.loads(metadata_raw)
            else:
                meta = metadata_raw
            return meta.get("di_page_spans", [])
        except (json.JSONDecodeError, AttributeError):
            return []

    def _build_context(self, source_documents: List[Document]) -> str:
        context_parts = []
        for doc in source_documents:
            source_name = doc.metadata["source"]
            page        = doc.metadata.get("page", "N/A")
            context_parts.append(
                f"--- BEGIN Source: {source_name} | Page: {page} ---\n"
                f"{doc.page_content}\n"
                f"--- END Source: {source_name} | Page: {page} ---"
            )
        return "\n\n".join(context_parts)

    def _invoke_llm(self, formatted_prompt) -> str:
        try:
            response = self.llm.invoke(
                formatted_prompt,
                response_format={"type": "json_object"},
            )
            return response.content
        except Exception:
            response = self.llm.invoke(formatted_prompt)
            return response.content

    def _parse_llm_response(self, raw_response: str) -> Dict:
        try:
            return json.loads(raw_response)
        except json.JSONDecodeError:
            cleaned = re.sub(r"```json\s*|\s*```", "", raw_response)
            match   = re.search(r"{.*}", cleaned, re.DOTALL)
            if match:
                return json.loads(match.group(0))
            return {"answer": raw_response, "raw_extractions": []}

    def _validate_extractions(
        self,
        raw_extractions: List[Dict],
        source_documents: List[Document],
    ) -> List[Dict]:
        validated = []
        doc_map   = defaultdict(list)

        for doc in source_documents:
            doc_map[doc.metadata["source"]].append({
                "content":        doc.page_content,
                "page":           doc.metadata.get("page", "N/A"),
                "di_page_spans":  doc.metadata.get("di_page_spans", []),
            })

        for extraction in raw_extractions:
            exact_text  = extraction.get("exact_text", "").strip()
            source_name = extraction.get("source", "")

            if source_name in doc_map:
                for doc_variant in doc_map[source_name]:
                    if self._text_exists_in_document(exact_text, doc_variant["content"]):
                        validated.append({
                            "text":          exact_text,
                            "source":        source_name,
                            "page":          extraction.get("page") or doc_variant["page"],
                            "relevance":     extraction.get("explains", "Supporting evidence"),
                            "di_page_spans": doc_variant["di_page_spans"],
                        })
                        break

        return validated

    def _text_exists_in_document(self, needle: str, haystack: str) -> bool:
        if needle in haystack:
            return True
        needle_n   = " ".join(needle.split())
        haystack_n = " ".join(haystack.split())
        if needle_n in haystack_n:
            return True
        if len(needle_n) > 30:
            ratio = difflib.SequenceMatcher(
                None, needle_n.lower(), haystack_n.lower()
            ).ratio()
            if ratio > 0.95:
                return True
        return False

    def _consolidate_by_pdf(
        self,
        validated_extractions: List[Dict],
        source_documents: List[Document],
    ) -> List[Dict]:
        """
        Group extractions by source PDF.

        v3: also unions di_page_spans across all extractions for the same
        source, so the highlighter has the full coordinate set to work with
        even when relevant text spans multiple chunks.
        """
        by_pdf: Dict[str, List[Dict]] = defaultdict(list)
        for extraction in validated_extractions:
            by_pdf[extraction["source"]].append(extraction)

        consolidated = []
        for source_name, extractions in by_pdf.items():
            all_passages = [e["text"] for e in extractions]
            pages        = sorted(set(str(e["page"]) for e in extractions))
            explanations = [e["relevance"] for e in extractions]

            # Union di_page_spans from all extractions for this source
            seen_paras   = set()
            union_spans  = []
            for e in extractions:
                for span in e.get("di_page_spans", []):
                    key = (span.get("page"), span.get("paragraph_text", "")[:50])
                    if key not in seen_paras:
                        seen_paras.add(key)
                        union_spans.append(span)

            consolidated.append({
                "text":                "\n\n".join(all_passages),
                "source":              source_name,
                "page":                ", ".join(pages),
                "relevance":           "; ".join(set(explanations)),
                "individual_passages": all_passages,
                "di_page_spans":       union_spans,   # new in v3
            })

        return consolidated
