"""
User Permission Manager
Filters document search results based on folder-level access control
AND real-time file stability state.

CORRECTED LOGIC:
- folders_access column contains folder IDs the user CANNOT access (restricted)
- User has access to ALL folders EXCEPT those listed in folders_access
"""

import psycopg2
from typing import List, Set, Dict, Optional
from psycopg2.extras import RealDictCursor

class UserPermissionManager:
    """
    Manages user-based document access control and file state filtering.
    
    Uses demo.document_repository_user_mapping to determine which folders
    a user CANNOT access (restricted folders), then filters search results accordingly.
    
    IMPORTANT: The folders_access column contains folder IDs the user is RESTRICTED from.
    """
    
    def __init__(self, db_config: Dict):
        """
        Initialize the permission manager
        
        Args:
            db_config: PostgreSQL connection configuration dict with keys:
                       host, database, user, password, port
        """
        self.db_config = db_config
        self._permission_cache = {}  # Cache: {username: set_of_restricted_folder_ids}
        
        print("✅ User Permission Manager initialized")
    
    def get_restricted_folder_ids(self, username: str) -> Set[str]:
        """
        Get the set of folder IDs that the user does NOT have access to.
        
        CORRECTED LOGIC: The folders_access column contains the RESTRICTED folders.
        
        Args:
            username: The user's username
            
        Returns:
            Set of folder_id values (as strings) that the user cannot access
        """
        # Check cache first
        if username in self._permission_cache:
            return self._permission_cache[username]
        
        try:
            with psycopg2.connect(**self.db_config) as conn:
                with conn.cursor(cursor_factory=RealDictCursor) as cur:
                    # Query to get all folder IDs the user is RESTRICTED from
                    # CORRECTED: folders_access contains the folders they CANNOT access
                    query = """
                        SELECT DISTINCT folders_access 
                        FROM demo.document_repository_user_mapping 
                        WHERE user_name = %s
                    """
                    cur.execute(query, (username,))
                    results = cur.fetchall()
                    
                    # Extract folder IDs they CANNOT access (restricted folders)
                    # CORRECTED: These are now treated as RESTRICTED, not accessible
                    restricted_folders = {
                        str(row['folders_access']) 
                        for row in results 
                        if row['folders_access'] is not None
                    }
                    
                    # Cache the result
                    self._permission_cache[username] = restricted_folders
                    
                    # Calculate accessible folders for logging purposes
                    cur.execute("""
                        SELECT DISTINCT id::text 
                        FROM demo.documents 
                        WHERE is_file = false
                    """)
                    all_folders = {row['id'] for row in cur.fetchall()}
                    accessible_count = len(all_folders) - len(restricted_folders)
                    
                    print(f"   ℹ️  User '{username}': {accessible_count} accessible folders, "
                          f"{len(restricted_folders)} restricted")
                    
                    return restricted_folders
                    
        except Exception as e:
            print(f"   ⚠️  Error fetching permissions for {username}: {e}")
            # On error, return empty set (allow all) - can be changed to deny all
            return set()

    def get_unstable_file_uris(self) -> Set[str]:
        """
        Get the set of blob URIs that are NOT in a 'stable' state.
        This ensures real-time hiding of files marked for deletion or currently updating.
        """
        try:
            with psycopg2.connect(**self.db_config) as conn:
                with conn.cursor() as cur:
                    # Safely check if the table exists to prevent crashes on fresh installs
                    cur.execute("""
                        SELECT EXISTS (
                            SELECT FROM information_schema.tables 
                            WHERE table_schema = 'demo' 
                            AND table_name = 'files_in_index'
                        );
                    """)
                    if not cur.fetchone()[0]:
                        return set()

                    cur.execute("SELECT blob_uri FROM demo.files_in_index WHERE status != 'stable'")
                    return {row[0] for row in cur.fetchall()}
        except Exception as e:
            print(f"   ⚠️  Error fetching unstable files: {e}")
            return set()
    
    def create_search_filter(self, username: str) -> str:
        """
        Create an OData filter string for Azure Search combining:
        1. Excluded restricted folders
        2. Excluded unstable files (to_be_deleted, ingestion_inp, etc.)
        
        Args:
            username: The user's username
            
        Returns:
            OData filter string like: "folder_id ne '1231' and blob_uri ne '...'"
            or empty string if no restrictions
        """
        restricted_ids = self.get_restricted_folder_ids(username)
        unstable_uris = self.get_unstable_file_uris()
        
        filter_parts = []
        
        # 1. Folder Security Filters
        for folder_id in restricted_ids:
            filter_parts.append(f"folder_id ne '{folder_id}'")
            
        # 2. File State Filters (Real-time stability check)
        for uri in unstable_uris:
            # Azure Search OData requires single quotes to be escaped
            safe_uri = uri.replace("'", "''")
            filter_parts.append(f"blob_uri ne '{safe_uri}'")
        
        return " and ".join(filter_parts)
    
    def filter_search_results(
        self, 
        search_results: List[Dict], 
        username: str
    ) -> List[Dict]:
        """
        Filter search results to exclude documents from restricted folders and unstable files.
        
        This is a backup filter in case the Azure Search filter isn't applied.
        
        Args:
            search_results: List of search result dictionaries
            username: The user's username
            
        Returns:
            Filtered list of search results
        """
        restricted_ids = self.get_restricted_folder_ids(username)
        unstable_uris = self.get_unstable_file_uris()
        
        if not restricted_ids and not unstable_uris:
            return search_results
        
        filtered_results = []
        excluded_count = 0
        
        for result in search_results:
            # Check if the document's folder_id is in the restricted set
            folder_id = str(result.get('folder_id', '0'))
            blob_uri = result.get('blob_uri', result.get('filepath', ''))
            
            if folder_id not in restricted_ids and blob_uri not in unstable_uris:
                filtered_results.append(result)
            else:
                excluded_count += 1
        
        if excluded_count > 0:
            print(f"   🔒 Filtered out {excluded_count} documents from restricted folders or unstable files")
        
        return filtered_results
    
    def clear_cache(self, username: Optional[str] = None):
        """
        Clear the permission cache.
        
        Args:
            username: If provided, only clear cache for this user.
                     If None, clear entire cache.
        """
        if username:
            self._permission_cache.pop(username, None)
            print(f"✅ Cache cleared for user: {username}")
        else:
            self._permission_cache.clear()
            print("✅ Permission cache completely cleared")
    
    def test_permissions(self, username: str, folder_ids: List[str]) -> Dict[str, bool]:
        """
        Test if a user has access to specific folders.
        
        Args:
            username: The user's username
            folder_ids: List of folder IDs to test
            
        Returns:
            Dict mapping folder_id -> has_access (bool)
        """
        restricted_ids = self.get_restricted_folder_ids(username)
        
        results = {}
        for folder_id in folder_ids:
            # User has access if folder_id is NOT in restricted set
            results[folder_id] = str(folder_id) not in restricted_ids
        
        return results


