package org.vmstudio.visorhandinteractions.core.client.interaction.generic;

/** Regression checks for Create's physical-detent/sneak direction mapping. */
public final class ControlDirectionSelfTest {
    private ControlDirectionSelfTest() {
    }

    public static void main(String[] args) {
        assertMatch(1.0D, false, true, "forward without sneak");
        assertMatch(1.0D, true, false, "forward while sneaking");
        assertMatch(-1.0D, false, false, "reverse without sneak");
        assertMatch(-1.0D, true, true, "reverse while sneaking");
        assertMatch(0.0D, false, false, "zero travel");
        assertMatch(Double.NaN, false, false, "non-finite travel");

        // A forward-then-reverse oscillation in normal mode must dispatch
        // exactly once: the reverse detent is consumed but not activated.
        int actions = 0;
        double[] detents = {1.0D, -1.0D};
        for (double detent : detents) {
            if (ControlMotionMath.detentMatchesModifier(detent, false)) {
                actions++;
            }
        }
        if (actions != 1) {
            throw new AssertionError(
                    "normal-mode oscillation dispatched " + actions + " actions"
            );
        }

        System.out.println("Control direction checks passed.");
    }

    private static void assertMatch(
            double travel,
            boolean reverseModifier,
            boolean expected,
            String label
    ) {
        boolean actual = ControlMotionMath.detentMatchesModifier(
                travel,
                reverseModifier
        );
        if (actual != expected) {
            throw new AssertionError(
                    label + ": expected " + expected + ", got " + actual
            );
        }
    }
}
