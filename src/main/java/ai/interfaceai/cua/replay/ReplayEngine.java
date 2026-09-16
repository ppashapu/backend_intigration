package ai.interfaceai.cua.replay;

import ai.interfaceai.cua.browser.SimpleBrowser;
import ai.interfaceai.cua.evidence.EvidenceLogger;
import ai.interfaceai.cua.guardrails.Guardrails;
import ai.interfaceai.cua.model.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * The production execution path (Section 3.3): replays a saved Capability
 * with no model in the decision loop. Every action is exactly the recorded
 * action; the only variability is (a) which locator fallback strategy
 * resolves, and (b) which of the three OutcomeType branches this run hits.
 *
 * Error taxonomy, applied at every step:
 *   1. Known BUSINESS OUTCOME markers on the page (e.g. "no records found",
 *      a validation message) are checked FIRST -- legitimate answers, not
 *      failures, and short-circuit the run immediately.
 *   2. Known RECOVERABLE markers (a transient "system busy" interstitial)
 *      trigger a bounded wait-and-retry of the SAME step, logged to
 *      recoveredEvents -- invisible to the caller if recovery succeeds.
 *   3. Anything else that stops the run (locator never resolves, checkpoint
 *      not met) is a HARD_FAILURE with enough detail to debug: which step,
 *      what was expected, what was actually observed.
 *
 * The condition detector below is a small, named list rather than generic
 * text-sniffing, because production markers should be sourced from the
 * target app's real vocabulary per vendor product (see REPORT.md #4) --
 * this is the seam where that per-app knowledge would plug in.
 */
public class ReplayEngine {

    private final DomActuator actuator = new DomActuator();
    private final Guardrails guardrails;
    private final EvidenceLogger evidence;
    private static final int MAX_TRANSIENT_RETRIES = 2;

    public ReplayEngine(Guardrails guardrails, EvidenceLogger evidence) {
        this.guardrails = guardrails;
        this.evidence = evidence;
    }

    public ReplayResult replay(Capability cap, Map<String, String> inputs) {
        long start = System.currentTimeMillis();
        List<String> recovered = new ArrayList<>();
        Map<String, Object> extracted = new LinkedHashMap<>();

        for (ParamSpec p : cap.inputParams()) {
            if (p.required() && !inputs.containsKey(p.name())) {
                return fail(cap, -1, "required input present", "missing: " + p.name(),
                        "Missing required input parameter '" + p.name() + "'", recovered, start);
            }
        }

        SimpleBrowser browser = new SimpleBrowser();

        for (Step step : cap.steps()) {
            evidence.event("replay_step_start", Map.of("stepId", step.stepId(), "action", step.action().name(),
                    "description", step.description()));

            if (!guardrails.isActionTypeAllowed(step.action())) {
                return fail(cap, step.stepId(), "action type allowed", "blocked by guardrail",
                        "Action type " + step.action() + " is not in the allowlist", recovered, start);
            }
            if (guardrails.requiresHumanConfirmation(step.riskLevel()) && cap.status() != Enums.ArtifactStatus.APPROVED) {
                return fail(cap, step.stepId(), "capability APPROVED for irreversible step",
                        "status=" + cap.status(),
                        "Irreversible step requires an APPROVED capability; this one is " + cap.status(),
                        recovered, start);
            }

            ReplayResult shortCircuit = executeStepWithRecovery(cap, step, inputs, browser, recovered, extracted, start);
            if (shortCircuit != null) return shortCircuit;
        }

        ReplayResult checkpointFailure = verifyCheckpoint(cap, browser, recovered, start);
        if (checkpointFailure != null) return checkpointFailure;

        evidence.event("replay_success", Map.of("outputs", extracted));
        return ReplayResult.success(extracted, recovered, evidence.dir().toString(), System.currentTimeMillis() - start);
    }

