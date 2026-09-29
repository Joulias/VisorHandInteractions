package org.vmstudio.visorhandinteractions.core.client.interaction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.vmstudio.visor.api.client.events.SessionStateChangedVREvent;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.visor.api.client.events.input.ActionButtonVREvent;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseType;
import org.vmstudio.visor.api.client.tasks.TaskType;
import org.vmstudio.visor.api.client.tasks.VisorTask;
import org.vmstudio.visor.api.common.HandType;
import org.vmstudio.visor.api.common.addon.VisorAddon;
import org.vmstudio.visor.api.common.eventbus.listener.VREventHandler;
import org.vmstudio.visor.api.common.eventbus.listener.VREventListener;
import org.vmstudio.visor.api.common.utils.VRMathUtils;
import org.vmstudio.visorhandinteractions.core.client.config.HandInteractionConfig;
import org.vmstudio.visorhandinteractions.core.client.input.GrabAction;
import org.vmstudio.visorhandinteractions.core.client.pose.GrabPose;
import org.vmstudio.visorhandinteractions.core.client.pose.GrabPoseProvider;
import org.vmstudio.visorhandinteractions.core.client.pose.HandPoseLockManager;
import org.vmstudio.visorhandinteractions.core.common.VisorHandInteractions;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Runs the physical contact and grab state machine on the VR player tick. */
public final class PhysicalInteractionTask extends VisorTask implements VREventListener {
    public static final String ID = "visor_hand_interactions_physical";

    private static final Set<String> MAIN_GRIP_CONFLICTS = Set.of(
            "hotbar_main",
            "mouse_middle_main"
    );
    private static final Set<String> OFFHAND_GRIP_CONFLICTS = Set.of(
            "hotbar_offhand",
            "mouse_middle_offhand"
    );

    private final Minecraft minecraft = Minecraft.getInstance();
    private final HandInteractionConfig config;
    private final EnumMap<HandType, GrabAction> grabActions;
    private final EnumMap<HandType, HandState> handStates = new EnumMap<>(HandType.class);
    private final Map<Object, Long> lastSuccessfulUse = new HashMap<>();
    private final Set<String> suppressedConflictPresses = new HashSet<>();

    private ClientLevel activeLevel;
    private LocalPlayer lastPlayer;

    public PhysicalInteractionTask(
            @NotNull VisorAddon owner,
            @NotNull HandInteractionConfig config,
            @NotNull GrabAction mainGrab,
            @NotNull GrabAction offhandGrab
    ) {
        super(owner);
        this.config = config;
        this.grabActions = new EnumMap<>(HandType.class);
        this.grabActions.put(HandType.MAIN, mainGrab);
        this.grabActions.put(HandType.OFFHAND, offhandGrab);
        for (HandType hand : HandType.values()) {
            handStates.put(hand, new HandState());
        }
        VisorAPI.eventBus().registerListener(owner, this);
        HandPoseLockManager.register(owner);
    }

