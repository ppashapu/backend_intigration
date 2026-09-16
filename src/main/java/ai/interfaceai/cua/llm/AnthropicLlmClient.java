package ai.interfaceai.cua.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * The genuine LLM decision-maker used for the required real discovery run
 * (Section 4: "the discovery run has to be real"). Calls the Anthropic
 * Messages API directly and forces strict-JSON output so the agent loop can
 * parse a decision deterministically, without a tool-use round trip.
 *
 * Requires ANTHROPIC_API_KEY in the environment. No key is embedded or
 * defaulted anywhere in this codebase.
 */
public class AnthropicLlmClient implements LlmClient {

    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public AnthropicLlmClient(String apiKey, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "ANTHROPIC_API_KEY is not set. A genuine discovery run requires real model access " +
                "(see Section 4 of the assignment) -- set the env var and retry, " +
                "or run with --scripted for an offline dry run of the rest of the system.");
        }
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public boolean isGenuine() { return true; }

    @Override
    public AgentDecision decide(String goal, List<String> historySoFar, String observationText) {
        String system = """
            You are driving a legacy back-office banking web UI to accomplish a goal.
            You see a list of interactive elements per observation, each with a numeric index.
            Respond with ONLY a single JSON object, no prose, no markdown fences, matching:
            {
              "reasoning": "short explanation of this step",
              "action": "NAVIGATE|CLICK|TYPE|SELECT_OPTION|EXTRACT|DONE|STUCK",
              "elementIndex": <int or null>,
              "value": "<string or null>",
              "extractAs": "<string or null, only for EXTRACT>",
              "done": <true only when action is DONE>,
              "finalOutputs": {<string:string>, only when done is true},
              "stuckReason": "<string or null, only when action is STUCK>"
            }
            Rules:
            - Use NAVIGATE only for the very first step, with value = a full URL.
            - Use CLICK/TYPE/SELECT_OPTION/EXTRACT with elementIndex referring to the
              most recent observation's element list.
            - Use EXTRACT to read a value (e.g. a balance or new account number) into extractAs.
            - When the goal is fully achieved, respond with action=DONE, done=true, and
              finalOutputs containing every value the goal asked you to capture.
            - If you cannot find a safe path forward after several attempts, respond with
              action=STUCK and a clear stuckReason instead of guessing.
            - Never invent element indices that were not shown to you.
            """;

        StringBuilder userMsg = new StringBuilder();
        userMsg.append("GOAL: ").append(goal).append("\n\n");
        if (!historySoFar.isEmpty()) {
            userMsg.append("STEPS SO FAR:\n");
            for (String h : historySoFar) userMsg.append("- ").append(h).append("\n");
            userMsg.append("\n");
        }
        userMsg.append("CURRENT OBSERVATION:\n").append(observationText);

        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", 1024);
            body.put("system", system);
            var messages = mapper.createArrayNode();
            var userNode = mapper.createObjectNode();
            userNode.put("role", "user");
            userNode.put("content", userMsg.toString());
            messages.add(userNode);
            body.set("messages", messages);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Anthropic API error " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode root = mapper.readTree(resp.body());
            String text = root.path("content").get(0).path("text").asText();
            String jsonOnly = extractJsonObject(text);
            JsonNode d = mapper.readTree(jsonOnly);

            Map<String, String> outputs = new LinkedHashMap<>();
            if (d.has("finalOutputs") && d.get("finalOutputs").isObject()) {
                d.get("finalOutputs").fields().forEachRemaining(e -> outputs.put(e.getKey(), e.getValue().asText()));
            }

            return new AgentDecision(
                    d.path("reasoning").asText(null),
                    AgentDecision.Action.valueOf(d.path("action").asText("STUCK")),
                    d.hasNonNull("elementIndex") ? d.get("elementIndex").asInt() : null,
                    d.hasNonNull("value") ? d.get("value").asText() : null,
                    d.hasNonNull("extractAs") ? d.get("extractAs").asText() : null,
                    d.path("done").asBoolean(false),
                    outputs,
                    d.hasNonNull("stuckReason") ? d.get("stuckReason").asText() : null
            );
        } catch (Exception e) {
            throw new RuntimeException("LLM decision call failed: " + e.getMessage(), e);
        }
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < 0 || end < start) {
            throw new RuntimeException("Model did not return a JSON object: " + text);
        }
        return text.substring(start, end + 1);
    }
}
