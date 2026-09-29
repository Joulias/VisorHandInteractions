package org.vmstudio.visorhandinteractions.loader.neoforge.compat.aeroworks;

import com.mred231.aeroworks.content.controls.ConsoleBlock;
import com.mred231.aeroworks.content.controls.ModulePartRender;
import com.mred231.aeroworks.content.controls.MountedModule;
import com.mred231.aeroworks.content.controls.Socket;
import com.mred231.aeroworks.content.joystick.JoystickBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.Arrays;

/** Render-exact transforms and contact math for Aeroworks' pedestal joystick. */
final class AeroworksJoystickGeometry {
    static final Vec3 PEDESTAL_PIVOT = new Vec3(0.5D, 0.15625D, 0.5D);
    static final double DEGREES_PER_STEP = 3.0D;

    private static final double EPSILON = 1.0E-7D;
    private static final String HANDLE_MODEL_NAMESPACE = "aeroworks";
    private static final String HANDLE_MODEL_PATH = "block/joystick/handle";

    // The upper grip from assets/aeroworks/models/block/joystick/handle.json.
    // The long stem is deliberately excluded so grabs naturally snap to the handle.
    private static final AABB MODEL_GRIP = new AABB(
            6.75D / 16.0D,
            15.0D / 16.0D,
            6.75D / 16.0D,
            9.25D / 16.0D,
            21.0D / 16.0D,
            9.25D / 16.0D
    );

    private AeroworksJoystickGeometry() {
        throw new UnsupportedOperationException("Utility class");
    }

    static @Nullable GripFrame gripFrame(@NotNull JoystickBlockEntity joystick) {
        MountedModule module = joystick.module(0);
        if (module == null || joystick.sockets().isEmpty()) {
            return null;
        }

        Socket socket = joystick.sockets().getFirst();
        ModulePartRender.RenderPart handlePart = null;
        for (ModulePartRender.RenderPart part
                : ModulePartRender.flatten(module, socket.type())) {
            ResourceLocation model = part.model();
            if (HANDLE_MODEL_NAMESPACE.equals(model.getNamespace())
                    && HANDLE_MODEL_PATH.equals(model.getPath())) {
                handlePart = part;
                break;
            }
        }
        if (handlePart == null) {
            return null;
        }

        BlockPos pos = joystick.getBlockPos();
        Vec3 offset = socket.offset();
        Matrix4f base = new Matrix4f()
                .translation(pos.getX(), pos.getY(), pos.getZ())
                .translate(0.5F, 0.5F, 0.5F)
                .rotate(ConsoleBlock.rotationFor(joystick.getBlockState()))
                .translate(
                        (float) (offset.x - 0.5D),
                        (float) (offset.y - 0.5D),
                        (float) (offset.z - 0.5D)
                )
                .rotate(socket.orientation())
                .translate(-0.5F, 0.0F, -0.5F);

        ModulePartRender.ChannelValues rawValues = channel ->
                joystick.channelValueAt(0, channel, 1.0F);
        ModulePartRender.ChannelValues displayValues =
                ModulePartRender.displayValues(module, rawValues);
        Matrix4f gripTransform = ModulePartRender.partMatrix(
                base,
                handlePart,
                displayValues,
                socket.orientation(),
                ConsoleBlock.isCeiling(joystick.getBlockState())
        );
        return new GripFrame(
                gripTransform,
                new Matrix4f(gripTransform).invert(),
                base,
                new Matrix4f(base).invert()
        );
    }

    static @Nullable LocalContact sweepGrip(
            @NotNull GripFrame frame,
            @NotNull Vec3 sweepStart,
            @NotNull Vec3 sweepEnd,
            double radius
    ) {
        Vec3 localStart = frame.toModel(sweepStart);
        Vec3 localEnd = frame.toModel(sweepEnd);
        return sweepSphereAabb(
                localStart,
                localEnd,
                Math.max(0.0D, radius),
                MODEL_GRIP
        );
    }

