package com.inboxintelligence.processor.persistence.repository;

import com.inboxintelligence.processor.model.entity.EmailContent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailContentRepository extends JpaRepository<EmailContent, Long> {
}