    @Override
    protected void onRun(@Nullable LocalPlayer nullablePlayer) {
        if (nullablePlayer == null || minecraft.level == null) {
            return;
        }
        LocalPlayer player = nullablePlayer;
        ClientLevel level = minecraft.level;
        if (activeLevel != level) {
            clearRuntimeState(lastPlayer);
            activeLevel = level;
        }
        lastPlayer = player;

        var vrPlayer = VisorAPI.client().getVRLocalPlayer();
        var pose = vrPlayer.getPoseData(PlayerPoseType.TICK);
        double worldScale = Math.max(0.05D, pose.getWorldScale());
        double radius = config.contactRadiusMetres() * worldScale;
        double probeOffset = config.fingertipOffsetMetres() * worldScale;
        double maximumSweepSquared = Math.pow(Math.max(0.75D, worldScale * 1.5D), 2.0D);

        for (HandType hand : HandType.values()) {
            HandState state = handStates.get(hand);
            if (!vrPlayer.getRawController(hand).isTracking() || handHasOverlayFocus(hand)) {
                clearHandState(player, hand, state);
                continue;
            }

            // Grip-space tracks the physical palm/controller body and is more
            // stable for contact than Visor's aim-space ray origin.
            var handPose = pose.getGripHand(hand);
            Vec3 direction = handPose.getCustomVector3(VRMathUtils.BACK_VECTOR);
            if (direction.lengthSqr() > 1.0E-9D) {
                direction = direction.normalize();
            }
            Vec3 probe = handPose.getPositionVec3().add(direction.scale(probeOffset));
            if (!isFinite(probe)) {
                clearHandState(player, hand, state);
                continue;
            }

            Vec3 sweepStart = state.previousProbe;
            if (sweepStart == null
                    || !isFinite(sweepStart)
                    || sweepStart.distanceToSqr(probe) > maximumSweepSquared) {
                sweepStart = probe;
            }
            state.previousProbe = probe;

            Optional<InteractionTarget> touchTarget = findTouchTarget(
                    level,
                    player,
                    sweepStart,
                    probe,
                    radius
            );
            Optional<InteractionTarget> grabTarget = findGrabTarget(
                    level,
                    player,
                    sweepStart,
                    probe,
                    radius
            );
            GrabAction grabAction = grabActions.get(hand);
            state.hoverKey = config.grabEnabled()
                    ? grabTarget
                    .filter(target -> grabItemPolicyAllows(player, hand, target))
                    .filter(target -> canInteract(player, level, target))
                    .map(InteractionTarget::key)
                    .orElse(null)
                    : null;
            boolean grabHeld = config.grabEnabled()
                    && grabAction != null
                    && grabAction.isPressed();

            processGrab(player, level, hand, state, grabTarget, probe);
            if (!grabHeld) {
                processTouch(player, level, hand, state, touchTarget);
            } else {
                synchronizeTouchLatch(state, touchTarget);
            }
        }

        long gameTime = level.getGameTime();
        if (gameTime % 200L == 0L) {
            long oldest = gameTime - Math.max(40L, config.interactionCooldownTicks() * 4L);
            lastSuccessfulUse.entrySet().removeIf(entry -> entry.getValue() < oldest);
        }
    }

    private Optional<InteractionTarget> findTouchTarget(
            ClientLevel level,
            LocalPlayer player,
            Vec3 from,
            Vec3 to,
            double radius
    ) {
        Optional<BlockInteractionTarget> nearestBlocker = ExactHandContactFinder.findNearest(
                level,
                player,
                from,
                to,
                radius,
                (pos, state) -> true
        );
        Optional<InteractionTarget> block = ExactHandContactFinder.findNearest(
                level,
                player,
                from,
                to,
                radius,
                (pos, state) -> isAutoTouchBlock(level, pos, state)
        ).map(target -> target);

        Optional<InteractionTarget> externalClaim = findExternalTarget(
                level,
                player,
                from,
                to,
                radius
        );
        if (externalClaim.isPresent()
                && !externalClaim.get().allowTouch()
                && (block.isEmpty()
                || externalClaim.get().sweepFraction()
                <= block.get().sweepFraction() + 1.0E-7D)) {
            // Continuous-control adapters can claim a backing block while
            // deliberately forbidding auto-touch, preventing an unowned hold.
            return Optional.empty();
        }
        Optional<InteractionTarget> external = externalClaim
                .filter(InteractionTarget::allowTouch);
        if (external.isPresent()
                && nearestBlocker.isPresent()
                && !external.get().isBackedBy(nearestBlocker.get().blockPos())
                && nearestBlocker.get().sweepFraction()
                + 1.0E-7D < external.get().sweepFraction()) {
            external = Optional.empty();
        }
        // A specialized bridge wins exact ties with its generic block target.
        return nearer(external, block);
    }

    private Optional<InteractionTarget> findGrabTarget(
            ClientLevel level,
            LocalPlayer player,
            Vec3 from,
            Vec3 to,
            double radius
    ) {
        Optional<BlockInteractionTarget> nearestBlocker = ExactHandContactFinder.findNearest(
                level,
                player,
                from,
                to,
                radius,
                (pos, state) -> true
        );
        Optional<InteractionTarget> preferredBlock = ExactHandContactFinder.findNearest(
                level,
                player,
                from,
                to,
                radius,
                (pos, state) -> isPreferredGrabBlock(level, pos, state)
        ).map(target -> target);
        Optional<InteractionTarget> external = findExternalTarget(
                level,
                player,
                from,
                to,
                radius
        );

        if (external.isPresent()
                && nearestBlocker.isPresent()
                && !external.get().isBackedBy(nearestBlocker.get().blockPos())
                && nearestBlocker.get().sweepFraction()
                + 1.0E-7D < external.get().sweepFraction()) {
            external = Optional.empty();
        }

        if (external.isPresent()
                && !external.get().allowGrab()
                && (preferredBlock.isEmpty()
                || external.get().sweepFraction()
                <= preferredBlock.get().sweepFraction() + 1.0E-7D)) {
            // A native-hold safety claim blocks the ordinary block-use
            // fallback without becoming a hoverable/grabbable target itself.
            return Optional.empty();
        }
        external = external.filter(InteractionTarget::allowGrab);

        // Specialized continuous controls must win ties with the same block.
        return nearer(external, preferredBlock);
    }

