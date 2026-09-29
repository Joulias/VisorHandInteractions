package org.vmstudio.visorhandinteractions.core.client.pose;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.vmstudio.visor.api.common.HandType;

/**
 * Optional interaction-target capability for a control-specific visual grip.
 * Implementations may return a different anchor/frame every tick as a lever or
 * wheel animates.
 */
public interface GrabPoseProvider {
    @NotNull GrabPose grabPose(
            @NotNull LocalPlayer player,
            @NotNull HandType hand,
            @NotNull Vec3 trackedHandAnchor
    );
}
