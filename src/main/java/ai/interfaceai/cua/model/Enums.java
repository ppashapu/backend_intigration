package ai.interfaceai.cua.model;

public final class Enums {
    private Enums() {}

    /** How a locator is expressed. Ordered fallback chain lives in LocatorSpec. */
    public enum LocatorKind {
        TEST_ID,           // data-testid — rare in legacy apps but preferred when present
        VISIBLE_TEXT_OR_VALUE, // link/button text, or a submit input's value attribute — survives markup churn
        FIELD_NAME_ATTR,   // CSS attribute selector on the HTML `name` attribute — stable even without labels
        POSITIONAL_CSS     // nth-of-type structural path — last resort, most brittle
    }

    /** Action vocabulary the recorder can emit and the replay engine can execute. */
    public enum ActionType {
        NAVIGATE,
        CLICK,
        TYPE,
        SELECT_OPTION,
        SWITCH_FRAME,     // enter an iframe/frameset by locator
        WAIT_FOR,         // wait for a locator/condition before proceeding
        EXTRACT,          // read text/value from a locator into a named output
        ASSERT_CHECKPOINT // explicit mid-flow assertion (used for confirmation steps)
    }

    /** Per-step and overall risk classification. Drives guardrail handling. */
    public enum RiskLevel {
        SAFE,          // read-only (navigate, extract, search)
        REVERSIBLE,    // mutates state but can be undone/re-done safely (e.g. saving a draft)
        IRREVERSIBLE   // cannot be cleanly undone (submitting a transaction, closing an account)
    }

    /** Lifecycle state of a saved artifact. Unattended replay should gate on APPROVED. */
    public enum ArtifactStatus {
        DRAFT,
        APPROVED,
        DEPRECATED
    }

    /** The three-way result contract replay must report (Section 3.3). */
    public enum OutcomeType {
        SUCCESS,          // goal achieved, checkpoint verified, outputs populated
        BUSINESS_OUTCOME, // a legitimate non-success answer (e.g. "member not found")
        HARD_FAILURE      // replay could not proceed or could not verify success
    }

    /** Why a run needed a human. */
    public enum EscalationReason {
        DISCOVERY_STUCK,          // LLM exhausted steps/couldn't find a path forward
        REPLAY_UNRECOVERABLE,     // replay hit a condition outside its recovery rules
        IRREVERSIBLE_CONFIRMATION,// a risky step requires explicit human sign-off
        GUARDRAIL_BLOCK           // action was outside the allowlist
    }

    public enum InterventionStatus {
        PENDING,
        HUMAN_IN_CONTROL,
        RESOLVED_RESUMED,
        RESOLVED_ABANDONED
    }

    public enum ParamType {
        STRING, NUMBER, BOOLEAN
    }

    public enum CheckpointKind {
        URL_CONTAINS,
        ELEMENT_VISIBLE,
        ELEMENT_TEXT_EQUALS,
        ELEMENT_TEXT_CONTAINS
    }
}