    private ReplayResult executeStepWithRecovery(Capability cap, Step step, Map<String, String> inputs,
                                                   SimpleBrowser browser, List<String> recovered,
                                                   Map<String, Object> extracted, long start) {
        int attempts = 0;
        while (true) {
            try {
                runAction(cap, step, inputs, browser, extracted);
            } catch (RuntimeException e) {
                // The action itself failed (e.g. a locator never resolved). Check whether that's
                // because the CURRENT page already shows a known condition before calling it a hard failure.
                Optional<KnownCondition> known = detectKnownCondition(browser);
                if (known.isPresent() && known.get().type() == ConditionType.BUSINESS_OUTCOME) {
                    evidence.event("business_outcome", Map.of("code", known.get().code()));
                    evidence.domSnapshot(currentHtml(browser), "business-outcome");
                    return ReplayResult.businessOutcome(known.get().code(), known.get().message(),
                            recovered, evidence.dir().toString(), System.currentTimeMillis() - start);
                }
                evidence.event("hard_failure_at_step", Map.of("stepId", step.stepId(), "message", String.valueOf(e.getMessage())));
                evidence.domSnapshot(currentHtml(browser), "hard-failure");
                return fail(cap, step.stepId(), step.description(), safeBodySnippet(browser), e.getMessage(), recovered, start);
            }

            // Action executed without throwing. A transient/business condition can still be the
            // RESULT of this action (e.g. a search submit whose response page reads "system busy"),
            // so check the resulting page immediately -- this is where such conditions actually surface.
            Optional<KnownCondition> known = detectKnownCondition(browser);
            if (known.isEmpty()) return null; // clean success, continue to next step

            if (known.get().type() == ConditionType.BUSINESS_OUTCOME) {
                evidence.event("business_outcome", Map.of("code", known.get().code()));
                evidence.domSnapshot(currentHtml(browser), "business-outcome");
                return ReplayResult.businessOutcome(known.get().code(), known.get().message(),
                        recovered, evidence.dir().toString(), System.currentTimeMillis() - start);
            }
            // RECOVERABLE
            if (attempts >= MAX_TRANSIENT_RETRIES) {
                evidence.event("hard_failure_at_step", Map.of("stepId", step.stepId(),
                        "message", "recoverable condition '" + known.get().code() + "' persisted past max retries"));
                evidence.domSnapshot(currentHtml(browser), "hard-failure");
                return fail(cap, step.stepId(), step.description(), known.get().code(),
                        "Recoverable condition '" + known.get().code() + "' did not clear after "
                                + MAX_TRANSIENT_RETRIES + " retries", recovered, start);
            }
            attempts++;
            recovered.add("step " + step.stepId() + ": recovered from '" + known.get().code() + "' (attempt " + attempts + ")");
            evidence.event("recoverable_condition", Map.of("code", known.get().code(), "attempt", attempts));
            // Re-run the SAME step (e.g. re-submit the search) — for a transient interstitial this is
            // exactly what a human operator would do: retry the action that produced the busy response.
        }
    }

    private void runAction(Capability cap, Step step, Map<String, String> inputs, SimpleBrowser browser, Map<String, Object> extracted) {
        String value = substitute(step.value(), inputs);
        switch (step.action()) {
            case NAVIGATE -> browser.navigate(resolveAgainstBase(cap, value));
            case CLICK -> {
                var res = actuator.resolve(browser, step.target());
                logFallback(step, res);
                actuator.click(browser, res);
            }
            case TYPE -> {
                var res = actuator.resolve(browser, step.target());
                logFallback(step, res);
                actuator.type(browser, res, value);
            }
            case SELECT_OPTION -> {
                var res = actuator.resolve(browser, step.target());
                logFallback(step, res);
                actuator.selectOption(browser, res, value);
            }
            case EXTRACT -> {
                var res = actuator.resolve(browser, step.target());
                logFallback(step, res);
                extracted.put(step.extractAs(), actuator.extractText(res));
            }
            case WAIT_FOR -> actuator.resolve(browser, step.target());
            case SWITCH_FRAME, ASSERT_CHECKPOINT -> { /* handled implicitly via LocatorSpec.frameChain / checkpoint */ }
        }
    }

    private void logFallback(Step step, DomActuator.Resolution res) {
        if (res.strategyIndexUsed() > 0) {
            evidence.event("locator_fallback_used", Map.of(
                    "stepId", step.stepId(), "strategyIndex", res.strategyIndexUsed(),
                    "note", "primary locator strategy did not resolve; fallback " + res.strategyIndexUsed() + " used"));
        }
    }

