package org.vmstudio.visorhandinteractions.loader.neoforge.compat.simulated;

import net.neoforged.fml.ModList;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionBridge;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionBridgeRegistry;
import org.vmstudio.visorhandinteractions.core.common.VisorHandInteractions;

/** Loads the optional Simulated compatibility layer without resolving it when absent. */
public final class SimulatedCompatBootstrap {
    private static final String BRIDGE_CLASS =
            "org.vmstudio.visorhandinteractions.loader.neoforge.compat.simulated."
                    + "SimulatedInteractionBridge";
    private static boolean attempted;

    private SimulatedCompatBootstrap() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static synchronized void registerIfPresent() {
        if (attempted) {
            return;
        }
        attempted = true;
        if (!ModList.get().isLoaded("simulated")) {
            return;
        }

        try {
            Class<?> bridgeType = Class.forName(
                    BRIDGE_CLASS,
                    true,
                    SimulatedCompatBootstrap.class.getClassLoader()
            );
            InteractionBridge bridge = (InteractionBridge) bridgeType
                    .getDeclaredConstructor()
                    .newInstance();
            InteractionBridgeRegistry.register(bridge);
            VisorHandInteractions.LOGGER.info(
                    "Enabled Simulated/Aeronautics continuous hand controls"
            );
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            VisorHandInteractions.LOGGER.error(
                    "Simulated is installed, but its hand-interaction bridge could not load",
                    exception
            );
        }
    }
}
