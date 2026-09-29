package org.vmstudio.visorhandinteractions.loader.neoforge.compat.simulated;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlock;
import dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlockEntity;
import dev.simulated_team.simulated.content.blocks.handle.HandleBlock;
import dev.simulated_team.simulated.content.blocks.handle.HandleBlockEntity;
import dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity;
import dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerGUIHandler;
import dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity;
import dev.simulated_team.simulated.content.blocks.util.AbstractDirectionalAxisBlock;
import dev.simulated_team.simulated.index.SimClickInteractions;
import dev.simulated_team.simulated.util.hold_interaction.BlockHoldInteraction;
import dev.simulated_team.simulated.util.hold_interaction.HoldInteractionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.vmstudio.visor.api.common.HandType;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionTarget;
import org.vmstudio.visorhandinteractions.core.client.pose.GrabPose;
import org.vmstudio.visorhandinteractions.core.client.pose.GrabPoseProvider;

import java.util.UUID;

/** A single-owner physical grab backed by Simulated's public hold handlers. */
final class SimulatedControlTarget implements InteractionTarget, GrabPoseProvider {
    private static final double MAXIMUM_WHEEL_DELTA_DEGREES = 30.0D;
    private static final double MAXIMUM_THROTTLE_DELTA_RADIANS = Math.toRadians(25.0D);

    private static SimulatedControlTarget activeOwner;

    private final ClientLevel level;
    private final BlockPos blockPos;
    private final @Nullable UUID subLevelId;
    private final SimulatedInteractionBridge.ControlKind kind;
    private final Vec3 initialWorldLocation;
    private final double sweepFraction;
    private final ControlKey key;

    private boolean ownsHold;
    private @Nullable Vec3 previousThrottleRadial;
    private @Nullable Vec3 previousWheelRadial;
    private @Nullable Vec3 previousAssemblerRadial;
    private @Nullable Vec3 throttleCanonicalGrabPoint;
    private @Nullable Vec3 assemblerCanonicalGrabPoint;
    private @Nullable Vec3 handleLocalGrabPoint;
    private @Nullable Vec3 wheelModelRadial;
    private double wheelAxialOffset;

    SimulatedControlTarget(
            ClientLevel level,
            BlockPos blockPos,
            @Nullable UUID subLevelId,
            SimulatedInteractionBridge.ControlKind kind,
            Vec3 worldLocation,
            double sweepFraction
    ) {
        this.level = level;
        this.blockPos = blockPos;
        this.subLevelId = subLevelId;
        this.kind = kind;
        this.initialWorldLocation = worldLocation;
        this.sweepFraction = sweepFraction;
        this.key = new ControlKey(level.dimension(), subLevelId, blockPos, kind);
    }

    @Override
    public @NotNull Object key() {
        return key;
    }

    @Override
    public @NotNull Vec3 worldLocation() {
        GrabPose pose = currentGrabPose();
        return pose == null ? initialWorldLocation : pose.worldAnchor();
    }

    @Override
    public double sweepFraction() {
        return ownsHold ? 0.0D : sweepFraction;
    }

    @Override
    public boolean allowTouch() {
        return false;
    }

    @Override
    public boolean beginGrabIsPassive() {
        return true;
    }

    @Override
    public boolean isBackedBy(@NotNull BlockPos worldBlockPos) {
        return subLevelId == null && blockPos.equals(worldBlockPos);
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
        BlockEntity blockEntity = currentControlEntity();
        BlockHoldInteraction handler = handler();
        // A native mouse hold may already own this singleton handler. Never
        // adopt or stop a hold that this addon did not start itself.
        if (blockEntity == null
                || handler == null
                || (kind == SimulatedInteractionBridge.ControlKind.HANDLE
                && !HandleBlock.canInteractWithHandle(player))
                || HoldInteractionManager.isActive()
                || !claimOwnership(this)) {
            return InteractionResult.PASS;
        }

        boolean successful = false;
        boolean startedHere = false;
        try {
            // startHold registers the manager and initializes the exact block.
            // Mark first so a partially failed native start is still cleaned up.
            startedHere = true;
            handler.startHold(level, player, blockPos);
            if (!isExactActiveHold(handler)) {
                return InteractionResult.PASS;
            }

            Vec3 localHand = toLocalPosition(handPosition);
            if (localHand == null) {
                return InteractionResult.PASS;
            }

            ownsHold = true;
            initializeGrabGeometry(blockEntity, localHand);
            successful = true;
            return InteractionResult.SUCCESS;
        } finally {
            if (!successful) {
                if (startedHere) {
                    stopExactHoldIfOwned(handler, false);
                }
                clearOwnership(this);
            }
        }
    }

