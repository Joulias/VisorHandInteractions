package org.vmstudio.visorhandinteractions.core.client.pose;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Lightweight regression checks for moving lever control frames. */
public final class GrabPoseFrameSelfTest {
    private static final double EPSILON = 1.0E-6D;

    private GrabPoseFrameSelfTest() {
    }

    public static void main(String[] args) {
        Vec3 hinge = new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 initialRod = new Vec3(0.0D, 0.0D, 1.0D);
        Vec3 pulledRod = new Vec3(1.0D, 0.0D, 0.0D);

        Matrix4f initialFrame = GrabPose.frameFromAxes(initialRod, hinge);
        Matrix4f pulledFrame = GrabPose.frameFromAxes(pulledRod, hinge);

        assertColumn(initialFrame, 2, initialRod, "initial rod axis");
        assertColumn(pulledFrame, 2, pulledRod, "pulled rod axis");
        assertColumn(initialFrame, 1, hinge, "initial hinge axis");
        assertColumn(pulledFrame, 1, hinge, "pulled hinge axis");
        assertNear(initialFrame.determinant3x3(), 1.0D, "initial handedness");
        assertNear(pulledFrame.determinant3x3(), 1.0D, "pulled handedness");

        Vector4f initialZ = initialFrame.getColumn(2, new Vector4f());
        Vector4f pulledZ = pulledFrame.getColumn(2, new Vector4f());
        if (distanceSquared(initialZ, pulledZ) < 0.5D) {
            throw new AssertionError("lever frame did not rotate with its rod");
        }

        testTickInterpolation();

        System.out.println("Grab pose frame and interpolation checks passed.");
    }

    private static void testTickInterpolation() {
        Vec3 axle = new Vec3(0.0D, 0.0D, 1.0D);
        GrabPose previous = GrabPose.attachedTo(
                new Vec3(0.0D, 0.0D, 0.0D),
                GrabPose.wheelFrame(axle, new Vec3(0.0D, 1.0D, 0.0D))
        );
        GrabPose current = GrabPose.attachedTo(
                new Vec3(8.0D, 4.0D, -2.0D),
                GrabPose.wheelFrame(axle, new Vec3(1.0D, 0.0D, 0.0D))
        );

        GrabPose midpoint = GrabPose.interpolate(previous, current, 0.5F);
        assertVec(midpoint.worldAnchor(), new Vec3(4.0D, 2.0D, -1.0D),
                "midpoint anchor");
        Matrix4f midpointFrame = midpoint.worldFrame();
        if (midpointFrame == null) {
            throw new AssertionError("midpoint unexpectedly lost its control frame");
        }
        double diagonal = Math.sqrt(0.5D);
        assertColumn(
                midpointFrame,
                1,
                new Vec3(diagonal, diagonal, 0.0D),
                "slerped wheel radial"
        );
        assertNear(midpointFrame.determinant3x3(), 1.0D,
                "slerped frame handedness");

        assertVec(
                GrabPose.interpolate(previous, current, -2.0F).worldAnchor(),
                previous.worldAnchor(),
                "lower partial-tick clamp"
        );
        assertVec(
                GrabPose.interpolate(previous, current, 3.0F).worldAnchor(),
                current.worldAnchor(),
                "upper partial-tick clamp"
        );

        GrabPose temporarilyUnframed = GrabPose.interpolate(
                previous,
                GrabPose.anchoredAt(current.worldAnchor()),
                0.5F
        );
        if (temporarilyUnframed.worldFrame() == null) {
            throw new AssertionError("a transient missing frame caused a visual pop");
        }
    }

    private static void assertVec(Vec3 actual, Vec3 expected, String label) {
        assertNear(actual.x, expected.x, label + " x");
        assertNear(actual.y, expected.y, label + " y");
        assertNear(actual.z, expected.z, label + " z");
    }

    private static void assertColumn(
            Matrix4f frame,
            int column,
            Vec3 expected,
            String label
    ) {
        Vector4f actual = frame.getColumn(column, new Vector4f());
        assertNear(actual.x, expected.x, label + " x");
        assertNear(actual.y, expected.y, label + " y");
        assertNear(actual.z, expected.z, label + " z");
        assertNear(actual.w, 0.0D, label + " w");
    }

    private static double distanceSquared(Vector4f first, Vector4f second) {
        double dx = first.x - second.x;
        double dy = first.y - second.y;
        double dz = first.z - second.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static void assertNear(double actual, double expected, String label) {
        if (Math.abs(actual - expected) > EPSILON) {
            throw new AssertionError(
                    label + ": expected " + expected + ", got " + actual
            );
        }
    }
}
