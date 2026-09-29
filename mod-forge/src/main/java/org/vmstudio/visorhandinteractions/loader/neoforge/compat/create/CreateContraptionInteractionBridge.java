package org.vmstudio.visorhandinteractions.loader.neoforge.compat.create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionHandlerClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionBridge;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionTarget;
import org.vmstudio.visorhandinteractions.core.client.interaction.generic.GenericControlClassifier;
import org.vmstudio.visorhandinteractions.core.client.interaction.generic.GenericControlKind;

import java.util.List;
import java.util.Optional;

/** Native Create 6.0.x interaction support for blocks on assembled contraptions. */
public final class CreateContraptionInteractionBridge implements InteractionBridge {
    private static final double EPSILON = 1.0E-7D;
    private static final int MAX_SWEEP_SEGMENTS = 32;

    // findTouchTarget and findGrabTarget ask bridges with identical inputs back-to-back.
    private ClientLevel cachedLevel;
    private int cachedPlayerId = Integer.MIN_VALUE;
    private long cachedGameTime = Long.MIN_VALUE;
    private Vec3 cachedSweepStart;
    private Vec3 cachedSweepEnd;
    private double cachedRadius = Double.NaN;
    private Optional<InteractionTarget> cachedResult = Optional.empty();

    /**
     * Diameter directions used to approximate the hand's swept sphere. The
     * center sweep is traced separately; these probes cover faces and corners.
     */
    private static final List<Vec3> PROBE_AXES = List.of(
            new Vec3(1.0D, 0.0D, 0.0D),
            new Vec3(0.0D, 1.0D, 0.0D),
            new Vec3(0.0D, 0.0D, 1.0D),
            new Vec3(1.0D, 1.0D, 1.0D).normalize(),
            new Vec3(1.0D, 1.0D, -1.0D).normalize(),
            new Vec3(1.0D, -1.0D, 1.0D).normalize(),
            new Vec3(-1.0D, 1.0D, 1.0D).normalize()
    );