# =============================================================================
# EXAMPLE USAGE
# =============================================================================

if __name__ == "__main__":
    """Test the permission manager"""
    
    # Example DB config (would come from your config.py)
    db_config = {
        'host': 'localhost',
        'database': 'your_db',
        'user': 'your_user',
        'password': 'your_password',
        'port': 5432
    }
    
    # Initialize manager
    manager = UserPermissionManager(db_config)
    
    # Test for a user
    test_username = "erin.vales"
    
    print(f"\n🔍 Testing permissions for: {test_username}")
    
    # Get restricted folders
    restricted = manager.get_restricted_folder_ids(test_username)
    print(f"Restricted folder IDs (user CANNOT access): {restricted}")
    
    # Create OData filter
    filter_string = manager.create_search_filter(test_username)
    print(f"Azure Search filter: {filter_string}")
    
    # Test specific folders
    test_folders = ["1231", "1000", "5000"]
    access_results = manager.test_permissions(test_username, test_folders)
    print(f"\nAccess test results:")
    for folder_id, has_access in access_results.items():
        status = "✅ ALLOWED" if has_access else "🔒 DENIED"
        print(f"  Folder {folder_id}: {status}")
    
    print("\n" + "="*70)
    print("LOGIC EXPLANATION:")
    print("="*70)
    print("1. Query DB for folders_access WHERE user_name = 'erin.vales'")
    print("2. These folder IDs are the RESTRICTED folders (user CANNOT access)")
    print("3. User has access to ALL folders EXCEPT those in folders_access")
    print("4. Filter creates: folder_id ne '1231' and blob_uri ne '...'")
    print("="*70)