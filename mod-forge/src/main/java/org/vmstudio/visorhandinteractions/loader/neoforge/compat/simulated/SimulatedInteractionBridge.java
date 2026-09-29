package org.vmstudio.visorhandinteractions.loader.neoforge.compat.simulated;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlock;
import dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlockEntity;
import dev.simulated_team.simulated.content.blocks.handle.HandleBlockEntity;
import dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity;
import dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity;
import dev.simulated_team.simulated.index.SimBlockShapes;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3dc;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionBridge;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionTarget;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Physical control lookup in both the main level and Sable plot coordinates. */
public final class SimulatedInteractionBridge implements InteractionBridge {
    private static final double EPSILON = 1.0E-7D;

    // Compatibility targets need to beat the same block's generic target. The
    // small bias is below the core finder's blocker tolerance.
    private static final double SPECIALIZED_TARGET_BIAS = 5.0E-7D;

    @Override
    public @NotNull Optional<InteractionTarget> findTarget(
            @NotNull ClientLevel level,
            @NotNull LocalPlayer player,
            @NotNull Vec3 sweepStart,
            @NotNull Vec3 sweepEnd,
            double radius
    ) {
        double safeRadius = Math.max(0.0D, radius);
        ScanResult aggregate = scanSpace(
                level,
                player,
                sweepStart,
                sweepEnd,
                safeRadius,
                null,
                null
        );

        AABB worldSweepBounds = new AABB(sweepStart, sweepEnd)
                .inflate(safeRadius + EPSILON);
        Set<UUID> visited = new HashSet<>();
        for (SubLevelAccess subLevel : SableCompanion.INSTANCE.getAllIntersecting(
                level,
                new BoundingBox3d(worldSweepBounds)
        )) {
            if (!(subLevel instanceof ClientSubLevelAccess clientSubLevel)
                    || !visited.add(clientSubLevel.getUniqueId())) {
                continue;
            }

            Pose3dc pose = clientSubLevel.logicalPose();
            double localRadius = inverseScaledRadius(safeRadius, pose.scale());
            if (!Double.isFinite(localRadius)) {
                continue;
            }

            ScanResult subLevelResult = scanSpace(
                    level,
                    player,
                    pose.transformPositionInverse(sweepStart),
                    pose.transformPositionInverse(sweepEnd),
                    localRadius,
                    pose,
                    clientSubLevel.getUniqueId()
            );
            aggregate = aggregate.merge(subLevelResult);
        }

        ControlCandidate target = aggregate.target;
        if (target == null) {
            return Optional.empty();
        }

        SweepCandidate blocker = aggregate.blocker;
        if (blocker != null
                && !Objects.equals(blocker.identity, target.contact.identity)
                && blocker.sweepT + EPSILON < target.contact.sweepT) {
            return Optional.empty();
        }

        Vec3 worldLocation = target.pose == null
                ? target.contact.surface
                : target.pose.transformPosition(target.contact.surface);
        double reportedFraction = Math.max(
                0.0D,
                target.contact.sweepT - SPECIALIZED_TARGET_BIAS
        );
        return Optional.of(new SimulatedControlTarget(
                level,
                target.contact.identity.blockPos,
                target.contact.identity.subLevelId,
                target.kind,
                worldLocation,
                reportedFraction
        ));
    }

