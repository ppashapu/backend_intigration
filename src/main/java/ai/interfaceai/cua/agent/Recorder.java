package ai.interfaceai.cua.agent;

import ai.interfaceai.cua.model.*;

import java.time.Instant;
import java.util.*;

/**
 * Converts the raw discovery transcript (a list of Steps as executed, with
 * literal values) into the reusable, parameterized Capability artifact --
 * "decoupled from the raw model transcript" (Section 3.2).
 *
 * Parametrization strategy: the caller declares which input values are
 * PARAMETERS up front (e.g. memberId=12345), the same way you'd say "this
 * run is an example invocation with these arguments". Any literal Step.value
 * that exactly matches a declared parameter's example value is rewritten to
 * a {{paramName}} template. This is a deliberately explicit, unambiguous
 * approach -- inferring which literals are "supposed to vary" from a single
 * transcript alone is not reliable, so this project doesn't guess.
 */
public class Recorder {

    public Capability buildCapability(
            String id, String name, String goalDescription,
            Capability.AppTarget appTarget,
            List<Step> rawSteps,
            Map<String, ParamSpec> declaredParams,   // name -> spec (spec.example() is the value used this run)
            List<String> extractedOutputNames,
            Checkpoint checkpoint,
            String sourceDiscoveryRunId
    ) {
        List<Step> templatized = templatize(rawSteps, declaredParams);

        List<ParamSpec> outputs = extractedOutputNames.stream()
                .map(n -> new ParamSpec(n, Enums.ParamType.STRING, true, "Extracted value: " + n, null))
                .toList();

        Enums.RiskLevel overall = templatized.stream()
                .map(Step::riskLevel)
                .max(Comparator.comparingInt(Recorder::riskRank))
                .orElse(Enums.RiskLevel.SAFE);

        return new Capability(
                id, 1, Enums.ArtifactStatus.DRAFT, name, goalDescription, appTarget,
                new ArrayList<>(declaredParams.values()), outputs, templatized, checkpoint,
                overall, sourceDiscoveryRunId, Instant.now().toString()
        );
    }

    private List<Step> templatize(List<Step> steps, Map<String, ParamSpec> params) {
        List<Step> out = new ArrayList<>();
        for (Step s : steps) {
            String value = s.value();
            if (value != null) {
                for (ParamSpec p : params.values()) {
                    if (p.example() != null && p.example().equals(value)) {
                        value = "{{" + p.name() + "}}";
                        break;
                    }
                }
            }
            out.add(new Step(s.stepId(), s.action(), s.description(), s.target(), value,
                    s.extractAs(), s.riskLevel(), s.timeoutMs()));
        }
        return out;
    }

    private static int riskRank(Enums.RiskLevel r) {
        return switch (r) { case SAFE -> 0; case REVERSIBLE -> 1; case IRREVERSIBLE -> 2; };
    }
}