    private Optional<InteractionTarget> findExternalTarget(
            ClientLevel level,
            LocalPlayer player,
            Vec3 from,
            Vec3 to,
            double radius
    ) {
        Optional<InteractionTarget> nearest = Optional.empty();
        for (InteractionBridge bridge : InteractionBridgeRegistry.bridges()) {
            try {
                nearest = nearer(nearest, bridge.findTarget(level, player, from, to, radius));
            } catch (LinkageError | RuntimeException exception) {
                VisorHandInteractions.LOGGER.warn(
                        "Disabling one failed physical interaction lookup for this tick",
                        exception
                );
            }
        }
        return nearest;
    }

    private void processTouch(
            LocalPlayer player,
            ClientLevel level,
            HandType hand,
            HandState state,
            Optional<InteractionTarget> target
    ) {
        boolean canTouch = config.touchEnabled()
                && (!config.requireEmptyHandForTouch()
                || player.getItemInHand(hand.asInteractionHand()).isEmpty());
        if (!canTouch || target.isEmpty()) {
            if (++state.touchMisses > config.releaseHysteresisTicks()) {
                state.touchKey = null;
            }
            return;
        }

        InteractionTarget current = target.get();
        state.touchMisses = 0;
        if (Objects.equals(state.touchKey, current.key())) {
            return;
        }
        state.touchKey = current.key();
        if (isActivelyGrabbed(current.key())) {
            return;
        }

        if (!canInteract(player, level, current) || isCoolingDown(level, current.key())) {
            return;
        }
        performInteraction(player, level, hand, current, false, current.worldLocation());
    }

    private void synchronizeTouchLatch(
            HandState state,
            Optional<InteractionTarget> target
    ) {
        if (target.isPresent()) {
            state.touchKey = target.get().key();
            state.touchMisses = 0;
        } else if (++state.touchMisses > config.releaseHysteresisTicks()) {
            state.touchKey = null;
        }
    }

    private void processGrab(
            LocalPlayer player,
            ClientLevel level,
            HandType hand,
            HandState state,
            Optional<InteractionTarget> target,
            Vec3 probe
    ) {
        GrabAction action = grabActions.get(hand);
        boolean pressed = action != null && action.isPressed();
        InteractionTarget itemPolicyTarget = state.activeGrab != null
                ? state.activeGrab
                : target.orElse(null);
        boolean eligible = config.grabEnabled()
                && grabItemPolicyAllows(player, hand, itemPolicyTarget);

        if (!eligible || action == null) {
            cancelGrab(player, hand, state);
            state.grabAttemptKey = null;
            state.grabMisses = 0;
            return;
        }

        if (!pressed) {
            finishGrabActionRelease(player, hand, state);
            state.grabAttemptKey = null;
            state.grabMisses = 0;
            return;
        }

        if (state.activeGrab != null) {
            Vec3 previous = state.lastGrabProbe == null ? probe : state.lastGrabProbe;
            try {
                // Once grabbed, retain the original target while the grip is
                // held. Pulling a lever naturally moves the physical probe
                // away from its thin collision shape; hover is not ownership.
                if (!state.activeGrab.canContinueGrab(player, hand)) {
                    Object completedKey = state.activeGrab.key();
                    boolean passive = state.activeGrab.beginGrabIsPassive();
                    cancelGrab(player, hand, state);
                    // Passive continuous targets may become valid again after
                    // a transient rebuild. One-shot targets stay latched until
                    // grip release so cooldown expiry cannot fire them twice.
                    state.grabAttemptKey = passive ? null : completedKey;
                    state.grabMisses = 0;
                    return;
                }
                state.activeGrab.continueGrab(
                        player,
                        hand,
                        probe,
                        probe.subtract(previous)
                );
                state.lastGrabProbe = probe;
                state.grabMisses = 0;
                updateVisualGrabPose(player, hand, state.activeGrab, probe);
            } catch (LinkageError | RuntimeException exception) {
                VisorHandInteractions.LOGGER.error(
                        "Physical grab update failed; cancelling the target",
                        exception
                );
                cancelGrab(player, hand, state);
                state.grabAttemptKey = null;
            }
            return;
        }

        if (target.isEmpty()) {
            return;
        }

        InteractionTarget current = target.get();
        if (Objects.equals(state.grabAttemptKey, current.key())) {
            return;
        }
        if (isGrabbedByOtherHand(hand, current.key())) {
            return;
        }
        state.grabMisses = 0;

        if (!canInteract(player, level, current)
                || (!current.beginGrabIsPassive()
                && isCoolingDown(level, current.key()))) {
            return;
        }
        state.grabAttemptKey = current.key();
        if (performInteraction(player, level, hand, current, true, probe)) {
            state.activeGrab = current;
            state.lastGrabProbe = probe;
            beginVisualGrabPose(player, hand, current, probe);
        } else {
            // Native/sublevel ownership can fail transiently while geometry
            // remains valid; permit another begin attempt on the next tick.
            state.grabAttemptKey = null;
        }
    }

