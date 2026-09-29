package org.vmstudio.visorhandinteractions.core.client.interaction.generic;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Optional;

/**
 * Conservative control recognition based on vanilla types, registry IDs and
 * common block-state property names. It intentionally excludes powered
 * cogwheels, flywheels, water wheels and cutting/crushing wheels.
 */
public final class GenericControlClassifier {
    private static final double MINIMUM_GRIP_RADIUS = 0.08D;
    private static final double MAXIMUM_GRIP_RADIUS = 0.42D;

    private GenericControlClassifier() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Exposes recognition semantics for moving-space compatibility adapters. */
    public static @NotNull Optional<GenericControlKind> controlKind(
            @NotNull BlockState state
    ) {
        return classify(state);
    }

    public static boolean recognizes(@NotNull BlockState state) {
        return classify(state).isPresent();
    }

    public static @NotNull Optional<GenericControlDescriptor> describe(
            @NotNull ClientLevel level,
            @NotNull LocalPlayer player,
            @NotNull BlockPos pos,
            @NotNull BlockState state,
            @NotNull BlockHitResult hit
    ) {
        Optional<GenericControlKind> classified = classify(state);
        if (classified.isEmpty()) {
            return Optional.empty();
        }

        VoxelShape outline = state.getShape(level, pos, CollisionContext.of(player));
        if (outline.isEmpty()) {
            return Optional.empty();
        }
        AABB bounds = outline.bounds().move(pos);
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        GenericControlKind kind = classified.get();
        Direction facing = directionProperty(state, "facing");
        Vec3 outward = kind.linearPress()
                ? directionVector(hit.getDirection())
                : attachedOutward(state, facing, hit.getDirection());
        Vec3 facingVector = facing == null ? directionVector(hit.getDirection())
                : directionVector(facing);
        Vec3 motionAxis;
        if (kind.linearPress()) {
            motionAxis = outward.scale(-1.0D);
        } else {
            motionAxis = ControlMotionMath.projectOntoPlane(facingVector, outward);
            if (motionAxis.lengthSqr() <= 1.0E-12D) {
                motionAxis = ControlMotionMath.projectOntoPlane(
                        new Vec3(0.0D, 1.0D, 0.0D),
                        outward
                );
            }
            if (motionAxis.lengthSqr() <= 1.0E-12D) {
                motionAxis = ControlMotionMath.projectOntoPlane(
                        new Vec3(1.0D, 0.0D, 0.0D),
                        outward
                );
            }
            motionAxis = ControlMotionMath.normalizedOr(
                    motionAxis,
                    new Vec3(0.0D, 0.0D, 1.0D)
            );
        }

        Vec3 center = bounds.getCenter();
        Vec3 pivot;
        Vec3 hinge;
        Vec3 grip;
        if (kind.rotary()) {
            hinge = outward;
            pivot = center;
            Vec3 contactFromPivot = hit.getLocation().subtract(pivot);
            Vec3 radial = ControlMotionMath.projectOntoPlane(contactFromPivot, hinge);
            double inferredRadius = inferredRotaryRadius(bounds, hinge);
            double gripRadius = ControlMotionMath.clamp(
                    radial.length(),
                    MINIMUM_GRIP_RADIUS,
                    Math.min(MAXIMUM_GRIP_RADIUS, inferredRadius)
            );
            radial = ControlMotionMath.normalizedOr(radial, motionAxis);
            double axialOffset = ControlMotionMath.clamp(
                    contactFromPivot.dot(hinge),
                    -0.18D,
                    0.18D
            );
            grip = pivot.add(radial.scale(gripRadius)).add(hinge.scale(axialOffset));
            motionAxis = ControlMotionMath.normalizedOr(hinge.cross(radial), motionAxis);
        } else if (kind.linearPress()) {
            pivot = hit.getLocation();
            hinge = ControlMotionMath.normalizedOr(
                    outward.cross(new Vec3(0.0D, 1.0D, 0.0D)),
                    outward.cross(new Vec3(1.0D, 0.0D, 0.0D))
            );
            grip = hit.getLocation();
        } else {
            double supportExtent = projectedHalfExtent(bounds, outward);
            pivot = center.subtract(outward.scale(supportExtent * 0.78D));
            hinge = ControlMotionMath.normalizedOr(
                    outward.cross(motionAxis),
                    new Vec3(1.0D, 0.0D, 0.0D)
            );
            grip = hit.getLocation();
            if (grip.distanceToSqr(pivot) < 0.10D * 0.10D) {
                grip = pivot.add(motionAxis.scale(0.28D)).add(outward.scale(0.04D));
            }
        }

        return Optional.of(new GenericControlDescriptor(
                pos,
                blockId,
                kind,
                pivot,
                outward,
                motionAxis,
                hinge,
                grip,
                hit.getDirection()
        ));
    }

