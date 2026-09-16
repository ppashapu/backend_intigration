package ai.interfaceai.cua.model;

/** Typed contract entry for a capability's inputs or outputs. */
public record ParamSpec(
        String name,
        Enums.ParamType type,
        boolean required,
        String description,
        String example
) {}
