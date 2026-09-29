package org.vmstudio.visorhandinteractions.loader.neoforge.compat.aeroworks;

import com.mred231.aeroworks.content.controls.C2SConsoleChannel;
import com.mred231.aeroworks.content.controls.ConsoleBlock;
import com.mred231.aeroworks.content.controls.ConsoleControlClient;
import com.mred231.aeroworks.content.joystick.JoystickBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.vmstudio.visor.api.common.HandType;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionTarget;
import org.vmstudio.visorhandinteractions.core.client.pose.GrabPose;
import org.vmstudio.visorhandinteractions.core.client.pose.GrabPoseProvider;

/** Owns one physical VR grab and maps its motion onto Aeroworks' joystick channels. */
final class AeroworksJoystickTarget implements InteractionTarget, GrabPoseProvider {
    private static final int MINIMUM_VALUE = -15;
    private static final int MAXIMUM_VALUE = 15;
    private static final double MINIMUM_HANDLE_HEIGHT = 0.05D;

    private static @Nullable AeroworksJoystickTarget activeOwner;

    private final ClientLevel level;
    private final BlockPos blockPos;
    private final Vec3 modelGrabPoint;
    private final Vec3 initialWorldLocation;
    private final double sweepFraction;

    private boolean ownsSession;
    private boolean dragInitialized;
    private int initialValueX;
    private int initialValueY;
    private double initialAngleX;
    private double initialAngleY;

    AeroworksJoystickTarget(
            @NotNull ClientLevel level,
            @NotNull BlockPos blockPos,
            @NotNull Vec3 modelGrabPoint,
            @NotNull Vec3 initialWorldLocation,
            double sweepFraction
    ) {
        this.level = level;
        this.blockPos = blockPos.immutable();
        this.modelGrabPoint = modelGrabPoint;
        this.initialWorldLocation = initialWorldLocation;
        this.sweepFraction = sweepFraction;
    }

    @Override
    public @NotNull Object key() {
        return new ControlKey(level.dimension(), blockPos);
    }

