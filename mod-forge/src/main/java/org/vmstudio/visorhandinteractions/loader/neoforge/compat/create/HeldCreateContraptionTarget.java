package org.vmstudio.visorhandinteractions.loader.neoforge.compat.create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsHandler;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsInputPacket;
import com.simibubi.create.content.contraptions.sync.ContraptionInteractionPacket;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.vmstudio.visor.api.common.HandType;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionTarget;
import org.vmstudio.visorhandinteractions.core.client.interaction.generic.ControlMotionMath;
import org.vmstudio.visorhandinteractions.core.client.interaction.generic.GenericControlClassifier;
import org.vmstudio.visorhandinteractions.core.client.interaction.generic.GenericControlKind;
import org.vmstudio.visorhandinteractions.core.client.pose.GrabPose;
import org.vmstudio.visorhandinteractions.core.client.pose.GrabPoseProvider;

import java.util.List;

/**
 * A held control on an assembled Create contraption.
 *
 * <p>All contact and motion state is kept in contraption-local coordinates.
 * This makes the grip point follow both translation and rotation without ever
 * writing foreign block-entity fields. Detents are ordinary Create moving
 * interaction packets, so the server remains authoritative.</p>
 */
final class HeldCreateContraptionTarget implements InteractionTarget, GrabPoseProvider {
    private static final double EPSILON_SQUARED = 1.0E-12D;
    private static final double LEVER_TRIGGER_TRAVEL = 0.085D;
    private static final double STEPPED_LEVER_DETENT = 0.055D;
    private static final double ROTARY_DETENT_RADIANS = Math.toRadians(24.0D);
    private static final double MAXIMUM_ROTATION_PER_TICK = Math.toRadians(85.0D);
    private static final double TRAIN_LEVER_DEAD_ZONE = 0.035D;
    private static final double MAXIMUM_TRAIN_LEVER_TRAVEL = 0.14D;
    private static final double TRAIN_LEVER_FRAME_SCALE = 4.0D;
    private static final int MINIMUM_USE_INTERVAL_TICKS = 2;
    private static final int MAXIMUM_ACTIONS_PER_GRAB = 32;
    private static final ResourceLocation TRAIN_CONTROLS_ID =
            ResourceLocation.fromNamespaceAndPath("create", "controls");

    private final AbstractContraptionEntity contraption;
    private final BlockPos localPos;
    private final Direction interactionFace;
    private final Vec3 localContact;
    private final Vec3 fallbackWorldContact;
    private final double sweepFraction;
    private final boolean allowTouch;
    private final ResourceLocation blockId;
    private final @Nullable GenericControlKind controlKind;
    private final boolean trainControls;
    private final TargetKey key;

    private boolean active;
    private boolean binaryTriggered;
    private int actionCount;
    private long lastUseGameTime = Long.MIN_VALUE;
    private @Nullable LocalGeometry geometry;
    private @Nullable Vec3 initialLocalHand;
    private @Nullable Vec3 previousLocalHand;
    private @Nullable Vec3 initialLeverGripVector;
    private @Nullable Vec3 initialLeverHandVector;
    private @Nullable Vec3 currentLeverGripVector;
    private double leverHingeOffset;
    private @Nullable Vec3 previousWheelHandRadial;
    private @Nullable Vec3 currentWheelGripRadial;
    private @Nullable Vec3 currentLocalAnchor;
    private double wheelGripRadius;
    private double wheelAxialOffset;
    private double accumulatedLeverTravel;
    private double accumulatedWheelAngle;
    private boolean trainSpeedLever;
    private @Nullable Vec3 trainMotionAxis;
    private @Nullable Vec3 trainHingeAxis;
    private double trainLeverTravel;

    HeldCreateContraptionTarget(
            @NotNull AbstractContraptionEntity contraption,
            @NotNull BlockPos localPos,
            @NotNull Direction interactionFace,
            @NotNull Vec3 localContact,
            @NotNull Vec3 fallbackWorldContact,
            double sweepFraction,
            boolean allowTouch,
            @NotNull BlockState initialState
    ) {
        this.contraption = contraption;
        this.localPos = localPos.immutable();
        this.interactionFace = interactionFace;
        this.localContact = localContact;
        this.fallbackWorldContact = fallbackWorldContact;
        this.sweepFraction = sweepFraction;
        this.allowTouch = allowTouch;
        this.blockId = BuiltInRegistries.BLOCK.getKey(initialState.getBlock());
        this.controlKind = GenericControlClassifier.controlKind(initialState).orElse(null);
        this.trainControls = TRAIN_CONTROLS_ID.equals(this.blockId);
        this.key = new TargetKey(contraption.getId(), this.localPos);
    }

