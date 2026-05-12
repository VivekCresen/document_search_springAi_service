# utils.py - MM Chatbot Core Utilities with Standalone Query Generation

import json
import re
import os
os.environ["KMP_DUPLICATE_LIB_OK"] = "TRUE"
import csv
import psycopg2
from psycopg2.extras import RealDictCursor
import pandas as pd
import requests
from datetime import datetime, date, time as dt_time
from typing import Dict, List, Any, Optional, Tuple
from pathlib import Path
import numpy as np
import pickle
import os
import time
import tempfile
import requests
import uuid

from config import (
    AZURE_OPENAI_ENDPOINT, AZURE_OPENAI_KEY, AZURE_OPENAI_DEPLOYMENT,
    AZURE_API_VERSION, TOKEN_COSTS, COST_TRACKING_CSV, CHAT_HISTORY_DIR
)

# =============================================================================
# UTILITY FUNCTIONS
# =============================================================================

def safe_parse_json(response_text: str) -> dict:
    """Safely parse JSON from LLM response with fallbacks"""
    text = re.sub(r'^(?:json)?\s*', '', response_text.strip(), flags=re.MULTILINE)
    text = re.sub(r'\s*$', '', text, flags=re.MULTILINE)
    try:
        return json.loads(text, strict=False)
    except json.JSONDecodeError:
        try:
            match = re.search(r'{.*}', text, re.DOTALL)
            if match: 
                return json.loads(match.group(0), strict=False)
        except: 
            pass
    return {}

def convert_results_to_json_serializable(results_data):
    """Convert database results to JSON-serializable format"""
    converted = []
    for row in results_data:
        new_row = {}
        for k, v in row.items():
            # CHANGE THIS LINE:
            if isinstance(v, (datetime, date, dt_time)):
                new_row[k] = v.isoformat()
            else:
                new_row[k] = str(v)
        converted.append(new_row)
    return converted



# After convert_results_to_json_serializable (line 48), add:

NLP_MAX_ROWS = 15  # shared threshold used by db_search.py and application.py

def sanitize_column_label(label: str) -> str:
    """
    Convert a raw DB column name or underscore-separated label to a
    human-readable Title Case string.

    Examples:
        'monitoring_activity_id'  → 'Monitoring Activity Id'
        'npi_number'              → 'Npi Number'
        'Activity ID'             → 'Activity ID'   (already clean — unchanged)
        'TOTAL_AMOUNT'            → 'Total Amount'
    """
    # If it already looks human (has spaces, mixed case) — leave it alone
    if ' ' in label or (label != label.lower() and label != label.upper()):
        return label
    return label.replace('_', ' ').title()


def sanitize_selected_columns(selected_columns: List[Dict]) -> List[Dict]:
    """
    Ensure every entry in selected_columns has a clean 'proxy' display label.

    Rules (applied in order):
      1. If 'proxy' exists and is non-empty  → sanitize it (catches LLM slips)
      2. If 'proxy' is missing or blank      → derive from 'name'
      3. 'name' (the actual DB column) is never modified

    Input:  [{"name": "monitoring_activity_id", "proxy": "Activity_ID"}, ...]
    Output: [{"name": "monitoring_activity_id", "proxy": "Activity Id"},  ...]
    """
    sanitized = []
    for col in selected_columns:
        # LLM occasionally returns plain strings instead of {"name":..., "proxy":...} dicts
        if isinstance(col, str):
            col = {'name': col, 'proxy': ''}
        raw_proxy = col.get('proxy', '').strip()
        raw_name  = col.get('name', '')
        clean_proxy = sanitize_column_label(raw_proxy if raw_proxy else raw_name)
        sanitized.append({**col, 'proxy': clean_proxy})
    return sanitized



# =============================================================================
# STANDALONE QUERY GENERATOR
# =============================================================================