    @Override
    public void continueGrab(
            @NotNull LocalPlayer player,
            @NotNull HandType hand,
            @NotNull Vec3 handPosition,
            @NotNull Vec3 handDelta
    ) {
        BlockHoldInteraction handler = handler();
        if (!ownsHold || !isOwner(this) || !isExactActiveHold(handler)) {
            abandonOwnership();
            return;
        }

        BlockEntity blockEntity = currentControlEntity();
        Vec3 currentLocalHand = toLocalPosition(handPosition);
        if (blockEntity == null || currentLocalHand == null) {
            releaseOwnedHold(handler, false);
            return;
        }

        try {
            switch (kind) {
                case THROTTLE_LEVER -> updateThrottle(
                        handler,
                        blockEntity,
                        currentLocalHand
                );
                case STEERING_WHEEL -> updateSteeringWheel(
                        handler,
                        blockEntity,
                        currentLocalHand
                );
                case PHYSICS_ASSEMBLER -> updatePhysicsAssembler(
                        handler,
                        blockEntity,
                        currentLocalHand
                );
                case HANDLE -> {
                    // Simulated's native handler follows its physics
                    // constraint during HoldInteractionManager.tick().
                }
            }

        } catch (LinkageError | RuntimeException exception) {
            releaseOwnedHold(handler, false);
            throw exception;
        }
    }

    @Override
    public void endGrab(@NotNull LocalPlayer player, @NotNull HandType hand) {
        releaseOwnedHold(handler(), true);
    }

    @Override
    public void cancelGrab(@NotNull LocalPlayer player, @NotNull HandType hand) {
        releaseOwnedHold(handler(), false);
    }

    @Override
    public boolean canContinueGrab(
            @NotNull LocalPlayer player,
            @NotNull HandType hand
    ) {
        BlockHoldInteraction currentHandler = handler();
        return ownsHold
                && isOwner(this)
                && isExactActiveHold(currentHandler)
                && currentControlEntity() != null
                && (subLevelId == null || currentSubLevelPose() != null);
    }

    private void updateThrottle(
            BlockHoldInteraction handler,
            BlockEntity blockEntity,
            Vec3 currentLocalHand
    ) {
        if (!(blockEntity instanceof ThrottleLeverBlockEntity throttle)) {
            return;
        }

        SimulatedControlGeometry.ThrottleFrame frame =
                SimulatedControlGeometry.throttleFrame(throttle);
        Vec3 currentRadial = SimulatedControlGeometry.projectedRadial(
                currentLocalHand,
                frame.pivot(),
                frame.axis()
        );
        Vec3 previousRadial = previousThrottleRadial;
        previousThrottleRadial = currentRadial;
        if (!usableRadial(previousRadial) || !usableRadial(currentRadial)) {
            return;
        }

        double angularTravel = SimulatedControlGeometry.signedAngle(
                previousRadial,
                currentRadial,
                frame.axis()
        );
        angularTravel = Mth.clamp(
                angularTravel,
                -MAXIMUM_THROTTLE_DELTA_RADIANS,
                MAXIMUM_THROTTLE_DELTA_RADIANS
        );
        AttachFace face = throttle.getBlockState().getValue(
                FaceAttachedHorizontalDirectionalBlock.FACE
        );
        double renderSign = face == AttachFace.WALL ? -1.0D : 1.0D;
        double valueDelta = renderSign * angularTravel
                / SimulatedControlGeometry.THROTTLE_ANGLE_SPAN_RADIANS;
        double pitch = Mth.clamp(-valueDelta * 180.0D, -90.0D, 90.0D);
        if (Math.abs(pitch) > 1.0E-5D) {
            handler.activeOnMouseMove(0.0D, pitch);
        }
    }

