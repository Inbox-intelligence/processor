package com.inboxintelligence.processor.model.entity;

import com.inboxintelligence.processor.model.ProcessedStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "email_content")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailContent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fk_gmail_mailbox_id", nullable = false)
    private Long gmailMailboxId;

    @Column(name = "message_id", nullable = false, length = 1024)
    private String messageId;

    @Column(name = "thread_id", nullable = false, length = 1024)
    private String threadId;

    @Column(name = "parent_message_id", length = 1024)
    private String parentMessageId;

    @Column(name = "subject", columnDefinition = "TEXT")
    private String subject;

    @Column(name = "from_address", columnDefinition = "TEXT")
    private String fromAddress;

    @Column(name = "to_address", columnDefinition = "TEXT")
    private String toAddress;

    @Column(name = "cc_address", columnDefinition = "TEXT")
    private String ccAddress;

    @Column(name = "raw_message_path", length = 1024)
    private String rawMessagePath;

    @Column(name = "body_content_path", length = 1024)
    private String bodyContentPath;

    @Column(name = "body_html_content_path", length = 1024)
    private String bodyHtmlContentPath;

    @Column(name = "processed_content_path", length = 1024)
    private String processedContentPath;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "processed_status", nullable = false, length = 32)
    private ProcessedStatus processedStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
