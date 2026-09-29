package org.vmstudio.visorhandinteractions.loader.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.vmstudio.visorhandinteractions.core.client.AddonEntryClient;
import org.vmstudio.visorhandinteractions.core.common.VisorHandInteractions;

/** Releases native held controls when the client leaves a world. */
@EventBusSubscriber(modid = VisorHandInteractions.MOD_ID, value = Dist.CLIENT)
public final class ClientLifecycleEvents {
    private ClientLifecycleEvents() {
        throw new UnsupportedOperationException("Utility class");
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        AddonEntryClient.clearPhysicalInteractionState();
    }
}