    private void updateSteeringWheel(
            BlockHoldInteraction handler,
            BlockEntity blockEntity,
            Vec3 currentLocalHand
    ) {
        if (!(blockEntity instanceof SteeringWheelBlockEntity wheel)) {
            return;
        }

        SimulatedControlGeometry.WheelFrame frame =
                SimulatedControlGeometry.wheelFrame(blockPos, wheel.getBlockState());
        Vec3 currentRadial = SimulatedControlGeometry.projectedRadial(
                currentLocalHand,
                frame.center(),
                frame.axis()
        );
        Vec3 previousRadial = previousWheelRadial;
        previousWheelRadial = currentRadial;
        if (!usableRadial(previousRadial) || !usableRadial(currentRadial)) {
            return;
        }

        double deltaDegrees = Math.toDegrees(SimulatedControlGeometry.signedAngle(
                previousRadial,
                currentRadial,
                frame.axis()
        ));
        deltaDegrees = Mth.clamp(
                deltaDegrees,
                -MAXIMUM_WHEEL_DELTA_DEGREES,
                MAXIMUM_WHEEL_DELTA_DEGREES
        );
        if (Math.abs(deltaDegrees) <= 1.0E-5D) {
            return;
        }

        // The visual wheel uses rawAngle for positive facings and -rawAngle
        // for negative facings. Simulated's mouse handler applies the opposite
        // facing sign, so fixed negative yaw follows the physical rim in all
        // four directions. Dividing by directionConvert (the old behavior)
        // canceled that correction and reversed NORTH/WEST wheels.
        handler.activeOnMouseMove(-10.0D * deltaDegrees, 0.0D);
    }

    private void updatePhysicsAssembler(
            BlockHoldInteraction handler,
            BlockEntity blockEntity,
            Vec3 currentLocalHand
    ) {
        if (!(handler instanceof PhysicsAssemblerGUIHandler assemblerHandler)
                || !(blockEntity instanceof PhysicsAssemblerBlockEntity assembler)) {
            return;
        }

        SimulatedControlGeometry.AssemblerFrame frame =
                SimulatedControlGeometry.assemblerFrame(assembler);
        Vec3 currentRadial = SimulatedControlGeometry.projectedRadial(
                currentLocalHand,
                frame.pivot(),
                frame.hingeAxis()
        );
        Vec3 previousRadial = previousAssemblerRadial;
        previousAssemblerRadial = currentRadial;
        if (!usableRadial(previousRadial) || !usableRadial(currentRadial)) {
            return;
        }

        double angularTravel = SimulatedControlGeometry.signedAngle(
                previousRadial,
                currentRadial,
                frame.hingeAxis()
        );
        angularTravel = Mth.clamp(
                angularTravel,
                -MAXIMUM_THROTTLE_DELTA_RADIANS,
                MAXIMUM_THROTTLE_DELTA_RADIANS
        );
        double valueDelta = angularTravel / Math.toRadians(45.0D);
        double fraction = Mth.clamp(assemblerHandler.getFraction(), 0.0D, 1.0D);
        double response = 0.5D - Math.abs(0.5D - fraction) + 0.05D;
        double pitch = -valueDelta * 80.0D / Math.max(0.05D, response);
        if (Math.abs(pitch) > 1.0E-5D) {
            handler.activeOnMouseMove(0.0D, pitch);
        }
    }

