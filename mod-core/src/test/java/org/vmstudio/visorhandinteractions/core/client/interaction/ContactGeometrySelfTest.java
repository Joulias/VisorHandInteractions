package org.vmstudio.visorhandinteractions.core.client.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.vmstudio.visorhandinteractions.core.client.interaction.generic.ControlMotionMath;

/** Dependency-free regression checks for the swept-sphere contact kernel. */
public final class ContactGeometrySelfTest {
    private static final AABB UNIT_BOX = new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
    private static final BlockPos OWNER = BlockPos.ZERO;

    private ContactGeometrySelfTest() {
    }

    public static void main(String[] args) {
        distanceToBoxIsExact();
        fastSweepDoesNotTunnel();
        inflatedBoxCornerFalsePositiveIsRejected();
        stationaryTouchIsDetected();
        startingInsideIsDetected();
        signedControlAnglesAreStable();
        controlRotationMatchesItsSignedAngle();
        System.out.println("Contact geometry checks passed.");
    }

    private static void distanceToBoxIsExact() {
        requireClose(0.0D, ExactHandContactFinder.distanceSquared(
                new Vec3(0.5D, 0.5D, 0.5D), UNIT_BOX
        ));
        requireClose(0.25D, ExactHandContactFinder.distanceSquared(
                new Vec3(-0.5D, 0.5D, 0.5D), UNIT_BOX
        ));
        requireClose(0.5D, ExactHandContactFinder.distanceSquared(
                new Vec3(-0.5D, 1.5D, 0.5D), UNIT_BOX
        ));
    }

    private static void fastSweepDoesNotTunnel() {
        require(
                ExactHandContactFinder.sweepSphereAabb(
                        new Vec3(-4.0D, 0.5D, 0.5D),
                        new Vec3(4.0D, 0.5D, 0.5D),
                        0.05D,
                        UNIT_BOX,
                        OWNER,
                        0
                ) != null,
                "A fast hand sweep tunneled through a full block"
        );
    }

    private static void inflatedBoxCornerFalsePositiveIsRejected() {
        // This segment intersects box.inflate(0.11), but its true distance to
        // the top/north edge is sqrt(0.1^2 + 0.1^2), so a sphere must miss.
        require(
                ExactHandContactFinder.sweepSphereAabb(
                        new Vec3(-1.0D, 1.1D, 1.1D),
                        new Vec3(2.0D, 1.1D, 1.1D),
                        0.11D,
                        UNIT_BOX,
                        OWNER,
                        0
                ) == null,
                "Corner near-miss was treated as contact"
        );
    }

    private static void stationaryTouchIsDetected() {
        require(
                ExactHandContactFinder.sweepSphereAabb(
                        new Vec3(-0.08D, 0.5D, 0.5D),
                        new Vec3(-0.08D, 0.5D, 0.5D),
                        0.1D,
                        UNIT_BOX,
                        OWNER,
                        0
                ) != null,
                "A stationary touching sphere was not detected"
        );
    }

    private static void startingInsideIsDetected() {
        require(
                ExactHandContactFinder.sweepSphereAabb(
                        new Vec3(0.5D, 0.5D, 0.5D),
                        new Vec3(2.0D, 0.5D, 0.5D),
                        0.01D,
                        UNIT_BOX,
                        OWNER,
                        0
                ) != null,
                "A sweep beginning inside the shape was not detected"
        );
    }

    private static void signedControlAnglesAreStable() {
        Vec3 radial = new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 quarterTurn = new Vec3(0.0D, 0.0D, -1.0D);
        Vec3 axle = new Vec3(0.0D, 1.0D, 0.0D);
        requireClose(
                Math.PI * 0.5D,
                ControlMotionMath.signedAngle(radial, quarterTurn, axle)
        );
        requireClose(
                -Math.PI * 0.5D,
                ControlMotionMath.signedAngle(quarterTurn, radial, axle)
        );
        requireClose(
                0.0D,
                ControlMotionMath.signedAngle(Vec3.ZERO, quarterTurn, axle)
        );
    }

    private static void controlRotationMatchesItsSignedAngle() {
        Vec3 radial = new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 axle = new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 rotated = ControlMotionMath.rotateAroundAxis(
                radial,
                axle,
                Math.PI * 0.5D
        );
        requireVectorClose(new Vec3(0.0D, 0.0D, -1.0D), rotated);
        requireClose(
                Math.PI * 0.5D,
                ControlMotionMath.signedAngle(radial, rotated, axle)
        );
        requireVectorClose(
                new Vec3(1.0D, 0.0D, 3.0D),
                ControlMotionMath.projectOntoPlane(
                        new Vec3(1.0D, 2.0D, 3.0D),
                        axle
                )
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void requireClose(double expected, double actual) {
        if (Math.abs(expected - actual) > 1.0E-12D) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static void requireVectorClose(Vec3 expected, Vec3 actual) {
        requireClose(expected.x, actual.x);
        requireClose(expected.y, actual.y);
        requireClose(expected.z, actual.z);
    }
}
