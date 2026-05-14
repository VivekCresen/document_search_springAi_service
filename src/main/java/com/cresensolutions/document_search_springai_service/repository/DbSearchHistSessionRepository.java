package com.cresensolutions.document_search_springai_service.repository;

import com.cresensolutions.document_search_springai_service.domain.DbSearchHistSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DbSearchHistSessionRepository extends JpaRepository<DbSearchHistSession, Long> {

    Optional<DbSearchHistSession> findByChatIdAndUserId(String chatId, UUID userId);

    @Modifying
    @Query(value = """
            INSERT INTO prestage.db_search_hist_sessions (chat_id, user_id)
            VALUES (:chatId, :userId)
            ON CONFLICT (chat_id, user_id) DO NOTHING
            """, nativeQuery = true)
    void insertIfMissing(@Param("chatId") String chatId, @Param("userId") UUID userId);

    @Modifying
    @Query(value = """
            UPDATE prestage.db_search_hist_sessions
            SET last_activity_at = NOW(),
                message_count = message_count + :incrementBy
            WHERE chat_id = :chatId
              AND user_id IS NOT DISTINCT FROM :userId
            """, nativeQuery = true)
    void touchAndIncrement(
            @Param("chatId") String chatId,
            @Param("userId") UUID userId,
            @Param("incrementBy") int incrementBy
    );
}
