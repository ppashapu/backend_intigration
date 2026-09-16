package ai.interfaceai.cua.model;

import java.util.List;
import java.util.Map;

/**
 * The structured result contract every replay returns to its caller
 * (Section 3.3). Exactly one of {outputs, businessOutcomeCode, failure}
 * is meaningfully populated, selected by outcomeType.
 */
public record ReplayResult(
        Enums.OutcomeType outcomeType,
        Map<String, Object> outputs,        // populated on SUCCESS
        String businessOutcomeCode,         // populated on BUSINESS_OUTCOME, e.g. "MEMBER_NOT_FOUND"
        String businessOutcomeMessage,
        FailureDetail failure,              // populated on HARD_FAILURE
        List<String> recoveredEvents,       // transient conditions handled mid-run (dialogs, retries)
        String evidenceDir,                 // where logs/screenshots for this run were written
        long durationMs
) {
    public record FailureDetail(
            int failedStepId,
            String stepDescription,
            String expected,
            String observed,
            String message
    ) {}

    public static ReplayResult success(Map<String, Object> outputs, List<String> recovered,
                                        String evidenceDir, long durationMs) {
        return new ReplayResult(Enums.OutcomeType.SUCCESS, outputs, null, null, null,
                recovered, evidenceDir, durationMs);
    }

    public static ReplayResult businessOutcome(String code, String message, List<String> recovered,
                                                String evidenceDir, long durationMs) {
        return new ReplayResult(Enums.OutcomeType.BUSINESS_OUTCOME, Map.of(), code, message, null,
                recovered, evidenceDir, durationMs);
    }

    public static ReplayResult hardFailure(FailureDetail detail, List<String> recovered,
                                            String evidenceDir, long durationMs) {
        return new ReplayResult(Enums.OutcomeType.HARD_FAILURE, Map.of(), null, null, detail,
                recovered, evidenceDir, durationMs);
    }
}
