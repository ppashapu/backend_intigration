package ai.interfaceai.cua.replay;

import ai.interfaceai.cua.browser.SimpleBrowser;
import ai.interfaceai.cua.model.LocatorSpec;
import ai.interfaceai.cua.model.LocatorSpec.LocatorStrategy;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Resolves a LocatorSpec's ordered strategy list against the live browser
 * state, trying each strategy in turn. Reports WHICH strategy resolved --
 * that fact is logged by the caller as evidence, since "fallback strategy N
 * used instead of strategy 0" is exactly the signal that flags UI drift
 * before it becomes an outright failure.
 */
public class DomActuator {

    public record Resolution(Element element, String frameName, int strategyIndexUsed) {}

    public Resolution resolve(SimpleBrowser browser, LocatorSpec spec) {
        String frameName = spec.frameChain().isEmpty() ? "" : spec.frameChain().get(0);
        Document doc = frameName.isEmpty() ? browser.topDocument() : browser.frameDocument(frameName);
        if (doc == null) {
            throw new LocatorResolutionException("Frame '" + frameName + "' is not currently loaded", null);
        }
        RuntimeException last = null;
        for (int i = 0; i < spec.strategies().size(); i++) {
            LocatorStrategy s = spec.strategies().get(i);
            try {
                Elements found = doc.select(s.cssSelector());
                if (!found.isEmpty()) {
                    return new Resolution(found.first(), frameName, i);
                }
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw new LocatorResolutionException(
                "No locator strategy resolved (" + spec.strategies().size() + " tried) in frame '" + frameName + "'", last);
    }

    public void click(SimpleBrowser browser, Resolution res) {
        Element el = res.element();
        if (el.tagName().equalsIgnoreCase("a")) {
            browser.followLink(res.frameName(), el);
        } else {
            // button / input[type=submit|button]
            browser.submitForm(res.frameName(), el);
        }
    }

    public void type(SimpleBrowser browser, Resolution res, String value) {
        browser.setFieldValue(res.element(), value);
    }

    public void selectOption(SimpleBrowser browser, Resolution res, String value) {
        browser.selectOption(res.element(), value);
    }

    public String extractText(Resolution res) {
        Element el = res.element();
        if (el.hasAttr("value") && (el.tagName().equalsIgnoreCase("input"))) {
            return el.attr("value");
        }
        return el.text().trim();
    }

    public static class LocatorResolutionException extends RuntimeException {
        public LocatorResolutionException(String msg, Throwable cause) { super(msg, cause); }
    }
}
