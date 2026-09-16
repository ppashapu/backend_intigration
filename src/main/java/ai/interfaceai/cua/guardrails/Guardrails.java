package ai.interfaceai.cua.guardrails;

import ai.interfaceai.cua.model.Enums;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Section 3.4. Two independent checks, both enforced at the point of action
 * (agent loop) AND at replay time — never trust that discovery-time approval
 * still holds:
 *
 *  1. Allowlist: is this URL/route + action type even permitted?
 *  2. Risk policy: if permitted, is it SAFE/REVERSIBLE (proceed) or
 *     IRREVERSIBLE (block unless explicitly confirmed by a human — see
 *     EscalationReason.IRREVERSIBLE_CONFIRMATION)?
 *
 * Kept intentionally simple (no infra) per the brief: "designing your core
 * abstractions so they could scale is valuable; prematurely building that
 * infrastructure is not."
 */
public class Guardrails {

    private final List<Pattern> allowedUrlPatterns;
    private final Set<Enums.ActionType> allowedActions;
    private final boolean blockIrreversibleWithoutConfirmation;

    public Guardrails(List<Pattern> allowedUrlPatterns,
                       Set<Enums.ActionType> allowedActions,
                       boolean blockIrreversibleWithoutConfirmation) {
        this.allowedUrlPatterns = allowedUrlPatterns;
        this.allowedActions = allowedActions;
        this.blockIrreversibleWithoutConfirmation = blockIrreversibleWithoutConfirmation;
    }

    /** Default policy for the mock bank target used in this project's demo. */
    public static Guardrails defaultForLocalMockBank(String baseUrl) {
        return new Guardrails(
                List.of(Pattern.compile(Pattern.quote(baseUrl) + ".*")),
                Set.of(Enums.ActionType.values()), // all action types permitted; risk is gated separately
                true
        );
    }

    public boolean isUrlAllowed(String url) {
        return allowedUrlPatterns.stream().anyMatch(p -> p.matcher(url).matches());
    }

    public boolean isActionTypeAllowed(Enums.ActionType type) {
        return allowedActions.contains(type);
    }

    /**
     * Irreversible steps are never auto-confirmed by the LLM during discovery
     * or silently replayed unattended. During discovery, the agent must pause
     * and log an explicit "would perform irreversible action" decision point
     * (captured as a human-reviewable moment in the transcript, not truly
     * blocking the recording run). During replay, an irreversible step
     * requires the capability to be APPROVED (see ArtifactStatus) — DRAFT
     * capabilities containing irreversible steps cannot run unattended.
     */
    public boolean requiresHumanConfirmation(Enums.RiskLevel risk) {
        return blockIrreversibleWithoutConfirmation && risk == Enums.RiskLevel.IRREVERSIBLE;
    }
}
