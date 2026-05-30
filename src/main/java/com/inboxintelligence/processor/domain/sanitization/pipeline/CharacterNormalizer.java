package com.inboxintelligence.processor.domain.sanitization.pipeline;

import com.inboxintelligence.processor.config.SanitizationStep;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@SanitizationStep(order = 3, description = "Normalize fancy/unicode characters into simple plain text")
public class CharacterNormalizer implements SanitizationStepProcessor {

    private static final Map<String, String> CHARACTER_REPLACEMENTS = buildReplacementMap();
    private static final Pattern UNICODE_SPACES = Pattern.compile("[\u00A0\u2000-\u200A\u202F\u205F\u3000]");
    private static final Pattern INVISIBLE_CHARS = Pattern.compile("[\u200B-\u200F\u00AD\u034F\u061C\uFEFF\u2060-\u2064]");
    private static final Pattern TRAILING_SPACES = Pattern.compile("(?m)[ \\t]+$");
    private static final Pattern PIPE_ONLY_LINES = Pattern.compile("(?m)^[\\s|]+$");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]{2,}");
    private static final Pattern EXCESSIVE_NEWLINES = Pattern.compile("\n{2,}");

    private static Map<String, String> buildReplacementMap() {

        LinkedHashMap<String, String> map = new LinkedHashMap<>();

        map.put("\u2018", "'");
        map.put("\u2019", "'");
        map.put("\u201A", "'");
        map.put("\u2039", "'");
        map.put("\u203A", "'");
        map.put("\u201C", "\"");
        map.put("\u201D", "\"");
        map.put("\u201E", "\"");
        map.put("\u00AB", "\"");
        map.put("\u00BB", "\"");
        map.put("\u2013", "-");
        map.put("\u2014", "-");
        map.put("\u2015", "-");
        map.put("\u2012", "-");
        map.put("\u2010", "-");
        map.put("\u2022", "-");
        map.put("\u2023", "-");
        map.put("\u25E6", "-");
        map.put("\u25AA", "-");
        map.put("\u25CF", "-");
        map.put("\u2192", "->");
        map.put("\u2190", "<-");
        map.put("\u2194", "<->");
        map.put("\u21D2", "=>");
        map.put("\u2026", "...");
        map.put("\u00A9", "(c)");
        map.put("\u00AE", "(R)");
        map.put("\u2122", "(TM)");
        map.put("\u00B0", " degrees");
        map.put("\u00D7", "x");
        map.put("\u00F7", "/");

        return Map.copyOf(map);
    }

    public String process(String content) {

        String result = content.replace("\r\n", "\n").replace("\r", "\n");

        for (Map.Entry<String, String> entry : CHARACTER_REPLACEMENTS.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }

        result = UNICODE_SPACES.matcher(result).replaceAll(" ");
        result = INVISIBLE_CHARS.matcher(result).replaceAll("");
        result = PIPE_ONLY_LINES.matcher(result).replaceAll("");
        result = MULTI_SPACE.matcher(result).replaceAll(" ");
        result = TRAILING_SPACES.matcher(result).replaceAll("");
        result = EXCESSIVE_NEWLINES.matcher(result).replaceAll("\n");

        return result.strip();
    }
}
