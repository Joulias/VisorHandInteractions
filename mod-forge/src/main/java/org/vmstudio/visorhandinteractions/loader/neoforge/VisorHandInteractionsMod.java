package org.vmstudio.visorhandinteractions.loader.neoforge;

import net.neoforged.fml.common.Mod;
import org.vmstudio.visor.api.ModLoader;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.visorhandinteractions.core.client.AddonEntryClient;
import org.vmstudio.visorhandinteractions.core.common.VisorHandInteractions;
import org.vmstudio.visorhandinteractions.loader.neoforge.compat.simulated.SimulatedCompatBootstrap;

@Mod(VisorHandInteractions.MOD_ID)
public final class VisorHandInteractionsMod {
    public VisorHandInteractionsMod() {
        if (!ModLoader.get().isDedicatedServer()) {
            VisorAPI.registerAddon(new AddonEntryClient());
            SimulatedCompatBootstrap.registerIfPresent();
        }
    }
}

