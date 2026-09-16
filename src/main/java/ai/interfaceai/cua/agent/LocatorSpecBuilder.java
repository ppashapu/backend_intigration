package ai.interfaceai.cua.agent;

import ai.interfaceai.cua.agent.DomObserver.FrameObservation;
import ai.interfaceai.cua.agent.DomObserver.ObservedElement;
import ai.interfaceai.cua.model.Enums;
import ai.interfaceai.cua.model.LocatorSpec;
import ai.interfaceai.cua.model.LocatorSpec.LocatorStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Builds the locator fallback chain at record time -- the project's central
 * robustness bet:
 *
 *  - Links/buttons/submit-inputs carry real visible text/value, so a
 *    text/value-based CSS selector (e.g. a:contains(View)) goes first: it
 *    survives markup/CSS restructuring because it reflects what a human
 *    operator actually reads, not where the element sits in the tree.
 *  - Legacy table-based form fields (this project's mock target, and most
 *    real core-banking screens) carry no visible label at all, but DO
 *    usually carry a stable HTML `name` attribute -- so for those, a
 *    name-attribute CSS selector goes first. Field `name` attributes survive
 *    a legacy app's incidental layout changes far better than DOM position.
 *  - A positional nth-of-type CSS path is always appended last, understood
 *    and documented as the most brittle strategy -- useful mainly to
 *    disambiguate ties, not as a primary signal.
 */
public class LocatorSpecBuilder {

    private static final Set<String> TEXT_BEARING_TAGS = Set.of("a", "button");

    public static LocatorSpec build(FrameObservation frame, ObservedElement el) {
        List<LocatorStrategy> strategies = new ArrayList<>();

        if (el.id() != null && !el.id().isBlank()) {
            strategies.add(new LocatorStrategy(Enums.LocatorKind.TEST_ID, "#" + escape(el.id()),
                    "matches on a stable element id"));
        }

        boolean hasVisibleTextOrValue =
                (TEXT_BEARING_TAGS.contains(el.tag()) && !el.text().isBlank())
                || (el.tag().equals("input") && Set.of("submit", "button").contains(el.type()) && !el.value().isBlank());

        if (hasVisibleTextOrValue) {
            String label = !el.text().isBlank() ? el.text() : el.value();
            String selector = el.tag().equals("input")
                    ? "input[value=\"" + escape(label) + "\"]"
                    : el.tag() + ":contains(" + label + ")";
            strategies.add(new LocatorStrategy(Enums.LocatorKind.VISIBLE_TEXT_OR_VALUE, selector,
                    "matches on visible text/value \"" + label + "\""));
        }

        if (el.name() != null && !el.name().isBlank()) {
            strategies.add(new LocatorStrategy(Enums.LocatorKind.FIELD_NAME_ATTR,
                    el.tag() + "[name=\"" + escape(el.name()) + "\"]",
                    "matches on the HTML name attribute, independent of DOM position"));
        }

        strategies.add(new LocatorStrategy(Enums.LocatorKind.POSITIONAL_CSS, el.cssPath(),
                "structural fallback; brittle to markup reordering"));

        List<String> frameChain = frame.frameName().isEmpty() ? List.of() : List.of(frame.frameName());
        return new LocatorSpec(strategies, frameChain);
    }

    private static String escape(String s) {
        return s.replace("\"", "\\\"");
    }
}
