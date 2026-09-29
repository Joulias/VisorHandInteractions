package org.vmstudio.visorhandinteractions.core.client.interaction.generic;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

/** Small, deterministic geometry helpers shared by generic held controls. */
public final class ControlMotionMath {
    private static final double EPSILON_SQUARED = 1.0E-12D;

    private ControlMotionMath() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static @NotNull Vec3 projectOntoPlane(
            @NotNull Vec3 vector,
            @NotNull Vec3 planeNormal
    ) {
        Vec3 normal = normalizedOr(planeNormal, new Vec3(0.0D, 1.0D, 0.0D));
        return vector.subtract(normal.scale(vector.dot(normal)));
    }

    public static double signedAngle(
            @NotNull Vec3 from,
            @NotNull Vec3 to,
            @NotNull Vec3 axis
    ) {
        Vec3 normal = normalizedOr(axis, new Vec3(0.0D, 1.0D, 0.0D));
        Vec3 first = projectOntoPlane(from, normal);
        Vec3 second = projectOntoPlane(to, normal);
        if (first.lengthSqr() <= EPSILON_SQUARED
                || second.lengthSqr() <= EPSILON_SQUARED) {
            return 0.0D;
        }
        first = first.normalize();
        second = second.normalize();
        double sine = normal.dot(first.cross(second));
        double cosine = clamp(first.dot(second), -1.0D, 1.0D);
        return Math.atan2(sine, cosine);
    }

    public static @NotNull Vec3 rotateAroundAxis(
            @NotNull Vec3 vector,
            @NotNull Vec3 axis,
            double radians
    ) {
        Vec3 normal = normalizedOr(axis, new Vec3(0.0D, 1.0D, 0.0D));
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        return vector.scale(cosine)
                .add(normal.cross(vector).scale(sine))
                .add(normal.scale(normal.dot(vector) * (1.0D - cosine)));
    }

    public static @NotNull Vec3 normalizedOr(
            @NotNull Vec3 vector,
            @NotNull Vec3 fallback
    ) {
        return vector.lengthSqr() > EPSILON_SQUARED ? vector.normalize() : fallback;
    }

    /**
     * Create interprets ordinary use as forward and sneak-use as reverse.
     * Only a matching physical detent should therefore dispatch an action.
     */
    public static boolean detentMatchesModifier(
            double signedTravel,
            boolean reverseModifier
    ) {
        if (!Double.isFinite(signedTravel)
                || Math.abs(signedTravel) <= EPSILON_SQUARED) {
            return false;
        }
        return (signedTravel > 0.0D) != reverseModifier;
    }

    public static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
