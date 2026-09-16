package ai.interfaceai.cua.model;

/**
 * One deterministic action in a recorded flow.
 *
 * `value` supports {{paramName}} templating, substituted from the caller's
 * input params at replay time — this is what makes a recorded flow reusable
 * (e.g. {{memberId}}) rather than a one-off transcript with hardcoded values.
 */
public record Step(
        int stepId,
        Enums.ActionType action,
        String description,          // human-readable, for the artifact reviewer
        LocatorSpec target,          // null for NAVIGATE / plain WAIT_FOR-by-time
        String value,                // literal or {{param}} template; null where n/a
        String extractAs,            // output variable name, only for EXTRACT
        Enums.RiskLevel riskLevel,
        int timeoutMs                // max wait for this step's target to resolve
) {
    public Step {
        if (timeoutMs <= 0) timeoutMs = 8000;
        if (riskLevel == null) riskLevel = Enums.RiskLevel.SAFE;
    }
}
