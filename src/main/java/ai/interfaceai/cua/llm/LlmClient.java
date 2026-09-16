package ai.interfaceai.cua.llm;

import java.util.List;
import java.util.Map;

/**
 * Deliberately narrow interface: given the goal, the history so far, and a
 * text rendering of the current page's interactive elements, return ONE
 * decision. This is what lets discovery be swapped between providers (or a
 * scripted stand-in for offline testing, see ScriptedLlmClient) without
 * touching the agent loop, recorder, or replay engine at all.
 */
public interface LlmClient {

    AgentDecision decide(String goal, List<String> historySoFar, String observationText);

    /** Whether this client constitutes a genuine LLM per the assignment (false for the scripted stand-in). */
    boolean isGenuine();

    record AgentDecision(
            String reasoning,
            Action action,
            Integer elementIndex,   // for CLICK / TYPE / SELECT_OPTION / EXTRACT
            String value,           // literal text to type/select, or URL for NAVIGATE
            String extractAs,       // output variable name, only for EXTRACT
            boolean done,
            Map<String, String> finalOutputs, // only when done=true
            String stuckReason      // only when action == STUCK
    ) {
        public enum Action { NAVIGATE, CLICK, TYPE, SELECT_OPTION, EXTRACT, DONE, STUCK }
    }
}
