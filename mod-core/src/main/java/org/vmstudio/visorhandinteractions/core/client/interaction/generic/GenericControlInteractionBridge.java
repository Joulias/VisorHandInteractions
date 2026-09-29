package org.vmstudio.visorhandinteractions.core.client.interaction.generic;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.vmstudio.visorhandinteractions.core.client.interaction.ExactHandContactFinder;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionBridge;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionTarget;

import java.util.Optional;

/** Finds recognized stationary controls and upgrades them to held targets. */
public final class GenericControlInteractionBridge implements InteractionBridge {
    @Override
    public @NotNull Optional<InteractionTarget> findTarget(
            @NotNull ClientLevel level,
            @NotNull LocalPlayer player,
            @NotNull Vec3 sweepStart,
            @NotNull Vec3 sweepEnd,
            double radius
    ) {
        return ExactHandContactFinder.findNearest(
                level,
                player,
                sweepStart,
                sweepEnd,
                Math.max(0.0D, radius),
                (pos, state) -> GenericControlClassifier.recognizes(state)
        ).flatMap(contact -> {
            BlockState state = level.getBlockState(contact.blockPos());
            return GenericControlClassifier.describe(
                    level,
                    player,
                    contact.blockPos(),
                    state,
                    contact.hitResult()
            ).map(descriptor -> new GenericControlTarget(
                    level,
                    descriptor,
                    contact.hitResult(),
                    contact.sweepFraction()
            ));
        }).map(target -> target);
    }
}