    private boolean grabItemPolicyAllows(
            LocalPlayer player,
            HandType hand,
            @Nullable InteractionTarget target
    ) {
        return !config.requireEmptyHandForGrab()
                || player.getItemInHand(hand.asInteractionHand()).isEmpty()
                || (target != null && target.allowsNonEmptyHandGrab(player, hand));
    }

    private void beginVisualGrabPose(
            LocalPlayer player,
            HandType hand,
            InteractionTarget target,
            Vec3 trackedHandAnchor
    ) {
        try {
            HandPoseLockManager.begin(
                    hand,
                    resolveGrabPose(player, hand, target, trackedHandAnchor),
                    trackedHandAnchor
            );
        } catch (LinkageError | RuntimeException exception) {
            VisorHandInteractions.LOGGER.warn(
                    "Could not begin the visual hand lock for target {}",
                    target.key(),
                    exception
            );
            HandPoseLockManager.release(hand);
        }
    }

    private void updateVisualGrabPose(
            LocalPlayer player,
            HandType hand,
            InteractionTarget target,
            Vec3 trackedHandAnchor
    ) {
        try {
            GrabPose pose = resolveGrabPose(player, hand, target, trackedHandAnchor);
            if (HandPoseLockManager.isLocked(hand)) {
                HandPoseLockManager.update(hand, pose);
            } else {
                // A provider can fail for a frame while a moving sublevel is
                // being rebuilt. Re-establish the visual lock on the next
                // successful update instead of leaving the hand unsnapped.
                HandPoseLockManager.begin(hand, pose, trackedHandAnchor);
            }
        } catch (LinkageError | RuntimeException exception) {
            VisorHandInteractions.LOGGER.warn(
                    "Could not update the visual hand lock for target {}",
                    target.key(),
                    exception
            );
            HandPoseLockManager.release(hand);
        }
    }

    private GrabPose resolveGrabPose(
            LocalPlayer player,
            HandType hand,
            InteractionTarget target,
            Vec3 trackedHandAnchor
    ) {
        if (target instanceof GrabPoseProvider provider) {
            return provider.grabPose(player, hand, trackedHandAnchor);
        }
        return GrabPose.anchoredAt(target.worldLocation());
    }

    private boolean performInteraction(
            LocalPlayer player,
            ClientLevel level,
            HandType hand,
            InteractionTarget target,
            boolean grab,
            Vec3 handPosition
    ) {
        var vrPlayer = VisorAPI.client().getVRLocalPlayer();
        if (vrPlayer.getActiveHand() != hand) {
            vrPlayer.setActiveHand(hand);
        }

        try {
            InteractionResult result = grab
                    ? target.beginGrab(player, hand, handPosition)
                    : target.touch(player, hand);
            if (!result.consumesAction()) {
                return false;
            }
            lastSuccessfulUse.put(target.key(), level.getGameTime());
            if (config.hapticsEnabled()) {
                VisorAPI.client().getInputManager().triggerHapticPulseClick(hand);
            }
            return true;
        } catch (LinkageError | RuntimeException exception) {
            VisorHandInteractions.LOGGER.error(
                    "Physical interaction failed for target {}",
                    target.key(),
                    exception
            );
            if (grab) {
                try {
                    target.cancelGrab(player, hand);
                } catch (LinkageError | RuntimeException cancelException) {
                    VisorHandInteractions.LOGGER.warn(
                            "Could not cancel failed physical grab",
                            cancelException
                    );
                }
            }
            return false;
        }
    }