    @Override
    public @NotNull Object key() {
        return key;
    }

    @Override
    public @NotNull Vec3 worldLocation() {
        if (!contraption.isAliveOrStale() || contraption.getContraption() == null) {
            return fallbackWorldContact;
        }
        Vec3 transformed = contraption.toGlobalVector(localContact, 1.0F);
        return finite(transformed) ? transformed : fallbackWorldContact;
    }

    @Override
    public double sweepFraction() {
        return sweepFraction;
    }

    @Override
    public boolean allowTouch() {
        // Entering a train-driving session is ownership, not a safe touch
        // pulse. It must start from the dedicated grab action.
        return allowTouch && !trainControls;
    }

    @Override
    public boolean allowGrab() {
        if (trainControls) {
            return hasMovingInteractor();
        }
        if (controlKind == null) {
            return allowTouch;
        }
        return controlKind.genericManipulationSafe()
                && hasMovingInteractor();
    }

    @Override
    public boolean beginGrabIsPassive() {
        return controlKind != null
                && controlKind.genericManipulationSafe()
                && hasMovingInteractor();
    }

    @Override
    public @NotNull InteractionResult touch(
            @NotNull LocalPlayer player,
            @NotNull HandType hand
    ) {
        if (trainControls
                || !allowTouch
                || (controlKind != null && !controlKind.touchSafe())) {
            // Native hold protocols must never be started by a touch pulse.
            return InteractionResult.PASS;
        }
        return dispatchInteraction(player, hand, false);
    }