    private void initializeGrabGeometry(BlockEntity blockEntity, Vec3 localHand) {
        Vec3 localContact = toLocalPosition(initialWorldLocation);
        if (localContact == null) {
            localContact = localHand;
        }

        if (blockEntity instanceof ThrottleLeverBlockEntity throttle) {
            SimulatedControlGeometry.ThrottleFrame frame =
                    SimulatedControlGeometry.throttleFrame(throttle);
            throttleCanonicalGrabPoint = SimulatedControlGeometry.toCanonicalThrottlePoint(
                    frame,
                    blockPos,
                    localContact
            );
            previousThrottleRadial = SimulatedControlGeometry.projectedRadial(
                    localHand,
                    frame.pivot(),
                    frame.axis()
            );
            clearNonThrottleGeometry();
            return;
        }

        if (blockEntity instanceof PhysicsAssemblerBlockEntity assembler) {
            SimulatedControlGeometry.AssemblerFrame frame =
                    SimulatedControlGeometry.assemblerFrame(assembler);
            assemblerCanonicalGrabPoint =
                    SimulatedControlGeometry.toCanonicalAssemblerPoint(
                            frame,
                            blockPos,
                            localContact
                    );
            previousAssemblerRadial = SimulatedControlGeometry.projectedRadial(
                    localHand,
                    frame.pivot(),
                    frame.hingeAxis()
            );
            clearNonAssemblerGeometry();
            return;
        }

        if (blockEntity instanceof HandleBlockEntity) {
            handleLocalGrabPoint = localContact;
            clearNonHandleGeometry();
            return;
        }

        if (blockEntity instanceof SteeringWheelBlockEntity wheel) {
            SimulatedControlGeometry.WheelFrame frame =
                    SimulatedControlGeometry.wheelFrame(blockPos, wheel.getBlockState());
            Vec3 contactRadial = SimulatedControlGeometry.projectedRadial(
                    localContact,
                    frame.center(),
                    frame.axis()
            );
            if (!usableRadial(contactRadial)) {
                contactRadial = SimulatedControlGeometry.projectedRadial(
                        localHand,
                        frame.center(),
                        frame.axis()
                );
            }
            if (!usableRadial(contactRadial)) {
                // A center grab has no angular identity. Snap it to the top of
                // the rim, which is stable for every horizontal facing.
                contactRadial = new Vec3(0.0D, 0.28D, 0.0D);
            }

            wheelModelRadial = rotateAroundAxis(
                    contactRadial,
                    frame.axis(),
                    -wheelVisualAngle(wheel)
            );
            wheelAxialOffset = localContact.subtract(frame.center()).dot(frame.axis());
            previousWheelRadial = SimulatedControlGeometry.projectedRadial(
                    localHand,
                    frame.center(),
                    frame.axis()
            );
            clearNonWheelGeometry();
        }
    }

    @Override
    public @NotNull GrabPose grabPose(
            @NotNull LocalPlayer player,
            @NotNull HandType hand,
            @NotNull Vec3 trackedHandAnchor
    ) {
        GrabPose pose = currentGrabPose();
        return pose == null ? GrabPose.anchoredAt(initialWorldLocation) : pose;
    }

