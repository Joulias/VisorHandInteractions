package org.vmstudio.visorhandinteractions.loader.neoforge.compat.create;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsInputHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.vmstudio.visor.api.common.HandType;
import org.vmstudio.visorhandinteractions.core.client.interaction.BlockInteractionTarget;
import org.vmstudio.visorhandinteractions.core.client.interaction.ExactHandContactFinder;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionBridge;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionTarget;

import java.util.Optional;

/**
 * Opens Create's value-setting board from an exact physical hand contact.
 *
 * <p>The vanilla Create input path keeps both the use key and Minecraft's
 * camera-derived block hit alive for several ticks. A tracked controller
 * contact has neither of those properties, so this adapter performs Create's
 * public validation itself and opens the same settings screen directly.</p>
 */
public final class CreateValueSettingsInteractionBridge implements InteractionBridge {
    private static final double EPSILON = 1.0E-7D;
    private static final double CONTACT_EPSILON = 1.0E-5D;
    private static final double SPECIALIZED_TARGET_BIAS = 5.0E-7D;
    private static final int BLOCK_SCAN_PADDING = 1;
    private static final ResourceLocation CREATE_CLIPBOARD =
            ResourceLocation.fromNamespaceAndPath("create", "clipboard");

    // Touch and grab resolution query every bridge with identical inputs.
    private ClientLevel cachedLevel;
    private int cachedPlayerId = Integer.MIN_VALUE;
    private long cachedGameTime = Long.MIN_VALUE;
    private Vec3 cachedSweepStart;
    private Vec3 cachedSweepEnd;
    private double cachedRadius = Double.NaN;
    private Optional<InteractionTarget> cachedResult = Optional.empty();

    @Override
    public @NotNull Optional<InteractionTarget> findTarget(
            @NotNull ClientLevel level,
            @NotNull LocalPlayer player,
            @NotNull Vec3 sweepStart,
            @NotNull Vec3 sweepEnd,
            double radius
    ) {
        double safeRadius = Math.max(0.0D, radius);
        long gameTime = level.getGameTime();
        if (level == cachedLevel
                && player.getId() == cachedPlayerId
                && gameTime == cachedGameTime
                && sweepStart.equals(cachedSweepStart)
                && sweepEnd.equals(cachedSweepEnd)
                && Double.compare(safeRadius, cachedRadius) == 0) {
            return cachedResult;
        }

        Optional<InteractionTarget> result = findUncached(
                level,
                player,
                sweepStart,
                sweepEnd,
                safeRadius
        );
        cachedLevel = level;
        cachedPlayerId = player.getId();
        cachedGameTime = gameTime;
        cachedSweepStart = sweepStart;
        cachedSweepEnd = sweepEnd;
        cachedRadius = safeRadius;
        cachedResult = result;
        return result;
    }

