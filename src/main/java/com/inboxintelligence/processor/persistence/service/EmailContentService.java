package com.inboxintelligence.processor.persistence.service;

import com.inboxintelligence.processor.model.entity.EmailContent;
import com.inboxintelligence.processor.persistence.repository.EmailContentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class EmailContentService {

    private final EmailContentRepository emailContentRepository;

    public Optional<EmailContent> findById(Long id) {
        return emailContentRepository.findById(id);
    }

    public EmailContent save(EmailContent emailContent) {
        return emailContentRepository.save(emailContent);
    }
}