    private boolean canInteract(
            LocalPlayer player,
            ClientLevel level,
            InteractionTarget target
    ) {
        double reach = player.blockInteractionRange() + 0.75D;
        if (player.getEyePosition().distanceToSqr(target.worldLocation()) > reach * reach) {
            return false;
        }
        if (!hasLineOfSight(player, level, target)) {
            return false;
        }
        if (target instanceof BlockInteractionTarget blockTarget) {
            BlockPos pos = blockTarget.blockPos();
            return player.canInteractWithBlock(pos, 0.0D)
                    && level.mayInteract(player, pos)
                    && level.getWorldBorder().isWithinBounds(pos);
        }
        return true;
    }

    private boolean hasLineOfSight(
            LocalPlayer player,
            ClientLevel level,
            InteractionTarget target
    ) {
        Vec3 origin = player.getEyePosition();
        Vec3 destination = target.worldLocation();
        if (origin.distanceToSqr(destination) <= 1.0E-12D) {
            return true;
        }
        BlockHitResult hit = level.clip(new ClipContext(
                origin,
                destination,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
        ));
        if (hit.getType() == HitResult.Type.MISS
                || target.isBackedBy(hit.getBlockPos())) {
            return true;
        }
        double hitDistanceSquared = origin.distanceToSqr(hit.getLocation());
        double targetDistanceSquared = origin.distanceToSqr(destination);
        return hitDistanceSquared + 1.0E-7D >= targetDistanceSquared;
    }

    private boolean isActivelyGrabbed(Object key) {
        for (HandState state : handStates.values()) {
            if (state.activeGrab != null && Objects.equals(state.activeGrab.key(), key)) {
                return true;
            }
        }
        return false;
    }

