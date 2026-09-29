package org.vmstudio.visorhandinteractions.core.client.interaction;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/** Optional resolver for moving contraptions or foreign coordinate spaces. */
public interface InteractionBridge {
    @NotNull Optional<InteractionTarget> findTarget(
            @NotNull ClientLevel level,
            @NotNull LocalPlayer player,
            @NotNull Vec3 sweepStart,
            @NotNull Vec3 sweepEnd,
            double radius
    );
}

