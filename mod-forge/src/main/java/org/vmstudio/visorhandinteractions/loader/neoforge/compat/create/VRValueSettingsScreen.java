package org.vmstudio.visorhandinteractions.loader.neoforge.compat.create;

import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsScreen;
import net.minecraft.core.BlockPos;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Create's normal settings board with a VR-primary-click commit path. */
final class VRValueSettingsScreen extends ValueSettingsScreen {
    private static final int PRIMARY_MOUSE_BUTTON = 0;

    private final BooleanSupplier remainsValid;

    VRValueSettingsScreen(
            BlockPos pos,
            ValueSettingsBoard board,
            ValueSettingsBehaviour.ValueSettings initialSettings,
            Consumer<ValueSettingsBehaviour.ValueSettings> onHover,
            int netId,
            BooleanSupplier remainsValid
    ) {
        super(pos, board, initialSettings, onHover, netId);
        this.remainsValid = remainsValid;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button != PRIMARY_MOUSE_BUTTON) {
            return super.mouseReleased(mouseX, mouseY, button);
        }

        if (remainsValid.getAsBoolean()) {
            saveAndClose(mouseX, mouseY);
        } else {
            onClose();
        }
        return true;
    }
}