class StandaloneQueryGenerator:
    def __init__(self, llm_client):
        self.llm_client = llm_client

    def create_standalone_query(
            self, 
            current_question: str, 
            conversation_history: str = ""
        ) -> str:
            
            prompt = f"""Given the conversation log below, reformulate the user's latest reply into a standalone, fully self-contained question.

========== CONVERSATION HISTORY ==========
(Reading from NEWEST to OLDEST)

{conversation_history}

========== CURRENT INTERACTION ==========
[CURRENT USER REPLY]: {current_question}

INSTRUCTIONS:
1. CHAINED FOLLOW-UPS: If the reply is a follow-up (e.g., "what about X?", "And in 2025?"), it is likely part of a multi-turn chain. Scan the ENTIRE conversation history to find the core subject and metric. You must combine the core subject with ALL previously established filters (e.g., regions, dates) AND the new filter from the current reply.
2. CLARIFICATION CONFIRMATION: If [TURN -1] is a clarifying question from the Assistant (e.g., "Did you mean X?"), and the [CURRENT USER REPLY] is a confirmation (e.g., "Yes", "Correct"), you MUST fuse the confirmed term into the user's original intent.
3. CLARIFICATION DENIAL: If the [CURRENT USER REPLY] corrects the Assistant (e.g., "No, I meant Y"), fuse their correction into the user's original intent.
4. ALREADY STANDALONE: If the reply is ALREADY a standalone question, keep it exactly as-is.
5. Translate your final standalone question into English if it is in another language.

Format your response EXACTLY like this:
REFORMULATED QUESTION: <standalone question>
ENGLISH TRANSLATION: <English translation>
"""
            try:
                # Bumped temperature from 0.1 to 0.2 for better synthesis of multi-turn chains
                response, _ = self.llm_client.generate(prompt, temperature=0.2)
                response = response.strip()
                
                if "ENGLISH TRANSLATION:" in response:
                    parts = response.split("ENGLISH TRANSLATION:")
                    
                    standalone_raw = parts[0].replace("REFORMULATED QUESTION:", "").strip()
                    standalone_raw = re.sub(r'^(question\s*:?\s*)', '', standalone_raw, flags=re.IGNORECASE).strip()
                    english_translation = parts[1].strip()
                    
                    if standalone_raw.lower() == english_translation.lower():
                        standalone_query = standalone_raw
                    else:
                        standalone_query = f"{standalone_raw} (English Translation: {english_translation})"
                else:
                    standalone_query = re.sub(
                        r'^(reformulated\s*question\s*:?\s*|question\s*:?\s*)', 
                        '', 
                        response, 
                        flags=re.IGNORECASE
                    ).strip()
                
                if len(standalone_query) > len(current_question) * 3:
                    # Optional: Adjust this multiplier if needed, but it's a good safety check
                    pass
                
                print(f"   🔄 Standalone query: '{standalone_query}'")
                return standalone_query
                
            except Exception as e:
                print(f"   ⚠️  Standalone query generation failed: {e}")
                return current_question


# =============================================================================
# COST TRACKER
# =============================================================================

class CostTracker:
    """Track API usage costs including reasoning tokens"""
    
    def __init__(self, csv_path: str = COST_TRACKING_CSV):
        self.csv_path = csv_path
        self.current_total = 0.0
        self._initialize_csv()
        self._load_current_total()

    def _initialize_csv(self):
        if not Path(self.csv_path).exists():
            with open(self.csv_path, 'w', newline='') as f:
                writer = csv.writer(f)
                writer.writerow([
                    'date', 'time', 'operation_type', 
                    'tokens_input', 'tokens_output', 'tokens_reasoning',
                    'cost_input', 'cost_output', 'total_cost', 'cumulative_total'
                ])

    def _load_current_total(self):
        try:
            df = pd.read_csv(self.csv_path)
            if not df.empty: 
                self.current_total = df['cumulative_total'].iloc[-1]
        except: 
            self.current_total = 0.0

    def log_usage(
        self, 
        input_tokens: int, 
        output_tokens: int, 
        reasoning_tokens: int = 0, 
        operation_type: str = "general"
    ) -> Dict:
        """Log token usage including reasoning tokens for reasoning models"""
        input_cost = (input_tokens / 1_000_000) * TOKEN_COSTS["input"]
        output_cost = (output_tokens / 1_000_000) * TOKEN_COSTS["output"]
        total_cost = input_cost + output_cost
        self.current_total += total_cost
        
        now = datetime.now()
        with open(self.csv_path, 'a', newline='') as f:
            writer = csv.writer(f)
            writer.writerow([
                now.strftime('%Y-%m-%d'), 
                now.strftime('%H:%M:%S'), 
                operation_type,
                input_tokens, 
                output_tokens, 
                reasoning_tokens,
                f"{input_cost:.10f}", 
                f"{output_cost:.10f}",
                f"{total_cost:.10f}", 
                f"{self.current_total:.10f}"
            ])
        
        return {
            "total_cost": total_cost, 
            "cumulative_total": self.current_total
        }

    def get_cumulative_total(self) -> float:
        return self.current_total

