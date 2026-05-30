package com.inboxintelligence.processor.domain.normalization;

import com.inboxintelligence.persistence.model.enums.Importance;

public record NormalisedEmail(
        String summary,
        Importance importance,
        String reason
) {
}