    private @Nullable GrabPose currentGrabPose() {
        BlockEntity blockEntity = currentControlEntity();
        if (blockEntity instanceof ThrottleLeverBlockEntity throttle
                && throttleCanonicalGrabPoint != null) {
            SimulatedControlGeometry.ThrottleFrame frame =
                    SimulatedControlGeometry.throttleFrame(throttle);
            Vec3 localAnchor = SimulatedControlGeometry.fromCanonicalThrottlePoint(
                    frame,
                    blockPos,
                    throttleCanonicalGrabPoint
            );
            Vec3 localHandleAxis = localAnchor.subtract(frame.pivot());
            if (!usableRadial(localHandleAxis)) {
                localHandleAxis = new Vec3(0.0D, 1.0D, 0.0D);
            }
            Vec3 worldAnchor = toWorldPosition(localAnchor);
            Vec3 worldHandleAxis = unitOrFallback(
                    toWorldNormal(localHandleAxis),
                    new Vec3(0.0D, 1.0D, 0.0D)
            );
            Vec3 worldHingeAxis = unitOrFallback(
                    toWorldNormal(frame.axis()),
                    new Vec3(1.0D, 0.0D, 0.0D)
            );
            return GrabPose.attachedTo(
                    worldAnchor,
                    GrabPose.frameFromAxes(worldHandleAxis, worldHingeAxis)
            );
        }

        if (blockEntity instanceof SteeringWheelBlockEntity wheel
                && wheelModelRadial != null) {
            SimulatedControlGeometry.WheelFrame frame =
                    SimulatedControlGeometry.wheelFrame(blockPos, wheel.getBlockState());
            Vec3 radial = rotateAroundAxis(
                    wheelModelRadial,
                    frame.axis(),
                    wheelVisualAngle(wheel)
            );
            Vec3 localAnchor = frame.center()
                    .add(radial)
                    .add(frame.axis().scale(wheelAxialOffset));
            Vec3 worldAnchor = toWorldPosition(localAnchor);
            Vec3 worldAxle = unitOrFallback(
                    toWorldNormal(frame.axis()),
                    new Vec3(0.0D, 0.0D, 1.0D)
            );
            Vec3 worldRadial = unitOrFallback(
                    toWorldNormal(radial),
                    new Vec3(0.0D, 1.0D, 0.0D)
            );
            return GrabPose.attachedTo(
                    worldAnchor,
                    GrabPose.wheelFrame(worldAxle, worldRadial)
            );
        }

        if (blockEntity instanceof PhysicsAssemblerBlockEntity assembler
                && assemblerCanonicalGrabPoint != null) {
            SimulatedControlGeometry.AssemblerFrame frame =
                    SimulatedControlGeometry.assemblerFrame(assembler);
            Vec3 localAnchor = SimulatedControlGeometry.fromCanonicalAssemblerPoint(
                    frame,
                    blockPos,
                    assemblerCanonicalGrabPoint
            );
            Vec3 localLeverAxis = unitOrFallback(
                    localAnchor.subtract(frame.pivot()),
                    frame.grip().subtract(frame.pivot())
            );
            Vec3 worldAnchor = toWorldPosition(localAnchor);
            Vec3 worldLeverAxis = unitOrFallback(
                    toWorldNormal(localLeverAxis),
                    new Vec3(0.0D, 1.0D, 0.0D)
            );
            Vec3 worldHingeAxis = unitOrFallback(
                    toWorldNormal(frame.hingeAxis()),
                    new Vec3(1.0D, 0.0D, 0.0D)
            );
            return GrabPose.attachedTo(
                    worldAnchor,
                    GrabPose.frameFromAxes(worldLeverAxis, worldHingeAxis)
            );
        }

        if (blockEntity instanceof HandleBlockEntity handle) {
            org.joml.Vector3d grabCenter = handle.getGrabCenter();
            Vec3 localAnchor = new Vec3(
                    grabCenter.x,
                    grabCenter.y,
                    grabCenter.z
            );
            BlockState state = handle.getBlockState();
            Vec3 outward = directionVector(state.getValue(HandleBlock.FACING));
            Vec3 barAxis = directionVector(
                    AbstractDirectionalAxisBlock.getDirectionOfAxis(state)
            );
            return GrabPose.attachedTo(
                    toWorldPosition(localAnchor),
                    GrabPose.frameFromAxes(
                            unitOrFallback(
                                    toWorldNormal(barAxis),
                                    new Vec3(0.0D, 1.0D, 0.0D)
                            ),
                            unitOrFallback(
                                    toWorldNormal(outward),
                                    new Vec3(0.0D, 0.0D, 1.0D)
                            )
                    )
            );
        }
        return null;
    }

    private double wheelVisualAngle(SteeringWheelBlockEntity wheel) {
        Direction facing = wheel.getBlockState().getValue(SteeringWheelBlock.FACING);
        double facingSign = facing.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? 1.0D
                : -1.0D;
        return Math.toRadians(wheel.getInteractionAngle(1.0F) * facingSign);
    }