    private static @NotNull Optional<InteractionTarget> findUncached(
            ClientLevel level,
            LocalPlayer player,
            Vec3 sweepStart,
            Vec3 sweepEnd,
            double radius
    ) {
        if (!canUseValueSettings(player)) {
            return Optional.empty();
        }

        Candidate nearest = null;
        int minimumX = Mth.floor(Math.min(sweepStart.x, sweepEnd.x) - radius)
                - BLOCK_SCAN_PADDING;
        int minimumY = Mth.floor(Math.min(sweepStart.y, sweepEnd.y) - radius)
                - BLOCK_SCAN_PADDING;
        int minimumZ = Mth.floor(Math.min(sweepStart.z, sweepEnd.z) - radius)
                - BLOCK_SCAN_PADDING;
        int maximumX = Mth.floor(Math.max(sweepStart.x, sweepEnd.x) + radius)
                + BLOCK_SCAN_PADDING;
        int maximumY = Mth.floor(Math.max(sweepStart.y, sweepEnd.y) + radius)
                + BLOCK_SCAN_PADDING;
        int maximumZ = Mth.floor(Math.max(sweepStart.z, sweepEnd.z) + radius)
                + BLOCK_SCAN_PADDING;

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
                    if (!(blockEntity instanceof SmartBlockEntity smartBlockEntity)
                            || !isPlacedBlockEntity(level, smartBlockEntity)
                            || !canUseBlock(level, player, mutable)) {
                        continue;
                    }

                    BlockPos blockPos = mutable.immutable();
                    for (BlockEntityBehaviour blockBehaviour
                            : smartBlockEntity.getAllBehaviours()) {
                        if (!(blockBehaviour instanceof ValueSettingsBehaviour behaviour)
                                || !canUseBehaviour(player, behaviour, null)) {
                            continue;
                        }

                        Candidate candidate = findBehaviourContact(
                                level,
                                smartBlockEntity,
                                behaviour,
                                blockPos,
                                sweepStart,
                                sweepEnd,
                                radius
                        );
                        nearest = nearer(nearest, candidate);
                    }
                }
            }
        }

        if (nearest != null) {
            Candidate selected = nearest;
            double reportedFraction = selected.sweepFraction();
            Optional<BlockInteractionTarget> ownerContact =
                    ExactHandContactFinder.findNearest(
                            level,
                            player,
                            sweepStart,
                            sweepEnd,
                            radius,
                            (pos, state) -> pos.equals(selected.blockPos())
                    );
            if (ownerContact.isPresent()) {
                reportedFraction = Math.min(
                        reportedFraction,
                        ownerContact.get().sweepFraction()
                );
            }
            reportedFraction = Math.max(
                    0.0D,
                    reportedFraction - SPECIALIZED_TARGET_BIAS
            );
            return Optional.of(selected.toTarget(reportedFraction));
        }

        // Create-family namespaces are auto-touch enabled by the core addon.
        // Claim an eligible settings block at its outline so touching the base
        // cannot invoke a short interaction before grip reaches its value box.
        return ExactHandContactFinder.findNearest(
                level,
                player,
                sweepStart,
                sweepEnd,
                radius,
                (pos, state) -> hasEligibleValueSettings(level, player, pos)
        ).map(SettingsBlockSafetyTarget::new);
    }

    private static @Nullable Candidate findBehaviourContact(
            ClientLevel level,
            SmartBlockEntity blockEntity,
            ValueSettingsBehaviour behaviour,
            BlockPos blockPos,
            Vec3 sweepStart,
            Vec3 sweepEnd,
            double radius
    ) {
        ValueBoxTransform transform = behaviour.getSlotPositioning();
        if (transform == null) {
            return null;
        }

        if (transform instanceof ValueBoxTransform.Sided sided) {
            Direction originalSide = sided.getSide();
            Candidate nearest = null;
            try {
                for (Direction side : Direction.values()) {
                    sided.fromSide(side);
                    Candidate candidate = testTransformContact(
                            level,
                            blockEntity,
                            behaviour,
                            transform,
                            blockPos,
                            side,
                            sweepStart,
                            sweepEnd,
                            radius
                    );
                    nearest = nearer(nearest, candidate);
                }
            } finally {
                sided.fromSide(originalSide);
            }
            return nearest;
        }

        return testTransformContact(
                level,
                blockEntity,
                behaviour,
                transform,
                blockPos,
                inferHitFace(
                        blockPos,
                        transform,
                        level,
                        blockEntity.getBlockState(),
                        sweepStart,
                        sweepEnd
                ),
                sweepStart,
                sweepEnd,
                radius
        );
    }

    private static @Nullable Candidate testTransformContact(
            ClientLevel level,
            SmartBlockEntity blockEntity,
            ValueSettingsBehaviour behaviour,
            ValueBoxTransform transform,
            BlockPos blockPos,
            Direction side,
            Vec3 sweepStart,
            Vec3 sweepEnd,
            double handRadius
    ) {
        BlockState state = blockEntity.getBlockState();
        Vec3 localCenter = transform.getLocalOffset(level, blockPos, state);
        if (localCenter == null || !isFinite(localCenter)) {
            return null;
        }

        Vec3 center = Vec3.atLowerCornerOf(blockPos).add(localCenter);
        double valueBoxRadius = Math.max(0.0D, transform.getScale() * 0.5D);
        double sweepFraction = firstSphereContact(
                sweepStart,
                sweepEnd,
                center,
                handRadius + valueBoxRadius
        );
        if (!Double.isFinite(sweepFraction)) {
            return null;
        }

        Vec3 handCenter = sweepStart.add(
                sweepEnd.subtract(sweepStart).scale(sweepFraction)
        );
        Vec3 towardValueBox = center.subtract(handCenter);
        double distance = towardValueBox.length();
        Vec3 exactHit;
        if (distance <= handRadius + CONTACT_EPSILON || distance <= EPSILON) {
            exactHit = center;
        } else {
            double contactReach = Math.min(
                    distance,
                    handRadius + CONTACT_EPSILON
            );
            exactHit = handCenter.add(towardValueBox.scale(contactReach / distance));
        }

        // Do not infer support from the transform alone. This is Create's own
        // final hit predicate, including sided activation and custom overrides.
        if (!behaviour.testHit(exactHit)) {
            return null;
        }

        return new Candidate(
                blockEntity,
                behaviour,
                blockPos,
                new BlockHitResult(exactHit, side, blockPos, false),
                center,
                sweepFraction,
                center.distanceToSqr(sweepEnd)
        );
    }

    private static double firstSphereContact(
            Vec3 sweepStart,
            Vec3 sweepEnd,
            Vec3 center,
            double combinedRadius
    ) {
        Vec3 movement = sweepEnd.subtract(sweepStart);
        Vec3 offset = sweepStart.subtract(center);
        double radiusSquared = combinedRadius * combinedRadius;
        double c = offset.lengthSqr() - radiusSquared;
        if (c <= EPSILON) {
            return 0.0D;
        }

        double a = movement.lengthSqr();
        if (a <= EPSILON * EPSILON) {
            return Double.NaN;
        }
        double b = offset.dot(movement);
        double discriminant = b * b - a * c;
        if (discriminant < 0.0D) {
            return Double.NaN;
        }

        double fraction = (-b - Math.sqrt(discriminant)) / a;
        if (fraction < -EPSILON || fraction > 1.0D + EPSILON) {
            return Double.NaN;
        }
        return Mth.clamp(fraction, 0.0D, 1.0D);
    }

    private static @NotNull Direction inferHitFace(
            BlockPos blockPos,
            ValueBoxTransform transform,
            ClientLevel level,
            BlockState state,
            Vec3 sweepStart,
            Vec3 sweepEnd
    ) {
        Vec3 localCenter = transform.getLocalOffset(level, blockPos, state);
        if (localCenter != null) {
            Vec3 fromBlockCenter = localCenter.subtract(0.5D, 0.5D, 0.5D);
            if (fromBlockCenter.lengthSqr() > EPSILON * EPSILON) {
                return Direction.getNearest(
                        fromBlockCenter.x,
                        fromBlockCenter.y,
                        fromBlockCenter.z
                );
            }
        }

        Vec3 movement = sweepEnd.subtract(sweepStart);
        if (movement.lengthSqr() > EPSILON * EPSILON) {
            return Direction.getNearest(-movement.x, -movement.y, -movement.z);
        }
        return Direction.UP;
    }

    private static boolean hasEligibleValueSettings(
            ClientLevel level,
            LocalPlayer player,
            BlockPos pos
    ) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof SmartBlockEntity smartBlockEntity)
                || !isPlacedBlockEntity(level, smartBlockEntity)
                || !canUseBlock(level, player, pos)) {
            return false;
        }
        for (BlockEntityBehaviour blockBehaviour : smartBlockEntity.getAllBehaviours()) {
            if (blockBehaviour instanceof ValueSettingsBehaviour behaviour
                    && canUseBehaviour(player, behaviour, null)) {
                return true;
            }
        }
        return false;
    }

    static boolean canOpen(
            ClientLevel level,
            LocalPlayer player,
            SmartBlockEntity blockEntity,
            ValueSettingsBehaviour behaviour,
            HandType hand,
            Direction side,
            Vec3 hitLocation
    ) {
        if (!isPlacedBlockEntity(level, blockEntity)
                || !canUseValueSettings(player)
                || !canUseBlock(level, player, blockEntity.getBlockPos())
                || !canUseBehaviour(player, behaviour, hand)
                || !containsIdentity(blockEntity, behaviour)) {
            return false;
        }

        ValueBoxTransform transform = behaviour.getSlotPositioning();
        if (transform == null) {
            return false;
        }
        if (transform instanceof ValueBoxTransform.Sided sided) {
            sided.fromSide(side);
        }
        return behaviour.testHit(hitLocation);
    }

    static boolean remainsValid(
            ClientLevel level,
            LocalPlayer player,
            SmartBlockEntity blockEntity,
            ValueSettingsBehaviour behaviour
    ) {
        return isPlacedBlockEntity(level, blockEntity)
                && canUseBlock(level, player, blockEntity.getBlockPos())
                && containsIdentity(blockEntity, behaviour)
                && behaviour.isActive()
                && behaviour.acceptsValueSettings()
                && behaviour.mayInteract(player);
    }

    private static boolean containsIdentity(
            SmartBlockEntity blockEntity,
            ValueSettingsBehaviour behaviour
    ) {
        for (BlockEntityBehaviour current : blockEntity.getAllBehaviours()) {
            if (current == behaviour) {
                return true;
            }
        }
        return false;
    }

    private static boolean canUseValueSettings(LocalPlayer player) {
        return ValueSettingsInputHandler.canInteract(player)
                && !BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem())
                        .equals(CREATE_CLIPBOARD);
    }

    private static boolean canUseBehaviour(
            LocalPlayer player,
            ValueSettingsBehaviour behaviour,
            @Nullable HandType hand
    ) {
        if (behaviour.bypassesInput(player.getMainHandItem())
                || !behaviour.mayInteract(player)
                || !behaviour.isActive()
                || !behaviour.acceptsValueSettings()) {
            return false;
        }
        if (!behaviour.onlyVisibleWithWrench()) {
            return true;
        }
        if (hand != null) {
            return player.getItemInHand(hand.asInteractionHand())
                    .is(Tags.Items.TOOLS_WRENCH);
        }
        return player.getMainHandItem().is(Tags.Items.TOOLS_WRENCH)
                || player.getOffhandItem().is(Tags.Items.TOOLS_WRENCH);
    }

    private static boolean canUseBlock(
            ClientLevel level,
            LocalPlayer player,
            BlockPos pos
    ) {
        return level.getWorldBorder().isWithinBounds(pos)
                && level.mayInteract(player, pos)
                && player.canInteractWithBlock(pos, 0.0D);
    }

    private static boolean isPlacedBlockEntity(
            ClientLevel level,
            SmartBlockEntity blockEntity
    ) {
        BlockPos pos = blockEntity.getBlockPos();
        return !blockEntity.isRemoved()
                && !blockEntity.isVirtual()
                && blockEntity.getLevel() == level
                && level.isLoaded(pos)
                && level.getBlockEntity(pos) == blockEntity;
    }

    private static @Nullable Candidate nearer(
            @Nullable Candidate first,
            @Nullable Candidate second
    ) {
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
            SmartBlockEntity blockEntity,
            ValueSettingsBehaviour behaviour,
            BlockPos blockPos,
            BlockHitResult hitResult,
            Vec3 valueBoxCenter,
            double sweepFraction,
            double distanceToEndSquared
    ) {
        private InteractionTarget toTarget(double reportedFraction) {
            return new CreateValueSettingsTarget(
                    blockEntity,
                    behaviour,
                    hitResult,
                    valueBoxCenter,
                    reportedFraction
            );
        }
    }

    /** A non-interactive claim which suppresses the generic namespace use. */
    private record SettingsBlockSafetyTarget(
            BlockInteractionTarget backingTarget
    ) implements InteractionTarget {
        @Override
        public @NotNull Object key() {
            return new SafetyKey(backingTarget.blockPos());
        }

        @Override
        public @NotNull Vec3 worldLocation() {
            return backingTarget.worldLocation();
        }

        @Override
        public double sweepFraction() {
            return backingTarget.sweepFraction();
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
            return backingTarget.isBackedBy(worldBlockPos);
        }

        @Override
        public @NotNull InteractionResult touch(
                @NotNull LocalPlayer player,
                @NotNull HandType hand
        ) {
            return InteractionResult.PASS;
        }
    }

    private record SafetyKey(BlockPos blockPos) {
    }
}
