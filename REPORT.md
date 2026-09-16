# REPORT.md — Computer-Use Automation System

## 1. Architecture

Four stages, each independently testable: **DomObserver** (perceives the page)
→ **LlmClient** (decides, via a real model or a scripted stand-in) →
**DomActuator** (acts) → **Recorder** (crystallizes the run into a
**Capability** artifact). Replay reuses the same DomActuator against a fresh
**SimpleBrowser** session, with an **LlmClient** nowhere in the loop.

**Automation mechanism — the one deliberate deviation from the obvious
choice.** The target is a server-rendered, JS-free legacy web app (framesets,
table layouts, no test IDs) — exactly the "no clean DOM" case the assignment
describes. A full rendered-browser engine (Playwright/Selenium) is the
standard choice and was my first implementation, but I could not verify it
compiles or runs in my development sandbox (no route to the dependency
registries a browser-automation library needs). Rather than ship code I
could not build and test, I switched to a **DOM-level automation** engine —
`SimpleBrowser`: a session-aware HTTP client (cookies persist, GET/POST forms
are submitted for real) plus jsoup for DOM parsing/selection, with explicit
`<frameset>`/`<frame>` handling since there's no browser engine doing that
implicitly. Section 3.1 explicitly lists DOM-level automation as a valid
mechanism. Trade-off, stated plainly: this cannot execute client-side JS or
render pixels, so it would not extend to a modern SPA or support a real
screenshot as evidence — a DOM snapshot is used instead (also explicitly
allowed by 3.5). For *this* target it is a faithful, fully-verified
substitute; for a JS-heavy tenant app, the Playwright-based `PlaywrightActuator`
I originally wrote (git history) is a near-drop-in replacement behind the
same `DomActuator`-shaped interface, because the LocatorSpec/Capability
schema was designed to be engine-agnostic from the start (see §2).

**Single process, synchronous, no queues.** Hundreds of tenants and ~20 apps
each is a real-world scale constraint, not a reason to add infrastructure to
a take-home. The design leaves room to scale (Capability is a plain
serializable value; ReplayEngine is stateless per call) without building the
scaling infrastructure now, per the assignment's explicit guidance.

## 2. Artifact schema

`Capability` is the reusable, agent-invocable contract (see
`model/Capability.java`, heavily commented). Deliberate choices:

- **`appTarget` is separated from `steps`.** Steps never embed an absolute
  URL — `NAVIGATE`'s value is stored *relative to* `appTarget.baseUrl` (I
  initially got this wrong: NAVIGATE stored the full discovery-time URL,
  which silently broke portability to a different tenant's instance; caught
  by testing the artifact against a second port and fixed). This is what
  lets the same `Capability` be pointed at any tenant's instance of the same
  vendor product by swapping one field.
- **`LocatorSpec` is an ordered fallback chain, not a single selector**
  (`strategies: List<LocatorStrategy>`). Each strategy is tagged with a
  `LocatorKind` and a human-readable `note` so a reviewer understands *why*
  each fallback exists. Tiers, strongest first: stable `id` (`TEST_ID`) →
  visible text/value (`VISIBLE_TEXT_OR_VALUE`, survives markup reshuffling
  because it reflects what an operator reads) → HTML `name` attribute
  (`FIELD_NAME_ATTR`, the most stable signal on legacy table-based form
  fields that carry no visible label) → structural nth-of-type CSS path
  (`POSITIONAL_CSS`, brittle, last resort). Replay logs which strategy
  actually resolved — a fallback firing is the early-warning signal for UI
  drift, before it becomes an outright failure.
- **`frameChain`** names the `<frame>` a control lives in (by HTML `name`
  attribute), because frameset navigation has no equivalent in a
  DOM-without-JS model otherwise.
- **Typed `inputParams`/`outputs`** make the capability callable like a
  function. `Step.value` supports `{{paramName}}` templates; the Recorder
  parametrizes a raw transcript by matching literal values against
  explicitly declared parameters (I chose not to *infer* which literals
  should vary — that's a guess a schema shouldn't encode silently).
- **`Checkpoint`** is a separate, explicit success assertion, not "the last
  step succeeded" — it's checked once at the end of replay, independent of
  per-step locator success, per the glossary's point about not conflating
  "the click worked" with "we're actually where we think we are."
- **`status: DRAFT|APPROVED|DEPRECATED`** gates unattended execution of
  irreversible steps (see §6). Versioning is a bare `int` for now — enough
  to support superseding a recording without deleting history; a real
  approval workflow (stretch goal) is out of scope here.

