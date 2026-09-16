package ai.interfaceai.cua.llm;

import java.util.Iterator;
import java.util.List;

/**
 * NOT a genuine LLM. A deterministic, hand-authored decision sequence that
 * exercises the exact same AgentLoop / Recorder / ReplayEngine code paths as
 * a real model would, so the rest of the system (schema, replay, guardrails,
 * escalation) can be built, run, and tested without spending on API calls.
 *
 * isGenuine() returns false, and AgentLoop refuses to let a run made with a
 * non-genuine client be the one written into /evidence/ as "the" discovery
 * run -- it labels the artifact accordingly. Swap in AnthropicLlmClient to
 * produce the real evidence the assignment requires.
 */
public class ScriptedLlmClient implements LlmClient {

    private final Iterator<AgentDecision> script;

    public ScriptedLlmClient(List<AgentDecision> decisions) {
        this.script = decisions.iterator();
    }

    @Override
    public boolean isGenuine() { return false; }

    @Override
    public AgentDecision decide(String goal, List<String> historySoFar, String observationText) {
        if (!script.hasNext()) {
            return new AgentDecision("scripted client exhausted its script", AgentDecision.Action.STUCK,
                    null, null, null, false, null, "scripted script ran out of steps");
        }
        return script.next();
    }

    /** A canned script for: "look up member {memberId} and read their savings balance". */
    public static ScriptedLlmClient forLookupBalanceDemo(String baseUrl, String memberId) {
        return new ScriptedLlmClient(List.of(
                new AgentDecision("Start at the search frameset", AgentDecision.Action.NAVIGATE,
                        null, baseUrl + "/search", null, false, null, null),
                new AgentDecision("Type the member ID into the search box", AgentDecision.Action.TYPE,
                        0, memberId, null, false, null, null),
                new AgentDecision("Submit the search", AgentDecision.Action.CLICK,
                        1, null, null, false, null, null),
                new AgentDecision("Open the member detail view", AgentDecision.Action.CLICK,
                        2, null, null, false, null, null),
                new AgentDecision("Read the balance", AgentDecision.Action.EXTRACT,
                        0, null, "balance", false, null, null),
                new AgentDecision("Goal achieved", AgentDecision.Action.DONE,
                        null, null, null, true, java.util.Map.of(), null)
        ));
    }
}
