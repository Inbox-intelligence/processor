package com.inboxintelligence.processor.domain.sanitization.step;

import com.inboxintelligence.processor.cleaning.SanitizationStep;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.Set;

@SanitizationStep(order = 1, description = "Convert HTML to plain text using Jsoup")
public class HtmlToTxtConverter {

    private static final Set<String> BLOCK_TAGS = Set.of("p", "div", "tr", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "hr", "table", "thead", "tbody", "ul", "ol", "br");
    private static final Set<String> TABLE_TAGS = Set.of("td", "th");
    private static final Set<String> HTML_HINTS = Set.of("<html", "<body", "<div", "<p>", "<br");

    public String process(String content) {

        if (content == null || content.isBlank() || HTML_HINTS.stream().noneMatch(content::contains)) {
            return content;
        }

        Document document = Jsoup.parse(content);

        document.select("script, style, head, meta, link").remove();
        document.select("img[width=1], img[height=1], img[width=0], img[height=0]").remove();
        document.select("img[src^=cid:]").remove();
        document.select("[style*=display:none], [style*=display: none]").remove();
        document.select("[style*=visibility:hidden], [style*=visibility: hidden]").remove();

        return extractText(document);
    }

    private String extractText(Document document) {

        StringBuilder sb = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode textNode) {
                    sb.append(textNode.text());
                } else if (node instanceof Element el) {
                    if (BLOCK_TAGS.contains(el.normalName())) {
                        sb.append('\n');
                    } else if ("li".equals(el.normalName())) {
                        sb.append("\n- ");
                    } else if (TABLE_TAGS.contains(el.normalName())) {
                        sb.append(" | ");
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element el) {
                    if (BLOCK_TAGS.contains(el.normalName())) {
                        sb.append("\n");
                    } else if ("a".equals(el.normalName()) && el.hasAttr("href")) {
                        String href = el.attr("href");
                        if (!StringUtils.equals(el.text(), el.attr("href"))) {
                            sb.append(" (").append(href).append(")");
                        }
                    }
                }
            }
        }, document);

        return sb.toString();
    }
}