    private boolean isGrabbedByOtherHand(HandType hand, Object key) {
        for (Map.Entry<HandType, HandState> entry : handStates.entrySet()) {
            if (entry.getKey() != hand
                    && entry.getValue().activeGrab != null
                    && Objects.equals(entry.getValue().activeGrab.key(), key)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCoolingDown(ClientLevel level, Object key) {
        Long usedAt = lastSuccessfulUse.get(key);
        return usedAt != null
                && level.getGameTime() - usedAt < config.interactionCooldownTicks();
    }

    private boolean isAutoTouchBlock(ClientLevel level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof LeverBlock || state.getBlock() instanceof ButtonBlock) {
            return true;
        }

        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (config.isTouchBlock(id.toString())) {
            return true;
        }
        return config.isTouchNamespace(id.getNamespace())
                && state.getMenuProvider(level, pos) == null;
    }

    private boolean isPreferredGrabBlock(ClientLevel level, BlockPos pos, BlockState state) {
        if (isAutoTouchBlock(level, pos, state)) {
            return true;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return config.isTouchNamespace(id.getNamespace())
                || state.getMenuProvider(level, pos) != null;
    }

    private boolean handHasOverlayFocus(HandType hand) {
        return VisorAPI.client().getGuiManager().getCursorHandler()
                .getFocusedOverlay(hand, true) != null;
    }

    private static Optional<InteractionTarget> nearer(
            Optional<? extends InteractionTarget> first,
            Optional<? extends InteractionTarget> second
    ) {
        if (first.isEmpty()) {
            return second.map(target -> target);
        }
        if (second.isEmpty()) {
            return first.map(target -> target);
        }
        return first.get().sweepFraction() <= second.get().sweepFraction()
                ? Optional.of(first.get())
                : Optional.of(second.get());
    }

    private static boolean isFinite(Vec3 vector) {
        return Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }

    private void endGrab(LocalPlayer player, HandType hand, HandState state) {
        if (state.activeGrab != null) {
            try {
                state.activeGrab.endGrab(player, hand);
            } catch (LinkageError | RuntimeException exception) {
                VisorHandInteractions.LOGGER.warn("Could not release physical grab", exception);
            }
        }
        state.activeGrab = null;
        state.lastGrabProbe = null;
        HandPoseLockManager.release(hand);
    }

    private void finishGrabActionRelease(
            LocalPlayer player,
            HandType hand,
            HandState state
    ) {
        if (state.activeGrab == null) {
            endGrab(player, hand, state);
            return;
        }

        try {
            // A release only commits while the original target is still a
            // valid live grab. If invalidation and button release land on the
            // same tick, invalidation wins and the control is cancelled.
            if (!state.activeGrab.canContinueGrab(player, hand)) {
                cancelGrab(player, hand, state);
                return;
            }
        } catch (LinkageError | RuntimeException exception) {
            VisorHandInteractions.LOGGER.warn(
                    "Could not validate physical grab release; cancelling the target",
                    exception
            );
            cancelGrab(player, hand, state);
            return;
        }

        endGrab(player, hand, state);
    }

    private void cancelGrab(LocalPlayer player, HandType hand, HandState state) {
        if (state.activeGrab != null) {
            try {
                state.activeGrab.cancelGrab(player, hand);
            } catch (LinkageError | RuntimeException exception) {
                VisorHandInteractions.LOGGER.warn("Could not cancel physical grab", exception);
            }
        }
        state.activeGrab = null;
        state.lastGrabProbe = null;
        HandPoseLockManager.release(hand);
    }

    private void clearHandState(LocalPlayer player, HandType hand, HandState state) {
        cancelGrab(player, hand, state);
        state.previousProbe = null;
        state.touchKey = null;
        state.hoverKey = null;
        state.grabAttemptKey = null;
        state.touchMisses = 0;
        state.grabMisses = 0;
    }

    private void clearRuntimeState(@Nullable LocalPlayer player) {
        if (player != null) {
            for (HandType hand : HandType.values()) {
                clearHandState(player, hand, handStates.get(hand));
            }
        } else {
            for (HandState state : handStates.values()) {
                state.resetWithoutRelease();
            }
        }
        lastSuccessfulUse.clear();
        suppressedConflictPresses.clear();
        HandPoseLockManager.clear();
    }

    public void clearForClientLifecycle() {
        clearRuntimeState(lastPlayer);
        activeLevel = null;
        lastPlayer = null;
    }

    @VREventHandler
    public void onSessionStateChanged(SessionStateChangedVREvent event) {
        if (event.becameInactive()) {
            clearForClientLifecycle();
        }
    }

    @VREventHandler
    public void onActionButton(ActionButtonVREvent event) {
        String actionId = event.getActionButton().getId();
        if (!event.isPressEvent()) {
            if (suppressedConflictPresses.remove(actionId)) {
                event.setCanceled(true);
            }
            return;
        }
        if (!config.suppressGripConflictsNearTarget()) {
            return;
        }
        HandType hand;
        if (MAIN_GRIP_CONFLICTS.contains(actionId)) {
            hand = HandType.MAIN;
        } else if (OFFHAND_GRIP_CONFLICTS.contains(actionId)) {
            hand = HandType.OFFHAND;
        } else {
            return;
        }

        if (handStates.get(hand).hoverKey == null) {
            return;
        }
        var input = VisorAPI.client().getInputManager();
        var profile = input.getActiveProfile();
        if (profile == null) {
            return;
        }
        var conflictingBinding = event.getActionButton().getBinding(profile);
        GrabAction grabAction = grabActions.get(hand);
        var grabBinding = grabAction == null ? null : grabAction.getBinding(profile);
        if (conflictingBinding == null || grabBinding == null) {
            return;
        }
        boolean leftHanded = input.isLeftHanded();
        if (!Objects.equals(
                conflictingBinding.getActionId(leftHanded),
                grabBinding.getActionId(leftHanded)
        ) || conflictingBinding.getActionKeyModifier(leftHanded)
                != grabBinding.getActionKeyModifier(leftHanded)) {
            return;
        }

        suppressedConflictPresses.add(actionId);
        event.setCanceled(true);
    }

    @Override
    protected void onClear(@Nullable LocalPlayer player) {
        clearForClientLifecycle();
    }

    @Override
    public boolean isActive(@Nullable LocalPlayer player) {
        return player != null
                && minecraft.level != null
                && minecraft.gameMode != null
                && minecraft.screen == null
                && !minecraft.isPaused()
                && player.isAlive()
                && !player.isSleeping()
                && !player.isSpectator();
    }

    @Override
    public @NotNull TaskType getType() {
        return TaskType.VR_PLAYER_TICK;
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }

    private static final class HandState {
        private Vec3 previousProbe;
        private Object touchKey;
        private Object hoverKey;
        private Object grabAttemptKey;
        private InteractionTarget activeGrab;
        private Vec3 lastGrabProbe;
        private int touchMisses;
        private int grabMisses;

        private void resetWithoutRelease() {
            previousProbe = null;
            touchKey = null;
            hoverKey = null;
            grabAttemptKey = null;
            activeGrab = null;
            lastGrabProbe = null;
            touchMisses = 0;
            grabMisses = 0;
        }
    }
}

