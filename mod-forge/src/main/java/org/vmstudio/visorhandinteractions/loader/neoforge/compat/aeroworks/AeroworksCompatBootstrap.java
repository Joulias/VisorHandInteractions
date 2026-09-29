package org.vmstudio.visorhandinteractions.loader.neoforge.compat.aeroworks;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionBridge;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionBridgeRegistry;
import org.vmstudio.visorhandinteractions.core.common.VisorHandInteractions;

/** Optional, version-gated loader for Aeroworks 1.4.x joystick controls. */
@EventBusSubscriber(modid = VisorHandInteractions.MOD_ID, value = Dist.CLIENT)
public final class AeroworksCompatBootstrap {
    private static final String MOD_ID = "aeroworks";
    private static final String BRIDGE_CLASS =
            "org.vmstudio.visorhandinteractions.loader.neoforge.compat.aeroworks."
                    + "AeroworksJoystickInteractionBridge";

    private static boolean attempted;

    private AeroworksCompatBootstrap() {
        throw new UnsupportedOperationException("Utility class");
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        registerIfPresent();
    }

    public static synchronized void registerIfPresent() {
        if (attempted) {
            return;
        }
        attempted = true;
        if (!ModList.get().isLoaded(MOD_ID)) {
            return;
        }

        String version = ModList.get()
                .getModContainerById(MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("");
        if (!supportsVersion(version)) {
            VisorHandInteractions.LOGGER.warn(
                    "Aeroworks {} is installed; physical joystick grabbing "
                            + "currently targets the 1.4.x control API",
                    version
            );
            return;
        }

        try {
            Class<?> bridgeType = Class.forName(
                    BRIDGE_CLASS,
                    true,
                    AeroworksCompatBootstrap.class.getClassLoader()
            );
            InteractionBridge bridge = (InteractionBridge) bridgeType
                    .getDeclaredConstructor()
                    .newInstance();
            InteractionBridgeRegistry.register(bridge);
            VisorHandInteractions.LOGGER.info(
                    "Enabled Aeroworks {} physical joystick grabbing",
                    version
            );
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            VisorHandInteractions.LOGGER.error(
                    "Aeroworks is installed, but its joystick hand-interaction "
                            + "bridge could not load",
                    exception
            );
        }
    }

    static boolean supportsVersion(String version) {
        String[] parts = version.split("[.+-]", 3);
        if (parts.length < 2) {
            return false;
        }
        try {
            return Integer.parseInt(parts[0]) == 1
                    && Integer.parseInt(parts[1]) == 4;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