    private @Nullable Vec3 toLocalPosition(Vec3 worldPosition) {
        if (subLevelId == null) {
            return worldPosition;
        }
        Pose3dc pose = currentSubLevelPose();
        return pose == null ? null : pose.transformPositionInverse(worldPosition);
    }

    private @NotNull Vec3 toWorldPosition(Vec3 localPosition) {
        if (subLevelId == null) {
            return localPosition;
        }
        Pose3dc pose = currentSubLevelPose();
        return pose == null ? initialWorldLocation : pose.transformPosition(localPosition);
    }

    private @NotNull Vec3 toWorldNormal(Vec3 localNormal) {
        if (subLevelId == null) {
            return localNormal;
        }
        Pose3dc pose = currentSubLevelPose();
        return pose == null ? localNormal : pose.transformNormal(localNormal);
    }

    private @Nullable Pose3dc currentSubLevelPose() {
        BlockEntity blockEntity = level.getBlockEntity(blockPos);
        if (blockEntity == null) {
            return null;
        }
        ClientSubLevelAccess subLevel = SableCompanion.INSTANCE.getContainingClient(blockEntity);
        if (subLevel == null || !subLevelId.equals(subLevel.getUniqueId())) {
            return null;
        }
        return subLevel.logicalPose();
    }

    private @Nullable BlockEntity currentControlEntity() {
        BlockEntity blockEntity = level.getBlockEntity(blockPos);
        return switch (kind) {
            case THROTTLE_LEVER -> blockEntity instanceof ThrottleLeverBlockEntity
                    ? blockEntity : null;
            case STEERING_WHEEL -> blockEntity instanceof SteeringWheelBlockEntity
                    ? blockEntity : null;
            case PHYSICS_ASSEMBLER -> blockEntity instanceof PhysicsAssemblerBlockEntity
                    ? blockEntity : null;
            case HANDLE -> blockEntity instanceof HandleBlockEntity
                    ? blockEntity : null;
        };
    }

    private @Nullable BlockHoldInteraction handler() {
        return switch (kind) {
            case THROTTLE_LEVER -> SimClickInteractions.THROTTLE_LEVER_MANAGER;
            case STEERING_WHEEL -> SimClickInteractions.STEERING_WHEEL_MANAGER;
            case PHYSICS_ASSEMBLER -> SimClickInteractions.PHYSICS_ASSEMBLER_MANAGER;
            case HANDLE -> SimClickInteractions.HANDLE_HANDLER;
        };
    }

    private boolean isExactActiveHold(@Nullable BlockHoldInteraction handler) {
        return handler != null
                && Minecraft.getInstance().level == level
                && isRegisteredHold(handler);
    }

    private boolean isRegisteredHold(@Nullable BlockHoldInteraction handler) {
        return handler != null
                && HoldInteractionManager.isActive(handler)
                && handler.isBlockActive(blockPos);
    }

    private void releaseOwnedHold(
            @Nullable BlockHoldInteraction handler,
            boolean completeInteraction
    ) {
        try {
            stopExactHoldIfOwned(handler, completeInteraction);
        } finally {
            abandonOwnership();
        }
    }

    private void stopExactHoldIfOwned(
            @Nullable BlockHoldInteraction handler,
            boolean completeInteraction
    ) {
        if (isOwner(this) && isRegisteredHold(handler)) {
            try {
                // Physics Assembler intentionally commits only on a clean
                // grip release. Its native manager does not call release()
                // from stop(), because invalid/range cancellation must not
                // assemble or disassemble anything.
                if (completeInteraction
                        && kind == SimulatedInteractionBridge.ControlKind.PHYSICS_ASSEMBLER) {
                    handler.release();
                }
            } finally {
                // SteeringWheelHandler and ClientHandleHandler override stop
                // to send their own authoritative release packets.
                HoldInteractionManager.stop();
            }
        }
    }

