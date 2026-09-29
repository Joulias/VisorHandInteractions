package org.vmstudio.visorhandinteractions.loader.neoforge.compat.create.mixin;

import com.simibubi.create.foundation.utility.ControlsUtil;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.vmstudio.visorhandinteractions.loader.neoforge.compat.create.TrainControlsInputOverride;

/** Adds physical lever intent only to Create's train-control input poll. */
@Pseudo
@Mixin(
        targets = "com.simibubi.create.content.contraptions.actors.trainControls.ControlsHandler",
        remap = false
)
abstract class ControlsHandlerMixin {
    @Redirect(
            method = "tick()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/foundation/utility/ControlsUtil;"
                            + "isActuallyPressed(Lnet/minecraft/client/KeyMapping;)Z",
                    remap = false
            ),
            remap = false
    )
    private static boolean visorHandInteractions$includePhysicalTrainLever(
            KeyMapping mapping
    ) {
        return ControlsUtil.isActuallyPressed(mapping)
                || TrainControlsInputOverride.isVirtuallyPressed(mapping);
    }
}
