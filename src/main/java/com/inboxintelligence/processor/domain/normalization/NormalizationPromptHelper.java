package com.inboxintelligence.processor.domain.normalization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inboxintelligence.persistence.model.enums.Category;
import com.inboxintelligence.persistence.model.enums.Importance;
import com.inboxintelligence.processor.domain.model.factory.ModelProvider;
import com.inboxintelligence.processor.domain.model.factory.ModelProviderFactory;
import com.inboxintelligence.processor.model.NormalisedEmailResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Builds the prompt, calls the LLM, parses the JSON into NormalisedEmail.
@Slf4j
@Component
public class NormalizationPromptHelper {

    private static final Pattern JSON_BLOCK = Pattern.compile("\\{.*}", Pattern.DOTALL);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final int MAX_NORMALIZED_CHARS = 5000;
    private static final int MAX_PROMPT_INPUT_CHARS = 6000;
    private static final int MAX_REASON_CHARS = 500;
    private static final int LOG_PREVIEW_CHARS = 200;

    private static final String SYSTEM_PROMPT = """
            ## Task
            Analyse the email and output ONE JSON object. DO NOT add any preamble,
            commentary, or markdown fences. DO NOT output anything outside the JSON.

            ## Output Schema
            (Write the reasons FIRST so you justify the rating before assigning it.)
            {
              "summary": string,
              "importance_reason": string,
              "importance": "LOW" | "MEDIUM" | "HIGH",
              "category_reason": string,
              "category": "PRIMARY" | "SOCIAL" | "PROMOTIONS" | "UPDATES" | "FORUMS"
            }

            ## Summary Rules
            - Up to %d chars, plain text, facts only.
            - Preserve OTPs, codes, amounts, account numbers, dates, IDs verbatim.

            ## Importance Rules — default is LOW
            The user has already received the email. Do NOT invent urgency from
            words like "verify", "alert", "action", "confirm". Pick the LOWEST
            level whose criteria are met.

            - LOW (DEFAULT, ~80%% of emails): The email is informational. No human
              reply is required and nothing bad happens if the user ignores it.
              ALL of these are LOW:
                * OTPs and verification codes (user already triggered them)
                * Bank debit/credit/UPI alerts about transactions that ALREADY happened
                * Order shipped / delivered / placed confirmations
                * Marketing, newsletters, deals, loyalty rewards, fee-change notices
                * Job recommendations, "new jobs matching your profile"
                * Social-network notifications and connection requests
            - MEDIUM (rare): A real human wrote directly to the user, OR an
              upcoming-event booking confirmation (flight, train, hotel, interview)
              the user must remember, OR an application/status change the user is
              waiting on.
            - HIGH (very rare): The email EXPLICITLY asks the user to do something
              within hours/days AND the user loses money, access, or an opportunity
              if they ignore it. Example: "Reset password — link expires in 1 hour",
              "Interview slot — confirm by tomorrow 6pm or it will be released".
              Past-tense transaction alerts are NEVER HIGH.

            ## User Context — Job Hunt Mode (PROMOTE these to HIGH)
            The user is actively job hunting. Override the LOW default for:
            - Named recruiter outreach addressed to the user personally:
              LinkedIn InMail with a named sender, personalised recruiter emails,
              interview scheduling, coding-test links, offer correspondence,
              "we'd like to discuss your application", "next round", reschedule.
            - Application-status updates from a company the user applied to
              (e.g. "Your application for <role> has been received / is under
              review / has progressed", "Thank you for applying" from the actual
              employer's ATS).

            Generic auto-digest job listings stay LOW PROMOTIONS:
            "X is hiring - be an early applicant", "Recruiters are searching",
            "Top roles for you", "New jobs matching your profile",
            "Jobs for you from <list of companies>".

            ## Category Rules — pick exactly one
            - SOCIAL:   LinkedIn / Twitter / Instagram / Facebook / Reddit / dating
                        apps / media-sharing sites — including their connection
                        requests, message digests, and reactions.
            - PROMOTIONS: marketing, deals, sales, newsletters, job recommendations
                        from job portals (Naukri, LinkedIn Job Alerts, Indeed).
            - UPDATES:  receipts, bills, statements, shipping/delivery,
                        booking confirmations, OTPs, bank alerts, account or
                        system notifications.
            - FORUMS:   mailing lists, discussion groups, group threads.
            - PRIMARY:  a real human writing directly to the user (no template,
                        no automation).

            Tie-breaker if two fit: SOCIAL > PROMOTIONS > UPDATES > FORUMS > PRIMARY.

            ## Override Rules (these beat every other rule above)
            - ALL bank credit / debit / UPI / card transaction alerts → LOW UPDATES.
              No exceptions. Applies even when wording suggests action
              ("potential unauthorized transaction", "block UPI if not initiated",
              "user must remember"), for upcoming e-mandate charges, for
              large amounts, or for any "transaction confirmation" notification.
            - ALL bank account statements and credit-card statements → LOW UPDATES.
            - ALL OTPs and verification codes → LOW UPDATES.

            ## Reason Rules
            - importance_reason: ONE short sentence justifying the chosen importance.
            - category_reason:   ONE short sentence justifying the chosen category.

            ## Examples
            <Example 1>
            From: GitHub <noreply@github.com>
            Subject: Sudo email verification code
            Body: Your verification code is 07019302. Valid for 15 minutes.
            Output:
            {"summary":"GitHub sudo verification code 07019302, valid 15 minutes.","importance_reason":"OTP the user just triggered; informational.","importance":"LOW","category_reason":"Account/system verification code.","category":"UPDATES"}
            </Example 1>

            <Example 2>
            From: Axis Bank Alerts <alerts@axis.bank.in>
            Subject: INR 234.00 was debited from your A/c no. XX4929
            Body: UPI to ESWAR FOODS. Block UPI if not initiated.
            Output:
            {"summary":"Axis Bank: INR 234.00 debited from A/c XX4929 via UPI to ESWAR FOODS.","importance_reason":"Routine bank debit alert for a transaction that already happened.","importance":"LOW","category_reason":"Bank account alert.","category":"UPDATES"}
            </Example 2>

            <Example 3>
            From: Manojit Parui <invitations@linkedin.com>
            Subject: I want to connect
            Body: LinkedIn connection invitation.
            Output:
            {"summary":"LinkedIn connection request from Manojit Parui.","importance_reason":"Social network notification, no action required.","importance":"LOW","category_reason":"LinkedIn invitation.","category":"SOCIAL"}
            </Example 3>

            <Example 4>
            From: LinkedIn Job Alerts <jobalerts-noreply@linkedin.com>
            Subject: Senior Software Engineer at Red Hat
            Body: New job matching your profile.
            Output:
            {"summary":"LinkedIn job alert: Senior Software Engineer at Red Hat.","importance_reason":"Generic job recommendation from a job portal.","importance":"LOW","category_reason":"Job marketing from LinkedIn.","category":"PROMOTIONS"}
            </Example 4>

            <Example 5>
            From: IRCTC <ticketadmin@irctc.co.in>
            Subject: Booking Confirmation, Train 22691, 14-Jun-2026
            Body: PNR 4550408763, SBC to SC, Rajdhani Express.
            Output:
            {"summary":"IRCTC train booking confirmed: 22691 SBC to SC on 14-Jun-2026, PNR 4550408763.","importance_reason":"Booking confirmation for an upcoming travel date the user must remember.","importance":"MEDIUM","category_reason":"Travel booking confirmation.","category":"UPDATES"}
            </Example 5>

            <Example 6>
            From: ACI Talent and Acquisitions <no_reply@aciworldwide.com>
            Subject: Your recent job application for 18272 - Sr Software Engineer
            Body: Your application has been received and is under review by our team.
            Output:
            {"summary":"ACI Worldwide: application received for role 18272 Sr Software Engineer; under review.","importance_reason":"Job-hunt mode: application status update from a company the user applied to.","importance":"HIGH","category_reason":"Job application status from a company.","category":"PROMOTIONS"}
            </Example 6>

            <Example 7>
            From: Pranoti Zode <inmail-hit-reply@linkedin.com>
            Subject: EPAM Hiring for Java Full stack (Java and React) Developers
            Body: Hi Ankit, I'd love to discuss a Java Full stack opportunity at EPAM with you.
            Output:
            {"summary":"LinkedIn InMail from Pranoti Zode at EPAM about Java Full stack opening.","importance_reason":"Job-hunt mode: named recruiter directly reaching out about a role.","importance":"HIGH","category_reason":"Job opportunity via LinkedIn InMail.","category":"PROMOTIONS"}
            </Example 7>

            <Example 8>
            From: Axis Bank Alerts <alerts@axis.bank.in>
            Subject: INR 47987.10 was debited from your A/c no. XX4929
            Body: Debit alert for a large transaction.
            Output:
            {"summary":"Axis Bank: INR 47987.10 debited from A/c XX4929.","importance_reason":"Bank transaction alert; per Override Rules always LOW regardless of amount.","importance":"LOW","category_reason":"Bank account alert.","category":"UPDATES"}
            </Example 8>

            ## Hard Constraints
            - You MUST default to LOW unless the MEDIUM or HIGH criteria are met.
            - You MUST output exactly one JSON object and nothing else.
            - DO NOT wrap the JSON in markdown fences.
            - For empty or garbage content, respond with no text and no JSON.
            """.formatted(MAX_NORMALIZED_CHARS);

