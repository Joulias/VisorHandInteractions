package org.vmstudio.visorhandinteractions.core.client.interaction.generic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

/** Immutable, dependency-free geometry and behavior inferred for one control. */
public record GenericControlDescriptor(
        @NotNull BlockPos blockPos,
        @NotNull ResourceLocation blockId,
        @NotNull GenericControlKind kind,
        @NotNull Vec3 pivot,
        @NotNull Vec3 outwardNormal,
        @NotNull Vec3 motionAxis,
        @NotNull Vec3 hingeAxis,
        @NotNull Vec3 defaultGrip,
        @NotNull Direction interactionFace
) {
    public GenericControlDescriptor {
        blockPos = blockPos.immutable();
        outwardNormal = ControlMotionMath.normalizedOr(
                outwardNormal,
                directionVector(interactionFace)
        );
        if (kind.linearPress()) {
            motionAxis = ControlMotionMath.normalizedOr(
                    motionAxis,
                    outwardNormal.scale(-1.0D)
            );
            hingeAxis = ControlMotionMath.normalizedOr(
                    ControlMotionMath.projectOntoPlane(hingeAxis, motionAxis),
                    orthogonal(motionAxis)
            );
        } else {
            motionAxis = ControlMotionMath.normalizedOr(
                    ControlMotionMath.projectOntoPlane(motionAxis, outwardNormal),
                    orthogonal(outwardNormal)
            );
            hingeAxis = ControlMotionMath.normalizedOr(
                    hingeAxis,
                    outwardNormal.cross(motionAxis).normalize()
            );
        }
    }

    public @NotNull Vec3 fingerDirection() {
        Vec3 held = defaultGrip.subtract(pivot);
        if (kind.rotary()) {
            Vec3 radial = ControlMotionMath.projectOntoPlane(held, hingeAxis);
            return ControlMotionMath.normalizedOr(
                    hingeAxis.cross(radial),
                    motionAxis
            );
        }
        return ControlMotionMath.normalizedOr(held, motionAxis);
    }

    private static Vec3 directionVector(Direction direction) {
        return new Vec3(
                direction.getStepX(),
                direction.getStepY(),
                direction.getStepZ()
        );
    }

    private static Vec3 orthogonal(Vec3 normal) {
        Vec3 seed = Math.abs(normal.y) < 0.85D
                ? new Vec3(0.0D, 1.0D, 0.0D)
                : new Vec3(1.0D, 0.0D, 0.0D);
        return ControlMotionMath.normalizedOr(
                ControlMotionMath.projectOntoPlane(seed, normal),
                new Vec3(0.0D, 0.0D, 1.0D)
        );
    }
}