    private static @Nullable LocalContact sweepSphereAabb(
            Vec3 from,
            Vec3 to,
            double radius,
            AABB box
    ) {
        Vec3 delta = to.subtract(from);
        double[] breakpoints = new double[8];
        int count = 0;
        breakpoints[count++] = 0.0D;
        breakpoints[count++] = 1.0D;
        count = addCrossings(breakpoints, count, from.x, delta.x, box.minX, box.maxX);
        count = addCrossings(breakpoints, count, from.y, delta.y, box.minY, box.maxY);
        count = addCrossings(breakpoints, count, from.z, delta.z, box.minZ, box.maxZ);
        Arrays.sort(breakpoints, 0, count);
        count = deduplicate(breakpoints, count);

        double minimumDistanceSquared = distanceSquared(from, box);
        double minimumT = 0.0D;
        for (int i = 0; i < count - 1; i++) {
            double startT = breakpoints[i];
            double endT = breakpoints[i + 1];
            double midpoint = (startT + endT) * 0.5D;
            Quadratic quadratic = distanceQuadratic(from, delta, box, midpoint);

            Minimum minimum = consider(
                    minimumT,
                    minimumDistanceSquared,
                    startT,
                    quadratic.value(startT)
            );
            minimumT = minimum.t;
            minimumDistanceSquared = minimum.value;

            minimum = consider(
                    minimumT,
                    minimumDistanceSquared,
                    endT,
                    quadratic.value(endT)
            );
            minimumT = minimum.t;
            minimumDistanceSquared = minimum.value;

            if (quadratic.a > EPSILON) {
                double vertex = Mth.clamp(
                        -quadratic.b / (2.0D * quadratic.a),
                        startT,
                        endT
                );
                minimum = consider(
                        minimumT,
                        minimumDistanceSquared,
                        vertex,
                        quadratic.value(vertex)
                );
                minimumT = minimum.t;
                minimumDistanceSquared = minimum.value;
            }
        }

        double effectiveRadius = radius + EPSILON;
        double radiusSquared = effectiveRadius * effectiveRadius;
        if (minimumDistanceSquared > radiusSquared) {
            return null;
        }

        double hitT;
        if (distanceSquared(from, box) <= radiusSquared) {
            hitT = 0.0D;
        } else {
            double low = 0.0D;
            double high = minimumT;
            for (int i = 0; i < 48; i++) {
                double middle = (low + high) * 0.5D;
                if (distanceSquared(from.add(delta.scale(middle)), box)
                        <= radiusSquared) {
                    high = middle;
                } else {
                    low = middle;
                }
            }
            hitT = high;
        }

        Vec3 handCenter = from.add(delta.scale(hitT));
        return new LocalContact(
                nearestSurface(box, handCenter),
                hitT,
                distanceSquared(to, box)
        );
    }

    private static @NotNull Minimum consider(
            double currentT,
            double currentValue,
            double candidateT,
            double candidateValue
    ) {
        if (candidateValue < currentValue - EPSILON
                || (Math.abs(candidateValue - currentValue) <= EPSILON
                && candidateT < currentT)) {
            return new Minimum(candidateT, candidateValue);
        }
        return new Minimum(currentT, currentValue);
    }

    private static int addCrossings(
            double[] values,
            int count,
            double start,
            double delta,
            double minimum,
            double maximum
    ) {
        if (Math.abs(delta) <= EPSILON) {
            return count;
        }
        double first = (minimum - start) / delta;
        double second = (maximum - start) / delta;
        if (first > 0.0D && first < 1.0D) {
            values[count++] = first;
        }
        if (second > 0.0D && second < 1.0D) {
            values[count++] = second;
        }
        return count;
    }

    private static int deduplicate(double[] values, int count) {
        int write = 1;
        for (int read = 1; read < count; read++) {
            if (Math.abs(values[read] - values[write - 1]) > EPSILON) {
                values[write++] = values[read];
            }
        }
        return write;
    }

