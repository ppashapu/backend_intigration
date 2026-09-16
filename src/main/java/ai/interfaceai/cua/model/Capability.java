package ai.interfaceai.cua.model;

import java.util.List;

/**
 * A CAPABILITY is the reusable artifact this whole system exists to produce:
 * a typed, versioned, agent-invocable description of "how to accomplish goal X
 * on app Y", decoupled from the raw LLM transcript that discovered it.
 *
 * Design goals (see REPORT.md #2 for the full rationale):
 *  - Callable like a function: named inputs in, typed outputs out, a checkpoint
 *    that proves success. An AI agent (or this project's own ReplayEngine)
 *    should be able to invoke it without knowing anything about the UI.
 *  - Reviewable: a human can read `steps` + `description` fields and understand
 *    exactly what will happen before approving it for unattended use.
 *  - Portable across tenants: `appTarget` is separated from `steps` precisely so
 *    the same capability can be pointed at a different tenant's instance of the
 *    same vendor product (see REPORT.md #4). Steps use relative locators/paths,
 *    never hardcoded absolute URLs beyond the declared baseUrl.
 *  - Versioned: `version` + `status` (DRAFT/APPROVED/DEPRECATED) let a new
 *    recording supersede an old one without deleting the audit trail.
 */
public record Capability(
        String id,                       // stable slug, e.g. "lookup-member-balance"
        int version,
        Enums.ArtifactStatus status,
        String name,
        String goalDescription,          // the natural-language goal this was recorded for
        AppTarget appTarget,
        List<ParamSpec> inputParams,
        List<ParamSpec> outputs,
        List<Step> steps,
        Checkpoint checkpoint,
        Enums.RiskLevel overallRisk,     // max risk across steps; surfaced for reviewers at a glance
        String sourceDiscoveryRunId,     // links back to /evidence/ for the run that produced this
        String createdAt                 // ISO-8601 instant string
) {
    public record AppTarget(
            String appId,           // logical app identity, stable across tenants
            String vendorProduct,   // e.g. "legacy-core-banking-v1" — shared across tenants running it
            String baseUrl,         // THIS tenant's instance; everything else in Capability is tenant-agnostic
            String tenantId         // null/"base" for a tenant-agnostic recording
    ) {}
}
