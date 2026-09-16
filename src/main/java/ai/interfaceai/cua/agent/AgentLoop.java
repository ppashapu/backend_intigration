package ai.interfaceai.cua.agent;

import ai.interfaceai.cua.browser.SimpleBrowser;
import ai.interfaceai.cua.evidence.EvidenceLogger;
import ai.interfaceai.cua.guardrails.Guardrails;
import ai.interfaceai.cua.guardrails.Redactor;
import ai.interfaceai.cua.llm.LlmClient;
import ai.interfaceai.cua.llm.LlmClient.AgentDecision;
import ai.interfaceai.cua.model.*;
import ai.interfaceai.cua.replay.DomActuator;

import java.util.*;

/** Section 3.1: the goal-driven observe -> decide -> act loop that produces a Capability artifact on success. */
public class AgentLoop {

    public record DiscoveryOutcome(Capability capability, boolean genuine, String evidenceDir, boolean stuck) {}

    private final LlmClient llm;
    private final Guardrails guardrails;
    private final EvidenceLogger evidence;
    private final DomObserver observer = new DomObserver();
    private final DomActuator actuator = new DomActuator();
    private final int maxSteps;

    public AgentLoop(LlmClient llm, Guardrails guardrails, EvidenceLogger evidence, int maxSteps) {
        this.llm = llm;
        this.guardrails = guardrails;
        this.evidence = evidence;
        this.maxSteps = maxSteps;
    }

