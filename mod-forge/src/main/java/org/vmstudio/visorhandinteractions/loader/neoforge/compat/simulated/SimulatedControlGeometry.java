package org.vmstudio.visorhandinteractions.loader.neoforge.compat.simulated;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlock;
import dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity;
import dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerRenderer;
import dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlock;
import dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity;
import dev.simulated_team.simulated.index.SimBlockShapes;
import com.mojang.math.Axis;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import net.createmod.catnip.math.AngleHelper;

/** Exact control-space transforms shared by contact, dragging, and hand poses. */
final class SimulatedControlGeometry {
    static final double THROTTLE_ANGLE_SPAN_RADIANS = Math.toRadians(80.0D);
    static final double MINIMUM_RADIAL_LENGTH_SQUARED = 0.025D * 0.025D;

    private static final String THROTTLE_RENDERER_CLASS =
            "dev.simulated_team.simulated.content.blocks.throttle_lever."
                    + "ThrottleLeverRenderer";
    private static final Method THROTTLE_HANDLE_TRANSFORM =
            findThrottleHandleTransform();
    private static final Vec3 THROTTLE_CANONICAL_PIVOT =
            new Vec3(0.5D, 3.0D / 16.0D, 0.5D);
    private static final Vec3 ASSEMBLER_CANONICAL_PIVOT =
            new Vec3(0.5D, 7.0D / 16.0D, 0.5D);
    private static final Vec3 ASSEMBLER_CANONICAL_GRIP =
            new Vec3(0.5D, 16.5D / 16.0D, 0.5D);
    private static final VoxelShape ASSEMBLER_CANONICAL_LEVER =
            net.minecraft.world.phys.shapes.Shapes.or(
                    Block.box(7.0D, 8.0D, 7.0D, 9.0D, 15.0D, 9.0D),
                    Block.box(6.5D, 15.0D, 6.5D, 9.5D, 18.0D, 9.5D)
            );

    private SimulatedControlGeometry() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Uses the end-of-tick pose consumed by render interpolation. */
    static @NotNull ThrottleFrame throttleFrame(
            @NotNull ThrottleLeverBlockEntity blockEntity
    ) {
        return throttleFrame(blockEntity, 1.0F);
    }

    /**
     * Delegates to Simulated's public renderer transform so contact and hand
     * locking use the same interpolated clientAngle as the visible handle.
     * Reflection avoids a compile-time dependency on private renderer methods
     * whose signatures mention Flywheel classes not exposed by the bundle.
     */
    static @NotNull ThrottleFrame throttleFrame(
            @NotNull ThrottleLeverBlockEntity blockEntity,
            float partialTick
    ) {
        PoseStack poseStack = new PoseStack();
        invokeThrottleHandleTransform(blockEntity, partialTick, poseStack);

        Matrix4f transform = new Matrix4f(poseStack.last().pose());
        Matrix4f inverse = new Matrix4f(transform).invert();
        BlockPos pos = blockEntity.getBlockPos();
        Vec3 pivot = transformPosition(transform, THROTTLE_CANONICAL_PIVOT)
                .add(pos.getX(), pos.getY(), pos.getZ());
        Vec3 axis = transformDirection(transform, new Vec3(1.0D, 0.0D, 0.0D));
        if (axis.lengthSqr() > 1.0E-12D) {
            axis = axis.normalize();
        }
        return new ThrottleFrame(transform, inverse, pivot, axis);
    }

    static @NotNull VoxelShape canonicalThrottleHandleShape(
            @NotNull BlockState state
    ) {
        ThrottleLeverBlock lever = (ThrottleLeverBlock) state.getBlock();
        return lever.getHandleShape(lever.defaultBlockState());
    }

    static @NotNull AssemblerFrame assemblerFrame(
            @NotNull PhysicsAssemblerBlockEntity blockEntity
    ) {
        BlockState state = blockEntity.getBlockState();
        Direction facing = state.getValue(
                FaceAttachedHorizontalDirectionalBlock.FACING
        );
        AttachFace face = state.getValue(
                FaceAttachedHorizontalDirectionalBlock.FACE
        );
        float faceAngle = switch (face) {
            case FLOOR -> 0.0F;
            case WALL -> 90.0F;
            case CEILING -> 180.0F;
        };

        PoseStack poseStack = new PoseStack();
        poseStack.translate(0.5D, 0.5D, 0.5D);
        poseStack.mulPose(Axis.YP.rotationDegrees(AngleHelper.horizontalAngle(facing)));
        poseStack.mulPose(Axis.XP.rotationDegrees(faceAngle));
        poseStack.translate(-0.5D, -0.5D, -0.5D);
        poseStack.translate(
                ASSEMBLER_CANONICAL_PIVOT.x,
                ASSEMBLER_CANONICAL_PIVOT.y,
                ASSEMBLER_CANONICAL_PIVOT.z
        );
        poseStack.mulPose(Axis.XP.rotation(
                PhysicsAssemblerRenderer.getRenderAngle(blockEntity, 1.0F)
        ));
        poseStack.translate(
                -ASSEMBLER_CANONICAL_PIVOT.x,
                -ASSEMBLER_CANONICAL_PIVOT.y,
                -ASSEMBLER_CANONICAL_PIVOT.z
        );

        Matrix4f transform = new Matrix4f(poseStack.last().pose());
        Matrix4f inverse = new Matrix4f(transform).invert();
        BlockPos pos = blockEntity.getBlockPos();
        Vec3 pivot = transformPosition(transform, ASSEMBLER_CANONICAL_PIVOT)
                .add(pos.getX(), pos.getY(), pos.getZ());
        Vec3 grip = transformPosition(transform, ASSEMBLER_CANONICAL_GRIP)
                .add(pos.getX(), pos.getY(), pos.getZ());
        Vec3 hingeAxis = transformDirection(
                transform,
                new Vec3(1.0D, 0.0D, 0.0D)
        );
        if (hingeAxis.lengthSqr() > 1.0E-12D) {
            hingeAxis = hingeAxis.normalize();
        }
        return new AssemblerFrame(transform, inverse, pivot, grip, hingeAxis);
    }