## 3. Determinism & error handling

Replay is deterministic in the strict sense the spec means: same recorded
steps, same substituted inputs, no model call anywhere in `ReplayEngine`.
The only two sources of legitimate variability are (a) which locator
strategy resolves, logged explicitly, and (b) which of three `OutcomeType`
branches a run lands on.

The three-way split (`ReplayResult.outcomeType`):

- **SUCCESS** — checkpoint verified, typed outputs returned.
- **BUSINESS_OUTCOME** — a known, legitimate non-success page condition
  (`MEMBER_NOT_FOUND`, `VALIDATION_ERROR`). Detected via a small, named
  `Pattern` list checked against page text *before* anything is called a
  failure — Section 3.3's central warning ("no such member" is not a crash)
  taken literally. Verified: replaying against member `99999` returns
  `BUSINESS_OUTCOME/MEMBER_NOT_FOUND`; replaying the sub-account flow with a
  $5 deposit returns `BUSINESS_OUTCOME/VALIDATION_ERROR`.
- **HARD_FAILURE** — carries `failedStepId`, `expected`, `observed`
  (a page-text snippet), and `message`, always paired with a DOM snapshot on
  disk. Triggered by: a locator never resolving, a blocked action, a missing
  required input, an un-approved irreversible step, or a checkpoint that
  isn't met after all steps ran.
- **RECOVERABLE**, a sub-case handled *transparently* inside SUCCESS/
  BUSINESS_OUTCOME/HARD_FAILURE rather than its own outcome type: after each
  step, the resulting page is checked for a known transient marker
  (`SYSTEM_BUSY`). If found, the *same step* is re-run (bounded, 2 retries)
  — re-submitting a search is exactly what a human would do — and the retry
  is appended to `recoveredEvents`, invisible to the caller if it clears.
  Exhausting retries becomes a `HARD_FAILURE`. Verified: replaying member
  `50000` recovers from one `SYSTEM_BUSY` interstitial and returns
  `SUCCESS` with the retry logged.

One correctness bug worth naming because it shaped the design: my first
recovery implementation re-ran the step that *failed to find a locator*
rather than the step that *produced* the busy page — for this app the busy
condition surfaces on the response to the search submission itself, one step
before the failure appears. Fixed by checking every step's resulting page
for known conditions immediately after it runs, not only reactively when a
later locator resolution throws.

## 4. Heterogeneity & multi-tenant

**Surface abstraction.** The seam is `DomActuator`/`SimpleBrowser` behind
`LocatorSpec`+`Step` — the artifact schema doesn't know or care how a
strategy gets resolved. Extending to a JS-heavy legacy web app or a desktop
app means implementing a new actuator (a Playwright-backed one for
JS-rendered surfaces; an OS-accessibility-tree-backed one for desktop) that
resolves the *same* `LocatorSpec` shape — `TEST_ID`/`VISIBLE_TEXT_OR_VALUE`/
`FIELD_NAME_ATTR`/`POSITIONAL_CSS` map naturally onto a real accessibility
role+name, a Windows UI Automation `AutomationId`, etc. `Recorder` and
`ReplayEngine` would not change.

**Multi-tenant reuse.** `Capability.appTarget` cleanly separates *what to do*
(steps, locators, checkpoint — tenant-agnostic once `appTarget.tenantId` is
`"base"`) from *where* (`baseUrl`, `tenantId`). Pointing a base capability at
a second tenant is a one-field change — verified directly: I replayed the
same recorded artifact against a second server instance on a different port
and it worked unmodified once the NAVIGATE-portability bug (above) was
fixed. For a tenant whose branded instance of the same vendor product has
diverged slightly (a relabeled button, a moved field), the fallback chain
already absorbs small drift for free; for a bigger divergence, the natural
extension (not built here) is a **per-tenant override layer**: a thin
`Map<tenantId, List<StepOverride>>` keyed by `stepId` that patches specific
`LocatorSpec`s without re-recording the whole capability, plus a
`locator_fallback_used` counter (already logged per replay) aggregated over
many runs as the drift-detection signal — a strategy that fires its fallback
consistently across many replays for one tenant is exactly the "this tenant
has drifted" alarm, without needing a screenshot-diffing pipeline.

## 5. Escalation & handoff