    @Override
    public @NotNull InteractionResult beginGrab(
            @NotNull LocalPlayer player,
            @NotNull HandType hand,
            @NotNull Vec3 handPosition
    ) {
        // Steering wheels and other controls with native start/stop-hold
        // protocols must be owned by their dedicated adapter. Sending one
        // ordinary right-click here would start a foreign hold that this
        // target cannot safely update or release.
        if (controlKind != null && !controlKind.genericManipulationSafe()) {
            return InteractionResult.PASS;
        }

        if (trainControls) {
            return beginTrainControlsGrab(player, hand, handPosition);
        }

        // Preserve Create's old one-shot grab behavior for non-control
        // interactors, but never retain them as a sticky held target.
        if (controlKind == null) {
            return dispatchInteraction(player, hand, false);
        }
        if (!validHeldControl(player) || !finite(handPosition)) {
            return InteractionResult.PASS;
        }

        Vec3 localHand = contraption.toLocalVector(handPosition, 1.0F);
        BlockState state = currentState();
        if (!finite(localHand) || state == null) {
            return InteractionResult.PASS;
        }

        LocalGeometry nextGeometry = describeGeometry(player, state);
        geometry = nextGeometry;
        active = true;
        binaryTriggered = false;
        actionCount = 0;
        lastUseGameTime = Long.MIN_VALUE;
        initialLocalHand = localHand;
        previousLocalHand = localHand;
        currentLocalAnchor = localContact;
        accumulatedLeverTravel = 0.0D;
        accumulatedWheelAngle = 0.0D;

        if (controlKind.rotary()) {
            beginRotary(localHand, nextGeometry);
        } else {
            beginLever(localHand, nextGeometry);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean canContinueGrab(
            @NotNull LocalPlayer player,
            @NotNull HandType hand
    ) {
        if (trainControls) {
            return active
                    && validBaseTarget(player)
                    && hasMovingInteractor()
                    && ownsTrainControlSession()
                    && TrainControlsInputOverride.isOwner(this);
        }
        return active
                && controlKind != null
                && controlKind.genericManipulationSafe()
                && validHeldControl(player);
    }

    @Override
    public void continueGrab(
            @NotNull LocalPlayer player,
            @NotNull HandType hand,
            @NotNull Vec3 handPosition,
            @NotNull Vec3 handDelta
    ) {
        if (trainControls) {
            if (!active || !validBaseTarget(player) || !finite(handPosition)) {
                TrainControlsInputOverride.update(
                        this,
                        false,
                        false,
                        false,
                        false
                );
                return;
            }
            Vec3 localHand = contraption.toLocalVector(handPosition, 1.0F);
            if (!finite(localHand)) {
                TrainControlsInputOverride.update(
                        this,
                        false,
                        false,
                        false,
                        false
                );
                return;
            }
            continueTrainControls(localHand);
            previousLocalHand = localHand;
            return;
        }

        if (!active || !validHeldControl(player) || !finite(handPosition)) {
            active = false;
            return;
        }
        Vec3 localHand = contraption.toLocalVector(handPosition, 1.0F);
        if (!finite(localHand)) {
            active = false;
            return;
        }
        if (controlKind != null && controlKind.rotary()) {
            continueRotary(player, hand, localHand);
        } else {
            continueLever(player, hand, localHand);
        }
        previousLocalHand = localHand;
    }

    @Override
    public void endGrab(@NotNull LocalPlayer player, @NotNull HandType hand) {
        try {
            if (trainControls && active && ownsTrainControlSession()) {
                // stopControlling samples the real bindings to restore their
                // down state. Remove the virtual directions first so none of
                // them can leak into normal player movement after release.
                TrainControlsInputOverride.release(this);
                stopOwnedTrainControlSession();
            }
        } finally {
            TrainControlsInputOverride.release(this);
            clearHeldState();
        }
    }

    @Override
    public @NotNull GrabPose grabPose(
            @NotNull LocalPlayer player,
            @NotNull HandType hand,
            @NotNull Vec3 trackedHandAnchor
    ) {
        LocalGeometry currentGeometry = geometry;
        Vec3 localAnchor = currentLocalAnchor == null ? localContact : currentLocalAnchor;
        Vec3 worldAnchor = worldPoint(localAnchor, worldLocation());
        if (trainControls && trainMotionAxis != null && trainHingeAxis != null) {
            Vec3 leverAxis = ControlMotionMath.normalizedOr(
                    new Vec3(0.0D, 1.0D, 0.0D).add(
                            trainMotionAxis.scale(
                                    trainLeverTravel * TRAIN_LEVER_FRAME_SCALE
                            )
                    ),
                    new Vec3(0.0D, 1.0D, 0.0D)
            );
            return GrabPose.attachedTo(
                    worldAnchor,
                    GrabPose.frameFromAxes(
                            worldVector(leverAxis),
                            worldVector(trainHingeAxis)
                    )
            );
        }
        if (currentGeometry == null || controlKind == null) {
            return GrabPose.anchoredAt(worldAnchor);
        }

        if (controlKind.rotary()) {
            Vec3 localRadial = currentWheelGripRadial;
            if (localRadial == null) {
                localRadial = ControlMotionMath.normalizedOr(
                        ControlMotionMath.projectOntoPlane(
                                localContact.subtract(currentGeometry.pivot()),
                                currentGeometry.hingeAxis()
                        ),
                        currentGeometry.motionAxis()
                );
            }
            return GrabPose.attachedTo(
                    worldAnchor,
                    GrabPose.wheelFrame(
                            worldVector(currentGeometry.hingeAxis()),
                            worldVector(localRadial)
                    )
            );
        }

        Vec3 localGrip = currentLeverGripVector == null
                ? localContact.subtract(currentGeometry.pivot())
                : currentLeverGripVector;
        return GrabPose.attachedTo(
                worldAnchor,
                GrabPose.frameFromAxes(
                        worldVector(localGrip),
                        worldVector(currentGeometry.hingeAxis())
                )
        );
    }

    private void beginLever(Vec3 localHand, LocalGeometry currentGeometry) {
        Vec3 hinge = currentGeometry.hingeAxis();
        Vec3 contactFromPivot = localContact.subtract(currentGeometry.pivot());
        Vec3 grip = ControlMotionMath.projectOntoPlane(contactFromPivot, hinge);
        if (grip.lengthSqr() <= EPSILON_SQUARED) {
            // Only a mathematically degenerate pivot hit needs a fallback;
            // every real contact keeps its exact local radius and angle.
            grip = currentGeometry.motionAxis().scale(0.08D);
        }
        Vec3 hand = ControlMotionMath.projectOntoPlane(
                localHand.subtract(currentGeometry.pivot()),
                hinge
        );
        initialLeverGripVector = grip;
        currentLeverGripVector = grip;
        initialLeverHandVector = ControlMotionMath.normalizedOr(hand, grip);
        leverHingeOffset = contactFromPivot.dot(hinge);
    }

    private void beginRotary(Vec3 localHand, LocalGeometry currentGeometry) {
        Vec3 axle = currentGeometry.hingeAxis();
        Vec3 contactFromPivot = localContact.subtract(currentGeometry.pivot());
        Vec3 contactRadial = ControlMotionMath.projectOntoPlane(contactFromPivot, axle);
        wheelGripRadius = contactRadial.length();
        currentWheelGripRadial = ControlMotionMath.normalizedOr(
                contactRadial,
                currentGeometry.motionAxis()
        );
        wheelAxialOffset = contactFromPivot.dot(axle);

        Vec3 handRadial = ControlMotionMath.projectOntoPlane(
                localHand.subtract(currentGeometry.pivot()),
                axle
        );
        previousWheelHandRadial = ControlMotionMath.normalizedOr(
                handRadial,
                currentWheelGripRadial
        );
    }

    private void continueLever(LocalPlayer player, HandType hand, Vec3 localHand) {
        LocalGeometry currentGeometry = geometry;
        Vec3 initialGrip = initialLeverGripVector;
        Vec3 initialHandVector = initialLeverHandVector;
        Vec3 startHand = initialLocalHand;
        Vec3 lastHand = previousLocalHand;
        if (currentGeometry == null || initialGrip == null
                || initialHandVector == null || startHand == null || lastHand == null) {
            return;
        }

        Vec3 physicalHand = ControlMotionMath.projectOntoPlane(
                localHand.subtract(currentGeometry.pivot()),
                currentGeometry.hingeAxis()
        );
        double angle = ControlMotionMath.signedAngle(
                initialHandVector,
                physicalHand,
                currentGeometry.hingeAxis()
        );
        angle = ControlMotionMath.clamp(
                angle,
                Math.toRadians(-52.0D),
                Math.toRadians(52.0D)
        );
        currentLeverGripVector = ControlMotionMath.rotateAroundAxis(
                initialGrip,
                currentGeometry.hingeAxis(),
                angle
        );
        currentLocalAnchor = currentGeometry.pivot()
                .add(currentLeverGripVector)
                .add(currentGeometry.hingeAxis().scale(leverHingeOffset));

        double tickTravel = localHand.subtract(lastHand).dot(currentGeometry.motionAxis());
        accumulatedLeverTravel += tickTravel;
        double totalTravel = localHand.subtract(startHand).dot(currentGeometry.motionAxis());
        double arcTravel = Math.abs(angle) * initialGrip.length();

        if (controlKind == GenericControlKind.BINARY_LEVER) {
            if (!binaryTriggered
                    && Math.max(Math.abs(totalTravel), arcTravel) >= LEVER_TRIGGER_TRAVEL) {
                // A transiently declined client preview can retry after the
                // normal detent rate limit; a consumed toggle happens once.
                binaryTriggered = dispatchInteraction(player, hand, true)
                        .consumesAction();
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
                    && !dispatchInteraction(player, hand, true)
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

    private void continueRotary(LocalPlayer player, HandType hand, Vec3 localHand) {
        LocalGeometry currentGeometry = geometry;
        Vec3 previousHandRadial = previousWheelHandRadial;
        Vec3 gripRadial = currentWheelGripRadial;
        if (currentGeometry == null || previousHandRadial == null || gripRadial == null) {
            return;
        }

        Vec3 handRadial = ControlMotionMath.projectOntoPlane(
                localHand.subtract(currentGeometry.pivot()),
                currentGeometry.hingeAxis()
        );
        if (handRadial.lengthSqr() < 0.025D * 0.025D) {
            return;
        }
        handRadial = handRadial.normalize();
        double delta = ControlMotionMath.signedAngle(
                previousHandRadial,
                handRadial,
                currentGeometry.hingeAxis()
        );
        if (Math.abs(delta) <= MAXIMUM_ROTATION_PER_TICK) {
            accumulatedWheelAngle += delta;
            currentWheelGripRadial = ControlMotionMath.rotateAroundAxis(
                    gripRadial,
                    currentGeometry.hingeAxis(),
                    delta
            ).normalize();
        }
        previousWheelHandRadial = handRadial;
        currentLocalAnchor = currentGeometry.pivot()
                .add(currentWheelGripRadial.scale(wheelGripRadius))
                .add(currentGeometry.hingeAxis().scale(wheelAxialOffset));

        int attempts = 0;
        while (Math.abs(accumulatedWheelAngle) >= ROTARY_DETENT_RADIANS
                && attempts++ < 2
                && actionCount < MAXIMUM_ACTIONS_PER_GRAB) {
            boolean directionMatches = ControlMotionMath.detentMatchesModifier(
                    accumulatedWheelAngle,
                    player.isShiftKeyDown()
            );
            if (directionMatches
                    && !dispatchInteraction(player, hand, true)
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

    private InteractionResult dispatchInteraction(
            LocalPlayer player,
            HandType hand,
            boolean enforceRateLimit
    ) {
        if (!validBaseTarget(player)) {
            return InteractionResult.PASS;
        }
        long gameTime = contraption.level().getGameTime();
        if (enforceRateLimit
                && lastUseGameTime != Long.MIN_VALUE
                && gameTime - lastUseGameTime < MINIMUM_USE_INTERVAL_TICKS) {
            return InteractionResult.PASS;
        }
        if (enforceRateLimit && actionCount >= MAXIMUM_ACTIONS_PER_GRAB) {
            return InteractionResult.PASS;
        }
        if (enforceRateLimit) {
            // Rate-limit attempts as well as successes; a foreign interactor
            // that temporarily declines cannot become a packet flood.
            lastUseGameTime = gameTime;
        }

        var interactionHand = hand.asInteractionHand();
        if (!contraption.handlePlayerInteraction(
                player,
                localPos,
                interactionFace,
                interactionHand
        )) {
            return InteractionResult.PASS;
        }
        CatnipServices.NETWORK.sendToServer(new ContraptionInteractionPacket(
                contraption,
                interactionHand,
                localPos,
                interactionFace
        ));
        if (enforceRateLimit) {
            actionCount++;
        }
        return InteractionResult.SUCCESS;
    }

    private boolean validHeldControl(LocalPlayer player) {
        if (!validBaseTarget(player)
                || controlKind == null
                || !controlKind.genericManipulationSafe()
                || !hasMovingInteractor()) {
            return false;
        }
        BlockState state = currentState();
        return state != null
                && GenericControlClassifier.controlKind(state).orElse(null) == controlKind;
    }

    private boolean validBaseTarget(LocalPlayer player) {
        if (Minecraft.getInstance().level != contraption.level()
                || !contraption.isAliveOrStale()
                || contraption.getContraption() == null
                || !contraption.canInteractWithBlock(player, localPos, 0.75D)) {
            return false;
        }
        BlockState state = currentState();
        return state != null
                && blockId.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    private boolean hasMovingInteractor() {
        return contraption.getContraption() != null
                && contraption.getContraption().getInteractors().containsKey(localPos);
    }

    private InteractionResult beginTrainControlsGrab(
            LocalPlayer player,
            HandType hand,
            Vec3 handPosition
    ) {
        if (!validBaseTarget(player)
                || !hasMovingInteractor()
                || !finite(handPosition)
                || ControlsHandler.getContraption() != null
                || !TrainControlsInputOverride.claim(this)) {
            return InteractionResult.PASS;
        }

        Vec3 localHand = contraption.toLocalVector(handPosition, 1.0F);
        BlockState state = currentState();
        Direction facing = state == null ? null : directionProperty(state, "facing");
        if (!finite(localHand)
                || facing == null
                || !facing.getAxis().isHorizontal()) {
            TrainControlsInputOverride.release(this);
            return InteractionResult.PASS;
        }

        Vec3 blockCenter = new Vec3(
                localPos.getX() + 0.5D,
                localPos.getY() + 0.5D,
                localPos.getZ() + 0.5D
        );
        Vec3 lateral = directionVector(facing.getClockWise());
        double canonicalX = 0.5D
                + localContact.subtract(blockCenter).dot(lateral);
        trainSpeedLever = canonicalX < 0.5D;
        trainMotionAxis = directionVector(facing.getOpposite());
        trainHingeAxis = lateral;
        trainLeverTravel = 0.0D;
        initialLocalHand = localHand;
        previousLocalHand = localHand;
        currentLocalAnchor = localContact;

        InteractionResult result;
        try {
            result = dispatchInteraction(player, hand, false);
        } catch (LinkageError | RuntimeException exception) {
            TrainControlsInputOverride.release(this);
            try {
                stopOwnedTrainControlSession();
            } catch (LinkageError | RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
            } finally {
                clearHeldState();
            }
            throw exception;
        }
        if (!result.consumesAction() || !ownsTrainControlSession()) {
            TrainControlsInputOverride.release(this);
            try {
                stopOwnedTrainControlSession();
            } finally {
                clearHeldState();
            }
            return InteractionResult.PASS;
        }

        active = true;
        return InteractionResult.SUCCESS;
    }

    private void continueTrainControls(Vec3 localHand) {
        Vec3 start = initialLocalHand;
        Vec3 motion = trainMotionAxis;
        if (start == null
                || motion == null
                || !TrainControlsInputOverride.isOwner(this)) {
            return;
        }

        trainLeverTravel = ControlMotionMath.clamp(
                localHand.subtract(start).dot(motion),
                -MAXIMUM_TRAIN_LEVER_TRAVEL,
                MAXIMUM_TRAIN_LEVER_TRAVEL
        );
        boolean positive = trainLeverTravel > TRAIN_LEVER_DEAD_ZONE;
        boolean negative = trainLeverTravel < -TRAIN_LEVER_DEAD_ZONE;
        TrainControlsInputOverride.update(
                this,
                trainSpeedLever && positive,
                trainSpeedLever && negative,
                !trainSpeedLever && negative,
                !trainSpeedLever && positive
        );
        currentLocalAnchor = localContact.add(motion.scale(trainLeverTravel));
    }

    private boolean ownsTrainControlSession() {
        return ControlsHandler.getContraption() == contraption
                && localPos.equals(ControlsHandler.getControlsPos());
    }

    /** Lets the virtual-input owner recover after an external Create teardown. */
    boolean hasLiveTrainOverrideSession() {
        return active && trainControls && ownsTrainControlSession();
    }

    /**
     * Mirrors Create's own escape/removal teardown: release native keys, then
     * send its dedicated stop flag. Unlike a second use interaction, this
     * cannot be rejected by ordinary reach validation or accidentally toggle
     * a desynchronised controller back on.
     */
    private void stopOwnedTrainControlSession() {
        if (!ownsTrainControlSession()) {
            return;
        }
        try {
            ControlsHandler.stopControlling();
        } finally {
            if (Minecraft.getInstance().level == contraption.level()) {
                CatnipServices.NETWORK.sendToServer(new ControlsInputPacket(
                        List.of(),
                        false,
                        contraption.getId(),
                        localPos,
                        true
                ));
            }
        }
    }

    private @Nullable BlockState currentState() {
        if (contraption.getContraption() == null) {
            return null;
        }
        var info = contraption.getContraption().getBlocks().get(localPos);
        return info == null ? null : info.state();
    }

    private LocalGeometry describeGeometry(LocalPlayer player, BlockState state) {
        AABB bounds = new AABB(localPos);
        try {
            VoxelShape shape = state.getShape(
                    contraption.getContraption().getContraptionWorld(),
                    localPos,
                    CollisionContext.of(player)
            );
            if (!shape.isEmpty()) {
                bounds = shape.bounds().move(localPos);
            }
        } catch (LinkageError | RuntimeException ignored) {
            // The exact ray-traced contact remains authoritative even when a
            // third-party shape needs a world implementation unavailable in
            // assembled space. Unit-block bounds are only a pivot fallback.
        }

        Direction facing = directionProperty(state, "facing");
        Vec3 outward = attachedOutward(state, facing, interactionFace);
        Vec3 facingVector = directionVector(facing == null ? interactionFace : facing);
        Vec3 motion = ControlMotionMath.projectOntoPlane(facingVector, outward);
        if (motion.lengthSqr() <= EPSILON_SQUARED) {
            motion = ControlMotionMath.projectOntoPlane(
                    new Vec3(0.0D, 1.0D, 0.0D),
                    outward
            );
        }
        if (motion.lengthSqr() <= EPSILON_SQUARED) {
            motion = ControlMotionMath.projectOntoPlane(
                    new Vec3(1.0D, 0.0D, 0.0D),
                    outward
            );
        }
        motion = ControlMotionMath.normalizedOr(motion, new Vec3(0.0D, 0.0D, 1.0D));

        Vec3 center = bounds.getCenter();
        if (controlKind != null && controlKind.rotary()) {
            return new LocalGeometry(center, outward, motion, outward);
        }
        double supportExtent = projectedHalfExtent(bounds, outward);
        Vec3 pivot = center.subtract(outward.scale(supportExtent * 0.78D));
        Vec3 hinge = ControlMotionMath.normalizedOr(
                outward.cross(motion),
                new Vec3(1.0D, 0.0D, 0.0D)
        );
        return new LocalGeometry(pivot, outward, motion, hinge);
    }

    private Vec3 worldPoint(Vec3 localPoint, Vec3 fallback) {
        if (!contraption.isAliveOrStale() || contraption.getContraption() == null) {
            return fallback;
        }
        Vec3 transformed = contraption.toGlobalVector(localPoint, 1.0F);
        return finite(transformed) ? transformed : fallback;
    }

    private Vec3 worldVector(Vec3 localVector) {
        Vec3 transformed = contraption.applyRotation(localVector, 1.0F);
        return ControlMotionMath.normalizedOr(transformed, localVector);
    }

    private void clearHeldState() {
        active = false;
        geometry = null;
        initialLocalHand = null;
        previousLocalHand = null;
        initialLeverGripVector = null;
        initialLeverHandVector = null;
        currentLeverGripVector = null;
        previousWheelHandRadial = null;
        currentWheelGripRadial = null;
        currentLocalAnchor = null;
        trainSpeedLever = false;
        trainMotionAxis = null;
        trainHingeAxis = null;
        trainLeverTravel = 0.0D;
    }

    private static Vec3 attachedOutward(
            BlockState state,
            @Nullable Direction facing,
            Direction fallback
    ) {
        Object faceValue = propertyValue(state, "face");
        if (faceValue instanceof AttachFace face) {
            return switch (face) {
                case FLOOR -> new Vec3(0.0D, 1.0D, 0.0D);
                case CEILING -> new Vec3(0.0D, -1.0D, 0.0D);
                case WALL -> directionVector(facing == null ? fallback : facing);
            };
        }
        return directionVector(facing == null ? fallback : facing);
    }

    private static @Nullable Direction directionProperty(BlockState state, String name) {
        Object value = propertyValue(state, name);
        return value instanceof Direction direction ? direction : null;
    }

    private static @Nullable Object propertyValue(BlockState state, String name) {
        for (Property<?> property : state.getProperties()) {
            if (name.equals(property.getName())) {
                return readProperty(state, property);
            }
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Comparable<?> readProperty(BlockState state, Property<?> property) {
        return state.getValue((Property) property);
    }

    private static double projectedHalfExtent(AABB bounds, Vec3 direction) {
        return Math.abs(direction.x) * bounds.getXsize() * 0.5D
                + Math.abs(direction.y) * bounds.getYsize() * 0.5D
                + Math.abs(direction.z) * bounds.getZsize() * 0.5D;
    }

    private static Vec3 directionVector(Direction direction) {
        return new Vec3(
                direction.getStepX(),
                direction.getStepY(),
                direction.getStepZ()
        );
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x)
                && Double.isFinite(value.y)
                && Double.isFinite(value.z);
    }

    private record LocalGeometry(
            Vec3 pivot,
            Vec3 outwardNormal,
            Vec3 motionAxis,
            Vec3 hingeAxis
    ) {
    }

    private record TargetKey(int entityId, BlockPos localPos) {
    }
}
