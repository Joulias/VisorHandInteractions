package org.vmstudio.visorhandinteractions.core.client.interaction.generic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.vmstudio.visor.api.common.HandType;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionTarget;
import org.vmstudio.visorhandinteractions.core.client.pose.GrabPose;
import org.vmstudio.visorhandinteractions.core.client.pose.GrabPoseProvider;

/**
 * Server-authoritative fallback for stationary controls whose public contract
 * is ordinary block use. Physical motion is converted to bounded use detents;
 * no foreign block-entity fields are read or written.
 */
public final class GenericControlTarget implements InteractionTarget, GrabPoseProvider {
    private static final double LEVER_TRIGGER_TRAVEL = 0.085D;
    private static final double STEPPED_LEVER_DETENT = 0.055D;
    private static final double PRESS_TRIGGER_TRAVEL = 0.045D;
    private static final double MAXIMUM_PRESS_TRAVEL = 0.075D;
    private static final double ROTARY_DETENT_RADIANS = Math.toRadians(24.0D);
    private static final double MAXIMUM_ROTATION_PER_TICK = Math.toRadians(85.0D);
    private static final int MINIMUM_USE_INTERVAL_TICKS = 2;
    private static final int MAXIMUM_ACTIONS_PER_GRAB = 32;

    private final ClientLevel level;
    private final GenericControlDescriptor descriptor;
    private final BlockHitResult originalHit;
    private final double sweepFraction;
    private final TargetKey key;

    private boolean active;
    private boolean binaryTriggered;
    private int actionCount;
    private long lastUseGameTime = Long.MIN_VALUE;
    private @Nullable Vec3 initialHand;
    private @Nullable Vec3 previousHand;
    private @Nullable Vec3 initialLeverGripVector;
    private @Nullable Vec3 initialLeverHandVector;
    private @Nullable Vec3 currentLeverVector;
    private @Nullable Vec3 previousWheelHandRadial;
    private @Nullable Vec3 currentWheelGripRadial;
    private @Nullable Vec3 currentSnapAnchor;
    private double wheelGripRadius;
    private double wheelAxialOffset;
    private double leverHingeOffset;
    private double accumulatedLeverTravel;
    private double accumulatedWheelAngle;

    public GenericControlTarget(
            @NotNull ClientLevel level,
            @NotNull GenericControlDescriptor descriptor,
            @NotNull BlockHitResult originalHit,
            double sweepFraction
    ) {
        this.level = level;
        this.descriptor = descriptor;
        this.originalHit = originalHit;
        this.sweepFraction = sweepFraction;
        this.key = new TargetKey(
                level.dimension(),
                descriptor.blockPos(),
                descriptor.blockId()
        );
    }

    @Override
    public @NotNull Object key() {
        return key;
    }

    @Override
    public @NotNull Vec3 worldLocation() {
        return originalHit.getLocation();
    }

    @Override
    public double sweepFraction() {
        return sweepFraction;
    }

    @Override
    public boolean allowTouch() {
        return descriptor.kind().touchSafe();
    }

    @Override
    public boolean allowGrab() {
        return descriptor.kind().genericManipulationSafe();
    }

    @Override
    public boolean beginGrabIsPassive() {
        return true;
    }

    @Override
    public boolean isBackedBy(@NotNull BlockPos worldBlockPos) {
        return descriptor.blockPos().equals(worldBlockPos);
    }

    @Override
    public @NotNull InteractionResult touch(
            @NotNull LocalPlayer player,
            @NotNull HandType hand
    ) {
        if (!descriptor.kind().touchSafe()) {
            return InteractionResult.PASS;
        }
        return useControl(player, hand, originalHit.getLocation(), false);
    }