    private static @NotNull Optional<GenericControlKind> classify(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String namespace = id.getNamespace().toLowerCase(Locale.ROOT);
        String path = id.getPath().toLowerCase(Locale.ROOT);

        if (state.getBlock() instanceof LeverBlock) {
            return Optional.of(GenericControlKind.BINARY_LEVER);
        }
        if ("simulated".equals(namespace) && "throttle_lever".equals(path)) {
            return Optional.of(GenericControlKind.NATIVE_HOLD_LEVER);
        }
        if ("simulated".equals(namespace) && "physics_assembler".equals(path)) {
            return Optional.of(GenericControlKind.NATIVE_HOLD_LEVER);
        }
        if ("analog_lever".equals(path)) {
            return Optional.of(GenericControlKind.STEPPED_LEVER);
        }
        if ("lever".equals(path) || path.endsWith("_lever")) {
            return Optional.of(GenericControlKind.BINARY_LEVER);
        }

        if ("hand_crank".equals(path)
                || "valve_handle".equals(path)
                || path.endsWith("_valve_handle")
                || ("create_connected".equals(namespace)
                && ("crank_wheel".equals(path)
                || "large_crank_wheel".equals(path)))) {
            return Optional.of(GenericControlKind.DETENTED_ROTARY);
        }
        if ("create".equals(namespace)
                && ("peculiar_bell".equals(path)
                || "powered_toggle_latch".equals(path)
                || "powered_latch".equals(path)
                || "pulse_timer".equals(path)
                || "pulse_extender".equals(path)
                || "pulse_repeater".equals(path))) {
            return Optional.of(GenericControlKind.LINEAR_PRESS);
        }
        if ("simulated".equals(namespace)
                && ("handle".equals(path) || path.endsWith("_handle"))) {
            return Optional.of(GenericControlKind.NATIVE_HOLD_HANDLE);
        }
        if ("steering_wheel".equals(path)
                || path.endsWith("_steering_wheel")
                || "control_wheel".equals(path)
                || path.endsWith("_control_wheel")
                || "ship_wheel".equals(path)
                || path.endsWith("_ship_wheel")
                || "helm".equals(path)) {
            return Optional.of(GenericControlKind.NATIVE_HOLD_ROTARY);
        }
        return Optional.empty();
    }

    private static Vec3 attachedOutward(
            BlockState state,
            Direction facing,
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
        Direction axisFacing = directionProperty(state, "axis_along_first_coordinate");
        if (axisFacing != null) {
            return directionVector(axisFacing);
        }
        return directionVector(facing == null ? fallback : facing);
    }

    private static Direction directionProperty(BlockState state, String name) {
        Object value = propertyValue(state, name);
        return value instanceof Direction direction ? direction : null;
    }

    private static Object propertyValue(BlockState state, String name) {
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

    private static double inferredRotaryRadius(AABB bounds, Vec3 axis) {
        double x = bounds.getXsize() * 0.5D;
        double y = bounds.getYsize() * 0.5D;
        double z = bounds.getZsize() * 0.5D;
        double axial = Math.abs(axis.x) * x + Math.abs(axis.y) * y + Math.abs(axis.z) * z;
        double totalSquared = x * x + y * y + z * z;
        double planar = Math.sqrt(Math.max(0.0D, totalSquared - axial * axial));
        return ControlMotionMath.clamp(planar, MINIMUM_GRIP_RADIUS, MAXIMUM_GRIP_RADIUS);
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
}
