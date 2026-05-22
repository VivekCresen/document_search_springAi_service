package com.cresensolutions.document_search_springai_service.repository;

import com.cresensolutions.document_search_springai_service.domain.DbSearchHistMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DbSearchHistMessageRepository extends JpaRepository<DbSearchHistMessage, Long> {

    @Query(value = """
            SELECT *
            FROM demo.db_search_hist_messages
            WHERE chat_id = :chatId
              AND user_id IS NOT DISTINCT FROM :userId
            ORDER BY id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<DbSearchHistMessage> findRecentContext(
            @Param("chatId") String chatId,
            @Param("userId") UUID userId,
            @Param("limit") int limit
    );
}
