package org.vmstudio.visorhandinteractions.core.client.interaction;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.vmstudio.visor.api.common.HandType;

/** A world-space physical target supplied by the vanilla or a compatibility resolver. */
public interface InteractionTarget {
    @NotNull Object key();

    @NotNull Vec3 worldLocation();

    /** Parametric distance along the current hand sweep, where 0 is its start. */
    double sweepFraction();

    default boolean allowTouch() {
        return true;
    }

    /** Whether this claim may be selected as a dedicated grab target. */
    default boolean allowGrab() {
        return true;
    }

    /**
     * Whether this target can satisfy the grab safety policy while the
     * interacting hand holds an item. The default preserves empty-hand-only
     * grabbing when that policy is enabled.
     */
    default boolean allowsNonEmptyHandGrab(
            @NotNull LocalPlayer player,
            @NotNull HandType hand
    ) {
        return false;
    }

    /**
     * Whether beginGrab only captures local state and can safely bypass the
     * interaction cooldown without activating the underlying control.
     */
    default boolean beginGrabIsPassive() {
        return false;
    }

    default boolean isBackedBy(@NotNull BlockPos worldBlockPos) {
        return false;
    }

    @NotNull InteractionResult touch(@NotNull LocalPlayer player, @NotNull HandType hand);

    default @NotNull InteractionResult beginGrab(
            @NotNull LocalPlayer player,
            @NotNull HandType hand,
            @NotNull Vec3 handPosition
    ) {
        return touch(player, hand);
    }

    /** Continuous targets must explicitly opt into off-contact held updates. */
    default boolean canContinueGrab(
            @NotNull LocalPlayer player,
            @NotNull HandType hand
    ) {
        return false;
    }

    default void continueGrab(
            @NotNull LocalPlayer player,
            @NotNull HandType hand,
            @NotNull Vec3 handPosition,
            @NotNull Vec3 handDelta
    ) {
    }

    default void endGrab(@NotNull LocalPlayer player, @NotNull HandType hand) {
    }

    /**
     * Tears down a grab that did not end through a deliberate grab-action
     * release. Targets whose release has no commit semantics may use the
     * default; controls such as assembly levers can override this to cancel
     * without activating their native release action.
     */
    default void cancelGrab(@NotNull LocalPlayer player, @NotNull HandType hand) {
        endGrab(player, hand);
    }
}

