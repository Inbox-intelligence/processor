package com.inboxintelligence.processor.domain.sanitization;

import com.inboxintelligence.processor.config.SanitizationStep;

import java.util.regex.Pattern;

@SanitizationStep(order = 2, description = "Remove inline cid: and image references")
public class InlineReferenceRemover {

    private static final Pattern CID_BRACKET = Pattern.compile("\\[cid:[^\\]]*]");
    private static final Pattern IMAGE_BRACKET = Pattern.compile("\\[image:[^\\]]*]");
    private static final Pattern CID_BARE = Pattern.compile("cid:\\S+");

    public String process(String content) {

        String result = CID_BRACKET.matcher(content).replaceAll("");
        result = IMAGE_BRACKET.matcher(result).replaceAll("");
        result = CID_BARE.matcher(result).replaceAll("");

        return result;
    }
}
