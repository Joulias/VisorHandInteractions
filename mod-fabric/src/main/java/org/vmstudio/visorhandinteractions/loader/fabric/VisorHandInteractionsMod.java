package org.vmstudio.visorhandinteractions.loader.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.visorhandinteractions.core.client.AddonEntryClient;

public final class VisorHandInteractionsMod implements ModInitializer {
    @Override
    public void onInitialize() {
        VisorAPI.registerAddon(new AddonEntryClient());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                AddonEntryClient.clearPhysicalInteractionState()
        );
    }
}

