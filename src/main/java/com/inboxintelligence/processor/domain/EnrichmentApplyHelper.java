package com.inboxintelligence.processor.domain;

import com.inboxintelligence.persistence.model.entity.EmailContent;
import com.inboxintelligence.persistence.model.entity.EmailEnrichment;
import com.inboxintelligence.persistence.service.EmailEnrichmentService;
import com.inboxintelligence.processor.model.NormalisedEmailResponse;
import com.inboxintelligence.processor.outbound.IngesterEnrichmentClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;


@Slf4j
@Component
@RequiredArgsConstructor
public class EnrichmentApplyHelper {

    private final IngesterEnrichmentClient ingesterEnrichmentClient;
    private final EmailEnrichmentService emailEnrichmentService;

    public void apply(String mailboxAddress, EmailContent emailContent, NormalisedEmailResponse result) {

        if (result.importance() == null && result.category() == null) {
            return;
        }

        boolean success = ingesterEnrichmentClient.applyEnrichment(
                mailboxAddress,
                emailContent.getMessageId(),
                result.importance(),
                result.category());

        if (!success) {
            log.error("Ingester enrichment call failed — not persisting importance/category for emailContent [id={}]", emailContent.getId());
            return;
        }

        EmailEnrichment enrichment = emailEnrichmentService.findByEmailContentId(emailContent.getId())
                .orElseThrow(() -> new IllegalStateException("Enrichment row missing for emailContent " + emailContent.getId() + " — normalisation should have saved it first"));

        enrichment.setImportance(result.importance());
        enrichment.setImportanceReason(result.importanceReason());
        enrichment.setCategory(result.category());
        enrichment.setCategoryReason(result.categoryReason());
        emailEnrichmentService.save(enrichment);

        log.debug("Persisted enrichment importance/category for emailContent [id={}, importance={}, category={}]", emailContent.getId(), result.importance(), result.category());
    }
}
