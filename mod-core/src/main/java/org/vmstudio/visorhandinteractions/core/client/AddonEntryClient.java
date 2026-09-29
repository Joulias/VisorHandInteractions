package org.vmstudio.visorhandinteractions.core.client;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.visor.api.common.HandType;
import org.vmstudio.visor.api.common.addon.VisorAddon;
import org.vmstudio.visorhandinteractions.core.client.config.HandInteractionConfig;
import org.vmstudio.visorhandinteractions.core.client.input.GrabAction;
import org.vmstudio.visorhandinteractions.core.client.interaction.PhysicalInteractionTask;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionBridgeRegistry;
import org.vmstudio.visorhandinteractions.core.client.interaction.generic.GenericControlInteractionBridge;
import org.vmstudio.visorhandinteractions.core.common.VisorHandInteractions;

public final class AddonEntryClient implements VisorAddon {
    private static final GenericControlInteractionBridge GENERIC_CONTROL_BRIDGE =
            new GenericControlInteractionBridge();
    private static volatile PhysicalInteractionTask physicalTask;

    @Override
    public void onAddonLoad() {
        HandInteractionConfig config = HandInteractionConfig.getInstance();
        InteractionBridgeRegistry.register(GENERIC_CONTROL_BRIDGE);
        var registries = VisorAPI.addonManager().getRegistries();
        var gameActions = registries.actionSets().getComponent("game");
        if (gameActions == null) {
            VisorHandInteractions.LOGGER.error(
                    "Could not register grab controls because Visor's game action set is missing"
            );
            return;
        }

        GrabAction mainGrab = new GrabAction(gameActions, HandType.MAIN);
        GrabAction offhandGrab = new GrabAction(gameActions, HandType.OFFHAND);
        gameActions.addKeyAction(mainGrab);
        gameActions.addKeyAction(offhandGrab);

        PhysicalInteractionTask task = new PhysicalInteractionTask(
                this,
                config,
                mainGrab,
                offhandGrab
        );
        physicalTask = task;
        registries.tasks().registerComponent(task);
    }

    public static void clearPhysicalInteractionState() {
        PhysicalInteractionTask task = physicalTask;
        if (task != null) {
            task.clearForClientLifecycle();
        }
    }

    @Override
    public @NotNull String getAddonId() {
        return VisorHandInteractions.MOD_ID;
    }

    @Override
    public @NotNull Component getAddonName() {
        return Component.literal(VisorHandInteractions.MOD_NAME);
    }

    @Override
    public @NotNull String getModId() {
        return VisorHandInteractions.MOD_ID;
    }
}

