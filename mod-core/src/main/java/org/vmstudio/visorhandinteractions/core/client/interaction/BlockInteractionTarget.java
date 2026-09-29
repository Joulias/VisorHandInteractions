package org.vmstudio.visorhandinteractions.core.client.interaction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.vmstudio.visor.api.common.HandType;

public record BlockInteractionTarget(
        @NotNull BlockPos blockPos,
        @NotNull BlockHitResult hitResult,
        double sweepFraction
) implements InteractionTarget {
    @Override
    public @NotNull Object key() {
        return blockPos;
    }

    @Override
    public @NotNull Vec3 worldLocation() {
        return hitResult.getLocation();
    }

    @Override
    public boolean isBackedBy(@NotNull BlockPos worldBlockPos) {
        return blockPos.equals(worldBlockPos);
    }

    @Override
    public @NotNull InteractionResult touch(
            @NotNull LocalPlayer player,
            @NotNull HandType hand
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode == null) {
            return InteractionResult.PASS;
        }
        return minecraft.gameMode.useItemOn(
                player,
                hand.asInteractionHand(),
                hitResult
        );
    }
}

