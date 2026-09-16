package ai.interfaceai.cua.browser;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A minimal, real HTTP+DOM "browser": session cookies persist across
 * requests, GET/POST forms are actually submitted over the wire against the
 * live target app, and classic <frameset>/<frame> navigation (heavily used
 * by legacy back-office banking UIs) is modeled explicitly, since it has no
 * client-side JS to drive it.
 *
 * This is the DOM-level automation mechanism chosen for this project's
 * target surface (Section 3.1 explicitly allows DOM-level automation as one
 * of several valid mechanisms). See REPORT.md #1 for the trade-off against a
 * full rendered-browser engine.
 */
public class SimpleBrowser {

    private final Map<String, String> cookies = new LinkedHashMap<>();
    private String currentUrl;
    private Document currentDoc;
    private final Map<String, String> frameUrls = new LinkedHashMap<>();
    private final Map<String, Document> frameDocs = new LinkedHashMap<>();

    /** Top-level document, or null before the first navigate(). */
    public Document topDocument() { return currentDoc; }
    public String topUrl() { return currentUrl; }
    public Document frameDocument(String frameName) { return frameDocs.get(frameName); }
    public String frameUrl(String frameName) { return frameUrls.get(frameName); }

    public void navigate(String url) {
        fetchTop(url, "GET", Map.of());
    }

    private void fetchTop(String url, String method, Map<String, String> data) {
        Document doc = request(url, method, data);
        this.currentUrl = url;
        this.currentDoc = doc;
        this.frameDocs.clear();
        this.frameUrls.clear();
        loadFramesIfAny(doc, url);
    }

    private void loadFramesIfAny(Document doc, String baseUrl) {
        Elements frames = doc.select("frame");
        for (Element f : frames) {
            String name = f.attr("name");
            String src = f.attr("src");
            if (name.isBlank()) continue;
            if (src.isBlank() || src.equals("about:blank")) {
                frameDocs.put(name, Jsoup.parse("<html><body></body></html>"));
                frameUrls.put(name, "about:blank");
                continue;
            }
            String abs = resolve(baseUrl, src);
            Document fDoc = request(abs, "GET", Map.of());
            frameDocs.put(name, fDoc);
            frameUrls.put(name, abs);
        }
    }

    /** Follows an <a href> from the given frame ("" = top), honoring target="_top"/frame-name/self. */
    public void followLink(String fromFrame, Element anchor) {
        String href = anchor.attr("href");
        String baseUrl = fromFrame.isEmpty() ? currentUrl : frameUrls.get(fromFrame);
        String abs = resolve(baseUrl, href);
        String target = anchor.attr("target");
        navigateInto(fromFrame, target, abs, "GET", Map.of());
    }

    /**
     * Submits the <form> containing `submitControl` (a submit button/input),
     * collecting the CURRENT in-memory values of its fields (including any
     * TYPE/SELECT_OPTION mutations already applied to the live DOM), honoring
     * the form's method/action/target.
     */
    public void submitForm(String fromFrame, Element submitControl) {
        Element form = submitControl.closest("form");
        if (form == null) throw new IllegalStateException("Submit control is not inside a <form>");
        String method = form.attr("method").isBlank() ? "GET" : form.attr("method").toUpperCase();
        String action = form.attr("action");
        String baseUrl = fromFrame.isEmpty() ? currentUrl : frameUrls.get(fromFrame);
        String abs = resolve(baseUrl, action.isBlank() ? baseUrl : action);

        Map<String, String> data = new LinkedHashMap<>();
        for (Element field : form.select("input[name], select[name], textarea[name]")) {
            String name = field.attr("name");
            if (field.tagName().equals("select")) {
                Element selected = field.select("option[selected]").first();
                data.put(name, selected != null ? selected.attr("value") : "");
            } else if (field.attr("type").equalsIgnoreCase("submit") || field.attr("type").equalsIgnoreCase("button")) {
                // only include the specific button actually clicked
                if (field == submitControl) data.put(name, field.attr("value"));
            } else {
                data.put(name, field.hasAttr("value") ? field.attr("value") : "");
            }
        }
        String target = form.attr("target");
        navigateInto(fromFrame, target, abs, method, data);
    }

    private void navigateInto(String fromFrame, String target, String url, String method, Map<String, String> data) {
        String effectiveTarget = target.isBlank() ? fromFrame : target;
        if (effectiveTarget.equals("_top") || effectiveTarget.isEmpty() && fromFrame.isEmpty()) {
            fetchTop(url, method, data);
            return;
        }
        if (effectiveTarget.equals("_self") || effectiveTarget.equals(fromFrame)) {
            Document doc = request(url, method, data);
            if (fromFrame.isEmpty()) {
                this.currentUrl = url; this.currentDoc = doc;
            } else {
                frameDocs.put(fromFrame, doc);
                frameUrls.put(fromFrame, url);
            }
            return;
        }
        // named frame (possibly a different one than the source frame)
        Document doc = request(url, method, data);
        frameDocs.put(effectiveTarget, doc);
        frameUrls.put(effectiveTarget, url);
    }

    /** Mutates a field's in-memory value (simulates typing) without any network call. */
    public void setFieldValue(Element field, String value) {
        field.attr("value", value);
    }

    /** Marks one <option> selected within a <select>, clearing others (simulates choosing an option). */
    public void selectOption(Element selectEl, String optionValue) {
        for (Element opt : selectEl.select("option")) {
            if (opt.attr("value").equals(optionValue)) opt.attr("selected", "selected");
            else opt.removeAttr("selected");
        }
    }

    private Document request(String url, String method, Map<String, String> data) {
        try {
            Connection conn = Jsoup.connect(url)
                    .method(method.equalsIgnoreCase("POST") ? Connection.Method.POST : Connection.Method.GET)
                    .ignoreContentType(false)
                    .timeout(10_000)
                    .cookies(cookies);
            if (method.equalsIgnoreCase("POST")) {
                conn = conn.data(data);
            } else if (!data.isEmpty()) {
                conn = conn.data(data);
            }
            Connection.Response resp = conn.execute();
            cookies.putAll(resp.cookies());
            return resp.parse();
        } catch (IOException e) {
            throw new RuntimeException("HTTP request failed for " + url + ": " + e.getMessage(), e);
        }
    }

    private String resolve(String base, String maybeRelative) {
        if (maybeRelative.startsWith("http://") || maybeRelative.startsWith("https://")) return maybeRelative;
        return URI.create(base).resolve(maybeRelative).toString();
    }
}
