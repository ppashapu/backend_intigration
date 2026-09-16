package ai.interfaceai.cua.model;

import java.util.List;

/**
 * How replay finds a control on screen.
 *
 * Design rationale: legacy bank UIs rarely have test IDs, so a single
 * selector is brittle. Every locator is an ORDERED list of strategies tried
 * in sequence -- strongest signal first (visible text/value, which survives
 * markup/CSS churn because it reflects what a human operator actually reads)
 * down to a positional CSS path as a last resort. Replay records which
 * strategy actually resolved, so drift shows up as "fallback strategy used"
 * in evidence before it becomes an outright failure.
 *
 * frameChain names the frame(s) (by HTML `name` attribute) a control lives
 * inside, top-down, for frameset-based legacy UIs. Empty means top-level
 * document. (Scoped to a shallow chain for this project's target; see
 * REPORT.md #4 for how this generalizes to arbitrarily nested surfaces.)
 */
public record LocatorSpec(
        List<LocatorStrategy> strategies,
        List<String> frameChain
) {
    public LocatorSpec {
        if (strategies == null || strategies.isEmpty()) {
            throw new IllegalArgumentException("LocatorSpec requires at least one strategy");
        }
        if (frameChain == null) frameChain = List.of();
    }

    public record LocatorStrategy(
            Enums.LocatorKind kind,
            String cssSelector,   // a ready-to-run jsoup/CSS selector
            String note           // human-readable explanation, for the artifact reviewer
    ) {}
}
