package ai.interfaceai.cua.model;

/**
 * A condition asserted to confirm the flow actually reached the state it
 * claims to have reached, rather than assuming the last click worked.
 * Evaluated at the end of a successful replay before outputs are returned.
 */
public record Checkpoint(
        Enums.CheckpointKind kind,
        String expectedValue,   // URL substring, or expected element text
        LocatorSpec target,     // null when kind == URL_CONTAINS
        String description
) {}