# =============================================================================
# AZURE OPENAI CLIENT
# =============================================================================

class AzureOpenAIClient:
    """Azure OpenAI client with reasoning model support and embedding support"""
    
    def __init__(self, deployment_name: Optional[str] = None):
        self.endpoint = AZURE_OPENAI_ENDPOINT
        self.api_key = AZURE_OPENAI_KEY
        self.deployment = deployment_name or AZURE_OPENAI_DEPLOYMENT
        self.api_version = AZURE_API_VERSION
        self.cost_tracker = CostTracker()
        
        # Detect if this is a reasoning model
        reasoning_models = ['o1', 'o3', 'o4', 'o5', 'gpt-5', 'reasoning']
        self.is_reasoning_model = any(x in self.deployment.lower() for x in reasoning_models)
        
        # Determine reasoning effort level
        self.reasoning_effort = self._get_reasoning_effort()
        
        print(f"✅ AzureOpenAIClient initialized for deployment: '{self.deployment}'")
        if self.is_reasoning_model:
            print(f"   ℹ️  Reasoning model detected")
            print(f"   ℹ️  reasoning_effort set to: '{self.reasoning_effort}'")
    
    def _get_reasoning_effort(self) -> Optional[str]:
        """Determine reasoning_effort parameter based on model name"""
        if not self.is_reasoning_model:
            return None
        
        deployment_lower = self.deployment.lower()
        
        # GPT-5 nano models get 'low' effort
        if 'gpt-5' in deployment_lower:
            return 'none'
        # Standard reasoning models get low effort (balanced)
        return 'none'

    def generate(
        self, 
        prompt: str, 
        temperature: float = 0.1, 
        max_tokens: int = 6000
    ) -> Tuple[str, Dict]:
        """Generate completion from Azure OpenAI"""
        import time
        start = time.time()
        
        headers = {
            "api-key": self.api_key, 
            "Content-Type": "application/json"
        }
        url = f"{self.endpoint.rstrip('/')}/openai/deployments/{self.deployment}/chat/completions?api-version={self.api_version}"
        
        payload = {
            "messages": [{"role": "user", "content": prompt}]
        }
        
        if self.is_reasoning_model:
            # Reasoning models use max_completion_tokens
            payload["max_completion_tokens"] = max_tokens
            
            # Add reasoning_effort parameter
            if self.reasoning_effort:
                payload["reasoning_effort"] = self.reasoning_effort
        else:
            # Standard models use max_tokens and temperature
            payload["max_tokens"] = max_tokens
            payload["temperature"] = temperature
        
        try:
            response = requests.post(url, headers=headers, json=payload, timeout=500)
            api_time = time.time() - start
            print(f"   ⏱️  Azure API call took: {api_time:.2f}s")
            
            response.raise_for_status()
            data = response.json()
            
            usage = data.get("usage", {})
            
            # Extract reasoning tokens if present
            reasoning_tokens = 0
            if self.is_reasoning_model:
                completion_details = usage.get("completion_tokens_details", {})
                reasoning_tokens = completion_details.get("reasoning_tokens", 0)
                
                if reasoning_tokens > 0:
                    print(f"   ℹ️  Reasoning tokens used: {reasoning_tokens}")
            
            # Log usage including reasoning tokens
            usage_stats = self.cost_tracker.log_usage(
                usage.get("prompt_tokens", 0),
                usage.get("completion_tokens", 0),
                reasoning_tokens
            )
            
            return data["choices"][0]["message"]["content"], usage_stats
            
        except requests.exceptions.Timeout:
            raise Exception(f"Azure OpenAI API timeout after 120 seconds")
        except requests.exceptions.HTTPError as e:
            error_detail = ""
            try:
                error_data = response.json()
                error_detail = f" - {error_data.get('error', {}).get('message', '')}"
            except:
                pass
            raise Exception(f"Azure OpenAI API HTTP error: {e}{error_detail}")
        except Exception as e:
            raise Exception(f"Azure OpenAI API error: {e}")

    def get_embeddings(self, texts: List[str]) -> np.ndarray:
        """Fetch vector embeddings from Azure OpenAI in batches of 16."""
        if not texts:
            return np.array([])
            
        embed_deployment = os.getenv('AZURE_OPENAI_EMBEDDING_DEPLOYMENT', 'text-embedding-ada-002')
        url = f"{self.endpoint.rstrip('/')}/openai/deployments/{embed_deployment}/embeddings?api-version={self.api_version}"
        headers = {"api-key": self.api_key, "Content-Type": "application/json"}
        
        all_embeddings = []
        batch_size = 100 # Safe batch size for Azure OpenAI
        
        for i in range(0, len(texts), batch_size):
            batch = texts[i:i+batch_size]
            payload = {"input": batch}
            try:
                response = requests.post(url, headers=headers, json=payload, timeout=30)
                response.raise_for_status()
                data = response.json()
                
                # Extract embeddings and sort by index to maintain order
                sorted_data = sorted(data["data"], key=lambda x: x["index"])
                all_embeddings.extend([item["embedding"] for item in sorted_data])
                
            except Exception as e:
                print(f"⚠️ Embedding failed for batch: {e}")
                # Pad with zeros to prevent dimension mismatch if a batch fails
                all_embeddings.extend([[0.0] * 1536 for _ in batch]) 
                
        return np.array(all_embeddings, dtype=np.float32)

    def get_cumulative_total(self) -> float:
        return self.cost_tracker.get_cumulative_total()