    private ReplayResult verifyCheckpoint(Capability cap, SimpleBrowser browser, List<String> recovered, long start) {
        Checkpoint cp = cap.checkpoint();
        boolean ok = switch (cp.kind()) {
            case URL_CONTAINS -> browser.topUrl() != null && browser.topUrl().contains(cp.expectedValue());
            case ELEMENT_VISIBLE -> {
                try { yield actuator.resolve(browser, cp.target()) != null; } catch (Exception e) { yield false; }
            }
            case ELEMENT_TEXT_EQUALS -> {
                try { yield actuator.extractText(actuator.resolve(browser, cp.target())).trim().equals(cp.expectedValue()); }
                catch (Exception e) { yield false; }
            }
            case ELEMENT_TEXT_CONTAINS -> {
                try { yield actuator.extractText(actuator.resolve(browser, cp.target())).contains(cp.expectedValue()); }
                catch (Exception e) { yield false; }
            }
        };
        if (!ok) {
            evidence.domSnapshot(currentHtml(browser), "checkpoint-failed");
            return fail(cap, cap.steps().size(), cp.description(), String.valueOf(browser.topUrl()),
                    "Checkpoint not satisfied after all steps completed", recovered, start);
        }
        return null;
    }

    private String resolveAgainstBase(Capability cap, String value) {
        if (value == null) return null;
        return (value.startsWith("http://") || value.startsWith("https://"))
                ? value : cap.appTarget().baseUrl() + value;
    }

    private String substitute(String template, Map<String, String> inputs) {
        if (template == null) return null;
        String result = template;
        for (var e : inputs.entrySet()) result = result.replace("{{" + e.getKey() + "}}", e.getValue());
        return result;
    }

    private ReplayResult fail(Capability cap, int stepId, String expected, String observed,
                               String message, List<String> recovered, long start) {
        return ReplayResult.hardFailure(
                new ReplayResult.FailureDetail(stepId, "step " + stepId + " of capability " + cap.id(), expected, observed, message),
                recovered, evidence.dir().toString(), System.currentTimeMillis() - start);
    }

    private String currentHtml(SimpleBrowser browser) {
        return browser.topDocument() != null ? browser.topDocument().outerHtml() : "";
    }

    private String safeBodySnippet(SimpleBrowser browser) {
        try {
            String text = browser.topDocument() != null ? browser.topDocument().text() : "";
            return text.length() > 200 ? text.substring(0, 200) : text;
        } catch (Exception e) { return "(unable to read page text)"; }
    }

    // ---- known-condition detection for the mock bank target ----

    enum ConditionType { BUSINESS_OUTCOME, RECOVERABLE }
    record KnownCondition(ConditionType type, String code, String message) {}

    private static final Pattern NOT_FOUND = Pattern.compile("(?i)no records found");
    private static final Pattern VALIDATION = Pattern.compile("(?i)VALIDATION ERROR:?\\s*(.*)");
    private static final Pattern SYSTEM_BUSY = Pattern.compile("(?i)SYSTEM BUSY");

    private Optional<KnownCondition> detectKnownCondition(SimpleBrowser browser) {
        // Checks every currently loaded document (top + all frames), since the
        // condition may have landed inside a results frame rather than the top page.
        List<String> texts = new ArrayList<>();
        if (browser.topDocument() != null) texts.add(browser.topDocument().text());
        for (String frameName : discoveredFrameNames(browser)) {
            var doc = browser.frameDocument(frameName);
            if (doc != null) texts.add(doc.text());
        }
        for (String text : texts) {
            if (SYSTEM_BUSY.matcher(text).find())
                return Optional.of(new KnownCondition(ConditionType.RECOVERABLE, "SYSTEM_BUSY", "Transient system busy interstitial"));
        }
        for (String text : texts) {
            var val = VALIDATION.matcher(text);
            if (val.find()) return Optional.of(new KnownCondition(ConditionType.BUSINESS_OUTCOME, "VALIDATION_ERROR", val.group(1).trim()));
        }
        for (String text : texts) {
            if (NOT_FOUND.matcher(text).find())
                return Optional.of(new KnownCondition(ConditionType.BUSINESS_OUTCOME, "MEMBER_NOT_FOUND", "No matching member record"));
        }
        return Optional.empty();
    }

    private List<String> discoveredFrameNames(SimpleBrowser browser) {
        List<String> names = new ArrayList<>();
        if (browser.topDocument() == null) return names;
        for (var f : browser.topDocument().select("frame")) {
            String n = f.attr("name");
            if (!n.isBlank()) names.add(n);
        }
        return names;
    }
}