    public DiscoveryOutcome run(String goal,
                                 Capability.AppTarget appTarget,
                                 String capabilityId,
                                 String capabilityName,
                                 Map<String, ParamSpec> declaredParams,
                                 String sourceRunId) {
        SimpleBrowser browser = new SimpleBrowser();
        List<String> history = new ArrayList<>();
        List<Step> rawSteps = new ArrayList<>();
        Set<String> extractedNames = new LinkedHashSet<>();
        int stepCounter = 0;
        evidence.event("discovery_start", Map.of("goal", goal, "genuineLlm", llm.isGenuine()));

        for (int i = 0; i < maxSteps; i++) {
            DomObserver.Observation obs = observer.observe(browser);
            String obsText = obs.toPromptText();
            evidence.event("observe", Map.of("url", String.valueOf(obs.pageUrl()), "elementCount",
                    obs.frames().stream().mapToInt(f -> f.elements().size()).sum()));

            AgentDecision decision;
            try {
                decision = llm.decide(goal, history, obsText);
            } catch (RuntimeException e) {
                evidence.event("llm_error", Map.of("message", String.valueOf(e.getMessage())));
                return new DiscoveryOutcome(null, llm.isGenuine(), evidence.dir().toString(), true);
            }
            evidence.event("decide", Map.of("action", decision.action().name(),
                    "reasoning", String.valueOf(Redactor.scrubFreeText(decision.reasoning()))));

            try {
                switch (decision.action()) {
                    case NAVIGATE -> {
                        String url = decision.value();
                        if (!guardrails.isUrlAllowed(url)) {
                            evidence.event("guardrail_block", Map.of("url", String.valueOf(url)));
                            throw new SecurityException("URL not in allowlist: " + url);
                        }
                        browser.navigate(url);
                        // Store relative to appTarget.baseUrl so the capability is portable across
                        // tenants running the same app at a different base URL (see REPORT.md #4).
                        String storedValue = url.startsWith(appTarget.baseUrl())
                                ? url.substring(appTarget.baseUrl().length())
                                : url;
                        stepCounter++;
                        rawSteps.add(new Step(stepCounter, Enums.ActionType.NAVIGATE,
                                "Navigate to " + storedValue, null, storedValue, null, Enums.RiskLevel.SAFE, 8000));
                        history.add("Navigated to " + url);
                    }
                    case CLICK -> {
                        var entry = obs.byIndex(decision.elementIndex());
                        var frame = entry.getKey();
                        var el = entry.getValue();
                        Enums.RiskLevel risk = classifyClickRisk(el);
                        if (guardrails.requiresHumanConfirmation(risk)) {
                            evidence.event("irreversible_action_pending", Map.of("label", el.bestVisibleLabel()));
                            // Logged as an explicit decision point; a stricter policy could call
                            // EscalationManager.escalate(...) here and block until a human resumes.
                        }
                        LocatorSpec spec = LocatorSpecBuilder.build(frame, el);
                        var res = actuator.resolve(browser, spec);
                        actuator.click(browser, res);
                        stepCounter++;
                        rawSteps.add(new Step(stepCounter, Enums.ActionType.CLICK,
                                "Click \"" + el.bestVisibleLabel() + "\"", spec, null, null, risk, 5000));
                        history.add("Clicked \"" + el.bestVisibleLabel() + "\"");
                    }
                    case TYPE -> {
                        var entry = obs.byIndex(decision.elementIndex());
                        var frame = entry.getKey();
                        var el = entry.getValue();
                        LocatorSpec spec = LocatorSpecBuilder.build(frame, el);
                        var res = actuator.resolve(browser, spec);
                        String raw = decision.value();
                        actuator.type(browser, res, raw);
                        String stored = Redactor.redactValueForField(el.name(), raw);
                        stepCounter++;
                        rawSteps.add(new Step(stepCounter, Enums.ActionType.TYPE,
                                "Type into \"" + el.bestVisibleLabel() + "\"", spec, stored, null,
                                Enums.RiskLevel.SAFE, 5000));
                        history.add("Typed into \"" + el.bestVisibleLabel() + "\"");
                    }
                    case SELECT_OPTION -> {
                        var entry = obs.byIndex(decision.elementIndex());
                        var frame = entry.getKey();
                        var el = entry.getValue();
                        LocatorSpec spec = LocatorSpecBuilder.build(frame, el);
                        var res = actuator.resolve(browser, spec);
                        actuator.selectOption(browser, res, decision.value());
                        stepCounter++;
                        rawSteps.add(new Step(stepCounter, Enums.ActionType.SELECT_OPTION,
                                "Select \"" + decision.value() + "\" in \"" + el.bestVisibleLabel() + "\"",
                                spec, decision.value(), null, Enums.RiskLevel.SAFE, 5000));
                        history.add("Selected " + decision.value());
                    }
                    case EXTRACT -> {
                        var entry = obs.byIndex(decision.elementIndex());
                        var frame = entry.getKey();
                        var el = entry.getValue();
                        LocatorSpec spec = LocatorSpecBuilder.build(frame, el);
                        var res = actuator.resolve(browser, spec);
                        String value = actuator.extractText(res);
                        String name = decision.extractAs() != null ? decision.extractAs() : "value" + stepCounter;
                        extractedNames.add(name);
                        stepCounter++;
                        rawSteps.add(new Step(stepCounter, Enums.ActionType.EXTRACT,
                                "Extract \"" + name + "\" from \"" + el.bestVisibleLabel() + "\"",
                                spec, null, name, Enums.RiskLevel.SAFE, 5000));
                        history.add("Extracted " + name + " = " + Redactor.scrubFreeText(value));
                    }
                    case DONE -> {
                        String finalUrl = browser.topUrl();
                        Checkpoint checkpoint = new Checkpoint(
                                Enums.CheckpointKind.URL_CONTAINS, pathOf(finalUrl), null,
                                "Replay must land on a URL containing " + pathOf(finalUrl));

                        Recorder recorder = new Recorder();
                        Capability capability = recorder.buildCapability(
                                capabilityId, capabilityName, goal, appTarget, rawSteps,
                                declaredParams, new ArrayList<>(extractedNames), checkpoint, sourceRunId);

                        evidence.event("discovery_done", Map.of("stepCount", rawSteps.size()));
                        evidence.writeJson("artifact.json", capability);
                        return new DiscoveryOutcome(capability, llm.isGenuine(), evidence.dir().toString(), false);
                    }
                    case STUCK -> {
                        evidence.event("discovery_stuck", Map.of("reason", String.valueOf(decision.stuckReason())));
                        evidence.domSnapshot(currentHtml(browser), "stuck");
                        return new DiscoveryOutcome(null, llm.isGenuine(), evidence.dir().toString(), true);
                    }
                }
            } catch (RuntimeException e) {
                evidence.event("agent_step_error", Map.of("message", String.valueOf(e.getMessage())));
                evidence.domSnapshot(currentHtml(browser), "agent-error");
                return new DiscoveryOutcome(null, llm.isGenuine(), evidence.dir().toString(), true);
            }
        }
        evidence.event("discovery_timeout", Map.of("maxSteps", maxSteps));
        evidence.domSnapshot(currentHtml(browser), "timeout");
        return new DiscoveryOutcome(null, llm.isGenuine(), evidence.dir().toString(), true);
    }

    private Enums.RiskLevel classifyClickRisk(DomObserver.ObservedElement el) {
        String label = el.bestVisibleLabel().toLowerCase(Locale.ROOT);
        boolean irreversibleWord = List.of("confirm", "submit", "open account", "create", "delete", "close", "transfer")
                .stream().anyMatch(label::contains);
        return irreversibleWord ? Enums.RiskLevel.IRREVERSIBLE : Enums.RiskLevel.SAFE;
    }

    private String pathOf(String url) {
        try { return java.net.URI.create(url).getPath(); } catch (Exception e) { return url; }
    }

    private String currentHtml(SimpleBrowser browser) {
        return browser.topDocument() != null ? browser.topDocument().outerHtml() : "";
    }
}
