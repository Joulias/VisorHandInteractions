package org.vmstudio.visorhandinteractions.loader.neoforge.compat.create;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import org.jetbrains.annotations.NotNull;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.visor.api.common.HandType;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionTarget;

/** One static Create value box selected by physical controller contact. */
final class CreateValueSettingsTarget implements InteractionTarget {
    private final SmartBlockEntity blockEntity;
    private final ValueSettingsBehaviour behaviour;
    private final BlockHitResult hitResult;
    private final Vec3 valueBoxCenter;
    private final double sweepFraction;
    private final TargetKey key;

    CreateValueSettingsTarget(
            SmartBlockEntity blockEntity,
            ValueSettingsBehaviour behaviour,
            BlockHitResult hitResult,
            Vec3 valueBoxCenter,
            double sweepFraction
    ) {
        this.blockEntity = blockEntity;
        this.behaviour = behaviour;
        this.hitResult = hitResult;
        this.valueBoxCenter = valueBoxCenter;
        this.sweepFraction = sweepFraction;
        this.key = new TargetKey(
                blockEntity.getBlockPos(),
                behaviour.netId(),
                behaviour.getClass().getName(),
                hitResult.getDirection()
        );
    }

    @Override
    public @NotNull Object key() {
        return key;
    }

    @Override
    public @NotNull Vec3 worldLocation() {
        return valueBoxCenter;
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
    public boolean allowsNonEmptyHandGrab(
            @NotNull LocalPlayer player,
            @NotNull HandType hand
    ) {
        return behaviour.onlyVisibleWithWrench()
                && player.getItemInHand(hand.asInteractionHand())
                        .is(Tags.Items.TOOLS_WRENCH);
    }

    @Override
    public boolean isBackedBy(@NotNull BlockPos worldBlockPos) {
        return blockEntity.getBlockPos().equals(worldBlockPos);
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
        ClientLevel level = minecraft.level;
        if (minecraft.screen != null
                || level == null
                || minecraft.player != player
                || !CreateValueSettingsInteractionBridge.canOpen(
                        level,
                        player,
                        blockEntity,
                        behaviour,
                        hand,
                        hitResult.getDirection(),
                        hitResult.getLocation()
                )) {
            return InteractionResult.PASS;
        }

        ValueBoxTransform transform = behaviour.getSlotPositioning();
        if (transform instanceof ValueBoxTransform.Sided sided) {
            sided.fromSide(hitResult.getDirection());
        }

        ValueSettingsBoard board = behaviour.createBoard(player, hitResult);
        if (board == null) {
            return InteractionResult.PASS;
        }

        VRValueSettingsScreen screen = new VRValueSettingsScreen(
                blockEntity.getBlockPos(),
                board,
                behaviour.getValueSettings(),
                behaviour::newSettingHovered,
                behaviour.netId(),
                () -> minecraft.level == level
                        && minecraft.player == player
                        && CreateValueSettingsInteractionBridge.remainsValid(
                        level,
                        player,
                        blockEntity,
                        behaviour
                )
        );

        VisorAPI.client()
                .getGuiManager()
                .getCursorHandler()
                .setCursorHand(hand);
        minecraft.setScreen(screen);
        return InteractionResult.SUCCESS;
    }

    private record TargetKey(
            BlockPos blockPos,
            int behaviourNetId,
            String behaviourClass,
            Direction side
    ) {
    }
}