    private static @NotNull ScanResult scanSpace(
            ClientLevel level,
            LocalPlayer player,
            Vec3 sweepStart,
            Vec3 sweepEnd,
            double radius,
            @Nullable Pose3dc pose,
            @Nullable UUID subLevelId
    ) {
        int minimumX = Mth.floor(Math.min(sweepStart.x, sweepEnd.x) - radius) - 1;
        int minimumY = Mth.floor(Math.min(sweepStart.y, sweepEnd.y) - radius) - 1;
        int minimumZ = Mth.floor(Math.min(sweepStart.z, sweepEnd.z) - radius) - 1;
        int maximumX = Mth.floor(Math.max(sweepStart.x, sweepEnd.x) + radius) + 1;
        int maximumY = Mth.floor(Math.max(sweepStart.y, sweepEnd.y) + radius) + 1;
        int maximumZ = Mth.floor(Math.max(sweepStart.z, sweepEnd.z) + radius) + 1;

        CollisionContext context = CollisionContext.of(player);
        SweepCandidate nearestBlocker = null;
        ControlCandidate nearestTarget = null;
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

                    BlockEntity blockEntity = level.getBlockEntity(mutable);
                    ControlKind kind = controlKind(blockEntity);
                    BlockPos owner = mutable.immutable();
                    BlockIdentity identity = new BlockIdentity(subLevelId, owner);

                    // The ordinary outline remains a blocker, but continuous
                    // controls get a separate grab shape. In particular, a
                    // throttle target is only its animated handle, not the base.
                    VoxelShape blockerOutline = state.getShape(level, mutable, context);
                    SweepCandidate blocker = sweepShape(
                            sweepStart,
                            sweepEnd,
                            radius,
                            blockerOutline,
                            owner,
                            identity
                    );
                    if (blocker != null && blocker.isBefore(nearestBlocker)) {
                        nearestBlocker = blocker;
                    }
                    if (kind == null) {
                        continue;
                    }

                    SweepCandidate candidate;
                    if (kind == ControlKind.THROTTLE_LEVER
                            && blockEntity instanceof ThrottleLeverBlockEntity throttle) {
                        candidate = sweepThrottleHandle(
                                sweepStart,
                                sweepEnd,
                                radius,
                                throttle,
                                identity
                        );
                    } else if (kind == ControlKind.PHYSICS_ASSEMBLER
                            && blockEntity instanceof PhysicsAssemblerBlockEntity assembler) {
                        candidate = sweepAssemblerLever(
                                sweepStart,
                                sweepEnd,
                                radius,
                                assembler,
                                identity
                        );
                    } else {
                        candidate = sweepShape(
                                sweepStart,
                                sweepEnd,
                                radius,
                                controlShape(state, level, owner, context, kind),
                                owner,
                                identity
                        );
                    }
                    if (candidate != null) {
                        ControlCandidate control = new ControlCandidate(
                                candidate,
                                kind,
                                pose
                        );
                        if (control.isBefore(nearestTarget)) {
                            nearestTarget = control;
                        }
                    }
                }
            }
        }
        return new ScanResult(nearestBlocker, nearestTarget);
    }

    private static @Nullable ControlKind controlKind(@Nullable BlockEntity blockEntity) {
        if (blockEntity instanceof ThrottleLeverBlockEntity) {
            return ControlKind.THROTTLE_LEVER;
        }
        if (blockEntity instanceof SteeringWheelBlockEntity) {
            return ControlKind.STEERING_WHEEL;
        }
        if (blockEntity instanceof PhysicsAssemblerBlockEntity) {
            return ControlKind.PHYSICS_ASSEMBLER;
        }
        if (blockEntity instanceof HandleBlockEntity) {
            return ControlKind.HANDLE;
        }
        return null;
    }

    private static @Nullable SweepCandidate sweepShape(
            Vec3 sweepStart,
            Vec3 sweepEnd,
            double radius,
            VoxelShape shape,
            BlockPos owner,
            BlockIdentity identity
    ) {
        SweepCandidate nearest = null;
        int partIndex = 0;
        for (AABB localPart : shape.toAabbs()) {
            SweepCandidate candidate = sweepSphereAabb(
                    sweepStart,
                    sweepEnd,
                    radius,
                    localPart.move(owner),
                    identity,
                    partIndex++
            );
            if (candidate != null && candidate.isBefore(nearest)) {
                nearest = candidate;
            }
        }
        return nearest;
    }

    private static @Nullable SweepCandidate sweepThrottleHandle(
            Vec3 sweepStart,
            Vec3 sweepEnd,
            double radius,
            ThrottleLeverBlockEntity throttle,
            BlockIdentity identity
    ) {
        BlockPos owner = throttle.getBlockPos();
        BlockState state = throttle.getBlockState();
        SimulatedControlGeometry.ThrottleFrame frame =
                SimulatedControlGeometry.throttleFrame(throttle);
        Vec3 canonicalStart = SimulatedControlGeometry.toCanonicalThrottlePoint(
                frame,
                owner,
                sweepStart
        );
        Vec3 canonicalEnd = SimulatedControlGeometry.toCanonicalThrottlePoint(
                frame,
                owner,
                sweepEnd
        );
        VoxelShape handle = SimulatedControlGeometry.canonicalThrottleHandleShape(state);
        SweepCandidate nearest = null;
        int partIndex = 0;
        for (AABB canonicalPart : handle.toAabbs()) {
            SweepCandidate candidate = sweepSphereAabb(
                    canonicalStart,
                    canonicalEnd,
                    radius,
                    canonicalPart,
                    identity,
                    partIndex++
            );
            if (candidate == null) {
                continue;
            }
            Vec3 localSurface = SimulatedControlGeometry.fromCanonicalThrottlePoint(
                    frame,
                    owner,
                    candidate.surface
            );
            SweepCandidate transformed = new SweepCandidate(
                    candidate.identity,
                    localSurface,
                    candidate.sweepT,
                    candidate.endDistanceSquared,
                    candidate.partIndex
            );
            if (transformed.isBefore(nearest)) {
                nearest = transformed;
            }
        }
        return nearest;
    }

    private static @Nullable SweepCandidate sweepAssemblerLever(
            Vec3 sweepStart,
            Vec3 sweepEnd,
            double radius,
            PhysicsAssemblerBlockEntity assembler,
            BlockIdentity identity
    ) {
        BlockPos owner = assembler.getBlockPos();
        SimulatedControlGeometry.AssemblerFrame frame =
                SimulatedControlGeometry.assemblerFrame(assembler);
        Vec3 canonicalStart = SimulatedControlGeometry.toCanonicalAssemblerPoint(
                frame,
                owner,
                sweepStart
        );
        Vec3 canonicalEnd = SimulatedControlGeometry.toCanonicalAssemblerPoint(
                frame,
                owner,
                sweepEnd
        );
        SweepCandidate nearest = null;
        int partIndex = 0;
        for (AABB canonicalPart
                : SimulatedControlGeometry.canonicalAssemblerLeverShape().toAabbs()) {
            SweepCandidate candidate = sweepSphereAabb(
                    canonicalStart,
                    canonicalEnd,
                    radius,
                    canonicalPart,
                    identity,
                    partIndex++
            );
            if (candidate == null) {
                continue;
            }
            Vec3 localSurface = SimulatedControlGeometry.fromCanonicalAssemblerPoint(
                    frame,
                    owner,
                    candidate.surface
            );
            SweepCandidate transformed = new SweepCandidate(
                    candidate.identity,
                    localSurface,
                    candidate.sweepT,
                    candidate.endDistanceSquared,
                    candidate.partIndex
            );
            if (transformed.isBefore(nearest)) {
                nearest = transformed;
            }
        }
        return nearest;
    }

    private static @NotNull VoxelShape controlShape(
            BlockState state,
            ClientLevel level,
            BlockPos pos,
            CollisionContext context,
            ControlKind kind
    ) {
        if (kind == ControlKind.STEERING_WHEEL) {
            Direction facing = state.getValue(SteeringWheelBlock.FACING);
            return state.getValue(SteeringWheelBlock.ON_FLOOR)
                    ? SimBlockShapes.STEERING_WHEEL_FULL_FLOOR.get(facing)
                    : SimBlockShapes.STEERING_WHEEL_FULL_CEILING.get(facing);
        }
        return state.getShape(level, pos, context);
    }

    private static double inverseScaledRadius(double radius, Vector3dc scale) {
        double minimumScale = Math.min(
                Math.abs(scale.x()),
                Math.min(Math.abs(scale.y()), Math.abs(scale.z()))
        );
        if (minimumScale <= EPSILON || !Double.isFinite(minimumScale)) {
            return Double.POSITIVE_INFINITY;
        }
        return radius / minimumScale;
    }

    private static @Nullable SweepCandidate sweepSphereAabb(
            Vec3 from,
            Vec3 to,
            double radius,
            AABB box,
            BlockIdentity identity,
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
                if (distanceSquared(from.add(delta.scale(middle)), box) <= radiusSquared) {
                    high = middle;
                } else {
                    low = middle;
                }
            }
            hitT = high;
        }

        Vec3 handCenter = from.add(delta.scale(hitT));
        return new SweepCandidate(
                identity,
                nearestSurface(box, handCenter),
                hitT,
                distanceSquared(to, box),
                partIndex
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
        if (!contains(box, point)) {
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

    private static boolean contains(AABB box, Vec3 point) {
        return point.x >= box.minX && point.x <= box.maxX
                && point.y >= box.minY && point.y <= box.maxY
                && point.z >= box.minZ && point.z <= box.maxZ;
    }

    enum ControlKind {
        THROTTLE_LEVER,
        STEERING_WHEEL,
        PHYSICS_ASSEMBLER,
        HANDLE
    }

    private record BlockIdentity(@Nullable UUID subLevelId, BlockPos blockPos) {
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

    private record SweepCandidate(
            BlockIdentity identity,
            Vec3 surface,
            double sweepT,
            double endDistanceSquared,
            int partIndex
    ) {
        private boolean isBefore(@Nullable SweepCandidate other) {
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
    }

    private record ControlCandidate(
            SweepCandidate contact,
            ControlKind kind,
            @Nullable Pose3dc pose
    ) {
        private boolean isBefore(@Nullable ControlCandidate other) {
            return other == null || contact.isBefore(other.contact);
        }
    }

    private record ScanResult(
            @Nullable SweepCandidate blocker,
            @Nullable ControlCandidate target
    ) {
        private @NotNull ScanResult merge(@NotNull ScanResult other) {
            SweepCandidate mergedBlocker = blocker;
            if (other.blocker != null && other.blocker.isBefore(mergedBlocker)) {
                mergedBlocker = other.blocker;
            }
            ControlCandidate mergedTarget = target;
            if (other.target != null && other.target.isBefore(mergedTarget)) {
                mergedTarget = other.target;
            }
            return new ScanResult(mergedBlocker, mergedTarget);
        }
    }
}