    @Override
    public @NotNull Optional<InteractionTarget> findTarget(
            @NotNull ClientLevel level,
            @NotNull LocalPlayer player,
            @NotNull Vec3 sweepStart,
            @NotNull Vec3 sweepEnd,
            double radius
    ) {
        double safeRadius = Math.max(0.015D, radius);
        long gameTime = level.getGameTime();
        if (level == cachedLevel
                && player.getId() == cachedPlayerId
                && gameTime == cachedGameTime
                && sweepStart.equals(cachedSweepStart)
                && sweepEnd.equals(cachedSweepEnd)
                && Double.compare(safeRadius, cachedRadius) == 0) {
            return cachedResult;
        }

        Candidate nearest = null;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof AbstractContraptionEntity contraption)
                    || !contraption.isAliveOrStale()
                    || contraption.getContraption() == null) {
                continue;
            }

            Candidate candidate = findOnContraption(
                    level,
                    player,
                    contraption,
                    sweepStart,
                    sweepEnd,
                    safeRadius
            );
            nearest = nearer(nearest, candidate);
        }

        Optional<InteractionTarget> result = nearest == null
                ? Optional.empty()
                : Optional.of(nearest.toTarget());
        cachedLevel = level;
        cachedPlayerId = player.getId();
        cachedGameTime = gameTime;
        cachedSweepStart = sweepStart;
        cachedSweepEnd = sweepEnd;
        cachedRadius = safeRadius;
        cachedResult = result;
        return result;
    }

    private Candidate findOnContraption(
            ClientLevel level,
            LocalPlayer player,
            AbstractContraptionEntity contraption,
            Vec3 sweepStart,
            Vec3 sweepEnd,
            double radius
    ) {
        Candidate nearest = null;
        Vec3 sweep = sweepEnd.subtract(sweepStart);
        double length = sweep.length();

        if (length > EPSILON) {
            nearest = trace(
                    level,
                    player,
                    contraption,
                    sweepStart,
                    sweepEnd,
                    sweepStart,
                    sweepStart,
                    sweepEnd
            );
        }

        double stepSize = Math.max(0.025D, radius * 0.75D);
        int segments = Math.max(1, (int) Math.ceil(length / stepSize));
        segments = Math.min(MAX_SWEEP_SEGMENTS, segments);

        for (int step = 0; step <= segments; step++) {
            double fraction = (double) step / (double) segments;
            Vec3 center = sweepStart.add(sweep.scale(fraction));

            for (Vec3 axis : PROBE_AXES) {
                Vec3 offset = axis.scale(radius);
                Candidate candidate = trace(
                        level,
                        player,
                        contraption,
                        center.subtract(offset),
                        center.add(offset),
                        center,
                        sweepStart,
                        sweepEnd
                );
                nearest = nearer(nearest, candidate);
            }
        }

        return nearest;
    }

    private Candidate trace(
            ClientLevel level,
            LocalPlayer player,
            AbstractContraptionEntity contraption,
            Vec3 rayStart,
            Vec3 rayEnd,
            Vec3 probeCenter,
            Vec3 sweepStart,
            Vec3 sweepEnd
    ) {
        BlockHitResult hit = ContraptionHandlerClient.rayTraceContraption(
                rayStart,
                rayEnd,
                contraption
        );
        if (hit == null) {
            return null;
        }

        BlockPos localPos = hit.getBlockPos();
        StructureTemplate.StructureBlockInfo blockInfo = contraption
                .getContraption()
                .getBlocks()
                .get(localPos);
        if (blockInfo == null
                || !contraption.canInteractWithBlock(player, localPos, 0.75D)) {
            return null;
        }

        Vec3 worldHit = contraption.toGlobalVector(hit.getLocation(), 1.0F);
        if (!isFinite(worldHit)
                || isOccluded(level, player, sweepStart, worldHit)
                || isOccluded(level, player, probeCenter, worldHit)) {
            return null;
        }

        double sweepFraction = projectOntoSweep(sweepStart, sweepEnd, worldHit);
        boolean allowTouch = isTouchControl(contraption, localPos, blockInfo.state());
        return new Candidate(
                contraption,
                localPos.immutable(),
                hit.getDirection(),
                hit.getLocation(),
                worldHit,
                sweepFraction,
                allowTouch,
                blockInfo.state(),
                worldHit.distanceToSqr(sweepEnd)
        );
    }

    private static boolean isTouchControl(
            AbstractContraptionEntity contraption,
            BlockPos localPos,
            BlockState state
    ) {
        Optional<GenericControlKind> kind = GenericControlClassifier.controlKind(state);
        if (kind.isPresent()) {
            return kind.get().touchSafe();
        }
        return state.getBlock() instanceof LeverBlock
                || state.getBlock() instanceof ButtonBlock
                || contraption.getContraption().getInteractors().containsKey(localPos);
    }

    private static boolean isOccluded(
            ClientLevel level,
            LocalPlayer player,
            Vec3 origin,
            Vec3 target
    ) {
        if (origin.distanceToSqr(target) <= EPSILON * EPSILON) {
            return false;
        }

        BlockHitResult worldHit = level.clip(new ClipContext(
                origin,
                target,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                player
        ));
        return worldHit.getType() != HitResult.Type.MISS
                && origin.distanceToSqr(worldHit.getLocation()) + EPSILON
                < origin.distanceToSqr(target);
    }

    private static double projectOntoSweep(Vec3 start, Vec3 end, Vec3 point) {
        Vec3 sweep = end.subtract(start);
        double lengthSquared = sweep.lengthSqr();
        if (lengthSquared <= EPSILON * EPSILON) {
            return 0.0D;
        }
        double fraction = point.subtract(start).dot(sweep) / lengthSquared;
        return Math.max(0.0D, Math.min(1.0D, fraction));
    }

    private static Candidate nearer(Candidate first, Candidate second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        if (second.sweepFraction() + EPSILON < first.sweepFraction()) {
            return second;
        }
        if (Math.abs(second.sweepFraction() - first.sweepFraction()) <= EPSILON
                && second.distanceToEndSquared() < first.distanceToEndSquared()) {
            return second;
        }
        return first;
    }

    private static boolean isFinite(Vec3 vector) {
        return Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }

    private record Candidate(
            AbstractContraptionEntity contraption,
            BlockPos localPos,
            Direction face,
            Vec3 localLocation,
            Vec3 worldLocation,
            double sweepFraction,
            boolean allowTouch,
            BlockState initialState,
            double distanceToEndSquared
    ) {
        private InteractionTarget toTarget() {
            return new HeldCreateContraptionTarget(
                    contraption,
                    localPos,
                    face,
                    localLocation,
                    worldLocation,
                    sweepFraction,
                    allowTouch,
                    initialState
            );
        }
    }
}
