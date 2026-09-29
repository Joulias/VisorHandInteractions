package org.vmstudio.visorhandinteractions.core.client.interaction.generic;

/**
 * Interaction semantics that can be inferred without linking against another
 * mod's classes. Native-hold controls are recognized so the generic bridge can
 * yield to a dedicated adapter without accidentally leaving a foreign
 * right-click hold active.
 */
public enum GenericControlKind {
    BINARY_LEVER(true, true, false),
    STEPPED_LEVER(true, true, false),
    DETENTED_ROTARY(true, true, true),
    LINEAR_PRESS(true, true, false),
    NATIVE_HOLD_LEVER(false, false, false),
    NATIVE_HOLD_ROTARY(false, false, true),
    NATIVE_HOLD_HANDLE(false, false, false);

    private final boolean genericManipulationSafe;
    private final boolean touchSafe;
    private final boolean rotary;

    GenericControlKind(
            boolean genericManipulationSafe,
            boolean touchSafe,
            boolean rotary
    ) {
        this.genericManipulationSafe = genericManipulationSafe;
        this.touchSafe = touchSafe;
        this.rotary = rotary;
    }

    public boolean genericManipulationSafe() {
        return genericManipulationSafe;
    }

    public boolean touchSafe() {
        return touchSafe;
    }

    public boolean rotary() {
        return rotary;
    }

    public boolean linearPress() {
        return this == LINEAR_PRESS;
    }
}
