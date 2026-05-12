package com.cresensolutions.document_search_springai_service.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "db_search_hist_messages", schema = "prestage")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DbSearchHistMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false, length = 50)
    private String chatId;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    @Builder.Default
    private OffsetDateTime timestamp = OffsetDateTime.now();

    @Column(name = "message_type", nullable = false, length = 30)
    private String messageType;

    @Column(columnDefinition = "text")
    private String content;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;
}
