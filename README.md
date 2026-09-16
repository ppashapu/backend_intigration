# Computer-Use Automation System

A goal → LLM-driven discovery run → saved capability artifact → deterministic
replay (with input params, outputs, and error/outcome handling) → human
escalation path → evidence pipeline, built against a deliberately "legacy"
mock banking UI (framesets, table layouts, no test IDs).

See **REPORT.md** for the design write-up (architecture, artifact schema,
determinism/error handling, multi-tenant story, escalation model, safety,
and cuts).

## Why jsoup instead of Playwright

The target app is server-rendered, JS-free legacy HTML — DOM-level automation
(explicitly allowed by the assignment, Section 3.1) is a faithful fit, and it
let me fully build and verify this end-to-end in my own environment, where I
did not have a working route to a browser-automation dependency. See
REPORT.md §1 for the full trade-off. `mvn` will work normally for you with
standard internet access if you'd rather swap in a Playwright-backed actuator
— the `LocatorSpec`/`Capability` schema was designed to make that a bounded
change, not a rewrite.

## Setup

Requires JDK 17+ and Maven, with normal internet access (to pull `jsoup` and
`jackson-databind` from Maven Central — this repo does not vendor them).

```bash
mvn -q package
```

This produces `target/cua.jar` (a shaded/fat jar with all dependencies).

No API key is needed to run the mock app, replay, or the scripted demo.
**A genuine discovery run needs `ANTHROPIC_API_KEY`** (see below) — this is
a hard requirement from the assignment, not optional.

## Demo path (exact commands)

The single command that exercises every core requirement end to end:

```bash
java -jar target/cua.jar demo --out ./evidence
```

This starts the mock bank server in-process, runs a **scripted discovery
run** (see "Genuine vs. scripted" below) for the goal *"look up member 12345
and read their current savings balance,"* saves the resulting `Capability`
artifact, then replays it three times to exercise all three outcome
branches:

- `replay-happy-path` → `SUCCESS`, `{balance: "$4,210.55"}`
- `replay-not-found` (member `99999`) → `BUSINESS_OUTCOME` /
  `MEMBER_NOT_FOUND`
- `replay-transient-recovered` (member `50000`) → `SUCCESS`, with a
  transient "system busy" interstitial transparently retried and logged to
  `recoveredEvents`

Each run's structured log, any DOM snapshots, and the artifact/result JSON
land under `./evidence/<run-id>/`.

### Genuine vs. scripted discovery

**The assignment requires at least one genuine LLM-driven discovery run.**
By default, `demo` and `discover` use a real `AnthropicLlmClient` and require
`ANTHROPIC_API_KEY`:

```bash
ANTHROPIC_API_KEY=sk-ant-... java -jar target/cua.jar demo --out ./evidence
```

To run the rest of the system (recorder, replay engine, guardrails,
escalation) without spending on API calls — useful for iterating on
everything *except* discovery — pass `--scripted` for a hand-authored,
deterministic stand-in that drives the exact same `AgentLoop`/`Recorder`/
`ReplayEngine` code paths:

```bash
java -jar target/cua.jar demo --scripted --out ./evidence
```

The evidence checked into `/evidence/` in this repo was produced with
`--scripted` (see the note in each run's `log.jsonl`: `"genuineLlm": false`,
and the console output is prefixed `[SCRIPTED, NON-GENUINE]`). **Re-run with
a real key before treating this submission as complete** — that's the one
thing the assignment says isn't optional.

### Individual commands

```bash
# Start the mock legacy bank app standalone
java -jar target/cua.jar serve-mock --port 8085

# Discover a new capability for an arbitrary goal
java -jar target/cua.jar discover \
  --goal "Look up member 12345 and read their savings balance" \
  --base-url http://127.0.0.1:8085 \
  --capability-id lookup-member-balance \
  --capability-name "Look up member balance" \
  --param memberId=12345 \
  --out ./evidence
  # add --scripted to skip the LLM call; omit it (with ANTHROPIC_API_KEY set) for a genuine run

# Replay a saved artifact with different inputs
java -jar target/cua.jar replay \
  --artifact ./evidence/discover-.../artifact.json \
  --param memberId=99999 \
  --out ./evidence

# Replay a capability containing an IRREVERSIBLE step (blocked unless approved)
java -jar target/cua.jar replay --artifact path/to/artifact.json --approve --out ./evidence
```

## Mock target app

`MockBankServer` (plain `com.sun.net.httpserver.HttpServer`, no framework)
simulates a legacy core-banking console:

- `<frameset>`-based navigation (search form frame + results frame)
- table-based layout, no `data-testid` attributes
- member `12345`, `40000` exist; `99999` is a deliberate "not found"
- member `50000` returns a transient "SYSTEM BUSY" page on the first
  request, then succeeds — exercises the recoverable-condition path
- opening a sub-account with a deposit under $25 returns a validation-error
  business outcome; the final "Confirm and Open Account" step is classified
  `IRREVERSIBLE` and is blocked at replay time unless the capability is
  `APPROVED` (`--approve`)

## Project layout

```
model/        artifact schema: Capability, Step, LocatorSpec, Checkpoint, ReplayResult, ...
mockapp/      MockBankServer — the legacy target
browser/      SimpleBrowser — session-aware HTTP+DOM automation engine
agent/        DomObserver, LocatorSpecBuilder, Recorder, AgentLoop (discovery)
llm/          LlmClient interface, AnthropicLlmClient (genuine), ScriptedLlmClient (offline stand-in)
replay/       DomActuator, ReplayEngine (deterministic production path)
guardrails/   Allowlist + risk policy + PII/secret redaction
escalation/   EscalationManager — human-in-the-loop pause/takeover/resume
evidence/     EvidenceLogger, JSON utilities
cli/          Main — discover / replay / demo / serve-mock commands
```
