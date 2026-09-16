package ai.interfaceai.cua.escalation;

import ai.interfaceai.cua.agent.DomObserver;
import ai.interfaceai.cua.browser.SimpleBrowser;
import ai.interfaceai.cua.evidence.EvidenceLogger;
import ai.interfaceai.cua.model.Enums;
import ai.interfaceai.cua.model.InterventionRequest;
import ai.interfaceai.cua.replay.DomActuator;

import java.time.Instant;
import java.util.*;

/**
 * Section 3.6. The seam this implements: automation calls escalate(), which
 * BLOCKS the calling thread (pause) while handing the SAME SimpleBrowser
 * instance -- not a fresh session, same cookies/current documents -- to a
 * minimal operator console read from stdin. The operator acts on that live
 * session via a tiny command DSL, then types `resume` (or `abandon`) to hand
 * control back, at which point the calling thread continues with the same
 * browser state.
 *
 * Scope note (per the assignment): a full real-time co-browsing console is
 * out of scope. This is the mocked operator surface; the pause/cede/resume
 * control-transfer model and the "same session, not a fresh one" property
 * are real, not mocked -- see REPORT.md #5.
 */
public class EscalationManager {

    private final SimpleBrowser browser;
    private final DomObserver observer = new DomObserver();
    private final DomActuator actuator = new DomActuator();
    private final EvidenceLogger evidence;
    private final boolean interactive;

    public EscalationManager(SimpleBrowser browser, EvidenceLogger evidence, boolean interactive) {
        this.browser = browser;
        this.evidence = evidence;
        this.interactive = interactive;
    }

    @SuppressWarnings("resource") // Scanner wraps System.in deliberately; closing it would close stdin process-wide

    public InterventionRequest escalate(String capabilityIdOrGoal, int currentStepIndex,
                                         Enums.EscalationReason reason, String contextSummary) {
        String snapshotPath = evidence.domSnapshot(currentHtml(), "escalation");
        String sessionHandle = "session@" + System.identityHashCode(browser);
        List<String> actionsTaken = new ArrayList<>();

        evidence.event("escalation_raised", Map.of(
                "reason", reason.name(), "context", contextSummary, "snapshot", snapshotPath));

        System.out.println("\n=== HUMAN INTERVENTION REQUESTED ===");
        System.out.println("Reason: " + reason);
        System.out.println("Context: " + contextSummary);
        System.out.println("DOM snapshot: " + snapshotPath);
        System.out.println("Session handle: " + sessionHandle + " (same live browser session, cookies intact)");

        Enums.InterventionStatus finalStatus;

        if (!interactive) {
            System.out.println("[non-interactive mode] auto-resuming without manual action.");
            finalStatus = Enums.InterventionStatus.RESOLVED_RESUMED;
        } else {
            System.out.println("Commands: show | click <idx> | type <idx> <text> | resume | abandon");
            Scanner sc = new Scanner(System.in);
            boolean inControl = true;
            Enums.InterventionStatus status = Enums.InterventionStatus.RESOLVED_ABANDONED;
            while (inControl) {
                System.out.print("operator> ");
                String line = sc.hasNextLine() ? sc.nextLine().trim() : "resume";
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+", 3);
                switch (parts[0]) {
                    case "show" -> System.out.println(observer.observe(browser).toPromptText());
                    case "click" -> {
                        try {
                            var obs = observer.observe(browser);
                            var entry = obs.byIndex(Integer.parseInt(parts[1]));
                            var spec = ai.interfaceai.cua.agent.LocatorSpecBuilder.build(entry.getKey(), entry.getValue());
                            var res = actuator.resolve(browser, spec);
                            actuator.click(browser, res);
                            actionsTaken.add("click " + parts[1]);
                        } catch (Exception e) {
                            System.out.println("click failed: " + e.getMessage());
                        }
                    }
                    case "type" -> {
                        try {
                            var obs = observer.observe(browser);
                            var entry = obs.byIndex(Integer.parseInt(parts[1]));
                            var spec = ai.interfaceai.cua.agent.LocatorSpecBuilder.build(entry.getKey(), entry.getValue());
                            var res = actuator.resolve(browser, spec);
                            actuator.type(browser, res, parts.length > 2 ? parts[2] : "");
                            actionsTaken.add("type " + parts[1] + " " + (parts.length > 2 ? parts[2] : ""));
                        } catch (Exception e) {
                            System.out.println("type failed: " + e.getMessage());
                        }
                    }
                    case "resume" -> { inControl = false; status = Enums.InterventionStatus.RESOLVED_RESUMED; }
                    case "abandon" -> { inControl = false; status = Enums.InterventionStatus.RESOLVED_ABANDONED; }
                    default -> System.out.println("unknown command");
                }
            }
            finalStatus = status;
        }

        evidence.event("escalation_resolved", Map.of("status", finalStatus.name(), "humanActions", actionsTaken));

        return new InterventionRequest(UUID.randomUUID().toString(), capabilityIdOrGoal, currentStepIndex, reason,
                contextSummary, snapshotPath, sessionHandle, Instant.now().toString(), finalStatus, actionsTaken);
    }

    private String currentHtml() {
        return browser.topDocument() != null ? browser.topDocument().outerHtml() : "";
    }
}