class SemanticCacheManager:
    """Manages SSD caching for distinct values and their embeddings."""
    def __init__(self, cache_file='semantic_cache_tst.pkl', ttl_seconds=300000): # 5 minutes TTL
        self.cache_file = os.path.join(tempfile.gettempdir(), cache_file)
        self.ttl_seconds = ttl_seconds
        self.cache = self._load_from_disk()

    def _load_from_disk(self) -> Dict:
        if os.path.exists(self.cache_file):
            try:
                with open(self.cache_file, 'rb') as f:
                    print(f"✅ Semantic cache loaded from SSD ({self.cache_file})")
                    return pickle.load(f)
            except Exception as e:
                print(f"⚠️ Failed to load cache from SSD: {e}")
        return {}

    def _save_to_disk(self):
        try:
            with open(self.cache_file, 'wb') as f:
                pickle.dump(self.cache, f)
        except Exception as e:
            print(f"⚠️ Failed to save cache to SSD: {e}")

    def get_or_build(self, view_name: str, categorical_columns: List[str], db_executor, llm_client) -> Tuple[Dict, Dict]:
        cache_key = f"{view_name}_{','.join(categorical_columns)}"
        entry = self.cache.get(cache_key)
        
        if entry and (time.time() - entry['timestamp'] < self.ttl_seconds):
            print(f"  ⚡ Using cached semantic values from memory/SSD")
            return entry['values'], entry['embeddings']

        print(f"  ⏳ Building semantic cache (DB fetch + Embeddings) for {len(categorical_columns)} columns...")
        
        # 1. Fetch from DB
        raw_values = db_executor.fetch_distinct_values_for_multiple_columns(view_name, categorical_columns)
        
        # Split comma-separated values (like department, policy) and clean them
        clean_values = {}
        for col, values in raw_values.items():
            split_vals = set()
            for val in values:
                for chunk in str(val).split(','):
                    chunk = chunk.strip()
                    if chunk:
                        split_vals.add(chunk)
            clean_values[col] = list(split_vals)

        # 2. Embed with Azure OpenAI
        embeddings = {}
        for col, values in clean_values.items():
            if values:
                embeddings[col] = llm_client.get_embeddings(values)
            else:
                embeddings[col] = np.array([])

        # 3. Update Cache & SSD
        self.cache[cache_key] = {
            'values': clean_values,
            'embeddings': embeddings,
            'timestamp': time.time()
        }
        self._save_to_disk()
        
        return clean_values, embeddings
# =============================================================================
# CHAT HISTORY TRACKER
# =============================================================================

# =============================================================================
# CHAT HISTORY TRACKER
# =============================================================================