    private void abandonOwnership() {
        ownsHold = false;
        previousThrottleRadial = null;
        previousWheelRadial = null;
        previousAssemblerRadial = null;
        throttleCanonicalGrabPoint = null;
        assemblerCanonicalGrabPoint = null;
        handleLocalGrabPoint = null;
        wheelModelRadial = null;
        wheelAxialOffset = 0.0D;
        clearOwnership(this);
    }

    private void clearNonThrottleGeometry() {
        previousWheelRadial = null;
        previousAssemblerRadial = null;
        assemblerCanonicalGrabPoint = null;
        handleLocalGrabPoint = null;
        wheelModelRadial = null;
        wheelAxialOffset = 0.0D;
    }

    private void clearNonAssemblerGeometry() {
        previousThrottleRadial = null;
        previousWheelRadial = null;
        throttleCanonicalGrabPoint = null;
        handleLocalGrabPoint = null;
        wheelModelRadial = null;
        wheelAxialOffset = 0.0D;
    }

    private void clearNonHandleGeometry() {
        previousThrottleRadial = null;
        previousWheelRadial = null;
        previousAssemblerRadial = null;
        throttleCanonicalGrabPoint = null;
        assemblerCanonicalGrabPoint = null;
        wheelModelRadial = null;
        wheelAxialOffset = 0.0D;
    }

    private void clearNonWheelGeometry() {
        previousThrottleRadial = null;
        previousAssemblerRadial = null;
        throttleCanonicalGrabPoint = null;
        assemblerCanonicalGrabPoint = null;
        handleLocalGrabPoint = null;
    }

    private boolean stillOwnsLiveHandler() {
        return ownsHold && isExactActiveHold(handler());
    }

    private static synchronized boolean claimOwnership(SimulatedControlTarget candidate) {
        if (activeOwner != null && !activeOwner.stillOwnsLiveHandler()) {
            activeOwner.ownsHold = false;
            activeOwner = null;
        }
        if (activeOwner != null && activeOwner != candidate) {
            return false;
        }
        activeOwner = candidate;
        return true;
    }

    private static synchronized boolean isOwner(SimulatedControlTarget candidate) {
        return activeOwner == candidate;
    }

    static synchronized @Nullable SimulatedControlTarget activeFor(ClientLevel level) {
        if (activeOwner != null && !activeOwner.stillOwnsLiveHandler()) {
            activeOwner.ownsHold = false;
            activeOwner = null;
        }
        return activeOwner != null && activeOwner.level == level ? activeOwner : null;
    }

    private static synchronized void clearOwnership(SimulatedControlTarget candidate) {
        if (activeOwner == candidate) {
            activeOwner = null;
        }
    }

    private static boolean usableRadial(@Nullable Vec3 radial) {
        return radial != null
                && Double.isFinite(radial.x)
                && Double.isFinite(radial.y)
                && Double.isFinite(radial.z)
                && radial.lengthSqr()
                >= SimulatedControlGeometry.MINIMUM_RADIAL_LENGTH_SQUARED;
    }

    private static @NotNull Vec3 unitOrFallback(Vec3 vector, Vec3 fallback) {
        return vector.lengthSqr() < 1.0E-12D ? fallback : vector.normalize();
    }

    private static @NotNull Vec3 directionVector(Direction direction) {
        return new Vec3(
                direction.getStepX(),
                direction.getStepY(),
                direction.getStepZ()
        );
    }

    private static @NotNull Vec3 rotateAroundAxis(
            @NotNull Vec3 vector,
            @NotNull Vec3 axis,
            double angle
    ) {
        Vec3 unitAxis = axis.normalize();
        double cosine = Math.cos(angle);
        double sine = Math.sin(angle);
        return vector.scale(cosine)
                .add(unitAxis.cross(vector).scale(sine))
                .add(unitAxis.scale(unitAxis.dot(vector) * (1.0D - cosine)));
    }

    private record ControlKey(
            ResourceKey<Level> dimension,
            @Nullable UUID subLevelId,
            BlockPos blockPos,
            SimulatedInteractionBridge.ControlKind kind
    ) {
    }
}
