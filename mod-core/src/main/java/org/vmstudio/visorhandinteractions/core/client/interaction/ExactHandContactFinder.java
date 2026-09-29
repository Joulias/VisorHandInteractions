package org.vmstudio.visorhandinteractions.core.client.interaction;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.BiPredicate;

/**
 * Exact swept-sphere contact against multipart outline shapes. A separate
 * nearest-blocker result prevents touching a control through a solid block.
 */
public final class ExactHandContactFinder {
    private static final double EPSILON = 1.0E-7D;

    private ExactHandContactFinder() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static @NotNull Optional<BlockInteractionTarget> findNearest(
            @NotNull ClientLevel level,
            @NotNull LocalPlayer player,
            @NotNull Vec3 sweepStart,
            @NotNull Vec3 sweepEnd,
            double radius,
            @NotNull BiPredicate<BlockPos, BlockState> targetFilter
    ) {
        // One owner-block of padding covers mod shapes which protrude outside
        // their own cell while keeping the candidate scan tightly bounded.
        int minimumX = Mth.floor(Math.min(sweepStart.x, sweepEnd.x) - radius) - 1;
        int minimumY = Mth.floor(Math.min(sweepStart.y, sweepEnd.y) - radius) - 1;
        int minimumZ = Mth.floor(Math.min(sweepStart.z, sweepEnd.z) - radius) - 1;
        int maximumX = Mth.floor(Math.max(sweepStart.x, sweepEnd.x) + radius) + 1;
        int maximumY = Mth.floor(Math.max(sweepStart.y, sweepEnd.y) + radius) + 1;
        int maximumZ = Mth.floor(Math.max(sweepStart.z, sweepEnd.z) + radius) + 1;

        CollisionContext context = CollisionContext.of(player);
        Candidate nearestBlocker = null;
        Candidate nearestTarget = null;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int x = minimumX; x <= maximumX; x++) {
            for (int y = minimumY; y <= maximumY; y++) {
                if (level.isOutsideBuildHeight(y)) {
                    continue;
                }
                for (int z = minimumZ; z <= maximumZ; z++) {
                    mutable.set(x, y, z);
                    if (!level.isLoaded(mutable)) {
                        continue;
                    }

                    BlockState state = level.getBlockState(mutable);
                    if (state.isAir()) {
                        continue;
                    }

                    VoxelShape outline = state.getShape(level, mutable, context);
                    if (outline.isEmpty()) {
                        continue;
                    }

                    BlockPos owner = mutable.immutable();
                    boolean target = targetFilter.test(owner, state);
                    int partIndex = 0;
                    for (AABB localPart : outline.toAabbs()) {
                        Candidate candidate = sweepSphereAabb(
                                sweepStart,
                                sweepEnd,
                                radius,
                                localPart.move(owner),
                                owner,
                                partIndex++
                        );
                        if (candidate == null) {
                            continue;
                        }
                        if (candidate.isBefore(nearestBlocker)) {
                            nearestBlocker = candidate;
                        }
                        if (target && candidate.isBefore(nearestTarget)) {
                            nearestTarget = candidate;
                        }
                    }
                }
            }
        }

        if (nearestTarget == null) {
            return Optional.empty();
        }
        if (nearestBlocker != null
                && !nearestBlocker.blockPos.equals(nearestTarget.blockPos)
                && nearestBlocker.sweepT + EPSILON < nearestTarget.sweepT) {
            return Optional.empty();
        }

