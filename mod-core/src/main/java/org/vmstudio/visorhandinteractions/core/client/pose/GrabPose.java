package org.vmstudio.visorhandinteractions.core.client.pose;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * A target-space visual pose for a grabbed control.
 *
 * <p>The anchor is the exact point on the control touched by the physical
 * hand probe.  An optional frame describes the control's current world-space
 * orientation.  Supplying a moving frame lets the renderer preserve the
 * player's natural incoming grip while rotating it with a wheel or lever.</p>
 */
public final class GrabPose {
    private static final double MINIMUM_AXIS_LENGTH_SQUARED = 1.0E-12D;

    private final Vec3 worldAnchor;
    private final @Nullable Matrix4f worldFrame;

    private GrabPose(
            @NotNull Vec3 worldAnchor,
            @Nullable Matrix4fc worldFrame
    ) {
        if (!isFinite(worldAnchor)) {
            throw new IllegalArgumentException("Grab anchor must be finite");
        }
        this.worldAnchor = worldAnchor;
        this.worldFrame = worldFrame == null ? null : new Matrix4f(worldFrame);
    }

    /** Lock a point while preserving the hand orientation captured at grab time. */
    public static @NotNull GrabPose anchoredAt(@NotNull Vec3 worldAnchor) {
        return new GrabPose(worldAnchor, null);
    }

    /**
     * Lock a point to a moving control frame.
     *
     * <p>The frame is not an absolute forced hand orientation.  At grab time,
     * the manager records the hand's orientation relative to it.  Later frame
     * updates therefore rotate the hand with the control without changing the
     * way the player naturally approached it.</p>
     */
    public static @NotNull GrabPose attachedTo(
            @NotNull Vec3 worldAnchor,
            @NotNull Matrix4fc worldFrame
    ) {
        return new GrabPose(worldAnchor, worldFrame);
    }

    /**
     * Samples two tick poses for smooth render-frame hand motion.
     * Anchor positions are linearly interpolated and control-frame rotations
     * use shortest-path quaternion spherical interpolation.
     */
    public static @NotNull GrabPose interpolate(
            @NotNull GrabPose previous,
            @NotNull GrabPose current,
            float partialTicks
    ) {
        float alpha = Math.max(0.0F, Math.min(1.0F, partialTicks));
        Vec3 anchor = previous.worldAnchor.lerp(current.worldAnchor, alpha);

        Matrix4f previousFrame = previous.worldFrame();
        Matrix4f currentFrame = current.worldFrame();
        Matrix4f sampledFrame = null;
        if (previousFrame != null && currentFrame != null) {
            Quaternionf previousRotation = new Quaternionf()
                    .setFromNormalized(previousFrame);
            Quaternionf currentRotation = new Quaternionf()
                    .setFromNormalized(currentFrame);
            previousRotation.slerp(currentRotation, alpha);
            sampledFrame = new Matrix4f().rotation(previousRotation);
        } else if (currentFrame != null) {
            sampledFrame = currentFrame;
        } else if (previousFrame != null) {
            // A temporarily unavailable moving frame should not make the hand
            // pop back to its captured world orientation for one render.
            sampledFrame = previousFrame;
        }

        return sampledFrame == null
                ? anchoredAt(anchor)
                : attachedTo(anchor, sampledFrame);
    }

    /**
     * Builds a stable right-handed control frame whose positive Z axis follows
     * {@code axisZ} and positive Y axis follows the component of
     * {@code upHint} perpendicular to it.
     */
    public static @NotNull Matrix4f frameFromAxes(
            @NotNull Vec3 axisZ,
            @NotNull Vec3 upHint
    ) {
        Vec3 z = requireUnit(axisZ, "frame Z axis");
        Vec3 projectedUp = upHint.subtract(z.scale(upHint.dot(z)));
        if (projectedUp.lengthSqr() < MINIMUM_AXIS_LENGTH_SQUARED) {
            Vec3 fallback = Math.abs(z.y) < 0.9D
                    ? new Vec3(0.0D, 1.0D, 0.0D)
                    : new Vec3(1.0D, 0.0D, 0.0D);
            projectedUp = fallback.subtract(z.scale(fallback.dot(z)));
        }
        Vec3 y = requireUnit(projectedUp, "frame Y axis");
        Vec3 x = requireUnit(y.cross(z), "frame X axis");
        // Recompute Y after the cross product so the basis is exactly
        // orthonormal even when the supplied hint was slightly noisy.
        y = requireUnit(z.cross(x), "orthogonal frame Y axis");

        return new Matrix4f()
                .setColumn(0, new Vector4f((float) x.x, (float) x.y, (float) x.z, 0.0F))
                .setColumn(1, new Vector4f((float) y.x, (float) y.y, (float) y.z, 0.0F))
                .setColumn(2, new Vector4f((float) z.x, (float) z.y, (float) z.z, 0.0F))
                .setColumn(3, new Vector4f(0.0F, 0.0F, 0.0F, 1.0F));
    }

    /** A wheel frame that rotates with the grabbed radial point. */
    public static @NotNull Matrix4f wheelFrame(
            @NotNull Vec3 axle,
            @NotNull Vec3 radial
    ) {
        return frameFromAxes(axle, radial);
    }

    public @NotNull Vec3 worldAnchor() {
        return worldAnchor;
    }

    public @Nullable Matrix4f worldFrame() {
        return worldFrame == null ? null : new Matrix4f(worldFrame);
    }

    private static @NotNull Vec3 requireUnit(@NotNull Vec3 vector, String name) {
        if (!isFinite(vector) || vector.lengthSqr() < MINIMUM_AXIS_LENGTH_SQUARED) {
            throw new IllegalArgumentException(name + " must be finite and non-zero");
        }
        return vector.normalize();
    }

    private static boolean isFinite(Vec3 vector) {
        return Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }
}
