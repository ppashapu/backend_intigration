package ai.interfaceai.cua.cli;

import ai.interfaceai.cua.agent.AgentLoop;
import ai.interfaceai.cua.evidence.EvidenceLogger;
import ai.interfaceai.cua.evidence.JsonUtil;
import ai.interfaceai.cua.guardrails.Guardrails;
import ai.interfaceai.cua.llm.AnthropicLlmClient;
import ai.interfaceai.cua.llm.LlmClient;
import ai.interfaceai.cua.llm.ScriptedLlmClient;
import ai.interfaceai.cua.mockapp.MockBankServer;
import ai.interfaceai.cua.model.Capability;
import ai.interfaceai.cua.model.Enums;
import ai.interfaceai.cua.model.ParamSpec;
import ai.interfaceai.cua.model.ReplayResult;
import ai.interfaceai.cua.replay.ReplayEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }
        Map<String, String> opts = parseOpts(args);
        switch (args[0]) {
            case "serve-mock" -> serveMock(opts);
            case "discover" -> discover(opts);
            case "replay" -> replay(opts);
            case "demo" -> demo(opts);
            default -> printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("""
            Usage:
              serve-mock [--port 8085]
              discover --goal "..." --base-url http://127.0.0.1:8085 --capability-id ID --capability-name NAME
                        [--param name=value]* [--scripted] [--out ./evidence]
              replay --artifact path/to/artifact.json [--param name=value]* [--out ./evidence] [--approve]
              demo [--genuine] [--port 8085] [--out ./evidence]
            """);
    }

    private static void serveMock(Map<String, String> opts) throws IOException, InterruptedException {
        int port = Integer.parseInt(opts.getOrDefault("port", "8085"));
        MockBankServer server = new MockBankServer(port);
        server.start();
        System.out.println("Mock bank server running at http://127.0.0.1:" + port + "/  (Ctrl+C to stop)");
        Thread.currentThread().join();
    }

    private static void discover(Map<String, String> opts) throws Exception {
        String goal = require(opts, "goal");
        String baseUrl = opts.getOrDefault("base-url", "http://127.0.0.1:8085");
        String capabilityId = require(opts, "capability-id");
        String capabilityName = opts.getOrDefault("capability-name", capabilityId);
        Path outDir = Path.of(opts.getOrDefault("out", "./evidence"));
        Map<String, ParamSpec> params = parseParams(opts);

        boolean scripted = opts.containsKey("scripted");
        LlmClient llm = scripted
                ? ScriptedLlmClient.forLookupBalanceDemo(baseUrl, firstParamValue(params, "memberId", "12345"))
                : new AnthropicLlmClient(System.getenv("ANTHROPIC_API_KEY"), "claude-sonnet-4-6");

        Guardrails guardrails = Guardrails.defaultForLocalMockBank(baseUrl);
        String runId = "discover-" + capabilityId + "-" + System.currentTimeMillis();

        try (EvidenceLogger evidence = new EvidenceLogger(outDir, runId)) {
            AgentLoop loop = new AgentLoop(llm, guardrails, evidence, 20);
            var outcome = loop.run(goal,
                    new Capability.AppTarget(capabilityId, "legacy-core-banking-demo", baseUrl, "base"),
                    capabilityId, capabilityName, params, runId);

            if (outcome.stuck() || outcome.capability() == null) {
                System.out.println("Discovery run did not complete (stuck/timeout). See evidence: " + evidence.dir());
                return;
            }
            Path artifactPath = evidence.dir().resolve("artifact.json");
            System.out.println((outcome.genuine() ? "[GENUINE LLM RUN] " : "[SCRIPTED, NON-GENUINE] ")
                    + "Capability saved: " + artifactPath);
            System.out.println("Evidence dir: " + evidence.dir());
        }
    }

    private static void replay(Map<String, String> opts) throws Exception {
        Path artifactPath = Path.of(require(opts, "artifact"));
        Capability cap = JsonUtil.MAPPER.readValue(artifactPath.toFile(), Capability.class);
        if (opts.containsKey("approve")) {
            cap = new Capability(cap.id(), cap.version(), Enums.ArtifactStatus.APPROVED, cap.name(),
                    cap.goalDescription(), cap.appTarget(), cap.inputParams(), cap.outputs(), cap.steps(),
                    cap.checkpoint(), cap.overallRisk(), cap.sourceDiscoveryRunId(), cap.createdAt());
        }
        Map<String, String> inputs = new LinkedHashMap<>();
        for (var e : parseParams(opts).entrySet()) inputs.put(e.getKey(), e.getValue().example());

        Path outDir = Path.of(opts.getOrDefault("out", "./evidence"));
        Guardrails guardrails = Guardrails.defaultForLocalMockBank(cap.appTarget().baseUrl());
        String runId = "replay-" + cap.id() + "-" + System.currentTimeMillis();

        try (EvidenceLogger evidence = new EvidenceLogger(outDir, runId)) {
            ReplayEngine engine = new ReplayEngine(guardrails, evidence);
            ReplayResult result = engine.replay(cap, inputs);
            evidence.writeJson("result.json", result);
            System.out.println("Outcome: " + result.outcomeType());
            System.out.println(JsonUtil.MAPPER.writeValueAsString(result));
            System.out.println("Evidence dir: " + evidence.dir());
        }
    }

    /** Runs the full vertical slice end to end and writes both a happy-path and an error-path replay. */
    private static void demo(Map<String, String> opts) throws Exception {
        int port = Integer.parseInt(opts.getOrDefault("port", "8085"));
        String baseUrl = "http://127.0.0.1:" + port;
        Path outDir = Path.of(opts.getOrDefault("out", "./evidence"));
        Files.createDirectories(outDir);

        MockBankServer server = new MockBankServer(port);
        server.start();
        try {
            boolean genuine = opts.containsKey("genuine");
                        String goal = "Starting URL (navigate here first): " + baseUrl + "/search\n\n"
                    + "Goal: Look up member 12345 and read their current savings balance, "
                    + "confirming the member detail page is reached.";
            Map<String, ParamSpec> params = Map.of("memberId",
                    new ParamSpec("memberId", Enums.ParamType.STRING, true, "The member to look up", "12345"));

            LlmClient llm = genuine
                    ? new AnthropicLlmClient(System.getenv("ANTHROPIC_API_KEY"), "claude-sonnet-4-6")
                    : ScriptedLlmClient.forLookupBalanceDemo(baseUrl, "12345");

            Guardrails guardrails = Guardrails.defaultForLocalMockBank(baseUrl);
            String discoverRunId = "discover-lookup-member-balance-" + System.currentTimeMillis();
            Capability capability;
            try (EvidenceLogger evidence = new EvidenceLogger(outDir, discoverRunId)) {
                AgentLoop loop = new AgentLoop(llm, guardrails, evidence, 20);
                var outcome = loop.run(goal,
                        new Capability.AppTarget("lookup-member-balance", "legacy-core-banking-demo", baseUrl, "base"),
                        "lookup-member-balance", "Look up member savings balance", params, discoverRunId);
                if (outcome.capability() == null) {
                    System.out.println("Discovery did not complete; see " + evidence.dir());
                    return;
                }
                capability = outcome.capability();
                System.out.println((outcome.genuine() ? "[GENUINE LLM RUN] " : "[SCRIPTED, NON-GENUINE] ")
                        + "Discovery complete -> " + evidence.dir().resolve("artifact.json"));
            }

            // Happy-path replay
            replayOnce(capability, Map.of("memberId", "12345"), guardrails, outDir, "replay-happy-path");

            // Error-path replay: member does not exist -> BUSINESS_OUTCOME
            replayOnce(capability, Map.of("memberId", "99999"), guardrails, outDir, "replay-not-found");

            // Error-path replay: transient backend hiccup -> RECOVERABLE, transparently retried
            replayOnce(capability, Map.of("memberId", "50000"), guardrails, outDir, "replay-transient-recovered");

        } finally {
            server.stop();
        }
    }

    private static void replayOnce(Capability capability, Map<String, String> inputs, Guardrails guardrails,
                                    Path outDir, String runIdPrefix) throws IOException {
        String runId = runIdPrefix + "-" + System.currentTimeMillis();
        try (EvidenceLogger evidence = new EvidenceLogger(outDir, runId)) {
            ReplayEngine engine = new ReplayEngine(guardrails, evidence);
            ReplayResult result = engine.replay(capability, inputs);
            evidence.writeJson("result.json", result);
            System.out.println(runIdPrefix + " -> " + result.outcomeType()
                    + (result.outcomeType() == Enums.OutcomeType.SUCCESS ? " " + result.outputs() : "")
                    + (result.outcomeType() == Enums.OutcomeType.BUSINESS_OUTCOME ? " " + result.businessOutcomeCode() : "")
                    + (!result.recoveredEvents().isEmpty() ? " recovered=" + result.recoveredEvents() : ""));
        }
    }

    // ---- arg parsing helpers ----

    private static Map<String, String> parseOpts(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    opts.merge(key, args[i + 1], (a, b) -> a + "\u0001" + b); // allow repeated --param
                    i++;
                } else {
                    opts.put(key, "true");
                }
            }
        }
        return opts;
    }

    private static Map<String, ParamSpec> parseParams(Map<String, String> opts) {
        Map<String, ParamSpec> params = new LinkedHashMap<>();
        String raw = opts.get("param");
        if (raw == null) return params;
        for (String entry : raw.split("\u0001")) {
            String[] kv = entry.split("=", 2);
            if (kv.length == 2) {
                params.put(kv[0], new ParamSpec(kv[0], Enums.ParamType.STRING, true, "Input parameter", kv[1]));
            }
        }
        return params;
    }

    private static String firstParamValue(Map<String, ParamSpec> params, String name, String fallback) {
        ParamSpec p = params.get(name);
        return p != null ? p.example() : fallback;
    }

    private static String require(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null) throw new IllegalArgumentException("Missing required --" + key);
        return v;
    }
}