    private final ModelProviderFactory modelProviderFactory;
    private final ObjectMapper objectMapper;

    public NormalizationPromptHelper(ModelProviderFactory modelProviderFactory, ObjectMapper baseMapper) {
        this.modelProviderFactory = modelProviderFactory;
        this.objectMapper = baseMapper.copy()
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true)
                .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true);
    }

    public NormalisedEmailResponse normalise(String fromAddress, String subject, String sanitizedBody) {

        String userPrompt = new StringBuilder()
                .append("From: ").append(StringUtils.left(fromAddress, MAX_PROMPT_INPUT_CHARS)).append('\n')
                .append("Subject: ").append(StringUtils.left(subject, MAX_PROMPT_INPUT_CHARS)).append('\n')
                .append("Body: ").append(StringUtils.left(sanitizedBody, MAX_PROMPT_INPUT_CHARS)).append('\n')
                .toString();

        ModelProvider provider = modelProviderFactory.getLlmProvider();
        String raw = provider.invokeLlm(SYSTEM_PROMPT, userPrompt);
        String response = raw == null ? "" : raw.trim();

        if (response.isEmpty()) {
            log.warn("LLM produced no content [provider={}]", provider.getClass().getSimpleName());
            return null;
        }
        return parse(response, provider);
    }

    private NormalisedEmailResponse parse(String response, ModelProvider provider) {

        String providerName = provider.getClass().getSimpleName();
        Matcher m = JSON_BLOCK.matcher(response);

        if (!m.find()) {
            log.warn("LLM response is not JSON [provider={}, response=\"{}\"]", providerName, StringUtils.left(response, LOG_PREVIEW_CHARS));
            return null;
        }

        try {

            Map<String, Object> r = objectMapper.readValue(m.group(), MAP_TYPE);
            String rawSummary = (String) r.get("summary");

            if (rawSummary == null || rawSummary.isBlank()) {
                log.warn("LLM JSON missing 'summary' [provider={}, response=\"{}\"]", providerName, StringUtils.left(response, LOG_PREVIEW_CHARS));
                return null;
            }

            String summary = StringUtils.left(rawSummary, MAX_NORMALIZED_CHARS);
            Importance importance = Objects.requireNonNullElse(objectMapper.convertValue(r.get("importance"), Importance.class), Importance.LOW);
            String importanceReason = StringUtils.left((String) r.get("importance_reason"), MAX_REASON_CHARS);
            Category category = Objects.requireNonNullElse(objectMapper.convertValue(r.get("category"), Category.class), Category.PRIMARY);
            String categoryReason = StringUtils.left((String) r.get("category_reason"), MAX_REASON_CHARS);

            return new NormalisedEmailResponse(summary, importance, importanceReason, category, categoryReason);
        } catch (Exception e) {
            log.error("Failed to parse LLM JSON [provider={}, error={}, response=\"{}\"]", providerName, e.getMessage(), StringUtils.left(response, LOG_PREVIEW_CHARS), e);
            return null;
        }
    }
}
