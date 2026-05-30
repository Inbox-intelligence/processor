package com.inboxintelligence.processor.domain.normalization;

import org.springframework.stereotype.Component;


@Component
public class NormalisationBias {

    static final String NO_BIAS = "";

    static final String JOB_HUNT_BIAS = """
            Bias: The recipient is job-hunting. If the email is a real recruiter message,
            interview invitation, job offer or alert addressed to the recipient by name,
            bump importance up one tier (LOW→MEDIUM, MEDIUM→HIGH). Notifications and 
            marketing messages disguised as job-hunting should not qualify for this.
            """;

    // Switch this when context changes; NO_BIAS to disable.
    private static final String ACTIVE_BIAS = JOB_HUNT_BIAS;

    public String text() {
        return ACTIVE_BIAS;
    }

    public boolean isActive() {
        return !ACTIVE_BIAS.isBlank();
    }
}