`EscalationManager.escalate(...)` is the seam: it takes the **same
`SimpleBrowser` instance** — same cookies, same in-memory document state,
not a fresh session — hands it to a minimal stdin operator console
(`show`/`click <idx>`/`type <idx> <text>`/`resume`/`abandon`), and blocks the
calling thread until the operator types `resume` or `abandon`. Automation
picks up exactly where it left off on `resume`. A DOM snapshot and full
context (goal/capability id, current step, reason) are written to
`/evidence/` the moment escalation is raised.

Two real triggers are wired in: an irreversible click during discovery logs
an explicit decision point (see §6), and every escalation reason
(`DISCOVERY_STUCK`, `REPLAY_UNRECOVERABLE`, `IRREVERSIBLE_CONFIRMATION`,
`GUARDRAIL_BLOCK`) is a real enum value carried on `InterventionRequest`,
not a placeholder string.

**What's mocked, deliberately:** the "operator console" is stdin/stdout, not
a UI, and there's no real-time co-browsing (explicitly out of scope per the
assignment). What's *not* mocked: the control-transfer model itself — pause,
hand over the live session object, resume — is exactly how a real operator
console would attach, just with a CLI instead of a browser window rendering
the same session.

## 6. Safety

Two independent gates, both re-checked at **replay** time, not just trusted
from discovery (`Guardrails`):

1. **Allowlist** — `isUrlAllowed` (regex over the tenant's `baseUrl`) and
   `isActionTypeAllowed` (action-type whitelist). A replay whose step
   requests a disallowed action type is a `HARD_FAILURE` before anything
   executes.
2. **Risk gating** — every step carries a `RiskLevel`
   (`SAFE`/`REVERSIBLE`/`IRREVERSIBLE`), classified at record time by
   keyword-matching the clicked control's visible label ("confirm", "open
   account", "delete", "transfer", ...). An `IRREVERSIBLE` step **cannot
   replay unattended unless the capability's `status` is `APPROVED`** —
   verified directly: replaying the sub-account-opening capability while
   `DRAFT` returns `HARD_FAILURE` ("requires an APPROVED capability");
   replaying the identical artifact with `--approve` succeeds. This is the
   stand-in for a real review workflow (a human promotes DRAFT→APPROVED
   after reading the step list) without building the workflow itself.

**Data handling** (`Redactor`): field values are checked against a
sensitive-field-name pattern (`password|token|ssn|pin|cvv|api[_-]?key|
account[_-]?number`) *before* a `TYPE` step's value is persisted into the
artifact — a credential typed during discovery is never written to disk in
the first place, not merely masked afterward. Free-text log fields (LLM
reasoning, extracted values) are separately scrubbed for SSN/card-number
patterns as a second line of defense against a model echoing something
sensitive into its own reasoning trace.

**Limits, honestly:** risk classification is a keyword heuristic on visible
text, not a semantic understanding of the action — a real deployment would
want this sourced from a per-app policy config (which button labels are
irreversible for *this* vendor product) rather than inferred generically.

## 7. Cuts

- **A real rendered-browser engine.** Swapped for jsoup+HTTP DOM automation
  because I could not verify a Playwright build in my environment (see §1).
  The `LocatorSpec`/`Capability` schema was designed so a Playwright-backed
  actuator is a bounded, additive change, not a rewrite.
- **Genuine LLM discovery run.** Requires an API key I was not given for
  this exercise; `AnthropicLlmClient` is fully implemented and wired behind
  the same `LlmClient` interface the scripted stand-in uses, so producing
  the required real evidence is a matter of `ANTHROPIC_API_KEY=... mvn
  exec:java ... discover ...` — see README for the exact command. Every
  other requirement was exercised end-to-end with the scripted client
  driving the identical agent loop, recorder, replay engine, and guardrails.
- **Desktop/OS-automation surface.** Designed for (§4), not built, per the
  assignment's explicit scope note.
- **Confidence scoring / multi-run stability signal.** `locator_fallback_used`
  events are already logged per replay; aggregating them into a
  per-capability confidence score is the natural next step, not built.
- **A real operator UI.** CLI stdin console instead of a co-browsing surface,
  per the assignment's explicit scope note.
- **Nested (>1 level) frame chains.** `LocatorSpec.frameChain` supports one
  level, matching this target; deeper nesting is a straightforward list
  extension, not a schema change.
- **Next with more time:** a per-tenant override layer (§4), a real
  Playwright actuator behind the existing interface, and turning the
  irreversible-during-discovery decision point into an actual blocking
  `EscalationManager.escalate()` call instead of a logged note.