class ChatHistoryTracker:
    _SESSIONS_TABLE = "prestage.db_search_hist_sessions"
    _MESSAGES_TABLE = "prestage.db_search_hist_messages"

    def __init__(self, db_config: Dict, user_id: int = None):
        self.db_config       = db_config
        self.user_id         = user_id
        self.current_chat_id = None
        self._ensure_tables()

    def _ensure_tables(self):
        with psycopg2.connect(**self.db_config) as conn:
            with conn.cursor() as cur:
                schema_name = "public"
                sess_table = self._SESSIONS_TABLE
                if "." in self._SESSIONS_TABLE:
                    schema_name, sess_table = self._SESSIONS_TABLE.split(".", 1)
                
                msg_table = self._MESSAGES_TABLE
                if "." in self._MESSAGES_TABLE:
                    schema_name_msg, msg_table = self._MESSAGES_TABLE.split(".", 1)
                else:
                    schema_name_msg = "public"

                # 1. Ensure sessions table exists
                cur.execute(f"""
                    CREATE TABLE IF NOT EXISTS {self._SESSIONS_TABLE} (
                        id               SERIAL PRIMARY KEY,
                        chat_id          VARCHAR(50)  NOT NULL,
                        user_id          INT,
                        started_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                        last_activity_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                        message_count    INT          NOT NULL DEFAULT 0
                    )
                """)
                # Force-add the user_id column if the table already existed but was missing it
                cur.execute(f"""
                    SELECT column_name 
                    FROM information_schema.columns 
                    WHERE table_schema = '{schema_name}' 
                      AND table_name = '{sess_table}' 
                      AND column_name = 'user_id'
                """)
                if not cur.fetchone():
                    cur.execute(f"ALTER TABLE {self._SESSIONS_TABLE} ADD COLUMN user_id INT")

                # 2. Ensure messages table exists
                cur.execute(f"""
                    CREATE TABLE IF NOT EXISTS {self._MESSAGES_TABLE} (
                        id           BIGSERIAL PRIMARY KEY,
                        chat_id      VARCHAR(50)  NOT NULL,
                        user_id      INT,
                        timestamp    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                        message_type VARCHAR(30)  NOT NULL,
                        content      TEXT,
                        metadata     JSONB
                    )
                """)
                # Force-add the user_id column to messages if it was missing
                cur.execute(f"""
                    SELECT column_name 
                    FROM information_schema.columns 
                    WHERE table_schema = '{schema_name_msg}' 
                      AND table_name = '{msg_table}' 
                      AND column_name = 'user_id'
                """)
                if not cur.fetchone():
                    cur.execute(f"ALTER TABLE {self._MESSAGES_TABLE} ADD COLUMN user_id INT")

                # 3. Drop legacy foreign key referencing the sessions table
                cur.execute(f"""
                    SELECT tc.constraint_name
                    FROM information_schema.table_constraints tc
                    WHERE tc.table_schema = '{schema_name_msg}'
                      AND tc.table_name = '{msg_table}'
                      AND tc.constraint_type = 'FOREIGN KEY'
                """)
                for row in cur.fetchall():
                    cur.execute(f"ALTER TABLE {self._MESSAGES_TABLE} DROP CONSTRAINT IF EXISTS {row[0]}")

                # 4. Drop the old standalone unique constraint strictly on `chat_id`
                cur.execute(f"""
                    SELECT tc.constraint_name
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.key_column_usage kcu 
                      ON tc.constraint_name = kcu.constraint_name
                    WHERE tc.table_schema = '{schema_name}'
                      AND tc.table_name = '{sess_table}'
                      AND tc.constraint_type = 'UNIQUE'
                    GROUP BY tc.constraint_name
                    HAVING COUNT(kcu.column_name) = 1 AND MAX(kcu.column_name::text) = 'chat_id'
                """)
                for row in cur.fetchall():
                    cur.execute(f"ALTER TABLE {self._SESSIONS_TABLE} DROP CONSTRAINT IF EXISTS {row[0]}")

                # 5. Safely add the new composite unique constraint on (chat_id, user_id)
                constraint_name = f"{sess_table}_chat_user_key"
                cur.execute(f"""
                    SELECT 1 FROM information_schema.table_constraints 
                    WHERE constraint_name = '{constraint_name}' AND table_schema = '{schema_name}'
                """)
                if not cur.fetchone():
                    # Drop old email constraint if it exists to be safe
                    cur.execute(f"ALTER TABLE {self._SESSIONS_TABLE} DROP CONSTRAINT IF EXISTS {sess_table}_chat_email_key")
                    cur.execute(f"ALTER TABLE {self._SESSIONS_TABLE} ADD CONSTRAINT {constraint_name} UNIQUE (chat_id, user_id)")

            conn.commit()

    def start_new_chat(self, user_id: int = None) -> str:
        chat_id = f"chat_{uuid.uuid4().hex[:12]}"
        with psycopg2.connect(**self.db_config) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    f"INSERT INTO {self._SESSIONS_TABLE} (chat_id, user_id) VALUES (%s, %s)",
                    (chat_id, user_id),
                )
            conn.commit()
        self.current_chat_id = chat_id
        return chat_id

    def start_new_chat_with_id(self, chat_id: str, user_id: int = None):
        effective_user_id = user_id or self.user_id
        with psycopg2.connect(**self.db_config) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    f"""
                    INSERT INTO {self._SESSIONS_TABLE} (chat_id, user_id)
                    VALUES (%s, %s)
                    ON CONFLICT (chat_id, user_id) DO NOTHING
                    """,
                    (chat_id, effective_user_id),
                )
            conn.commit()
        self.current_chat_id = chat_id
        if effective_user_id:
            self.user_id = effective_user_id

    def load_existing_chat(self, chat_id: str):
        self.current_chat_id = chat_id

    def list_available_chats(self) -> List[Dict]:
        with psycopg2.connect(**self.db_config) as conn:
            with conn.cursor(cursor_factory=RealDictCursor) as cur:
                cur.execute(f"""
                    SELECT s.chat_id, s.message_count, s.last_activity_at,
                        (SELECT LEFT(m.content, 50) || '...'
                         FROM {self._MESSAGES_TABLE} m
                         WHERE m.chat_id = s.chat_id 
                           AND (m.user_id = s.user_id OR (m.user_id IS NULL AND s.user_id IS NULL))
                           AND m.message_type = 'user_question'
                         ORDER BY m.id LIMIT 1) AS preview
                    FROM {self._SESSIONS_TABLE} s
                    ORDER BY s.last_activity_at DESC
                """)
                rows = cur.fetchall()
        return [{'chat_id': r['chat_id'], 'message_count': r['message_count'], 'preview': r['preview'] or 'Empty Chat'} for r in rows]

    def append_message(self, message_type: str, content: str, metadata: Optional[Dict] = None):
        if not self.current_chat_id:
            raise ValueError("No active chat session. Call start_new_chat() or load_existing_chat() first.")
        with psycopg2.connect(**self.db_config) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    f"INSERT INTO {self._SESSIONS_TABLE} (chat_id, user_id) VALUES (%s, %s) ON CONFLICT (chat_id, user_id) DO NOTHING",
                    (self.current_chat_id, self.user_id),
                )
                cur.execute(
                    f"INSERT INTO {self._MESSAGES_TABLE} (chat_id, user_id, message_type, content, metadata) VALUES (%s, %s, %s, %s, %s)",
                    (self.current_chat_id, self.user_id, message_type, content, json.dumps(metadata) if metadata else None),
                )
                
                # Restrict the metadata/message_count update to the correct distinct user identity
                update_query = f"UPDATE {self._SESSIONS_TABLE} SET last_activity_at = NOW(), message_count = message_count + 1 WHERE chat_id = %s"
                params = [self.current_chat_id]
                if self.user_id is not None:
                    update_query += " AND user_id = %s"
                    params.append(self.user_id)
                
                cur.execute(update_query, tuple(params))
            conn.commit()

    def get_recent_context(self, n_messages: int = 10) -> str:
        if not self.current_chat_id: return ""
        with psycopg2.connect(**self.db_config) as conn:
            with conn.cursor(cursor_factory=RealDictCursor) as cur:
                if self.user_id is not None:
                    cur.execute(
                        f"""
                        SELECT message_type, content FROM {self._MESSAGES_TABLE}
                        WHERE chat_id = %s AND user_id = %s
                        ORDER BY id DESC LIMIT %s
                        """, (self.current_chat_id, self.user_id, n_messages)
                    )
                else:
                    cur.execute(
                        f"SELECT message_type, content FROM {self._MESSAGES_TABLE} WHERE chat_id = %s ORDER BY id DESC LIMIT %s",
                        (self.current_chat_id, n_messages)
                    )
                rows = cur.fetchall()

        context_blocks = []
        
        # Rows are fetched DESC (newest first). Keep this order.
        for i, row in enumerate(rows):
            role = 'User' if row['message_type'] == 'user_question' else 'Assistant'
            
            # Apply mathematical turn indicators
            turn_label = f"[TURN -{i + 1} | {role}]"
            context_blocks.append(f"{turn_label}: {row['content']}")
            
        if not context_blocks:
            return "None (This is the first interaction)"
            
        return "\n\n".join(context_blocks)