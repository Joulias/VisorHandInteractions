package org.vmstudio.visorhandinteractions.core.client.pose;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.visor.api.client.events.SessionStateChangedVREvent;
import org.vmstudio.visor.api.client.events.render.RenderFrameStartedVREvent;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseType;
import org.vmstudio.visor.api.client.player.pose.VRPlayerPoseClient;
import org.vmstudio.visor.api.common.HandType;
import org.vmstudio.visor.api.common.addon.VisorAddon;
import org.vmstudio.visor.api.common.eventbus.listener.VREventHandler;
import org.vmstudio.visor.api.common.eventbus.listener.VREventListener;
import org.vmstudio.visor.api.common.player.VRPose;
import org.vmstudio.visor.api.common.utils.VRMathUtils;

import java.util.EnumMap;

/**
 * Render-only hand locking for physical controls.
 *
 * <p>This deliberately mutates only Visor's {@link PlayerPoseType#RENDER}
 * body-hand pose.  Tick, grip, and aim tracking remain physical, so the player
 * can keep moving the real controller to drive a held control while the visible
 * hand stays attached to it.</p>
 */
public final class HandPoseLockManager implements VREventListener {
    private static final HandPoseLockManager INSTANCE = new HandPoseLockManager();
    private static final EnumMap<HandType, LockState> LOCKS = new EnumMap<>(HandType.class);

    private static boolean registered;

    private HandPoseLockManager() {
    }

    /** Register the single render listener. Safe to call more than once. */
    public static synchronized void register(@NotNull VisorAddon owner) {
        if (registered) {
            return;
        }
        VisorAPI.eventBus().registerListener(owner, INSTANCE);
        registered = true;
    }

    /**
     * Starts a visual lock.
     *
     * @param physicalHandAnchor the tracked probe/contact point at grab time,
     *                           normally the same position passed to beginGrab
     */
    public static synchronized void begin(
            @NotNull HandType hand,
            @NotNull GrabPose target,
            @NotNull Vec3 physicalHandAnchor
    ) {
        VRPlayerPoseClient tickPose = VisorAPI.client()
                .getVRLocalPlayer()
                .getPoseData(PlayerPoseType.TICK);
        VRPose trackedHand = tickPose.getBody().getHand(hand).getPose();

        Matrix4f trackedRotation = new Matrix4f(trackedHand.getRotation());
        Matrix4f inverseTrackedRotation = trackedRotation.invert(new Matrix4f());
        Vector3f anchorOffsetWorld = physicalHandAnchor
                .subtract(trackedHand.getPositionVec3())
                .toVector3f();
        Vector3f localHandAnchor = inverseTrackedRotation.transformDirection(
                anchorOffsetWorld,
                new Vector3f()
        );

        Matrix4f targetFrame = target.worldFrame();
        Matrix4f relativeRotation = targetFrame == null
                ? trackedRotation
                : targetFrame.invert(new Matrix4f()).mul(trackedRotation, new Matrix4f());

        LOCKS.put(hand, new LockState(
                target,
                localHandAnchor,
                relativeRotation,
                targetFrame != null
        ));
        VRPlayerPoseClient renderPose = VisorAPI.client()
                .getVRLocalPlayer()
                .getPoseData(PlayerPoseType.RENDER);
        apply(hand, LOCKS.get(hand), target, renderPose);
    }

    /** Update the moving anchor/frame while retaining the original natural grip. */
    public static synchronized void update(
            @NotNull HandType hand,
            @NotNull GrabPose target
    ) {
        LockState state = LOCKS.get(hand);
        if (state == null) {
            return;
        }
        state.previousTarget = state.currentTarget;
        state.currentTarget = target;
    }

    public static synchronized void release(@NotNull HandType hand) {
        if (LOCKS.remove(hand) != null) {
            restoreTrackedRenderHand(hand);
        }
    }

