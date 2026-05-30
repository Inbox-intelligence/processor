package com.inboxintelligence.processor.model;

import com.inboxintelligence.persistence.model.enums.Category;
import com.inboxintelligence.persistence.model.enums.Importance;

public record NormalisedEmailResponse(
        String summary,
        Importance importance,
        String importanceReason,
        Category category,
        String categoryReason
) {
}