    @Override
    public @NotNull InteractionResult beginGrab(
            @NotNull LocalPlayer player,
            @NotNull HandType hand,
            @NotNull Vec3 handPosition
    ) {
        if (!descriptor.kind().genericManipulationSafe()
                || !validControl(player)
                || !finite(handPosition)) {
            return InteractionResult.PASS;
        }

        active = true;
        binaryTriggered = false;
        actionCount = 0;
        lastUseGameTime = Long.MIN_VALUE;
        initialHand = handPosition;
        previousHand = handPosition;
        accumulatedLeverTravel = 0.0D;
        accumulatedWheelAngle = 0.0D;

        if (descriptor.kind().linearPress()) {
            currentSnapAnchor = descriptor.defaultGrip();
        } else if (descriptor.kind().rotary()) {
            Vec3 axle = descriptor.hingeAxis();
            Vec3 contactFromPivot = descriptor.defaultGrip()
                    .subtract(descriptor.pivot());
            Vec3 contactRadial = ControlMotionMath.projectOntoPlane(
                    contactFromPivot,
                    axle
            );
            wheelGripRadius = Math.max(0.08D, contactRadial.length());
            currentWheelGripRadial = ControlMotionMath.normalizedOr(
                    contactRadial,
                    descriptor.motionAxis()
            );
            wheelAxialOffset = contactFromPivot.dot(axle);

            Vec3 handRadial = ControlMotionMath.projectOntoPlane(
                    handPosition.subtract(descriptor.pivot()),
                    axle
            );
            previousWheelHandRadial = ControlMotionMath.normalizedOr(
                    handRadial,
                    currentWheelGripRadial
            );
            currentSnapAnchor = wheelAnchor(currentWheelGripRadial);
        } else {
            Vec3 hinge = descriptor.hingeAxis();
            Vec3 contactFromPivot = descriptor.defaultGrip().subtract(descriptor.pivot());
            Vec3 modelGrip = ControlMotionMath.projectOntoPlane(contactFromPivot, hinge);
            if (modelGrip.lengthSqr() < 1.0E-12D) {
                modelGrip = descriptor.motionAxis().scale(0.28D);
            }
            Vec3 physicalHand = ControlMotionMath.projectOntoPlane(
                    handPosition.subtract(descriptor.pivot()),
                    hinge
            );
            initialLeverGripVector = modelGrip;
            initialLeverHandVector = ControlMotionMath.normalizedOr(
                    physicalHand,
                    modelGrip
            );
            currentLeverVector = modelGrip;
            leverHingeOffset = contactFromPivot.dot(hinge);
            currentSnapAnchor = descriptor.pivot()
                    .add(modelGrip)
                    .add(hinge.scale(leverHingeOffset));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean canContinueGrab(
            @NotNull LocalPlayer player,
            @NotNull HandType hand
    ) {
        return active
                && descriptor.kind().genericManipulationSafe()
                && validControl(player);
    }

    @Override
    public void continueGrab(
            @NotNull LocalPlayer player,
            @NotNull HandType hand,
            @NotNull Vec3 handPosition,
            @NotNull Vec3 handDelta
    ) {
        if (!active || !validControl(player) || !finite(handPosition)) {
            active = false;
            return;
        }
        if (descriptor.kind().linearPress()) {
            continuePress(player, hand, handPosition);
        } else if (descriptor.kind().rotary()) {
            continueRotary(player, hand, handPosition);
        } else {
            continueLever(player, hand, handPosition);
        }
        previousHand = handPosition;
    }

    @Override
    public void endGrab(@NotNull LocalPlayer player, @NotNull HandType hand) {
        active = false;
        initialHand = null;
        previousHand = null;
        initialLeverGripVector = null;
        initialLeverHandVector = null;
        currentLeverVector = null;
        previousWheelHandRadial = null;
        currentWheelGripRadial = null;
        currentSnapAnchor = null;
        leverHingeOffset = 0.0D;
    }

    @Override
    public @NotNull GrabPose grabPose(
            @NotNull LocalPlayer player,
            @NotNull HandType hand,
            @NotNull Vec3 trackedHandAnchor
    ) {
        Vec3 anchor = currentSnapAnchor == null
                ? descriptor.defaultGrip()
                : currentSnapAnchor;
        if (descriptor.kind().linearPress()) {
            return GrabPose.attachedTo(
                    anchor,
                    GrabPose.frameFromAxes(
                            descriptor.motionAxis(),
                            descriptor.hingeAxis()
                    )
            );
        }
        if (descriptor.kind().rotary()) {
            Vec3 radial = currentWheelGripRadial == null
                    ? ControlMotionMath.projectOntoPlane(
                            descriptor.defaultGrip().subtract(descriptor.pivot()),
                            descriptor.hingeAxis()
                    )
                    : currentWheelGripRadial;
            return GrabPose.attachedTo(
                    anchor,
                    GrabPose.wheelFrame(descriptor.hingeAxis(), radial)
            );
        }
        Vec3 lever = currentLeverVector == null
                ? descriptor.defaultGrip().subtract(descriptor.pivot())
                : currentLeverVector;
        return GrabPose.attachedTo(
                anchor,
                GrabPose.frameFromAxes(lever, descriptor.hingeAxis())
        );
    }

    private void continueLever(
            LocalPlayer player,
            HandType hand,
            Vec3 handPosition
    ) {
        Vec3 initialGrip = initialLeverGripVector;
        Vec3 initialHandVector = initialLeverHandVector;
        Vec3 startHand = initialHand;
        Vec3 lastHand = previousHand;
        if (initialGrip == null || initialHandVector == null
                || startHand == null || lastHand == null) {
            return;
        }

        Vec3 physicalLever = ControlMotionMath.projectOntoPlane(
                handPosition.subtract(descriptor.pivot()),
                descriptor.hingeAxis()
        );
        double angle = ControlMotionMath.signedAngle(
                initialHandVector,
                physicalLever,
                descriptor.hingeAxis()
        );
        angle = ControlMotionMath.clamp(
                angle,
                Math.toRadians(-52.0D),
                Math.toRadians(52.0D)
        );
        currentLeverVector = ControlMotionMath.rotateAroundAxis(
                initialGrip,
                descriptor.hingeAxis(),
                angle
        );
        currentSnapAnchor = descriptor.pivot()
                .add(currentLeverVector)
                .add(descriptor.hingeAxis().scale(leverHingeOffset));

        double tickTravel = handPosition.subtract(lastHand).dot(descriptor.motionAxis());
        accumulatedLeverTravel += tickTravel;
        double totalTravel = handPosition.subtract(startHand).dot(descriptor.motionAxis());
        double arcTravel = Math.abs(angle) * initialGrip.length();

        if (descriptor.kind() == GenericControlKind.BINARY_LEVER) {
            if (!binaryTriggered
                    && Math.max(Math.abs(totalTravel), arcTravel) >= LEVER_TRIGGER_TRAVEL) {
                InteractionResult result = useControl(player, hand, currentSnapAnchor, true);
                binaryTriggered = result.consumesAction();
            }
            return;
        }

        int attempts = 0;
        while (Math.abs(accumulatedLeverTravel) >= STEPPED_LEVER_DETENT
                && attempts++ < 2
                && actionCount < MAXIMUM_ACTIONS_PER_GRAB) {
            boolean directionMatches = ControlMotionMath.detentMatchesModifier(
                    accumulatedLeverTravel,
                    player.isShiftKeyDown()
            );
            if (directionMatches
                    && !useControl(player, hand, currentSnapAnchor, true)
                    .consumesAction()) {
                break;
            }
            // Consume opposite-modifier motion too, preventing a delayed
            // backlog or one-way advancement from physical oscillation.
            accumulatedLeverTravel -= Math.copySign(
                    STEPPED_LEVER_DETENT,
                    accumulatedLeverTravel
            );
        }
    }

    private void continuePress(
            LocalPlayer player,
            HandType hand,
            Vec3 handPosition
    ) {
        Vec3 startHand = initialHand;
        if (startHand == null) {
            return;
        }

        double travel = ControlMotionMath.clamp(
                handPosition.subtract(startHand).dot(descriptor.motionAxis()),
                0.0D,
                MAXIMUM_PRESS_TRAVEL
        );
        currentSnapAnchor = descriptor.defaultGrip()
                .add(descriptor.motionAxis().scale(travel));
        if (!binaryTriggered && travel >= PRESS_TRIGGER_TRAVEL) {
            InteractionResult result = useControl(
                    player,
                    hand,
                    currentSnapAnchor,
                    true
            );
            binaryTriggered = result.consumesAction();
        }
    }

    private void continueRotary(
            LocalPlayer player,
            HandType hand,
            Vec3 handPosition
    ) {
        Vec3 axle = descriptor.hingeAxis();
        Vec3 previousHandRadial = previousWheelHandRadial;
        Vec3 gripRadial = currentWheelGripRadial;
        if (previousHandRadial == null || gripRadial == null) {
            return;
        }

        Vec3 handRadial = ControlMotionMath.projectOntoPlane(
                handPosition.subtract(descriptor.pivot()),
                axle
        );
        if (handRadial.lengthSqr() < 0.025D * 0.025D) {
            return;
        }
        handRadial = handRadial.normalize();
        double delta = ControlMotionMath.signedAngle(
                previousHandRadial,
                handRadial,
                axle
        );
        if (Math.abs(delta) <= MAXIMUM_ROTATION_PER_TICK) {
            accumulatedWheelAngle += delta;
            currentWheelGripRadial = ControlMotionMath.rotateAroundAxis(
                    gripRadial,
                    axle,
                    delta
            ).normalize();
        }
        previousWheelHandRadial = handRadial;
        currentSnapAnchor = wheelAnchor(currentWheelGripRadial);

        int attempts = 0;
        while (Math.abs(accumulatedWheelAngle) >= ROTARY_DETENT_RADIANS
                && attempts++ < 2
                && actionCount < MAXIMUM_ACTIONS_PER_GRAB) {
            boolean directionMatches = ControlMotionMath.detentMatchesModifier(
                    accumulatedWheelAngle,
                    player.isShiftKeyDown()
            );
            if (directionMatches
                    && !useControl(player, hand, currentSnapAnchor, true)
                    .consumesAction()) {
                break;
            }
            // Mismatched physical direction is intentionally discarded.
            accumulatedWheelAngle -= Math.copySign(
                    ROTARY_DETENT_RADIANS,
                    accumulatedWheelAngle
            );
        }
    }

    private Vec3 wheelAnchor(Vec3 radial) {
        return descriptor.pivot()
                .add(radial.scale(wheelGripRadius))
                .add(descriptor.hingeAxis().scale(wheelAxialOffset));
    }

    private InteractionResult useControl(
            LocalPlayer player,
            HandType hand,
            Vec3 hitLocation,
            boolean enforceRateLimit
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!validControl(player) || minecraft.gameMode == null) {
            return InteractionResult.PASS;
        }
        long gameTime = level.getGameTime();
        if (enforceRateLimit
                && lastUseGameTime != Long.MIN_VALUE
                && gameTime - lastUseGameTime < MINIMUM_USE_INTERVAL_TICKS) {
            return InteractionResult.PASS;
        }
        if (enforceRateLimit && actionCount >= MAXIMUM_ACTIONS_PER_GRAB) {
            return InteractionResult.PASS;
        }

        lastUseGameTime = gameTime;
        InteractionResult result = minecraft.gameMode.useItemOn(
                player,
                hand.asInteractionHand(),
                new BlockHitResult(
                        finite(hitLocation) ? hitLocation : originalHit.getLocation(),
                        descriptor.interactionFace(),
                        descriptor.blockPos(),
                        originalHit.isInside()
                )
        );
        if (enforceRateLimit && result.consumesAction()) {
            actionCount++;
        }
        return result;
    }

    private boolean validControl(LocalPlayer player) {
        if (Minecraft.getInstance().level != level
                || !level.isLoaded(descriptor.blockPos())
                || !level.getWorldBorder().isWithinBounds(descriptor.blockPos())
                || !level.mayInteract(player, descriptor.blockPos())
                || !player.canInteractWithBlock(descriptor.blockPos(), 0.0D)) {
            return false;
        }
        BlockState current = level.getBlockState(descriptor.blockPos());
        return descriptor.blockId().equals(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(current.getBlock())
        );
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x)
                && Double.isFinite(value.y)
                && Double.isFinite(value.z);
    }

    private record TargetKey(
            ResourceKey<Level> dimension,
            BlockPos blockPos,
            net.minecraft.resources.ResourceLocation blockId
    ) {
    }
}