    private static @NotNull Quadratic distanceQuadratic(
            Vec3 start,
            Vec3 delta,
            AABB box,
            double sampleT
    ) {
        AxisDistance x = axisDistance(start.x, delta.x, box.minX, box.maxX, sampleT);
        AxisDistance y = axisDistance(start.y, delta.y, box.minY, box.maxY, sampleT);
        AxisDistance z = axisDistance(start.z, delta.z, box.minZ, box.maxZ, sampleT);
        return new Quadratic(
                x.slope * x.slope + y.slope * y.slope + z.slope * z.slope,
                2.0D * (
                        x.offset * x.slope
                                + y.offset * y.slope
                                + z.offset * z.slope
                ),
                x.offset * x.offset + y.offset * y.offset + z.offset * z.offset
        );
    }

    private static @NotNull AxisDistance axisDistance(
            double start,
            double delta,
            double minimum,
            double maximum,
            double sampleT
    ) {
        double sample = start + delta * sampleT;
        if (sample < minimum) {
            return new AxisDistance(minimum - start, -delta);
        }
        if (sample > maximum) {
            return new AxisDistance(start - maximum, delta);
        }
        return new AxisDistance(0.0D, 0.0D);
    }

    private static double distanceSquared(Vec3 point, AABB box) {
        double dx = Math.max(Math.max(box.minX - point.x, 0.0D), point.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.y, 0.0D), point.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.z, 0.0D), point.z - box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static @NotNull Vec3 nearestSurface(AABB box, Vec3 point) {
        Vec3 clamped = new Vec3(
                Mth.clamp(point.x, box.minX, box.maxX),
                Mth.clamp(point.y, box.minY, box.maxY),
                Mth.clamp(point.z, box.minZ, box.maxZ)
        );
        if (!box.contains(point)) {
            return clamped;
        }

        double[] distances = {
                point.x - box.minX,
                box.maxX - point.x,
                point.y - box.minY,
                box.maxY - point.y,
                point.z - box.minZ,
                box.maxZ - point.z
        };
        int nearest = 0;
        for (int i = 1; i < distances.length; i++) {
            if (distances[i] < distances[nearest]) {
                nearest = i;
            }
        }
        return switch (nearest) {
            case 0 -> new Vec3(box.minX, point.y, point.z);
            case 1 -> new Vec3(box.maxX, point.y, point.z);
            case 2 -> new Vec3(point.x, box.minY, point.z);
            case 3 -> new Vec3(point.x, box.maxY, point.z);
            case 4 -> new Vec3(point.x, point.y, box.minZ);
            default -> new Vec3(point.x, point.y, box.maxZ);
        };
    }

    private static @NotNull Vec3 transformPosition(
            @NotNull Matrix4fc matrix,
            @NotNull Vec3 point
    ) {
        Vector3f result = matrix.transformPosition(new Vector3f(
                (float) point.x,
                (float) point.y,
                (float) point.z
        ));
        return new Vec3(result.x, result.y, result.z);
    }

    private static @NotNull Vec3 transformDirection(
            @NotNull Matrix4fc matrix,
            @NotNull Vec3 direction
    ) {
        Vector3f result = matrix.transformDirection(new Vector3f(
                (float) direction.x,
                (float) direction.y,
                (float) direction.z
        ));
        return new Vec3(result.x, result.y, result.z);
    }

    record GripFrame(
            Matrix4f gripTransform,
            Matrix4f inverseGripTransform,
            Matrix4f baseTransform,
            Matrix4f inverseBaseTransform
    ) {
        @NotNull Vec3 toModel(@NotNull Vec3 worldPoint) {
            return transformPosition(inverseGripTransform, worldPoint);
        }

        @NotNull Vec3 toBase(@NotNull Vec3 worldPoint) {
            return transformPosition(inverseBaseTransform, worldPoint);
        }

        @NotNull Vec3 toWorld(@NotNull Vec3 modelPoint) {
            return transformPosition(gripTransform, modelPoint);
        }

        @NotNull Vec3 modelDirectionToWorld(@NotNull Vec3 modelDirection) {
            return transformDirection(gripTransform, modelDirection);
        }
    }

    record LocalContact(Vec3 modelSurface, double sweepT, double endDistanceSquared) {
    }

    private record AxisDistance(double offset, double slope) {
    }

    private record Quadratic(double a, double b, double c) {
        private double value(double t) {
            return a * t * t + b * t + c;
        }
    }

    private record Minimum(double t, double value) {
    }
}
