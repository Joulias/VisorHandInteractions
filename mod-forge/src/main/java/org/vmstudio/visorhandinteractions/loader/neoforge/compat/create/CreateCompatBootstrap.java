package org.vmstudio.visorhandinteractions.loader.neoforge.compat.create;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionBridge;
import org.vmstudio.visorhandinteractions.core.client.interaction.InteractionBridgeRegistry;
import org.vmstudio.visorhandinteractions.core.common.VisorHandInteractions;

/**
 * Loads the Create adapter only after NeoForge confirms that Create is present.
 *
 * <p>This class deliberately contains no references to Create classes. Keeping
 * the implementation behind reflection prevents class verification from making
 * Create a hard dependency of Visor Hand Interactions.</p>
 */
@EventBusSubscriber(modid = VisorHandInteractions.MOD_ID, value = Dist.CLIENT)
public final class CreateCompatBootstrap {
    private static final String CREATE_MOD_ID = "create";
    private static final String CONTRAPTION_BRIDGE_CLASS =
            "org.vmstudio.visorhandinteractions.loader.neoforge.compat.create."
                    + "CreateContraptionInteractionBridge";
    private static final String VALUE_SETTINGS_BRIDGE_CLASS =
            "org.vmstudio.visorhandinteractions.loader.neoforge.compat.create."
                    + "CreateValueSettingsInteractionBridge";

    private static boolean attempted;

    private CreateCompatBootstrap() {
        throw new UnsupportedOperationException("Utility class");
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (attempted) {
            return;
        }
        attempted = true;

        if (!ModList.get().isLoaded(CREATE_MOD_ID)) {
            return;
        }

        registerBridge(
                CONTRAPTION_BRIDGE_CLASS,
                "Create moving-contraption hand interactions"
        );
        registerBridge(
                VALUE_SETTINGS_BRIDGE_CLASS,
                "Create physical value-setting menus"
        );
    }

    private static void registerBridge(String className, String description) {
        try {
            Class<?> bridgeType = Class.forName(
                    className,
                    true,
                    CreateCompatBootstrap.class.getClassLoader()
            );
            InteractionBridge bridge = (InteractionBridge) bridgeType
                    .getDeclaredConstructor()
                    .newInstance();
            InteractionBridgeRegistry.register(bridge);
            VisorHandInteractions.LOGGER.info("Enabled {}", description);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            VisorHandInteractions.LOGGER.error(
                    "Create is installed, but {} could not load",
                    description,
                    exception
            );
        }
    }
}
