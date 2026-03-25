package com.inboxintelligence.processor.cleaning.steps;

import com.inboxintelligence.processor.cleaning.SanitizationStep;
import org.apache.commons.text.StringEscapeUtils;

import java.util.regex.Pattern;

@SanitizationStep(order = 6, description = "Normalize whitespace, unicode chars, and HTML entities")
public class WhitespaceNormalizer {

    private static final Pattern UNICODE_SPACES = Pattern.compile("[\\u00A0\\u2007\\u202F]");
    private static final Pattern INVISIBLE_CHARS = Pattern.compile("[\\u200B\\uFEFF]");
    private static final Pattern EXCESSIVE_NEWLINES = Pattern.compile("\n{3,}");
    private static final Pattern TRAILING_SPACES = Pattern.compile("(?m)[ \\t]+$");

    public String clean(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }

        String result = content.replace("\r\n", "\n");
        result = UNICODE_SPACES.matcher(result).replaceAll(" ");
        result = INVISIBLE_CHARS.matcher(result).replaceAll("");
        result = TRAILING_SPACES.matcher(result).replaceAll("");
        result = EXCESSIVE_NEWLINES.matcher(result).replaceAll("\n\n");
        result = StringEscapeUtils.unescapeHtml4(result);
        return result.strip();
    }
}
