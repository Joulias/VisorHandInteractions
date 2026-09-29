package org.vmstudio.visorhandinteractions.loader.neoforge.compat.aeroworks;

import com.mred231.aeroworks.content.joystick.JoystickBlockEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.vmstudio.visor.api.common.HandType;
import org.vmstudio.visorhandinteractions.core.client.interaction.BlockInteractionTarget;
import org.vmstudio.visorhandinteractions.core.client.interaction.ExactHandContactFinder;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionBridge;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionTarget;

import java.util.Optional;

/** Finds exact hand contact with the animated grip of a placed Aeroworks joystick. */
public final class AeroworksJoystickInteractionBridge implements InteractionBridge {
    private static final double EPSILON = 1.0E-7D;
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
        int minimumX = Mth.floor(Math.min(sweepStart.x, sweepEnd.x) - safeRadius) - 1;
        int minimumY = Mth.floor(Math.min(sweepStart.y, sweepEnd.y) - safeRadius) - 2;
        int minimumZ = Mth.floor(Math.min(sweepStart.z, sweepEnd.z) - safeRadius) - 1;
        int maximumX = Mth.floor(Math.max(sweepStart.x, sweepEnd.x) + safeRadius) + 1;
        int maximumY = Mth.floor(Math.max(sweepStart.y, sweepEnd.y) + safeRadius) + 2;
        int maximumZ = Mth.floor(Math.max(sweepStart.z, sweepEnd.z) + safeRadius) + 1;

        GripCandidate nearest = null;
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

                    BlockEntity blockEntity = level.getBlockEntity(mutable);
                    if (!(blockEntity instanceof JoystickBlockEntity joystick)) {
                        continue;
                    }
                    AeroworksJoystickGeometry.GripFrame frame =
                            AeroworksJoystickGeometry.gripFrame(joystick);
                    if (frame == null) {
                        continue;
                    }
                    AeroworksJoystickGeometry.LocalContact contact =
                            AeroworksJoystickGeometry.sweepGrip(
                                    frame,
                                    sweepStart,
                                    sweepEnd,
                                    safeRadius
                            );
                    if (contact == null) {
                        continue;
                    }

                    GripCandidate candidate = new GripCandidate(
                            mutable.immutable(),
                            contact.modelSurface(),
                            frame.toWorld(contact.modelSurface()),
                            contact.sweepT(),
                            contact.endDistanceSquared()
                    );
                    if (candidate.isBefore(nearest)) {
                        nearest = candidate;
                    }
                }
            }
        }

        Optional<BlockInteractionTarget> joystickBody = ExactHandContactFinder.findNearest(
                level,
                player,
                sweepStart,
                sweepEnd,
                safeRadius,
                (pos, state) -> level.getBlockEntity(pos)
                        instanceof JoystickBlockEntity
        );
        if (nearest == null) {
            return joystickBody.map(target -> player.isShiftKeyDown()
                    ? new AeroworksJoystickConfigTarget(
                            level,
                            target.blockPos(),
                            target.worldLocation(),
                            Math.max(
                                    0.0D,
                                    target.sweepFraction() - SPECIALIZED_TARGET_BIAS
                            )
                    )
                    : new JoystickBodySafetyTarget(
                            target.blockPos(),
                            target.worldLocation(),
                            Math.max(
                                    0.0D,
                                    target.sweepFraction() - SPECIALIZED_TARGET_BIAS
                            )
                    ));
        }

        if (joystickBody.isPresent()
                && joystickBody.get().sweepFraction() + EPSILON < nearest.sweepT) {
            BlockInteractionTarget target = joystickBody.get();
            return Optional.of(player.isShiftKeyDown()
                    ? new AeroworksJoystickConfigTarget(
                            level,
                            target.blockPos(),
                            target.worldLocation(),
                            Math.max(
                                    0.0D,
                                    target.sweepFraction() - SPECIALIZED_TARGET_BIAS
                            )
                    )
                    : new JoystickBodySafetyTarget(
                            target.blockPos(),
                            target.worldLocation(),
                            Math.max(
                                    0.0D,
                                    target.sweepFraction() - SPECIALIZED_TARGET_BIAS
                            )
                    ));
        }

        Optional<BlockInteractionTarget> blocker = ExactHandContactFinder.findNearest(
                level,
                player,
                sweepStart,
                sweepEnd,
                safeRadius,
                (pos, state) -> true
        );
        if (blocker.isPresent()
                && !nearest.blockPos.equals(blocker.get().blockPos())
                && blocker.get().sweepFraction() + EPSILON < nearest.sweepT) {
            return Optional.empty();
        }

        if (player.isShiftKeyDown()) {
            return Optional.of(new AeroworksJoystickConfigTarget(
                    level,
                    nearest.blockPos,
                    nearest.worldSurface,
                    Math.max(0.0D, nearest.sweepT - SPECIALIZED_TARGET_BIAS)
            ));
        }

        return Optional.of(new AeroworksJoystickTarget(
                level,
                nearest.blockPos,
                nearest.modelSurface,
                nearest.worldSurface,
                Math.max(0.0D, nearest.sweepT - SPECIALIZED_TARGET_BIAS)
        ));
    }

    private record GripCandidate(
            BlockPos blockPos,
            Vec3 modelSurface,
            Vec3 worldSurface,
            double sweepT,
            double endDistanceSquared
    ) {
        private boolean isBefore(@Nullable GripCandidate other) {
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
            if (Math.abs(endDistanceSquared - other.endDistanceSquared) > EPSILON) {
                return false;
            }
            return blockPos.asLong() < other.blockPos.asLong();
        }
    }

    /** Prevents namespace auto-touch from starting an unowned console session. */
    private record JoystickBodySafetyTarget(
            BlockPos blockPos,
            Vec3 worldLocation,
            double sweepFraction
    ) implements InteractionTarget {
        @Override
        public @NotNull Object key() {
            return blockPos;
        }

        @Override
        public boolean allowTouch() {
            return false;
        }

        @Override
        public boolean allowGrab() {
            return false;
        }

        @Override
        public boolean isBackedBy(@NotNull BlockPos worldBlockPos) {
            return blockPos.equals(worldBlockPos);
        }

        @Override
        public @NotNull InteractionResult touch(
                @NotNull LocalPlayer player,
                @NotNull HandType hand
        ) {
            return InteractionResult.PASS;
        }
    }
}
