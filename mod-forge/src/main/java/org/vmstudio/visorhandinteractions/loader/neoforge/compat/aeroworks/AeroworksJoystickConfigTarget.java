package org.vmstudio.visorhandinteractions.loader.neoforge.compat.aeroworks;

import com.mred231.aeroworks.content.controls.ConsoleBlock;
import com.mred231.aeroworks.content.joystick.JoystickBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.vmstudio.visor.api.common.HandType;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionTarget;

/** One-shot sneaking grab which opens Aeroworks' native console screen. */
final class AeroworksJoystickConfigTarget implements InteractionTarget {
    private final ClientLevel level;
    private final BlockPos blockPos;
    private final Vec3 worldLocation;
    private final double sweepFraction;

    AeroworksJoystickConfigTarget(
            @NotNull ClientLevel level,
            @NotNull BlockPos blockPos,
            @NotNull Vec3 worldLocation,
            double sweepFraction
    ) {
        this.level = level;
        this.blockPos = blockPos.immutable();
        this.worldLocation = worldLocation;
        this.sweepFraction = sweepFraction;
    }

    @Override
    public @NotNull Object key() {
        return new ConfigKey(level.dimension(), blockPos);
    }

    @Override
    public @NotNull Vec3 worldLocation() {
        return worldLocation;
    }

    @Override
    public double sweepFraction() {
        return sweepFraction;
    }

    @Override
    public boolean allowTouch() {
        return false;
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
        return InteractionResult.PASS;
    }

    @Override
    public @NotNull InteractionResult beginGrab(
            @NotNull LocalPlayer player,
            @NotNull HandType hand,
            @NotNull Vec3 handPosition
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockEntity blockEntity = level.getBlockEntity(blockPos);
        if (minecraft.level != level
                || minecraft.gameMode == null
                || player.level() != level
                || !player.isShiftKeyDown()
                || !player.getMainHandItem().isEmpty()
                || !(blockEntity instanceof JoystickBlockEntity joystick)) {
            return InteractionResult.FAIL;
        }

        Direction face = ConsoleBlock.isCeiling(joystick.getBlockState())
                ? Direction.DOWN
                : Direction.UP;
        return minecraft.gameMode.useItemOn(
                player,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(
                        worldLocation,
                        face,
                        blockPos,
                        false
                )
        );
    }

    private record ConfigKey(ResourceKey<Level> dimension, BlockPos blockPos) {
    }
}