    static @NotNull VoxelShape canonicalAssemblerLeverShape() {
        return ASSEMBLER_CANONICAL_LEVER;
    }

    static @NotNull Vec3 toCanonicalAssemblerPoint(
            @NotNull AssemblerFrame frame,
            @NotNull BlockPos blockPos,
            @NotNull Vec3 localLevelPoint
    ) {
        return transformPosition(
                frame.inverse,
                localLevelPoint.subtract(
                        blockPos.getX(),
                        blockPos.getY(),
                        blockPos.getZ()
                )
        );
    }

    static @NotNull Vec3 fromCanonicalAssemblerPoint(
            @NotNull AssemblerFrame frame,
            @NotNull BlockPos blockPos,
            @NotNull Vec3 canonicalPoint
    ) {
        return transformPosition(frame.transform, canonicalPoint)
                .add(blockPos.getX(), blockPos.getY(), blockPos.getZ());
    }

    static @NotNull Vec3 toCanonicalThrottlePoint(
            @NotNull ThrottleFrame frame,
            @NotNull BlockPos blockPos,
            @NotNull Vec3 localLevelPoint
    ) {
        return transformPosition(
                frame.inverse,
                localLevelPoint.subtract(
                        blockPos.getX(),
                        blockPos.getY(),
                        blockPos.getZ()
                )
        );
    }

    static @NotNull Vec3 fromCanonicalThrottlePoint(
            @NotNull ThrottleFrame frame,
            @NotNull BlockPos blockPos,
            @NotNull Vec3 canonicalPoint
    ) {
        return transformPosition(frame.transform, canonicalPoint)
                .add(blockPos.getX(), blockPos.getY(), blockPos.getZ());
    }

    static @NotNull Vec3 projectedRadial(
            @NotNull Vec3 point,
            @NotNull Vec3 center,
            @NotNull Vec3 axis
    ) {
        Vec3 fromCenter = point.subtract(center);
        return fromCenter.subtract(axis.scale(fromCenter.dot(axis)));
    }

    static double signedAngle(
            @NotNull Vec3 previousRadial,
            @NotNull Vec3 currentRadial,
            @NotNull Vec3 axis
    ) {
        Vec3 previous = previousRadial.normalize();
        Vec3 current = currentRadial.normalize();
        return Math.atan2(
                axis.dot(previous.cross(current)),
                previous.dot(current)
        );
    }

    static @NotNull WheelFrame wheelFrame(
            @NotNull BlockPos blockPos,
            @NotNull BlockState state
    ) {
        Direction facing = state.getValue(SteeringWheelBlock.FACING);
        VoxelShape wheelShape = state.getValue(SteeringWheelBlock.ON_FLOOR)
                ? SimBlockShapes.STEERING_WHEEL_FLOOR.get(facing)
                : SimBlockShapes.STEERING_WHEEL_CEILING.get(facing);
        AABB bounds = wheelShape.bounds().move(blockPos);
        Vec3 center = new Vec3(
                (bounds.minX + bounds.maxX) * 0.5D,
                (bounds.minY + bounds.maxY) * 0.5D,
                (bounds.minZ + bounds.maxZ) * 0.5D
        );
        return new WheelFrame(center, directionVector(facing), bounds);
    }

    private static @NotNull Method findThrottleHandleTransform() {
        try {
            Class<?> renderer = Class.forName(
                    THROTTLE_RENDERER_CLASS,
                    false,
                    SimulatedControlGeometry.class.getClassLoader()
            );
            return renderer.getMethod(
                    "transformHandleExternal",
                    ThrottleLeverBlockEntity.class,
                    float.class,
                    PoseStack.class
            );
        } catch (ClassNotFoundException | NoSuchMethodException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void invokeThrottleHandleTransform(
            ThrottleLeverBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack
    ) {
        try {
            THROTTLE_HANDLE_TRANSFORM.invoke(
                    null,
                    blockEntity,
                    partialTick,
                    poseStack
            );
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(
                    "Cannot access Simulated's public throttle transform",
                    exception
            );
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                    "Simulated's throttle transform failed",
                    cause
            );
        }
    }

    private static @NotNull Vec3 directionVector(@NotNull Direction direction) {
        return new Vec3(
                direction.getStepX(),
                direction.getStepY(),
                direction.getStepZ()
        );
    }

    private static @NotNull Vec3 transformPosition(
            @NotNull Matrix4f matrix,
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
            @NotNull Matrix4f matrix,
            @NotNull Vec3 direction
    ) {
        Vector3f result = matrix.transformDirection(new Vector3f(
                (float) direction.x,
                (float) direction.y,
                (float) direction.z
        ));
        return new Vec3(result.x, result.y, result.z);
    }

    record ThrottleFrame(
            Matrix4f transform,
            Matrix4f inverse,
            Vec3 pivot,
            Vec3 axis
    ) {
    }

    record AssemblerFrame(
            Matrix4f transform,
            Matrix4f inverse,
            Vec3 pivot,
            Vec3 grip,
            Vec3 hingeAxis
    ) {
    }

    record WheelFrame(Vec3 center, Vec3 axis, AABB bounds) {
    }
}