    public static synchronized void clear() {
        boolean restoreMain = LOCKS.containsKey(HandType.MAIN);
        boolean restoreOffhand = LOCKS.containsKey(HandType.OFFHAND);
        LOCKS.clear();
        if (restoreMain) {
            restoreTrackedRenderHand(HandType.MAIN);
        }
        if (restoreOffhand) {
            restoreTrackedRenderHand(HandType.OFFHAND);
        }
    }

    public static synchronized boolean isLocked(@NotNull HandType hand) {
        return LOCKS.containsKey(hand);
    }

    @VREventHandler
    public void onRenderFrameStarted(RenderFrameStartedVREvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            clear();
            return;
        }
        VRPlayerPoseClient renderPose = VisorAPI.client()
                .getVRLocalPlayer()
                .getPoseData(PlayerPoseType.RENDER);
        synchronized (HandPoseLockManager.class) {
            LOCKS.forEach((hand, state) -> {
                GrabPose sampledTarget = GrabPose.interpolate(
                        state.previousTarget,
                        state.currentTarget,
                        event.getPartialTicks()
                );
                apply(hand, state, sampledTarget, renderPose);
            });
        }
    }

    @VREventHandler
    public void onSessionStateChanged(SessionStateChangedVREvent event) {
        if (event.becameInactive()) {
            clear();
        }
    }

    private static void apply(
            HandType hand,
            LockState state,
            GrabPose target,
            VRPlayerPoseClient renderPose
    ) {
        Matrix4f frame = target.worldFrame();
        Matrix4f worldHandRotation;
        if (state.frameRelative && frame != null) {
            worldHandRotation = frame.mul(state.relativeRotation, new Matrix4f());
        } else {
            worldHandRotation = new Matrix4f(state.relativeRotation);
        }

        Vector3f worldAnchorOffset = worldHandRotation.transformDirection(
                state.localHandAnchor,
                new Vector3f()
        );
        Vec3 handOrigin = target.worldAnchor().subtract(
                worldAnchorOffset.x,
                worldAnchorOffset.y,
                worldAnchorOffset.z
        );

        float yaw = renderPose.getRotationY();
        float worldScale = Math.max(1.0E-5F, renderPose.getWorldScale());
        Vector3f rawPosition = handOrigin.toVector3f()
                .sub(renderPose.getOrigin())
                .rotateY(-yaw)
                .div(worldScale);
        Matrix4f rawRotation = new Matrix4f()
                .rotationY(-yaw)
                .mul(worldHandRotation);
        Vector3f worldDirection = worldHandRotation.transformDirection(
                VRMathUtils.BACK_VECTOR,
                new Vector3f()
        );
        Vector3f rawDirection = worldDirection.rotateY(-yaw, new Vector3f());

        VRPose renderedHand = renderPose.getBody().getHand(hand).getPose();
        renderedHand.update(
                rawPosition,
                rawRotation,
                rawDirection,
                renderPose.getOrigin(),
                yaw,
                worldScale
        );
    }

    private static void restoreTrackedRenderHand(HandType hand) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        VRPlayerPoseClient renderPose = VisorAPI.client()
                .getVRLocalPlayer()
                .getPoseData(PlayerPoseType.RENDER);
        renderPose.getBody().getHand(hand).getPose().copyFrom(renderPose.getHand(hand));
    }

    private static final class LockState {
        private GrabPose previousTarget;
        private GrabPose currentTarget;
        private final Vector3f localHandAnchor;
        private final Matrix4f relativeRotation;
        private final boolean frameRelative;

        private LockState(
                GrabPose target,
                Vector3f localHandAnchor,
                Matrix4f relativeRotation,
                boolean frameRelative
        ) {
            this.previousTarget = target;
            this.currentTarget = target;
            this.localHandAnchor = localHandAnchor;
            this.relativeRotation = relativeRotation;
            this.frameRelative = frameRelative;
        }
    }
}
