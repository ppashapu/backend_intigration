package ai.interfaceai.cua.agent;

import ai.interfaceai.cua.browser.SimpleBrowser;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.*;

public class DomObserver {

    private static final String SELECTOR = "input, button, a[href], select, textarea, [id]";

    public Observation observe(SimpleBrowser browser) {
        List<FrameObservation> frames = new ArrayList<>();
        if (browser.topDocument() != null) {
            frames.add(collect("", browser.topUrl(), browser.topDocument()));
        }
        for (String frameName : frameNames(browser)) {
            Document doc = browser.frameDocument(frameName);
            if (doc == null) continue;
            frames.add(collect(frameName, browser.frameUrl(frameName), doc));
        }
        return new Observation(browser.topUrl(), frames);
    }

    private List<String> frameNames(SimpleBrowser browser) {
        // frame names discovered via the top document's <frame> tags
        List<String> names = new ArrayList<>();
        if (browser.topDocument() == null) return names;
        for (Element f : browser.topDocument().select("frame")) {
            String n = f.attr("name");
            if (!n.isBlank()) names.add(n);
        }
        return names;
    }

    private FrameObservation collect(String frameName, String url, Document doc) {
        Elements els = doc.select(SELECTOR);
        List<ObservedElement> out = new ArrayList<>();
        for (Element e : els) {
            out.add(new ObservedElement(
                    e.tagName(), e.attr("type"), e.attr("name"), e.attr("id"),
                    e.hasAttr("value") ? e.attr("value") : "",
                    e.text().trim(), e.attr("aria-label"), cssPath(e)
            ));
        }
        return new FrameObservation(frameName, url, out);
    }

    /** Structural nth-of-type CSS path, positional-fallback tier. */
    private String cssPath(Element el) {
        LinkedList<String> parts = new LinkedList<>();
        Element cur = el;
        while (cur != null && !cur.tagName().equalsIgnoreCase("body") && !cur.tagName().equalsIgnoreCase("html")) {
            String tag = cur.tagName();
            Elements siblings = cur.parent() != null ? cur.parent().children() : new Elements();
            int nth = 1;
            for (Element sib : siblings) {
                if (sib == cur) break;
                if (sib.tagName().equalsIgnoreCase(tag)) nth++;
            }
            parts.addFirst(tag + ":nth-of-type(" + nth + ")");
            cur = cur.parent();
        }
        return String.join(" > ", parts);
    }

    public record ObservedElement(
            String tag, String type, String name, String id, String value,
            String text, String ariaLabel, String cssPath
    ) {
        public String bestVisibleLabel() {
            if (ariaLabel != null && !ariaLabel.isBlank()) return ariaLabel;
            if (!text.isBlank()) return text;
            if (!value.isBlank()) return value;
            return name;
        }
    }

    public record FrameObservation(String frameName, String frameUrl, List<ObservedElement> elements) {}

    public record Observation(String pageUrl, List<FrameObservation> frames) {
        public String toPromptText() {
            StringBuilder sb = new StringBuilder("Top URL: ").append(pageUrl).append("\n");
            int idx = 0;
            for (FrameObservation f : frames) {
                sb.append("Frame[").append(f.frameName().isEmpty() ? "top" : f.frameName())
                  .append("] ").append(f.frameUrl()).append("\n");
                for (ObservedElement e : f.elements()) {
                    sb.append("  [").append(idx++).append("] <").append(e.tag());
                    if (!e.type().isBlank()) sb.append(" type=").append(e.type());
                    sb.append("> \"").append(e.bestVisibleLabel()).append("\"");
                    if (!e.name().isBlank()) sb.append(" name=").append(e.name());
                    sb.append("\n");
                }
            }
            return sb.toString();
        }

        public Map.Entry<FrameObservation, ObservedElement> byIndex(int index) {
            int i = 0;
            for (FrameObservation f : frames) {
                for (ObservedElement e : f.elements()) {
                    if (i == index) return Map.entry(f, e);
                    i++;
                }
            }
            throw new IndexOutOfBoundsException("No element at index " + index);
        }
    }
}
