package org.vmstudio.visorhandinteractions.loader.neoforge.compat.create;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.NotNull;

/**
 * Adds physical train-lever intent to Create's normal control-key poll.
 *
 * <p>Create remains responsible for packet cadence, pressed-key transitions,
 * keepalives, and lever animation. The optional mixin only ORs these four
 * virtual directions into the key check inside {@code ControlsHandler.tick}
 * while the exact VR grab owns the active train-control session.</p>
 */
public final class TrainControlsInputOverride {
    private static HeldCreateContraptionTarget owner;
    private static boolean forward;
    private static boolean backward;
    private static boolean left;
    private static boolean right;

    private TrainControlsInputOverride() {
        throw new UnsupportedOperationException("Utility class");
    }

    static synchronized boolean claim(
            @NotNull HeldCreateContraptionTarget candidate
    ) {
        if (owner != null && owner != candidate) {
            if (owner.hasLiveTrainOverrideSession()) {
                return false;
            }
            // Recover from a target abandoned by an external Create session
            // teardown before Visor's normal cancellation tick reached it.
            clearDirections();
            owner = null;
        }
        owner = candidate;
        clearDirections();
        return true;
    }

    static synchronized boolean isOwner(
            @NotNull HeldCreateContraptionTarget candidate
    ) {
        return owner == candidate;
    }

    static synchronized void update(
            @NotNull HeldCreateContraptionTarget candidate,
            boolean nextForward,
            boolean nextBackward,
            boolean nextLeft,
            boolean nextRight
    ) {
        if (owner != candidate) {
            return;
        }
        forward = nextForward;
        backward = nextBackward;
        left = nextLeft;
        right = nextRight;
    }

    static synchronized void release(
            @NotNull HeldCreateContraptionTarget candidate
    ) {
        if (owner != candidate) {
            return;
        }
        clearDirections();
        owner = null;
    }

    /** Called from the optional Create mixin; false leaves native input alone. */
    public static synchronized boolean isVirtuallyPressed(
            @NotNull KeyMapping mapping
    ) {
        if (owner == null) {
            return false;
        }
        var options = Minecraft.getInstance().options;
        return (forward && mapping == options.keyUp)
                || (backward && mapping == options.keyDown)
                || (left && mapping == options.keyLeft)
                || (right && mapping == options.keyRight);
    }

    private static void clearDirections() {
        forward = false;
        backward = false;
        left = false;
        right = false;
    }
}
