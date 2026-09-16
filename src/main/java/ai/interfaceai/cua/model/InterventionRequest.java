package ai.interfaceai.cua.model;

import java.util.List;

/**
 * Carries enough context for a human operator to act, per Section 3.6:
 * which capability/goal, the current step, the current state, and why it
 * stopped. `sessionHandle` is the seam that lets the human act on the SAME
 * live browser session the automation was using, not a fresh one.
 */
public record InterventionRequest(
        String id,
        String capabilityIdOrGoal,
        int currentStepIndex,
        Enums.EscalationReason reason,
        String contextSummary,
        String screenshotPath,
        String sessionHandle,        // opaque id the operator console uses to attach to the live page
        String createdAt,            // ISO-8601 instant string
        Enums.InterventionStatus status,
        List<String> humanActionsTaken // appended as the operator acts; replayed into evidence log
) {}