    @Override
    public @NotNull Vec3 worldLocation() {
        return initialWorldLocation;
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
        JoystickBlockEntity joystick = currentJoystick();
        if (minecraft.level != level
                || minecraft.gameMode == null
                || joystick == null
                || player.level() != level
                || !player.getMainHandItem().isEmpty()
                || player.isShiftKeyDown()) {
            return InteractionResult.FAIL;
        }

        // Aeroworks has one global client console session. Never steal an
        // unrelated keyboard/mouse session merely because a hand touched this grip.
        if (ConsoleControlClient.isActive() && !joystick.checkUser(player.getUUID())) {
            return InteractionResult.FAIL;
        }
        if (!claimOwnership(this)) {
            return InteractionResult.FAIL;
        }

        Direction face = ConsoleBlock.isCeiling(joystick.getBlockState())
                ? Direction.DOWN
                : Direction.UP;
        InteractionResult result = minecraft.gameMode.useItemOn(
                player,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(
                        initialWorldLocation,
                        face,
                        blockPos,
                        false
                )
        );
        joystick = currentJoystick();
        if (!result.consumesAction()
                || joystick == null
                || !joystick.checkUser(player.getUUID())
                || !ConsoleControlClient.isActive()) {
            abandonOwnership();
            return InteractionResult.FAIL;
        }

        ownsSession = true;
        initialValueX = Mth.clamp(joystick.getTiltX(), MINIMUM_VALUE, MAXIMUM_VALUE);
        initialValueY = Mth.clamp(joystick.getTiltY(), MINIMUM_VALUE, MAXIMUM_VALUE);
        initializeDrag(joystick, handPosition);
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean canContinueGrab(
            @NotNull LocalPlayer player,
            @NotNull HandType hand
    ) {
        JoystickBlockEntity joystick = currentJoystick();
        boolean live = ownsSession
                && isOwner(this)
                && Minecraft.getInstance().level == level
                && player.level() == level
                && joystick != null
                && joystick.checkUser(player.getUUID())
                && ConsoleControlClient.isActive();
        return live;
    }

    @Override
    public void continueGrab(
            @NotNull LocalPlayer player,
            @NotNull HandType hand,
            @NotNull Vec3 handPosition,
            @NotNull Vec3 handDelta
    ) {
        JoystickBlockEntity joystick = currentJoystick();
        if (joystick == null || !ownsSession || !isOwner(this)) {
            return;
        }
        if (!dragInitialized) {
            initializeDrag(joystick, handPosition);
        }

        AeroworksJoystickGeometry.GripFrame frame =
                AeroworksJoystickGeometry.gripFrame(joystick);
        if (frame == null) {
            return;
        }
        Vec3 localHand = frame.toBase(handPosition);
        Vec3 fromPivot = localHand.subtract(AeroworksJoystickGeometry.PEDESTAL_PIVOT);
        if (fromPivot.y < MINIMUM_HANDLE_HEIGHT) {
            return;
        }

        double angleX = Math.atan2(fromPivot.x, fromPivot.y);
        double angleY = Math.atan2(fromPivot.z, fromPivot.y);
        int targetX = Mth.clamp(
                initialValueX + (int) Math.round(Math.toDegrees(
                        wrappedAngle(angleX - initialAngleX)
                ) / AeroworksJoystickGeometry.DEGREES_PER_STEP),
                MINIMUM_VALUE,
                MAXIMUM_VALUE
        );
        int targetY = Mth.clamp(
                initialValueY + (int) Math.round(Math.toDegrees(
                        wrappedAngle(angleY - initialAngleY)
                ) / AeroworksJoystickGeometry.DEGREES_PER_STEP),
                MINIMUM_VALUE,
                MAXIMUM_VALUE
        );
        applyValues(joystick, targetX, targetY);
    }

    @Override
    public void endGrab(@NotNull LocalPlayer player, @NotNull HandType hand) {
        if (!ownsSession || !isOwner(this)) {
            abandonOwnership();
            return;
        }

        JoystickBlockEntity joystick = currentJoystick();
        try {
            if (joystick != null
                    && joystick.checkUser(player.getUUID())
                    && joystick.isSpringBack()) {
                sendValue("x", 0);
                sendValue("y", 0);
                updateClientAnimation(joystick, 0, 0);
            }
        } finally {
            try {
                if (ConsoleControlClient.isActive()) {
                    ConsoleControlClient.requestExit();
                }
            } finally {
                abandonOwnership();
            }
        }
    }

    @Override
    public void cancelGrab(@NotNull LocalPlayer player, @NotNull HandType hand) {
        boolean ownsLiveSession = isOwner(this)
                && ConsoleControlClient.isActive()
                && (ownsSession || isCurrentJoystickUser(player));
        try {
            if (ownsLiveSession) {
                ConsoleControlClient.requestExit();
            }
        } finally {
            abandonOwnership();
        }
    }

    @Override
    public @NotNull GrabPose grabPose(
            @NotNull LocalPlayer player,
            @NotNull HandType hand,
            @NotNull Vec3 trackedHandAnchor
    ) {
        JoystickBlockEntity joystick = currentJoystick();
        AeroworksJoystickGeometry.GripFrame frame = joystick == null
                ? null
                : AeroworksJoystickGeometry.gripFrame(joystick);
        if (frame == null) {
            return GrabPose.anchoredAt(initialWorldLocation);
        }

        Vec3 worldAnchor = frame.toWorld(modelGrabPoint);
        Vec3 handleAxis = unitOrFallback(
                frame.modelDirectionToWorld(new Vec3(0.0D, 1.0D, 0.0D)),
                new Vec3(0.0D, 1.0D, 0.0D)
        );
        Vec3 forwardHint = unitOrFallback(
                frame.modelDirectionToWorld(new Vec3(0.0D, 0.0D, 1.0D)),
                new Vec3(0.0D, 0.0D, 1.0D)
        );
        return GrabPose.attachedTo(
                worldAnchor,
                GrabPose.frameFromAxes(handleAxis, forwardHint)
        );
    }

    private void initializeDrag(
            @NotNull JoystickBlockEntity joystick,
            @NotNull Vec3 handPosition
    ) {
        AeroworksJoystickGeometry.GripFrame frame =
                AeroworksJoystickGeometry.gripFrame(joystick);
        if (frame == null) {
            return;
        }
        Vec3 fromPivot = frame.toBase(handPosition)
                .subtract(AeroworksJoystickGeometry.PEDESTAL_PIVOT);
        if (fromPivot.y < MINIMUM_HANDLE_HEIGHT) {
            return;
        }
        initialAngleX = Math.atan2(fromPivot.x, fromPivot.y);
        initialAngleY = Math.atan2(fromPivot.z, fromPivot.y);
        dragInitialized = true;
    }

    private void applyValues(
            @NotNull JoystickBlockEntity joystick,
            int x,
            int y
    ) {
        // ConsoleBlockEntity#setChannelFromController is intentionally
        // server-only. Updating the public mounted-module values locally feeds
        // Aeroworks' normal channel lerps while the authoritative packet makes
        // the same change on the server.
        updateClientAnimation(joystick, x, y);
        // Aeroworks' own key/spring-back controller remains active during the
        // console session. Reassert both physical axes each held tick so that
        // its idle spring cannot overwrite an unmoving VR hand on the server.
        sendValue("x", x);
        sendValue("y", y);
    }

    private void sendValue(@NotNull String channel, int value) {
        PacketDistributor.sendToServer(new C2SConsoleChannel(
                blockPos,
                (byte) 0,
                channel,
                (byte) Mth.clamp(value, MINIMUM_VALUE, MAXIMUM_VALUE)
        ));
    }

    private static void updateClientAnimation(
            @NotNull JoystickBlockEntity joystick,
            int x,
            int y
    ) {
        var module = joystick.module(0);
        if (module == null) {
            return;
        }
        module.setValue("x", Mth.clamp(x, MINIMUM_VALUE, MAXIMUM_VALUE));
        module.setValue("y", Mth.clamp(y, MINIMUM_VALUE, MAXIMUM_VALUE));
    }

    private @Nullable JoystickBlockEntity currentJoystick() {
        BlockEntity blockEntity = level.getBlockEntity(blockPos);
        return blockEntity instanceof JoystickBlockEntity joystick
                ? joystick
                : null;
    }

    private boolean isCurrentJoystickUser(LocalPlayer player) {
        JoystickBlockEntity joystick = currentJoystick();
        return joystick != null && joystick.checkUser(player.getUUID());
    }

    private boolean stillOwnsLiveSession() {
        Minecraft minecraft = Minecraft.getInstance();
        JoystickBlockEntity joystick = currentJoystick();
        return ownsSession
                && minecraft.level == level
                && minecraft.player != null
                && joystick != null
                && joystick.checkUser(minecraft.player.getUUID())
                && ConsoleControlClient.isActive();
    }

    private void abandonOwnership() {
        ownsSession = false;
        dragInitialized = false;
        clearOwnership(this);
    }

    private static synchronized boolean claimOwnership(
            AeroworksJoystickTarget candidate
    ) {
        if (activeOwner != null && !activeOwner.stillOwnsLiveSession()) {
            activeOwner.ownsSession = false;
            activeOwner = null;
        }
        if (activeOwner != null && activeOwner != candidate) {
            return false;
        }
        activeOwner = candidate;
        return true;
    }

    private static synchronized boolean isOwner(AeroworksJoystickTarget candidate) {
        return activeOwner == candidate;
    }

    private static synchronized void clearOwnership(
            AeroworksJoystickTarget candidate
    ) {
        if (activeOwner == candidate) {
            activeOwner = null;
        }
    }

    private static double wrappedAngle(double radians) {
        return Math.atan2(Math.sin(radians), Math.cos(radians));
    }

    private static @NotNull Vec3 unitOrFallback(Vec3 vector, Vec3 fallback) {
        return vector.lengthSqr() < 1.0E-12D ? fallback : vector.normalize();
    }

    private record ControlKey(ResourceKey<Level> dimension, BlockPos blockPos) {
    }
}