        return Optional.of(nearestTarget.toTarget());
    }

    static Candidate sweepSphereAabb(
            Vec3 from,
            Vec3 to,
            double radius,
            AABB box,
            BlockPos owner,
            int partIndex
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

        double minimumDistanceSquared = Double.POSITIVE_INFINITY;
        double minimumT = 0.0D;
        for (int i = 0; i < count - 1; i++) {
            double startT = breakpoints[i];
            double endT = breakpoints[i + 1];
            double midpoint = (startT + endT) * 0.5D;

            Quadratic quadratic = distanceQuadratic(from, delta, box, midpoint);
            minimumT = chooseMinimum(
                    quadratic,
                    startT,
                    minimumT,
                    minimumDistanceSquared
            );
            minimumDistanceSquared = Math.min(
                    minimumDistanceSquared,
                    quadratic.value(startT)
            );

            double endValue = quadratic.value(endT);
            if (endValue < minimumDistanceSquared - EPSILON) {
                minimumDistanceSquared = endValue;
                minimumT = endT;
            }

            if (quadratic.a > EPSILON) {
                double vertex = Mth.clamp(
                        -quadratic.b / (2.0D * quadratic.a),
                        startT,
                        endT
                );
                double vertexValue = quadratic.value(vertex);
                if (vertexValue < minimumDistanceSquared - EPSILON
                        || (Math.abs(vertexValue - minimumDistanceSquared) <= EPSILON
                        && vertex < minimumT)) {
                    minimumDistanceSquared = vertexValue;
                    minimumT = vertex;
                }
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
                Vec3 point = from.add(delta.scale(middle));
                if (distanceSquared(point, box) <= radiusSquared) {
                    high = middle;
                } else {
                    low = middle;
                }
            }
            hitT = high;
        }

        Vec3 handCenter = from.add(delta.scale(hitT));
        SurfaceHit surfaceHit = surfaceHit(box, handCenter, delta);
        return new Candidate(
                owner,
                surfaceHit.point,
                surfaceHit.face,
                box.contains(from),
                hitT,
                distanceSquared(to, box),
                partIndex
        );
    }

    static double distanceSquared(Vec3 point, AABB box) {
        double dx = Math.max(Math.max(box.minX - point.x, 0.0D), point.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.y, 0.0D), point.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.z, 0.0D), point.z - box.maxZ);
        return dx * dx + dy * dy + dz * dz;
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

    private static Quadratic distanceQuadratic(
            Vec3 start,
            Vec3 delta,
            AABB box,
            double sampleT
    ) {
        AxisDistance x = axisDistance(start.x, delta.x, box.minX, box.maxX, sampleT);
        AxisDistance y = axisDistance(start.y, delta.y, box.minY, box.maxY, sampleT);
        AxisDistance z = axisDistance(start.z, delta.z, box.minZ, box.maxZ, sampleT);

        double a = x.slope * x.slope + y.slope * y.slope + z.slope * z.slope;
        double b = 2.0D * (
                x.offset * x.slope
                        + y.offset * y.slope
                        + z.offset * z.slope
        );
        double c = x.offset * x.offset + y.offset * y.offset + z.offset * z.offset;
        return new Quadratic(a, b, c);
    }

    private static AxisDistance axisDistance(
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

    private static double chooseMinimum(
            Quadratic quadratic,
            double candidateT,
            double currentT,
            double currentValue
    ) {
        double candidateValue = quadratic.value(candidateT);
        if (candidateValue < currentValue - EPSILON
                || (Math.abs(candidateValue - currentValue) <= EPSILON
                && candidateT < currentT)) {
            return candidateT;
        }
        return currentT;
    }

    private static SurfaceHit surfaceHit(AABB box, Vec3 center, Vec3 movement) {
        Vec3 surface = new Vec3(
                Mth.clamp(center.x, box.minX, box.maxX),
                Mth.clamp(center.y, box.minY, box.maxY),
                Mth.clamp(center.z, box.minZ, box.maxZ)
        );
        Vec3 outward = center.subtract(surface);
        if (outward.lengthSqr() > 1.0E-14D) {
            return new SurfaceHit(
                    surface,
                    Direction.getNearest(outward.x, outward.y, outward.z)
            );
        }

        double[] distances = {
                center.x - box.minX,
                box.maxX - center.x,
                center.y - box.minY,
                box.maxY - center.y,
                center.z - box.minZ,
                box.maxZ - center.z
        };
        Direction[] faces = {
                Direction.WEST,
                Direction.EAST,
                Direction.DOWN,
                Direction.UP,
                Direction.NORTH,
                Direction.SOUTH
        };
        int nearest = 0;
        for (int i = 1; i < distances.length; i++) {
            if (distances[i] < distances[nearest]) {
                nearest = i;
            }
        }
        Direction face = faces[nearest];
        surface = switch (face) {
            case WEST -> new Vec3(box.minX, center.y, center.z);
            case EAST -> new Vec3(box.maxX, center.y, center.z);
            case DOWN -> new Vec3(center.x, box.minY, center.z);
            case UP -> new Vec3(center.x, box.maxY, center.z);
            case NORTH -> new Vec3(center.x, center.y, box.minZ);
            case SOUTH -> new Vec3(center.x, center.y, box.maxZ);
        };

        if (movement.lengthSqr() > 1.0E-14D) {
            Direction approach = Direction.getNearest(movement.x, movement.y, movement.z)
                    .getOpposite();
            if (Math.abs(distances[nearest]) <= EPSILON) {
                face = approach;
            }
        }
        return new SurfaceHit(surface, face);
    }

    private record AxisDistance(double offset, double slope) {
    }

    private record Quadratic(double a, double b, double c) {
        private double value(double t) {
            return a * t * t + b * t + c;
        }
    }

    private record SurfaceHit(Vec3 point, Direction face) {
    }

    static final class Candidate {
        private final BlockPos blockPos;
        private final Vec3 surface;
        private final Direction face;
        private final boolean inside;
        private final double sweepT;
        private final double endDistanceSquared;
        private final int partIndex;

        private Candidate(
                BlockPos blockPos,
                Vec3 surface,
                Direction face,
                boolean inside,
                double sweepT,
                double endDistanceSquared,
                int partIndex
        ) {
            this.blockPos = blockPos;
            this.surface = surface;
            this.face = face;
            this.inside = inside;
            this.sweepT = sweepT;
            this.endDistanceSquared = endDistanceSquared;
            this.partIndex = partIndex;
        }

        private boolean isBefore(Candidate other) {
            if (other == null) {
                return true;
            }
            if (sweepT < other.sweepT - EPSILON) {
                return true;
            }
            if (Math.abs(sweepT - other.sweepT) > EPSILON) {
                return false;
            }
            if (endDistanceSquared < other.endDistanceSquared - EPSILON) {
                return true;
            }
            return Math.abs(endDistanceSquared - other.endDistanceSquared) <= EPSILON
                    && partIndex < other.partIndex;
        }

        private BlockInteractionTarget toTarget() {
            return new BlockInteractionTarget(
                    blockPos,
                    new BlockHitResult(surface, face, blockPos, inside),
                    sweepT
            );
        }
    }
}

